# Large TAK payloads: chunking sketch

**Status:** Draft / proposal. Nothing here is implemented yet.
**Scope:** How to carry a `TAKPacketV2` whose compressed wire payload is larger than one Meshtastic frame (roughly 1 KB to 3.5 KB), across all five bindings.

## 1. Summary

1. **Keep `TAKPacketV2` unchanged.** Chunking is a thin transport layer *around* today's wire payload, not a schema change. A message that fits one frame goes out exactly as it does today, byte for byte, with zero added overhead.
2. **Compress first, then chunk.** Serialize and compress the whole packet once with the one-shot zstd API (as today), then split the resulting wire payload into fragments. Measured, this needs about half the airtime of splitting the content into many small independent packets (§3).
3. **Mark fragments with flags byte `0xFE` on the same port (78).** Older receivers read `0xFE & 0x3F = 0x3E`, an unknown dictionary, and drop the frame cleanly, so no new port number or firmware change is needed.
4. **3-byte fragment header:** `[0xFE][msg_id][index:4 | count-1:4]`. That allows up to 16 fragments, about 3.6 KB of compressed payload, which is about 35 s of airtime per hop on LongFast. The cap is deliberate.
5. **A CRC-32 per message, and an optional XOR parity fragment.** The CRC catches mis-assembled messages. One parity fragment recovers any single lost fragment. At 90% per-frame delivery that lifts a 5-fragment message from 59% to 89% delivered. Receivers must support recovery from day one; senders may choose whether to send parity.
6. **Reassembly is bounded and stateless across messages.** Losing a fragment loses only that message. It never affects decoding of any other message, which is the resilience invariant restated for multi-frame messages (§7).

A Python prototype of the framing, reassembly and parity recovery (§5–§6) round-trips correctly under shuffled order, duplicate frames and any single lost fragment.

## 2. Why: how big do "large" payloads get?

Measured with the Python binding and the shipped 512 KB dictionary, with the parser's vertex and route caps lifted. Freehand vertices come from a random walk, and every route waypoint carries a UUID, which is close to incompressible.

| Case | CoT XML | Protobuf | Wire (compressed) | Fragments (230 B slices + CRC) |
|---|---:|---:|---:|---:|
| Freehand, 32 vertices | 1,547 | 267 | 250 | 2 |
| Freehand, 128 vertices | 4,907 | 790 | 704 | 4 |
| Freehand, 256 vertices | 9,387 | 1,484 | 1,220 | 6 |
| Freehand, 512 vertices | 18,347 | 2,971 | 2,259 | 10 |
| Route, 16 waypoints | 2,191 | 975 | 644 | 3 |
| Route, 32 waypoints | 3,935 | 1,870 | 1,216 | 6 |
| Route, 64 waypoints | 7,423 | 3,663 | 2,259 | 10 |
| Route, 128 waypoints | 14,428 | **7,308** | 4,321 | 19 (over cap) |
| Marker + ~600-word remarks | 4,291 | 3,960 | 839 | 4 |

Takeaways:

- Even a 32-vertex freehand with realistic jitter already misses one frame (250 B). Today the parser truncates it and sets `truncated`.
- Routes cost about 35 B per waypoint after compression, because of the UUIDs. A 64-waypoint route needs about 10 fragments.
- **The 4,096-byte decompression cap is hit before the fragment cap.** The 128-waypoint route's protobuf (7.3 KB) is rejected by today's decoder. Fragmented messages need their own, larger cap (§8).

## 3. Options considered

### What gets split

| Option | Airtime | Loss behaviour | Verdict |
|---|---|---|---|
| **A. Semantic split:** N independent `TAKPacketV2`s (e.g. a polygon cut into 24-vertex pieces) | 128 v: 1,089 B / 6 frames<br>256 v: 2,093 B / 11 frames<br>512 v: 4,159 B / 22 frames | Graceful: each piece decodes alone | Rejected as the general mechanism. About 2× the airtime, because every piece repeats the envelope and compresses with no shared context. It also needs per-type merge logic on the ATAK side and does not work for opaque payloads (remarks, `raw_detail`). |
| **B. Chunk, then compress each chunk** | Worst | Same as C: all chunks needed | Rejected. It splits protobuf mid-field and loses cross-chunk matches. |
| **C. Compress whole, then chunk** ✅ | 128 v: 662 B / 3 frames<br>256 v: 1,126 B / 5 frames<br>512 v: 2,211 B / 10 frames | All data fragments needed (or all but one, with parity) | **Chosen.** Best ratio, payload-agnostic, and reuses `compress()` / `decompress()` unchanged. |

A `TAKPacketV2` has no useful partial form: half a route or half a `raw_detail` is not a valid CoT event. So the "graceful degradation" of A buys little, and parity (§6.3) gives better delivery odds for less airtime.

### How fragments are marked

| Option | Notes |
|---|---|
| New portnum | Clean separation, but needs a `portnums.proto` change, firmware and app plumbing, and a second listener in every app. |
| Protobuf envelope (`message TAKFragment { uint32 msg_id; uint32 index; uint32 count; bytes data; }`) | Self-describing, but about 7–9 B per frame of tags and varints versus 3 B. That goes against the frame-slimming work already done (the magic strip, which saves 4 B). |
| **Flags byte `0xFE` on port 78** ✅ | Fits the existing `[flags][body]` format. Old receivers reject it as unknown dictionary `0x3E`, a path every binding already handles without crashing. Costs one value from the flags space. |

## 4. Constraints this has to respect

- **Frame size.** `mesh.options` caps `Data.payload` at **233 B** (`DATA_PAYLOAD_LEN = 233`). The SDK and `WIRE_FORMAT.md` use 237 B as "the LoRa MTU". Fragments should fill frames, so the fragmenter takes `maxFrameBytes` as a parameter defaulting to **233**. *The 237 vs 233 difference is worth reconciling separately (see §12).*
- **Resilience invariant:** no cross-packet compression state, one-shot zstd only, static dictionary. Still true here: the whole message is compressed once, and the reassembler only buffers bytes (§7).
- **Firmware nanopb pools** (`atak.options`): `Route.links max_count:16`, 32 vertices, `GeoChat.message max_size:200`, `raw_detail max_size:220`. Firmware only ever decodes uncompressed `0xFF` frames (e.g. TAK_TRACKER), and it cannot decode `0xFE` fragments, so the pools constrain single `0xFF` frames only. See §8.
- **Airtime.** One full frame is about 2.2 s on LongFast, 0.64 s on MediumFast and 9.3 s on LongSlow, per hop, before rebroadcasts. Firmware also throttles on channel utilization. Multi-frame messages must be rare, user-initiated events, never periodic traffic.

## 5. Wire format

### 5.1 Fragment frame

```
+--------+---------+-------------------+------------------------------+
| Byte 0 | Byte 1  | Byte 2            | Bytes 3..N                   |
| 0xFE   | msg_id  | idx:4 | last:4    | fragment data                |
+--------+---------+-------------------+------------------------------+
```

| Field | Size | Meaning |
|---|---|---|
| marker | 1 B | `0xFE`. Receivers MUST test for `0xFE` (and `0xFF`) **before** masking with `& 0x3F`. Dictionary ID `0x3E` becomes reserved and is never assigned, so a masked `0xFE` can never be mistaken for a real dictionary. |
| `msg_id` | 1 B | Per-sender rolling counter, random start at process launch. Receivers key reassembly by `(sender node number, msg_id)`. 256 IDs across a reassembly window of about 2 minutes is ample on LoRa. |
| `idx` | high 4 bits | Fragment index. `0..K-1` are data fragments; `idx == K` is the parity fragment. |
| `last` | low 4 bits | `K - 1`, where `K` is the number of **data** fragments (1..16). With parity, `K ≤ 15`. |
| data | ≤ maxFrame − 3 | A slice of the message blob (§5.2), or parity (§5.3). |

The fragment header has no version field. `0xFE` *is* the version: a future incompatible fragment scheme takes another flags value. `0xFD` is reserved now for repair requests (§10).

### 5.2 Message blob

```
blob = inner || CRC32(inner)          (CRC-32/IEEE, little-endian, 4 bytes)
inner = today's wire payload: [flags 0x00|0x01|0xFF][zstd body | raw protobuf]
```

The blob is cut into `K` slices of exactly `S` bytes, except the last (1..S bytes). `S = maxFrame − 3`, or `maxFrame − 4` when a parity fragment is sent, so that the parity frame also fits (230 / 229 at 233). Receivers do not need to know `S` in advance: every non-last data fragment carries exactly `S` bytes.

Fragmentation applies only when `inner.size > maxFrame`. Below that, the inner payload is sent as-is, so `K ≥ 2` always holds for a fragmented message.

The CRC is per *message* (4 B once), not per fragment. LoRa already has a PHY CRC per frame. This one catches the failures chunking introduces: a `msg_id` reused after a sender restart, or fragments from two messages spliced together. The zstd frame checksum stays off, so wire bytes do not depend on each binding's zstd options. CRC-32 is about 15 lines, table-driven, in each binding, with one shared test vector.

### 5.3 Parity fragment (optional for senders, mandatory for receivers)

```
[0xFE][msg_id][K<<4 | K-1][len(last slice)][XOR of all K slices, each zero-padded to S]
```

If any single data fragment is missing, XOR the parity bytes with every received slice (zero-padded) to rebuild it. Truncate to `len(last slice)` if it was the last one. A single XOR fragment covers one loss. Reed–Solomon could cover more, but it is a lot more code to keep identical across five bindings. Start with XOR, and keep the `idx > K` space reserved in case a stronger code is needed later.

### 5.4 Overhead

From the prototype, with a 233 B frame budget:

| Inner payload | Frames (no parity) | On-air bytes | Overhead | Frames (+ parity) |
|---:|---:|---:|---:|---:|
| 300 B | 2 | 310 | 10 B | 3 |
| 700 B | 4 | 716 | 16 B | 5 |
| 1,200 B | 6 | 1,222 | 22 B | 7 |
| 2,260 B | 10 | 2,294 | 34 B | 11 |
| 3,400 B | 15 | 3,449 | 49 B | 16 |

Header overhead is under 3%. The real cost is parity's one extra frame, and that is what buys loss tolerance.

## 6. Procedures

### 6.1 Sender

```
1. packet  = parse(cotXml, limits = LARGE)                   // see §8
2. inner   = compress(packet)                                 // unchanged one-shot path
3. if inner.size <= maxFrame:        send [inner]             // today's behaviour, byte-identical
4. blob    = inner || crc32(inner)
5. K       = ceil(blob.size / S)
6. if K > maxFragments (policy, ≤16; ≤15 with parity):
       if packet has remarks: retry from 2 without remarks    // existing fallback, generalized
       else: fall back to single-frame limits (truncate + `truncated=true`) and go to 2
7. emit K data fragments (+ 1 parity if enabled) with a fresh msg_id
8. hand frames to the radio back-to-back; the firmware queue paces them
```

Never fragment PLI or other periodic traffic. Apps should set `maxFragments` per event type: 1 for PLI, which preserves today's behaviour exactly, and a small number like 4–8 for shapes, routes and chat. Lower it further on slow presets.

### 6.2 Receiver

```
on frame from node N on port 78:
  0xFF / other flags  -> today's decompress() path
  0xFE                -> reassembler.accept(N, frame, now)
                           -> on completion: inner -> decompress(inner, maxDecompressed = LARGE_CAP)
```

Reassembler rules:

- **Any order, duplicates ignored.** Rebroadcasts, MQTT and store-and-forward can reorder or replay frames.
- **A conflicting `K` for an existing key** means `msg_id` was reused (e.g. the sender restarted). Discard the old partial and start a new one.
- **Completion:** all `K` data slices are present, or `K−1` plus parity (rebuild the missing slice). Then check the CRC. On a mismatch, drop the message.
- **After completion,** remember `(N, msg_id, crc)` for the timeout window, so late duplicate fragments do not open a new partial.
- **Bounds:** at most 4 partial messages per sender, 32 in total, and 16 × 230 B per message. Evict the oldest when full. Timeout is about 120 s from the first fragment by default; apps can scale it with the preset (16 frames on LongSlow is over 2 minutes of airtime).
- **Malformed input** never throws past the reassembler: `idx > K`, a non-last slice whose length differs from its siblings, a frame shorter than 4 B, and so on. Such frames are dropped and logged. The same "MUST NOT crash" rule as `WIRE_FORMAT.md` §10 applies.

### 6.3 Delivery odds

`p` is the chance a single frame arrives:

| p | K | No parity | +1 XOR parity |
|---|---|---:|---:|
| 0.95 | 3 | 86% | 99% |
| 0.95 | 5 | 77% | 97% |
| 0.95 | 10 | 60% | 90% |
| 0.90 | 3 | 73% | 95% |
| 0.90 | 5 | 59% | 89% |
| 0.90 | 10 | 35% | 70% |
| 0.80 | 5 | 33% | 66% |

Recommendation: **parity on by default for broadcasts.** For DMs sent with `want_ack`, firmware already retries each frame, so parity is optional there.

## 7. Resilience invariant, restated

The CLAUDE.md invariant says every *packet* must decode from its own bytes plus the static dictionary. For multi-frame messages it becomes:

> Every **message** (a single frame, or one fragment set) decodes from its own frames plus the static dictionary. There is no state shared between messages. Losing any frame affects only the message it belongs to.

Concretely:

- Compression stays one-shot over the whole message. No zstd streaming API, no adaptive dictionary.
- The reassembler holds raw bytes per `(sender, msg_id)` only. It never shares decoder or compression context between messages, and an evicted or timed-out partial cannot affect another message.
- `ResilienceTest` in every binding gains a case that interleaves fragments of messages A and B, drops some of A's, and asserts that B still decodes to its exact golden.

## 8. Schema and limits impact

- **`atak.proto`: no wire change.** `TAKPacketV2` is unchanged and no new messages are needed. Only comments change: "capped at 32 by the nanopb pool" becomes "single-frame packets are capped at 32…; fragmented messages may carry more."
- **Parser limits become a parameter.** `MAX_VERTICES = 32` and `MAX_ROUTE_LINKS = 16` stay the defaults (single-frame limits). Add a `LARGE` limit set, for example 512 vertices and 64 route links, used when the caller allows fragmentation. Rule: **a single uncompressed `0xFF` frame must never exceed the nanopb pools**, because that is the only form firmware decodes. In practice a packet over the pools always compresses, but the encoder should enforce the rule rather than assume it.
- **Decompression cap becomes a parameter.** Single frames keep `MAX_DECOMPRESSED_SIZE = 4096` (today's bomb guard and tests unchanged). Reassembled messages use `MAX_REASSEMBLED_DECOMPRESSED_SIZE = 16384`, which covers the measured 7.3 KB route with headroom. Reassembled compressed input is bounded at about 3.7 KB by the 16-fragment cap.
- **Dictionaries: no change.** A 2 KB payload compresses fine against the 512 KB dictionary. TypeScript's `windowLog = 21` already covers it.

## 9. SDK API sketch (Kotlin; the other four mirror it)

```kotlin
/** Splits one wire payload into frames. Pure function of (inner, msgId, config): byte-identical across bindings. */
public class TakFragmenter(
    public val maxFrameBytes: Int = 233,
    public val maxFragments: Int = 8,     // policy cap; protocol max is 16 (15 with parity)
    public val parity: Boolean = true,
    private val msgIds: MessageIdSource = MessageIdSource.random(),
) {
    /** [inner] unchanged if it fits; otherwise a fragment set; null if it would exceed [maxFragments]. */
    public fun split(inner: ByteArray): List<ByteArray>?
}

/** Buffers fragments per sender. Clock is injected (commonMain has no wall clock; tests stay deterministic). */
public class TakReassembler(
    public val timeoutMillis: Long = 120_000,
    public val maxPendingPerSender: Int = 4,
    public val maxPendingTotal: Int = 32,
) {
    /** Returns the completed inner wire payload, or null while waiting / on a dropped frame. */
    public fun accept(sender: Long, frame: ByteArray, nowMillis: Long): ByteArray?
    public fun evictExpired(nowMillis: Long)
}

// TakCompressor additions
public fun decompress(wirePayload: ByteArray, maxDecompressedSize: Int = MAX_DECOMPRESSED_SIZE): TakPacketV2Data

/** One-call send path: compress, remarks fallback, then fragment. Generalizes compressWithRemarksFallbackDetailed. */
public fun compressForMesh(packet: TakPacketV2Data, fragmenter: TakFragmenter): MeshEncodeResult
public data class MeshEncodeResult(val frames: List<ByteArray>?, val remarksStripped: Boolean, val fragmented: Boolean)

/** One-call receive path: routes 0xFE to the reassembler, everything else to decompress(). */
public class TakMeshReceiver(compressor: TakCompressor, reassembler: TakReassembler) {
    public fun onPayload(sender: Long, payload: ByteArray, nowMillis: Long): TakPacketV2Data?
}
```

All new code lives in `commonMain` (the fragmenter, reassembler and CRC-32 are pure byte logic), so iOS consumers of the KMP klibs get it too. These are additions only: existing `compress` / `decompress` / `compressWithRemarksFallback*` signatures keep working. They still need a `CHANGELOG.md` entry because `kotlin/api/*.api` moves.

## 10. Later: selective repair (not in v1)

If parity is not enough in the field, add a repair request on the same port:

```
[0xFD][msg_id][missing-bitmap: 2 B]      // sent as a DM to the originating node
```

The sender keeps its last few fragment sets for the timeout window and rebroadcasts the requested slices. That makes them available to every receiver, not only the one who asked. Receivers that hear someone else's request for the same `(sender, msg_id)` hold off, to avoid request storms. This puts cache state on the sender, which is transport state, not compression state, so the invariant in §7 still holds. Reserve `0xFD` in v1 so this can be added without a format break.

## 10a. Out of scope: files, images, data packages

ATAK also moves non-CoT data: data/mission packages (`.zip` + `MANIFEST.xml`), images, marker attachments, and Data Sync mission content. None of it is carried *inside* CoT. ATAK sends a small CoT notice (e.g. a `b-f-t-r` file-transfer request, or a TAK Server content notice) holding a URL and hash, and the receiver fetches the bytes over TCP/HTTP: from the sender's local web server, or from TAK Server (`/Marti/sync/content`). On a mesh with no IP route, that notice is undeliverable as-is.

This design deliberately does not cover those payloads:

- **Size class.** Files are tens of KB to MB. 100 KB is about 430 frames, roughly 16 minutes of airtime per hop on LongFast, against this design's cap of 16 frames / about 3.6 KB.
- **No compression gain.** JPEGs and zips are already compressed, so the TAK dictionary adds nothing.
- **Different reliability model.** A 400-frame transfer cannot rely on parity plus "resend everything". It needs sender-side caching, selective repair (§10, generalized), resumable transfers, and probably DM-only, explicit opt-in delivery.

If mesh file transfer is wanted, it should be a separate bulk-transfer protocol, likely on its own portnum with wider index fields. It could reuse this design's ideas (CRC per message, keying by sender and message ID, bounded reassembly) but not its frame layout or caps. The `b-f-t-r` notice itself is small CoT and already fits a single frame. A receiving app could show it as "file offered, not reachable over mesh" rather than offering a dead URL.

## 11. Testing plan

- **Golden fragment sets** in `testdata/fragment/`, generated by Kotlin from a new `testdata/cot_xml_large/` fixture set (a large freehand, a 40-waypoint route, long remarks, and an inner payload of exactly `maxFrame + 1`). They live in a separate directory so the existing "100% of fixtures under MTU" assertion and the compression report stay as they are.
- **Splitting is byte-identical across bindings.** It is deterministic given the inner bytes, `msg_id` and config, so each binding splits the golden `.bin` and must match the Kotlin frames exactly. That is a stronger check than the zstd layer allows. Compressed bytes still differ per binding, so end-to-end cross-language tests assert decodability plus the size tolerance, as today.
- **Reassembly matrix in every binding:** shuffled order, duplicates, each single drop with parity (recovers), two drops (waits, then times out), CRC mismatch, `K` conflict, `idx > K`, a truncated header, the per-sender and global caps, and timeout eviction.
- **Malformed-input fuzzing** of the reassembler, alongside the existing `testdata/malformed/` suite.
- **ResilienceTest:** the interleaving case from §7.
- **Backward compatibility:** a pre-change decoder fed `0xFE` frames rejects them as an unknown dictionary and does not throw past its existing contract.

## 12. Rollout and open questions

**Compatibility.** Additive on the wire. New senders only fragment what old senders would have truncated or dropped. Old receivers drop fragments cleanly, so they see nothing where today they would see a truncated shape. Apps should note this in release notes. Meshtastic-Android and Meshtastic-Apple need to route port-78 frames through `TakMeshReceiver` (or the reassembler) and pass the sender's node number.

**Open questions**

1. **233 vs 237.** `Data.payload` is 233 B per `mesh.options`. Should the SDK's MTU constant and `WIRE_FORMAT.md` move to 233 as well?
2. **Fragment cap:** 16 (one nibble) or a full byte per field (4 B header, 255 fragments)? 16 frames is already about 35 s per hop on LongFast. A larger cap mostly enables messages the mesh should not carry.
3. **Default `maxFragments` per event type, and per modem preset.** Should the SDK ship a policy table, or leave it entirely to apps?
4. **Should truncation stay the default** (fragmentation opt-in per call) for the first release, so app teams can roll it out deliberately?
5. **Large-mode parser limits:** 512 vertices and 64 links are placeholders. What do ATAK users actually draw?
