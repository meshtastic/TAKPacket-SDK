# TAK Mesh Integration — Testing Punchlist

Manual testing checklist for ATAK ↔ iTAK over Meshtastic mesh. Covers all payload types supported by the TAKPacket-SDK v2 protocol.

**Setup:** ATAK on Android tablet connected to Meshtastic radio via the Meshtastic app's built-in TAK server. iTAK on iPad connected to a separate Meshtastic radio via the Meshtastic Apple app's built-in TAK server. Both radios on the same mesh channel.

---

## 1. PLI — Position Location Info

| # | Test | Direction | Steps | Expected | Pass |
|---|------|-----------|-------|----------|------|
| 1.1 | Basic PLI | ATAK → iTAK | ATAK moves on map | iTAK shows ATAK's position icon updating | ☐ |
| 1.2 | Basic PLI | iTAK → ATAK | iTAK moves on map | ATAK shows iTAK's position icon updating | ☐ |
| 1.3 | Callsign | Both | Set callsign on each client | Correct callsign displayed on the other | ☐ |
| 1.4 | Team color | Both | Set team color (e.g. Cyan, Red) | Correct team color on the other side | ☐ |
| 1.5 | Battery | Both | Check battery level shown | Battery % matches sender's device | ☐ |

## 2. GeoChat — Text Messages

| # | Test | Direction | Steps | Expected | Pass |
|---|------|-----------|-------|----------|------|
| 2.1 | Broadcast chat | ATAK → iTAK | Send message to "All Chat Rooms" | Message appears in iTAK chat with correct text and sender | ☐ |
| 2.2 | Broadcast chat | iTAK → ATAK | Send message to "All Chat Rooms" | Message appears in ATAK chat with correct text and sender | ☐ |
| 2.3 | Message text preserved | Both | Send "Hello from mesh!" | Exact text displayed, no empty messages | ☐ |
| 2.4 | Special characters | Both | Send message with `& < > "` | Characters displayed correctly (XML escaping works) | ☐ |
| 2.5 | Long message | Both | Send a ~150 character message | Message delivered (may be truncated if exceeds MTU) | ☐ |

## 3. Drawn Shapes — Geometry

| # | Test | Direction | Steps | Expected | Pass |
|---|------|-----------|-------|----------|------|
| 3.1 | Circle | ATAK → iTAK | Draw a circle on map | iTAK shows circle at same location with same size | ☐ |
| 3.2 | Circle | iTAK → ATAK | Draw a circle on map | ATAK shows circle at same location with same size | ☐ |
| 3.3 | Rectangle | ATAK → iTAK | Draw a rectangle | iTAK shows rectangle with correct vertices | ☐ |
| 3.4 | Rectangle | iTAK → ATAK | Draw a rectangle | ATAK shows rectangle with correct vertices | ☐ |
| 3.5 | Freeform polyline | ATAK → iTAK | Draw a freeform line | iTAK shows polyline with correct shape | ☐ |
| 3.6 | Freeform polyline | iTAK → ATAK | Draw a freeform line | ATAK shows polyline with correct shape | ☐ |
| 3.7 | Polygon | Either | Draw a closed polygon | Other side shows correct polygon shape | ☐ |
| 3.8 | Stroke color | Both | Draw shape with non-default color (e.g. Red) | Color preserved on the other side | ☐ |
| 3.9 | Fill color | Both | Draw shape with fill (semi-transparent) | Fill color and opacity preserved | ☐ |
| 3.10 | Shape with remarks | Both | Add description text to a shape | Remarks text preserved on the other side (if fits under MTU) | ☐ |
| 3.11 | Ellipse | Either | Draw an ellipse (major ≠ minor) | Correct ellipse proportions on other side | ☐ |

## 4. Markers — Points of Interest

| # | Test | Direction | Steps | Expected | Pass |
|---|------|-----------|-------|----------|------|
| 4.1 | Spot marker | ATAK → iTAK | Place a spot marker | iTAK shows marker at correct location with callsign | ☐ |
| 4.2 | Spot marker | iTAK → ATAK | Place a spot marker | ATAK shows marker at correct location with callsign | ☐ |
| 4.3 | Waypoint | Either | Place a waypoint | Other side shows waypoint marker | ☐ |
| 4.4 | Checkpoint | Either | Place a checkpoint | Other side shows checkpoint marker | ☐ |
| 4.5 | Marker color | Both | Set marker color (non-default) | Color preserved on the other side | ☐ |
| 4.6 | Custom icon marker | Either | Place marker with custom icon | Icon type/category preserved (iconset path) | ☐ |
| 4.7 | 2525 symbol | ATAK → iTAK | Place a 2525B military symbol | iTAK shows correct symbology | ☐ |
| 4.8 | Marker with remarks | Both | Add description to marker | Remarks preserved (if fits under MTU) | ☐ |

## 5. Routes — Waypoint Sequences

| # | Test | Direction | Steps | Expected | Pass |
|---|------|-----------|-------|----------|------|
| 5.1 | 3-waypoint route | iTAK → ATAK | Create route with 3 waypoints | ATAK imports route via data package, visible in Route Manager | ☐ |
| 5.2 | 3-waypoint route | ATAK → iTAK | Create route with 3 waypoints | iTAK shows route on map with waypoints | ☐ |
| 5.3 | Route name | Both | Name the route | Route name (callsign) preserved on the other side | ☐ |
| 5.4 | Walking method | Either | Create Walking route | Travel method preserved | ☐ |
| 5.5 | Infil/Exfil direction | Either | Set route direction | Direction preserved | ☐ |
| 5.6 | 5+ waypoint route | Either | Create route with 5-6 waypoints | Route fits under 225B MTU (UID stripping saves space) | ☐ |
| 5.7 | Route with remarks | Either | Add description to route | Remarks text preserved (if fits) | ☐ |

## 6. Range and Bearing

| # | Test | Direction | Steps | Expected | Pass |
|---|------|-----------|-------|----------|------|
| 6.1 | RAB line | ATAK → iTAK | Create range/bearing measurement | iTAK shows line with distance and bearing | ☐ |
| 6.2 | RAB line | iTAK → ATAK | Create range/bearing measurement | ATAK shows line with distance and bearing | ☐ |

## 7. Emergency Alerts

| # | Test | Direction | Steps | Expected | Pass |
|---|------|-----------|-------|----------|------|
| 7.1 | 911 alert | Either | Trigger 911 emergency | Other side shows emergency alert | ☐ |
| 7.2 | Cancel alert | Either | Cancel the emergency | Other side removes/cancels the alert | ☐ |

## 8. CASEVAC / MEDEVAC (9-Line)

| # | Test | Direction | Steps | Expected | Pass |
|---|------|-----------|-------|----------|------|
| 8.1 | Basic CASEVAC | ATAK → iTAK | Create 9-line MEDEVAC request | iTAK receives with precedence, patient counts | ☐ |
| 8.2 | Full 9-line | Either | Fill all 9 lines | All fields preserved: precedence, equipment, HLZ marking, security, patients, terrain | ☐ |

## 9. Tasking

| # | Test | Direction | Steps | Expected | Pass |
|---|------|-----------|-------|----------|------|
| 9.1 | Task request | Either | Create engage/observe task | Other side receives task with type, target, priority | ☐ |

## 10. Delete Events

| # | Test | Direction | Steps | Expected | Pass |
|---|------|-----------|-------|----------|------|
| 10.1 | Delete marker | Either | Delete a previously sent marker | Other side removes the marker from the map | ☐ |
| 10.2 | Delete shape | Either | Delete a previously sent shape | Other side removes the shape | ☐ |

---

## Feature-Level Verification

### Stale Time Extension

| # | Test | Steps | Expected | Pass |
|---|------|-------|----------|------|
| F.1 | Route stale | Send route from iTAK (2-min default stale) | Route still renders on ATAK (stale extended to 15 min) | ☐ |
| F.2 | Shape stale | Send shape from iTAK | Shape still renders on the other side (stale extended) | ☐ |
| F.3 | PLI stale unchanged | Send PLI | Stale NOT extended (dynamic position, short stale is correct) | ☐ |

### Remarks Preservation (new feature)

| # | Test | Steps | Expected | Pass |
|---|------|-------|----------|------|
| F.4 | Shape with short remarks | Add "Enemy OP spotted" to shape | Remarks visible on the other side | ☐ |
| F.5 | Marker with short remarks | Add "Resupply point" to marker | Remarks visible on the other side | ☐ |
| F.6 | Shape with very long remarks | Add 500+ character description | Remarks auto-stripped to fit MTU; shape still arrives | ☐ |
| F.7 | Route with remarks | Add description to route | Remarks preserved if fits; route still works without them | ☐ |

### Route Data Package (ATAK-specific)

| # | Test | Steps | Expected | Pass |
|---|------|-------|----------|------|
| F.8 | Route appears in Route Manager | Send route from iTAK → ATAK | ATAK auto-imports KML data package; route in Route Manager | ☐ |
| F.9 | Route waypoints match | Compare waypoint positions | Coordinates match between sender and receiver | ☐ |

### GeoChat Remarks Fix (regression check)

| # | Test | Steps | Expected | Pass |
|---|------|-------|----------|------|
| F.10 | iTAK→ATAK chat text | Send "Test message" from iTAK | ATAK shows "Test message" (not empty) | ☐ |
| F.11 | ATAK→iTAK chat text | Send "Test message" from ATAK | iTAK shows "Test message" (not empty) | ☐ |

### Node Broadcast Removed (regression check)

| # | Test | Steps | Expected | Pass |
|---|------|-------|----------|------|
| F.12 | No phantom nodes | Connect ATAK to mesh with 50+ nodes | ATAK does NOT show all mesh nodes as TAK contacts — only actual TAK users | ☐ |

---

## Compression Sanity Checks

| # | Test | Steps | Expected | Pass |
|---|------|-------|----------|------|
| C.1 | PLI under MTU | Send PLI, check Android logs | Compressed size ~70B (well under 225B limit) | ☐ |
| C.2 | GeoChat under MTU | Send chat, check logs | Compressed size ~80-120B | ☐ |
| C.3 | Shape under MTU | Send rectangle, check logs | Compressed size ~100B | ☐ |
| C.4 | Route under MTU | Send 3-waypoint route, check logs | Compressed size ~80-135B | ☐ |
| C.5 | No dropped packets | Monitor logs during all tests | No "Dropping oversized" warnings | ☐ |

---

## Test Pass Summary

| Category | Total | Passed | Failed | Blocked |
|----------|-------|--------|--------|---------|
| PLI | 5 | | | |
| GeoChat | 5 | | | |
| Shapes | 11 | | | |
| Markers | 8 | | | |
| Routes | 7 | | | |
| Range & Bearing | 2 | | | |
| Emergency | 2 | | | |
| CASEVAC | 2 | | | |
| Tasking | 1 | | | |
| Delete | 2 | | | |
| Features | 12 | | | |
| Compression | 5 | | | |
| **Total** | **62** | | | |
