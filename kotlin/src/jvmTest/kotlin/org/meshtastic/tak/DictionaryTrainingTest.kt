package org.meshtastic.tak

import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.random.Random

/**
 * Generates a large corpus of valid TAKPacketV2 protobuf samples for
 * zstd dictionary training. This test is disabled by default to prevent
 * accidental CI runs — enable it manually when retraining dictionaries.
 *
 * Run with:
 *   ./gradlew test --tests "org.meshtastic.tak.DictionaryTrainingTest.generate training corpus"
 *
 * Then train dictionaries:
 *   zstd --train training_corpus/ -o dict_non_aircraft.zstd --maxdict=16384
 *   zstd --train training_corpus/aircraft_ -o dict_aircraft.zstd --maxdict=4096
 */
@Disabled("Manual-only: run explicitly when retraining zstd dictionaries")
class DictionaryTrainingTest {

    private val parser = CotXmlParser()
    private val rng = Random(42) // deterministic for reproducibility
    private val outputDir = File("../testdata/training_corpus")

    private val callsigns = listOf(
        "ALPHA-1", "BRAVO-2", "CHARLIE-3", "DELTA-4", "ECHO-5",
        "iPad", "iPadTAKAware", "ETHEL", "TestNode", "Recon-01",
        "SIM-01", "SIM-02", "CR1", "DS1", "LKK4", "rock",
    )
    private val teams = listOf("White", "Yellow", "Orange", "Red", "Cyan", "Green", "Blue", "Purple")
    private val roles = listOf("Team Member", "Team Lead", "HQ", "Forward Observer")
    private val shapeNames = listOf("Rectangle 1", "Circle A", "Shape 324", "AO North", "Killbox Bravo")
    private val routeNames = listOf("Route Alpha", "MSR Tampa", "Route - 04/11 06:48:00", "Exfil Route")
    private val chatMessages = listOf(
        "Roger that", "Moving to rally point", "at breach", "cover by fire",
        "SITREP follows", "area clear", "contact front", "need resupply",
        "en route", "hold position", "abort abort abort", "mission complete",
    )

    private fun randLat() = "%.7f".format(rng.nextDouble(-60.0, 60.0))
    private fun randLon() = "%.7f".format(rng.nextDouble(-170.0, 170.0))
    private fun randAlt() = rng.nextInt(-30, 500).toString()
    private fun randBat() = rng.nextInt(10, 101).toString()
    private fun randSpeed() = "%.1f".format(rng.nextDouble(0.0, 30.0))
    private fun randCourse() = "%.2f".format(rng.nextDouble(0.0, 360.0))
    private fun randCallsign() = callsigns.random(rng)
    private fun randTeam() = teams.random(rng)
    private fun randRole() = roles.random(rng)
    private fun randUid() = "%08x-%04x-%04x-%04x-%012x".format(
        rng.nextInt(), rng.nextInt(0xFFFF), rng.nextInt(0xFFFF),
        rng.nextInt(0xFFFF), rng.nextLong(0xFFFFFFFFFFFFL)
    )
    private fun randArgb(): String {
        val colors = listOf("-1", "-65536", "-16711936", "-16776961", "-48571", "-16777089", "-1778384769", "0")
        return colors.random(rng)
    }

    @Test
    fun `generate training corpus`() {
        outputDir.mkdirs()
        outputDir.listFiles()?.forEach { it.delete() }

        var count = 0

        // === PLI variants (200 samples) ===
        repeat(200) { i ->
            val xml = """<event version="2.0" uid="ANDROID-${"%016x".format(rng.nextLong())}" type="a-f-G-U-C" how="${if (rng.nextBoolean()) "h-e" else "m-g"}" time="2026-03-15T14:22:10Z" start="2026-03-15T14:22:10Z" stale="2026-03-15T14:24:10Z"><point lat="${randLat()}" lon="${randLon()}" hae="${randAlt()}" ce="9999999" le="9999999"/><detail><contact callsign="${randCallsign()}" endpoint="0.0.0.0:4242:tcp"/><__group role="${randRole()}" name="${randTeam()}"/><status battery="${randBat()}"/><track speed="${randSpeed()}" course="${randCourse()}"/><uid Droid="${randCallsign()}"/></detail></event>"""
            writeSample("pli_$i", xml)
            count++
        }

        // === GeoChat variants (100 samples) ===
        repeat(100) { i ->
            val senderUid = "ANDROID-${"%016x".format(rng.nextLong())}"
            val cs = randCallsign()
            val msg = chatMessages.random(rng)
            val xml = """<event version="2.0" uid="GeoChat.$senderUid.All Chat Rooms.${"%08x".format(rng.nextInt())}" type="b-t-f" how="h-g-i-g-o" time="2026-03-15T19:00:00Z" start="2026-03-15T19:00:00Z" stale="2026-03-15T19:02:00Z"><point lat="${randLat()}" lon="${randLon()}" hae="9999999" ce="9999999" le="9999999"/><detail><__chat senderCallsign="$cs" chatRoom="All Chat Rooms" id="All Chat Rooms" parent="RootContactGroup"><chatgrp uid0="$senderUid" uid1="All Chat Rooms"/></__chat><link uid="$senderUid" relation="p-p" type="a-f-G-U-C"/><remarks source="BAO.F.ATAK.$senderUid" time="2026-03-15T19:00:00Z">$msg</remarks><__serverdestination destinations="0.0.0.0:4242:tcp:$senderUid"/></detail></event>"""
            writeSample("geochat_$i", xml)
            count++
        }

        // === Rectangle/polygon shapes (100 samples) ===
        repeat(100) { i ->
            val type = if (rng.nextBoolean()) "u-d-r" else "u-d-f"
            val nVerts = if (type == "u-d-r") 4 else rng.nextInt(3, 8)
            val baseLat = rng.nextDouble(-50.0, 50.0)
            val baseLon = rng.nextDouble(-170.0, 170.0)
            val verts = (0 until nVerts).joinToString("") { v ->
                val vLat = "%.7f".format(baseLat + rng.nextDouble(-0.01, 0.01))
                val vLon = "%.7f".format(baseLon + rng.nextDouble(-0.01, 0.01))
                """<link point="$vLat,$vLon"/>"""
            }
            val xml = """<event version="2.0" uid="${randUid()}" type="$type" time="2026-03-15T14:22:10Z" start="2026-03-15T14:22:10Z" stale="2026-03-16T14:22:10Z" how="h-e"><point lat="${"%.7f".format(baseLat)}" lon="${"%.7f".format(baseLon)}" hae="9999999" ce="9999999" le="9999999"/><detail>$verts<strokeColor value="${randArgb()}"/><strokeWeight value="${rng.nextInt(1, 6)}.0"/><fillColor value="${randArgb()}"/><contact callsign="${shapeNames.random(rng)}"/><labels_on value="false"/></detail></event>"""
            writeSample("shape_$i", xml)
            count++
        }

        // === Circle/ellipse shapes (80 samples) ===
        repeat(80) { i ->
            val type = if (rng.nextBoolean()) "u-d-c-c" else "u-d-c-e"
            val major = "%.2f".format(rng.nextDouble(50.0, 5000.0))
            val minor = if (type == "u-d-c-c") major else "%.2f".format(rng.nextDouble(50.0, 5000.0))
            val xml = """<event version="2.0" uid="${randUid()}" type="$type" time="2026-03-15T14:22:10Z" start="2026-03-15T14:22:10Z" stale="2026-03-16T14:22:10Z" how="h-e"><point lat="${randLat()}" lon="${randLon()}" hae="9999999" ce="9999999" le="9999999"/><detail><shape><ellipse major="$major" minor="$minor" angle="${rng.nextInt(0, 361)}"/></shape><strokeColor value="${randArgb()}"/><strokeWeight value="${rng.nextInt(1, 6)}.0"/><fillColor value="${randArgb()}"/><contact callsign="${shapeNames.random(rng)}"/><labels_on value="false"/></detail></event>"""
            writeSample("circle_$i", xml)
            count++
        }

        // === Route variants (80 samples) ===
        repeat(80) { i ->
            val nWps = rng.nextInt(2, 6)
            val baseLat = rng.nextDouble(-50.0, 50.0)
            val baseLon = rng.nextDouble(-170.0, 170.0)
            val wps = (0 until nWps).joinToString("") { w ->
                val wLat = "%.7f".format(baseLat + rng.nextDouble(-0.05, 0.05))
                val wLon = "%.7f".format(baseLon + rng.nextDouble(-0.05, 0.05))
                val wpType = if (w == 0 || w == nWps - 1) "b-m-p-w" else "b-m-p-c"
                val cs = if (w == 0) "SP" else if (w == nWps - 1) "VDO" else "CP${w}"
                """<link uid="${randUid()}" callsign="$cs" type="$wpType" point="$wLat,$wLon"/>"""
            }
            val method = listOf("Driving", "Walking", "Flying", "Watercraft").random(rng)
            val dir = listOf("Infil", "Exfil").random(rng)
            val xml = """<event version="2.0" uid="${randUid()}" type="b-m-r" time="2026-03-15T14:22:10Z" start="2026-03-15T14:22:10Z" stale="2026-03-16T14:22:10Z" how="h-e"><point lat="${"%.7f".format(baseLat)}" lon="${"%.7f".format(baseLon)}" hae="0" ce="0" le="0"/><detail><__routeinfo/><link_attr method="$method" direction="$dir" prefix="CP" stroke="3"/>$wps<contact callsign="${routeNames.random(rng)}"/></detail></event>"""
            writeSample("route_$i", xml)
            count++
        }

        // === Marker variants (80 samples) ===
        repeat(80) { i ->
            val types = listOf("b-m-p-s-m", "b-m-p-w", "b-m-p-c", "b-m-p-w-GOTO", "b-m-p-s-p-i")
            val xml = """<event version="2.0" uid="${randUid()}" type="${types.random(rng)}" time="2026-03-15T14:22:10Z" start="2026-03-15T14:22:10Z" stale="2026-03-16T14:22:10Z" how="h-g-i-g-o"><point lat="${randLat()}" lon="${randLon()}" hae="9999999" ce="9999999" le="9999999"/><detail><link uid="ANDROID-${"%016x".format(rng.nextLong())}" type="a-f-G-U-C" parent_callsign="${randCallsign()}" relation="p-p"/><contact callsign="${randCallsign()}"/><color argb="${randArgb()}"/><usericon iconsetpath="COT_MAPPING_SPOTMAP/b-m-p-s-m/-65536"/></detail></event>"""
            writeSample("marker_$i", xml)
            count++
        }

        // === Aircraft variants (60 samples) ===
        repeat(60) { i ->
            val icao = "%06X".format(rng.nextInt(0xFFFFFF))
            val reg = "N${rng.nextInt(1000, 99999)}"
            val flight = "${listOf("AAL", "DAL", "UAL", "SWA").random(rng)}${rng.nextInt(100, 9999)}"
            val xml = """<event version="2.0" uid="icao-$icao" type="a-n-A-C-F" time="2026-03-15T14:22:10Z" start="2026-03-15T14:22:10Z" stale="2026-03-15T14:23:10Z" how="m-g"><point lat="${randLat()}" lon="${randLon()}" hae="${rng.nextInt(1000, 45000)}" ce="9999999" le="9999999"/><detail><contact callsign="$flight"/><_aircot_ icao="$icao" reg="$reg" flight="$flight" cat="A3"/><track speed="${rng.nextInt(50, 300)}" course="${randCourse()}"/></detail></event>"""
            writeSample("aircraft_$i", xml)
            count++
        }

        println("Generated $count training samples in ${outputDir.absolutePath}")
        println("Total size: ${outputDir.listFiles()?.sumOf { it.length() } ?: 0} bytes")
    }

    private fun writeSample(name: String, xml: String) {
        val packet = parser.parse(xml)
        val bytes = TakPacketV2Serializer.serialize(packet)
        File(outputDir, "$name.pb").writeBytes(bytes)
    }
}
