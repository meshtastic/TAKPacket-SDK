using System.Globalization;
using System.Text;
using Meshtastic.Protobufs;

namespace Meshtastic.TAK;

/// <summary>
/// Rebuilds ATAK Cursor-on-Target (CoT) XML from a decoded <c>TAKPacketV2</c>
/// packet — the inverse of <see cref="CotXmlParser"/>.
/// </summary>
/// <remarks>
/// <para>Emits a complete <c>&lt;event&gt;</c> document with a <c>&lt;point&gt;</c>
/// and a <c>&lt;detail&gt;</c> body whose children are reconstructed from the
/// packet's payload variant (chat, aircraft, drawn shape, marker, range &amp;
/// bearing, route, casevac, emergency, task, TAK-Talk) plus the shared envelope
/// fields (contact, group, track, TAK version, etc.).</para>
/// <para>Element shapes and attribute ordering are chosen to match what real
/// ATAK / iTAK captures emit, so the receiving plugin parses the rebuilt XML
/// natively. The builder reverses the parser's unit scaling — speed back to m/s
/// (from cm/s), course back to degrees (from degrees×100), shape radii back to
/// meters (from centimeters), and integer-scaled coordinates back to degrees
/// (from degrees×1e7). Delta-encoded route waypoints, R&amp;B anchors, and
/// drawn-shape vertices are re-anchored against the envelope coordinate.</para>
/// <para>Doubles are formatted with the invariant culture so locales that use a
/// comma decimal separator cannot corrupt the XML.</para>
/// </remarks>
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
    /// True when decoded raw-detail text may be re-emitted verbatim as the
    /// inner content of the one <c>&lt;detail&gt;</c> element being built.
    /// </summary>
    /// <remarks>
    /// Well-formed CoT escapes a literal '&lt;' as <c>&amp;lt;</c>, so this
    /// inner content can never legitimately carry an <c>&lt;event&gt;</c> or
    /// <c>&lt;detail&gt;</c> tag token. A fragment that does is malformed:
    /// re-emitting it verbatim would unbalance the enclosing
    /// <c>&lt;detail&gt;</c>/<c>&lt;event&gt;</c> wrapper and hand the receiver
    /// a document it can't parse as a single event. Dropping such a fragment
    /// keeps the rebuilt document well-formed.
    /// </remarks>
    private static bool IsRawDetailWellFormed(string text)
    {
        var lower = text.ToLowerInvariant();
        return !lower.Contains("<event")
            && !lower.Contains("</event")
            && !lower.Contains("<detail")
            && !lower.Contains("</detail");
    }

    /// <summary>Convert ARGB int to ABGR hex string (KML color format).</summary>
    private static string ArgbToAbgrHex(int argb)
    {
        var a = (argb >> 24) & 0xFF;
        var r = (argb >> 16) & 0xFF;
        var g = (argb >> 8) & 0xFF;
        var b = argb & 0xFF;
        return $"{a:x2}{b:x2}{g:x2}{r:x2}";
    }

    /// <summary>
    /// Render a double using invariant culture so locales with comma
    /// decimal separators (e.g. de-DE) don't break CoT XML parsing.
    /// </summary>
    private static string F(double d) => d.ToString("R", CultureInfo.InvariantCulture);

    /// <summary>
    /// Build a complete CoT XML <c>&lt;event&gt;</c> document from a decoded packet.
    /// </summary>
    /// <remarks>
    /// <para>The <c>type</c> attribute comes from the packet's CoT type enum, falling
    /// back to <c>cot_type_str</c> for types the enum doesn't know. The <c>time</c> and
    /// <c>start</c> are stamped at the current UTC instant and <c>stale</c> is derived
    /// from <c>stale_seconds</c> (floored at 45s). The detail body is reconstructed from
    /// the packet's payload variant; an empty payload with an <c>a-f-*</c> type round-trips
    /// as an implicit PLI (a bare position report with no payload-specific detail).</para>
    /// </remarks>
    /// <param name="pkt">The decoded packet to serialize.</param>
    /// <returns>The CoT XML document as a string, beginning with the
    /// <c>&lt;?xml …?&gt;</c> declaration.</returns>
    public string Build(Meshtastic.Protobufs.TAKPacketV2 pkt)
    {
        var now = DateTimeOffset.UtcNow;
        var staleSecs = Math.Max((int)pkt.StaleSeconds, 45);
        var stale = now.AddSeconds(staleSecs);
        var timeStr = now.ToString("yyyy-MM-ddTHH:mm:ss.fffZ");
        var staleStr = stale.ToString("yyyy-MM-ddTHH:mm:ss.fffZ");

        var cotType = CotTypeMapper.TypeToString((int)pkt.CotTypeId) ?? pkt.CotTypeStr ?? "";
        // For most CoT types, missing/Unspecified how falls back to "m-g".
        // TAKTALK m-t-t and y- are special-cased to use ATAK's "null" how
        // convention — the plugin's inbound classifier gates on this value
        // for TTS routing, so source how="null" must NOT round-trip as "m-g".
        var how = CotTypeMapper.HowToString((int)pkt.How)
                  ?? ((cotType == "m-t-t" || cotType == "y-") ? "null" : "m-g");
        var lat = pkt.LatitudeI / 1e7;
        var lon = pkt.LongitudeI / 1e7;
        if (pkt.PayloadVariantCase == TAKPacketV2.PayloadVariantOneofCase.Route
            && pkt.LatitudeI == 0 && pkt.LongitudeI == 0
            && pkt.Route.Links.Count > 0)
        {
            lat = (pkt.Route.Links[0].Point?.LatDeltaI ?? 0) / 1e7;
            lon = (pkt.Route.Links[0].Point?.LonDeltaI ?? 0) / 1e7;
        }

        var sb = new StringBuilder();
        sb.AppendLine("<?xml version=\"1.0\" encoding=\"UTF-8\"?>");
        sb.AppendLine($"<event version=\"2.0\" uid=\"{Esc(pkt.Uid)}\" type=\"{Esc(cotType)}\" how=\"{Esc(how)}\" time=\"{timeStr}\" start=\"{timeStr}\" stale=\"{staleStr}\">");
        sb.AppendLine($"  <point lat=\"{F(lat)}\" lon=\"{F(lon)}\" hae=\"{pkt.Altitude}\" ce=\"9999999\" le=\"9999999\"/>");
        sb.AppendLine("  <detail>");

        var isRoute = pkt.PayloadVariantCase == TAKPacketV2.PayloadVariantOneofCase.Route;
        // v0.3.2: skip <contact> for TAKTALK m-t-t and y- — real ATAK never
        // emits a top-level <contact> on those shapes (m-t-t carries the
        // sender via <callsign>SENDER</callsign> inside <detail>; y- uses
        // <sender-callsign>). Emitting a phantom <contact> bloats the
        // wire ~35 bytes and produces XML that doesn't match captures.
        var isTakTalkShape =
            pkt.PayloadVariantCase == TAKPacketV2.PayloadVariantOneofCase.Taktalk
            || pkt.PayloadVariantCase == TAKPacketV2.PayloadVariantOneofCase.TaktalkRoom;
        if (!string.IsNullOrEmpty(pkt.Callsign) && !isRoute && !isTakTalkShape)
        {
            // "*:-1:stcp" = TAK "reply via this server" — required so ATAK routes directed
            // GeoChat / TAK-Talk back down the server stream. A concrete host breaks it,
            // so the constant is emitted unconditionally: packets from older senders may
            // still carry a concrete endpoint, and honoring it would point the receiver at
            // a socket no other mesh member can open.
            var tag = $"    <contact callsign=\"{Esc(pkt.Callsign)}\" endpoint=\"*:-1:stcp\"";
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
            sb.AppendLine($"    <track speed=\"{F(pkt.Speed / 100.0)}\" course=\"{F(pkt.Course / 100.0)}\"/>");

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
                    // Reconstruct the full __chat element that ATAK/iTAK needs
                    // for routing and display. GeoChat event UID format:
                    // GeoChat.{senderUid}.{chatroom}.{messageId}
                    var gcParts = pkt.Uid.Split('.', 4);
                    if (gcParts.Length == 4 && gcParts[0] == "GeoChat")
                    {
                        var senderUid = gcParts[1];
                        var chatroom = gcParts[2];
                        var msgId = gcParts[3];
                        var senderCs = !string.IsNullOrEmpty(pkt.Chat.ToCallsign)
                            ? pkt.Chat.ToCallsign
                            : (!string.IsNullOrEmpty(pkt.Callsign) ? pkt.Callsign : "UNKNOWN");
                        sb.AppendLine($"    <__chat parent=\"RootContactGroup\" groupOwner=\"false\" messageId=\"{Esc(msgId)}\" chatroom=\"{Esc(chatroom)}\" id=\"{Esc(chatroom)}\" senderCallsign=\"{Esc(senderCs)}\">");
                        sb.AppendLine($"      <chatgrp uid0=\"{Esc(senderUid)}\" uid1=\"{Esc(chatroom)}\" id=\"{Esc(chatroom)}\"/>");
                        sb.AppendLine("    </__chat>");
                        sb.AppendLine($"    <link uid=\"{Esc(senderUid)}\" type=\"a-f-G-U-C\" relation=\"p-p\"/>");
                        sb.AppendLine($"    <__serverdestination destinations=\"0.0.0.0:4242:tcp:{Esc(senderUid)}\"/>");
                        sb.AppendLine($"    <remarks source=\"BAO.F.ATAK.{Esc(senderUid)}\" to=\"{Esc(chatroom)}\" time=\"{timeStr}\">{Esc(pkt.Chat.Message)}</remarks>");
                    }
                    else
                    {
                        sb.AppendLine($"    <remarks>{Esc(pkt.Chat.Message)}</remarks>");
                    }
                    // TAKTALK-flavored sidecars. proto3-optional HasField()
                    // distinguishes "absent" from "explicit empty"; the empty
                    // <voice_profile_id/> marker re-emits faithfully.
                    var hasVoiceProfile = pkt.Chat.HasVoiceProfileId;
                    var isTaktalk = hasVoiceProfile
                        || pkt.Chat.HasLang
                        || pkt.Chat.HasRoomId;
                    if (isTaktalk)
                    {
                        if (hasVoiceProfile)
                        {
                            if (string.IsNullOrEmpty(pkt.Chat.VoiceProfileId))
                                sb.AppendLine("    <voice_profile_id/>");
                            else
                                sb.AppendLine($"    <voice_profile_id>{Esc(pkt.Chat.VoiceProfileId)}</voice_profile_id>");
                        }
                        if (!string.IsNullOrEmpty(pkt.Callsign))
                            sb.AppendLine($"    <callsign>{Esc(pkt.Callsign)}</callsign>");
                        if (pkt.Chat.HasLang && !string.IsNullOrEmpty(pkt.Chat.Lang))
                            sb.AppendLine($"    <Ea>{Esc(pkt.Chat.Lang)}</Ea>");
                        if (pkt.Chat.HasRoomId && !string.IsNullOrEmpty(pkt.Chat.RoomId))
                            sb.AppendLine($"    <roomId>{Esc(pkt.Chat.RoomId)}</roomId>");
                    }
                }
                break;
            case TAKPacketV2.PayloadVariantOneofCase.Taktalk:
                // TAKTALK m-t-t voice/text message. Element-body shape matches
                // what ATAK + the TAKTALK plugin emit so the receiver's plugin
                // parses it natively.
                if (!string.IsNullOrEmpty(pkt.Callsign))
                    sb.AppendLine($"    <callsign>{Esc(pkt.Callsign)}</callsign>");
                if (!string.IsNullOrEmpty(pkt.Taktalk.Lang))
                    sb.AppendLine($"    <lang>{Esc(pkt.Taktalk.Lang)}</lang>");
                sb.AppendLine($"    <text>{Esc(pkt.Taktalk.Text)}</text>");
                if (!string.IsNullOrEmpty(pkt.Taktalk.ChatroomId))
                    sb.AppendLine($"    <chatroom-id>{Esc(pkt.Taktalk.ChatroomId)}</chatroom-id>");
                if (pkt.Taktalk.FromVoice)
                    sb.AppendLine("    <voice/>");
                break;
            case TAKPacketV2.PayloadVariantOneofCase.TaktalkRoom:
                // TAKTALK y- room/membership broadcast.
                // v0.3.2: reconstitute <sender-callsign> from envelope
                // pkt.Callsign rather than the deprecated payload field
                // (always equals pkt.Callsign in valid packets). Parser
                // routes <sender-callsign> into pkt.Callsign on the way in;
                // here we route it back out.
                if (!string.IsNullOrEmpty(pkt.Callsign))
                    sb.AppendLine($"    <sender-callsign>{Esc(pkt.Callsign)}</sender-callsign>");
                if (!string.IsNullOrEmpty(pkt.TaktalkRoom.RoomId))
                    sb.AppendLine($"    <chatroom-id>{Esc(pkt.TaktalkRoom.RoomId)}</chatroom-id>");
                if (!string.IsNullOrEmpty(pkt.TaktalkRoom.RoomName))
                    sb.AppendLine($"    <chatroom-name>{Esc(pkt.TaktalkRoom.RoomName)}</chatroom-name>");
                if (pkt.TaktalkRoom.Participants.Count > 0)
                {
                    var joined = string.Join(",", pkt.TaktalkRoom.Participants);
                    sb.AppendLine($"    <chatroom-participants>{Esc(joined)}</chatroom-participants>");
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
                // Squawk and aircraft metadata as remarks text
                if (pkt.Aircraft.Squawk > 0)
                {
                    var parts = new List<string>();
                    if (!string.IsNullOrEmpty(pkt.Aircraft.Icao)) parts.Add($"ICAO: {pkt.Aircraft.Icao}");
                    if (!string.IsNullOrEmpty(pkt.Aircraft.Registration)) parts.Add($"REG: {pkt.Aircraft.Registration}");
                    if (!string.IsNullOrEmpty(pkt.Aircraft.AircraftType)) parts.Add($"Type: {pkt.Aircraft.AircraftType}");
                    parts.Add($"Squawk: {pkt.Aircraft.Squawk}");
                    if (!string.IsNullOrEmpty(pkt.Aircraft.Flight)) parts.Add($"Flight: {pkt.Aircraft.Flight}");
                    sb.AppendLine($"    <remarks>{Esc(string.Join(" ", parts))}</remarks>");
                }
                // ADS-B receiver metadata
                if (pkt.Aircraft.RssiX10 != 0)
                {
                    var rssi = pkt.Aircraft.RssiX10 / 10.0;
                    var radioTag = $"    <_radio rssi=\"{F(rssi)}\"";
                    if (pkt.Aircraft.Gps) radioTag += " gps=\"true\"";
                    sb.AppendLine(radioTag + "/>");
                }
                break;
            }
            case TAKPacketV2.PayloadVariantOneofCase.Shape:
                EmitShape(sb, pkt.Shape, pkt.LatitudeI, pkt.LongitudeI, pkt.Uid);
                break;
            case TAKPacketV2.PayloadVariantOneofCase.Marker:
                EmitMarker(sb, pkt.Marker);
                break;
            case TAKPacketV2.PayloadVariantOneofCase.Rab:
                EmitRab(sb, pkt.Rab, pkt.LatitudeI, pkt.LongitudeI);
                break;
            case TAKPacketV2.PayloadVariantOneofCase.Route:
                EmitRoute(sb, pkt.Route, pkt.LatitudeI, pkt.LongitudeI, pkt.Uid, pkt.Remarks, pkt.Callsign);
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
                // Raw-detail fallback path: emit the raw bytes verbatim as
                // the inner content of <detail>.  No re-escaping — bytes
                // pass through so the receiver round trip stays byte-exact
                // with the source XML.  A fragment that fails
                // IsRawDetailWellFormed can't be inner content of a single
                // <detail>, so it is skipped rather than emitted; every other
                // part of the event is still built as usual.
                if (pkt.RawDetail.Length > 0)
                {
                    var text = pkt.RawDetail.ToStringUtf8();
                    if (text.Length > 0 && IsRawDetailWellFormed(text))
                    {
                        sb.Append(text);
                        if (!text.EndsWith('\n')) sb.AppendLine();
                    }
                }
                break;
        }

        // Emit <remarks> for non-Chat/non-Aircraft/non-Route types that carried remarks text.
        // Chat uses GeoChat.Message; Aircraft synthesizes from ICAO fields; Route handles
        // remarks in its own block above. TAKTALK variants own their detail emission too —
        // m-t-t and y- don't carry <remarks>.
        if (!string.IsNullOrEmpty(pkt.Remarks)
            && pkt.PayloadVariantCase != TAKPacketV2.PayloadVariantOneofCase.Chat
            && pkt.PayloadVariantCase != TAKPacketV2.PayloadVariantOneofCase.Aircraft
            && pkt.PayloadVariantCase != TAKPacketV2.PayloadVariantOneofCase.Route
            && pkt.PayloadVariantCase != TAKPacketV2.PayloadVariantOneofCase.Taktalk
            && pkt.PayloadVariantCase != TAKPacketV2.PayloadVariantOneofCase.TaktalkRoom)
        {
            sb.AppendLine($"    <remarks>{Esc(pkt.Remarks)}</remarks>");
        }

        // Directed-routing recipient list (<marti><dest callsign='X'/>…</marti>).
        // Emit last among <detail> children to match the element ordering ATAK
        // produces in real captures. TAKTALK gates voice TTS playback on this
        // list matching the receiver's callsign, so dropping it silently
        // breaks voice messaging end-to-end.
        if (pkt.Marti != null && pkt.Marti.DestCallsign.Count > 0)
        {
            sb.Append("    <marti>");
            foreach (var dest in pkt.Marti.DestCallsign)
                sb.Append($"<dest callsign=\"{Esc(dest)}\"/>");
            sb.AppendLine("</marti>");
        }

        sb.AppendLine("  </detail>");
        sb.Append("</event>");
        return sb.ToString();
    }

    // --- Typed geometry emitters ----------------------------------------

    private void EmitShape(StringBuilder sb, DrawnShape shape, int eventLatI, int eventLonI, string uid = "")
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
                var strokeW = shape.StrokeWeightX10 / 10.0;
                sb.AppendLine("    <shape>");
                sb.AppendLine($"      <ellipse major=\"{F(majorM)}\" minor=\"{F(minorM)}\" angle=\"{shape.AngleDeg}\"/>");
                // KML style link — iTAK requires this to render circles/ellipses
                sb.Append($"      <link uid=\"{Esc(uid)}.Style\" type=\"b-x-KmlStyle\" relation=\"p-c\">");
                sb.Append($"<Style><LineStyle><color>{ArgbToAbgrHex(strokeVal)}</color><width>{F(strokeW)}</width></LineStyle>");
                if (fillVal != 0) sb.Append($"<PolyStyle><color>{ArgbToAbgrHex(fillVal)}</color></PolyStyle>");
                sb.AppendLine("</Style></link>");
                sb.AppendLine("    </shape>");
            }
        }
        else
        {
            // Vertices are two PACKED parallel delta columns (see atak.proto);
            // zip them back into pairs (same length by construction).
            var n = System.Math.Min(shape.VertexLatDeltas.Count, shape.VertexLonDeltas.Count);
            for (var i = 0; i < n; i++)
            {
                var vlat = (eventLatI + shape.VertexLatDeltas[i]) / 1e7;
                var vlon = (eventLonI + shape.VertexLonDeltas[i]) / 1e7;
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

    private void EmitRoute(StringBuilder sb, Route route, int eventLatI, int eventLonI, string eventUid = "", string remarks = "", string callsign = "")
    {
        // Emit <link> elements BEFORE <link_attr> (ATAK expects waypoints first)
        for (var idx = 0; idx < route.Links.Count; idx++)
        {
            var link = route.Links[idx];
            var llat = (eventLatI + (link.Point?.LatDeltaI ?? 0)) / 1e7;
            var llon = (eventLonI + (link.Point?.LonDeltaI ?? 0)) / 1e7;
            var linkType = link.LinkType == 1 ? "b-m-p-c" : "b-m-p-w";
            // Generate deterministic uid when not present
            var uid = string.IsNullOrEmpty(link.Uid) ? $"{eventUid}-{idx}" : link.Uid;
            var linkParts = new List<string>();
            linkParts.Add($"uid=\"{Esc(uid)}\"");
            linkParts.Add($"type=\"{linkType}\"");
            if (!string.IsNullOrEmpty(link.Callsign)) linkParts.Add($"callsign=\"{Esc(link.Callsign)}\"");
            // ATAK expects 3-component point: lat,lon,hae
            linkParts.Add($"point=\"{F(llat)},{F(llon)},0\" relation=\"c\"");
            sb.AppendLine($"    <link {string.Join(" ", linkParts)}/>");
        }
        var parts = new List<string>();
        if (RouteMethodNames.TryGetValue(route.Method, out var method))
            parts.Add($"method=\"{method}\"");
        if (RouteDirectionNames.TryGetValue(route.Direction, out var dir))
            parts.Add($"direction=\"{dir}\"");
        if (!string.IsNullOrEmpty(route.Prefix))
            parts.Add($"prefix=\"{Esc(route.Prefix)}\"");
        if (route.StrokeWeightX10 > 0)
            parts.Add($"stroke=\"{F(route.StrokeWeightX10 / 10.0)}\"");
        sb.AppendLine(parts.Count > 0
            ? $"    <link_attr {string.Join(" ", parts)}/>"
            : "    <link_attr/>");
        // Conditional remarks element (route block handles its own remarks)
        if (!string.IsNullOrEmpty(remarks))
            sb.AppendLine($"    <remarks>{Esc(remarks)}</remarks>");
        else
            sb.AppendLine("    <remarks/>");
        // routeinfo with navcues child (after link_attr)
        sb.AppendLine("    <__routeinfo><__navcues/></__routeinfo>");
        sb.AppendLine("    <strokeColor value=\"-1\"/>");
        var strokeW = route.StrokeWeightX10 > 0 ? F(route.StrokeWeightX10 / 10.0) : "3";
        sb.AppendLine($"    <strokeWeight value=\"{strokeW}\"/>");
        sb.AppendLine("    <strokeStyle value=\"solid\"/>");
        if (!string.IsNullOrEmpty(callsign))
            sb.AppendLine($"    <contact callsign=\"{Esc(callsign)}\"/>");
        sb.AppendLine("    <labels_on value=\"false\"/>");
        sb.AppendLine("    <color value=\"-1\"/>");
    }

    private void EmitCasevac(StringBuilder sb, CasevacReport c)
    {
        var parts = new List<string>();

        // Metadata (emit first to match ATAK's attribute ordering)
        if (!string.IsNullOrEmpty(c.Title)) parts.Add($"title=\"{Esc(c.Title)}\"");
        if (!string.IsNullOrEmpty(c.MedlineRemarks))
            parts.Add($"medline_remarks=\"{Esc(c.MedlineRemarks)}\"");
        if (!string.IsNullOrEmpty(c.Frequency))
            parts.Add($"freq=\"{Esc(c.Frequency)}\"");

        if (PrecedenceNames.TryGetValue(c.Precedence, out var precName))
            parts.Add($"precedence=\"{precName}\"");

        // Precedence patient counts (newer ATAK format)
        if (c.UrgentCount > 0) parts.Add($"urgent=\"{c.UrgentCount}\"");
        if (c.UrgentSurgicalCount > 0) parts.Add($"urgent_surgical=\"{c.UrgentSurgicalCount}\"");
        if (c.PriorityCount > 0) parts.Add($"priority=\"{c.PriorityCount}\"");
        if (c.RoutineCount > 0) parts.Add($"routine=\"{c.RoutineCount}\"");
        if (c.ConvenienceCount > 0) parts.Add($"convenience=\"{c.ConvenienceCount}\"");

        // Equipment bitfield flags
        if ((c.EquipmentFlags & 0x01) != 0) parts.Add("none=\"true\"");
        if ((c.EquipmentFlags & 0x02) != 0) parts.Add("hoist=\"true\"");
        if ((c.EquipmentFlags & 0x04) != 0) parts.Add("extraction_equipment=\"true\"");
        if ((c.EquipmentFlags & 0x08) != 0) parts.Add("ventilator=\"true\"");
        if ((c.EquipmentFlags & 0x10) != 0) parts.Add("blood=\"true\"");
        if ((c.EquipmentFlags & 0x20) != 0) parts.Add("equipment_other=\"true\"");
        if (!string.IsNullOrEmpty(c.EquipmentDetail))
            parts.Add($"equipment_detail=\"{Esc(c.EquipmentDetail)}\"");

        if (c.LitterPatients > 0) parts.Add($"litter=\"{c.LitterPatients}\"");
        if (c.AmbulatoryPatients > 0) parts.Add($"ambulatory=\"{c.AmbulatoryPatients}\"");

        if (SecurityNames.TryGetValue(c.Security, out var secName))
            parts.Add($"security=\"{secName}\"");
        if (HlzMarkingNames.TryGetValue(c.HlzMarking, out var hlzName))
            parts.Add($"hlz_marking=\"{hlzName}\"");

        if (c.UsMilitary > 0) parts.Add($"us_military=\"{c.UsMilitary}\"");
        if (c.UsCivilian > 0) parts.Add($"us_civilian=\"{c.UsCivilian}\"");
        if (c.NonUsMilitary > 0) parts.Add($"non_us_military=\"{c.NonUsMilitary}\"");
        if (c.NonUsCivilian > 0) parts.Add($"non_us_civilian=\"{c.NonUsCivilian}\"");
        if (c.Epw > 0) parts.Add($"epw=\"{c.Epw}\"");
        if (c.Child > 0) parts.Add($"child=\"{c.Child}\"");

        // Terrain bitfield flags + slope direction detail
        if ((c.TerrainFlags & 0x01) != 0) parts.Add("terrain_slope=\"true\"");
        if (!string.IsNullOrEmpty(c.TerrainSlopeDir))
            parts.Add($"terrain_slope_dir=\"{Esc(c.TerrainSlopeDir)}\"");
        if ((c.TerrainFlags & 0x02) != 0) parts.Add("terrain_rough=\"true\"");
        if ((c.TerrainFlags & 0x04) != 0) parts.Add("terrain_loose=\"true\"");
        if ((c.TerrainFlags & 0x08) != 0) parts.Add("terrain_trees=\"true\"");
        if ((c.TerrainFlags & 0x10) != 0) parts.Add("terrain_wires=\"true\"");
        if ((c.TerrainFlags & 0x20) != 0) parts.Add("terrain_other=\"true\"");
        if (!string.IsNullOrEmpty(c.TerrainOtherDetail))
            parts.Add($"terrain_other_detail=\"{Esc(c.TerrainOtherDetail)}\"");

        // Location / marking
        if (!string.IsNullOrEmpty(c.ZoneProtectedCoord))
            parts.Add($"zone_protected_coord=\"{Esc(c.ZoneProtectedCoord)}\"");
        if (!string.IsNullOrEmpty(c.ZoneMarker))
            parts.Add($"zone_prot_marker=\"{Esc(c.ZoneMarker)}\"");
        if (!string.IsNullOrEmpty(c.MarkedBy))
            parts.Add($"marked_by=\"{Esc(c.MarkedBy)}\"");

        // Tier-2 situational awareness free-text
        if (!string.IsNullOrEmpty(c.Obstacles))
            parts.Add($"obstacles=\"{Esc(c.Obstacles)}\"");
        if (!string.IsNullOrEmpty(c.WindsAreFrom))
            parts.Add($"winds_are_from=\"{Esc(c.WindsAreFrom)}\"");
        if (!string.IsNullOrEmpty(c.Friendlies))
            parts.Add($"friendlies=\"{Esc(c.Friendlies)}\"");
        if (!string.IsNullOrEmpty(c.Enemy))
            parts.Add($"enemy=\"{Esc(c.Enemy)}\"");
        if (!string.IsNullOrEmpty(c.HlzRemarks))
            parts.Add($"hlz_remarks=\"{Esc(c.HlzRemarks)}\"");

        if (c.Zmist.Count == 0)
        {
            sb.AppendLine(parts.Count > 0
                ? $"    <_medevac_ {string.Join(" ", parts)}/>"
                : "    <_medevac_/>");
        }
        else
        {
            sb.AppendLine(parts.Count > 0
                ? $"    <_medevac_ {string.Join(" ", parts)}>"
                : "    <_medevac_>");
            sb.AppendLine("      <zMistsMap>");
            foreach (var z in c.Zmist)
            {
                var zParts = new List<string>();
                if (!string.IsNullOrEmpty(z.Title)) zParts.Add($"title=\"{Esc(z.Title)}\"");
                if (!string.IsNullOrEmpty(z.Z)) zParts.Add($"z=\"{Esc(z.Z)}\"");
                if (!string.IsNullOrEmpty(z.M)) zParts.Add($"m=\"{Esc(z.M)}\"");
                if (!string.IsNullOrEmpty(z.I)) zParts.Add($"i=\"{Esc(z.I)}\"");
                if (!string.IsNullOrEmpty(z.S)) zParts.Add($"s=\"{Esc(z.S)}\"");
                if (!string.IsNullOrEmpty(z.T)) zParts.Add($"t=\"{Esc(z.T)}\"");
                sb.AppendLine(zParts.Count > 0
                    ? $"        <zMist {string.Join(" ", zParts)}/>"
                    : "        <zMist/>");
            }
            sb.AppendLine("      </zMistsMap>");
            sb.AppendLine("    </_medevac_>");
        }
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
