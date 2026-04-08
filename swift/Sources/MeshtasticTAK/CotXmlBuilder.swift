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
            s += "    <remarks>\(esc(chat.message))</remarks>\n"
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
        default:
            break
        }

        s += "  </detail>\n"
        s += "</event>"
        return s
    }

    private func esc(_ s: String) -> String {
        s.replacingOccurrences(of: "&", with: "&amp;")
         .replacingOccurrences(of: "<", with: "&lt;")
         .replacingOccurrences(of: ">", with: "&gt;")
         .replacingOccurrences(of: "\"", with: "&quot;")
         .replacingOccurrences(of: "'", with: "&apos;")
    }
}
