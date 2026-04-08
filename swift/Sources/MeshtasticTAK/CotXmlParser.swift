import Foundation

/// Parses a CoT XML event string into a TAKPacketV2 protobuf message.
public class CotXmlParser: NSObject, XMLParserDelegate {

    private static let teamNameToEnum: [String: Team] = [
        "White": .white, "Yellow": .yellow, "Orange": .orange, "Magenta": .magenta,
        "Red": .red, "Maroon": .maroon, "Purple": .purple, "Dark Blue": .darkBlue,
        "Blue": .blue, "Cyan": .cyan, "Teal": .teal, "Green": .green,
        "Dark Green": .darkGreen, "Brown": .brown,
    ]

    private static let roleNameToEnum: [String: MemberRole] = [
        "Team Member": .teamMember, "Team Lead": .teamLead, "HQ": .hq,
        "Sniper": .sniper, "Medic": .medic, "ForwardObserver": .forwardObserver,
        "RTO": .rto, "K9": .k9,
    ]

    // State during parsing
    private var packet = TAKPacketV2()
    private var currentElement = ""
    private var currentText = ""
    private var hasAircraftData = false
    private var hasChatData = false
    private var remarksText = ""
    private var icao = ""
    private var registration = ""
    private var flight = ""
    private var aircraftType = ""
    private var squawk: UInt32 = 0
    private var category = ""
    private var rssiX10: Int32 = 0
    private var gps = false
    private var cotHostId = ""
    private var chatTo: String?
    private var chatToCallsign: String?
    private var timeStr = ""
    private var staleStr = ""
    private var inRemarks = false

    public func parse(_ cotXml: String) -> TAKPacketV2 {
        // Reset state
        packet = TAKPacketV2()
        hasAircraftData = false
        hasChatData = false
        remarksText = ""
        icao = ""; registration = ""; flight = ""; aircraftType = ""
        squawk = 0; category = ""; rssiX10 = 0; gps = false; cotHostId = ""
        chatTo = nil; chatToCallsign = nil
        timeStr = ""; staleStr = ""
        inRemarks = false

        guard let data = cotXml.data(using: .utf8) else { return packet }
        let parser = XMLParser(data: data)
        parser.delegate = self
        parser.parse()

        // Parse aircraft data from remarks if ICAO not yet found
        if icao.isEmpty, !remarksText.isEmpty {
            if let match = remarksText.range(of: #"ICAO:\s*([A-Fa-f0-9]{6})"#, options: .regularExpression) {
                hasAircraftData = true
                let fullMatch = String(remarksText[match])
                icao = String(fullMatch.suffix(6))

                if let regMatch = remarksText.range(of: #"REG:\s*(\S+)"#, options: .regularExpression) {
                    registration = String(remarksText[regMatch]).replacingOccurrences(of: "REG: ", with: "").trimmingCharacters(in: .whitespaces)
                }
                if let fltMatch = remarksText.range(of: #"Flight:\s*(\S+)"#, options: .regularExpression) {
                    flight = String(remarksText[fltMatch]).replacingOccurrences(of: "Flight: ", with: "").trimmingCharacters(in: .whitespaces)
                }
                if let typeMatch = remarksText.range(of: #"Type:\s*(\S+)"#, options: .regularExpression) {
                    aircraftType = String(remarksText[typeMatch]).replacingOccurrences(of: "Type: ", with: "").trimmingCharacters(in: .whitespaces)
                }
                if let sqkMatch = remarksText.range(of: #"Squawk:\s*(\d+)"#, options: .regularExpression) {
                    let sqkStr = String(remarksText[sqkMatch]).replacingOccurrences(of: "Squawk: ", with: "").trimmingCharacters(in: .whitespaces)
                    squawk = UInt32(sqkStr) ?? 0
                }
                if category.isEmpty, let catMatch = remarksText.range(of: #"Category:\s*(\S+)"#, options: .regularExpression) {
                    category = String(remarksText[catMatch]).replacingOccurrences(of: "Category: ", with: "").trimmingCharacters(in: .whitespaces)
                }
            }
        }

        // Compute stale seconds
        packet.staleSeconds = computeStaleSeconds(timeStr, staleStr)

        // Set payload
        if hasChatData {
            var chat = GeoChat()
            chat.message = remarksText
            if let to = chatTo { chat.to = to }
            if let toCs = chatToCallsign { chat.toCallsign = toCs }
            packet.chat = chat
        } else if hasAircraftData {
            var aircraft = AircraftTrack()
            aircraft.icao = icao
            aircraft.registration = registration
            aircraft.flight = flight
            aircraft.aircraftType = aircraftType
            aircraft.squawk = squawk
            aircraft.category = category
            aircraft.rssiX10 = rssiX10
            aircraft.gps = gps
            aircraft.cotHostID = cotHostId
            packet.aircraft = aircraft
        } else {
            packet.pli = true
        }

        return packet
    }

    // MARK: - XMLParserDelegate

    public func parser(_ parser: XMLParser, didStartElement name: String,
                       namespaceURI: String?, qualifiedName: String?,
                       attributes: [String: String] = [:]) {
        currentElement = name
        currentText = ""

        switch name {
        case "event":
            packet.uid = attributes["uid"] ?? ""
            let typeStr = attributes["type"] ?? ""
            packet.cotTypeID = CotTypeMapper.typeToEnum(typeStr)
            if packet.cotTypeID == .other { packet.cotTypeStr = typeStr }
            packet.how = CotTypeMapper.howToEnum(attributes["how"] ?? "")
            timeStr = attributes["time"] ?? ""
            staleStr = attributes["stale"] ?? ""

        case "point":
            let lat = Double(attributes["lat"] ?? "0") ?? 0
            let lon = Double(attributes["lon"] ?? "0") ?? 0
            let hae = Double(attributes["hae"] ?? "0") ?? 0
            packet.latitudeI = Int32(lat * 1e7)
            packet.longitudeI = Int32(lon * 1e7)
            packet.altitude = Int32(hae)

        case "contact":
            packet.callsign = attributes["callsign"] ?? ""
            if let ep = attributes["endpoint"] { packet.endpoint = ep }
            if let ph = attributes["phone"] { packet.phone = ph }

        case "__group":
            if let teamName = attributes["name"] {
                packet.team = Self.teamNameToEnum[teamName] ?? .unspecifedColor
            }
            if let roleName = attributes["role"] {
                packet.role = Self.roleNameToEnum[roleName] ?? .unspecifed
            }

        case "status":
            packet.battery = UInt32(attributes["battery"] ?? "0") ?? 0

        case "track":
            let spd = Double(attributes["speed"] ?? "0") ?? 0
            let crs = Double(attributes["course"] ?? "0") ?? 0
            packet.speed = UInt32(spd * 100)
            packet.course = UInt32(crs * 100)

        case "takv":
            packet.takVersion = attributes["version"] ?? ""
            packet.takDevice = attributes["device"] ?? ""
            packet.takPlatform = attributes["platform"] ?? ""
            packet.takOs = attributes["os"] ?? ""

        case "precisionlocation":
            packet.geoSrc = parseGeoSrc(attributes["geopointsrc"])
            packet.altSrc = parseGeoSrc(attributes["altsrc"])

        case "uid", "UID":
            if let droid = attributes["Droid"] { packet.deviceCallsign = droid }

        case "_radio":
            if let rssiStr = attributes["rssi"], let rssi = Double(rssiStr) {
                rssiX10 = Int32(rssi * 10)
                hasAircraftData = true
            }
            gps = attributes["gps"] == "true"

        case "_aircot_":
            hasAircraftData = true
            icao = attributes["icao"] ?? ""
            registration = attributes["reg"] ?? ""
            flight = attributes["flight"] ?? ""
            category = attributes["cat"] ?? ""
            cotHostId = attributes["cot_host_id"] ?? ""

        case "__chat":
            hasChatData = true
            chatToCallsign = attributes["senderCallsign"]
            chatTo = attributes["id"]

        case "link":
            // Capture link uid for delete events etc.
            break

        case "remarks":
            inRemarks = true

        default:
            break
        }
    }

    public func parser(_ parser: XMLParser, foundCharacters string: String) {
        if inRemarks {
            currentText += string
        }
    }

    public func parser(_ parser: XMLParser, didEndElement name: String,
                       namespaceURI: String?, qualifiedName: String?) {
        if name == "remarks" {
            remarksText = currentText.trimmingCharacters(in: .whitespacesAndNewlines)
            inRemarks = false
        }
    }

    // MARK: - Helpers

    private func parseGeoSrc(_ src: String?) -> GeoPointSource {
        switch src {
        case "GPS": return .gps
        case "USER": return .user
        case "NETWORK": return .network
        default: return .unspecified
        }
    }

    private func computeStaleSeconds(_ timeStr: String, _ staleStr: String) -> UInt32 {
        guard !timeStr.isEmpty, !staleStr.isEmpty else { return 0 }
        let fmt = ISO8601DateFormatter()
        fmt.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        let fmtNoFrac = ISO8601DateFormatter()
        fmtNoFrac.formatOptions = [.withInternetDateTime]

        guard let time = fmt.date(from: timeStr) ?? fmtNoFrac.date(from: timeStr),
              let stale = fmt.date(from: staleStr) ?? fmtNoFrac.date(from: staleStr) else { return 0 }

        let diff = stale.timeIntervalSince(time)
        return diff > 0 ? UInt32(diff) : 0
    }
}
