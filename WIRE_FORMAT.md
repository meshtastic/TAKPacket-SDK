# TAKPacket v2 Wire Format Specification

**Version:** 1.0
**Status:** Draft
**Date:** April 2026

## 1. Overview

The TAKPacket v2 wire format defines how ATAK Cursor-on-Target (CoT) messages are encoded for transmission over Meshtastic LoRa mesh networks. The format compresses CoT data from its native XML representation (382-750 bytes) down to a median of ~100 bytes using a two-stage pipeline: protobuf structural encoding followed by zstd dictionary compression.

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
| `0x00` | Non-aircraft | 8,192 bytes | PLI, GeoChat, ground units, alerts, sensors, delete events |
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

The payload serializes a `TAKPacketV2` message defined in [`meshtastic/atak.proto`](https://github.com/meshtastic/protobufs/blob/tak_v2/meshtastic/atak.proto).

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
  // ... additional fields ...
  string cot_type_str = 23;      // Fallback for unknown CoT types

  oneof payload_variant {
    bool pli = 30;               // Position report
    GeoChat chat = 31;           // Chat message
    AircraftTrack aircraft = 32; // ADS-B / air track
    bytes raw_detail = 33;       // Generic CoT XML fallback
  }
}
```

The `CotType` enum covers 76 well-known CoT types. Unknown types use `CotType_Other` (0) with the raw type string in `cot_type_str`.

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

- Dictionaries are pre-trained on representative CoT XML corpora using the `zstd --train` facility
- Two dictionaries are maintained: non-aircraft (8KB) and aircraft (4KB)
- Dictionaries are embedded as binary resources in each client application
- All nodes on a mesh MUST use the same dictionary pair for interoperability
- Dictionary updates are distributed via application releases
- The dictionary ID in the flags byte allows future expansion to 62 dictionary types

## 12. Compression Performance

Measured against 733,746 real CoT events from a TAK Server database:

| Metric | Value |
|--------|-------|
| Median XML input size | ~650 bytes |
| Median compressed wire size | ~100 bytes |
| Median compression ratio | ~6.5x |
| Maximum compressed size (test fixtures) | 160 bytes |
| LoRa MTU headroom | 77+ bytes |
| 100% of test fixtures under MTU | Yes |

## 13. Example

A compressed PLI (Position Location Information) message for a ground unit:

**Input CoT XML** (430 bytes):
```xml
<event version="2.0" uid="testnode" type="a-f-G-U-C" how="m-g" ...>
  <point lat="37.7749" lon="-122.4194" hae="-22" ce="4.9" le="9999999"/>
  <detail>
    <contact endpoint="*:-1:stcp" callsign="testnode"/>
    ...
  </detail>
</event>
```

**Wire payload** (63 bytes):
```
Offset  Hex                                              ASCII
00      00                                               .        <- Flags: dict ID 0 (non-aircraft)
01      28 b5 2f fd 23 f9 91 8b 01 40 8d 01 00 54 02   (./.#....@...T.  <- zstd compressed frame
10      08 01 10 02 1a 08 74 65 73 74 6e 6f 64 65 35   ......testnode5
20      08 fe 83 16 3d 30 48 08 b7 40 2b 72 7a 80 01   ....=0H..@+rz..
30      2d aa 01 09 f0 01 01 03 04 06 9d 44 b5 dc 73   -..........D..s
3E      8e 01                                            ..
```

**Breakdown:**
- Byte 0: `0x00` = flags (dictionary ID 0, non-aircraft, no reserved bits)
- Bytes 1-62: zstd-compressed TAKPacketV2 protobuf (62 bytes)
- Compression ratio: 430B XML -> 63B wire = **6.8x**

## 14. References

- [TAKPacket-SDK](https://github.com/meshtastic/TAKPacket-SDK) — Multi-platform SDK implementing this specification
- [Meshtastic Protobufs](https://github.com/meshtastic/protobufs/tree/tak_v2) — TAKPacketV2 protobuf schema (`tak_v2` branch)
- [Zstandard](https://github.com/facebook/zstd) — Compression algorithm
- [Cursor on Target](https://www.mitre.org/sites/default/files/pdf/09_4937.pdf) — CoT event specification
