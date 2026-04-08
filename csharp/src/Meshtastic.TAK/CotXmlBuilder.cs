using System.Text;

namespace Meshtastic.TAK;

public class CotXmlBuilder
{
    private static readonly Dictionary<int, string> TeamNames = new()
    {
        [1] = "White", [2] = "Yellow", [3] = "Orange", [4] = "Magenta",
        [5] = "Red", [6] = "Maroon", [7] = "Purple", [8] = "Dark Blue",
        [9] = "Blue", [10] = "Cyan", [11] = "Teal", [12] = "Green",
        [13] = "Dark Green", [14] = "Brown",
    };

    private static readonly Dictionary<int, string> RoleNames = new()
    {
        [1] = "Team Member", [2] = "Team Lead", [3] = "HQ",
        [4] = "Sniper", [5] = "Medic", [6] = "ForwardObserver",
        [7] = "RTO", [8] = "K9",
    };

    private static string GeoSrcName(int src) => src switch
    {
        1 => "GPS", 2 => "USER", 3 => "NETWORK", _ => "???",
    };

    private static string Esc(string s) => System.Security.SecurityElement.Escape(s) ?? s;

    public string Build(Meshtastic.Protobufs.TAKPacketV2 pkt)
    {
        var now = DateTimeOffset.UtcNow;
        var staleSecs = Math.Max((int)pkt.StaleSeconds, 45);
        var stale = now.AddSeconds(staleSecs);
        var timeStr = now.ToString("yyyy-MM-ddTHH:mm:ss.fffZ");
        var staleStr = stale.ToString("yyyy-MM-ddTHH:mm:ss.fffZ");

        var cotType = CotTypeMapper.TypeToString((int)pkt.CotTypeId) ?? pkt.CotTypeStr ?? "";
        var how = CotTypeMapper.HowToString((int)pkt.How) ?? "m-g";
        var lat = pkt.LatitudeI / 1e7;
        var lon = pkt.LongitudeI / 1e7;

        var sb = new StringBuilder();
        sb.AppendLine("<?xml version=\"1.0\" encoding=\"UTF-8\"?>");
        sb.AppendLine($"<event version=\"2.0\" uid=\"{Esc(pkt.Uid)}\" type=\"{Esc(cotType)}\" how=\"{Esc(how)}\" time=\"{timeStr}\" start=\"{timeStr}\" stale=\"{staleStr}\">");
        sb.AppendLine($"  <point lat=\"{lat}\" lon=\"{lon}\" hae=\"{pkt.Altitude}\" ce=\"9999999\" le=\"9999999\"/>");
        sb.AppendLine("  <detail>");

        if (!string.IsNullOrEmpty(pkt.Callsign))
        {
            var tag = $"    <contact callsign=\"{Esc(pkt.Callsign)}\"";
            if (!string.IsNullOrEmpty(pkt.Endpoint)) tag += $" endpoint=\"{Esc(pkt.Endpoint)}\"";
            if (!string.IsNullOrEmpty(pkt.Phone)) tag += $" phone=\"{Esc(pkt.Phone)}\"";
            sb.AppendLine(tag + "/>");
        }

        TeamNames.TryGetValue((int)pkt.Team, out var teamName);
        RoleNames.TryGetValue((int)pkt.Role, out var roleName);
        if (teamName != null || roleName != null)
        {
            var tag = "    <__group";
            if (roleName != null) tag += $" role=\"{roleName}\"";
            if (teamName != null) tag += $" name=\"{teamName}\"";
            sb.AppendLine(tag + "/>");
        }

        if (pkt.Battery > 0) sb.AppendLine($"    <status battery=\"{pkt.Battery}\"/>");

        if (pkt.Speed > 0 || pkt.Course > 0)
            sb.AppendLine($"    <track speed=\"{pkt.Speed / 100.0}\" course=\"{pkt.Course / 100.0}\"/>");

        if (!string.IsNullOrEmpty(pkt.TakVersion) || !string.IsNullOrEmpty(pkt.TakPlatform))
        {
            var tag = "    <takv";
            if (!string.IsNullOrEmpty(pkt.TakDevice)) tag += $" device=\"{Esc(pkt.TakDevice)}\"";
            if (!string.IsNullOrEmpty(pkt.TakPlatform)) tag += $" platform=\"{Esc(pkt.TakPlatform)}\"";
            if (!string.IsNullOrEmpty(pkt.TakOs)) tag += $" os=\"{Esc(pkt.TakOs)}\"";
            if (!string.IsNullOrEmpty(pkt.TakVersion)) tag += $" version=\"{Esc(pkt.TakVersion)}\"";
            sb.AppendLine(tag + "/>");
        }

        if (pkt.GeoSrc != 0 || pkt.AltSrc != 0)
            sb.AppendLine($"    <precisionlocation geopointsrc=\"{GeoSrcName((int)pkt.GeoSrc)}\" altsrc=\"{GeoSrcName((int)pkt.AltSrc)}\"/>");

        if (!string.IsNullOrEmpty(pkt.DeviceCallsign))
            sb.AppendLine($"    <uid Droid=\"{Esc(pkt.DeviceCallsign)}\"/>");

        if (pkt.PayloadVariantCase == Meshtastic.Protobufs.TAKPacketV2.PayloadVariantOneofCase.Chat)
            sb.AppendLine($"    <remarks>{Esc(pkt.Chat.Message)}</remarks>");
        else if (pkt.PayloadVariantCase == Meshtastic.Protobufs.TAKPacketV2.PayloadVariantOneofCase.Aircraft && !string.IsNullOrEmpty(pkt.Aircraft.Icao))
        {
            var tag = $"    <_aircot_ icao=\"{Esc(pkt.Aircraft.Icao)}\"";
            if (!string.IsNullOrEmpty(pkt.Aircraft.Registration)) tag += $" reg=\"{Esc(pkt.Aircraft.Registration)}\"";
            if (!string.IsNullOrEmpty(pkt.Aircraft.Flight)) tag += $" flight=\"{Esc(pkt.Aircraft.Flight)}\"";
            if (!string.IsNullOrEmpty(pkt.Aircraft.Category)) tag += $" cat=\"{Esc(pkt.Aircraft.Category)}\"";
            if (!string.IsNullOrEmpty(pkt.Aircraft.CotHostId)) tag += $" cot_host_id=\"{Esc(pkt.Aircraft.CotHostId)}\"";
            sb.AppendLine(tag + "/>");
        }

        sb.AppendLine("  </detail>");
        sb.Append("</event>");
        return sb.ToString();
    }
}
