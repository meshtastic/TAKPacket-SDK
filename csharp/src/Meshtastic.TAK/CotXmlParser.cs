using System.Text.RegularExpressions;
using System.Xml.Linq;

namespace Meshtastic.TAK;

public class CotXmlParser
{
    private static readonly Dictionary<string, Meshtastic.Protobufs.Team> TeamMap = new()
    {
        ["White"] = Meshtastic.Protobufs.Team.White, ["Yellow"] = Meshtastic.Protobufs.Team.Yellow,
        ["Orange"] = Meshtastic.Protobufs.Team.Orange, ["Magenta"] = Meshtastic.Protobufs.Team.Magenta,
        ["Red"] = Meshtastic.Protobufs.Team.Red, ["Maroon"] = Meshtastic.Protobufs.Team.Maroon,
        ["Purple"] = Meshtastic.Protobufs.Team.Purple, ["Dark Blue"] = Meshtastic.Protobufs.Team.DarkBlue,
        ["Blue"] = Meshtastic.Protobufs.Team.Blue, ["Cyan"] = Meshtastic.Protobufs.Team.Cyan,
        ["Teal"] = Meshtastic.Protobufs.Team.Teal, ["Green"] = Meshtastic.Protobufs.Team.Green,
        ["Dark Green"] = Meshtastic.Protobufs.Team.DarkGreen, ["Brown"] = Meshtastic.Protobufs.Team.Brown,
    };

    private static readonly Dictionary<string, Meshtastic.Protobufs.MemberRole> RoleMap = new()
    {
        ["Team Member"] = Meshtastic.Protobufs.MemberRole.TeamMember, ["Team Lead"] = Meshtastic.Protobufs.MemberRole.TeamLead,
        ["HQ"] = Meshtastic.Protobufs.MemberRole.Hq, ["Sniper"] = Meshtastic.Protobufs.MemberRole.Sniper,
        ["Medic"] = Meshtastic.Protobufs.MemberRole.Medic, ["ForwardObserver"] = Meshtastic.Protobufs.MemberRole.ForwardObserver,
        ["RTO"] = Meshtastic.Protobufs.MemberRole.Rto, ["K9"] = Meshtastic.Protobufs.MemberRole.K9,
    };

    private static Meshtastic.Protobufs.GeoPointSource ParseGeoSrc(string? s) => s switch
    {
        "GPS" => Meshtastic.Protobufs.GeoPointSource.Gps,
        "USER" => Meshtastic.Protobufs.GeoPointSource.User,
        "NETWORK" => Meshtastic.Protobufs.GeoPointSource.Network,
        _ => Meshtastic.Protobufs.GeoPointSource.Unspecified,
    };

    public Meshtastic.Protobufs.TAKPacketV2 Parse(string cotXml)
    {
        // Reject XML with DOCTYPE or ENTITY declarations to prevent XXE and entity expansion
        var lower = cotXml.ToLowerInvariant();
        if (lower.Contains("<!doctype") || lower.Contains("<!entity"))
            throw new ArgumentException("XML contains prohibited DOCTYPE or ENTITY declaration");

        var doc = XDocument.Parse(cotXml);
        var evt = doc.Root!;
        var pkt = new Meshtastic.Protobufs.TAKPacketV2();

        var typeStr = evt.Attribute("type")?.Value ?? "";
        pkt.CotTypeId = (Meshtastic.Protobufs.CotType)CotTypeMapper.TypeToEnum(typeStr);
        if (pkt.CotTypeId == 0) pkt.CotTypeStr = typeStr;
        pkt.How = (Meshtastic.Protobufs.CotHow)CotTypeMapper.HowToEnum(evt.Attribute("how")?.Value ?? "");
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
                    if (el.Attribute("name")?.Value is { } tn && TeamMap.TryGetValue(tn, out var t)) pkt.Team = t;
                    if (el.Attribute("role")?.Value is { } rn && RoleMap.TryGetValue(rn, out var r)) pkt.Role = r;
                    break;
                case "status":
                    pkt.Battery = uint.Parse(el.Attribute("battery")?.Value ?? "0");
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

        if (hasChat)
        {
            var chat = new Meshtastic.Protobufs.GeoChat { Message = remarksText };
            if (chatTo != null) chat.To = chatTo;
            if (chatToCs != null) chat.ToCallsign = chatToCs;
            pkt.Chat = chat;
        }
        else if (hasAircraft)
        {
            pkt.Aircraft = new Meshtastic.Protobufs.AircraftTrack
            {
                Icao = icao, Registration = reg, Flight = flight,
                AircraftType = acType, Squawk = squawk, Category = category,
                RssiX10 = rssiX10, Gps = gps, CotHostId = cotHostId,
            };
        }
        else pkt.Pli = true;

        return pkt;
    }

    private static uint ComputeStaleSeconds(string timeStr, string staleStr)
    {
        if (string.IsNullOrEmpty(timeStr) || string.IsNullOrEmpty(staleStr)) return 0;
        if (!DateTimeOffset.TryParse(timeStr, out var t) || !DateTimeOffset.TryParse(staleStr, out var s)) return 0;
        var diff = (int)(s - t).TotalSeconds;
        return diff > 0 ? (uint)diff : 0;
    }
}
