import Foundation

/// Parses a CoT XML event string into a TAKPacketV2 protobuf message.
public class CotXmlParser: NSObject, XMLParserDelegate {

    /// Vertex pool cap — matches `*DrawnShape.vertices max_count:32` in atak.options.
    /// Longer vertex lists are truncated and `truncated = true` is set.
    public static let maxVertices = 32

    /// Route link pool cap — matches `*Route.links max_count:16` in atak.options.
    public static let maxRouteLinks = 16

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

    private static let routeMethodMap: [String: Route.Method] = [
        "Driving": .driving, "Walking": .walking, "Flying": .flying,
        "Swimming": .swimming, "Watercraft": .watercraft,
    ]

    private static let routeDirectionMap: [String: Route.Direction] = [
        "Infil": .infil, "Exfil": .exfil,
    ]

    private static let bearingRefMap: [String: UInt32] = [
        "M": 1, "T": 2, "G": 3,
    ]

    // State during parsing
    private var packet = TAKPacketV2()
    private var cotTypeStr = ""
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

    // --- Drawn shape accumulators --------------------------------------
    private var hasShapeData = false
    private var inShape = false
    private var shapeMajorCm: UInt32 = 0
    private var shapeMinorCm: UInt32 = 0
    private var shapeAngleDeg: UInt32 = 360
    private var sawStrokeColor = false
    private var sawFillColor = false
    // ARGB stored as UInt32 to match the proto-generated `fixed32 _argb`
    // field type. Parsed from ATAK's signed decimal via Int32 → UInt32
    // bit-pattern conversion.
    private var strokeColorArgb: UInt32 = 0
    private var strokeWeightX10: UInt32 = 0
    private var fillColorArgb: UInt32 = 0
    private var labelsOn = false
    /// Accumulated absolute vertex coordinates. Converted to delta-from-event
    /// at the end of parse() before being attached to the DrawnShape proto.
    private var verticesAbs: [(Int32, Int32)] = []
    private var verticesTruncated = false

    // --- Bullseye accumulators ------------------------------------------
    private var bullseyeDistanceDm: UInt32 = 0
    private var bullseyeBearingRef: UInt32 = 0
    private var bullseyeFlags: UInt32 = 0
    private var bullseyeUidRef = ""

    // --- Marker accumulators --------------------------------------------
    private var hasMarkerData = false
    private var markerColorArgb: UInt32 = 0
    private var markerReadiness = false
    private var markerParentUid = ""
    private var markerParentType = ""
    private var markerParentCallsign = ""
    private var markerIconset = ""

    // --- Range and bearing accumulators ---------------------------------
    private var hasRabData = false
    private var rabAnchorLatI: Int32 = 0
    private var rabAnchorLonI: Int32 = 0
    private var rabAnchorUid = ""
    private var rabRangeCm: UInt32 = 0
    private var rabBearingCdeg: UInt32 = 0

    // --- Route accumulators ---------------------------------------------
    // Holds ABSOLUTE lat/lon during parsing — converted to deltas relative
    // to the event anchor at the end of parse().
    private struct RouteLinkAbs {
        var latI: Int32
        var lonI: Int32
        var uid: String
        var callsign: String
        var linkType: UInt32
    }
    private var hasRouteData = false
    private var routeLinksAbs: [RouteLinkAbs] = []
    private var routeTruncated = false
    private var routePrefix = ""
    private var routeMethod: Route.Method = .unspecified
    private var routeDirection: Route.Direction = .unspecified

    public func parse(_ cotXml: String) -> TAKPacketV2 {
        // Reject XML with DOCTYPE or ENTITY declarations to prevent XXE and entity expansion attacks
        let lower = cotXml.lowercased()
        if lower.contains("<!doctype") || lower.contains("<!entity") {
            return TAKPacketV2()  // Return empty packet for malicious input
        }

        // Reset state
        packet = TAKPacketV2()
        cotTypeStr = ""
        hasAircraftData = false
        hasChatData = false
        remarksText = ""
        icao = ""; registration = ""; flight = ""; aircraftType = ""
        squawk = 0; category = ""; rssiX10 = 0; gps = false; cotHostId = ""
        chatTo = nil; chatToCallsign = nil
        timeStr = ""; staleStr = ""
        inRemarks = false
        // --- Reset typed geometry accumulators ---
        hasShapeData = false
        inShape = false
        shapeMajorCm = 0; shapeMinorCm = 0; shapeAngleDeg = 360
        sawStrokeColor = false; sawFillColor = false
        strokeColorArgb = 0; strokeWeightX10 = 0; fillColorArgb = 0
        labelsOn = false
        verticesAbs = []; verticesTruncated = false
        bullseyeDistanceDm = 0; bullseyeBearingRef = 0; bullseyeFlags = 0; bullseyeUidRef = ""
        hasMarkerData = false
        markerColorArgb = 0; markerReadiness = false
        markerParentUid = ""; markerParentType = ""; markerParentCallsign = ""; markerIconset = ""
        hasRabData = false
        rabAnchorLatI = 0; rabAnchorLonI = 0; rabAnchorUid = ""
        rabRangeCm = 0; rabBearingCdeg = 0
        hasRouteData = false
        routeLinksAbs = []; routeTruncated = false
        routePrefix = ""
        routeMethod = .unspecified; routeDirection = .unspecified

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

        // Derive the stroke/fill/both discriminator from what we observed.
        let shapeStyle: DrawnShape.StyleMode
        if sawStrokeColor && sawFillColor {
            shapeStyle = .strokeAndFill
        } else if sawStrokeColor {
            shapeStyle = .strokeOnly
        } else if sawFillColor {
            shapeStyle = .fillOnly
        } else {
            shapeStyle = .unspecified
        }

        // Payload priority: chat > aircraft > route > rab > shape > marker > pli.
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
        } else if hasRouteData && !routeLinksAbs.isEmpty {
            var route = Route()
            route.method = routeMethod
            route.direction = routeDirection
            route.prefix = routePrefix
            route.strokeWeightX10 = strokeWeightX10
            route.links = routeLinksAbs.map { abs in
                var link = Route.Link()
                var gp = GeoPoint()
                gp.latDeltaI = abs.latI - packet.latitudeI
                gp.lonDeltaI = abs.lonI - packet.longitudeI
                link.point = gp
                link.uid = abs.uid
                link.callsign = abs.callsign
                link.linkType = abs.linkType
                return link
            }
            route.truncated = routeTruncated
            packet.route = route
        } else if hasRabData {
            var rab = RangeAndBearing()
            var anchor = GeoPoint()
            anchor.latDeltaI = rabAnchorLatI - packet.latitudeI
            anchor.lonDeltaI = rabAnchorLonI - packet.longitudeI
            rab.anchor = anchor
            rab.anchorUid = rabAnchorUid
            rab.rangeCm = rabRangeCm
            rab.bearingCdeg = rabBearingCdeg
            rab.strokeColor = AtakPalette.argbToTeam(strokeColorArgb)
            rab.strokeArgb = strokeColorArgb
            rab.strokeWeightX10 = strokeWeightX10
            packet.rab = rab
        } else if hasShapeData {
            var shape = DrawnShape()
            shape.kind = shapeKindFromCotType(cotTypeStr)
            shape.style = shapeStyle
            shape.majorCm = shapeMajorCm
            shape.minorCm = shapeMinorCm
            shape.angleDeg = shapeAngleDeg
            shape.strokeColor = AtakPalette.argbToTeam(strokeColorArgb)
            shape.strokeArgb = strokeColorArgb
            shape.strokeWeightX10 = strokeWeightX10
            shape.fillColor = AtakPalette.argbToTeam(fillColorArgb)
            shape.fillArgb = fillColorArgb
            shape.labelsOn = labelsOn
            // Apply delta encoding relative to the event anchor.
            shape.vertices = verticesAbs.map { (lat, lon) in
                var gp = GeoPoint()
                gp.latDeltaI = lat - packet.latitudeI
                gp.lonDeltaI = lon - packet.longitudeI
                return gp
            }
            shape.truncated = verticesTruncated
            shape.bullseyeDistanceDm = bullseyeDistanceDm
            shape.bullseyeBearingRef = bullseyeBearingRef
            shape.bullseyeFlags = bullseyeFlags
            shape.bullseyeUidRef = bullseyeUidRef
            packet.shape = shape
        } else if hasMarkerData {
            var marker = Marker()
            marker.kind = markerKindFromCotType(cotTypeStr, iconset: markerIconset)
            marker.color = AtakPalette.argbToTeam(markerColorArgb)
            marker.colorArgb = markerColorArgb
            marker.readiness = markerReadiness
            marker.parentUid = markerParentUid
            marker.parentType = markerParentType
            marker.parentCallsign = markerParentCallsign
            marker.iconset = markerIconset
            packet.marker = marker
        } else {
            packet.pli = true
        }

        return packet
    }

    // MARK: - Typed geometry classifiers

    private func shapeKindFromCotType(_ t: String) -> DrawnShape.Kind {
        switch t {
        case "u-d-c-c": return .circle
        case "u-d-r": return .rectangle
        case "u-d-f": return .freeform
        case "u-d-f-m": return .telestration
        case "u-d-p": return .polygon
        case "u-r-b-c-c": return .rangingCircle
        case "u-r-b-bullseye": return .bullseye
        default: return .unspecified
        }
    }

    /// Derive a Marker.Kind from CoT type + iconset path. When the CoT
    /// type alone is ambiguous (e.g. `a-u-G` could be 2525 or custom icon),
    /// the iconset path disambiguates.
    private func markerKindFromCotType(_ cotType: String, iconset: String) -> Marker.Kind {
        switch cotType {
        case "b-m-p-s-m": return .spot
        case "b-m-p-w": return .waypoint
        case "b-m-p-c": return .checkpoint
        case "b-m-p-s-p-i", "b-m-p-s-p-loc": return .selfPosition
        default:
            if iconset.hasPrefix("COT_MAPPING_2525B") { return .symbol2525 }
            if iconset.hasPrefix("COT_MAPPING_SPOTMAP") { return .spotMap }
            if !iconset.isEmpty { return .customIcon }
            return .unspecified
        }
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
            cotTypeStr = attributes["type"] ?? ""
            packet.cotTypeID = CotTypeMapper.typeToEnum(cotTypeStr)
            if packet.cotTypeID == .other { packet.cotTypeStr = cotTypeStr }
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
            handleLink(attributes: attributes)

        case "remarks":
            inRemarks = true

        // --- Drawn shape elements --------------------------------------
        case "shape":
            hasShapeData = true
            inShape = true

        case "ellipse":
            if inShape {
                let majorM = Double(attributes["major"] ?? "0") ?? 0
                let minorM = Double(attributes["minor"] ?? "0") ?? 0
                shapeMajorCm = UInt32(max(0, majorM * 100))
                shapeMinorCm = UInt32(max(0, minorM * 100))
                if let a = attributes["angle"], let ai = UInt32(a) {
                    shapeAngleDeg = ai
                }
                hasShapeData = true
            }

        case "strokeColor":
            sawStrokeColor = true
            if let v = Int32(attributes["value"] ?? "0") {
                strokeColorArgb = UInt32(bitPattern: v)
            }
            hasShapeData = true

        case "strokeWeight":
            let w = Double(attributes["value"] ?? "0") ?? 0
            strokeWeightX10 = UInt32(max(0, w * 10))
            hasShapeData = true

        case "fillColor":
            sawFillColor = true
            if let v = Int32(attributes["value"] ?? "0") {
                fillColorArgb = UInt32(bitPattern: v)
            }
            hasShapeData = true

        case "labels_on":
            labelsOn = attributes["value"] == "true"

        // --- Marker elements -------------------------------------------
        case "color":
            if let v = Int32(attributes["argb"] ?? "0") {
                markerColorArgb = UInt32(bitPattern: v)
            }
            hasMarkerData = true

        case "usericon":
            markerIconset = attributes["iconsetpath"] ?? ""
            if !markerIconset.isEmpty { hasMarkerData = true }

        // --- Bullseye --------------------------------------------------
        case "bullseye":
            hasShapeData = true
            let dist = Double(attributes["distance"] ?? "0") ?? 0
            bullseyeDistanceDm = UInt32(max(0, dist * 10))
            bullseyeBearingRef = Self.bearingRefMap[attributes["bearingRef"] ?? ""] ?? 0
            var flags: UInt32 = 0
            if attributes["rangeRingVisible"] == "true" { flags |= 0x01 }
            if attributes["hasRangeRings"] == "true" { flags |= 0x02 }
            if attributes["edgeToCenter"] == "true" { flags |= 0x04 }
            if attributes["mils"] == "true" { flags |= 0x08 }
            bullseyeFlags = flags
            bullseyeUidRef = attributes["bullseyeUID"] ?? ""

        // --- Range and bearing -----------------------------------------
        case "range":
            let v = Double(attributes["value"] ?? "0") ?? 0
            rabRangeCm = UInt32(max(0, v * 100))
            hasRabData = true

        case "bearing":
            let v = Double(attributes["value"] ?? "0") ?? 0
            rabBearingCdeg = UInt32(max(0, v * 100))
            hasRabData = true

        // --- Route -----------------------------------------------------
        case "__routeinfo":
            hasRouteData = true

        case "link_attr":
            hasRouteData = true
            routePrefix = attributes["prefix"] ?? ""
            routeMethod = Self.routeMethodMap[attributes["method"] ?? ""] ?? .unspecified
            routeDirection = Self.routeDirectionMap[attributes["direction"] ?? ""] ?? .unspecified
            if let sw = attributes["stroke"], let swi = UInt32(sw), swi > 0 {
                strokeWeightX10 = swi * 10
            }

        default:
            break
        }
    }

    /// Handle a `<link>` element — the most overloaded tag in CoT XML.
    /// Disambiguates by event cotType and attributes to route to the
    /// appropriate accumulator (route waypoint, RAB anchor, shape vertex,
    /// or marker parent link).
    private func handleLink(attributes: [String: String]) {
        let linkUidAttr = attributes["uid"]
        let pointAttr = attributes["point"]
        let linkType = attributes["type"] ?? ""
        let relation = attributes["relation"] ?? ""
        let linkCallsign = attributes["callsign"] ?? ""
        let parentCallsign = attributes["parent_callsign"] ?? ""

        // Ignore style links nested inside <shape> (type="b-x-KmlStyle").
        // Their uid ends in ".Style" and they carry styling, not geometry.
        let isStyleLink = linkType.hasPrefix("b-x-KmlStyle") ||
            (linkUidAttr?.hasSuffix(".Style") ?? false)
        if isStyleLink { return }

        if let pt = pointAttr {
            let parts = pt.split(separator: ",")
            guard parts.count >= 2 else { return }
            let plat = Double(parts[0]) ?? 0
            let plon = Double(parts[1]) ?? 0
            let plati = Int32(plat * 1e7)
            let ploni = Int32(plon * 1e7)

            // u-rb-a is the ONLY type whose <link> is the range/bearing
            // anchor. Check cotType first so a ranging-line link never
            // escalates to a one-waypoint "route".
            if cotTypeStr == "u-rb-a" {
                if rabAnchorLatI == 0 && rabAnchorLonI == 0 {
                    rabAnchorLatI = plati
                    rabAnchorLonI = ploni
                    if let u = linkUidAttr { rabAnchorUid = u }
                }
                hasRabData = true
            } else if (linkType == "b-m-p-w" || linkType == "b-m-p-c") && cotTypeStr == "b-m-r" {
                if routeLinksAbs.count < Self.maxRouteLinks {
                    routeLinksAbs.append(RouteLinkAbs(
                        latI: plati, lonI: ploni,
                        uid: linkUidAttr ?? "",
                        callsign: linkCallsign,
                        linkType: linkType == "b-m-p-c" ? 1 : 0
                    ))
                } else {
                    routeTruncated = true
                }
                hasRouteData = true
            } else {
                // Shape vertex
                if verticesAbs.count < Self.maxVertices {
                    verticesAbs.append((plati, ploni))
                    hasShapeData = true
                } else {
                    verticesTruncated = true
                }
            }
        } else if let u = linkUidAttr, relation == "p-p", !linkType.isEmpty {
            // Marker parent link: no point attribute, p-p relation.
            markerParentUid = u
            markerParentType = linkType
            if !parentCallsign.isEmpty {
                markerParentCallsign = parentCallsign
            }
            hasMarkerData = true
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
        } else if name == "shape" {
            inShape = false
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

    /// Extract the inner bytes of the `<detail>` element from a CoT XML
    /// event, exactly as they appear in the source (no XML normalization,
    /// no re-escaping).  Used by `TakCompressor.compressBestOf(_:rawDetailBytes:)`
    /// to build a `raw_detail` fallback packet alongside the typed packet.
    ///
    /// Returns an empty `Data` for events with a self-closing `<detail/>`
    /// or no `<detail>` at all.  Receivers rehydrate the full event by
    /// wrapping these bytes in `<detail>…</detail>`, so a byte-for-byte
    /// extraction is required to keep the round trip loss-free.
    public func extractRawDetailBytes(_ cotXml: String) -> Data {
        // Regex with dotAll so .* matches across newlines; case-insensitive
        // so <DETAIL> also works.  Uses NSRegularExpression since Swift's
        // native regex literals aren't available on every deployment target.
        let pattern = "<detail\\b[^>]*>(.*?)</detail\\s*>"
        let options: NSRegularExpression.Options = [.dotMatchesLineSeparators, .caseInsensitive]
        guard let regex = try? NSRegularExpression(pattern: pattern, options: options) else {
            return Data()
        }
        let nsRange = NSRange(cotXml.startIndex..., in: cotXml)
        guard let match = regex.firstMatch(in: cotXml, range: nsRange),
              match.numberOfRanges >= 2,
              let innerRange = Range(match.range(at: 1), in: cotXml) else {
            return Data()
        }
        return Data(cotXml[innerRange].utf8)
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
