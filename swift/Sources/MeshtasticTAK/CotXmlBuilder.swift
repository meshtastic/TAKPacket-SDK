import Foundation

/// Builds a CoT XML event string from a TAKPacketV2 protobuf message.
public class CotXmlBuilder {

    private static let teamEnumToName: [Team: String] = [
        .white: "White", .yellow: "Yellow", .orange: "Orange", .magenta: "Magenta",
        .red: "Red", .maroon: "Maroon", .purple: "Purple", .darkBlue: "Dark Blue",
        .blue: "Blue", .cyan: "Cyan", .teal: "Teal", .green: "Green",
        .darkGreen: "Dark Green", .brown: "Brown",
    ]

    private static let roleEnumToName: [MemberRole: String] = [
        .teamMember: "Team Member", .teamLead: "Team Lead", .hq: "HQ",
        .sniper: "Sniper", .medic: "Medic", .forwardObserver: "ForwardObserver",
        .rto: "RTO", .k9: "K9",
    ]

    // --- Reverse lookups for route/bullseye fields ------------------------
    private static let routeMethodIntToName: [Route.Method: String] = [
        .driving: "Driving", .walking: "Walking", .flying: "Flying",
        .swimming: "Swimming", .watercraft: "Watercraft",
    ]
    private static let routeDirectionIntToName: [Route.Direction: String] = [
        .infil: "Infil", .exfil: "Exfil",
    ]
    private static let bearingRefIntToName: [UInt32: String] = [
        1: "M", 2: "T", 3: "G",
    ]

    private static func geoSrcStr(_ src: GeoPointSource) -> String {
        switch src {
        case .gps: return "GPS"
        case .user: return "USER"
        case .network: return "NETWORK"
        default: return "???"
        }
    }

    public init() {}

    public func build(_ packet: TAKPacketV2) -> String {
        let now = ISO8601DateFormatter().string(from: Date())
        let staleSecs = max(Int(packet.staleSeconds), 45)
        let stale = ISO8601DateFormatter().string(from: Date().addingTimeInterval(TimeInterval(staleSecs)))

        let cotType = CotTypeMapper.typeToString(packet.cotTypeID) ?? packet.cotTypeStr
        let how = CotTypeMapper.howToString(packet.how) ?? "m-g"

        let lat = Double(packet.latitudeI) / 1e7
        let lon = Double(packet.longitudeI) / 1e7

        var s = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
        s += "<event version=\"2.0\" uid=\"\(esc(packet.uid))\" type=\"\(esc(cotType))\" how=\"\(esc(how))\" "
        s += "time=\"\(now)\" start=\"\(now)\" stale=\"\(stale)\">\n"
        s += "  <point lat=\"\(lat)\" lon=\"\(lon)\" hae=\"\(packet.altitude)\" ce=\"9999999\" le=\"9999999\"/>\n"
        s += "  <detail>\n"

        if !packet.callsign.isEmpty {
            s += "    <contact callsign=\"\(esc(packet.callsign))\""
            if !packet.endpoint.isEmpty { s += " endpoint=\"\(esc(packet.endpoint))\"" }
            if !packet.phone.isEmpty { s += " phone=\"\(esc(packet.phone))\"" }
            s += "/>\n"
        }

        let teamName = Self.teamEnumToName[packet.team]
        let roleName = Self.roleEnumToName[packet.role]
        if teamName != nil || roleName != nil {
            var tag = "    <__group"
            if let r = roleName { tag += " role=\"\(r)\"" }
            if let t = teamName { tag += " name=\"\(t)\"" }
            s += tag + "/>\n"
        }

        if packet.battery > 0 {
            s += "    <status battery=\"\(packet.battery)\"/>\n"
        }

        if packet.speed > 0 || packet.course > 0 {
            let speedMs = Double(packet.speed) / 100.0
            let courseDeg = Double(packet.course) / 100.0
            s += "    <track speed=\"\(speedMs)\" course=\"\(courseDeg)\"/>\n"
        }

        if !packet.takVersion.isEmpty || !packet.takPlatform.isEmpty {
            s += "    <takv"
            if !packet.takDevice.isEmpty { s += " device=\"\(esc(packet.takDevice))\"" }
            if !packet.takPlatform.isEmpty { s += " platform=\"\(esc(packet.takPlatform))\"" }
            if !packet.takOs.isEmpty { s += " os=\"\(esc(packet.takOs))\"" }
            if !packet.takVersion.isEmpty { s += " version=\"\(esc(packet.takVersion))\"" }
            s += "/>\n"
        }

        if packet.geoSrc != .unspecified || packet.altSrc != .unspecified {
            s += "    <precisionlocation geopointsrc=\"\(Self.geoSrcStr(packet.geoSrc))\" altsrc=\"\(Self.geoSrcStr(packet.altSrc))\"/>\n"
        }

        if !packet.deviceCallsign.isEmpty {
            s += "    <uid Droid=\"\(esc(packet.deviceCallsign))\"/>\n"
        }

        // Payload-specific elements
        switch packet.payloadVariant {
        case .chat(let chat):
            if chat.receiptType != .none && !chat.receiptForUid.isEmpty {
                // Delivered / read receipt: emit a <link> pointing at the
                // original message UID. The envelope cot_type_id already
                // distinguishes delivered vs read.
                s += "    <link uid=\"\(esc(chat.receiptForUid))\" relation=\"p-p\" type=\"b-t-f\"/>\n"
            } else {
                // Reconstruct the full __chat element that ATAK/iTAK needs
                // for routing and display. GeoChat event UID format:
                // GeoChat.{senderUid}.{chatroom}.{messageId}
                let gcParts = packet.uid.split(separator: ".", maxSplits: 3).map(String.init)
                if gcParts.count == 4 && gcParts[0] == "GeoChat" {
                    let senderUid = gcParts[1]
                    let chatroom = gcParts[2]
                    let msgId = gcParts[3]
                    let senderCs: String = {
                        if !chat.toCallsign.isEmpty { return chat.toCallsign }
                        return packet.callsign.isEmpty ? "UNKNOWN" : packet.callsign
                    }()
                    s += "    <__chat parent=\"RootContactGroup\" groupOwner=\"false\""
                    s += " messageId=\"\(esc(msgId))\" chatroom=\"\(esc(chatroom))\""
                    s += " id=\"\(esc(chatroom))\" senderCallsign=\"\(esc(senderCs))\">\n"
                    s += "      <chatgrp uid0=\"\(esc(senderUid))\" uid1=\"\(esc(chatroom))\" id=\"\(esc(chatroom))\"/>\n"
                    s += "    </__chat>\n"
                    s += "    <link uid=\"\(esc(senderUid))\" type=\"a-f-G-U-C\" relation=\"p-p\"/>\n"
                    s += "    <__serverdestination destinations=\"0.0.0.0:4242:tcp:\(esc(senderUid))\"/>\n"
                    s += "    <remarks source=\"BAO.F.ATAK.\(esc(senderUid))\" to=\"\(esc(chatroom))\" time=\"\(now)\">\(esc(chat.message))</remarks>\n"
                } else {
                    s += "    <remarks>\(esc(chat.message))</remarks>\n"
                }
            }
        case .aircraft(let ac):
            if !ac.icao.isEmpty {
                s += "    <_aircot_"
                s += " icao=\"\(esc(ac.icao))\""
                if !ac.registration.isEmpty { s += " reg=\"\(esc(ac.registration))\"" }
                if !ac.flight.isEmpty { s += " flight=\"\(esc(ac.flight))\"" }
                if !ac.category.isEmpty { s += " cat=\"\(esc(ac.category))\"" }
                if !ac.cotHostID.isEmpty { s += " cot_host_id=\"\(esc(ac.cotHostID))\"" }
                s += "/>\n"
            }
            if ac.squawk > 0 {
                var parts: [String] = []
                if !ac.icao.isEmpty { parts.append("ICAO: \(ac.icao)") }
                if !ac.registration.isEmpty { parts.append("REG: \(ac.registration)") }
                if !ac.aircraftType.isEmpty { parts.append("Type: \(ac.aircraftType)") }
                parts.append("Squawk: \(ac.squawk)")
                if !ac.flight.isEmpty { parts.append("Flight: \(ac.flight)") }
                s += "    <remarks>\(esc(parts.joined(separator: " ")))</remarks>\n"
            }
            if ac.rssiX10 != 0 {
                let rssi = Double(ac.rssiX10) / 10.0
                s += "    <_radio rssi=\"\(rssi)\""
                if ac.gps { s += " gps=\"true\"" }
                s += "/>\n"
            }
        case .shape(let shape):
            s += emitShape(shape, eventLatI: packet.latitudeI, eventLonI: packet.longitudeI, uid: packet.uid)
        case .marker(let marker):
            s += emitMarker(marker)
        case .rab(let rab):
            s += emitRab(rab, eventLatI: packet.latitudeI, eventLonI: packet.longitudeI)
        case .route(let route):
            s += emitRoute(route, eventLatI: packet.latitudeI, eventLonI: packet.longitudeI)
        case .casevac(let c):
            s += emitCasevac(c)
        case .emergency(let e):
            s += emitEmergency(e)
        case .task(let t):
            s += emitTask(t)
        case .rawDetail(let bytes):
            // Fallback path (`TakCompressor.compressBestOf`): the original
            // <detail> inner bytes are shipped verbatim and re-emitted
            // without any normalization so the receiver round trip stays
            // byte-exact with the source XML.
            if !bytes.isEmpty, let text = String(data: bytes, encoding: .utf8) {
                s += text
                if !text.hasSuffix("\n") { s += "\n" }
            }
        default:
            break
        }

        s += "  </detail>\n"
        s += "</event>"
        return s
    }

    // MARK: - Typed geometry emitters

    private func emitShape(_ shape: DrawnShape, eventLatI: Int32, eventLonI: Int32, uid: String) -> String {
        var s = ""
        // ATAK XML writes colors as signed Int32 decimal (e.g. -65536 for red).
        // The proto-generated `_argb` fields are UInt32; bit-cast on emit.
        let strokeArgbUInt = AtakPalette.resolveColor(palette: shape.strokeColor, fallback: shape.strokeArgb)
        let fillArgbUInt = AtakPalette.resolveColor(palette: shape.fillColor, fallback: shape.fillArgb)
        let strokeVal = Int32(bitPattern: strokeArgbUInt)
        let fillVal = Int32(bitPattern: fillArgbUInt)
        let emitStroke = shape.style == .strokeOnly || shape.style == .strokeAndFill ||
            (shape.style == .unspecified && strokeVal != 0)
        let emitFill = shape.style == .fillOnly || shape.style == .strokeAndFill ||
            (shape.style == .unspecified && fillVal != 0)

        // Circle-like kinds use <shape><ellipse/></shape>.
        // Polyline-like kinds (rectangle, freeform, polygon, telestration)
        // emit vertices as <link point> siblings that the parser treats as
        // the shape's vertex list.
        let kind = shape.kind
        if kind == .circle || kind == .rangingCircle || kind == .bullseye || kind == .ellipse {
            if shape.majorCm > 0 || shape.minorCm > 0 {
                let majorM = Double(shape.majorCm) / 100.0
                let minorM = Double(shape.minorCm) / 100.0
                let strokeW = Double(shape.strokeWeightX10) / 10.0
                s += "    <shape>\n"
                s += "      <ellipse major=\"\(majorM)\" minor=\"\(minorM)\" angle=\"\(shape.angleDeg)\"/>\n"
                // KML style link — iTAK requires this to render circles/ellipses.
                s += "      <link uid=\"\(esc(uid)).Style\" type=\"b-x-KmlStyle\" relation=\"p-c\">"
                s += "<Style><LineStyle><color>\(Self.argbToAbgrHex(strokeVal))</color><width>\(strokeW)</width></LineStyle>"
                if fillVal != 0 {
                    s += "<PolyStyle><color>\(Self.argbToAbgrHex(fillVal))</color></PolyStyle>"
                }
                s += "</Style></link>\n"
                s += "    </shape>\n"
            }
        } else {
            for v in shape.vertices {
                let vlat = Double(eventLatI + v.latDeltaI) / 1e7
                let vlon = Double(eventLonI + v.lonDeltaI) / 1e7
                s += "    <link point=\"\(vlat),\(vlon)\"/>\n"
            }
        }

        if kind == .bullseye {
            s += "    <bullseye"
            if shape.bullseyeDistanceDm > 0 {
                let distM = Double(shape.bullseyeDistanceDm) / 10.0
                s += " distance=\"\(distM)\""
            }
            if let ref = Self.bearingRefIntToName[shape.bullseyeBearingRef] {
                s += " bearingRef=\"\(ref)\""
            }
            if shape.bullseyeFlags & 0x01 != 0 { s += " rangeRingVisible=\"true\"" }
            if shape.bullseyeFlags & 0x02 != 0 { s += " hasRangeRings=\"true\"" }
            if shape.bullseyeFlags & 0x04 != 0 { s += " edgeToCenter=\"true\"" }
            if shape.bullseyeFlags & 0x08 != 0 { s += " mils=\"true\"" }
            if !shape.bullseyeUidRef.isEmpty {
                s += " bullseyeUID=\"\(esc(shape.bullseyeUidRef))\""
            }
            s += "/>\n"
        }

        if emitStroke {
            s += "    <strokeColor value=\"\(strokeVal)\"/>\n"
            if shape.strokeWeightX10 > 0 {
                let w = Double(shape.strokeWeightX10) / 10.0
                s += "    <strokeWeight value=\"\(w)\"/>\n"
            }
        }
        if emitFill {
            s += "    <fillColor value=\"\(fillVal)\"/>\n"
        }
        s += "    <labels_on value=\"\(shape.labelsOn)\"/>\n"
        return s
    }

    private func emitMarker(_ marker: Marker) -> String {
        var s = ""
        if marker.readiness {
            s += "    <status readiness=\"true\"/>\n"
        }
        if !marker.parentUid.isEmpty {
            s += "    <link"
            s += " uid=\"\(esc(marker.parentUid))\""
            if !marker.parentType.isEmpty {
                s += " type=\"\(esc(marker.parentType))\""
            }
            if !marker.parentCallsign.isEmpty {
                s += " parent_callsign=\"\(esc(marker.parentCallsign))\""
            }
            s += " relation=\"p-p\"/>\n"
        }
        let colorArgbUInt = AtakPalette.resolveColor(palette: marker.color, fallback: marker.colorArgb)
        let colorVal = Int32(bitPattern: colorArgbUInt)
        if colorVal != 0 {
            s += "    <color argb=\"\(colorVal)\"/>\n"
        }
        if !marker.iconset.isEmpty {
            s += "    <usericon iconsetpath=\"\(esc(marker.iconset))\"/>\n"
        }
        return s
    }

    private func emitRab(_ rab: RangeAndBearing, eventLatI: Int32, eventLonI: Int32) -> String {
        var s = ""
        let anchorLatI = eventLatI + rab.anchor.latDeltaI
        let anchorLonI = eventLonI + rab.anchor.lonDeltaI
        if anchorLatI != 0 || anchorLonI != 0 {
            let alat = Double(anchorLatI) / 1e7
            let alon = Double(anchorLonI) / 1e7
            s += "    <link"
            if !rab.anchorUid.isEmpty {
                s += " uid=\"\(esc(rab.anchorUid))\""
            }
            s += " relation=\"p-p\" type=\"b-m-p-w\" point=\"\(alat),\(alon)\"/>\n"
        }
        if rab.rangeCm > 0 {
            let rangeM = Double(rab.rangeCm) / 100.0
            s += "    <range value=\"\(rangeM)\"/>\n"
        }
        if rab.bearingCdeg > 0 {
            let bearingDeg = Double(rab.bearingCdeg) / 100.0
            s += "    <bearing value=\"\(bearingDeg)\"/>\n"
        }
        let strokeArgbUInt = AtakPalette.resolveColor(palette: rab.strokeColor, fallback: rab.strokeArgb)
        let strokeVal = Int32(bitPattern: strokeArgbUInt)
        if strokeVal != 0 {
            s += "    <strokeColor value=\"\(strokeVal)\"/>\n"
        }
        if rab.strokeWeightX10 > 0 {
            let w = Double(rab.strokeWeightX10) / 10.0
            s += "    <strokeWeight value=\"\(w)\"/>\n"
        }
        return s
    }

    private func emitRoute(_ route: Route, eventLatI: Int32, eventLonI: Int32) -> String {
        var s = "    <__routeinfo/>\n"
        s += "    <link_attr"
        if let m = Self.routeMethodIntToName[route.method] { s += " method=\"\(m)\"" }
        if let d = Self.routeDirectionIntToName[route.direction] { s += " direction=\"\(d)\"" }
        if !route.prefix.isEmpty { s += " prefix=\"\(esc(route.prefix))\"" }
        if route.strokeWeightX10 > 0 {
            let sw = Double(route.strokeWeightX10) / 10.0
            s += " stroke=\"\(sw)\""
        }
        s += "/>\n"
        for link in route.links {
            let llat = Double(eventLatI + link.point.latDeltaI) / 1e7
            let llon = Double(eventLonI + link.point.lonDeltaI) / 1e7
            s += "    <link"
            if !link.uid.isEmpty { s += " uid=\"\(esc(link.uid))\"" }
            let linkType = link.linkType == 1 ? "b-m-p-c" : "b-m-p-w"
            s += " type=\"\(linkType)\""
            if !link.callsign.isEmpty { s += " callsign=\"\(esc(link.callsign))\"" }
            s += " point=\"\(llat),\(llon)\"/>\n"
        }
        return s
    }

    private static let precedenceIntToName: [CasevacReport.Precedence: String] = [
        .urgent: "Urgent", .urgentSurgical: "Urgent Surgical",
        .priority: "Priority", .routine: "Routine", .convenience: "Convenience",
    ]
    private static let hlzMarkingIntToName: [CasevacReport.HlzMarking: String] = [
        .panels: "Panels", .pyroSignal: "Pyro", .smoke: "Smoke",
        .none: "None", .other: "Other",
    ]
    private static let securityIntToName: [CasevacReport.Security: String] = [
        .noEnemy: "N", .possibleEnemy: "P",
        .enemyInArea: "E", .enemyInArmedContact: "X",
    ]
    private static let emergencyTypeIntToName: [EmergencyAlert.TypeEnum: String] = [
        .alert911: "911 Alert", .ringTheBell: "Ring The Bell",
        .inContact: "In Contact", .geoFenceBreached: "Geo-fence Breached",
        .custom: "Custom", .cancel: "Cancel",
    ]
    private static let taskPriorityIntToName: [TaskRequest.Priority: String] = [
        .low: "Low", .normal: "Normal", .high: "High", .critical: "Critical",
    ]
    private static let taskStatusIntToName: [TaskRequest.Status: String] = [
        .pending: "Pending", .acknowledged: "Acknowledged",
        .inProgress: "In Progress", .completed: "Completed", .cancelled: "Cancelled",
    ]

    private func emitCasevac(_ c: CasevacReport) -> String {
        var s = "    <_medevac_"
        if let p = Self.precedenceIntToName[c.precedence] {
            s += " precedence=\"\(p)\""
        }
        if c.equipmentFlags & 0x01 != 0 { s += " none=\"true\"" }
        if c.equipmentFlags & 0x02 != 0 { s += " hoist=\"true\"" }
        if c.equipmentFlags & 0x04 != 0 { s += " extraction_equipment=\"true\"" }
        if c.equipmentFlags & 0x08 != 0 { s += " ventilator=\"true\"" }
        if c.equipmentFlags & 0x10 != 0 { s += " blood=\"true\"" }
        if c.litterPatients > 0 { s += " litter=\"\(c.litterPatients)\"" }
        if c.ambulatoryPatients > 0 { s += " ambulatory=\"\(c.ambulatoryPatients)\"" }
        if let sec = Self.securityIntToName[c.security] {
            s += " security=\"\(sec)\""
        }
        if let hlz = Self.hlzMarkingIntToName[c.hlzMarking] {
            s += " hlz_marking=\"\(hlz)\""
        }
        if !c.zoneMarker.isEmpty { s += " zone_prot_marker=\"\(esc(c.zoneMarker))\"" }
        if c.usMilitary > 0 { s += " us_military=\"\(c.usMilitary)\"" }
        if c.usCivilian > 0 { s += " us_civilian=\"\(c.usCivilian)\"" }
        if c.nonUsMilitary > 0 { s += " non_us_military=\"\(c.nonUsMilitary)\"" }
        if c.nonUsCivilian > 0 { s += " non_us_civilian=\"\(c.nonUsCivilian)\"" }
        if c.epw > 0 { s += " epw=\"\(c.epw)\"" }
        if c.child > 0 { s += " child=\"\(c.child)\"" }
        if c.terrainFlags & 0x01 != 0 { s += " terrain_slope=\"true\"" }
        if c.terrainFlags & 0x02 != 0 { s += " terrain_rough=\"true\"" }
        if c.terrainFlags & 0x04 != 0 { s += " terrain_loose=\"true\"" }
        if c.terrainFlags & 0x08 != 0 { s += " terrain_trees=\"true\"" }
        if c.terrainFlags & 0x10 != 0 { s += " terrain_wires=\"true\"" }
        if c.terrainFlags & 0x20 != 0 { s += " terrain_other=\"true\"" }
        if !c.frequency.isEmpty { s += " freq=\"\(esc(c.frequency))\"" }
        s += "/>\n"
        return s
    }

    private func emitEmergency(_ e: EmergencyAlert) -> String {
        var s = "    <emergency"
        if e.type == .cancel {
            s += " cancel=\"true\""
        } else if let t = Self.emergencyTypeIntToName[e.type] {
            s += " type=\"\(t)\""
        }
        s += "/>\n"
        if !e.authoringUid.isEmpty {
            s += "    <link uid=\"\(esc(e.authoringUid))\" relation=\"p-p\" type=\"a-f-G-U-C\"/>\n"
        }
        if !e.cancelReferenceUid.isEmpty {
            s += "    <link uid=\"\(esc(e.cancelReferenceUid))\" relation=\"p-p\" type=\"b-a-o-tbl\"/>\n"
        }
        return s
    }

    private func emitTask(_ t: TaskRequest) -> String {
        var s = "    <task"
        if !t.taskType.isEmpty { s += " type=\"\(esc(t.taskType))\"" }
        if let p = Self.taskPriorityIntToName[t.priority] { s += " priority=\"\(p)\"" }
        if let st = Self.taskStatusIntToName[t.status] { s += " status=\"\(st)\"" }
        if !t.assigneeUid.isEmpty { s += " assignee=\"\(esc(t.assigneeUid))\"" }
        if !t.note.isEmpty { s += " note=\"\(esc(t.note))\"" }
        s += "/>\n"
        if !t.targetUid.isEmpty {
            s += "    <link uid=\"\(esc(t.targetUid))\" relation=\"p-p\" type=\"a-f-G\"/>\n"
        }
        return s
    }

    /// Convert ARGB int to ABGR hex string (KML color format).
    private static func argbToAbgrHex(_ argb: Int32) -> String {
        let a = (argb >> 24) & 0xFF
        let r = (argb >> 16) & 0xFF
        let g = (argb >> 8) & 0xFF
        let b = argb & 0xFF
        return String(format: "%02x%02x%02x%02x", a, b, g, r)
    }

    private func esc(_ s: String) -> String {
        s.replacingOccurrences(of: "&", with: "&amp;")
         .replacingOccurrences(of: "<", with: "&lt;")
         .replacingOccurrences(of: ">", with: "&gt;")
         .replacingOccurrences(of: "\"", with: "&quot;")
         .replacingOccurrences(of: "'", with: "&apos;")
    }
}
