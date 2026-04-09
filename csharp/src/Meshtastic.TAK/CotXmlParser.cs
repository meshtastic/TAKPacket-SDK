using System.Text.RegularExpressions;
using System.Xml.Linq;
using Meshtastic.Protobufs;

namespace Meshtastic.TAK;

public class CotXmlParser
{
    /// <summary>Vertex pool cap matching *DrawnShape.vertices max_count:32.</summary>
    public const int MaxVertices = 32;

    /// <summary>Route link pool cap matching *Route.links max_count:16.</summary>
    public const int MaxRouteLinks = 16;

    private static readonly Dictionary<string, Team> TeamMap = new()
    {
        ["White"] = Team.White, ["Yellow"] = Team.Yellow,
        ["Orange"] = Team.Orange, ["Magenta"] = Team.Magenta,
        ["Red"] = Team.Red, ["Maroon"] = Team.Maroon,
        ["Purple"] = Team.Purple, ["Dark Blue"] = Team.DarkBlue,
        ["Blue"] = Team.Blue, ["Cyan"] = Team.Cyan,
        ["Teal"] = Team.Teal, ["Green"] = Team.Green,
        ["Dark Green"] = Team.DarkGreen, ["Brown"] = Team.Brown,
    };

    private static readonly Dictionary<string, MemberRole> RoleMap = new()
    {
        ["Team Member"] = MemberRole.TeamMember, ["Team Lead"] = MemberRole.TeamLead,
        ["HQ"] = MemberRole.Hq, ["Sniper"] = MemberRole.Sniper,
        ["Medic"] = MemberRole.Medic, ["ForwardObserver"] = MemberRole.ForwardObserver,
        ["RTO"] = MemberRole.Rto, ["K9"] = MemberRole.K9,
    };

    private static readonly Dictionary<string, Route.Types.Method> RouteMethodMap = new()
    {
        ["Driving"] = Route.Types.Method.Driving,
        ["Walking"] = Route.Types.Method.Walking,
        ["Flying"] = Route.Types.Method.Flying,
        ["Swimming"] = Route.Types.Method.Swimming,
        ["Watercraft"] = Route.Types.Method.Watercraft,
    };

    private static readonly Dictionary<string, Route.Types.Direction> RouteDirectionMap = new()
    {
        ["Infil"] = Route.Types.Direction.Infil,
        ["Exfil"] = Route.Types.Direction.Exfil,
    };

    private static readonly Dictionary<string, uint> BearingRefMap = new()
    {
        ["M"] = 1, ["T"] = 2, ["G"] = 3,
    };

    private static GeoPointSource ParseGeoSrc(string? s) => s switch
    {
        "GPS" => GeoPointSource.Gps,
        "USER" => GeoPointSource.User,
        "NETWORK" => GeoPointSource.Network,
        _ => GeoPointSource.Unspecified,
    };

    private static DrawnShape.Types.Kind ShapeKindFromCotType(string t) => t switch
    {
        "u-d-c-c" => DrawnShape.Types.Kind.Circle,
        "u-d-r" => DrawnShape.Types.Kind.Rectangle,
        "u-d-f" => DrawnShape.Types.Kind.Freeform,
        "u-d-f-m" => DrawnShape.Types.Kind.Telestration,
        "u-d-p" => DrawnShape.Types.Kind.Polygon,
        "u-r-b-c-c" => DrawnShape.Types.Kind.RangingCircle,
        "u-r-b-bullseye" => DrawnShape.Types.Kind.Bullseye,
        _ => DrawnShape.Types.Kind.Unspecified,
    };

    private static Marker.Types.Kind MarkerKindFromCotType(string cotType, string iconset)
    {
        return cotType switch
        {
            "b-m-p-s-m" => Marker.Types.Kind.Spot,
            "b-m-p-w" => Marker.Types.Kind.Waypoint,
            "b-m-p-c" => Marker.Types.Kind.Checkpoint,
            "b-m-p-s-p-i" or "b-m-p-s-p-loc" => Marker.Types.Kind.SelfPosition,
            _ => iconset.StartsWith("COT_MAPPING_2525B") ? Marker.Types.Kind.Symbol2525
                : iconset.StartsWith("COT_MAPPING_SPOTMAP") ? Marker.Types.Kind.SpotMap
                : iconset.Length > 0 ? Marker.Types.Kind.CustomIcon
                : Marker.Types.Kind.Unspecified,
        };
    }

    public TAKPacketV2 Parse(string cotXml)
    {
        // Reject XML with DOCTYPE or ENTITY declarations to prevent XXE and entity expansion
        var lower = cotXml.ToLowerInvariant();
        if (lower.Contains("<!doctype") || lower.Contains("<!entity"))
            throw new ArgumentException("XML contains prohibited DOCTYPE or ENTITY declaration");

        var doc = XDocument.Parse(cotXml);
        var evt = doc.Root!;
        var pkt = new TAKPacketV2();

        var typeStr = evt.Attribute("type")?.Value ?? "";
        pkt.CotTypeId = (CotType)CotTypeMapper.TypeToEnum(typeStr);
        if (pkt.CotTypeId == 0) pkt.CotTypeStr = typeStr;
        pkt.How = (CotHow)CotTypeMapper.HowToEnum(evt.Attribute("how")?.Value ?? "");
        pkt.Uid = evt.Attribute("uid")?.Value ?? "";

        var timeStr = evt.Attribute("time")?.Value ?? "";
        var staleStr = evt.Attribute("stale")?.Value ?? "";
        pkt.StaleSeconds = ComputeStaleSeconds(timeStr, staleStr);

        var point = evt.Element("point");
        if (point != null)
        {
            pkt.LatitudeI = (int)(double.Parse(point.Attribute("lat")?.Value ?? "0") * 1e7);
            pkt.LongitudeI = (int)(double.Parse(point.Attribute("lon")?.Value ?? "0") * 1e7);
            pkt.Altitude = (int)double.Parse(point.Attribute("hae")?.Value ?? "0");
        }

        var detail = evt.Element("detail");
        if (detail == null) { pkt.Pli = true; return pkt; }

        bool hasAircraft = false, hasChat = false;
        string icao = "", reg = "", flight = "", acType = "", category = "", cotHostId = "", remarksText = "";
        uint squawk = 0; int rssiX10 = 0; bool gps = false;
        string? chatTo = null, chatToCs = null;

        // --- Drawn shape accumulators ---
        bool hasShapeData = false;
        uint shapeMajorCm = 0, shapeMinorCm = 0, shapeAngleDeg = 360;
        bool sawStrokeColor = false, sawFillColor = false;
        uint strokeColorArgb = 0, fillColorArgb = 0, strokeWeightX10 = 0;
        bool labelsOn = false;
        var verticesAbs = new List<(int latI, int lonI)>();
        bool verticesTruncated = false;

        // --- Bullseye accumulators ---
        uint bullseyeDistanceDm = 0, bullseyeBearingRef = 0, bullseyeFlags = 0;
        string bullseyeUidRef = "";

        // --- Marker accumulators ---
        bool hasMarkerData = false;
        uint markerColorArgb = 0;
        bool markerReadiness = false;
        string markerParentUid = "", markerParentType = "", markerParentCallsign = "", markerIconset = "";

        // --- Range and bearing accumulators ---
        bool hasRabData = false;
        int rabAnchorLatI = 0, rabAnchorLonI = 0;
        string rabAnchorUid = "";
        uint rabRangeCm = 0, rabBearingCdeg = 0;

        // --- Route accumulators ---
        bool hasRouteData = false;
        var routeLinksAbs = new List<(int latI, int lonI, string uid, string callsign, uint linkType)>();
        bool routeTruncated = false;
        string routePrefix = "";
        Route.Types.Method routeMethod = Route.Types.Method.Unspecified;
        Route.Types.Direction routeDirection = Route.Types.Direction.Unspecified;

        void HandleLink(XElement el)
        {
            var linkUid = el.Attribute("uid")?.Value;
            var pointAttr = el.Attribute("point")?.Value;
            var linkType = el.Attribute("type")?.Value ?? "";
            var relation = el.Attribute("relation")?.Value ?? "";
            var linkCallsign = el.Attribute("callsign")?.Value ?? "";
            var parentCallsign = el.Attribute("parent_callsign")?.Value ?? "";

            var isStyleLink = linkType.StartsWith("b-x-KmlStyle") ||
                (linkUid != null && linkUid.EndsWith(".Style"));
            if (isStyleLink) return;

            if (pointAttr != null)
            {
                var parts = pointAttr.Split(',');
                if (parts.Length < 2) return;
                if (!double.TryParse(parts[0], out var plat) || !double.TryParse(parts[1], out var plon)) return;
                var plati = (int)(plat * 1e7);
                var ploni = (int)(plon * 1e7);

                if (typeStr == "u-rb-a")
                {
                    if (rabAnchorLatI == 0 && rabAnchorLonI == 0)
                    {
                        rabAnchorLatI = plati;
                        rabAnchorLonI = ploni;
                        if (linkUid != null) rabAnchorUid = linkUid;
                    }
                    hasRabData = true;
                }
                else if ((linkType == "b-m-p-w" || linkType == "b-m-p-c") && typeStr == "b-m-r")
                {
                    if (routeLinksAbs.Count < MaxRouteLinks)
                    {
                        routeLinksAbs.Add((plati, ploni, linkUid ?? "", linkCallsign,
                            linkType == "b-m-p-c" ? 1u : 0u));
                    }
                    else
                    {
                        routeTruncated = true;
                    }
                    hasRouteData = true;
                }
                else
                {
                    if (verticesAbs.Count < MaxVertices)
                    {
                        verticesAbs.Add((plati, ploni));
                        hasShapeData = true;
                    }
                    else
                    {
                        verticesTruncated = true;
                    }
                }
            }
            else if (linkUid != null && relation == "p-p" && linkType.Length > 0)
            {
                markerParentUid = linkUid;
                markerParentType = linkType;
                if (parentCallsign.Length > 0) markerParentCallsign = parentCallsign;
                hasMarkerData = true;
            }
        }

        foreach (var el in detail.Elements())
        {
            switch (el.Name.LocalName)
            {
                case "contact":
                    pkt.Callsign = el.Attribute("callsign")?.Value ?? "";
                    if (el.Attribute("endpoint") is { } ep) pkt.Endpoint = ep.Value;
                    if (el.Attribute("phone") is { } ph) pkt.Phone = ph.Value;
                    break;
                case "__group":
                    if (el.Attribute("name")?.Value is { } tn && TeamMap.TryGetValue(tn, out var tv)) pkt.Team = tv;
                    if (el.Attribute("role")?.Value is { } rn && RoleMap.TryGetValue(rn, out var rv)) pkt.Role = rv;
                    break;
                case "status":
                    if (el.Attribute("battery")?.Value is { } bat && uint.TryParse(bat, out var b))
                        pkt.Battery = b;
                    if (el.Attribute("readiness")?.Value == "true") markerReadiness = true;
                    break;
                case "track":
                    pkt.Speed = (uint)(double.Parse(el.Attribute("speed")?.Value ?? "0") * 100);
                    pkt.Course = (uint)(double.Parse(el.Attribute("course")?.Value ?? "0") * 100);
                    break;
                case "takv":
                    pkt.TakVersion = el.Attribute("version")?.Value ?? "";
                    pkt.TakDevice = el.Attribute("device")?.Value ?? "";
                    pkt.TakPlatform = el.Attribute("platform")?.Value ?? "";
                    pkt.TakOs = el.Attribute("os")?.Value ?? "";
                    break;
                case "precisionlocation":
                    pkt.GeoSrc = ParseGeoSrc(el.Attribute("geopointsrc")?.Value);
                    pkt.AltSrc = ParseGeoSrc(el.Attribute("altsrc")?.Value);
                    break;
                case "uid": case "UID":
                    if (el.Attribute("Droid") is { } d) pkt.DeviceCallsign = d.Value;
                    break;
                case "_radio":
                    if (el.Attribute("rssi")?.Value is { } rssi)
                    { rssiX10 = (int)(double.Parse(rssi) * 10); hasAircraft = true; }
                    gps = el.Attribute("gps")?.Value == "true";
                    break;
                case "_aircot_":
                    hasAircraft = true;
                    icao = el.Attribute("icao")?.Value ?? "";
                    reg = el.Attribute("reg")?.Value ?? "";
                    flight = el.Attribute("flight")?.Value ?? "";
                    category = el.Attribute("cat")?.Value ?? "";
                    cotHostId = el.Attribute("cot_host_id")?.Value ?? "";
                    break;
                case "__chat":
                    hasChat = true;
                    chatToCs = el.Attribute("senderCallsign")?.Value;
                    chatTo = el.Attribute("id")?.Value;
                    break;
                case "remarks":
                    remarksText = el.Value.Trim();
                    break;
                case "link":
                    HandleLink(el);
                    break;
                case "shape":
                    hasShapeData = true;
                    var ellipse = el.Element("ellipse");
                    if (ellipse != null)
                    {
                        var majorM = double.Parse(ellipse.Attribute("major")?.Value ?? "0");
                        var minorM = double.Parse(ellipse.Attribute("minor")?.Value ?? "0");
                        shapeMajorCm = (uint)Math.Max(0, majorM * 100);
                        shapeMinorCm = (uint)Math.Max(0, minorM * 100);
                        if (uint.TryParse(ellipse.Attribute("angle")?.Value, out var ang))
                            shapeAngleDeg = ang;
                    }
                    break;
                case "strokeColor":
                    sawStrokeColor = true;
                    if (int.TryParse(el.Attribute("value")?.Value, out var sv))
                        strokeColorArgb = unchecked((uint)sv);
                    hasShapeData = true;
                    break;
                case "strokeWeight":
                    if (double.TryParse(el.Attribute("value")?.Value, out var sw))
                        strokeWeightX10 = (uint)Math.Max(0, sw * 10);
                    hasShapeData = true;
                    break;
                case "fillColor":
                    sawFillColor = true;
                    if (int.TryParse(el.Attribute("value")?.Value, out var fv))
                        fillColorArgb = unchecked((uint)fv);
                    hasShapeData = true;
                    break;
                case "labels_on":
                    labelsOn = el.Attribute("value")?.Value == "true";
                    break;
                case "color":
                    if (int.TryParse(el.Attribute("argb")?.Value, out var cv))
                        markerColorArgb = unchecked((uint)cv);
                    hasMarkerData = true;
                    break;
                case "usericon":
                    markerIconset = el.Attribute("iconsetpath")?.Value ?? "";
                    if (markerIconset.Length > 0) hasMarkerData = true;
                    break;
                case "bullseye":
                    hasShapeData = true;
                    if (double.TryParse(el.Attribute("distance")?.Value, out var dist))
                        bullseyeDistanceDm = (uint)Math.Max(0, dist * 10);
                    bullseyeBearingRef = BearingRefMap.GetValueOrDefault(
                        el.Attribute("bearingRef")?.Value ?? "", 0u);
                    uint flags = 0;
                    if (el.Attribute("rangeRingVisible")?.Value == "true") flags |= 0x01;
                    if (el.Attribute("hasRangeRings")?.Value == "true") flags |= 0x02;
                    if (el.Attribute("edgeToCenter")?.Value == "true") flags |= 0x04;
                    if (el.Attribute("mils")?.Value == "true") flags |= 0x08;
                    bullseyeFlags = flags;
                    bullseyeUidRef = el.Attribute("bullseyeUID")?.Value ?? "";
                    break;
                case "range":
                    if (double.TryParse(el.Attribute("value")?.Value, out var rangeVal))
                        rabRangeCm = (uint)Math.Max(0, rangeVal * 100);
                    hasRabData = true;
                    break;
                case "bearing":
                    if (double.TryParse(el.Attribute("value")?.Value, out var bearingVal))
                        rabBearingCdeg = (uint)Math.Max(0, bearingVal * 100);
                    hasRabData = true;
                    break;
                case "__routeinfo":
                    hasRouteData = true;
                    break;
                case "link_attr":
                    hasRouteData = true;
                    routePrefix = el.Attribute("prefix")?.Value ?? "";
                    routeMethod = RouteMethodMap.GetValueOrDefault(
                        el.Attribute("method")?.Value ?? "", Route.Types.Method.Unspecified);
                    routeDirection = RouteDirectionMap.GetValueOrDefault(
                        el.Attribute("direction")?.Value ?? "", Route.Types.Direction.Unspecified);
                    if (el.Attribute("stroke")?.Value is { } swAttr &&
                        uint.TryParse(swAttr, out var swVal) && swVal > 0)
                        strokeWeightX10 = swVal * 10;
                    break;
            }
        }

        // Parse ICAO from remarks
        if (string.IsNullOrEmpty(icao) && !string.IsNullOrEmpty(remarksText))
        {
            var m = Regex.Match(remarksText, @"ICAO:\s*([A-Fa-f0-9]{6})");
            if (m.Success)
            {
                hasAircraft = true; icao = m.Groups[1].Value;
                reg = Regex.Match(remarksText, @"REG:\s*(\S+)").Groups[1].Value;
                flight = Regex.Match(remarksText, @"Flight:\s*(\S+)").Groups[1].Value;
                acType = Regex.Match(remarksText, @"Type:\s*(\S+)").Groups[1].Value;
                var sq = Regex.Match(remarksText, @"Squawk:\s*(\d+)");
                if (sq.Success) squawk = uint.Parse(sq.Groups[1].Value);
                if (string.IsNullOrEmpty(category))
                    category = Regex.Match(remarksText, @"Category:\s*(\S+)").Groups[1].Value;
            }
        }

        // Derive DrawnShape.StyleMode from stroke/fill presence flags.
        DrawnShape.Types.StyleMode shapeStyle;
        if (sawStrokeColor && sawFillColor)
            shapeStyle = DrawnShape.Types.StyleMode.StrokeAndFill;
        else if (sawStrokeColor)
            shapeStyle = DrawnShape.Types.StyleMode.StrokeOnly;
        else if (sawFillColor)
            shapeStyle = DrawnShape.Types.StyleMode.FillOnly;
        else
            shapeStyle = DrawnShape.Types.StyleMode.Unspecified;

        // Payload priority: chat > aircraft > route > rab > shape > marker > pli.
        if (hasChat)
        {
            var chat = new GeoChat { Message = remarksText };
            if (chatTo != null) chat.To = chatTo;
            if (chatToCs != null) chat.ToCallsign = chatToCs;
            pkt.Chat = chat;
        }
        else if (hasAircraft)
        {
            pkt.Aircraft = new AircraftTrack
            {
                Icao = icao, Registration = reg, Flight = flight,
                AircraftType = acType, Squawk = squawk, Category = category,
                RssiX10 = rssiX10, Gps = gps, CotHostId = cotHostId,
            };
        }
        else if (hasRouteData && routeLinksAbs.Count > 0)
        {
            var route = new Route
            {
                Method = routeMethod,
                Direction = routeDirection,
                Prefix = routePrefix,
                StrokeWeightX10 = strokeWeightX10,
                Truncated = routeTruncated,
            };
            foreach (var (lat, lon, luid, lcs, lt) in routeLinksAbs)
            {
                route.Links.Add(new Route.Types.Link
                {
                    Point = new CotGeoPoint
                    {
                        LatDeltaI = lat - pkt.LatitudeI,
                        LonDeltaI = lon - pkt.LongitudeI,
                    },
                    Uid = luid,
                    Callsign = lcs,
                    LinkType = lt,
                });
            }
            pkt.Route = route;
        }
        else if (hasRabData)
        {
            pkt.Rab = new RangeAndBearing
            {
                Anchor = new CotGeoPoint
                {
                    LatDeltaI = rabAnchorLatI - pkt.LatitudeI,
                    LonDeltaI = rabAnchorLonI - pkt.LongitudeI,
                },
                AnchorUid = rabAnchorUid,
                RangeCm = rabRangeCm,
                BearingCdeg = rabBearingCdeg,
                StrokeColor = AtakPalette.ArgbToTeam(strokeColorArgb),
                StrokeArgb = strokeColorArgb,
                StrokeWeightX10 = strokeWeightX10,
            };
        }
        else if (hasShapeData)
        {
            var shape = new DrawnShape
            {
                Kind = ShapeKindFromCotType(typeStr),
                Style = shapeStyle,
                MajorCm = shapeMajorCm,
                MinorCm = shapeMinorCm,
                AngleDeg = shapeAngleDeg,
                StrokeColor = AtakPalette.ArgbToTeam(strokeColorArgb),
                StrokeArgb = strokeColorArgb,
                StrokeWeightX10 = strokeWeightX10,
                FillColor = AtakPalette.ArgbToTeam(fillColorArgb),
                FillArgb = fillColorArgb,
                LabelsOn = labelsOn,
                Truncated = verticesTruncated,
                BullseyeDistanceDm = bullseyeDistanceDm,
                BullseyeBearingRef = bullseyeBearingRef,
                BullseyeFlags = bullseyeFlags,
                BullseyeUidRef = bullseyeUidRef,
            };
            foreach (var (lat, lon) in verticesAbs)
            {
                shape.Vertices.Add(new CotGeoPoint
                {
                    LatDeltaI = lat - pkt.LatitudeI,
                    LonDeltaI = lon - pkt.LongitudeI,
                });
            }
            pkt.Shape = shape;
        }
        else if (hasMarkerData)
        {
            pkt.Marker = new Marker
            {
                Kind = MarkerKindFromCotType(typeStr, markerIconset),
                Color = AtakPalette.ArgbToTeam(markerColorArgb),
                ColorArgb = markerColorArgb,
                Readiness = markerReadiness,
                ParentUid = markerParentUid,
                ParentType = markerParentType,
                ParentCallsign = markerParentCallsign,
                Iconset = markerIconset,
            };
        }
        else pkt.Pli = true;

        return pkt;
    }

    /// <summary>
    /// Extract the inner bytes of the <c>&lt;detail&gt;</c> element from a
    /// CoT XML event, exactly as they appear in the source — no XML
    /// normalization, no re-escaping.  Used by
    /// <see cref="TakCompressor.CompressBestOf"/> to build a
    /// <c>raw_detail</c> fallback packet alongside the typed packet.
    /// </summary>
    /// <remarks>
    /// Returns an empty <c>byte[]</c> for events with a self-closing
    /// <c>&lt;detail/&gt;</c> or no <c>&lt;detail&gt;</c> at all.
    /// Receivers rehydrate the full event by wrapping these bytes in
    /// <c>&lt;detail&gt;…&lt;/detail&gt;</c>, so a byte-for-byte
    /// extraction is required to keep the round trip loss-free.
    /// </remarks>
    public static byte[] ExtractRawDetailBytes(string cotXml)
    {
        var match = Regex.Match(
            cotXml,
            @"<detail\b[^>]*>(.*?)</detail\s*>",
            RegexOptions.Singleline | RegexOptions.IgnoreCase);
        if (!match.Success) return Array.Empty<byte>();
        return System.Text.Encoding.UTF8.GetBytes(match.Groups[1].Value);
    }

    private static uint ComputeStaleSeconds(string timeStr, string staleStr)
    {
        if (string.IsNullOrEmpty(timeStr) || string.IsNullOrEmpty(staleStr)) return 0;
        if (!DateTimeOffset.TryParse(timeStr, out var t) || !DateTimeOffset.TryParse(staleStr, out var s)) return 0;
        var diff = (int)(s - t).TotalSeconds;
        return diff > 0 ? (uint)diff : 0;
    }
}
