using Meshtastic.TAK;

namespace Meshtastic.TAK.Tests;

public class TakMalformedTests
{
    private static readonly string MalformedDir = Path.GetFullPath(
        Path.Combine(AppDomain.CurrentDomain.BaseDirectory, "..", "..", "..", "..", "..", "..", "testdata", "malformed"));

    private readonly TakCompressor _compressor = new();

    private byte[] Load(string name) => File.ReadAllBytes(Path.Combine(MalformedDir, name));

    [Fact]
    public void RejectsEmptyPayload()
    {
        Assert.ThrowsAny<Exception>(() => _compressor.Decompress(Array.Empty<byte>()));
    }

    [Fact]
    public void RejectsSingleByte()
    {
        Assert.ThrowsAny<Exception>(() => _compressor.Decompress(new byte[] { 0x00 }));
    }

    [Fact]
    public void RejectsInvalidDictId()
    {
        Assert.ThrowsAny<Exception>(() => _compressor.Decompress(Load("invalid_dict_id.bin")));
    }

    [Fact]
    public void RejectsTruncatedZstd()
    {
        Assert.ThrowsAny<Exception>(() => _compressor.Decompress(Load("truncated_zstd.bin")));
    }

    [Fact]
    public void RejectsCorruptedZstd()
    {
        Assert.ThrowsAny<Exception>(() => _compressor.Decompress(Load("corrupted_zstd.bin")));
    }

    [Fact]
    public void HandlesInvalidProtobufWithoutCrash()
    {
        // 0xFF + garbage bytes — no crash is the key assertion
        try { _compressor.Decompress(Load("invalid_protobuf.bin")); }
        catch { /* Expected */ }
    }

    [Fact]
    public void IgnoresReservedBitsInFlagsByte()
    {
        // 0xC0 has reserved bits set but dict ID = 0 (0xC0 & 0x3F = 0)
        var pkt = _compressor.Decompress(Load("reserved_bits_set.bin"));
        Assert.NotEmpty(pkt.Uid);
    }

    // Security attack tests

    [Fact]
    public void RejectsXmlWithDoctype()
    {
        var xml = File.ReadAllText(Path.Combine(MalformedDir, "xml_doctype.xml"));
        var parser = new CotXmlParser();
        Assert.ThrowsAny<Exception>(() => parser.Parse(xml));
    }

    [Fact]
    public void RejectsXmlWithEntityExpansion()
    {
        var xml = File.ReadAllText(Path.Combine(MalformedDir, "xml_entity_expansion.xml"));
        var parser = new CotXmlParser();
        Assert.ThrowsAny<Exception>(() => parser.Parse(xml));
    }

    [Fact]
    public void RejectsOversizedFields()
    {
        Assert.ThrowsAny<Exception>(() => _compressor.Decompress(Load("oversized_callsign.bin")));
    }

    [Fact]
    public void RejectsDecompressionBomb()
    {
        Assert.ThrowsAny<Exception>(() => _compressor.Decompress(Load("decompression_bomb.bin")));
    }

    // === TAKTALK edge-case tests ===
    //
    // These confirm that anomalous-but-legal TAKTALK shapes — empty bodies,
    // missing children, surprise sibling elements — parse without crashing
    // and don't corrupt the surrounding envelope.

    [Fact]
    public void MTTWithEmptyTextParsesWithoutCrashAndRoundTrips()
    {
        var xml = @"<?xml version=""1.0"" encoding=""UTF-8""?>
<event version=""2.0"" uid=""TAKTALK-MESSAGE-empty"" type=""m-t-t"" how=""null""
    time=""2026-05-27T01:36:25.023Z"" start=""2026-05-27T01:36:25.023Z""
    stale=""2026-05-27T01:42:26.251Z"">
  <point lat=""0.0"" lon=""0.0"" hae=""9999999.0"" ce=""9999999.0"" le=""9999999.0""/>
  <detail>
    <callsign>ASPEN</callsign>
    <lang>English</lang>
    <text></text>
    <chatroom-id>1</chatroom-id>
  </detail>
</event>";
        var parser = new CotXmlParser();
        var pkt = parser.Parse(xml);
        Assert.Equal(Meshtastic.Protobufs.CotType.MTT, pkt.CotTypeId);
        Assert.Equal(Meshtastic.Protobufs.TAKPacketV2.PayloadVariantOneofCase.Taktalk, pkt.PayloadVariantCase);
        Assert.Equal("", pkt.Taktalk.Text);
        Assert.Equal("1", pkt.Taktalk.ChatroomId);
        Assert.Equal("English", pkt.Taktalk.Lang);
        Assert.False(pkt.Taktalk.FromVoice);
        // Full wire round-trip — compress + decompress shouldn't throw
        var wire = _compressor.Compress(pkt);
        var decompressed = _compressor.Decompress(wire);
        Assert.Equal(Meshtastic.Protobufs.CotType.MTT, decompressed.CotTypeId);
    }

    [Fact]
    public void YDashWithNoParticipantsParsesWithEmptyParticipantsList()
    {
        var xml = @"<?xml version=""1.0"" encoding=""UTF-8""?>
<event version=""2.0"" uid=""ROOM-DATA-no-roster"" type=""y-"" how=""null""
    time=""2026-05-27T02:09:08.426Z"" start=""2026-05-27T02:09:08.426Z""
    stale=""2026-05-27T02:15:09.699Z"">
  <point lat=""0.0"" lon=""0.0"" hae=""9999999.0"" ce=""9999999.0"" le=""9999999.0""/>
  <detail>
    <sender-callsign>ASPEN</sender-callsign>
    <chatroom-id>30b2755c-c547-44ef-a0cc-cdbd8a15616f</chatroom-id>
    <chatroom-name>test-empty-room</chatroom-name>
  </detail>
</event>";
        var parser = new CotXmlParser();
        var pkt = parser.Parse(xml);
        Assert.Equal(Meshtastic.Protobufs.CotType.Y, pkt.CotTypeId);
        Assert.Equal(Meshtastic.Protobufs.TAKPacketV2.PayloadVariantOneofCase.TaktalkRoom, pkt.PayloadVariantCase);
        // v0.3.2: <sender-callsign> routes into envelope pkt.Callsign,
        // not the deprecated TaktalkRoom.SenderCallsign field.
        Assert.Equal("ASPEN", pkt.Callsign);
        Assert.Equal("30b2755c-c547-44ef-a0cc-cdbd8a15616f", pkt.TaktalkRoom.RoomId);
        Assert.Equal("test-empty-room", pkt.TaktalkRoom.RoomName);
        Assert.Empty(pkt.TaktalkRoom.Participants);
        // Round-trip wire to confirm no crash on serialize/deserialize
        var wire = _compressor.Compress(pkt);
        var decompressed = _compressor.Decompress(wire);
        Assert.Equal(Meshtastic.Protobufs.CotType.Y, decompressed.CotTypeId);
        Assert.Empty(decompressed.TaktalkRoom.Participants);
    }

    [Fact]
    public void BTFWithEmptyEaElementDoesNotCorruptChatParsing()
    {
        // <Ea></Ea> with no body — chat message in <remarks> must NOT be
        // clobbered by the empty Ea text.
        var xml = @"<?xml version=""1.0"" encoding=""UTF-8""?>
<event version=""2.0"" uid=""GeoChat.ANDROID-aaa.ANDROID-bbb.malformed-ea"" type=""b-t-f"" how=""h-g-i-g-o""
    time=""2026-05-27T02:09:20.718Z"" start=""2026-05-27T02:09:20.718Z""
    stale=""2026-05-28T02:09:20.718Z"">
  <point lat=""38.8895"" lon=""-77.0353"" hae=""73.863"" ce=""6.9"" le=""9999999.0""/>
  <detail>
    <__chat parent=""RootContactGroup"" messageId=""malformed-ea"" chatroom=""ETHEL""
        id=""ANDROID-bbb"" senderCallsign=""ASPEN"">
      <chatgrp uid0=""ANDROID-aaa"" uid1=""ANDROID-bbb"" id=""ANDROID-bbb""/>
    </__chat>
    <link uid=""ANDROID-aaa"" type=""a-f-G-U-C"" relation=""p-p""/>
    <remarks source=""BAO.F.ATAK.ANDROID-aaa"" to=""ANDROID-bbb""
        time=""2026-05-27T02:09:20.718Z"">Real message body</remarks>
    <Ea></Ea>
    <roomId>30b2755c-c547-44ef-a0cc-cdbd8a15616f</roomId>
  </detail>
</event>";
        var parser = new CotXmlParser();
        var pkt = parser.Parse(xml);
        Assert.Equal(Meshtastic.Protobufs.TAKPacketV2.PayloadVariantOneofCase.Chat, pkt.PayloadVariantCase);
        // The critical assertion — chat message survives intact despite the
        // empty <Ea/> sibling sitting next to <remarks>.
        Assert.Equal("Real message body", pkt.Chat.Message);
        Assert.Equal("", pkt.Chat.Lang);
        Assert.Equal("30b2755c-c547-44ef-a0cc-cdbd8a15616f", pkt.Chat.RoomId);
        // Round-trip should preserve message body
        var wire = _compressor.Compress(pkt);
        var decompressed = _compressor.Decompress(wire);
        Assert.Equal("Real message body", decompressed.Chat.Message);
    }
}
