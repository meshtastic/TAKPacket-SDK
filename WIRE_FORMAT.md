# TAKPacket v2 Wire Format Specification

**Version:** 1.2 (current as of SDK v0.5.1 — wire format unchanged since v0.4.0; v0.5.0 added the `CotMeshSanitizer` pre-parse helper and v0.5.1 corrected the reconstructed contact endpoint, neither of which affects the wire)
**Status:** Stable
**Date:** May 2026

## 1. Overview

The TAKPacket v2 wire format defines how ATAK Cursor-on-Target (CoT) messages are encoded for transmission over Meshtastic LoRa mesh networks. The format compresses CoT data from its native XML representation (400-2300 bytes) down to a median of ~87 bytes using a two-stage pipeline: protobuf structural encoding (12 typed payload variants plus an implicit PLI) followed by zstd dictionary compression.

The v2 protocol moves all compression logic from device firmware to client applications (Android, iOS, ATAK plugin), allowing the use of large pre-trained dictionaries and more expensive compression algorithms than the constrained ESP32 firmware could support.

## 2. Wire Format Layout

```
+----------+---------------------------------------------+
| Byte 0   | Bytes 1..N                                  |
| Flags    | Payload                                     |
+----------+---------------------------------------------+
```

| Offset | Size | Field | Description |
|--------|------|-------|-------------|
| 0 | 1 byte | Flags | Dictionary ID and version bits |
| 1 | 1..224 bytes | Payload | zstd-compressed TAKPacketV2 protobuf body, **with the 4-byte zstd magic number stripped** (or raw protobuf if flags=0xFF) |

Total wire size MUST NOT exceed **225 bytes**.

Three limits are involved, and the tightest one wins:

| Limit | Bytes | Where it comes from |
|-------|-------|---------------------|
| Firmware ceiling for `Data.payload` | 233 | `mesh.options` (`DATA_PAYLOAD_LEN`) |
| Port-78 payload Android will send | 228 | `sendData` checks the whole encoded `Data` message (port number, payload and their protobuf framing) against 233 |
| What both clients cap a TAK payload at | **225** | Meshtastic-Android and Meshtastic-Apple |

Earlier versions of this document gave 237 bytes. No frame that size can be sent.

Those three apply to every channel. A channel's own configuration can tighten the ceiling below
225, because the firmware checks the whole encoded `Data` message against what the radio can carry
(`255 - 16 B mesh header`) and refuses an oversized one with `TOO_LARGE` before it reaches the air:

| Channel configuration | Payload bytes | What it spends |
|-----------------------|---------------|----------------|
| Plain | 225 (the client cap still binds) | 5 B `Data` framing, 2 B `bitfield` |
| AES-CCM authenticated | **220** | the above plus the 12 B authentication tag |
| XEdDSA signed | **166** | the above plus a 64 B signature and its framing |
| Authenticated and signed | **154** | both |

Signing is the one that cannot be discovered by trial: over the limit the firmware sends the packet
**unsigned** instead, and a receiver in Strict signature mode then drops it. A sender that takes the
225-byte convention on an authenticated channel simply loses every full frame, which looks from the
far end like a radio fault. Senders should derive the budget from the channel rather than assume
one; the Kotlin SDK exposes `TakFragment.maxFrameBytes()` for exactly this.

The 2 B `bitfield` is only spent when the firmware composes the `Data` message, which it does
whenever it encrypts. A sender that builds and encrypts `Data` itself and hands over the ciphertext
keeps those two bytes.

## 3. Flags Byte

```
  Bit 7   Bit 6   Bit 5   Bit 4   Bit 3   Bit 2   Bit 1   Bit 0
+-------+-------+-------+-------+-------+-------+-------+-------+
| Rsvd  | Rsvd  |          Dictionary ID (6 bits)                |
+-------+-------+-------+-------+-------+-------+-------+-------+
```

### Dictionary IDs

| ID | Name | Dictionary Size | Usage |
|----|------|-----------------|-------|
| `0x00` | Non-aircraft | 524,288 bytes | PLI, GeoChat, ground units, shapes, markers, routes, ranging, alerts, casevac, emergency, task, TAKTALK, delete events |
| `0x01` | Aircraft | 4,096 bytes | ADS-B tracks, military air, helicopters |
| `0x02`-`0x3C` | Reserved | — | Reserved for future dictionary types |
| `0x3D`, `0x3E` | Never assigned | — | `0xFD` and `0xFE` mask to these with `& 0x3F`, so they must never name a dictionary |
| `0xFD` | Repair request | — | Reserved for on-demand fragment repair; not sent by this version (see below) |
| `0xFE` | Fragment | — | One fragment of a message too large for one frame (see below) |
| `0xFF` | Uncompressed | — | Payload is raw TAKPacketV2 protobuf (no zstd compression) |

Receivers MUST test for `0xFF`, `0xFE` and `0xFD` **before** masking with `& 0x3F`.

### Reserved Bits

Bits 6-7 are reserved for future use.

- **Senders** MUST set reserved bits to `0`.
- **Receivers** SHOULD ignore reserved bits (mask with `& 0x3F` to extract dictionary ID).

### Special Value: 0xFF (Uncompressed)

When the flags byte is `0xFF`, the payload is a raw (uncompressed) TAKPacketV2 protobuf. This is used by TAK_TRACKER firmware devices that cannot perform zstd compression, and is also emitted by the SDK encoder's **skip-compress** path: when the raw protobuf is no larger than the compressed body, the encoder emits `[0xFF][raw protobuf]` instead of a compressed frame, so tiny packets never expand on the wire. Receivers detect this value before attempting decompression.

### Special Values: 0xFE (Fragment) and 0xFD (Repair Request)

A wire payload (`[flags][body]`) too large for one frame, or whose protobuf exceeds the 4,096-byte
single-frame cap, can be sent as a set of fragment frames when the sender opts in. Each fragment
starts with a 7-byte header:

```
[0xFE][msg_id: u32 little-endian][code:3 | K-1:5][esi][symbol data]
```

| Field | Size | Meaning |
|-------|------|---------|
| `0xFE` | 1 B | Fragment marker |
| `msg_id` | 4 B | Random message ID; receivers key reassembly on it alone, not on the mesh packet's sender |
| `code` | high 3 bits | `0` = systematic Cauchy erasure code over GF(256); `1`-`7` reserved, and receivers drop unknown codes |
| `K-1` | low 5 bits | Number of source fragments K, minus 1 (K = 1..32) |
| `esi` | 1 B | Encoding symbol ID: `0..K-1` are source fragments, `K..255` repair fragments |

The fragments carry `wire payload || CRC32 || 0x80`, cut into slices, plus repair symbols so that
any K distinct fragments rebuild the message. A rebuilt payload is decoded as in §9 with a
decompression cap of 32,768 bytes instead of 4,096.

The slice length is not carried in the header and is not implied by the frame size. A sender
chooses it, and spreads the message evenly over its frames rather than filling them, so a slice is
usually well short of what the frame could hold. A receiver MUST therefore learn the length from
the frames instead of assuming one:

- source fragments `esi = 0..K-2` and every repair fragment are exactly one slice long, so the
  first of those to arrive reveals the length;
- the last source fragment, `esi = K-1`, is `1..slice` bytes and reveals nothing;
- repair symbols are computed over source slices zero-padded to the slice length, so a decoder
  must pad the last slice before using it to rebuild a missing fragment, and trim the rebuilt
  message at the `0x80` delimiter afterwards.

A receiver that hardcodes a slice length, or derives one from its own frame budget, will reject
valid bursts from a sender configured differently. `0xFD` is reserved for repair requests and is
not sent by this version; receivers ignore it. The full specification is
`docs/design/large-payload-chunking.md` §5-§6.

Receivers without fragment support see an unknown dictionary (`0x3E` or `0x3D` after masking) and
drop the frame, as §10 requires.

## 4. Payload

### Compressed Payload (flags != 0xFF)

The payload is a zstd-compressed frame body containing a serialized `TAKPacketV2` protobuf message. The frame is compressed using a **pre-trained dictionary** identified by the dictionary ID in the flags byte. The 4-byte zstd magic number is **stripped** before transmission and **re-prepended** by the receiver before decompression (see "Magic-number handling" below).

**Compression parameters:**
- Algorithm: Zstandard (zstd)
- Compression level: 19 requested (Kotlin's pure-Kotlin encoder, `org.meshtastic:kzstd`, treats `level` as a documented no-op — it uses a single fixed strategy regardless of the value passed; other bindings' libzstd encoders do honor 19 as maximum)
- Dictionary: Pre-trained on serialized protobuf bytes, embedded in client applications
- Frame fields: `dictID`, `contentSize`, and `checksum` all **disabled** — the dictionary ID already travels in the flags byte, and content-size / checksum are dead weight on these tiny dict-compressed payloads
- Magic number: the 4-byte zstd magic (`28 B5 2F FD`) is stripped on encode and re-prepended on decode (saves ~8 bytes/packet; the body on the wire has **no** magic number)

**Magic-number handling:** Because the SDK is both ends of the link and identifies the frame from its own 1-byte flags prefix, the zstd magic is pure overhead. It is stripped in application code in **all five language bindings** (rather than via a binding's native "magicless" format flag, which TypeScript's zstd-napi cannot set) so the wire bytes stay byte-identical across platforms. The magic is a fixed constant, so every frame remains independently reconstructable.

### Uncompressed Payload (flags == 0xFF)

The payload is a raw serialized `TAKPacketV2` protobuf message with no compression applied.

## 5. Dictionary Selection

The sending application selects the dictionary based on the CoT event type:

```
Parse the CoT type string (e.g., "a-f-G-U-C")
Split by "-" into atoms: ["a", "f", "G", "U", "C"]
If the 3rd atom (index 2) is "A":
    Use aircraft dictionary (ID 0x01)
Else:
    Use non-aircraft dictionary (ID 0x00)
```

Aircraft types have the Air battle dimension indicator `A` as the 3rd hierarchical atom in the CoT type string (e.g., `a-f-A-M-H`, `a-n-A-C-F`). All other types (ground, sea, tasking, bits, drawings) use the non-aircraft dictionary.

## 6. Protobuf Schema

The payload serializes a `TAKPacketV2` message defined in [`protobufs/meshtastic/atak.proto`](protobufs/meshtastic/atak.proto).

Key message structure:

```protobuf
message TAKPacketV2 {
  CotType cot_type_id = 1;       // Well-known CoT type enum
  CotHow how = 2;                // Coordinate generation method
  string callsign = 3;           // User callsign
  Team team = 4;                 // Team color
  MemberRole role = 5;           // Role in team
  sfixed32 latitude_i = 6;       // Latitude * 1e7
  sfixed32 longitude_i = 7;      // Longitude * 1e7
  sint32 altitude = 8;           // Altitude in meters (HAE)
  uint32 speed = 9;              // Speed in cm/s
  uint32 course = 10;            // Course in degrees * 100
  uint32 battery = 11;           // Battery 0-100
  GeoPointSource geo_src = 12;   // Position source (GPS, USER, NETWORK)
  GeoPointSource alt_src = 13;   // Altitude source
  string uid = 14;               // Event UID
  string device_callsign = 15;   // Device callsign (from <uid Droid="..."/>)
  uint32 stale_seconds = 16;     // stale - time, varint delta
  string tak_version = 17;       // TAK client version
  string tak_device = 18;        // TAK device model
  string tak_platform = 19;      // TAK platform (ATAK-CIV, iTAK, WinTAK)
  string tak_os = 20;            // TAK client OS
  string endpoint = 21;          // Contact endpoint
  string phone = 22;             // Contact phone
  string cot_type_str = 23;      // Fallback for unknown CoT types
  string remarks = 24;           // Optional <remarks> free text (non-chat types)

  reserved 30;                        // former `bool pli` arm — PLI is now implicit
  oneof payload_variant {
    // (no `pli` arm) A packet with NO payload_variant set is an implicit
    // PLI position report. The old `bool pli = 30` was removed because the
    // boolean was pure overhead on the highest-frequency packet; tag 30 is
    // reserved at the message level (proto3 forbids `reserved` inside a oneof).
    GeoChat chat = 31;                // Chat message + delivered/read receipts
    AircraftTrack aircraft = 32;      // ADS-B / military air track
    bytes raw_detail = 33;            // Raw <detail> fallback (callers building packets directly)
    DrawnShape shape = 34;            // Tactical graphics (circle, rect, polygon, etc.)
    Marker marker = 35;               // Fixed markers (spot, waypoint, 2525, etc.)
    RangeAndBearing rab = 36;         // Range-and-bearing measurement
    Route route = 37;                 // Waypoint + control point sequence
    CasevacReport casevac = 38;       // 9-line MEDEVAC request
    EmergencyAlert emergency = 39;    // Emergency / 911 alert
    TaskRequest task = 40;            // Tasking / engagement request
    TakTalkMessage taktalk = 41;      // TAKTALK chat message (m-t-t)
    TakTalkRoomData taktalk_room = 42; // TAKTALK room/membership broadcast (y-)
  }
}
```

The `payload_variant` oneof has 12 typed cases (tags 31–42); a packet with **no** `payload_variant` set is an implicit PLI position report, for 13 payload variants in total. The `CotType` enum covers 126 well-known CoT types (`CotType_Other` = 0 through `CotType_y` = 126). Unknown types use `CotType_Other` (0) with the raw type string in `cot_type_str`.

### Field 24: Remarks

The top-level `string remarks = 24` field carries free-text annotations (`<remarks>` XML element content) for non-chat payload types — shapes, markers, routes, range-and-bearing, casevac, emergency, and task events. It is not used by:

- **Chat payloads** — the GeoChat message itself goes in `GeoChat.message` (inside the `chat` oneof variant)
- **Aircraft payloads** — remarks are synthesized from the structured ICAO/registration/flight/squawk fields on rebuild

Remarks are optional. When the compressed packet would exceed the LoRa MTU, the `compressWithRemarksFallback()` helper automatically clears the remarks field and re-compresses before giving up.

## 7. Transport

The wire payload is transported as the `Data.payload` field of a Meshtastic `MeshPacket` with:

- **Port number:** `ATAK_PLUGIN_V2` = **78**
- **Priority:** Application-dependent (typically `RELIABLE` for TAK_TRACKER, `BACKGROUND` for general PLI)

The legacy v1 protocol uses port 72 (`ATAK_PLUGIN`) with a different encoding (TAKPacket + Unishox2 string compression on firmware).

## 8. Encoding Procedure

```
1. Parse CoT XML event string
2. Map fields into TAKPacketV2 protobuf message
3. Serialize TAKPacketV2 to protobuf wire format bytes
4. Classify CoT type -> select dictionary ID (0x00 or 0x01)
5. Compress protobuf bytes using zstd with the selected dictionary
   (dictID / contentSize / checksum disabled), then strip the 4-byte zstd magic
6. If the raw protobuf is no larger than the compressed body:
     a. Emit flags byte 0xFF + raw protobuf (skip-compress path)
   Else:
     a. Prepend flags byte (dictionary ID in bits 0-5, reserved bits 0)
7. Transmit on port 78
```

## 9. Decoding Procedure

```
1. Receive payload on port 78
2. Validate payload length >= 2 bytes
3. Read flags byte (byte 0)
   - If flags == 0xFE: pass the frame to the reassembler. When a message completes,
     go back to step 2 with the rebuilt payload as the payload: read ITS byte 0 as the
     flags byte (it is never 0xFE), and use a 32,768-byte cap instead of 4,096, both
     for the raw protobuf in step 4 and for decompression in step 5e
   - If flags == 0xFD: ignore (repair requests are not used by this version)
4. If flags == 0xFF:
     a. Payload bytes 1..N are raw TAKPacketV2 protobuf
5. Else:
     a. Extract dictionary ID = flags & 0x3F
     b. Look up dictionary by ID
     c. If dictionary not found: reject (unknown dictionary)
     d. Re-prepend the 4-byte zstd magic (28 B5 2F FD) to bytes 1..N,
        then decompress using zstd with the dictionary
     e. Limit decompressed size to 4096 bytes
6. Parse decompressed bytes as TAKPacketV2 protobuf
7. Reconstruct CoT XML event from TAKPacketV2 fields
```

## 10. Error Handling Requirements

| Condition | Requirement | Behavior |
|-----------|-------------|----------|
| Payload < 2 bytes | MUST reject | Drop message, log warning |
| Unknown dictionary ID (not 0x00, 0x01, 0xFF) | MUST reject | Drop message, log warning |
| Zstd decompression failure | SHOULD handle gracefully | Drop message, log error |
| Invalid protobuf after decompression | SHOULD handle gracefully | Drop message, log error |
| Decompressed size > 4096 bytes | MUST reject | Drop message, log warning |
| Reserved bits set in flags byte | SHOULD ignore | Mask with `& 0x3F`, process normally |
| Fragment with an unknown `code`, a frame over 233 bytes, or a length inconsistent with its set | MUST reject | Drop the frame; other fragments of the message are unaffected |
| Wire payload > 225 bytes | MUST NOT send | Sender error |

Implementations MUST NOT crash on any malformed input.

## 11. Dictionary Management

- Dictionaries are pre-trained on serialized `TAKPacketV2` **protobuf** bytes (the exact bytes that go on the wire, not raw CoT XML) using the `zstd --train` facility at level 19 (see [TAKPacket-ZTSD](https://github.com/meshtastic/TAKPacket-ZTSD) for the training pipeline)
- Two dictionaries are maintained: non-aircraft (512KB) and aircraft (4KB)
- Dictionaries are embedded as binary resources in each client application
- All nodes on a mesh MUST use the same dictionary pair for interoperability
- Dictionary updates are distributed via application releases
- The dictionary ID in the flags byte allows future expansion to 62 dictionary types

## 12. Compression Performance

Measured against the 47 bundled test fixtures (real CoT XML captured from ATAK-CIV, iTAK, and WebTAK clients):

| Metric | Value |
|--------|-------|
| Median XML input size | ~700 bytes |
| Median compressed wire size | **87 bytes** |
| Median compression ratio | **7.2×** |
| Maximum compressed size (test fixtures) | 193 bytes (`drawing_telestration`, 2130B XML) |
| LoRa MTU headroom (worst case) | 32 bytes |
| 100% of test fixtures under MTU | Yes |

See [`testdata/compression-report.md`](testdata/compression-report.md) for per-fixture measurements, regenerated on every Kotlin test run.

## 13. Example

A compressed PLI (Position Location Information) message for a ground unit:

**Input CoT XML** (446 bytes, from `testdata/cot_xml/pli_basic.xml`):
```xml
<?xml version="1.0" encoding="UTF-8"?>
<event version="2.0" uid="testnode" type="a-f-G-U-C" how="m-g"
       time="2026-03-15T14:22:10Z" start="2026-03-15T14:22:10Z"
       stale="2026-03-15T14:22:55Z">
  <point lat="37.7749" lon="-122.4194" hae="-22" ce="4.9" le="9999999"/>
  <detail>
    <contact endpoint="*:-1:stcp" callsign="testnode"/>
    <uid Droid="testnode"/>
    <_flow-tags_ TAK-Server-0000…="2026-03-15T14:22:10Z"/>
  </detail>
</event>
```

**Wire payload layout** (42 bytes):
```
+----------+-----------------------------------------------+
| Byte 0   | Bytes 1..41                                   |
| 0x00     | zstd-compressed TAKPacketV2 protobuf body     |
+----------+-----------------------------------------------+
   ^
   |
   dict ID 0 (non-aircraft), reserved bits 0
```

**Breakdown:**
- Byte 0: `0x00` — flags (dictionary ID 0 = non-aircraft, reserved bits = 0)
- Bytes 1–41: zstd-compressed TAKPacketV2 protobuf body (zstd magic stripped) using the non-aircraft dictionary
- Compression ratio: 446B XML → 42B wire = **10.6×**

Exact compressed bytes for every fixture are available at [`testdata/golden/pli_basic.bin`](testdata/golden/pli_basic.bin) (and one per fixture). The intermediate uncompressed protobuf bytes are at [`testdata/protobuf/pli_basic.pb`](testdata/protobuf/pli_basic.pb).

## 14. References

- [TAKPacket-SDK](https://github.com/meshtastic/TAKPacket-SDK) — Multi-platform SDK implementing this specification
- [`protobufs/meshtastic/atak.proto`](protobufs/meshtastic/atak.proto) — TAKPacketV2 protobuf schema (source of truth for field numbers)
- [TAKPacket-ZTSD](https://github.com/meshtastic/TAKPacket-ZTSD) — Dictionary training pipeline
- [Zstandard](https://github.com/facebook/zstd) — Compression algorithm
- [Cursor on Target](https://www.mitre.org/sites/default/files/pdf/09_4937.pdf) — CoT event specification
- [`testdata/compression-report.md`](testdata/compression-report.md) — Per-fixture compression measurements
