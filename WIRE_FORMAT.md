# TAKPacket v2 Wire Format Specification

**Version:** 1.1
**Status:** Stable
**Date:** April 2026

## 1. Overview

The TAKPacket v2 wire format defines how ATAK Cursor-on-Target (CoT) messages are encoded for transmission over Meshtastic LoRa mesh networks. The format compresses CoT data from its native XML representation (400-2300 bytes) down to a median of ~95 bytes using a two-stage pipeline: protobuf structural encoding (with 11 typed payload variants) followed by zstd dictionary compression.

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
| 1 | 1..236 bytes | Payload | zstd-compressed TAKPacketV2 protobuf (or raw protobuf if flags=0xFF) |

Total wire size MUST NOT exceed **237 bytes** (the LoRa maximum payload size).

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
| `0x00` | Non-aircraft | 16,384 bytes | PLI, GeoChat, ground units, shapes, markers, routes, ranging, alerts, casevac, emergency, task, delete events |
| `0x01` | Aircraft | 4,096 bytes | ADS-B tracks, military air, helicopters |
| `0x02`-`0x3E` | Reserved | — | Reserved for future dictionary types |
| `0xFF` | Uncompressed | — | Payload is raw TAKPacketV2 protobuf (no zstd compression) |

### Reserved Bits

Bits 6-7 are reserved for future use.

- **Senders** MUST set reserved bits to `0`.
- **Receivers** SHOULD ignore reserved bits (mask with `& 0x3F` to extract dictionary ID).

### Special Value: 0xFF (Uncompressed)

When the flags byte is `0xFF`, the payload is a raw (uncompressed) TAKPacketV2 protobuf. This is used by TAK_TRACKER firmware devices that cannot perform zstd compression. Receivers detect this value before attempting decompression.

## 4. Payload

### Compressed Payload (flags != 0xFF)

The payload is a zstd-compressed frame containing a serialized `TAKPacketV2` protobuf message. The zstd frame is compressed using a **pre-trained dictionary** identified by the dictionary ID in the flags byte.

**Compression parameters:**
- Algorithm: Zstandard (zstd)
- Compression level: 19 (maximum)
- Dictionary: Pre-trained, embedded in client applications
- Frame format: Standard zstd frame (includes magic number, frame header, blocks)

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

  oneof payload_variant {
    bool pli = 30;                    // Position report
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
  }
}
```

The `CotType` enum covers 125 well-known CoT types. Unknown types use `CotType_Other` (0) with the raw type string in `cot_type_str`.

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
6. Prepend flags byte (dictionary ID in bits 0-5, reserved bits 0)
7. Transmit on port 78
```

## 9. Decoding Procedure

```
1. Receive payload on port 78
2. Validate payload length >= 2 bytes
3. Read flags byte (byte 0)
4. If flags == 0xFF:
     a. Payload bytes 1..N are raw TAKPacketV2 protobuf
5. Else:
     a. Extract dictionary ID = flags & 0x3F
     b. Look up dictionary by ID
     c. If dictionary not found: reject (unknown dictionary)
     d. Decompress bytes 1..N using zstd with dictionary
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
| Wire payload > 237 bytes | MUST NOT send | Sender error |

Implementations MUST NOT crash on any malformed input.

## 11. Dictionary Management

- Dictionaries are pre-trained on representative CoT XML corpora using the `zstd --train` facility (see [TAKPacket-ZTSD](https://github.com/meshtastic/TAKPacket-ZTSD) for the training pipeline)
- Two dictionaries are maintained: non-aircraft (16KB) and aircraft (4KB)
- Dictionaries are embedded as binary resources in each client application
- All nodes on a mesh MUST use the same dictionary pair for interoperability
- Dictionary updates are distributed via application releases
- The dictionary ID in the flags byte allows future expansion to 62 dictionary types

## 12. Compression Performance

Measured against the 40 bundled test fixtures (real CoT XML captured from ATAK-CIV, iTAK, and WebTAK clients):

| Metric | Value |
|--------|-------|
| Median XML input size | ~700 bytes |
| Median compressed wire size | **95 bytes** |
| Median compression ratio | **6.8×** |
| Maximum compressed size (test fixtures) | 212 bytes (`drawing_telestration`, 2018B XML) |
| LoRa MTU headroom (worst case) | 25+ bytes |
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

**Wire payload layout** (57 bytes):
```
+----------+-----------------------------------------------+
| Byte 0   | Bytes 1..56                                   |
| 0x00     | zstd-compressed TAKPacketV2 protobuf          |
+----------+-----------------------------------------------+
   ^
   |
   dict ID 0 (non-aircraft), reserved bits 0
```

**Breakdown:**
- Byte 0: `0x00` — flags (dictionary ID 0 = non-aircraft, reserved bits = 0)
- Bytes 1–56: zstd-compressed TAKPacketV2 protobuf using the non-aircraft dictionary
- Compression ratio: 446B XML → 57B wire = **7.8×**

Exact compressed bytes for every fixture are available at [`testdata/golden/pli_basic.bin`](testdata/golden/pli_basic.bin) (and one per fixture). The intermediate uncompressed protobuf bytes are at [`testdata/protobuf/pli_basic.pb`](testdata/protobuf/pli_basic.pb).

## 14. References

- [TAKPacket-SDK](https://github.com/meshtastic/TAKPacket-SDK) — Multi-platform SDK implementing this specification
- [`protobufs/meshtastic/atak.proto`](protobufs/meshtastic/atak.proto) — TAKPacketV2 protobuf schema (source of truth for field numbers)
- [TAKPacket-ZTSD](https://github.com/meshtastic/TAKPacket-ZTSD) — Dictionary training pipeline
- [Zstandard](https://github.com/facebook/zstd) — Compression algorithm
- [Cursor on Target](https://www.mitre.org/sites/default/files/pdf/09_4937.pdf) — CoT event specification
- [`testdata/compression-report.md`](testdata/compression-report.md) — Per-fixture compression measurements
