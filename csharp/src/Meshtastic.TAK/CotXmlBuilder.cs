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

    // --- CasevacReport reverse lookups (mirror CotXmlParser maps) -------
    private static readonly Dictionary<CasevacReport.Types.Precedence, string> PrecedenceNames = new()
    {
        [CasevacReport.Types.Precedence.Urgent] = "Urgent",
        [CasevacReport.Types.Precedence.UrgentSurgical] = "Urgent Surgical",
        [CasevacReport.Types.Precedence.Priority] = "Priority",
        [CasevacReport.Types.Precedence.Routine] = "Routine",
        [CasevacReport.Types.Precedence.Convenience] = "Convenience",
    };
    private static readonly Dictionary<CasevacReport.Types.HlzMarking, string> HlzMarkingNames = new()
    {
        [CasevacReport.Types.HlzMarking.Panels] = "Panels",
        [CasevacReport.Types.HlzMarking.PyroSignal] = "Pyro",
        [CasevacReport.Types.HlzMarking.Smoke] = "Smoke",
        [CasevacReport.Types.HlzMarking.None] = "None",
        [CasevacReport.Types.HlzMarking.Other] = "Other",
    };
    private static readonly Dictionary<CasevacReport.Types.Security, string> SecurityNames = new()
    {
        [CasevacReport.Types.Security.NoEnemy] = "N",
        [CasevacReport.Types.Security.PossibleEnemy] = "P",
        [CasevacReport.Types.Security.EnemyInArea] = "E",
        [CasevacReport.Types.Security.EnemyInArmedContact] = "X",
    };

    // --- EmergencyAlert reverse lookups ---------------------------------
    private static readonly Dictionary<EmergencyAlert.Types.Type, string> EmergencyTypeNames = new()
    {
        [EmergencyAlert.Types.Type.Alert911] = "911 Alert",
        [EmergencyAlert.Types.Type.RingTheBell] = "Ring The Bell",
        [EmergencyAlert.Types.Type.InContact] = "In Contact",
        [EmergencyAlert.Types.Type.GeoFenceBreached] = "Geo-fence Breached",
        [EmergencyAlert.Types.Type.Custom] = "Custom",
        [EmergencyAlert.Types.Type.Cancel] = "Cancel",
    };

    // --- TaskRequest reverse lookups ------------------------------------
    private static readonly Dictionary<TaskRequest.Types.Priority, string> TaskPriorityNames = new()
    {
        [TaskRequest.Types.Priority.Low] = "Low",
        [TaskRequest.Types.Priority.Normal] = "Normal",
        [TaskRequest.Types.Priority.High] = "High",
        [TaskRequest.Types.Priority.Critical] = "Critical",
    };
    private static readonly Dictionary<TaskRequest.Types.Status, string> TaskStatusNames = new()
    {
        [TaskRequest.Types.Status.Pending] = "Pending",
        [TaskRequest.Types.Status.Acknowledged] = "Acknowledged",
        [TaskRequest.Types.Status.InProgress] = "In Progress",
        [TaskRequest.Types.Status.Completed] = "Completed",
        [TaskRequest.Types.Status.Cancelled] = "Cancelled",
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
                // Chat receipts (b-t-f-d / b-t-f-r) emit a <link> to the
                // original message UID instead of a <remarks> element; the
                // envelope cot_type_id carries the discriminator.
                if (pkt.Chat.ReceiptType != GeoChat.Types.ReceiptType.None &&
                    !string.IsNullOrEmpty(pkt.Chat.ReceiptForUid))
                {
                    sb.AppendLine($"    <link uid=\"{Esc(pkt.Chat.ReceiptForUid)}\" relation=\"p-p\" type=\"b-t-f\"/>");
                }
                else
                {
                    sb.AppendLine($"    <remarks>{Esc(pkt.Chat.Message)}</remarks>");
                }
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
            case TAKPacketV2.PayloadVariantOneofCase.Casevac:
                EmitCasevac(sb, pkt.Casevac);
                break;
            case TAKPacketV2.PayloadVariantOneofCase.Emergency:
                EmitEmergency(sb, pkt.Emergency);
                break;
            case TAKPacketV2.PayloadVariantOneofCase.Task:
                EmitTask(sb, pkt.Task);
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
            kind == DrawnShape.Types.Kind.Bullseye ||
            kind == DrawnShape.Types.Kind.Ellipse)
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

    private void EmitCasevac(StringBuilder sb, CasevacReport c)
    {
        var parts = new List<string>();
        if (PrecedenceNames.TryGetValue(c.Precedence, out var precName))
            parts.Add($"precedence=\"{precName}\"");

        // Equipment bitfield flags
        if ((c.EquipmentFlags & 0x01) != 0) parts.Add("none=\"true\"");
        if ((c.EquipmentFlags & 0x02) != 0) parts.Add("hoist=\"true\"");
        if ((c.EquipmentFlags & 0x04) != 0) parts.Add("extraction_equipment=\"true\"");
        if ((c.EquipmentFlags & 0x08) != 0) parts.Add("ventilator=\"true\"");
        if ((c.EquipmentFlags & 0x10) != 0) parts.Add("blood=\"true\"");

        if (c.LitterPatients > 0) parts.Add($"litter=\"{c.LitterPatients}\"");
        if (c.AmbulatoryPatients > 0) parts.Add($"ambulatory=\"{c.AmbulatoryPatients}\"");

        if (SecurityNames.TryGetValue(c.Security, out var secName))
            parts.Add($"security=\"{secName}\"");
        if (HlzMarkingNames.TryGetValue(c.HlzMarking, out var hlzName))
            parts.Add($"hlz_marking=\"{hlzName}\"");

        if (!string.IsNullOrEmpty(c.ZoneMarker))
            parts.Add($"zone_prot_marker=\"{Esc(c.ZoneMarker)}\"");

        if (c.UsMilitary > 0) parts.Add($"us_military=\"{c.UsMilitary}\"");
        if (c.UsCivilian > 0) parts.Add($"us_civilian=\"{c.UsCivilian}\"");
        if (c.NonUsMilitary > 0) parts.Add($"non_us_military=\"{c.NonUsMilitary}\"");
        if (c.NonUsCivilian > 0) parts.Add($"non_us_civilian=\"{c.NonUsCivilian}\"");
        if (c.Epw > 0) parts.Add($"epw=\"{c.Epw}\"");
        if (c.Child > 0) parts.Add($"child=\"{c.Child}\"");

        // Terrain bitfield flags
        if ((c.TerrainFlags & 0x01) != 0) parts.Add("terrain_slope=\"true\"");
        if ((c.TerrainFlags & 0x02) != 0) parts.Add("terrain_rough=\"true\"");
        if ((c.TerrainFlags & 0x04) != 0) parts.Add("terrain_loose=\"true\"");
        if ((c.TerrainFlags & 0x08) != 0) parts.Add("terrain_trees=\"true\"");
        if ((c.TerrainFlags & 0x10) != 0) parts.Add("terrain_wires=\"true\"");
        if ((c.TerrainFlags & 0x20) != 0) parts.Add("terrain_other=\"true\"");

        if (!string.IsNullOrEmpty(c.Frequency))
            parts.Add($"freq=\"{Esc(c.Frequency)}\"");

        sb.AppendLine(parts.Count > 0
            ? $"    <_medevac_ {string.Join(" ", parts)}/>"
            : "    <_medevac_/>");
    }

    private void EmitEmergency(StringBuilder sb, EmergencyAlert e)
    {
        var parts = new List<string>();
        if (e.Type == EmergencyAlert.Types.Type.Cancel)
        {
            parts.Add("cancel=\"true\"");
        }
        else if (EmergencyTypeNames.TryGetValue(e.Type, out var typeName))
        {
            parts.Add($"type=\"{typeName}\"");
        }
        sb.AppendLine(parts.Count > 0
            ? $"    <emergency {string.Join(" ", parts)}/>"
            : "    <emergency/>");

        if (!string.IsNullOrEmpty(e.AuthoringUid))
        {
            sb.AppendLine($"    <link uid=\"{Esc(e.AuthoringUid)}\" relation=\"p-p\" type=\"a-f-G-U-C\"/>");
        }
        if (!string.IsNullOrEmpty(e.CancelReferenceUid))
        {
            sb.AppendLine($"    <link uid=\"{Esc(e.CancelReferenceUid)}\" relation=\"p-p\" type=\"b-a-o-tbl\"/>");
        }
    }

    private void EmitTask(StringBuilder sb, TaskRequest t)
    {
        var parts = new List<string>();
        if (!string.IsNullOrEmpty(t.TaskType))
            parts.Add($"type=\"{Esc(t.TaskType)}\"");
        if (TaskPriorityNames.TryGetValue(t.Priority, out var prioName))
            parts.Add($"priority=\"{prioName}\"");
        if (TaskStatusNames.TryGetValue(t.Status, out var statusName))
            parts.Add($"status=\"{statusName}\"");
        if (!string.IsNullOrEmpty(t.AssigneeUid))
            parts.Add($"assignee=\"{Esc(t.AssigneeUid)}\"");
        if (!string.IsNullOrEmpty(t.Note))
            parts.Add($"note=\"{Esc(t.Note)}\"");

        sb.AppendLine(parts.Count > 0
            ? $"    <task {string.Join(" ", parts)}/>"
            : "    <task/>");

        if (!string.IsNullOrEmpty(t.TargetUid))
        {
            sb.AppendLine($"    <link uid=\"{Esc(t.TargetUid)}\" relation=\"p-p\" type=\"a-f-G\"/>");
        }
    }
}
