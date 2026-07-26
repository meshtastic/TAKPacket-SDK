using Google.Protobuf;
using Meshtastic.TAK;
using Meshtastic.Protobufs;

namespace Meshtastic.TAK.Tests;

/// <summary>
/// Locks the well-formedness invariants of the rebuilt CoT document: the
/// builder always produces exactly one <c>&lt;event&gt;</c> wrapper no matter
/// what a raw-detail fragment carries, and a <c>&lt;contact&gt;</c> endpoint is
/// the "reply via this server" constant on both the parse and the rebuild side.
/// </summary>
public class RebuildHygieneTests
{
    private readonly CotXmlParser _parser = new();
    private readonly CotXmlBuilder _builder = new();

    private static int Count(string haystack, string needle)
    {
        var n = 0;
        for (var i = haystack.IndexOf(needle, StringComparison.Ordinal); i >= 0;
             i = haystack.IndexOf(needle, i + needle.Length, StringComparison.Ordinal))
            n++;
        return n;
    }

    private static int CountIgnoreCase(string haystack, string needle)
    {
        var n = 0;
        for (var i = haystack.IndexOf(needle, StringComparison.OrdinalIgnoreCase); i >= 0;
             i = haystack.IndexOf(needle, i + needle.Length, StringComparison.OrdinalIgnoreCase))
            n++;
        return n;
    }

    private static TAKPacketV2 RawDetailPacket(string fragment) => new()
    {
        CotTypeId = CotType.AFGUC,
        How = CotHow.MG,
        Uid = "ALPHA-1-UID",
        Callsign = "ALPHA-1",
        LatitudeI = 340522000,
        LongitudeI = -1182437000,
        RawDetail = ByteString.CopyFromUtf8(fragment),
    };

    [Fact]
    public void RawDetail_FragmentCarryingEventTokens_IsNotEmitted()
    {
        // A fragment that closes the wrapper and opens a second event can't be
        // the inner content of one <detail> — emitting it would leave the
        // receiver with a document that isn't a single event.
        var pkt = RawDetailPacket("<a b=\"1\"/></detail></event><event uid=\"SECOND\">");
        var xml = _builder.Build(pkt);
        Assert.DoesNotContain("</detail></event><event ", xml);
        Assert.Equal(1, Count(xml, "<event"));
        Assert.Equal(1, Count(xml, "</event>"));
    }

    [Fact]
    public void RawDetail_FragmentCarryingMixedCaseEventTokens_IsNotEmitted()
    {
        // XML tag names are case-sensitive, but a receiver's parser matches
        // "</DETAIL>" to a "<detail>" it never saw just as readily as
        // "</detail>" would — so a fragment that closes the wrapper in mixed
        // or upper case unbalances the document exactly like the lowercase
        // form and must be dropped exactly like it. Counted case-insensitively
        // so the mixed-case tags in the fragment are actually seen: an
        // ordinal count would score "<Event " as zero and pass regardless.
        var pkt = RawDetailPacket(
            "<remarks>ALPHA-1 checking in</remarks></DETAIL></Event><Event version=\"2.0\" uid=\"SECOND\">");
        var xml = _builder.Build(pkt);
        Assert.DoesNotContain("</DETAIL></Event>", xml);
        Assert.DoesNotContain("uid=\"SECOND\"", xml);
        Assert.Equal(1, CountIgnoreCase(xml, "<event"));
        Assert.Equal(1, CountIgnoreCase(xml, "</event>"));
    }

    [Fact]
    public void RawDetail_BenignFragment_SurvivesVerbatim()
    {
        var pkt = RawDetailPacket("    <foo bar=\"1\"/>");
        var xml = _builder.Build(pkt);
        Assert.Contains("<foo bar=\"1\"/>", xml);
        Assert.Equal(1, Count(xml, "<event"));
        Assert.Equal(1, Count(xml, "</event>"));
    }

    [Fact]
    public void Build_ContactEndpoint_IsAlwaysTheServerStreamConstant()
    {
        // Packets from older senders may still carry a concrete endpoint; the
        // builder must ignore it in favor of the "reply via this server" form.
        var pkt = new TAKPacketV2
        {
            CotTypeId = CotType.AFGUC,
            How = CotHow.MG,
            Uid = "ALPHA-1-UID",
            Callsign = "ALPHA-1",
            LatitudeI = 340522000,
            LongitudeI = -1182437000,
            Endpoint = "192.0.2.1:4242:tcp",
        };
        var xml = _builder.Build(pkt);
        Assert.Contains("endpoint=\"*:-1:stcp\"", xml);
        Assert.DoesNotContain("192.0.2.1", xml);
    }

    [Fact]
    public void Parse_ContactEndpoint_IsNeverStored()
    {
        var xml = @"<?xml version=""1.0"" encoding=""UTF-8""?>
<event version=""2.0"" uid=""ALPHA-1-UID"" type=""a-f-G-U-C"" how=""m-g""
    time=""2026-05-27T01:36:25.023Z"" start=""2026-05-27T01:36:25.023Z""
    stale=""2026-05-27T01:42:26.251Z"">
  <point lat=""34.0522"" lon=""-118.2437"" hae=""100.0"" ce=""9999999.0"" le=""9999999.0""/>
  <detail>
    <contact callsign=""ALPHA-1"" endpoint=""192.0.2.1:4242:tcp""/>
    <__group role=""Team Member"" name=""Cyan""/>
  </detail>
</event>";
        var pkt = _parser.Parse(xml);
        Assert.Equal("ALPHA-1", pkt.Callsign);
        Assert.Equal("", pkt.Endpoint);
    }
}
