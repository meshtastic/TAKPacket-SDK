package org.meshtastic.tak;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.meshtastic.tak.TakPacketV2Data.Payload.DrawnShape;
import org.meshtastic.tak.TakPacketV2Data.Payload.TakTalk;
import org.meshtastic.tak.TakPacketV2Data.Payload.TakTalkRoom;

/**
 * The SDK as a pure-Java consumer sees it: builders instead of the long
 * Kotlin constructors, and the mesh send/receive calls without Kotlin default
 * arguments. Written in Java so the build fails if that surface stops
 * compiling from Java.
 */
class JavaApiTest {
    private static final long AIRTIME_MS = 2_200L;

    private final TakCompressor compressor = new TakCompressor();

    private static TakPacketV2Data.Builder envelope(String type) {
        return new TakPacketV2Data.Builder()
                .cotType(type)
                .how("h-g-i-g-o")
                .uid("ANDROID-0000000000000001")
                .callsign("ALPHA-1")
                .latitudeDegrees(38.8895)
                .longitudeDegrees(-77.0353)
                .staleSeconds(120)
                .startEpochSeconds(1_763_251_200L);
    }

    private static String noisyText(int length) {
        Random rnd = new Random(length);
        String alphabet = "abcdefghijklmnopqrstuvwxyz0123456789 ";
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) sb.append(alphabet.charAt(rnd.nextInt(alphabet.length())));
        return sb.toString();
    }

    private static TakPacketV2Data receive(List<byte[]> frames) {
        TakMeshReceiver receiver = new TakMeshReceiver(new TakReassembler(AIRTIME_MS));
        List<TakPacketV2Data> out = new ArrayList<>();
        for (byte[] frame : frames) {
            TakPacketV2Data packet = receiver.onPayload(frame, 0L);
            if (packet != null) out.add(packet);
        }
        assertEquals(1, out.size());
        return out.get(0);
    }

    @Test
    void takTalkMessageRoundTripsAsOneFrame() {
        TakPacketV2Data packet = envelope("m-t-t")
                .addMarti("BRAVO-2")
                .payload(new TakTalk.Builder()
                        .text("Moving to the north gate")
                        .chatroomId("6f1c2a54-0d3b-4f6e-9a77-2c8e5b1d4f90")
                        .lang("English")
                        .fromVoice(true)
                        .build())
                .build();

        MeshEncodeResult result = compressor.compressForMesh(packet, new TakFragmenter());
        assertEquals(MeshEncodeResult.Outcome.SINGLE_FRAME, result.getOutcome());
        assertEquals(packet, receive(result.getFrames()));
        assertEquals(388_895_000, packet.getLatitudeI());
        assertEquals(-770_353_000, packet.getLongitudeI());
        assertEquals(List.of("BRAVO-2"), packet.getMarti());
    }

    @Test
    void takTalkRoomRoundTrips() {
        TakPacketV2Data packet = envelope("y-")
                .payload(new TakTalkRoom.Builder()
                        .roomId("6f1c2a54-0d3b-4f6e-9a77-2c8e5b1d4f90")
                        .roomName("Gate team")
                        .addParticipant("ALPHA-1")
                        .addParticipant("BRAVO-2")
                        .build())
                .build();

        MeshEncodeResult result = compressor.compressForMesh(packet, new TakFragmenter());
        assertEquals(MeshEncodeResult.Outcome.SINGLE_FRAME, result.getOutcome());
        assertEquals(packet, receive(result.getFrames()));
    }

    @Test
    void longTakTalkMessageIsFragmentedWhenOptedIn() {
        TakPacketV2Data packet = envelope("m-t-t")
                .payload(new TakTalk.Builder().text(noisyText(1_500)).build())
                .build();
        TakFragmenter fragmenter = new TakFragmenter();

        assertEquals(MeshEncodeResult.Outcome.TOO_LARGE, compressor.compressForMesh(packet, fragmenter).getOutcome());

        MeshEncodeResult result = compressor.compressForMesh(packet, fragmenter, true);
        assertEquals(MeshEncodeResult.Outcome.FRAGMENTED, result.getOutcome());
        for (byte[] frame : result.getFrames()) {
            assertTrue(TakFragment.isFragment(frame));
            assertTrue(frame.length <= TakFragment.DEFAULT_MAX_FRAME_BYTES);
        }
        assertEquals(packet, receive(result.getFrames()));
    }

    @Test
    void largeShapeHonoursTheAirtimeBudget() {
        DrawnShape.Builder shape = new DrawnShape.Builder().kind(CotXmlParser.SHAPE_KIND_FREEFORM);
        Random rnd = new Random(7);
        int lat = 388_895_000;
        int lon = -770_353_000;
        for (int i = 0; i < 200; i++) {
            lat += rnd.nextInt(801) - 400;
            lon += rnd.nextInt(801) - 400;
            shape.addVertex(lat, lon);
        }
        TakPacketV2Data packet = envelope("u-d-f").payload(shape.build()).build();
        TakFragmenter fragmenter = new TakFragmenter(TakFragment.DEFAULT_MAX_FRAME_BYTES, 16);

        AirtimeBudget plenty = AirtimeBudget.fromDeviceMetrics(10.0, 1.0, 0, AIRTIME_MS);
        MeshEncodeResult sent = compressor.compressForMesh(packet, fragmenter, true, plenty);
        assertEquals(MeshEncodeResult.Outcome.FRAGMENTED, sent.getOutcome());
        assertEquals(200, ((DrawnShape) receive(sent.getFrames()).getPayload()).getVertices().size());

        AirtimeBudget spent = AirtimeBudget.fromDeviceMetrics(10.0, 10.0, 0, AIRTIME_MS);
        MeshEncodeResult held = compressor.compressForMesh(packet, fragmenter, true, spent);
        assertEquals(MeshEncodeResult.Outcome.INSUFFICIENT_AIRTIME, held.getOutcome());
        assertNull(held.getFrames());
    }

    @Test
    void fragmenterTakesJavaLambdas() {
        TakFragmenter noRepair = new TakFragmenter(TakFragment.DEFAULT_MAX_FRAME_BYTES, 8, k -> 0, () -> 0x12345678);
        byte[] inner = new byte[600];
        new Random(1).nextBytes(inner);
        inner[0] = 0;
        List<byte[]> frames = noRepair.split(inner, 900);
        assertEquals(noRepair.sourceFragmentsFor(inner.length), frames.size());
        assertEquals(0x78, frames.get(0)[1] & 0xFF);
        assertEquals(1, TakFragmenter.defaultRepairCount(4));
    }

    @Test
    void toBuilderEditsACopy() {
        TakPacketV2Data original = envelope("a-f-G-U-C").remarks("first").build();
        TakPacketV2Data edited = original.toBuilder().remarks("second").battery(80).build();
        assertEquals("first", original.getRemarks());
        assertEquals("second", edited.getRemarks());
        assertEquals(80, edited.getBattery());
        assertEquals(original.getUid(), edited.getUid());
    }

    /** Fails when a field is added to a data class without a builder setter of the same name. */
    @Test
    void everyFieldHasABuilderSetter() {
        Class<?>[][] pairs = {
            {TakPacketV2Data.class, TakPacketV2Data.Builder.class},
            {TakPacketV2Data.Payload.Chat.class, TakPacketV2Data.Payload.Chat.Builder.class},
            {TakTalk.class, TakTalk.Builder.class},
            {TakTalkRoom.class, TakTalkRoom.Builder.class},
            {TakPacketV2Data.Payload.Marker.class, TakPacketV2Data.Payload.Marker.Builder.class},
            {DrawnShape.class, DrawnShape.Builder.class},
            {TakPacketV2Data.Payload.Route.class, TakPacketV2Data.Payload.Route.Builder.class},
            {TakPacketV2Data.Payload.RangeAndBearing.class, TakPacketV2Data.Payload.RangeAndBearing.Builder.class},
            {TakPacketV2Data.Payload.EmergencyAlert.class, TakPacketV2Data.Payload.EmergencyAlert.Builder.class},
        };
        for (Class<?>[] pair : pairs) {
            Set<String> setters = new HashSet<>();
            for (Method m : pair[1].getMethods()) {
                if (m.getParameterCount() == 1 && m.getReturnType() == pair[1]) setters.add(m.getName());
            }
            for (Field f : pair[0].getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers())) continue;
                assertTrue(setters.contains(f.getName()), pair[1].getName() + " has no setter for " + f.getName());
            }
        }
    }
}
