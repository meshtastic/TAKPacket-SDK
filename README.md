# TAKPacket-SDK

Shared libraries for converting ATAK Cursor-on-Target (CoT) XML to Meshtastic's TAKPacketV2 protobuf format and compressing it for LoRa transport using zstd dictionary compression.

This SDK is the single source of truth for CoT conversion and compression across all Meshtastic client platforms. Each language implementation produces interoperable compressed payloads, validated by 40 shared test fixtures and 1,000+ cross-platform tests.

## Architecture

```mermaid
flowchart TD
    subgraph send ["Sending App"]
        A["CoT XML<br/>(~400-2300 B)"]
        B["TAKPacketV2<br/>Protobuf"]
        C["Wire Payload<br/>(median 95B,<br/>max 212B)"]
        A -->|CotXmlParser| B
        B -->|TakCompressor| C
    end
    C -->|"LoRa / Meshtastic<br/>(≤237 B MTU)"| D
    subgraph recv ["Receiving App"]
        D["Wire Payload"]
        E["TAKPacketV2<br/>Protobuf"]
        F["CoT XML"]
        D -->|TakCompressor| E
        E -->|CotXmlBuilder| F
    end
```

## How It Works

### 1. CoT XML Parsing

`CotXmlParser` extracts structured fields from a CoT XML event and maps them into a `TAKPacketV2` protobuf message. The parser recognizes all major CoT categories and decomposes each into a strongly-typed `payload_variant` case:

- **PLI** (position location info) — the default for ground units
- **GeoChat** — team chat with sender/recipient metadata, plus delivered/read receipts
- **Aircraft track** — ADS-B, military air tracks, with ICAO/reg/flight/category fields
- **Drawn shapes** — circles, rectangles, polygons, freeform polylines, telestrations, ranging circles, bullseyes, ellipses, 2D/3D vehicles
- **Markers** — spot, waypoint, checkpoint, self-position, 2525 symbols, spot maps, custom icons, mission points (GoTo / IP / CP / OP), image markers
- **Range-and-bearing lines** — anchor + range + bearing + stroke
- **Routes** — ordered waypoint + control point sequences with travel method and direction
- **CASEVAC reports** — 9-line MEDEVAC with precedence, patient counts, equipment / terrain bitfields, HLZ marking, security, comms frequency
- **Emergency alerts** — 911 / troops-in-contact / geo-fence breach / cancel with authoring and cancel-reference UIDs
- **Tasking requests** — engagement / observation / recon / rescue tasks with priority, status, target, assignee, and note
- **Delete events** — fall into the PLI envelope with the `t-x-d-d` CoT type string

### 2. Compression Pipeline

```mermaid
flowchart TD
    A[TAKPacketV2 protobuf bytes] --> B{Classify CoT type}
    B -->|"Air domain\n(3rd atom = 'A')"| C["Aircraft dictionary\n(4KB) — dict ID 0x01"]
    B -->|"Ground / chat / shapes /\nmarkers / routes / alerts"| D["Non-aircraft dictionary\n(16KB) — dict ID 0x00"]
    C --> E[zstd compress with dictionary]
    D --> E
    E --> F["Prepend 1-byte flags\n(dict ID in bits 0-5)"]
    F --> G["Wire payload"]
```

Two pre-trained zstd dictionaries are used because aircraft and non-aircraft CoT messages have fundamentally different structural patterns. Using the wrong dictionary degrades compression past the LoRa MTU on the worst-case fixtures. The classification result is encoded into the flags byte on the wire so the receiver knows which dictionary to use for decompression — see [Wire Format](#3-wire-format) below. On the bundled test set the pipeline achieves:

| Metric | Value |
|---|---|
| Total test messages | 40 |
| 100% under 237B LoRa MTU | ✅ YES |
| Median compressed size | **95B** |
| Median compression ratio | **6.8×** |
| Worst case | 212B (89% of LoRa MTU — `drawing_telestration`) |

See [`testdata/compression-report.md`](testdata/compression-report.md) for the per-fixture breakdown, regenerated on every Kotlin test run.

### 3. Wire Format

The sender's dictionary selection (step 2 above) is encoded into the flags byte so the receiver can pick the matching dictionary for decompression without re-parsing the CoT type:

```
+----------+---------------------------------------------+
| Flags    | zstd-compressed TAKPacketV2 protobuf        |
| (1 byte) | (N bytes)                                   |
+----------+---------------------------------------------+

Flags byte:
  bits 0-5: Dictionary ID — written by the sender after classification,
            read by the receiver to select the matching dictionary
    0x00 = Non-aircraft (PLI, chat, ground, shapes, markers,
                         routes, ranging, alerts)
    0x01 = Aircraft (3rd atom = 'A' — ADS-B, military air tracks, helicopters)
    0x02-0x3E = Reserved for future dictionaries
  bits 6-7: Reserved / version

  Special value:
    0xFF = Uncompressed raw protobuf (sent by TAK_TRACKER firmware)
```

See [`WIRE_FORMAT.md`](WIRE_FORMAT.md) for the full specification including error handling requirements and annotated examples.

### 4. CoT XML Reconstruction

`CotXmlBuilder` reconstructs a standards-compliant CoT XML event from a `TAKPacketV2` protobuf, preserving every structured field extracted during parsing — including geometry vertices, stroke/fill colors, marker iconsets, and route waypoints.

## Structured Payload Types

`TAKPacketV2.payload_variant` is a proto `oneof` with eleven strongly-typed cases rather than a single opaque bytes field. Decomposing the `<detail>` element into structured messages gives three concrete benefits:

1. **Tighter compression** — repeated field names collapse to varint tags. A circle that takes 930B of XML fits in 128B on the wire (7.3× ratio); a 9-line MEDEVAC goes from 808B of XML to 99B (8.2× ratio).
2. **Schema evolution** — adding a field to `DrawnShape` doesn't break older receivers, they just see an unknown varint and skip it.
3. **Parse-side validation** — geometry is a structured object, not a regex over XML text.

| Tag | Variant | Proto message | CoT type atoms | Contents |
|---|---|---|---|---|
| 30 | `pli` | `bool` | `a-f-G-U-C`, `a-f-G-U-C-I`, … | Default ground-unit position |
| 31 | `chat` | `GeoChat` | `b-t-f`, `b-t-f-d`, `b-t-f-r` | Team chat message (plus delivered/read receipts via new fields) |
| 32 | `aircraft` | `AircraftTrack` | `a-n-A-C-F`, `a-h-A-M-F-F`, … | ADS-B / military air track |
| 33 | `raw_detail` | `bytes` | *any* | Raw `<detail>` fallback for callers that build a packet directly (no public compressor path) |
| 34 | `shape` | `DrawnShape` | `u-d-c-c`, `u-d-r`, `u-d-f`, `u-d-f-m`, `u-d-p`, `u-r-b-c-c`, `u-r-b-bullseye`, `u-d-c-e`, `u-d-v`, `u-d-v-m` | Tactical graphics |
| 35 | `marker` | `Marker` | `b-m-p-s-m`, `b-m-p-w`, `b-m-p-c`, `a-u-G`, `b-m-p-w-GOTO`, `b-m-p-c-ip`, `b-m-p-c-cp`, `b-m-p-s-p-op`, `b-i-x-i`, … | Fixed markers, 2525 symbols, icons, mission points |
| 36 | `rab` | `RangeAndBearing` | `u-rb-a` | Range-and-bearing measurement |
| 37 | `route` | `Route` | `b-m-r` | Waypoint + control point sequence |
| 38 | `casevac` | `CasevacReport` | `b-r-f-h-c` | 9-line MEDEVAC request |
| 39 | `emergency` | `EmergencyAlert` | `b-a-o-tbl`, `b-a-o-pan`, `b-a-o-opn`, `b-a-g`, `b-a-o-c`, `b-a-o-can` | Emergency / 911 alert |
| 40 | `task` | `TaskRequest` | `t-s` | Tasking / engagement request |

Every geometry variant uses **delta-encoded** `CotGeoPoint` vertices (`sint32` offsets from the event anchor) so a 32-vertex telestration clustered inside 100m encodes in ~60 bytes of vertex data instead of ~320 bytes with absolute coordinates. Color fields use a two-field encoding: a `Team` palette enum for the 14 ATAK-standard colors (2 bytes on the wire) plus a `fixed32 _argb` fallback for custom user-picked colors (5 bytes). Round-trip is byte-exact — custom colors are never quantized to the nearest palette entry.

### DrawnShape

Covers ten tactical graphic kinds. The shape's anchor point lives on `TAKPacketV2.latitude_i`/`longitude_i`; geometry vertices are in a repeated `CotGeoPoint` field delta-encoded from that anchor.

| Kind value | Name | Wire fields used |
|---|---|---|
| 1 | `Circle` | `major_cm`, `minor_cm`, `angle_deg` |
| 2 | `Rectangle` | 4 vertices (corner points) |
| 3 | `Freeform` | N vertices (polyline) |
| 4 | `Telestration` | N vertices (may truncate at 32, sets `truncated=true`) |
| 5 | `Polygon` | N vertices (implicitly closed) |
| 6 | `RangingCircle` | `major_cm`, `minor_cm`, `angle_deg`, stroke only |
| 7 | `Bullseye` | Ellipse + `bullseye_distance_dm`, `bullseye_bearing_ref`, `bullseye_flags`, `bullseye_uid_ref` |
| 8 | `Ellipse` | `major_cm`, `minor_cm`, `angle_deg` (distinct major/minor, not a circle) |
| 9 | `Vehicle2D` | N vertices (footprint polygon) |
| 10 | `Vehicle3D` | N vertices (footprint polygon; receiver extrudes) |

`StyleMode` discriminates `StrokeOnly` vs `FillOnly` vs `StrokeAndFill`, preserving the distinction between *"this polyline has no fill"* and *"this shape has a transparent black fill"*.

**KML Style Links:** Circle and ellipse shapes include a `<link type="b-x-KmlStyle">` element inside `<shape>` for iTAK compatibility. ATAK encodes colors in KML's **ABGR hex format** (not ARGB). The builder converts `stroke_argb`/`fill_argb` proto fields (ARGB int32) to ABGR hex strings automatically:

| Proto field (ARGB) | KML hex (ABGR) | Example |
|---|---|---|
| `0xFFFF0000` (opaque red) | `ff0000ff` | `<color>ff0000ff</color>` |
| `0x9F00FF00` (semi-transparent green) | `9f00ff00` | `<color>9f00ff00</color>` |
| `0x00000000` (transparent) | not emitted | PolyStyle omitted |

**Kotlin — parse a drawing_circle and read its structured fields:**
```kotlin
val parser = CotXmlParser()
val packet = parser.parse(drawingCircleXml)
val shape = packet.payload as TakPacketV2Data.Payload.DrawnShape

println("Kind: ${shape.kind}")                              // 1 = Circle
println("Radius: ${shape.majorCm / 100.0} meters")          // e.g. 226.98
println("Stroke ARGB: #%08X".format(shape.strokeArgb))      // e.g. #FFFFFFFF
println("Style: ${shape.style}")                            // 3 = StrokeAndFill
println("Labels on: ${shape.labelsOn}")
```

**Swift — extract a polygon's vertices:**
```swift
let packet = parser.parse(drawingPolygonXml)
if case .shape(let shape) = packet.payloadVariant,
   shape.kind == .polygon {
    for vertex in shape.vertices {
        // CotGeoPoint is delta-encoded from the event anchor.
        let lat = Double(packet.latitudeI + vertex.latDeltaI) / 1e7
        let lon = Double(packet.longitudeI + vertex.lonDeltaI) / 1e7
        print("  (\(lat), \(lon))")
    }
}
```

### Marker

Fixed markers with a `Kind` enum covering the common ATAK categories plus the mission-point set that ATAK CIV added in 4.x (GoTo / Initial Point / Contact Point / Observation Post) and standalone image markers. `iconset` holds the full iconset path verbatim (no prefix stripping) — round-trip works for `COT_MAPPING_SPOTMAP/…`, `COT_MAPPING_2525B/…`, and custom `UUID/group/icon.png` paths.

| Kind value | Name | Typical CoT type |
|---|---|---|
| 1 | `Spot` | `b-m-p-s-m` |
| 2 | `Waypoint` | `b-m-p-w` |
| 3 | `Checkpoint` | `b-m-p-c` |
| 4 | `SelfPosition` | `b-m-p-s-p-i`, `b-m-p-s-p-loc` |
| 5 | `Symbol2525` | `a-*-G` with `iconsetpath="COT_MAPPING_2525B/…"` |
| 6 | `SpotMap` | `iconsetpath="COT_MAPPING_SPOTMAP/…"` |
| 7 | `CustomIcon` | Any type with a `UUID/group/icon.png` iconset |
| 8 | `GoToPoint` | `b-m-p-w-GOTO` |
| 9 | `InitialPoint` | `b-m-p-c-ip` |
| 10 | `ContactPoint` | `b-m-p-c-cp` |
| 11 | `ObservationPost` | `b-m-p-s-p-op` |
| 12 | `ImageMarker` | `b-i-x-i` |

**Python — classify a marker by kind:**
```python
packet = parser.parse(marker_2525_xml)
if packet.HasField("marker"):
    marker = packet.marker
    print(f"Kind: {marker.kind}")              # 5 = Symbol2525
    print(f"Iconset: {marker.iconset}")        # "COT_MAPPING_2525B/a-u/a-u-G"
    print(f"Parent UID: {marker.parent_uid}")
    print(f"Readiness: {marker.readiness}")
```

### RangeAndBearing

Single-leg range-and-bearing line. The anchor endpoint is a `CotGeoPoint` delta-encoded from the event point, so an anchor identical to the event (common for self-anchored RAB) encodes in zero bytes.

**TypeScript — extract range, bearing, and reconstruct the anchor:**
```typescript
import { parseCotXml } from "@meshtastic/takpacket-sdk";

const packet = parseCotXml(rangingLineXml);
if (packet.rab) {
  const rab = packet.rab as any;
  const rangeM = rab.rangeCm / 100;
  const bearingDeg = rab.bearingCdeg / 100;
  const anchorLat = (packet.latitudeI + (rab.anchor?.latDeltaI ?? 0)) / 1e7;
  const anchorLon = (packet.longitudeI + (rab.anchor?.lonDeltaI ?? 0)) / 1e7;
  console.log(`Range: ${rangeM} m @ ${bearingDeg}°`);
  console.log(`Anchor: ${anchorLat.toFixed(6)}, ${anchorLon.toFixed(6)}`);
}
```

### Route

Ordered waypoint sequence with travel method and direction. Link count caps at 16 — longer routes are truncated and the `truncated` flag is set.

| Field | Values |
|---|---|
| `method` | `Driving`, `Walking`, `Flying`, `Swimming`, `Watercraft` |
| `direction` | `Infil`, `Exfil` |
| `prefix` | Short waypoint prefix (e.g. `"CP"`, `"RP"`) |
| `links[]` | Up to 16 waypoint/checkpoint entries, each with delta-encoded point, uid, callsign, and `link_type` (0=waypoint, 1=checkpoint) |

**C# — iterate a route's waypoints:**
```csharp
var packet = parser.Parse(route3wpXml);
if (packet.PayloadVariantCase == TAKPacketV2.PayloadVariantOneofCase.Route)
{
    var route = packet.Route;
    Console.WriteLine($"Method: {route.Method}, Direction: {route.Direction}");
    foreach (var link in route.Links)
    {
        var lat = (packet.LatitudeI + link.Point.LatDeltaI) / 1e7;
        var lon = (packet.LongitudeI + link.Point.LonDeltaI) / 1e7;
        var kind = link.LinkType == 1 ? "checkpoint" : "waypoint";
        Console.WriteLine($"  {link.Callsign} ({kind}): {lat:F6}, {lon:F6}");
    }
}
```

### CasevacReport

9-line MEDEVAC request for CoT type `b-r-f-h-c`. Mirrors ATAK's MedLine `<_medevac_>` detail element: precedence, equipment flags bitfield, patient counts, HLZ marking method, zone marker, security at PZ, nationality counts, terrain obstacles bitfield, and comms frequency. Every field is optional so senders omit lines they don't have. The envelope carries Line 1 (location) and Line 2 (callsign).

| Enum | Values |
|---|---|
| `Precedence` | `Urgent` (A), `UrgentSurgical` (B), `Priority` (C), `Routine` (D), `Convenience` (E) |
| `HlzMarking` | `Panels`, `PyroSignal`, `Smoke`, `None`, `Other` |
| `Security` | `NoEnemy` (N), `PossibleEnemy` (P), `EnemyInArea` (E), `EnemyInArmedContact` (X) |

| Bitfield | Bit layout |
|---|---|
| `equipment_flags` | 0=none, 1=hoist, 2=extraction, 3=ventilator, 4=blood |
| `terrain_flags` | 0=slope, 1=rough, 2=loose, 3=trees, 4=wires, 5=other |

Typical wire size is ~65B of proto + envelope, compressing to ~99B — well under the 237B LoRa MTU even with all 9 lines populated.

**Kotlin — build a CASEVAC request:**
```kotlin
val packet = TakPacketV2Data(
    cotTypeId = CotTypeMapper.typeToEnum("b-r-f-h-c"),
    how = CotTypeMapper.howToEnum("h-g-i-g-o"),
    callsign = "MEDEVAC-1",
    uid = "medevac-01",
    latitudeI = (18.1 * 1e7).roundToInt(),
    longitudeI = (140.1 * 1e7).roundToInt(),
    payload = TakPacketV2Data.Payload.CasevacReport(
        precedence = CotXmlParser.PRECEDENCE_URGENT,
        litterPatients = 2,
        ambulatoryPatients = 1,
        equipmentFlags = 0x02 or 0x04,       // hoist + extraction
        security = CotXmlParser.SECURITY_POSSIBLE_ENEMY,
        hlzMarking = CotXmlParser.HLZ_MARKING_SMOKE,
        zoneMarker = "Green smoke",
        frequency = "38.90",
    ),
)
val wire = TakCompressor().compress(packet)  // ~99B, well under LoRa MTU
```

### EmergencyAlert

Small, high-priority structured record for emergency CoT types (`b-a-o-*`, `b-a-g`). The CoT type string is still set on `cot_type_id` so receivers that don't handle `payload_variant` can still display the alert; the typed fields let modern receivers show the authoring unit and handle cancel referencing without XML parsing.

| Enum | Value | CoT type |
|---|---|---|
| `Type_Alert911` | 1 | `b-a-o-tbl` |
| `Type_RingTheBell` | 2 | `b-a-o-pan` |
| `Type_InContact` | 3 | `b-a-o-opn` |
| `Type_GeoFenceBreached` | 4 | `b-a-g` |
| `Type_Custom` | 5 | `b-a-o-c` |
| `Type_Cancel` | 6 | `b-a-o-can` |

Typical self-authored alert compresses to ~87B on the wire. Cancel events reference the original alert UID via `cancel_reference_uid`.

**Swift — read an emergency alert:**
```swift
let packet = parser.parse(emergency911Xml)
if case .emergency(let emergency) = packet.payloadVariant {
    switch emergency.type {
    case .alert911:
        print("911 alert from \(emergency.authoringUid)")
    case .inContact:
        print("Troops in contact from \(emergency.authoringUid)")
    case .cancel:
        print("Cancel for \(emergency.cancelReferenceUid)")
    default:
        print("Emergency type: \(emergency.type)")
    }
}
```

### TaskRequest

Structured tasking record for CoT type `t-s`. Captures the target UID, assignee, priority, status, and a short note — everything the raw-detail fallback loses when flattening a task into remarks text. The envelope carries the requester UID (implicit) and creation time.

| Enum | Values |
|---|---|
| `Priority` | `Low`, `Normal`, `High`, `Critical` |
| `Status` | `Pending`, `Acknowledged`, `InProgress`, `Completed`, `Cancelled` |

The `task_type` field is free-text (capped at 12 chars) to avoid proto churn when ATAK adds new task categories — common values are `"engage"`, `"observe"`, `"recon"`, `"rescue"`.

**Python — inspect a task request:**
```python
packet = parser.parse(task_engage_xml)
if packet.HasField("task"):
    task = packet.task
    print(f"Task type: {task.task_type}")          # "engage"
    print(f"Target: {task.target_uid}")            # "target-01"
    print(f"Assigned to: {task.assignee_uid}")     # "ANDROID-..."
    print(f"Priority: {task.priority}")            # 3 = High
    print(f"Status: {task.status}")                # 1 = Pending
    print(f"Note: {task.note}")                    # "cover by fire"
```

### GeoChat receipts

Delivered (`b-t-f-d`) and read (`b-t-f-r`) chat receipts ride on the same `chat = 31` slot as regular chat messages. The CoT type string on `cot_type_id` distinguishes delivered vs read at the envelope level; two new fields on `GeoChat` carry the referenced message UID so receivers can match the receipt back to the outbound message without XML parsing.

| Field | Values |
|---|---|
| `receipt_for_uid` | UID of the chat message being acknowledged |
| `receipt_type` | `None` (normal chat), `Delivered` (`b-t-f-d`), `Read` (`b-t-f-r`) |

**TypeScript — handle an incoming chat or receipt:**
```typescript
import { parseCotXml } from "@meshtastic/takpacket-sdk";

const packet = parseCotXml(incomingXml);
if (packet.chat) {
  const chat = packet.chat as any;
  if (chat.receiptType === 1) {
    console.log(`Delivered receipt for message ${chat.receiptForUid}`);
  } else if (chat.receiptType === 2) {
    console.log(`Read receipt for message ${chat.receiptForUid}`);
  } else {
    console.log(`Message from ${chat.toCallsign}: ${chat.message}`);
  }
}
```

### Color encoding with AtakPalette

Every color field in `DrawnShape`, `Marker`, and `RangeAndBearing` uses two parallel fields:

- A `Team` enum for the 14 ATAK palette colors (White, Yellow, Orange, Magenta, Red, Maroon, Purple, Dark Blue, Blue, Cyan, Teal, Green, Dark Green, Brown) — encodes in **2 bytes** on the wire
- A `fixed32 _argb` fallback for custom user-picked colors — encodes in **5 bytes**

The `AtakPalette` helper (shipped in every SDK) does the bidirectional lookup: `argbToTeam(0xFFFFFFFF)` returns `Team.White`, `teamToArgb(Team.Red)` returns `0xFFFF0000`. The parser sets both fields so receivers can pick whichever one suits them; the builder uses the palette's canonical ARGB when the team enum is set, otherwise the raw fallback bits so custom colors round-trip byte-for-byte.

## Remarks-Aware Compression

`compressWithRemarksFallback()` preserves user-authored `<remarks>` text (shape descriptions, route notes, etc.) when the compressed packet fits under the LoRa MTU. If it doesn't fit, remarks are stripped and the packet is re-compressed. If it *still* doesn't fit, the method returns null and the caller drops the packet. This is the recommended entry point for mesh transmission — it subsumes the size guard that callers would otherwise need:

```kotlin
val wire = compressor.compressWithRemarksFallback(packet, maxWireBytes = 225)
    ?: return // packet too large even without remarks
```

## Real-World Compression Examples

End-to-end walkthroughs showing actual CoT XML from ATAK/iTAK being compressed for LoRa mesh transmission. Each example shows the raw XML, what gets stripped before compression, the resulting proto structure, and the final compressed wire payload. The LoRa MTU is **237 bytes**.

> **Two-phase optimization:** The examples below show two reduction stages. First, the app strips non-essential XML *elements* (`<takv>`, `<voice>`, `<precisionLocation>`, etc.) before the SDK sees the XML. Second, the SDK parser eliminates further redundancy at parse time: `version="2.0"` is not stored (hardcoded on rebuild), `time` and `start` are discarded (rebuilt from receiver clock), `stale` becomes a compact `staleSeconds` varint (delta from time), and `ce`/`le` sentinels are dropped (hardcoded `9999999` on rebuild). The "After stripping" blocks below show the XML after phase 1 only — the event-envelope attributes visible there are **NOT on the wire**.

### PLI — Position Report (`a-f-G-U-C`)

**Raw CoT XML from TAK client** (754 bytes)
```xml
<event version="2.0" uid="ANDROID-0000000000000002" type="a-f-G-U-C" how="h-e"
       time="2026-03-15T15:30:00Z" start="2026-03-15T15:30:00Z" stale="2026-03-15T15:30:45Z">
  <point lat="12.00000" lon="91.00000" hae="-29.667" ce="32.2" le="9999999"/>
  <detail>
    <takv os="34" version="4.12.0.1 (00000000)[playstore].0000000000-CIV"
          device="Simulator" platform="ATAK-CIV"/>
    <contact endpoint="*:-1:stcp" phone="+15550000001" callsign="TESTNODE-01"/>
    <uid Droid="TESTNODE-01"/>
    <precisionlocation altsrc="GPS" geopointsrc="GPS"/>
    <__group role="Team Member" name="Cyan"/>
    <status battery="88"/>
    <track course="142.75" speed="1.2"/>
    <_flow-tags_ TAK-Server-00000000="2026-03-15T15:30:00Z"/>
  </detail>
</event>
```

**After stripping** (~400 bytes) — `<takv>`, `<precisionlocation>`, `<_flow-tags_>` removed
```xml
<event version="2.0" uid="ANDROID-0000000000000002" type="a-f-G-U-C" how="h-e"
       time="2026-03-15T15:30:00Z" start="2026-03-15T15:30:00Z" stale="2026-03-15T15:30:45Z">
  <point lat="12.00000" lon="91.00000" hae="-29.667" ce="32.2" le="9999999"/>
  <detail>
    <contact endpoint="*:-1:stcp" phone="+15550000001" callsign="TESTNODE-01"/>
    <uid Droid="TESTNODE-01"/>
    <__group role="Team Member" name="Cyan"/>
    <status battery="88"/>
    <track course="142.75" speed="1.2"/>
  </detail>
</event>
```

**TAKPacketV2 proto fields**
```
cot_type_id: 1 (a-f-G-U-C)    how: 1 (h-e)
callsign: "TESTNODE-01"        device_callsign: "TESTNODE-01"
latitude_i: 120000000          longitude_i: 910000000
altitude: -30                  speed: 120 (cm/s)      course: 14275 (deg×100)
battery: 88                    team: 5 (Cyan)          role: 1 (TeamMember)
geo_src: 1 (GPS)               alt_src: 1 (GPS)
endpoint: "" (normalized)       phone: "+15550000001"
pli: true
```

| Stage | Size | Reduction |
|-------|------|-----------|
| Raw XML | 754 B | — |
| Stripped | ~400 B | -47% |
| Compressed | **151 B** | **80% total** |

---

### GeoChat — Broadcast Message (`b-t-f`)

**Raw CoT XML from iTAK** (1031 bytes)
```xml
<event version="2.0" uid="GeoChat.23131970-4D02-4092-A30A-8A49EBD04AA0.All Chat Rooms.08C6FA28"
       type="b-t-f" how="h-g-i-g-o" time="2026-04-10T13:41:23Z" ...>
  <point lat="34.80545694681502" lon="-92.4817947769074" hae="9999999.0" .../>
  <detail>
    <__chat parent="RootContactGroup" groupOwner="false" messageId="08C6FA28"
            chatroom="All Chat Rooms" id="All Chat Rooms" senderCallsign="iPad">
      <chatgrp uid0="23131970-4D02-4092-A30A-8A49EBD04AA0" uid1="All Chat Rooms"/>
    </__chat>
    <link uid="23131970-4D02-4092-A30A-8A49EBD04AA0" type="a-f-G-E-V-C" relation="p-p"/>
    <remarks source="BAO.F.ATAK.23131970-..." to="All Chat Rooms" time="...">Test</remarks>
    <__serverdestination destinations="*:4242:tcp:23131970-..."/>
    <_flow-tags_ TAK-Server-dd4055d1="2026-04-10T13:41:23Z"/>
  </detail>
</event>
```

**TAKPacketV2 proto fields** — `chat.to` omitted for broadcast (saves 16 bytes)
```
cot_type_id: 25 (b-t-f)       how: 3 (h-g-i-g-o)
callsign: "iPad"               uid: "GeoChat.23131970-...All Chat Rooms.08C6FA28"
chat {
  message: "Test"
  to_callsign: "iPad"          # to: null (broadcast = 0 bytes)
}
```

| Stage | Size | Reduction |
|-------|------|-----------|
| Raw XML | 1,031 B | — |
| Stripped | ~700 B | -32% |
| Compressed | **80 B** | **92% total** |

---

### Rectangle — Drawn Shape (`u-d-r`)

**Raw CoT XML from ATAK** (945 bytes)
```xml
<event version="2.0" uid="ace0fc3f-9587-406c-be66-a52f02cdbedf" type="u-d-r"
       time="2026-04-11T01:09:56.557Z" stale="2026-04-12T01:09:56.557Z" how="h-e">
  <point lat="34.8044064" lon="-92.436114" hae="67.004" .../>
  <detail>
    <link point="34.80564553084199,-92.43683293800487"/>
    <link point="34.80422710311164,-92.43446184473841"/>
    <link point="34.80316693332748,-92.43539543971937"/>
    <link point="34.80458597608953,-92.43776596177908"/>
    <__shapeExtras cpvis="false" editable="true"/>
    <remarks/>
    <creator uid="ANDROID-2fb24d79bf83a660" callsign="ETHEL" .../>
    <strokeColor value="-16777089"/>
    <strokeWeight value="3.0"/>
    <strokeStyle value="solid"/>
    <fillColor value="-1778384769"/>
    <contact callsign="Rectangle 2"/>
    <tog enabled="0"/>
    <precisionlocation altsrc="SRTM1" geopointsrc="USER"/>
    <labels_on value="false"/>
    <archive/>
  </detail>
</event>
```

**After stripping** (~500 bytes) — `<__shapeExtras>`, `<creator>`, `<tog>`, `<archive>`, `<remarks/>`, `<strokeStyle>`, `<precisionlocation>` removed
```xml
<event version="2.0" uid="ace0fc3f-..." type="u-d-r" ...>
  <point lat="34.8044064" lon="-92.436114" hae="67.004" .../>
  <detail>
    <link point="34.80564553084199,-92.43683293800487"/>
    <link point="34.80422710311164,-92.43446184473841"/>
    <link point="34.80316693332748,-92.43539543971937"/>
    <link point="34.80458597608953,-92.43776596177908"/>
    <strokeColor value="-16777089"/>
    <strokeWeight value="3.0"/>
    <fillColor value="-1778384769"/>
    <contact callsign="Rectangle 2"/>
    <labels_on value="false"/>
  </detail>
</event>
```

**TAKPacketV2 proto fields** — vertices delta-encoded from anchor point
```
cot_type_id: 40 (u-d-f/u-d-r)  how: 1 (h-e)
callsign: "Rectangle 2"
latitude_i: 348044064          longitude_i: -924361140
shape {
  kind: 2 (Rectangle)          style: 3 (StrokeAndFill)
  stroke_argb: 0xFF0000FF      fill_argb: 0x960000FF
  stroke_weight_x10: 30        labels_on: false
  vertices: [                  # delta-encoded from anchor
    { lat_delta_i: +1245, lon_delta_i: -7219 }
    { lat_delta_i: -1835, lon_delta_i: +1655 }
    { lat_delta_i: -2737, lon_delta_i: +719  }
    { lat_delta_i: -1527, lon_delta_i: -1559 }
  ]
}
```

| Stage | Size | Reduction |
|-------|------|-----------|
| Raw XML | 945 B | — |
| Stripped | ~500 B | -47% |
| Compressed | **101 B** | **87% total** |

---

### Circle — Drawn Shape (`u-d-c-c`)

**Raw CoT XML from ATAK** (851 bytes)
```xml
<event version="2.0" uid="67EBAF59-A216-4B0C-BD24-9AE5EE4D65E6" type="u-d-c-c" ...>
  <point lat="34.7720486" lon="-92.4584657" hae="9999999.0" .../>
  <detail>
    <shape>
      <ellipse major="393.14" minor="393.14" angle="360"/>
      <link uid="67EBAF59-...Style" type="b-x-KmlStyle" relation="p-c">
        <Style><LineStyle><color>ffff4245</color><width>3.0</width></LineStyle>
        <PolyStyle><color>00000000</color></PolyStyle></Style>
      </link>
    </shape>
    <__shapeExtras cpvis="true" editable="true"/>
    <strokeColor value="-48571"/>
    <strokeWeight value="3.0"/>
    <fillColor value="0"/>
    <contact callsign="Shape 324"/>
    <labels_on value="false"/>
    <archive/>
    <uid Droid="Shape 324"/>
  </detail>
</event>
```

**TAKPacketV2 proto fields** — circle stored as major/minor radii in centimeters
```
cot_type_id: 42 (u-d-c-c)     how: 1 (h-e)
callsign: "Shape 324"
latitude_i: 347720486          longitude_i: -924584657
shape {
  kind: 1 (Circle)             style: 1 (StrokeOnly)
  major_cm: 39314              minor_cm: 39314         angle_deg: 360
  stroke_argb: 0xFFFF4245      fill_argb: 0x00000000
  stroke_weight_x10: 30        labels_on: false
}
```

| Stage | Size | Reduction |
|-------|------|-----------|
| Raw XML | 851 B | — |
| Stripped | ~450 B | -47% |
| Compressed | **90 B** | **90% total** |

---

### Route — 3 Waypoints (`b-m-r`)

**Raw CoT XML from iTAK** (890 bytes)
```xml
<event version="2.0" uid="139A3009-681E-4B1A-8F23-DBB49A2C338D" type="b-m-r" ...>
  <point lat="34.74829435592147" lon="-92.43520215509216" hae="0.0" .../>
  <detail>
    <contact callsign="Route - 04/11 06:48:00"/>
    <precisionLocation geopointsrc="???" altsrc="???"/>
    <link uid="D71306C3-..." callsign="SP" type="b-m-p-w"
          point="34.74829435592147,-92.43520215509216"/>
    <link uid="06BDF9C8-..." callsign="" type="b-m-p-c"
          point="34.74650551240878,-92.43195557866541"/>
    <link uid="A5449578-..." callsign="VDO" type="b-m-p-w"
          point="34.748578593226505,-92.4354345620684"/>
    <link_attr color="-65281" method="Walking" prefix="CP" direction="Infil"
               routetype="Primary" order="Ascending Check Points"/>
    <marti/>
  </detail>
</event>
```

**After stripping** (~380 bytes) — `<precisionLocation>`, `<marti/>`, `???` attrs, `routetype`, `order`, `color`, empty `callsign`, and waypoint `uid` attrs removed
```xml
<event version="2.0" uid="139A3009-..." type="b-m-r" ...>
  <point lat="34.74829435592147" lon="-92.43520215509216" hae="0.0" .../>
  <detail>
    <contact callsign="Route - 04/11 06:48:00"/>
    <link callsign="SP" type="b-m-p-w"
          point="34.74829435592147,-92.43520215509216"/>
    <link type="b-m-p-c"
          point="34.74650551240878,-92.43195557866541"/>
    <link callsign="VDO" type="b-m-p-w"
          point="34.748578593226505,-92.4354345620684"/>
    <link_attr method="Walking" prefix="CP" direction="Infil"/>
  </detail>
</event>
```

**TAKPacketV2 proto fields** — waypoints delta-encoded, UIDs omitted (receiver derives)
```
cot_type_id: 10 (b-m-r)       how: 1 (h-e)
callsign: "Route - 04/11 06:48:00"
latitude_i: 347482943          longitude_i: -924352021
route {
  method: 1 (Walking)          direction: 1 (Infil)     prefix: "CP"
  stroke_weight_x10: 30
  links: [
    { lat_i: 347482943, lon_i: -924352021, callsign: "SP",  link_type: 0 }
    { lat_i: 347465055, lon_i: -924319555,                   link_type: 1 }
    { lat_i: 347485785, lon_i: -924354345, callsign: "VDO", link_type: 0 }
  ]
}
```

| Stage | Size | Reduction |
|-------|------|-----------|
| Raw XML | 890 B | — |
| Stripped | ~380 B | -57% |
| Compressed | **~80 B** | **91% total** |

> **Note:** Routes are the tightest fit under the 237B LoRa MTU. The stripper removes waypoint `uid` attributes (~40 bytes each in proto wire format), `routetype`, `order`, `color`, and empty `callsign` attributes. Without UID stripping, a 5-waypoint route compresses to ~271 bytes (over the 237B limit); with it, even routes with 5-6 waypoints fit comfortably. The builder generates deterministic UIDs (`{eventUid}-{index}`) on reconstruction so ATAK can create internal waypoint markers.

**Route reconstruction details:** The `CotXmlBuilder` emits route elements in the order ATAK expects:
1. `<link>` waypoints first — each with `uid`, `type`, `callsign`, 3-component `point="lat,lon,hae"`, and `relation="c"`
2. `<link_attr>` with `method`, `direction`, `prefix`, `stroke`
3. `<__routeinfo><__navcues/></__routeinfo>` (non-self-closing, with navcues child)

The stale time for routes (and all static CoT types) is extended to a minimum of 15 minutes before mesh transmission to survive multi-hop LoRa delivery. iTAK uses a 2-minute stale for routes which expires before mesh delivery completes.

---

### Marker — Spot Map (`b-m-p-s-m`)

**Raw CoT XML from ATAK** (721 bytes)
```xml
<event version="2.0" uid="9405e320-9356-41c4-8449-f46990aa17f8" type="b-m-p-s-m"
       time="2026-03-15T14:22:10Z" stale="2026-03-16T14:22:10Z" how="h-g-i-g-o">
  <point lat="10.00606" lon="95.00362" hae="9999999.0" .../>
  <detail>
    <status readiness="true"/>
    <archive/>
    <link uid="ANDROID-0000000000000001" type="a-f-G-U-C"
          parent_callsign="SIM-01" relation="p-p"/>
    <contact callsign="R 1"/>
    <remarks/>
    <color argb="-65536"/>
    <precisionlocation altsrc="???"/>
    <usericon iconsetpath="COT_MAPPING_SPOTMAP/b-m-p-s-m/-65536"/>
  </detail>
</event>
```

**After stripping** (~400 bytes) — `<archive>`, `<remarks/>`, `<precisionlocation>`, `???` removed
```xml
<event version="2.0" uid="9405e320-..." type="b-m-p-s-m" ...>
  <point lat="10.00606" lon="95.00362" hae="9999999.0" .../>
  <detail>
    <status readiness="true"/>
    <link uid="ANDROID-0000000000000001" type="a-f-G-U-C"
          parent_callsign="SIM-01" relation="p-p"/>
    <contact callsign="R 1"/>
    <color argb="-65536"/>
    <usericon iconsetpath="COT_MAPPING_SPOTMAP/b-m-p-s-m/-65536"/>
  </detail>
</event>
```

**TAKPacketV2 proto fields** — kind derived from CoT type, color as palette enum
```
cot_type_id: 8 (b-m-p-s-m)    how: 3 (h-g-i-g-o)
callsign: "R 1"
latitude_i: 100060600          longitude_i: 950036200
marker {
  kind: 1 (Spot)               readiness: true
  color: 4 (Red)               color_argb: 0xFFFF0000
  parent_uid: "ANDROID-0000000000000001"
  parent_type: "a-f-G-U-C"
  parent_callsign: "SIM-01"
  iconset: "COT_MAPPING_SPOTMAP/b-m-p-s-m/-65536"
}
```

| Stage | Size | Reduction |
|-------|------|-----------|
| Raw XML | 721 B | — |
| Stripped | ~400 B | -44% |
| Compressed | **81 B** | **89% total** |

---

### Compression Summary

| Payload Type | Raw XML | Compressed | Ratio | Fits LoRa? |
|-------------|---------|------------|-------|------------|
| PLI (position) | 754 B | 151 B | 5.0x | ✅ |
| GeoChat (text) | 1,031 B | 80 B | 12.9x | ✅ |
| Rectangle (4 vertices) | 945 B | 101 B | 9.4x | ✅ |
| Circle (ellipse) | 851 B | 90 B | 9.5x | ✅ |
| Route (3 waypoints) | 890 B | ~80 B | 11.1x | ✅ |
| Marker (spot) | 721 B | 81 B | 8.9x | ✅ |

Median compression ratio across all 40 fixture types: **6.8×** (400-2300 bytes XML → 56-212 bytes wire).

### Wire Optimizations

The SDK applies several optimizations to minimize wire payload size:

| Optimization | Savings | Description |
|-------------|---------|-------------|
| **Endpoint normalization** | ~20 B/msg | Default endpoints (`0.0.0.0:4242:tcp`, `*:-1:stcp`) normalized to empty; builder restores the default on reconstruction |
| **Broadcast sentinel** | ~16 B/chat | `chat.to = "All Chat Rooms"` normalized to null (proto field omitted) |
| **Element stripping** | ~100-200 B/msg | Non-essential XML elements (`<takv>`, `<voice>`, `<precisionLocation>`, `<__geofence>`, `<marti>`, `<__shapeExtras>`, `<creator>`, `<tog>`, `<archive>`, `<strokeStyle>`, empty `<remarks>`) stripped before SDK parsing |
| **Attribute stripping** | ~30-80 B/msg | Display-only attributes stripped: `routetype`, `order`, `color` (from `<link_attr>`), `access`, empty `callsign`/`phone`, and all `"???"` placeholder values |
| **Route waypoint UID stripping** | ~40 B/waypoint | UUID `uid` attributes stripped from route `<link>` elements before compression. Builder generates deterministic UIDs (`{eventUid}-{index}`) on reconstruction. Saves ~120 bytes proto for a 3-waypoint route |
| **Stale time extension** | 0 B (metadata) | Static CoT types (routes `b-m-r`, shapes `u-d-*`, markers `b-m-p-*`) get a minimum 15-minute stale TTL before mesh transmission. Prevents iTAK's 2-min stale from expiring during multi-hop LoRa delivery |
| **Delta vertex encoding** | ~50% vs abs | Shape/route vertices stored as deltas from the event anchor point |
| **Remarks preservation** | variable | Non-empty `<remarks>` text preserved for shapes, markers, routes, casevac, emergency, and task types via top-level `string remarks = 24` proto field. `compressWithRemarksFallback()` strips remarks automatically if the compressed packet exceeds the LoRa MTU, so annotations survive when there's room |
| **zstd dictionary v2** | ~5-8x | Dictionaries trained on 760 protobuf samples covering all 11 payload types including remarks |

**Stripped elements and attributes are not needed for rendering** — the SDK extracts all structurally meaningful data (coordinates, waypoints, colors, stroke weight, method, direction, prefix) into typed proto fields. The stripped metadata is display-only, UI-state, or redundant with proto fields.

### Route Delivery via Data Package (ATAK)

ATAK silently ignores `b-m-r` route CoT events received over TCP streaming connections. Routes are only accepted from KML/GPX file import, TAK Server mission sync, or data packages auto-imported from `/sdcard/atak/tools/datapackage/`. iTAK does not have this limitation.

The Meshtastic Android app works around this by converting mesh-received routes into KML data packages:

1. Route arrives from mesh as a compressed TAKPacketV2
2. SDK decompresses and reconstructs the route XML with waypoint coordinates
3. `RouteDataPackageGenerator` extracts waypoints and generates a KML `<LineString>`
4. KML is packaged in a MissionPackageManifest v2 zip with `manifest.xml`
5. Zip is written to `/sdcard/atak/tools/datapackage/{routeUid}.zip`
6. ATAK auto-imports the data package and renders the route in Route Manager

```
Route over LoRa mesh (135 bytes)
    ↓ TakCompressor.decompress()
Route CoT XML (waypoints, method, direction)
    ↓ RouteDataPackageGenerator.generateDataPackage()
{routeUid}.zip
├── {routeUid}.kml      ← KML LineString with lon,lat,hae coordinates
└── manifest.xml         ← MissionPackageManifest v2
    ↓ AtakFileWriter.writeToImportDir()
/sdcard/atak/tools/datapackage/{routeUid}.zip
    ↓ ATAK auto-import
Route rendered in ATAK Route Manager
```

## Supported Platforms

| Platform | Language | Directory | Tests |
|----------|----------|-----------|-------|
| Android / ATAK Plugin | Kotlin | `kotlin/` | ✅ 211 |
| iOS / macOS | Swift | `swift/` | ✅ 152 |
| Windows / .NET | C# | `csharp/` | ✅ 191 |
| Web / Node.js | TypeScript | `typescript/` | ✅ 161 |
| CLI / Scripting | Python | `python/` | ✅ 153 |

Every platform is byte-interoperable: `.pb` and `.bin` golden files written by Kotlin's `CompressionTest.generate compression report` are consumed by the other four platforms for exact-match validation (within protobuf field-order tolerance) and full round-trip equivalence.

## Quick Start

### Kotlin
```kotlin
val parser = CotXmlParser()
val compressor = TakCompressor()

// Compress a CoT message for LoRa
val packet = parser.parse(cotXmlString)
val wirePayload = compressor.compress(packet)

// Decompress a received payload
val received = compressor.decompress(wirePayload)
val cotXml = CotXmlBuilder().build(received)
```

### Swift
```swift
let parser = CotXmlParser()
let compressor = TakCompressor()

// Compress
let packet = parser.parse(cotXmlString)
let wirePayload = try compressor.compress(packet)

// Decompress
let received = try compressor.decompress(wirePayload)
let cotXml = CotXmlBuilder().build(received)
```

### Python
```python
from meshtastic_tak import CotXmlParser, CotXmlBuilder, TakCompressor

parser = CotXmlParser()
compressor = TakCompressor()

# Compress
packet = parser.parse(cot_xml_string)
wire_payload = compressor.compress(packet)

# Decompress
received = compressor.decompress(wire_payload)
cot_xml = CotXmlBuilder().build(received)
```

### TypeScript
```typescript
import { parseCotXml, buildCotXml, TakCompressor } from "@meshtastic/takpacket-sdk";

const compressor = new TakCompressor();

// Compress
const packet = parseCotXml(cotXmlString);
const wirePayload = await compressor.compress(packet);

// Decompress
const received = await compressor.decompress(wirePayload);
const cotXml = buildCotXml(received);
```

### C#
```csharp
using Meshtastic.TAK;

var parser = new CotXmlParser();
var compressor = new TakCompressor();
var builder = new CotXmlBuilder();

// Compress
var packet = parser.Parse(cotXmlString);
var wirePayload = compressor.Compress(packet);

// Decompress
var received = compressor.Decompress(wirePayload);
var cotXml = builder.Build(received);
```

## API Reference

Each platform implements the same components with identical behavior:

| Class | Purpose |
|-------|---------|
| **CotXmlParser** | Parses a CoT XML event string into a `TAKPacketV2` protobuf with the appropriate typed payload variant |
| **CotXmlBuilder** | Builds a CoT XML event string from a `TAKPacketV2` protobuf (handles every typed variant including `raw_detail`) |
| **TakCompressor** | Compresses/decompresses `TAKPacketV2` using zstd dictionaries, with `compress()`, `compressWithRemarksFallback()`, and `compressWithStats()` entry points |
| **CotTypeMapper** | Maps CoT type strings to/from `CotType` enum values; classifies aircraft types for dictionary selection |
| **AtakPalette** | Bidirectional lookup between ATAK's 14-color palette and `Team` enum values, for color round-trip preservation |

## Dictionary Management

- **Training** — Dictionaries are trained in the [TAKPacket-ZTSD](https://github.com/meshtastic/TAKPacket-ZTSD) repository using real CoT XML corpora from TAK Server databases, augmented with synthetic shape/marker/route/casevac/emergency/task samples for the typed-payload extensions.
- **Shipping** — Each platform embeds the dictionaries as binary resources: **16 KB** non-aircraft + **4 KB** aircraft = 20 KB total.
- **Versioning** — The flags byte supports up to 62 dictionary IDs, allowing new dictionaries to be added without breaking backward compatibility. The current dictionaries are **v2** — retrained on the expanded corpus for the CasevacReport / EmergencyAlert / TaskRequest / GeoChat-receipts rollout. Dictionary IDs stay at 0 (non-aircraft) / 1 (aircraft), so a pre-v2 receiver can still decode post-v2 wire payloads at the cost of a slightly worse ratio.
- **Updates** — Retrained dictionaries are deployed via `TAKPacket-ZTSD/deploy.sh` and ship with SDK releases; old dictionary IDs remain valid on the wire.

## Testing

All five language implementations share the same test vectors in `testdata/`:

- **`cot_xml/`** — 40 input CoT XML fixtures captured from real ATAK-CIV, iTAK, and WebTAK clients (coordinates scrubbed to synthetic test ranges so no real user locations leak), covering 6 PLI variants, 3 GeoChat bodies + delivered/read receipts, 2 aircraft tracks, CASEVAC (bare + full 9-line), delete events, 8 drawings (circle, circle large, ellipse, freeform, polygon, rectangle, rectangle iTAK, telestration), 6 markers (2525, GoTo, GoTo iTAK, icon set, spot, tank), 3 ranging (bullseye, circle, line), 2 routes (3-waypoint canonical + iTAK variant), emergency alerts (911 + cancel), tasking, and the alert_tic / waypoint envelopes.
- **`protobuf/`** — Expected `TAKPacketV2` protobuf bytes (pre-compression), written by Kotlin's `CompressionTest`, consumed by every other platform for exact-match validation.
- **`golden/`** — Expected compressed wire payloads, byte-for-byte identical across platforms.

Run tests for each platform:
```bash
cd kotlin && ./gradlew test
cd swift && swift test
cd csharp && dotnet test
cd typescript && npm test
cd python && pytest
```

Or run all five at once:
```bash
./build.sh test
```

The Kotlin `CompressionTest.generate compression report` test regenerates `testdata/compression-report.md`, `testdata/protobuf/*.pb`, and `testdata/golden/*.bin` — it's the canonical fixture generator for the entire SDK.

## License

GPL-3.0 — see [LICENSE](LICENSE) for details.
