package org.meshtastic.tak

/**
 * Inlined XML test fixtures for cross-platform (commonTest) tests.
 * These are a subset of fixtures from testdata/cot_xml/ embedded as string constants
 * so they can be used in commonTest without java.io.File.
 *
 * The JVM-only [TestFixtures] (in jvmTest) provides dynamic file-based fixture
 * discovery for JUnit parameterized tests.
 */
object InlinedFixtures {

    val PLI_BASIC = """
        <?xml version="1.0" encoding="UTF-8"?>
        <event version="2.0" uid="testnode" type="a-f-G-U-C" how="m-g" time="2026-03-15T14:22:10Z" start="2026-03-15T14:22:10Z" stale="2026-03-15T14:22:55Z">
          <point lat="37.7749" lon="-122.4194" hae="-22" ce="4.9" le="9999999"/>
          <detail><contact endpoint="*:-1:stcp" callsign="testnode"/><uid Droid="testnode"/><_flow-tags_ TAK-Server-e9a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5="2026-03-15T14:22:10Z"/></detail>
        </event>
    """.trimIndent()

    val PLI_FULL = """
        <?xml version="1.0" encoding="UTF-8"?>
        <event version="2.0" uid="ANDROID-a1b2c3d4e5f6a7b8" type="a-f-G-U-C" how="h-e" time="2026-03-15T15:30:00Z" start="2026-03-15T15:30:00Z" stale="2026-03-15T15:30:45Z">
          <point lat="38.8977" lon="-77.0365" hae="-29.667" ce="32.2" le="9999999"/>
          <detail><takv os="34" version="4.12.0.1 (abc12345)[playstore].1700000000-CIV" device="SAMSUNG GALAXY S24" platform="ATAK-CIV"/><contact endpoint="*:-1:stcp" phone="+15551234567" callsign="VIPER"/><uid Droid="VIPER"/><precisionlocation altsrc="GPS" geopointsrc="GPS"/><__group role="Team Member" name="Cyan"/><status battery="88"/><track course="142.75" speed="1.2"/><_flow-tags_ TAK-Server-e9a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5="2026-03-15T15:30:00Z"/></detail>
        </event>
    """.trimIndent()

    val PLI_WEBTAK = """
        <?xml version="1.0" encoding="UTF-8"?>
        <event version="2.0" uid="d7e8f9a0-1b2c-3d4e-5f6a-7b8c9d0e1f2a" type="a-f-G-U-C-I" how="h-e" time="2026-03-15T16:10:00Z" start="2026-03-15T16:10:00Z" stale="2026-03-15T16:14:00Z">
          <point lat="38.8977" lon="-77.0365" hae="999999" ce="999999" le="999999"/>
          <detail><contact callsign="FALCON224" endpoint="*:-1:stcp"/><__group name="Cyan" role="Team Member"/><takv device="Chrome - 134" platform="WebTAK" os="Windows - 11" version="4.12.1"/><link relation="p-p" type="a-f-G-U-C-I" uid="d7e8f9a0-1b2c-3d4e-5f6a-7b8c9d0e1f2a"/><_flow-tags_ TAK-Server-e9a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5="2026-03-15T16:10:00Z"/></detail>
        </event>
    """.trimIndent()

    val GEOCHAT_SIMPLE = """
        <?xml version="1.0" encoding="UTF-8"?>
        <event version="2.0" uid="GeoChat.ANDROID-a1b2c3d4e5f6a7b8.All Chat Rooms.d4e5f6a7" type="b-t-f" how="h-g-i-g-o" time="2026-03-15T19:00:00Z" start="2026-03-15T19:00:00Z" stale="2026-03-15T19:01:00Z">
          <point lat="42.3601" lon="-71.0589" hae="-22" ce="9999999" le="9999999"/>
          <detail>
            <__chat senderCallsign="VIPER" chatRoom="All Chat Rooms" id="All Chat Rooms" parent="RootContactGroup">
              <chatgrp uid0="ANDROID-a1b2c3d4e5f6a7b8" uid1="All Chat Rooms"/>
            </__chat>
            <link uid="ANDROID-a1b2c3d4e5f6a7b8" relation="p-p" type="a-f-G-U-C"/>
            <remarks source="BAO.F.ATAK.ANDROID-a1b2c3d4e5f6a7b8" time="2026-03-15T19:00:00Z">Roger that, moving to rally point</remarks>
            <__serverdestination destinations="0.0.0.0:4242:tcp:ANDROID-a1b2c3d4e5f6a7b8"/>
          </detail>
        </event>
    """.trimIndent()

    val AIRCRAFT_ADSB = """
        <?xml version="1.0" encoding="UTF-8"?>
        <event version="2.0" uid="ICAOF1E2D3" type="a-n-A-C-F" how="m-g" time="2026-03-15T17:45:00Z" start="2026-03-15T17:45:00Z" stale="2026-03-15T17:45:45Z">
          <point lat="39.8561" lon="-104.6737" hae="3048" ce="9999999" le="9999999"/>
          <detail><contact callsign="DAL417-N338DN-A3"/><track speed="223.58" course="76.16"/><UID Droid="DAL417-N338DN-A3"/><_radio rssi="-19.4" gps="true"/><link uid="ANDROID-f1e2d3c4b5a69788" type="a-f-G-U" relation="p-p"/><remarks>f1e2d3 ICAO: F1E2D3 REG: N338DN Flight: DAL417 Type: A321 Squawk: 3456 DO-260B Category: A3     #adsbreceiver</remarks><_flow-tags_ TAK-Server-e9a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5="2026-03-15T17:45:00Z"/></detail>
        </event>
    """.trimIndent()

    val AIRCRAFT_HOSTILE = """
        <?xml version="1.0" encoding="UTF-8"?>
        <event version="2.0" uid="ICAO-B7C8D9" type="a-h-A-M-F-F" how="m-g" time="2026-03-15T18:20:00Z" start="2026-03-15T18:20:00Z" stale="2026-03-15T18:20:48Z">
          <point lat="41.2524" lon="-95.9980" hae="10000" ce="9999999" le="9999999"/>
          <detail><contact callsign="N789ZZ-N789ZZ-HAWK"/><remarks>N789ZZ N789ZZ B7C8D9 Cat:A6 Type:HAWK cotbridge@example.takserver</remarks><_aircot_ flight="N789ZZ" reg="N789ZZ" cat="A6" icao="B7C8D9" cot_host_id="cotbridge@example.takserver" type="HAWK"/><_flow-tags_ TAK-Server-e9a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5="2026-03-15T18:20:00Z"/></detail>
        </event>
    """.trimIndent()

    val DELETE_EVENT = """
        <?xml version="1.0" encoding="UTF-8"?>
        <event version="2.0" uid="a1b2c3d4-e5f6-7a8b-9c0d-e1f2a3b4c5d6" type="t-x-d-d" how="h-g-i-g-o" time="2026-03-15T19:30:00Z" start="2026-03-15T19:30:00Z" stale="2026-03-15T19:30:20Z">
          <point lat="0" lon="0" hae="0" ce="9999999" le="9999999"/>
          <detail><link relation="p-p" uid="d7e8f9a0-1b2c-3d4e-5f6a-7b8c9d0e1f2a" type="a-f-G-U-C-I"/><_flow-tags_ TAK-Server-e9a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5="2026-03-15T19:30:00Z"/></detail>
        </event>
    """.trimIndent()

    val CASEVAC = """
        <?xml version="1.0" encoding="UTF-8"?>
        <event version="2.0" uid="casevac-f7e6d5c4" type="b-r-f-h-c" how="h-e" time="2026-03-15T20:00:00Z" start="2026-03-15T20:00:00Z" stale="2026-03-15T20:10:00Z">
          <point lat="47.6062" lon="-122.3321" hae="100" ce="10" le="10"/>
          <detail>
            <contact callsign="CASEVAC-1"/>
            <link uid="ANDROID-a1b2c3d4e5f6a7b8" relation="p-p" type="a-f-G-U-C"/>
            <remarks>2 urgent surgical, 1 priority. LZ marked with green smoke. No enemy activity.</remarks>
            <_flow-tags_ TAK-Server-e9a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5="2026-03-15T20:00:00Z"/>
          </detail>
        </event>
    """.trimIndent()

    val ALERT_TIC = """
        <?xml version="1.0" encoding="UTF-8"?>
        <event version="2.0" uid="alert-b3c4d5e6" type="b-a-o-opn" how="h-e" time="2026-03-15T20:30:00Z" start="2026-03-15T20:30:00Z" stale="2026-03-15T20:35:00Z">
          <point lat="29.7604" lon="-95.3698" hae="150" ce="15" le="15"/>
          <detail>
            <contact callsign="ALPHA-6"/>
            <remarks>Troops in contact, requesting support at grid reference</remarks>
          </detail>
        </event>
    """.trimIndent()

    val DRAWING_CIRCLE = """
        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <event version="2.0" uid="6d09b6f6-720a-4eef-a197-183012512316" type="u-d-c-c" time="2026-03-15T14:22:10Z" start="2026-03-15T14:22:10Z" stale="2026-03-16T14:22:10Z" how="h-e">
          <point lat="9.98738" lon="94.98700" hae="9999999.0" ce="9999999.0" le="9999999.0"/>
          <detail>
            <shape>
              <ellipse major="226.98" minor="226.98" angle="360"/>
              <link uid="6d09b6f6-720a-4eef-a197-183012512316.Style" type="b-x-KmlStyle" relation="p-c">
                <Style>
                  <LineStyle>
                    <color>ffffffff</color>
                    <width>4.0</width>
                  </LineStyle>
                </Style>
              </link>
            </shape>
            <strokeColor value="-1"/>
            <strokeWeight value="4.0"/>
            <fillColor value="-1761607681"/>
            <contact callsign="Drawing Circle 1"/>
            <remarks/>
            <archive/>
            <labels_on value="true"/>
            <precisionlocation altsrc="???"/>
          </detail>
        </event>
    """.trimIndent()

    val MARKER_SPOT = """
        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <event version="2.0" uid="9405e320-9356-41c4-8449-f46990aa17f8" type="b-m-p-s-m" time="2026-03-15T14:22:10Z" start="2026-03-15T14:22:10Z" stale="2026-03-16T14:22:10Z" how="h-g-i-g-o">
          <point lat="10.00606" lon="95.00362" hae="9999999.0" ce="9999999.0" le="9999999.0"/>
          <detail>
            <status readiness="true"/>
            <archive/>
            <link uid="ANDROID-0000000000000001" production_time="2026-03-15T14:21:09Z" type="a-f-G-U-C" parent_callsign="SIM-01" relation="p-p"/>
            <contact callsign="R 1"/>
            <remarks/>
            <color argb="-65536"/>
            <precisionlocation altsrc="???"/>
            <usericon iconsetpath="COT_MAPPING_SPOTMAP/b-m-p-s-m/-65536"/>
          </detail>
        </event>
    """.trimIndent()

    val ROUTE_3WP = """
        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <event version="2.0" uid="a3f58c21-91e4-4b76-8d5f-6291704835ab" type="b-m-r" time="2026-03-15T14:22:10Z" start="2026-03-15T14:22:10Z" stale="2026-03-16T14:22:10Z" how="h-e">
          <point lat="9.99200" lon="95.00600" hae="9999999.0" ce="9999999.0" le="9999999.0"/>
          <detail>
            <__routeinfo/>
            <link_attr method="Driving" direction="Infil" prefix="CP" stroke="3"/>
            <link uid="wp-a1b2c3d4-0001" type="b-m-p-w" callsign="CP1" point="9.99200,95.00600"/>
            <link uid="wp-a1b2c3d4-0002" type="b-m-p-w" callsign="CP2" point="9.99320,95.00750"/>
            <link uid="wp-a1b2c3d4-0003" type="b-m-p-w" callsign="CP3" point="9.99450,95.00880"/>
            <contact callsign="Route Alpha"/>
            <remarks/>
            <archive/>
            <labels_on value="false"/>
            <precisionlocation altsrc="???"/>
          </detail>
        </event>
    """.trimIndent()

    val TASK_ENGAGE = """
        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <event version="2.0" uid="task-01" type="t-s" time="2026-03-15T21:00:00Z" start="2026-03-15T21:00:00Z" stale="2026-03-15T22:00:00Z" how="h-e">
          <point lat="17.99700" lon="140.00300" hae="80" ce="9999999" le="9999999"/>
          <detail>
            <contact callsign="Task-Alpha"/>
            <task type="engage" priority="High" status="Pending" assignee="ANDROID-0000000000000005" note="cover by fire"/>
            <link uid="target-01" relation="p-p" type="a-f-G"/>
            <remarks/>
          </detail>
        </event>
    """.trimIndent()

    /** All fixtures indexed by name, matching the file names in testdata/cot_xml/. */
    val ALL = mapOf(
        "pli_basic" to PLI_BASIC,
        "pli_full" to PLI_FULL,
        "pli_webtak" to PLI_WEBTAK,
        "geochat_simple" to GEOCHAT_SIMPLE,
        "aircraft_adsb" to AIRCRAFT_ADSB,
        "aircraft_hostile" to AIRCRAFT_HOSTILE,
        "delete_event" to DELETE_EVENT,
        "casevac" to CASEVAC,
        "alert_tic" to ALERT_TIC,
        "drawing_circle" to DRAWING_CIRCLE,
        "marker_spot" to MARKER_SPOT,
        "route_3wp" to ROUTE_3WP,
        "task_engage" to TASK_ENGAGE,
    )
}
