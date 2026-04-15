package org.meshtastic.tak

import org.junit.jupiter.api.Test
import java.io.File
import kotlin.random.Random

/**
 * Generates a large corpus of valid TAKPacketV2 protobuf samples for
 * zstd dictionary training. Run with:
 *   ./gradlew test --tests "org.meshtastic.tak.DictionaryTrainingTest.generate training corpus"
 *
 * Then train dictionaries:
 *   zstd --train training_corpus/ -o dict_non_aircraft.zstd --maxdict=16384
 *   zstd --train training_corpus/aircraft_ -o dict_aircraft.zstd --maxdict=4096
 */
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
    private val remarksTexts = listOf(
        "Enemy OP spotted", "Friendly observation post", "Resupply point",
        "Phase line alpha", "Dismount point", "Assembly area", "HLZ primary",
        "Breach point", "Support by fire position", "Casualty collection point",
        "Suspected IED location", "Alternate route if primary blocked",
        "", "", "", "", // empty ~40% of the time
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
            val rmk = remarksTexts.random(rng)
            val remarksEl = if (rmk.isNotEmpty()) """<remarks>$rmk</remarks>""" else """<remarks/>"""
            val xml = """<event version="2.0" uid="${randUid()}" type="$type" time="2026-03-15T14:22:10Z" start="2026-03-15T14:22:10Z" stale="2026-03-16T14:22:10Z" how="h-e"><point lat="${"%.7f".format(baseLat)}" lon="${"%.7f".format(baseLon)}" hae="9999999" ce="9999999" le="9999999"/><detail>$verts<strokeColor value="${randArgb()}"/><strokeWeight value="${rng.nextInt(1, 6)}.0"/><fillColor value="${randArgb()}"/><contact callsign="${shapeNames.random(rng)}"/>$remarksEl<labels_on value="false"/></detail></event>"""
            writeSample("shape_$i", xml)
            count++
        }

        // === Circle/ellipse shapes (80 samples) ===
        repeat(80) { i ->
            val type = if (rng.nextBoolean()) "u-d-c-c" else "u-d-c-e"
            val major = "%.2f".format(rng.nextDouble(50.0, 5000.0))
            val minor = if (type == "u-d-c-c") major else "%.2f".format(rng.nextDouble(50.0, 5000.0))
            val rmk = remarksTexts.random(rng)
            val remarksEl = if (rmk.isNotEmpty()) """<remarks>$rmk</remarks>""" else """<remarks/>"""
            val xml = """<event version="2.0" uid="${randUid()}" type="$type" time="2026-03-15T14:22:10Z" start="2026-03-15T14:22:10Z" stale="2026-03-16T14:22:10Z" how="h-e"><point lat="${randLat()}" lon="${randLon()}" hae="9999999" ce="9999999" le="9999999"/><detail><shape><ellipse major="$major" minor="$minor" angle="${rng.nextInt(0, 361)}"/></shape><strokeColor value="${randArgb()}"/><strokeWeight value="${rng.nextInt(1, 6)}.0"/><fillColor value="${randArgb()}"/><contact callsign="${shapeNames.random(rng)}"/>$remarksEl<labels_on value="false"/></detail></event>"""
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
            val rmk = remarksTexts.random(rng)
            val remarksEl = if (rmk.isNotEmpty()) """<remarks>$rmk</remarks>""" else """<remarks/>"""
            val xml = """<event version="2.0" uid="${randUid()}" type="b-m-r" time="2026-03-15T14:22:10Z" start="2026-03-15T14:22:10Z" stale="2026-03-16T14:22:10Z" how="h-e"><point lat="${"%.7f".format(baseLat)}" lon="${"%.7f".format(baseLon)}" hae="0" ce="0" le="0"/><detail><__routeinfo/><link_attr method="$method" direction="$dir" prefix="CP" stroke="3"/>$wps$remarksEl<contact callsign="${routeNames.random(rng)}"/></detail></event>"""
            writeSample("route_$i", xml)
            count++
        }

        // === Marker variants (80 samples) ===
        repeat(80) { i ->
            val types = listOf("b-m-p-s-m", "b-m-p-w", "b-m-p-c", "b-m-p-w-GOTO", "b-m-p-s-p-i")
            val rmk = remarksTexts.random(rng)
            val remarksEl = if (rmk.isNotEmpty()) """<remarks>$rmk</remarks>""" else ""
            val xml = """<event version="2.0" uid="${randUid()}" type="${types.random(rng)}" time="2026-03-15T14:22:10Z" start="2026-03-15T14:22:10Z" stale="2026-03-16T14:22:10Z" how="h-g-i-g-o"><point lat="${randLat()}" lon="${randLon()}" hae="9999999" ce="9999999" le="9999999"/><detail><link uid="ANDROID-${"%016x".format(rng.nextLong())}" type="a-f-G-U-C" parent_callsign="${randCallsign()}" relation="p-p"/><contact callsign="${randCallsign()}"/><color argb="${randArgb()}"/><usericon iconsetpath="COT_MAPPING_SPOTMAP/b-m-p-s-m/-65536"/>$remarksEl</detail></event>"""
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

        // === CASEVAC samples (20) ===
        // Half the samples exercise the v2.x medline extensions (title, medline_remarks,
        // precedence counts, terrain_slope_dir, one ZMIST entry) so the retrained
        // dictionary learns their common substrings. The other half use the legacy
        // 9-line-only shape so the dictionary keeps compressing older clients well.
        val medlineTitles = listOf("M-1", "M-2", "EAGLE-1", "COBRA-2", "A7-03")
        val medlineRemarks = listOf(
            "2 litter 1 amb", "3 urgent litter", "1 urgent surgical",
            "2 priority amb", "smoke on approach", "LZ hot",
        )
        val slopeDirs = listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
        val zmistMechanisms = listOf("GSW", "Blast", "Frag", "Burn", "Fall", "MVA")
        val zmistInjuries = listOf("Left thigh", "Right arm", "Head", "Abdomen", "L leg")
        val zmistSigns = listOf("Stable", "Priority", "Urgent", "BP 120/80", "GCS 13")
        val zmistTreatments = listOf("TQ applied", "Fluids started", "Airway cleared", "IV in place")
        repeat(20) { i ->
            val prec = listOf("Urgent", "Urgent Surgical", "Priority", "Routine", "Convenience").random(rng)
            val hlz = listOf("Panels", "Pyro", "Smoke", "None").random(rng)
            val sec = listOf("N", "P", "E", "X").random(rng)
            val extended = i % 2 == 0
            val xml = if (extended) {
                // Extended medline with v2.x attributes
                val title = medlineTitles.random(rng)
                val medRem = medlineRemarks.random(rng)
                val urgent = rng.nextInt(0, 3)
                val priority = rng.nextInt(0, 3)
                val routine = rng.nextInt(0, 2)
                val slopeDir = slopeDirs.random(rng)
                val zTitle = "ZMIST-${rng.nextInt(1, 4)}"
                val zMech = zmistMechanisms.random(rng)
                val zInj = zmistInjuries.random(rng)
                val zSign = zmistSigns.random(rng)
                val zTreat = zmistTreatments.random(rng)
                """<event version="2.0" uid="${randUid()}" type="b-r-f-h-c" time="2026-03-15T14:22:10Z" start="2026-03-15T14:22:10Z" stale="2026-03-16T14:22:10Z" how="h-g-i-g-o"><point lat="${randLat()}" lon="${randLon()}" hae="${randAlt()}" ce="9999999" le="9999999"/><detail><contact callsign="MEDEVAC-${rng.nextInt(10)}"/><_medevac_ title="$title" medline_remarks="$medRem" freq="${"%.2f".format(rng.nextDouble(30.0, 400.0))}" precedence="$prec" urgent="$urgent" priority="$priority" routine="$routine" hoist="true" litter="${rng.nextInt(0, 4)}" ambulatory="${rng.nextInt(0, 4)}" security="$sec" hlz_marking="$hlz" us_military="${rng.nextInt(0, 4)}" terrain_slope="true" terrain_slope_dir="$slopeDir"><zMistsMap><zMist title="$zTitle" z="${zMech}-${rng.nextInt(100)}" m="$zMech" i="$zInj" s="$zSign" t="$zTreat"/></zMistsMap></_medevac_></detail></event>"""
            } else {
                // Legacy 9-line shape (no v2.x extensions)
                """<event version="2.0" uid="${randUid()}" type="b-r-f-h-c" time="2026-03-15T14:22:10Z" start="2026-03-15T14:22:10Z" stale="2026-03-16T14:22:10Z" how="h-g-i-g-o"><point lat="${randLat()}" lon="${randLon()}" hae="${randAlt()}" ce="9999999" le="9999999"/><detail><contact callsign="MEDEVAC-${rng.nextInt(10)}"/><_medevac_ precedence="$prec" hlz_marking="$hlz" security="$sec" litter="${rng.nextInt(0, 4)}" ambulatory="${rng.nextInt(0, 4)}" freq="${"%.2f".format(rng.nextDouble(30.0, 400.0))}"/></detail></event>"""
            }
            writeSample("casevac_$i", xml)
            count++
        }

        // === Emergency samples (20) ===
        repeat(20) { i ->
            val types = listOf("b-a-o-tbl", "b-a-o-pan", "b-a-o-opn", "b-a-g", "b-a-o-c", "b-a-o-can")
            val eType = types.random(rng)
            val authorUid = "ANDROID-${"%016x".format(rng.nextLong())}"
            val cancelRef = if (eType == "b-a-o-can") """ cancel_uid="${randUid()}" """ else ""
            val xml = """<event version="2.0" uid="${randUid()}" type="$eType" time="2026-03-15T14:22:10Z" start="2026-03-15T14:22:10Z" stale="2026-03-16T14:22:10Z" how="h-g-i-g-o"><point lat="${randLat()}" lon="${randLon()}" hae="${randAlt()}" ce="9999999" le="9999999"/><detail><contact callsign="${randCallsign()}"/><emergency type="$eType" uid="$authorUid"$cancelRef/></detail></event>"""
            writeSample("emergency_$i", xml)
            count++
        }

        // === Task samples (20) ===
        repeat(20) { i ->
            val taskTypes = listOf("engage", "observe", "recon", "rescue")
            val priorities = listOf("Low", "Normal", "High", "Critical")
            val statuses = listOf("Pending", "Acknowledged", "InProgress", "Completed")
            val xml = """<event version="2.0" uid="${randUid()}" type="t-s" time="2026-03-15T14:22:10Z" start="2026-03-15T14:22:10Z" stale="2026-03-16T14:22:10Z" how="h-g-i-g-o"><point lat="${randLat()}" lon="${randLon()}" hae="${randAlt()}" ce="9999999" le="9999999"/><detail><contact callsign="${randCallsign()}"/><task type="${taskTypes.random(rng)}" target_uid="${randUid()}" assignee_uid="ANDROID-${"%016x".format(rng.nextLong())}" priority="${priorities.random(rng)}" status="${statuses.random(rng)}" note="${chatMessages.random(rng)}"/></detail></event>"""
            writeSample("task_$i", xml)
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
