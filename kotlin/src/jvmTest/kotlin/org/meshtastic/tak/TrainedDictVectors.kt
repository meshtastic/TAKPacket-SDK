package org.meshtastic.tak

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * A genuinely TRAINED zstd dictionary (`zstd --train`, CLI >= 1.4) plus
 * training-distribution samples, used by the jvmTest oracle to exercise the
 * decoder's dictionary-entropy path.
 *
 * WHY this exists (regression guard): TAK's own shipped dicts / golden frames
 * happen to never reference a Huffman weight FSE table containing a 0-bit
 * (`nbBits == 0`) transition, so a false-positive "non-advancing weight
 * transition" guard in [org.meshtastic.tak.internal.zstd.decodeWeightStream]
 * went undetected. A `zstd --train` dictionary DOES produce such a table, and
 * libzstd, compressing training-distribution data WITH that dict, emits frames
 * that reference it (treeless literals / FSE-repeat). Decoding those frames is
 * the only thing that drives the dict-entropy decode branches — TAK's own
 * encoder emits Predefined-FSE + Raw-literals frames and never references a
 * dictionary's entropy tables. See [TrainedDictInteropTest].
 *
 * Provenance: byte-identical to kzstd's `TestVectors.trainedDict` /`structured`
 * (the pure-Kotlin codec extracted from this SDK), itself the deterministic
 * output of kzstd `scripts/train_test_dict.py` (fixed RNG seed, generic
 * key/value JSON — NO CoT/TAK content). The chunks below are the raw dictionary
 * re-chunked into Base64 string constants.
 */
@OptIn(ExperimentalEncodingApi::class)
internal object TrainedDictVectors {

    /** Trained-dict magic (RFC 8878 §5): 0xEC30A437, little-endian bytes. */
    val TRAINED_DICT_MAGIC: ByteArray = byteArrayOf(0x37, 0xA4.toByte(), 0x30, 0xEC.toByte())

    private val DICT_B64_CHUNKS: List<String> = listOf(
        "N6Qw7A2SUDsqEMCaJB0zMAMzMAMzBOGg4aNvrY/d0KLoR9+P1ojsRUoppZRyZ+p2oEh7IwIACAaCwWChudnwBAAEYEMGB4gcqJgTy8mjybBDBABIgAEC",
        "AAAAAAAAAJAAAAAENHNRTCajDBIAAAAAAAAAAAAAAAEAAAAEAAAACAAAACJsYXQiOi0xLjEwNjEyLCJsb24iOi0zNi44NjIxNCwibXNnIjoiYW5kIHRo",
        "ZSB0aGUgc3lzdGVtIHRoZSBhIHF1aWNrIHJlcG9ydHMifXsidHlwZSI6ImhlYXJ0YmVhdCIsInNlcSI6OTM5OTI5LCJub2RlIjoibm9kZS0yOSIsInN0",
        "YXRlIjoicG9ydHMgYnJvd24gdGhlIGV2ZXJ5IGxpbmsgYnJvd24ifXsidHlwZSI6ImFsZXJ0Iiwic2VxIjo2OTMwMjUsIm5vZGUiOiJub2RlLTU2Iiwi",
        "c3RhdGUiOiJ3YXJuIiwibGF0Ijo3My40OTMyNCwibG9uIjotMTY2LjU3Nzg4LCJtc2ciOiJyZWdpb24gYnJvd24gdGhyb3VnaHB1dCBhbmQgdGhyb3Vn",
        "aHB1dCByZXBvcnRzIHRoZSB3aGlsZSBhbmQgYSJ9eyJ0eXBlIjoiYWxlcnQiLCJzZXEiOjUxNjEyMywibm9kZSI6Im5vZGUtMDciLCJzdGF0ZSI6ImRl",
        "Z3JhZGVkIiwibGF0IjotNzAuMDUwNjEsImxvbiI6MzQuNzY2OTIsIm1zZyI6ImEgdGhlIG1vbml0b3JlZCBicm93biBhIGxhenkgbm9taW5hbCB3aGls",
        "ZSB3aGlsZSJ9eyJ0eXBlIjoicmVwb3J0Iiwic2VxIjo1NTg2OTcsIm5vZGUiOiJub2RlLTE2Iiwic3RhdGUiOiJvZmZsaW5lIiwibGF0IjoyMi44MzY3",
        "MiwibG9uIjotNTEuNjc0MTEsIm1zZyI6InF1aWNrIHF1aWNrIG1vbml0b3JlZCBqdW1wcyJ9eyJ0eXBlIjoidGVsZW1ldHJ5Iiwic2VxIjo5MTk3MTIs",
        "Im5vZGUiOiJub2RlLTU0Iiwic3RhcyBkb2cgd2hpbGUgbW9uaXRvcmVkIHdoaWxlIHRoZSBzdGFibGUgYW5kIHN0YWJsZSBicm93biBhIn17InR5cGUi",
        "OiJzdGF0dXMiLCJzZXEiOjcwODY3MSwibm9kZSI6Im5vZGUtNTEiLCJzdGF0ZSI6Im9mZmxpbmUiLCJsYXQiOjEyLjU4OTcwLCJsb24iOi0xNjguMTcy",
        "OTEsIm1zZyI6InRoZSBzdGFibGUgbGF0ZW5jeSJ9eyJ0eXBlIjoidGVsZW1ldHJ5Iiwic2VxIjo1MTkyMDAsIm5vZGUiOiJub2RlLTYxIiwic3RhdGUi",
        "OiJvayIsImxhdCI6LTE0Ljc1NzI2LCJsb24iOi0xMjcuNzM0MzQsIm1zZyI6ImJyb3duIGEgYWNyb3NzIHdoaWxlIHJlcG9ydHMgc3RhYmxlIGxhenkg",
        "dGhlIn17InR5cGUiOiJoZWFydGJlYXQiLCJzZXEiOjcxMzc1OCwibm9kZSI6Im5vZGUtNDgiLCJzdGF0ZSI6Indhcm4iLCJsYXQiOjQwLjE5MTI5LCJs",
        "b24iOjkyLjkwOTg1LCJtc2ciOiJ0aGUgbm9taW5hbCBqdW1wcyBhbmQgbm9taW5hbCB0aGUgYW5kIG92ZXIgcXVpY2sgdGhlIn17InR5cGUiOiJyZXBv",
        "cnQiLCJzZXEiOjMxNzEsIm5vZGUiOiJub2RlLTA3Iiwic3RhLjQ5NTgyLCJtc2ciOiJpbiBqdW1wcyBpbiB3aGlsZSBsYXp5IG92ZXIifXsidHlwZSI6",
        "ImV2ZW50Iiwic2VxIjozMjk1MzAsIm5vZGUiOiJub2RlLTA2Iiwic3RhdGUiOiJ1bmtub3duIiwibGF0IjoxLjE4NTkzLCJsb24iOi0yNi4zNTQzNywi",
        "bXNnIjoid2hpbGUgZG9nIGV2ZXJ5IGp1bXBzIHF1aWNrIHdoaWxlIHJlcG9ydHMgYSB0aHJvdWdocHV0IHN5c3RlbSBsYXRlbmN5IHRoZSJ9eyJ0eXBl",
        "IjoicmVwb3J0Iiwic2VxIjozMjEzODQsIm5vZGUiOiJub2RlLTQ0Iiwic3RhdGUiOiJ3YXJuIiwibGF0Ijo4My40MDA1NywibG9uIjotMTE1LjE4MjUx",
        "LCJtc2ciOiJtb25pdG9yZWQgbm9taW5hbCBkb2cganVtcHMifXsidHlwZSI6InRlbGVtZXRyeSIsInNlcSI6MTkwNjE4LCJub2RlIjoibm9kZS01NSIs",
        "InN0YXRlIjoicmVjb3ZlcmluZyIsImxhdCI6LTY3LjA0NjU0LCJsb24iOjM2LjQyMDM2LCJtc2ciOiJzdGFibGUgd2hpbGUgdGhyb3VnaHB1dCB0aGUi",
        "fXsidHlwZSI6ImFsZXJ0Iiwic2VxIjoyMTEzNDEsIm5vZGUiOiJub2RlLTExIiwic3RhIm1zZyI6ImJyb3duIHN0YWJsZSBxdWljayBub21pbmFsIGV2",
        "ZXJ5IHRocm91Z2hwdXQifXsidHlwZSI6InN0YXR1cyIsInNlcSI6Nzc3MzUwLCJub2RlIjoibm9kZS02MyIsInN0YXRlIjoid2FybiIsImxhdCI6NDQu",
        "NzMxMTMsImxvbiI6LTE0MS4xMTAwMSwibXNnIjoiYW5kIGV2ZXJ5IGV2ZXJ5IGluIGxpbmsgbGF6eSBmb3ggbGF6eSBxdWljayBkb2cgcmVnaW9uIGZv",
        "eCJ9eyJ0eXBlIjoic3RhdHVzIiwic2VxIjo2OTAwNDgsIm5vZGUiOiJub2RlLTIzIiwic3RhdGUiOiJyZWNvdmVyaW5nIiwibGF0IjotMzAuODcwOTEs",
        "ImxvbiI6LTE5LjcxOTk4LCJtc2ciOiJyZXBvcnRzIG92ZXIgYWNyb3NzIGFjcm9zcyByZXBvcnRzIHN5c3RlbSBhY3Jvc3MgdGhlIHRoZSByZWdpb24g",
        "dGhyb3VnaHB1dCBsaW5rIn17InR5cGUiOiJ0ZWxlbWV0cnkiLCJzZXEiOjE5NzcxMiwibm9kZSI6Im5vZGUtMjgiLCJzdGF0ZSI6InJlY292ZXJpbmci",
        "LCJsYXQiOi05LjI5Njg3LCJsb24iOjE3NC42NDQ4MCwibXNnIjoiYWNyb3NzIGZveCBldmVyeSB3aGlsZSBub21pNSwibG9uIjotMC4zNjYwNywibXNn",
        "IjoicmVnaW9uIG92ZXIganVtcHMgZG9nIGRvZyJ9eyJ0eXBlIjoicmVwb3J0Iiwic2VxIjo3OTQ4NjQsIm5vZGUiOiJub2RlLTQyIiwic3RhdGUiOiJv",
        "ayIsImxhdCI6LTUxLjE1MjE2LCJsb24iOi05Mi45NzEyOCwibXNnIjoidGhlIHJlcG9ydHMgZXZlcnkgZm94IHF1aWNrIGRvZyBldmVyeSBldmVyeSBl",
        "dmVyeSBtb25pdG9yZWQgZm94IGV2ZXJ5In17InR5cGUiOiJoZWFydGJlYXQiLCJzZXEiOjk4MjY4Mywibm9kZSI6Im5vZGUtMDEiLCJzdGF0ZSI6Indh",
        "cm4iLCJsYXQiOjU0LjcwOTA2LCJsb24iOjEyLjkxMTA3LCJtc2ciOiJhIGluIGxhdGVuY3kgdGhyb3VnaHB1dCBmb3ggc3RhYmxlIGluIn17InR5cGUi",
        "OiJldmVudCIsInNlcSI6OTk1OTYxLCJub2RlIjoibm9kZS0yMCIsInN0YXRlIjoib2ZmbGluZSIsImxhdCI6LTY3LjQyMzEwLCJsb24iOjQ3LjgwNzQ1",
        "LCJtc2ciOiJzeXN0ZW0gdGhlIHF1aWNrIHRocm91Z2hwdXQgbGF6eSBsaW5rIG5vbWluYWwgYWNyb3NzIHJlZ2lvbiByZXBvcnRzIGRvZyJ9NDYwNywi",
        "bG9uIjoxNDIuNTUwMTMsIm1zZyI6ImluIGxhenkgb3ZlciByZWdpb24gaW4gbW9uaXRvcmVkIHF1aWNrIGRvZyJ9eyJ0eXBlIjoiaGVhcnRiZWF0Iiwi",
        "c2VxIjoyNjY2OTAsIm5vZGUiOiJub2RlLTMyIiwic3RhdGUiOiJvayIsImxhdCI6NDAuMDM0MzIsImxvbiI6LTE1My42ODYxMiwibXNnIjoibGF6eSB0",
        "aGUgYWNyb3NzIHN0YWJsZSBsYXRlbmN5IGFjcm9zcyBicm93biB0aHJvdWdocHV0In17InR5cGUiOiJ0ZWxlbWV0cnkiLCJzZXEiOjc3Mjc3OCwibm9k",
        "ZSI6Im5vZGUtMTIiLCJzdGF0ZSI6Im9mZmxpbmUiLCJsYXQiOi03Ni44Nzk0NiwibG9uIjotNi44MDEyMSwibXNnIjoibW9uaXRvcmVkIHRoZSB0aGUg",
        "YnJvd24gYnJvd24gbW9uaXRvcmVkIn17InR5cGUiOiJldmVudCIsInNlcSI6OTk5MzYxLCJub2RlIjoibm9kZS0yOCIsInN0YXRlIjoiZGVncmFkZWQi",
        "LCJsYXQiOi0xNy4yMzU5NSwibG9uIjotNTkuMzU4NjYsIm1zZyI6InRocm91Z2hwdXQgbGF0ZW5jeSBtb25pdG9yZWQgc3RhYmxlIGluIGZveCBub21p",
        "bmFsIHRoZSJ9NjkzLCJsb24iOjk2LjQ0MzczLCJtc2ciOiJyZXBvcnRzIGZveCByZXBvcnRzIHJlcG9ydHMgc3RhYmxlIGZveCJ9eyJ0eXBlIjoiYWxl",
        "cnQiLCJzZXEiOjc2MDYxOSwibm9kZSI6Im5vZGUtNjAiLCJzdGF0ZSI6Im9mZmxpbmUiLCJsYXQiOi03Ny4xNDM0OCwibG9uIjotNjcuNzQzODUsIm1z",
        "ZyI6Im1vbml0b3JlZCBzdGFibGUgZXZlcnkgZXZlcnkgc3RhYmxlIHJlZ2lvbiBmb3gifXsidHlwZSI6InJlcG9ydCIsInNlcSI6NjAyOTM2LCJub2Rl",
        "Ijoibm9kZS0yMiIsInN0YXRlIjoid2FybiIsImxhdCI6LTIyLjA5MjA2LCJsb24iOi0xMjIuMTk4OTAsIm1zZyI6ImRvZyBldmVyeSBhY3Jvc3MgcmVw",
        "b3J0cyB0aHJvdWdocHV0IGluIHRoZSBsaW5rIGxhdGVuY3kgb3ZlciBsaW5rIn17InR5cGUiOiJldmVudCIsInNlcSI6NTY0MTQwLCJub2RlIjoibm9k",
        "ZS0xOCIsInN0YXRlIjoib2ZmbGluZSIsImxhdCI6NDMuNzQzNzgsImxvbiI6LTU1LjAxNTY5LCJtc2ciOiJpbiBhY3Jvc3MgYnJvd24gYnJvd24gbm9t",
        "aW5hbCBicm93biBkb2cgdGhlIGFuZCJ9biBkb2cgbW9uaXRvcmVkIGJyb3duIHN5c3RlbSBzdGFibGUgYWNyb3NzIn17InR5cGUiOiJldmVudCIsInNl",
        "cSI6NDI4OTM3LCJub2RlIjoibm9kZS0xNSIsInN0YXRlIjoiZGVncmFkZWQiLCJsYXQiOi0wLjAxNzc5LCJsb24iOi01MC40NzIxMCwibXNnIjoib3Zl",
        "ciB0aHJvdWdocHV0IGFuZCBmb3gifXsidHlwZSI6InN0YXR1cyIsInNlcSI6NjcxNzUsIm5vZGUiOiJub2RlLTA0Iiwic3RhdGUiOiJvZmZsaW5lIiwi",
        "bGF0Ijo3NS45OTMwNywibG9uIjo4OC4yNDk3NSwibXNnIjoic3lzdGVtIHRocm91Z2hwdXQgc3lzdGVtIG92ZXIgcXVpY2sgZm94IGxpbmsgcmVnaW9u",
        "IHRoZSB0aGUifXsidHlwZSI6InRlbGVtZXRyeSIsInNlcSI6OTQ0MDMyLCJub2RlIjoibm9kZS01OCIsInN0YXRlIjoiZGVncmFkZWQiLCJsYXQiOi01",
        "NC4wMDQzNiwibG9uIjotMTY5LjYxNDM4LCJtc2ciOiJicm93biBhIHRocm91Z2hwdXQgaW4gbGF6eSB0aHJvdWdocHV0IHN0YWJsZSBmb3ggbGF6eSBq",
        "dW1wcyJ9eyJ0eXBlIjoiYWxlcnQiLCJzZXEiOjU1NTcwNCwibm9kNy42NjQyNCwibG9uIjoxNC4zMTU0MywibXNnIjoicmVnaW9uIG5vbWluYWwgdGhl",
        "IHJlZ2lvbiBsYXRlbmN5IGxpbmsifXsidHlwZSI6ImV2ZW50Iiwic2VxIjoyMTU3NTQsIm5vZGUiOiJub2RlLTMzIiwic3RhdGUiOiJvZmZsaW5lIiwi",
        "bGF0IjotOC4zNzAzNiwibG9uIjoxNzUuMDY2MDUsIm1zZyI6InRoZSB0aGUgb3ZlciBtb25pdG9yZWQgYSBhIG92ZXIgaW4ifXsidHlwZSI6InN0YXR1",
        "cyIsInNlcSI6ODI5ODM1LCJub2RlIjoibm9kZS0xNCIsInN0YXRlIjoicmVjb3ZlcmluZyIsImxhdCI6LTg0LjcyMjM1LCJsb24iOjY2LjI1Mzc2LCJt",
        "c2ciOiJyZWdpb24gdGhlIGV2ZXJ5IHRoZSBtb25pdG9yZWQgdGhlIHRoZSBhY3Jvc3MifXsidHlwZSI6InN0YXR1cyIsInNlcSI6NjU0NjIzLCJub2Rl",
        "Ijoibm9kZS01MiIsInN0YXRlIjoib2ZmbGluZSIsImxhdCI6LTQuOTEzODUsImxvbiI6MTM3LjA5MjYzLCJtc2ciOiJqdW1wcyB0aHJvdWdocHV0IGxp",
        "bmsgcmVwb3J0cyBxdWljayBxdWljayB0aGUganVtcHMgYWNyb3NzIG5vbWluYWwgcmVnaW9ucSI6MzA2MzI0LCJub2RlIjoibm9kZS0zOCIsInN0YXRl",
        "Ijoib2siLCJsYXQiOjU3LjQxNDEwLCJsb24iOjYwLjA5MDU0LCJtc2ciOiJkb2cgcmVwb3J0cyBsYXp5IHJlZ2lvbiBqdW1wcyBhY3Jvc3Mgb3ZlciBy",
        "ZXBvcnRzIGEganVtcHMgd2hpbGUifXsidHlwZSI6InRlbGVtZXRyeSIsInNlcSI6NjgzMjM5LCJub2RlIjoibm9kZS0yNyIsInN0YXRlIjoib2ZmbGlu",
        "ZSIsImxhdCI6NDQuNzAwMTIsImxvbiI6LTEyMi4zMjk3MSwibXNnIjoib3ZlciBzeXN0ZW0gZG9nIGp1bXBzIGp1bXBzIHJlZ2lvbiBldmVyeSBub21p",
        "bmFsIHJlcG9ydHMgYnJvd24gcmVnaW9uIHRocm91Z2hwdXQifXsidHlwZSI6ImV2ZW50Iiwic2VxIjo5MTg1NzYsIm5vZGUiOiJub2RlLTQzIiwic3Rh",
        "dGUiOiJvayIsImxhdCI6LTYwLjYyMjM1LCJsb24iOi0xNzQuMDExMDMsIm1zZyI6InN5c3RlbSBmb3ggcmVwb3J0cyBzeXN0ZW0gbGluayBpbiBxdWlj",
        "ayB0aHJvdWdocHV0IHRoZSJ9eyJ0eXBlIjoic3RhdHVzIiwic2VxIjo0NDQzODUsIm5vZGUiOiJub2RlLTU4Iiwic3RhOTIyMSwibG9uIjotODQuMTg3",
        "ODYsIm1zZyI6ImxpbmsgYnJvd24gd2hpbGUgdGhyb3VnaHB1dCJ9eyJ0eXBlIjoicmVwb3J0Iiwic2VxIjozMDg3NzIsIm5vZGUiOiJub2RlLTA5Iiwi",
        "c3RhdGUiOiJkZWdyYWRlZCIsImxhdCI6LTM3LjM4MTQ4LCJsb24iOi0xNi4wODU2MSwibXNnIjoidGhlIGxpbmsgdGhlIHN0YWJsZSBvdmVyIHRoZSBy",
        "ZXBvcnRzIGxpbmsgdGhlIn17InR5cGUiOiJ0ZWxlbWV0cnkiLCJzZXEiOjEyMzEwNCwibm9kZSI6Im5vZGUtMTgiLCJzdGF0ZSI6InJlY292ZXJpbmci",
        "LCJsYXQiOi05LjUzOTk2LCJsb24iOjExOC45NjE3MSwibXNnIjoibGF0ZW5jeSBicm93biBhbmQgbGF0ZW5jeSBpbiB0aGUgcmVnaW9uIGxpbmsgdGhl",
        "IG5vbWluYWwifXsidHlwZSI6ImFsZXJ0Iiwic2VxIjo4OTg5NzQsIm5vZGUiOiJub2RlLTI2Iiwic3RhdGUiOiJvZmZsaW5lIiwibGF0Ijo0Ni44NDkw",
        "NywibG9uIjotNTUuNTExNTYsIm1zZyI6Im1vbml0b3JlZCB0aGUgc3lzdGVtIGFuZCByZXBvcnRzIHRocm91Z2hwdXQgZm94IHRoZSBxdWljayJ9MTc1",
        "OCwibG9uIjotNTQuMDU2MTEsIm1zZyI6ImFjcm9zcyB0aGUgbGF0ZW5jeSBldmVyeSB0aHJvdWdocHV0IGJyb3duIHRoZSBsYXp5IHJlZ2lvbiB0aGUg",
        "dGhyb3VnaHB1dCBtb25pdG9yZWQifXsidHlwZSI6InN0YXR1cyIsInNlcSI6NjQxODI4LCJub2RlIjoibm9kZS0wMyIsInN0YXRlIjoidW5rbm93biIs",
        "ImxhdCI6NjQuNDk1NDIsImxvbiI6LTgzLjgwOTEwLCJtc2ciOiJqdW1wcyByZXBvcnRzIGluIGJyb3duIHdoaWxlIn17InR5cGUiOiJyZXBvcnQiLCJz",
        "ZXEiOjQzNjA2Miwibm9kZSI6Im5vZGUtMDgiLCJzdGF0ZSI6InJlY292ZXJpbmciLCJsYXQiOi04Mi43MjI0NCwibG9uIjotNzQuOTk0MjAsIm1zZyI6",
        "Im92ZXIgbGF0ZW5jeSBub21pbmFsIGluIGluIn17InR5cGUiOiJldmVudCIsInNlcSI6ODE4OTUxLCJub2RlIjoibm9kZS00NSIsInN0YXRlIjoib2si",
        "LCJsYXQiOjcyLjM0OTM2LCJsb24iOi0zMi41NTc2MiwibXNnIjoibm9taW5hbCBtb25pdG9yZWQgYSBsaW5rIG92ZXIgYWNyb3NzIGxhenkgc3RhYmxl",
        "IGluIHRoZSBpbiJ9YXQiOi00NS4yNTkzMywibG9uIjo1NS45NDkwOSwibXNnIjoibGF0ZW5jeSB0aGUgbGluayBtb25pdG9yZWQgbGF0ZW5jeSBtb25p",
        "dG9yZWQgcmVnaW9uIGxhdGVuY3kifXsidHlwZSI6InN0YXR1cyIsInNlcSI6NDUwMjY0LCJub2RlIjoibm9kZS0wNCIsInN0YXRlIjoib2ZmbGluZSIs",
        "ImxhdCI6NTkuNjI1MTUsImxvbiI6MTQzLjMxMDk0LCJtc2ciOiJxdWljayBmb3ggbW9uaXRvcmVkIn17InR5cGUiOiJhbGVydCIsInNlcSI6OTYyMjks",
        "Im5vZGUiOiJub2RlLTM3Iiwic3RhdGUiOiJvZmZsaW5lIiwibGF0IjozMy44MjE1MiwibG9uIjotODcuNTY3MTMsIm1zZyI6ImZveCBicm93biB0aHJv",
        "dWdocHV0IGluIGJyb3duIHN5c3RlbSBhbmQgdGhlIHdoaWxlIHRoZSJ9eyJ0eXBlIjoic3RhdHVzIiwic2VxIjo4NjQ0NjAsIm5vZGUiOiJub2RlLTUx",
        "Iiwic3RhdGUiOiJ1bmtub3duIiwibGF0Ijo1MC4zMzE4MCwibG9uIjotNjkuNzI2NjcsIm1zZyI6ImxhdGVuY3kgbm9taW5hbCB0aGUgZG9nIGxhdGVu",
        "Y3kgZG9nIHJlcG9ydHMgYW5kIGFjcm9zcyJ9ZGUiOiJub2RlLTQxIiwic3RhdGUiOiJvayIsImxhdCI6NTIuNzg0MDYsImxvbiI6NzMuNzY5MTcsIm1z",
        "ZyI6ImFjcm9zcyBkb2cgaW4gYW5kIGJyb3duIn17InR5cGUiOiJyZXBvcnQiLCJzZXEiOjQyNTk1OCwibm9kZSI6Im5vZGUtMzYiLCJzdGF0ZSI6Indh",
        "cm4iLCJsYXQiOi0zOC45ODgzOCwibG9uIjotMTc3LjkzMzg5LCJtc2ciOiJ0aHJvdWdocHV0IHdoaWxlIGp1bXBzIHRoZSBsYXp5In17InR5cGUiOiJh",
        "bGVydCIsInNlcSI6NjkyNDYyLCJub2RlIjoibm9kZS02NCIsInN0YXRlIjoib2ZmbGluZSIsImxhdCI6LTYuNzYyNjAsImxvbiI6LTEwNC44NjU0Mywi",
        "bXNnIjoic3RhYmxlIGV2ZXJ5IHN5c3RlbSByZXBvcnRzIGEgcmVnaW9uIn17InR5cGUiOiJzdGF0dXMiLCJzZXEiOjcxMjk1OSwibm9kZSI6Im5vZGUt",
        "MjIiLCJzdGF0ZSI6InVua25vd24iLCJsYXQiOi03MS45ODE1OCwibG9uIjotMTA0Ljg3NDM4LCJtc2ciOiJqdW1wcyBsaW5rIG92ZXIgc3lzdGVtIGxh",
        "dGVuY3kgcmVwb3J0cyBsYXRlbmN5IHRoZSBzdGFibGUgbm9taW5hbCJ9OiJub2RlLTQwIiwic3RhdGUiOiJkZWdyYWRlZCIsImxhdCI6OS4wNjEwOCwi",
        "bG9uIjoxMjguNTkzOTQsIm1zZyI6ImFjcm9zcyB0aHJvdWdocHV0IHRoZSBhbmQgdGhlIHRoZSBhY3Jvc3Mgb3ZlciByZXBvcnRzIn17InR5cGUiOiJl",
        "dmVudCIsInNlcSI6NzU3ODI5LCJub2RlIjoibm9kZS0zMyIsInN0YXRlIjoib2ZmbGluZSIsImxhdCI6LTM1Ljk4MDA1LCJsb24iOjY3LjcxNTYwLCJt",
        "c2ciOiJldmVyeSBhIHN0YWJsZSJ9eyJ0eXBlIjoiaGVhcnRiZWF0Iiwic2VxIjo4MzIyNTMsIm5vZGUiOiJub2RlLTIzIiwic3RhdGUiOiJ3YXJuIiwi",
        "bGF0IjotMTQuOTEwODksImxvbiI6LTQwLjY3ODA0LCJtc2ciOiJ3aGlsZSB0aGUgZG9nIGV2ZXJ5IHF1aWNrIHN0YWJsZSB0aGUgbW9uaXRvcmVkIG92",
        "ZXIgcXVpY2sgYW5kIGo=",
    )

    /** A genuinely TRAINED zstd dictionary (Huffman + FSE entropy tables + content). */
    val trainedDict: ByteArray by lazy { Base64.decode(DICT_B64_CHUNKS.joinToString("")) }

    /**
     * Structured records from the dictionary's training distribution. libzstd
     * compresses these WITH the dict (treeless literals / FSE-repeat), so
     * decoding them drives the dictionary-entropy path.
     */
    val structured: List<ByteArray> = listOf(
        """{"type":"heartbeat","seq":648886,"node":"node-23","state":"ok","lat":-68.20110,"lon":-25.68255,"msg":"region and nominal nominal monitored the the"}""".encodeToByteArray(),
        """{"type":"alert","seq":871432,"node":"node-21","state":"offline","lat":24.37329,"lon":151.41676,"msg":"system region fox the"}""".encodeToByteArray(),
        """{"type":"telemetry","seq":891278,"node":"node-32","state":"offline","lat":52.97562,"lon":-132.17450,"msg":"region reports latency and nominal over"}""".encodeToByteArray(),
        """{"type":"telemetry","seq":979009,"node":"node-38","state":"ok","lat":-77.62734,"lon":-86.69041,"msg":"link region a fox dog the the stable quick"}""".encodeToByteArray(),
        """{"type":"alert","seq":71295,"node":"node-14","state":"warn","lat":-30.09490,"lon":-104.70441,"msg":"fox latency jumps the fox dog fox dog a fox reports throughput"}""".encodeToByteArray(),
        """{"type":"alert","seq":690387,"node":"node-02","state":"degraded","lat":27.61411,"lon":113.50328,"msg":"throughput stable while latency over dog the in and"}""".encodeToByteArray(),
    )
}
