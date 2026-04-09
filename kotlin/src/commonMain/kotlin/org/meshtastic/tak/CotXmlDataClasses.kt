package org.meshtastic.tak

import kotlinx.serialization.Serializable
import nl.adaptivity.xmlutil.serialization.XmlElement
import nl.adaptivity.xmlutil.serialization.XmlSerialName
import nl.adaptivity.xmlutil.serialization.XmlValue

/**
 * XML-serializable data classes for Cursor-on-Target event structure.
 * Used by [CotXmlParser] for deserialization via xmlutil.
 */

@Serializable
@XmlSerialName("event")
internal data class CoTEventXml(
    @XmlElement(false) val version: String = "2.0",
    @XmlElement(false) val uid: String = "",
    @XmlElement(false) val type: String = "",
    @XmlElement(false) val how: String = "",
    @XmlElement(false) val time: String = "",
    @XmlElement(false) val start: String = "",
    @XmlElement(false) val stale: String = "",
    val point: CoTPointXml = CoTPointXml(),
    val detail: CoTDetailXml = CoTDetailXml(),
)

@Serializable
@XmlSerialName("point")
internal data class CoTPointXml(
    @XmlElement(false) val lat: Double = 0.0,
    @XmlElement(false) val lon: Double = 0.0,
    @XmlElement(false) val hae: Double = 0.0,
    @XmlElement(false) val ce: Double = 9999999.0,
    @XmlElement(false) val le: Double = 9999999.0,
)

@Serializable
@XmlSerialName("detail")
internal data class CoTDetailXml(
    val contact: CoTContactXml? = null,
    @XmlSerialName("__group")
    val group: CoTGroupXml? = null,
    val status: CoTStatusXml? = null,
    val track: CoTTrackXml? = null,
    val takv: CoTTakVersionXml? = null,
    val precisionlocation: CoTPrecisionLocationXml? = null,
    @XmlSerialName("uid")
    val uidDetail: CoTUidXml? = null,
    @XmlSerialName("_aircot_")
    val aircot: CoTAircotXml? = null,
    @XmlSerialName("_radio")
    val radio: CoTRadioXml? = null,
    @XmlSerialName("__chat")
    val chat: CoTChatXml? = null,
    val link: CoTLinkXml? = null,
    val remarks: CoTRemarksXml? = null,
)

@Serializable
@XmlSerialName("contact")
internal data class CoTContactXml(
    @XmlElement(false) val callsign: String = "",
    @XmlElement(false) val endpoint: String = "",
    @XmlElement(false) val phone: String = "",
)

@Serializable
@XmlSerialName("__group")
internal data class CoTGroupXml(
    @XmlElement(false) val name: String = "",
    @XmlElement(false) val role: String = "",
)

@Serializable
@XmlSerialName("status")
internal data class CoTStatusXml(
    @XmlElement(false) val battery: String = "0",
)

@Serializable
@XmlSerialName("track")
internal data class CoTTrackXml(
    @XmlElement(false) val speed: String = "0",
    @XmlElement(false) val course: String = "0",
)

@Serializable
@XmlSerialName("takv")
internal data class CoTTakVersionXml(
    @XmlElement(false) val version: String = "",
    @XmlElement(false) val device: String = "",
    @XmlElement(false) val platform: String = "",
    @XmlElement(false) val os: String = "",
)

@Serializable
@XmlSerialName("precisionlocation")
internal data class CoTPrecisionLocationXml(
    @XmlElement(false) val geopointsrc: String = "",
    @XmlElement(false) val altsrc: String = "",
)

@Serializable
@XmlSerialName("uid")
internal data class CoTUidXml(
    @XmlElement(false) val Droid: String = "",
)

@Serializable
@XmlSerialName("_aircot_")
internal data class CoTAircotXml(
    @XmlElement(false) val icao: String = "",
    @XmlElement(false) val reg: String = "",
    @XmlElement(false) val flight: String = "",
    @XmlElement(false) val cat: String = "",
    @XmlElement(false) val cot_host_id: String = "",
)

@Serializable
@XmlSerialName("_radio")
internal data class CoTRadioXml(
    @XmlElement(false) val rssi: String = "",
    @XmlElement(false) val gps: String = "",
)

@Serializable
@XmlSerialName("__chat")
internal data class CoTChatXml(
    @XmlElement(false) val senderCallsign: String = "",
    @XmlElement(false) val id: String = "",
)

@Serializable
@XmlSerialName("link")
internal data class CoTLinkXml(
    @XmlElement(false) val uid: String = "",
)

@Serializable
@XmlSerialName("remarks")
internal data class CoTRemarksXml(
    @XmlValue val text: String = "",
)
