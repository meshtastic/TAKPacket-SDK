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

    // --- CasevacReport / EmergencyAlert / TaskRequest mappings -----------

    private static readonly Dictionary<string, CasevacReport.Types.Precedence> PrecedenceMap = new()
    {
        ["A"] = CasevacReport.Types.Precedence.Urgent,
        ["URGENT"] = CasevacReport.Types.Precedence.Urgent,
        ["Urgent"] = CasevacReport.Types.Precedence.Urgent,
        ["B"] = CasevacReport.Types.Precedence.UrgentSurgical,
        ["URGENT SURGICAL"] = CasevacReport.Types.Precedence.UrgentSurgical,
        ["Urgent Surgical"] = CasevacReport.Types.Precedence.UrgentSurgical,
        ["C"] = CasevacReport.Types.Precedence.Priority,
        ["PRIORITY"] = CasevacReport.Types.Precedence.Priority,
        ["Priority"] = CasevacReport.Types.Precedence.Priority,
        ["D"] = CasevacReport.Types.Precedence.Routine,
        ["ROUTINE"] = CasevacReport.Types.Precedence.Routine,
        ["Routine"] = CasevacReport.Types.Precedence.Routine,
        ["E"] = CasevacReport.Types.Precedence.Convenience,
        ["CONVENIENCE"] = CasevacReport.Types.Precedence.Convenience,
        ["Convenience"] = CasevacReport.Types.Precedence.Convenience,
    };

    private static readonly Dictionary<string, CasevacReport.Types.HlzMarking> HlzMarkingMap = new()
    {
        ["Panels"] = CasevacReport.Types.HlzMarking.Panels,
        ["Pyro"] = CasevacReport.Types.HlzMarking.PyroSignal,
        ["Pyrotechnic"] = CasevacReport.Types.HlzMarking.PyroSignal,
        ["Smoke"] = CasevacReport.Types.HlzMarking.Smoke,
        ["None"] = CasevacReport.Types.HlzMarking.None,
        ["Other"] = CasevacReport.Types.HlzMarking.Other,
    };

    private static readonly Dictionary<string, CasevacReport.Types.Security> SecurityMap = new()
    {
        ["N"] = CasevacReport.Types.Security.NoEnemy,
        ["No Enemy"] = CasevacReport.Types.Security.NoEnemy,
        ["P"] = CasevacReport.Types.Security.PossibleEnemy,
        ["Possible Enemy"] = CasevacReport.Types.Security.PossibleEnemy,
        ["E"] = CasevacReport.Types.Security.EnemyInArea,
        ["Enemy In Area"] = CasevacReport.Types.Security.EnemyInArea,
        ["X"] = CasevacReport.Types.Security.EnemyInArmedContact,
        ["Enemy In Armed Contact"] = CasevacReport.Types.Security.EnemyInArmedContact,
    };

    private static readonly Dictionary<string, EmergencyAlert.Types.Type> EmergencyTypeMap = new()
    {
        ["911 Alert"] = EmergencyAlert.Types.Type.Alert911,
        ["911"] = EmergencyAlert.Types.Type.Alert911,
        ["Ring The Bell"] = EmergencyAlert.Types.Type.RingTheBell,
        ["Ring the Bell"] = EmergencyAlert.Types.Type.RingTheBell,
        ["In Contact"] = EmergencyAlert.Types.Type.InContact,
        ["Troops In Contact"] = EmergencyAlert.Types.Type.InContact,
        ["Geo-fence Breached"] = EmergencyAlert.Types.Type.GeoFenceBreached,
        ["Geo Fence Breached"] = EmergencyAlert.Types.Type.GeoFenceBreached,
        ["Custom"] = EmergencyAlert.Types.Type.Custom,
        ["Cancel"] = EmergencyAlert.Types.Type.Cancel,
    };

    private static EmergencyAlert.Types.Type EmergencyTypeFromCotType(string t) => t switch
    {
        "b-a-o-tbl" => EmergencyAlert.Types.Type.Alert911,
        "b-a-o-pan" => EmergencyAlert.Types.Type.RingTheBell,
        "b-a-o-opn" => EmergencyAlert.Types.Type.InContact,
        "b-a-g" => EmergencyAlert.Types.Type.GeoFenceBreached,
        "b-a-o-c" => EmergencyAlert.Types.Type.Custom,
        "b-a-o-can" => EmergencyAlert.Types.Type.Cancel,
        _ => EmergencyAlert.Types.Type.Unspecified,
    };

    private static readonly Dictionary<string, TaskRequest.Types.Priority> TaskPriorityMap = new()
    {
        ["Low"] = TaskRequest.Types.Priority.Low,
        ["Normal"] = TaskRequest.Types.Priority.Normal,
        ["Medium"] = TaskRequest.Types.Priority.Normal,
        ["High"] = TaskRequest.Types.Priority.High,
        ["Critical"] = TaskRequest.Types.Priority.Critical,
    };

    private static readonly Dictionary<string, TaskRequest.Types.Status> TaskStatusMap = new()
    {
        ["Pending"] = TaskRequest.Types.Status.Pending,
        ["Acknowledged"] = TaskRequest.Types.Status.Acknowledged,
        ["InProgress"] = TaskRequest.Types.Status.InProgress,
        ["In Progress"] = TaskRequest.Types.Status.InProgress,
        ["Completed"] = TaskRequest.Types.Status.Completed,
        ["Done"] = TaskRequest.Types.Status.Completed,
        ["Cancelled"] = TaskRequest.Types.Status.Cancelled,
        ["Canceled"] = TaskRequest.Types.Status.Cancelled,
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
        "u-d-c-e" => DrawnShape.Types.Kind.Ellipse,
        "u-d-v" => DrawnShape.Types.Kind.Vehicle2D,
        "u-d-v-m" => DrawnShape.Types.Kind.Vehicle3D,
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
            "b-m-p-w-GOTO" => Marker.Types.Kind.GoToPoint,
            "b-m-p-c-ip" => Marker.Types.Kind.InitialPoint,
            "b-m-p-c-cp" => Marker.Types.Kind.ContactPoint,
            "b-m-p-s-p-op" => Marker.Types.Kind.ObservationPost,
            "b-i-x-i" => Marker.Types.Kind.ImageMarker,
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

        // --- CasevacReport accumulators ---
        bool hasCasevacData = false;
        var casevacPrecedence = CasevacReport.Types.Precedence.Unspecified;
        uint casevacEquipmentFlags = 0;
        uint casevacLitterPatients = 0, casevacAmbulatoryPatients = 0;
        var casevacSecurity = CasevacReport.Types.Security.Unspecified;
        var casevacHlzMarking = CasevacReport.Types.HlzMarking.Unspecified;
        string casevacZoneMarker = "";
        uint casevacUsMilitary = 0, casevacUsCivilian = 0;
        uint casevacNonUsMilitary = 0, casevacNonUsCivilian = 0;
        uint casevacEpw = 0, casevacChild = 0;
        uint casevacTerrainFlags = 0;
        string casevacFrequency = "";

        // --- EmergencyAlert accumulators ---
        bool hasEmergencyData = false;
        var emergencyTypeEnum = EmergencyAlert.Types.Type.Unspecified;
        string emergencyAuthoringUid = "";
        string emergencyCancelReferenceUid = "";

        // --- TaskRequest accumulators ---
        bool hasTaskData = false;
        string taskTypeTag = "";
        string taskTargetUid = "";
        string taskAssigneeUid = "";
        var taskPriority = TaskRequest.Types.Priority.Unspecified;
        var taskStatus = TaskRequest.Types.Status.Unspecified;
        string taskNote = "";

        // --- GeoChat receipt accumulators ---
        string chatReceiptForUid = "";
        var chatReceiptType = GeoChat.Types.ReceiptType.None;

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
                // Trim whitespace — iTAK uses "lat, lon" with a space after comma
                if (!double.TryParse(parts[0].Trim(), out var plat) || !double.TryParse(parts[1].Trim(), out var plon)) return;
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
                // Chat receipts: <link uid="…original-message-uid…"
                // relation="p-p" type="b-t-f"/> on a b-t-f-d or b-t-f-r
                // event is a receipt pointing at the acknowledged message.
                if (typeStr == "b-t-f-d" || typeStr == "b-t-f-r")
                {
                    if (chatReceiptForUid.Length == 0) chatReceiptForUid = linkUid;
                    chatReceiptType = typeStr == "b-t-f-d"
                        ? GeoChat.Types.ReceiptType.Delivered
                        : GeoChat.Types.ReceiptType.Read;
                    hasChat = true;
                }
                else if (typeStr == "t-s")
                {
                    // Task target link: first non-self-ref p-p link on
                    // a t-s event is the target being tasked.
                    if (taskTargetUid.Length == 0) taskTargetUid = linkUid;
                    hasTaskData = true;
                }
                else if (typeStr.StartsWith("b-a-"))
                {
                    // Emergency links: a b-a-* event may carry two p-p links:
                    //   1. authoring link (type a-f-*): who raised the alert
                    //   2. cancel-reference link (type b-a-*): the alert being cancelled
                    if (linkType.StartsWith("b-a-"))
                    {
                        if (emergencyCancelReferenceUid.Length == 0) emergencyCancelReferenceUid = linkUid;
                    }
                    else
                    {
                        if (emergencyAuthoringUid.Length == 0) emergencyAuthoringUid = linkUid;
                    }
                    hasEmergencyData = true;
                }
                else
                {
                    markerParentUid = linkUid;
                    markerParentType = linkType;
                    if (parentCallsign.Length > 0) markerParentCallsign = parentCallsign;
                    hasMarkerData = true;
                }
            }
        }

        foreach (var el in detail.Elements())
        {
            switch (el.Name.LocalName)
            {
                case "contact":
                    pkt.Callsign = el.Attribute("callsign")?.Value ?? "";
                    // Normalize default TAK endpoints to empty — saves ~20 wire bytes
                    if (el.Attribute("endpoint") is { } epAttr &&
                        epAttr.Value != "0.0.0.0:4242:tcp" && epAttr.Value != "*:-1:stcp")
                        pkt.Endpoint = epAttr.Value;
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
                    // Proto field is uint32 (cm/s for speed, deg*100 for
                    // course). ATAK emits speed="-1.0" for stationary /
                    // unknown targets; casting a negative double to uint
                    // in C# is undefined behavior (typically wraps to a
                    // huge value). Clamp to 0 first.
                    var spdCs = double.Parse(el.Attribute("speed")?.Value ?? "0") * 100;
                    var crsCs = double.Parse(el.Attribute("course")?.Value ?? "0") * 100;
                    pkt.Speed = (uint)Math.Max(0, Math.Round(spdCs));
                    pkt.Course = (uint)Math.Max(0, Math.Round(crsCs));
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
                    // "All Chat Rooms" is the broadcast sentinel — omit from proto
                    // so the field costs 0 bytes on the wire instead of 16.
                    var chatId = el.Attribute("id")?.Value;
                    chatTo = chatId == "All Chat Rooms" ? null : chatId;
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
                // --- CasevacReport (9-line MEDEVAC) -----------------------
                // ATAK writes a <_medevac_> element with ~25 attributes for
                // the 9-line fields.  We map the structured ones; the rest
                // are captured by the envelope (contact, location, freq).
                case "_medevac_":
                    hasCasevacData = true;
                    if (el.Attribute("precedence")?.Value is { } precStr &&
                        PrecedenceMap.TryGetValue(precStr, out var precVal))
                        casevacPrecedence = precVal;
                    // Equipment bitfield flags
                    uint eqFlags = 0;
                    if (el.Attribute("none")?.Value == "true") eqFlags |= 0x01;
                    if (el.Attribute("hoist")?.Value == "true") eqFlags |= 0x02;
                    if (el.Attribute("extraction_equipment")?.Value == "true") eqFlags |= 0x04;
                    if (el.Attribute("ventilator")?.Value == "true") eqFlags |= 0x08;
                    if (el.Attribute("blood")?.Value == "true") eqFlags |= 0x10;
                    casevacEquipmentFlags = eqFlags;
                    if (uint.TryParse(el.Attribute("litter")?.Value, out var lit))
                        casevacLitterPatients = lit;
                    if (uint.TryParse(el.Attribute("ambulatory")?.Value, out var amb))
                        casevacAmbulatoryPatients = amb;
                    if (el.Attribute("security")?.Value is { } secStr &&
                        SecurityMap.TryGetValue(secStr, out var secVal))
                        casevacSecurity = secVal;
                    if (el.Attribute("hlz_marking")?.Value is { } hlzStr &&
                        HlzMarkingMap.TryGetValue(hlzStr, out var hlzVal))
                        casevacHlzMarking = hlzVal;
                    casevacZoneMarker = el.Attribute("zone_prot_marker")?.Value ?? "";
                    if (uint.TryParse(el.Attribute("us_military")?.Value, out var usMil))
                        casevacUsMilitary = usMil;
                    if (uint.TryParse(el.Attribute("us_civilian")?.Value, out var usCiv))
                        casevacUsCivilian = usCiv;
                    if (uint.TryParse(el.Attribute("non_us_military")?.Value, out var nonUsMil))
                        casevacNonUsMilitary = nonUsMil;
                    if (uint.TryParse(el.Attribute("non_us_civilian")?.Value, out var nonUsCiv))
                        casevacNonUsCivilian = nonUsCiv;
                    if (uint.TryParse(el.Attribute("epw")?.Value, out var epw))
                        casevacEpw = epw;
                    if (uint.TryParse(el.Attribute("child")?.Value, out var child))
                        casevacChild = child;
                    // Terrain bitfield flags
                    uint tfFlags = 0;
                    if (el.Attribute("terrain_slope")?.Value == "true") tfFlags |= 0x01;
                    if (el.Attribute("terrain_rough")?.Value == "true") tfFlags |= 0x02;
                    if (el.Attribute("terrain_loose")?.Value == "true") tfFlags |= 0x04;
                    if (el.Attribute("terrain_trees")?.Value == "true") tfFlags |= 0x08;
                    if (el.Attribute("terrain_wires")?.Value == "true") tfFlags |= 0x10;
                    if (el.Attribute("terrain_other")?.Value == "true") tfFlags |= 0x20;
                    casevacTerrainFlags = tfFlags;
                    casevacFrequency = el.Attribute("freq")?.Value ?? "";
                    break;
                // --- EmergencyAlert ---------------------------------------
                // ATAK writes: <emergency type="911 Alert"/> or
                //              <emergency cancel="true"/>
                case "emergency":
                    hasEmergencyData = true;
                    if (el.Attribute("type")?.Value is { } eTypeAttr &&
                        EmergencyTypeMap.TryGetValue(eTypeAttr, out var eTypeVal))
                        emergencyTypeEnum = eTypeVal;
                    else
                        emergencyTypeEnum = EmergencyTypeFromCotType(typeStr);
                    if (el.Attribute("cancel")?.Value == "true")
                        emergencyTypeEnum = EmergencyAlert.Types.Type.Cancel;
                    break;
                // --- TaskRequest ------------------------------------------
                // ATAK writes: <task type="engage" priority="High"
                //   status="Pending" note="…" assignee="…"/>
                // (Element name varies — also sometimes "_task_".)
                case "task":
                case "_task_":
                    hasTaskData = true;
                    taskTypeTag = el.Attribute("type")?.Value ?? "";
                    if (el.Attribute("priority")?.Value is { } tpStr &&
                        TaskPriorityMap.TryGetValue(tpStr, out var tpVal))
                        taskPriority = tpVal;
                    if (el.Attribute("status")?.Value is { } tsStr &&
                        TaskStatusMap.TryGetValue(tsStr, out var tsVal))
                        taskStatus = tsVal;
                    if (el.Attribute("note")?.Value is { } noteAttr && noteAttr.Length > 0)
                        taskNote = noteAttr;
                    if (el.Attribute("assignee")?.Value is { } assigneeAttr && assigneeAttr.Length > 0)
                        taskAssigneeUid = assigneeAttr;
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

        // Payload priority: chat > aircraft > route > rab > shape > marker >
        // casevac > emergency > task > pli.
        //
        // Chat wins first to keep parity with pre-v2 behavior; chat receipts
        // (b-t-f-d / b-t-f-r) ride on the same Chat variant with receipt
        // fields populated.
        if (hasChat)
        {
            var chat = new GeoChat { Message = remarksText };
            if (chatTo != null) chat.To = chatTo;
            if (chatToCs != null) chat.ToCallsign = chatToCs;
            if (chatReceiptForUid.Length > 0) chat.ReceiptForUid = chatReceiptForUid;
            if (chatReceiptType != GeoChat.Types.ReceiptType.None) chat.ReceiptType = chatReceiptType;
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
        else if (hasCasevacData)
        {
            pkt.Casevac = new CasevacReport
            {
                Precedence = casevacPrecedence,
                EquipmentFlags = casevacEquipmentFlags,
                LitterPatients = casevacLitterPatients,
                AmbulatoryPatients = casevacAmbulatoryPatients,
                Security = casevacSecurity,
                HlzMarking = casevacHlzMarking,
                ZoneMarker = casevacZoneMarker,
                UsMilitary = casevacUsMilitary,
                UsCivilian = casevacUsCivilian,
                NonUsMilitary = casevacNonUsMilitary,
                NonUsCivilian = casevacNonUsCivilian,
                Epw = casevacEpw,
                Child = casevacChild,
                TerrainFlags = casevacTerrainFlags,
                Frequency = casevacFrequency,
            };
        }
        else if (hasEmergencyData)
        {
            pkt.Emergency = new EmergencyAlert
            {
                Type = emergencyTypeEnum != EmergencyAlert.Types.Type.Unspecified
                    ? emergencyTypeEnum
                    : EmergencyTypeFromCotType(typeStr),
                AuthoringUid = emergencyAuthoringUid,
                CancelReferenceUid = emergencyCancelReferenceUid,
            };
        }
        else if (hasTaskData)
        {
            pkt.Task = new TaskRequest
            {
                TaskType = taskTypeTag,
                TargetUid = taskTargetUid,
                AssigneeUid = taskAssigneeUid,
                Priority = taskPriority,
                Status = taskStatus,
                Note = taskNote,
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
