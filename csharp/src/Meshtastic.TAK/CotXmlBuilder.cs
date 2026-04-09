using System.Globalization;
using System.Text;
using Meshtastic.Protobufs;

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

    private static readonly Dictionary<Route.Types.Method, string> RouteMethodNames = new()
    {
        [Route.Types.Method.Driving] = "Driving",
        [Route.Types.Method.Walking] = "Walking",
        [Route.Types.Method.Flying] = "Flying",
        [Route.Types.Method.Swimming] = "Swimming",
        [Route.Types.Method.Watercraft] = "Watercraft",
    };

    private static readonly Dictionary<Route.Types.Direction, string> RouteDirectionNames = new()
    {
        [Route.Types.Direction.Infil] = "Infil",
        [Route.Types.Direction.Exfil] = "Exfil",
    };

    private static readonly Dictionary<uint, string> BearingRefNames = new()
    {
        [1] = "M", [2] = "T", [3] = "G",
    };

    private static string GeoSrcName(int src) => src switch
    {
        1 => "GPS", 2 => "USER", 3 => "NETWORK", _ => "???",
    };

    private static string Esc(string s) => System.Security.SecurityElement.Escape(s) ?? s;

    /// <summary>
    /// Render a double using invariant culture so locales with comma
    /// decimal separators (e.g. de-DE) don't break CoT XML parsing.
    /// </summary>
    private static string F(double d) => d.ToString("R", CultureInfo.InvariantCulture);

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

        switch (pkt.PayloadVariantCase)
        {
            case TAKPacketV2.PayloadVariantOneofCase.Chat:
                sb.AppendLine($"    <remarks>{Esc(pkt.Chat.Message)}</remarks>");
                break;
            case TAKPacketV2.PayloadVariantOneofCase.Aircraft when !string.IsNullOrEmpty(pkt.Aircraft.Icao):
            {
                var tag = $"    <_aircot_ icao=\"{Esc(pkt.Aircraft.Icao)}\"";
                if (!string.IsNullOrEmpty(pkt.Aircraft.Registration)) tag += $" reg=\"{Esc(pkt.Aircraft.Registration)}\"";
                if (!string.IsNullOrEmpty(pkt.Aircraft.Flight)) tag += $" flight=\"{Esc(pkt.Aircraft.Flight)}\"";
                if (!string.IsNullOrEmpty(pkt.Aircraft.Category)) tag += $" cat=\"{Esc(pkt.Aircraft.Category)}\"";
                if (!string.IsNullOrEmpty(pkt.Aircraft.CotHostId)) tag += $" cot_host_id=\"{Esc(pkt.Aircraft.CotHostId)}\"";
                sb.AppendLine(tag + "/>");
                break;
            }
            case TAKPacketV2.PayloadVariantOneofCase.Shape:
                EmitShape(sb, pkt.Shape, pkt.LatitudeI, pkt.LongitudeI);
                break;
            case TAKPacketV2.PayloadVariantOneofCase.Marker:
                EmitMarker(sb, pkt.Marker);
                break;
            case TAKPacketV2.PayloadVariantOneofCase.Rab:
                EmitRab(sb, pkt.Rab, pkt.LatitudeI, pkt.LongitudeI);
                break;
            case TAKPacketV2.PayloadVariantOneofCase.Route:
                EmitRoute(sb, pkt.Route, pkt.LatitudeI, pkt.LongitudeI);
                break;
            case TAKPacketV2.PayloadVariantOneofCase.RawDetail:
                // Fallback path (TakCompressor.CompressBestOf): emit the
                // raw bytes verbatim as the inner content of <detail>.
                // No re-escaping — bytes pass through so the receiver
                // round trip stays byte-exact with the source XML.
                if (pkt.RawDetail.Length > 0)
                {
                    var text = pkt.RawDetail.ToStringUtf8();
                    sb.Append(text);
                    if (!text.EndsWith('\n')) sb.AppendLine();
                }
                break;
        }

        sb.AppendLine("  </detail>");
        sb.Append("</event>");
        return sb.ToString();
    }

    // --- Typed geometry emitters ----------------------------------------

    private void EmitShape(StringBuilder sb, DrawnShape shape, int eventLatI, int eventLonI)
    {
        var strokeArgb = AtakPalette.ResolveColor(shape.StrokeColor, shape.StrokeArgb);
        var fillArgb = AtakPalette.ResolveColor(shape.FillColor, shape.FillArgb);
        var strokeVal = unchecked((int)strokeArgb);
        var fillVal = unchecked((int)fillArgb);

        var style = shape.Style;
        var emitStroke = style == DrawnShape.Types.StyleMode.StrokeOnly ||
            style == DrawnShape.Types.StyleMode.StrokeAndFill ||
            (style == DrawnShape.Types.StyleMode.Unspecified && strokeVal != 0);
        var emitFill = style == DrawnShape.Types.StyleMode.FillOnly ||
            style == DrawnShape.Types.StyleMode.StrokeAndFill ||
            (style == DrawnShape.Types.StyleMode.Unspecified && fillVal != 0);

        var kind = shape.Kind;
        if (kind == DrawnShape.Types.Kind.Circle ||
            kind == DrawnShape.Types.Kind.RangingCircle ||
            kind == DrawnShape.Types.Kind.Bullseye)
        {
            if (shape.MajorCm > 0 || shape.MinorCm > 0)
            {
                var majorM = shape.MajorCm / 100.0;
                var minorM = shape.MinorCm / 100.0;
                sb.AppendLine("    <shape>");
                sb.AppendLine($"      <ellipse major=\"{F(majorM)}\" minor=\"{F(minorM)}\" angle=\"{shape.AngleDeg}\"/>");
                sb.AppendLine("    </shape>");
            }
        }
        else
        {
            foreach (var v in shape.Vertices)
            {
                var vlat = (eventLatI + v.LatDeltaI) / 1e7;
                var vlon = (eventLonI + v.LonDeltaI) / 1e7;
                sb.AppendLine($"    <link point=\"{F(vlat)},{F(vlon)}\"/>");
            }
        }

        if (kind == DrawnShape.Types.Kind.Bullseye)
        {
            var parts = new List<string>();
            if (shape.BullseyeDistanceDm > 0)
                parts.Add($"distance=\"{F(shape.BullseyeDistanceDm / 10.0)}\"");
            if (BearingRefNames.TryGetValue(shape.BullseyeBearingRef, out var refStr))
                parts.Add($"bearingRef=\"{refStr}\"");
            if ((shape.BullseyeFlags & 0x01) != 0) parts.Add("rangeRingVisible=\"true\"");
            if ((shape.BullseyeFlags & 0x02) != 0) parts.Add("hasRangeRings=\"true\"");
            if ((shape.BullseyeFlags & 0x04) != 0) parts.Add("edgeToCenter=\"true\"");
            if ((shape.BullseyeFlags & 0x08) != 0) parts.Add("mils=\"true\"");
            if (!string.IsNullOrEmpty(shape.BullseyeUidRef))
                parts.Add($"bullseyeUID=\"{Esc(shape.BullseyeUidRef)}\"");
            sb.AppendLine(parts.Count > 0
                ? $"    <bullseye {string.Join(" ", parts)}/>"
                : "    <bullseye/>");
        }

        if (emitStroke)
        {
            sb.AppendLine($"    <strokeColor value=\"{strokeVal}\"/>");
            if (shape.StrokeWeightX10 > 0)
                sb.AppendLine($"    <strokeWeight value=\"{F(shape.StrokeWeightX10 / 10.0)}\"/>");
        }
        if (emitFill)
        {
            sb.AppendLine($"    <fillColor value=\"{fillVal}\"/>");
        }
        sb.AppendLine($"    <labels_on value=\"{(shape.LabelsOn ? "true" : "false")}\"/>");
    }

    private void EmitMarker(StringBuilder sb, Marker marker)
    {
        if (marker.Readiness) sb.AppendLine("    <status readiness=\"true\"/>");
        if (!string.IsNullOrEmpty(marker.ParentUid))
        {
            var tag = $"    <link uid=\"{Esc(marker.ParentUid)}\"";
            if (!string.IsNullOrEmpty(marker.ParentType)) tag += $" type=\"{Esc(marker.ParentType)}\"";
            if (!string.IsNullOrEmpty(marker.ParentCallsign)) tag += $" parent_callsign=\"{Esc(marker.ParentCallsign)}\"";
            tag += " relation=\"p-p\"";
            sb.AppendLine(tag + "/>");
        }
        var colorArgb = AtakPalette.ResolveColor(marker.Color, marker.ColorArgb);
        var colorVal = unchecked((int)colorArgb);
        if (colorVal != 0) sb.AppendLine($"    <color argb=\"{colorVal}\"/>");
        if (!string.IsNullOrEmpty(marker.Iconset))
            sb.AppendLine($"    <usericon iconsetpath=\"{Esc(marker.Iconset)}\"/>");
    }

    private void EmitRab(StringBuilder sb, RangeAndBearing rab, int eventLatI, int eventLonI)
    {
        var anchorLatI = eventLatI + (rab.Anchor?.LatDeltaI ?? 0);
        var anchorLonI = eventLonI + (rab.Anchor?.LonDeltaI ?? 0);
        if (anchorLatI != 0 || anchorLonI != 0)
        {
            var alat = anchorLatI / 1e7;
            var alon = anchorLonI / 1e7;
            var tag = "    <link";
            if (!string.IsNullOrEmpty(rab.AnchorUid)) tag += $" uid=\"{Esc(rab.AnchorUid)}\"";
            tag += $" relation=\"p-p\" type=\"b-m-p-w\" point=\"{F(alat)},{F(alon)}\"";
            sb.AppendLine(tag + "/>");
        }
        if (rab.RangeCm > 0)
            sb.AppendLine($"    <range value=\"{F(rab.RangeCm / 100.0)}\"/>");
        if (rab.BearingCdeg > 0)
            sb.AppendLine($"    <bearing value=\"{F(rab.BearingCdeg / 100.0)}\"/>");
        var strokeArgb = AtakPalette.ResolveColor(rab.StrokeColor, rab.StrokeArgb);
        var strokeVal = unchecked((int)strokeArgb);
        if (strokeVal != 0) sb.AppendLine($"    <strokeColor value=\"{strokeVal}\"/>");
        if (rab.StrokeWeightX10 > 0)
            sb.AppendLine($"    <strokeWeight value=\"{F(rab.StrokeWeightX10 / 10.0)}\"/>");
    }

    private void EmitRoute(StringBuilder sb, Route route, int eventLatI, int eventLonI)
    {
        sb.AppendLine("    <__routeinfo/>");
        var parts = new List<string>();
        if (RouteMethodNames.TryGetValue(route.Method, out var method))
            parts.Add($"method=\"{method}\"");
        if (RouteDirectionNames.TryGetValue(route.Direction, out var dir))
            parts.Add($"direction=\"{dir}\"");
        if (!string.IsNullOrEmpty(route.Prefix))
            parts.Add($"prefix=\"{Esc(route.Prefix)}\"");
        if (route.StrokeWeightX10 > 0)
            parts.Add($"stroke=\"{route.StrokeWeightX10 / 10}\"");
        sb.AppendLine(parts.Count > 0
            ? $"    <link_attr {string.Join(" ", parts)}/>"
            : "    <link_attr/>");

        foreach (var link in route.Links)
        {
            var llat = (eventLatI + (link.Point?.LatDeltaI ?? 0)) / 1e7;
            var llon = (eventLonI + (link.Point?.LonDeltaI ?? 0)) / 1e7;
            var linkType = link.LinkType == 1 ? "b-m-p-c" : "b-m-p-w";
            var linkParts = new List<string>();
            if (!string.IsNullOrEmpty(link.Uid)) linkParts.Add($"uid=\"{Esc(link.Uid)}\"");
            linkParts.Add($"type=\"{linkType}\"");
            if (!string.IsNullOrEmpty(link.Callsign)) linkParts.Add($"callsign=\"{Esc(link.Callsign)}\"");
            linkParts.Add($"point=\"{F(llat)},{F(llon)}\"");
            sb.AppendLine($"    <link {string.Join(" ", linkParts)}/>");
        }
    }
}
