# Large TAK payloads: chunking sketch

**Status:** Draft / proposal. Nothing here is implemented yet.
**Scope:** How to carry a `TAKPacketV2` whose compressed wire payload is larger than one Meshtastic frame (roughly 1 KB to 7 KB), across all five bindings.

## 1. Summary

1. **Keep `TAKPacketV2` unchanged.** Chunking is a thin transport layer *around* today's wire payload, not a schema change. A message that fits one frame goes out exactly as it does today, byte for byte, with zero added overhead.
2. **Compress first, then chunk.** Serialize and compress the whole packet once with the one-shot zstd API (as today), then split the resulting wire payload into fragments. Measured, this needs about half the airtime of splitting the content into many small independent packets (§3).
3. **Mark fragments with flags byte `0xFE` on the same port (78).** Older receivers read `0xFE & 0x3F = 0x3E`, an unknown dictionary, and drop the frame cleanly, so no new port number or firmware change is needed.
4. **5-byte fragment header:** `[0xFE][msg_id:16][code:3 | K-1:5][esi]`. The wire format allows up to 32 *source* fragments, about 7 KB of compressed payload or about 70 s of airtime per hop on LongFast. The SDK's default policy cap is lower (8), and senders opt in per CoT.
5. **Fountain-style erasure coding.** The sender transmits the K source fragments unchanged, then any number R of *repair* fragments. A receiver decodes from **any K distinct fragments**, whichever ones arrive. The code is a systematic MDS code over GF(256), built from a Cauchy matrix (§5.3). Its first repair fragment is exactly an XOR parity. At 90% per-frame delivery, a 5-fragment message goes from 59% delivered with no repair to 89% with one repair fragment and 97% with two. **The same codec also serves file transfer** (§5.5, §10a): a TAK message is just its one-block case.
6. **A CRC-32 per message.** It catches mis-assembled messages, such as a reused `msg_id` or spliced fragments.
7. **Reassembly is bounded and stateless across messages.** Losing fragments costs only that message. It never affects decoding of any other message, which is the resilience invariant restated for multi-frame messages (§7).

Python prototypes of the framing and of the erasure code (encode, Gauss–Jordan decode, CRC and padding) round-trip correctly: any K of K+R fragments, in any order, with duplicates. An exhaustive check confirms the MDS property for K = 2–16 with up to 6 repair rows (about 30,000 K×K submatrices, all invertible), and sampled decodes pass at K = 64 and 128. The Cauchy construction is MDS for any K + R ≤ 256, so K up to 32 is covered by the same argument, and the conformance suite will test it directly.

## 2. Why: how big do "large" payloads get?

Measured with the Python binding and the shipped 512 KB dictionary, with the parser's vertex and route caps lifted. Freehand vertices come from a random walk, and every route waypoint carries a UUID, which is close to incompressible.

**Which binding these numbers come from.** All sizes in this doc were measured with the **Python** binding (libzstd, level 19). Android uses the canonical **Kotlin** binding, whose pure-Kotlin encoder ignores the compression level. On the 47 shipped fixtures, Kotlin's output is about 1% larger in total: 4,447 B against 4,397 B, from `testdata/compression-report.md` versus Python on the same XML. That gap is small, but it can push a message near a boundary into one more fragment. These tables should be re-measured through Kotlin before the numbers are treated as final. An attempt in this environment was blocked by Maven Central rate-limiting.

| Case | CoT XML | Protobuf | Wire (compressed) | Source fragments (220 B) |
|---|---:|---:|---:|---:|
| Freehand, 32 vertices | 1,547 | 267 | 250 | 2 |
| Freehand, 128 vertices | 4,907 | 790 | 704 | 4 |
| Freehand, 256 vertices | 9,387 | 1,484 | 1,220 | 6 |
| Freehand, 512 vertices | 18,347 | 2,971 | 2,259 | 11 |
| Route, 16 waypoints | 2,191 | 975 | 644 | 3 |
| Route, 32 waypoints | 3,935 | 1,870 | 1,216 | 6 |
| Route, 64 waypoints | 7,423 | 3,663 | 2,259 | 11 |
| Route, 128 waypoints | 14,428 | **7,308** | 4,321 | 20 (over cap) |
| Marker + ~600-word remarks | 4,291 | 3,960 | 839 | 4 |

Takeaways:

- Even a 32-vertex freehand with realistic jitter already misses one frame (250 B). Today the parser truncates it and sets `truncated`.
- Routes cost about 35 B per waypoint after compression, because of the UUIDs. A 64-waypoint route needs about 11 fragments.
- **The 4,096-byte decompression cap is hit before the fragment cap.** The 128-waypoint route's protobuf (7.3 KB) is rejected by today's decoder. Fragmented messages need their own, larger cap (§8).

## 3. Options considered

### What gets split

| Option | Airtime | Loss behaviour | Verdict |
|---|---|---|---|
| **A. Semantic split:** N independent `TAKPacketV2`s (e.g. a polygon cut into 24-vertex pieces) | 128 v: 1,089 B / 6 frames<br>256 v: 2,093 B / 11 frames<br>512 v: 4,159 B / 22 frames | Graceful: each piece decodes alone | Rejected as the general mechanism. About 2× the airtime, because every piece repeats the envelope and compresses with no shared context. It also needs per-type merge logic on the ATAK side and does not work for opaque payloads (remarks, `raw_detail`). |
| **B. Chunk, then compress each chunk** | Worst | Same as C | Rejected. It splits protobuf mid-field and loses cross-chunk matches. |
| **C. Compress whole, then chunk** ✅ | 128 v: 662 B / 4 frames<br>256 v: 1,126 B / 6 frames<br>512 v: 2,211 B / 11 frames | Any K of K+R fragments (§5.3) | **Chosen.** Best ratio, payload-agnostic, and reuses `compress()` / `decompress()` unchanged. |

A `TAKPacketV2` has no useful partial form: half a route or half a `raw_detail` is not a valid CoT event. So the "graceful degradation" of A buys little, and repair fragments give better delivery odds for less airtime.

### How fragments are marked

| Option | Notes |
|---|---|
| New portnum | Clean separation, but needs a `portnums.proto` change, firmware and app plumbing, and a second listener in every app. |
| Protobuf envelope (`message TAKFragment { … }`) | Self-describing, but about 9–11 B per frame of tags and varints versus 5 B. That goes against the frame-slimming work already done (the magic strip, which saves 4 B). |
| **Flags byte `0xFE` on port 78** ✅ | Fits the existing `[flags][body]` format. Old receivers reject it as unknown dictionary `0x3E`, a path every binding already handles without crashing. Costs one value from the flags space. |

### Loss recovery

| Option | Fragments needed to decode | Notes |
|---|---|---|
| None | all K | 35% delivery for K=10 at p=0.9. |
| Single XOR parity | any K of K+1 | One loss only. |
| **LT code** (classic fountain) | measured mean **+50–58%** over K at K=5–16; 95th percentile +130% | LT codes are near-optimal only for K in the hundreds or more. At our K they waste airtime, so they're rejected for this size class (see §10a for where they fit). |
| **RaptorQ** (RFC 6330) | ≈K: per the RFC, decoding fails under 1% of the time at exactly K, under 0.01% at K+1, under 0.0001% at K+2 | The strongest candidate for *one code at every size*: a single source block holds up to 56,403 symbols, so files need no blocking. But it is a large spec. Implementations exist in pure Kotlin Multiplatform ([raptorq-kotlin](https://klibs.io/project/andreypfau/raptorq-kotlin)), Rust, Go and C/C++; I found none for Swift, C# or TypeScript, so those bindings would need ports or native bindings. **Phones would need a Qualcomm licence** (§5.6): the royalty-free non-assert covers only devices that implement no wireless wide-area standard. For devices that do, which includes every phone running ATAK or a Meshtastic app, Qualcomm offers licences on its standard terms. At K ≤ 32 it is also strictly weaker than the Cauchy code, because it is not MDS. |
| Random linear code over GF(256) | ≈K (fails ~0.4% of the time at exactly K) | Simple, but probabilistic. |
| **Systematic Cauchy code over GF(256)** ✅ | **exactly K, guaranteed** | MDS: *any* K distinct fragments decode. It is rateless in practice (up to 256−K repair fragments per message). Decoding is a ≤32×32 Gauss–Jordan solve: microseconds, and about 60 lines per binding. XOR parity is its first repair row, so the earlier single-parity design is just the R=1 case. It **also scales to file transfer** by splitting a file into source blocks of up to 128 symbols (§5.5). |

At K ≤ 32, a true fountain code and an MDS code converge. We get the fountain properties that matter here: rateless repair, no per-receiver feedback, any K suffice, and repair fragments mintable by any node that has decoded (§10). And we get them without LT's overhead or RaptorQ's complexity.

**Prior art: the legacy port-257 fountain.** Meshtastic-Apple already ships an LT fountain code for the legacy ATAK plugin protocol on port 257 (`FountainCodec.swift`: 220-byte blocks, with acknowledgements). Android has no equivalent. That path is legacy TAK support and is handled like the rest of it. This design neither replaces nor changes it. It is the new path for `TAKPacketV2` on port 78. The legacy codec is still worth studying for field behaviour. The acknowledgements are what make LT's overhead tolerable there, and they map onto this design's repair-on-demand (§10).

## 4. Constraints this has to respect

- **Frame size: 225 B.** Three numbers matter here.
  - **233 B** is the firmware's ceiling for `Data.payload` (`mesh.options`, `DATA_PAYLOAD_LEN`).
  - **228 B** is what is left for a port-78 payload. Android's `sendData` checks the whole encoded `Data` message (port number, payload and their protobuf framing) against 233.
  - **225 B** is what both clients actually cap a TAK payload at today. The SDK's own `compressWithRemarksFallback` example already uses 225.

  So `maxFrameBytes` defaults to **225 B**, unless the clients' check changes first. Fragments carry 220 B of data each. The SDK's 237 B constant and `WIRE_FORMAT.md` describe a frame that cannot be sent. They move to the real figures in a separate change, ahead of this design (see §12).
- **Broadcast only.** CoT traffic is always sent as a channel broadcast, never as a direct message, so every frame, fragment or repair request in this design is a broadcast, within the 225 B budget above. `maxFrameBytes` stays a parameter anyway, because the clients' cap may change, and PKI-encrypted DMs would need 12 B less (an 8-byte authentication tag plus a 4-byte nonce). Receivers learn the slice size from the fragments, so they never need to know the sender's budget.
- **Resilience invariant:** no cross-packet compression state, one-shot zstd only, static dictionary. Still true here: the whole message is compressed once, and the reassembler only buffers bytes (§7).
- **Firmware does not decode port 78.** `AtakPluginModule` is a passthrough for `ATAK_PLUGIN_V2`: firmware decrypts the packet and hands the bytes to the client unchanged, whatever the flags byte (`0xFF`, a dictionary ID, or `0xFE`). The nanopb pools in `atak.options` (`Route.links max_count:16`, 32 vertices, `GeoChat.message max_size:200`, `raw_detail max_size:220`) only bound the PLI that firmware builds itself in the TAK_TRACKER role. They place no limit on what phones send. See §8.
- **Airtime.** One full frame is about 2.2 s on LongFast, 0.64 s on MediumFast and 9.3 s on LongSlow, per hop, before rebroadcasts. Firmware also throttles on channel utilization. Multi-frame messages must be rare, user-initiated events, never periodic traffic.
- **Bounded radio queue.** The firmware's outgoing queue is small and can reject packets when full. A fragment set must be paced against it (§6.1).

## 5. Wire format

### 5.1 Fragment frame

```
+--------+------------+------------------+---------+---------------------+
| Byte 0 | Bytes 1-2  | Byte 3           | Byte 4  | Bytes 5..N          |
| 0xFE   | msg_id LE  | code:3 | K-1:5   | esi     | symbol data         |
+--------+------------+------------------+---------+---------------------+
```

| Field | Size | Meaning |
|---|---|---|
| marker | 1 B | `0xFE`. Receivers MUST test for `0xFF`, `0xFE` and `0xFD` (§10) **before** masking with `& 0x3F`. Dictionary IDs `0x3E` and `0x3D` are taken out of the dictionary range and never assigned, so a masked `0xFE` or `0xFD` can never be mistaken for a real dictionary. `WIRE_FORMAT.md` §3 currently lists `0x02`–`0x3E` as reserved for dictionaries; it changes to `0x02`–`0x3C` when this ships. |
| `msg_id` | 2 B | Per-sender message ID, little-endian. Receivers key reassembly by `(sender node number, msg_id)`. See the reuse rule below. |
| `code` | high 3 bits | Fragment variant: which repair scheme built the fragment, and whether the header carries extra fields. `0` = systematic Cauchy over GF(256), plain 5-byte header (§5.3). `1` is reserved for helper-relayed repair, whose header adds the originator's 4-byte node number (§10). `2`–`7` are reserved. Receivers drop a fragment with an unknown `code`, so a new variant never needs a new marker byte. |
| `K-1` | low 5 bits | Number of source fragments K, minus 1 (K = 1..32). |
| `esi` | 1 B | Encoding symbol ID. `0..K-1` are source fragments (verbatim slices). `K..255` are repair fragments. |
| data | ≤ maxFrame − 5 | A source slice, or a repair symbol of exactly S bytes. |

**`msg_id` reuse rule.** A sender MUST NOT reuse a `msg_id` within the reassembly window (§6.2). It keeps a rolling 16-bit counter that starts at a random value on every launch, and persists the last value where the platform allows.

- **Wrap-around.** Counter wrap is not a practical concern: 65,536 fragmented messages inside one window is far beyond what the airtime allows.
- **Restarts.** A restart reuses an active ID only by chance: about 1 in 65,536 per message still being reassembled.
- **If a collision happens anyway,** the CRC rejects the mixed result and that message is lost. Nothing wrong is ever delivered, and other messages are unaffected.

An 8-bit ID was considered and rejected. Its 1-in-256 restart collision rate, and a wrap after 256 messages, are too likely for a byte of savings.

### 5.2 Message blob and source fragments

```
blob  = inner || CRC32(inner) || 0x80        (CRC-32/IEEE, little-endian)
inner = today's wire payload: [flags 0x00|0x01|0xFF][zstd body | raw protobuf]
```

- **Slicing.** The blob is cut into K slices of `S = maxFrame − 5` bytes (220 at 225). Source fragment `esi = i` carries slice *i*. The last slice is sent **unpadded**, so no airtime is wasted on padding.
- **Receiving.** The receiver zero-pads every source slice to S. S is learned from any repair fragment or any non-last source fragment. When K=1 with no repair, the single fragment's own length is used.
- **Finding the end.** The trailing `0x80` delimits the blob (ISO/IEC 7816-4 style). After decoding, strip trailing zero bytes, then the `0x80`. This costs 1 byte per message, and it lets a lost *last* slice be rebuilt from repair data without any length field.
- **CRC.** The CRC is per *message* (4 B once), not per fragment; LoRa already has a PHY CRC per frame. This one catches what chunking introduces: a `msg_id` reused after a sender restart, or fragments from two messages spliced together. The zstd frame checksum stays off, so wire bytes do not depend on each binding's zstd options.
- **When to fragment.** Use the fragment path when `inner.size > maxFrame`, **or** when the protobuf exceeds the single-frame decompression cap of 4,096 B (§6.1, §8). In the second case K may be 1.

### 5.3 Repair fragments: systematic Cauchy code over GF(256)

All arithmetic is in GF(2⁸) with the reduction polynomial `0x11D` (the common Reed–Solomon field). Addition is XOR.

Each fragment `esi = e` is a linear combination of the K zero-padded source slices `s_0 … s_{K-1}`:

```
coef(e, j) = 1 if e == j, 0 otherwise                (e < K: source fragment, the identity rows)
coef(e, j) = (K ⊕ j) · (e ⊕ j)⁻¹                     (e ≥ K: repair fragment)

symbol(e)  = Σ_j coef(e, j) · s_j                     (byte-wise, over GF(256))
```

- **This is MDS.** The repair rows are a Cauchy matrix `1/(x_e ⊕ y_j)` with `x_e = e ∈ [K, 255]` and `y_j = j ∈ [0, K-1]`, which never collide. Each column is scaled by a nonzero constant, which keeps every square submatrix nonsingular. So *any* K distinct fragments determine the K source slices.
- **The scaling makes `coef(K, j) = 1`.** Repair fragment `esi = K` is the plain XOR of all source slices, so a binding that only ever sends one repair fragment needs no GF multiplication on encode.
- **Decoding.** Once K distinct ESIs are held, build the K×K coefficient matrix and solve with Gauss–Jordan elimination over GF(256): at most 32×32, with 220-byte right-hand sides. If all source fragments arrived, there is nothing to solve.
- **Determinism.** Coefficients are a pure function of `(e, j, K)`, so every binding produces byte-identical repair fragments from the same blob. Log/exp tables (2 × 256 B) are generated at startup or checked in.

### 5.4 Overhead

Computed with the prototype's framing for a 225 B frame budget:

| Inner payload | Source frames K | On-air bytes (R=0) | Overhead | Frames with R=1 / R=2 |
|---:|---:|---:|---:|---:|
| 300 B | 2 | 315 | 15 B | 3 / 4 |
| 700 B | 4 | 725 | 25 B | 5 / 6 |
| 1,200 B | 6 | 1,235 | 35 B | 7 / 8 |
| 2,260 B | 11 | 2,320 | 60 B | 12 / 13 |
| 3,400 B | 16 | 3,485 | 85 B | 17 / 18 |

Header overhead is under 5% (prototype measured with a 4-byte header; numbers above are recomputed for the 5-byte header). The real cost is the R repair frames, and those are what buy loss tolerance.

### 5.5 One codec for small messages and file transfer

The erasure code is packaged as a standalone codec, separate from the TAK fragment framing, so a future file-transfer protocol (§10a) uses the same implementation, tables and test vectors. A TAK message is the single-block case.

```
FecCodec  (code 0 = systematic Cauchy over GF(256), §5.3)
  partition(objectLength, S, maxBlockK) -> [K_0, K_1, ...]   // balanced: sizes differ by at most 1 (RFC 6330 style)
  encodeSymbol(block: List<ByteArray>, esi: Int): ByteArray  // esi < K_b: source symbol; K_b..255: repair
  BlockDecoder(K_b, S).add(esi, symbol): Boolean             // true once any K_b distinct ESIs are held
  BlockDecoder.result(): List<ByteArray>
```

| | TAK messages (this design) | File transfer (§10a, future) |
|---|---|---|
| Object | `inner ‖ CRC32 ‖ 0x80` | `file ‖ CRC32 ‖ 0x80` (or a hash in a manifest frame) |
| Symbol size S | 220 (225 − 5-byte header) | maxFrame − that protocol's header |
| Max block size | 32 symbols, so always one block | 128 symbols, so several blocks |
| Repair per block | protocol caps a burst at K + R ≤ 48 | up to 256 − K_b (≥ 128) |
| Framing | port 78, `0xFE` header (§5.1) | its own port; header carries object ID, source block number (SBN) and ESI |

**Why 128-symbol blocks for files.** Cauchy rows need K + R ≤ 256 distinct field elements, and 128 leaves room for 100% repair in every block.

- **Decode cost per block:** a ≤128×128 elimination, plus about m × K_b × S table lookups for m missing source symbols. That is a few million lookups, milliseconds on a phone. The prototype decodes any 128 of 256 correctly.
- **Size ceiling:** a 1-byte SBN allows 256 blocks of 128 symbols, about 7 MB, far beyond what LoRa can usefully move.

**What blocking costs** compared with an ideal single-block fountain code, such as RaptorQ. Measured for a 100 KB file (about 440 symbols) at a 99% delivery target, as total frames sent with no feedback at all:

| Per-frame delivery p | One ideal block | Blocks ≤ 64 | Blocks ≤ 128 | Blocks ≤ 200 |
|---|---:|---:|---:|---:|
| 0.9 | 507 | 559 | 536 (+6%) | 527 |
| 0.8 | 578 | 657 | 624 (+8%) | 610 |

So blocking costs about 6–8% more airtime when receivers cannot ask for anything. With repair-on-demand by count, per block (§10), each receiver asks only the blocks it is short on for exactly what it lacks, and the gap closes to the cost of the requests. Transfers of that size take many minutes per hop, so they need that feedback anyway.

**What a single codec buys.** One implementation of about 150 lines per binding: the GF(256) tables, the coefficient formula, partitioning, and one Gauss–Jordan decoder. It also means one conformance suite (table checksums, coefficient vectors, MDS property, any-K decode) shared across all five bindings, so every TAK-fragment test also exercises the file-transfer codec's core. The 3-bit `code` field (§5.1) leaves room for another code later, should feedback-free broadcast of large files ever become a core requirement. RaptorQ is unlikely to be that code, for the patent reasons in §5.6.

### 5.6 Patents: why not RaptorQ

RaptorQ is patented, and the patents passed to Qualcomm when it acquired Digital Fountain. RFC 6330 is where those patents and this design meet. The RFC's own licence lets anyone copy its text, and its code under a BSD licence, but that licence says nothing about patents. Patent terms come only from the holder's IETF IPR disclosures, which the IETF publishes but does not evaluate. Implementing a code *from* RFC 6330 means implementing what those disclosures cover.

**What Qualcomm disclosed.** The current statement for RFC 6330 is [IPR #2554](https://datatracker.ietf.org/ipr/2554/) (March 2015). It updates [#1958](https://datatracker.ietf.org/ipr/1958/) (2013), which updated [#1553](https://datatracker.ietf.org/ipr/1553/) (2011). This session's network policy blocks the IETF site, so the clauses below come from a third party quoting #2554 in [cberner/raptorq#116](https://github.com/cberner/raptorq/issues/116). **They must be checked against #2554 itself before anyone relies on them.** As quoted, Qualcomm will not assert its claims against a device that:

> (a) "fully implements such adopted standards track or experimental document; and (b) does not implement any wireless wide-area standard"

For devices that do implement such a standard, the disclosure says Qualcomm "will offer licenses" under its standard terms.

and it reserves the right:

> to assert its patents "against any product or portion thereof that does not fully implement the IETF standards track or experimental document," and against any party that asserts a patent against Qualcomm products "(including the right to claim past royalties)."

**What that means for this SDK.** This is a design reading, not legal advice.

1. **Phones need a licence on Qualcomm's terms.** Every Android or iOS phone running ATAK, iTAK or a Meshtastic app implements cellular, a wireless wide-area standard, so the royalty-free non-assert does not cover it. For such devices, #2554 says Qualcomm "will offer licenses" under its standard licensing terms (as read from the disclosure by a reviewer on this PR). Phones are therefore not excluded, but they are licensed on Qualcomm's terms rather than free. An open-source SDK cannot take that on for every app that embeds it, so the conclusion is the same.
2. **Radios are unclear.** Meshtastic nodes don't run cellular, but whether LoRa-based hardware, especially hardware that can also bridge LoRaWAN (a "wide area network" by name), counts as implementing "a wireless wide-area standard" is a question for a lawyer.
3. **Partial use is outside the promise.** Using RaptorQ's code inside our own fragment framing, rather than "fully implementing" RFC 6330's object-delivery scheme, may not qualify.
4. **Defensive suspension.** Any organisation shipping it loses the non-assert if it ever asserts a patent against Qualcomm.
5. **Expiry doesn't save us.** Secondary sources claim the original Raptor (RFC 5053) patents have expired, but that later RaptorQ patents run to about 2028–2030. These dates are unverified.

Maintainers of open-source RaptorQ libraries say the same thing. The Rust crate's maintainer: "there are patents that apply to RaptorQ. If you plan to use it for commercial purposes, I recommend you consult a lawyer" ([cberner/raptorq#116](https://github.com/cberner/raptorq/issues/116)). OpenRQ's maintainers note only that Qualcomm has known about their implementation since 2013 and hasn't sued ([OpenRQ#8](https://github.com/openrq-team/OpenRQ/issues/8)), which is no licence.

**The Cauchy GF(256) code is a different lineage.** It is a Reed–Solomon-family code built from a Cauchy matrix. Reed–Solomon codes date from 1960, and Cauchy-matrix erasure codes from mid-1990s work, so any patents on the basic construction expired long ago. The IETF's own Reed–Solomon FEC schemes (RFC 5510) sit in the same family. Nothing in §5.3 comes from RFC 6330. A quick freedom-to-operate check is still sensible before release, but the exposure is of a different order from RaptorQ's.

## 6. Procedures

### 6.1 Sender

```
1. packet = parse(cotXml, limits = fragmentIfOversize ? LARGE : SINGLE_FRAME)   // see §8
   fallbacks = {remarks: available, singleFrameLimits: available}
2. proto  = serialize(packet); inner = compress(packet)         // unchanged one-shot path
3. if inner.size <= maxFrame and proto.size <= 4096:
       send [inner]; done                                       // today's behaviour, byte-identical
4. K = ceil((inner.size + 5) / S)
5. if fragmentIfOversize is false for this CoT
      or K > maxSourceFragments (policy, ≤ 32) or proto.size > LARGE_CAP:
       if remarks available and packet has remarks:
           drop remarks, mark used, go to 2
       if singleFrameLimits available:
           re-parse with single-frame limits (truncate + `truncated=true`), keeping remarks
           dropped if the remarks fallback already ran; mark used, go to 2
       return failure                                           // each fallback runs at most once
6. airtime check: if the region has a duty-cycle limit and (K + R) × frameAirtime would exceed
       the airtime left in the current hour, do not start; report failure (or let the app defer)
7. emit K source fragments + R repair fragments (R from policy, §6.3) with a fresh msg_id
8. send with backpressure:
       before each frame, wait for free space in the radio's outgoing queue (`QueueStatus.free`);
       if a frame is rejected (`QueueStatus.res` ≠ OK), retry it after a short delay;
       the gap between two consecutive fragments MUST stay under the receivers' idle budget
       (§6.2), and the whole burst under its absolute ceiling; if queueing would exceed either,
       stop sending this message and report failure (receivers time out on their own)
```

- **Each fallback runs at most once**, so the loop always terminates. The fallbacks accumulate: when the single-frame re-parse runs after remarks were dropped, the re-parsed packet has its remarks dropped too. The `fragmentIfOversize = false` path uses the same chain, but its packet was parsed with single-frame limits in step 1, so the single-frame fallback has nothing to cut. That leaves exactly today's `compressWithRemarksFallback` behaviour: a CoT with too much geometry is truncated to 32 vertices and keeps its remarks if it then fits, rather than keeping more vertices at the cost of its remarks.
- **Pacing is bounded by the receivers' timers, as far as the sender can see.** The sender can only observe its own wait for the radio queue. It cannot observe when the radio actually transmits a queued frame, or how long relays take to forward it. So the rule above limits only queue wait: the gap between handing two fragments to the radio stays under the idle budget, and the whole hand-off under the ceiling. Delay after the radio accepts a frame (time on air, channel-busy backoff, firmware throttling, relay contention) is absorbed by the receivers' `hopFactor` and `slack` margins (§6.2). The guarantee is therefore conditional: a message is delivered within the receivers' timers only while that later delay stays inside those margins. Past them, the message is lost cleanly, which is the same outcome as a dropped fragment. Field tests should tune `hopFactor` and `slack` per preset.
- **Never start a burst the duty cycle will cut off.** In regions with a duty-cycle limit, such as EU_868, firmware aborts a phone-originated send once the node's hourly airtime budget is spent. A burst that crosses that line is cut off partway, which wastes all the airtime already used. So before step 7 the sender compares the airtime the whole set needs, (K + R) × frame airtime, against what is left in the hour.
  - **What is left in the hour** is the region's limit, minus the radio's reported `air_util_tx` (the percentage of airtime used transmitting in the last hour, from its device metrics), minus the airtime of frames already handed to the radio but not yet sent. `air_util_tx` only counts frames that have been transmitted, so the sender also subtracts what is still queued, including fragments of other sets in flight. It can estimate that from `QueueStatus` (`maxlen − free` queued frames, each costing up to one frame airtime). Skip the check when the region has no limit or `override_duty_cycle` is set.
  - **If the set doesn't fit,** the call fails with a distinct "insufficient airtime" result, so the app can retry later instead of dropping the message.
- **The radio queue is bounded.** Firmware reports `QueueStatus` (`free` / `maxlen` / `res`) after each packet it accepts from the phone. Pushing a whole fragment set without waiting can overflow the queue and lose several fragments before any of them reaches the air. Repair fragments are not meant to cover that loss.
- **Fragmentation is opt-in, per CoT.** Each send carries a `fragmentIfOversize` flag, default `false`. With `false`, the SDK behaves exactly as today for that CoT and never emits a fragment. With `true`, an oversized CoT may be fragmented, up to a `maxSourceFragments` cap. The flag lives on the send call, not on the wire. The app or plugin sets it per CoT: for example `true` for a user-drawn shape, route or long chat message, and never for PLI or other periodic traffic.
  - **With `fragmentIfOversize = false`, the sender never emits a fragment.** The CoT is parsed with single-frame limits, and a packet that cannot take step 3 drops its remarks. If it still doesn't fit, it is dropped and the call reports failure. The SDK never sends a single frame that receivers would reject for exceeding the 4,096-byte cap.
  - **That is today's behaviour, made explicit.** Today such a packet would be sent and then rejected by every receiver. Real PLI protobufs are about 100 B, so in practice this path never triggers for them.
  - **`maxSourceFragments = 1` is not the same as `fragmentIfOversize = false`.** It still allows a one-source-fragment set (plus repair) for a packet that compresses into one frame but whose protobuf is over 4,096 B.

### 6.2 Receiver

```
on frame from node N on port 78:
  0xFF / other flags  -> today's decompress() path
  0xFE                -> reassembler.accept(N, frame, now)
                           -> on completion: inner -> decompress(inner, maxDecompressed = LARGE_CAP)
```

Reassembler rules:

- **Any order, duplicate ESIs ignored.** Rebroadcasts, MQTT and store-and-forward can reorder or replay frames.
- **A conflicting `K` or `code` for an existing key** means `msg_id` was reused (e.g. the sender restarted). Discard the old partial and start a new one.
- **An unknown `code`** means the frame is dropped (forward compatibility).
- **Completion:** the reassembler holds K distinct ESIs. It solves (§5.3), strips the padding and `0x80`, and checks the CRC.
- **A CRC mismatch does not drop the message.** One bad or stale fragment among the K used (for example after a `msg_id` collision) makes the solve produce garbage, but the message may still be recoverable. The partial stays open, and each new ESI triggers a retry: with K + j fragments held, try leaving out one fragment at a time. That is at most K + j solves of at most 32×32, microseconds each, and it recovers from any single bad fragment. More than one bad fragment just runs out the deadline, and the message is dropped then.
- **After completion,** remember `(N, msg_id)` until that message's absolute ceiling, so late fragments and repair frames do not open a new partial. (Fragments carry no CRC, so the key is the ID alone.) The cache is bounded, e.g. 256 entries with least-recently-used eviction. An entry lost early means a late duplicate can be reassembled again; the CRC cache below stops it being delivered twice.
- **Redelivery is suppressed by content.** The receiver also keeps, per sender, the CRC-32s of recently delivered messages, for example the last 64 or the last hour. A reassembled message whose CRC is already in that cache is dropped instead of delivered. This costs 4 B per entry. It is transport-level de-duplication of exact copies only. Deciding whether a *different* update is stale is the app's job (next point).
- **Stale-update protection belongs in the app, not the SDK.** A late message can also be an *older* update with *different* content for the same `uid`, which the CRC cache cannot recognise. The SDK does not judge that. It delivers every reassembled event with its reconstructed `start` (from `start_epoch_lo14`). The app owns its CoT state, so it keeps the newest `start` per `uid` and ignores anything older. `uid` alone is the key, because each `uid` has a single sender. Events without `start_epoch_lo14` fall back to arrival order, as today.
- **Bounds:** at most 4 partial messages per sender and 32 in total; evict the oldest when full. Each partial stores at most **K + 4** distinct fragments, which is at most 36 × 220 B ≈ 7.9 KB. That covers the CRC-retry rule below, which needs only one more fragment than K per bad fragment. Further fragments for a partial that is already full are ignored.
- **Timeouts come from the preset's airtime, and never depend on R.** The receiver cannot know how many repair fragments the sender chose, so its timers rely only on things it can see:
  - **Idle deadline:** `lastNewEsiAt + (L + 1) × frameAirtime(preset) × hopFactor + slack`. It is reset by every new ESI, so a long repair run of any length keeps the partial alive for as long as fragments keep arriving. L is the number of consecutive lost frames tolerated (default 4).
  - **Absolute ceiling**, for memory safety: `firstSeenAt + MAX_BURST × frameAirtime(preset) × hopFactor + slack`. The protocol bounds the initial burst: **a sender MUST send all fragments of a message as one burst (paced only by the radio queue), with K + R ≤ 48.** With K at most 32, that leaves room for 16 repair fragments. `MAX_BURST = 48` therefore covers every permitted sender policy.
  - **Defaults:** the app supplies the preset's frame airtime (about 2.2 s LongFast, 9.3 s LongSlow); hopFactor is 3 and slack is 30 s. On LongSlow, the ceiling is about 23 minutes and the idle gap about 2.8 minutes.
  - **On-demand repair (§10)** later restarts the idle deadline when a receiver sends its request, and the ceiling is reset once per request. That repair is outside the K + R ≤ 48 burst limit but capped separately, at K fragments per message (§10).
- **Malformed input** never throws past the reassembler: `esi < K` with an oversize slice, a repair symbol whose length isn't S, a frame shorter than 6 B, a singular solve (impossible for valid input), and so on. Such frames are dropped and logged. The same "MUST NOT crash" rule as `WIRE_FORMAT.md` §10 applies.

### 6.3 Delivery odds

With an MDS code, a message decodes when at least K of its K+R frames arrive: `P = P[Binomial(K+R, p) ≥ K]`.

| p | K | R=0 | R=1 | R=2 | R=3 |
|---|---|---:|---:|---:|---:|
| 0.9 | 5 | 59% | 89% | 97% | 99% |
| 0.9 | 10 | 35% | 70% | 89% | 97% |
| 0.8 | 5 | 33% | 66% | 85% | 94% |
| 0.8 | 10 | 11% | 32% | 56% | 75% |

Recommendation:

- **Default:** R = max(1, ⌈K/4⌉), so K=5 gets 2 repair fragments and K=10 gets 3.
- **Adaptive senders** can append more repair fragments after the burst, with fresh ESIs and without re-sending anything. Unsolicited extras count against the same K + R ≤ 48 limit, and they help only if they arrive before receivers' idle deadline and absolute ceiling. A fragment arriving after its partial has expired opens a fresh partial. A single late fragment cannot complete it, so it just times out. That costs one partial slot until the idle deadline expires, within the per-sender and global caps.
  - **K distinct late fragments can complete it,** for example after a store-and-forward or MQTT replay that outlasts the completed-message cache. The message can then be reassembled again, but the per-sender CRC cache (§6.2) recognises it and drops it, so it is not delivered twice. If that cache has also lost the entry, the copy is delivered with its original `start` time (`start_epoch_lo14`). An app applying the newest-`start`-per-`uid` rule (§6.2) then ignores it as old. Extras sent in answer to a repair request follow §10 instead.

**Consideration: losses are bursty, not independent.** The table assumes each frame is lost independently with the same probability. LoRa losses cluster instead: collisions, interference, hidden nodes, and a receiver briefly out of range. A fragment burst also competes with its own rebroadcasts on later hops, since fragment n+1 leaves the sender while neighbours are still relaying fragment n. So the effective p for a burst is probably lower than for an isolated packet, and the R=1–3 delivery figures above are best cases. Field tests should measure delivery for whole fragment sets rather than infer it from single-packet loss. Two levers to try:
- **Spacing** fragments slightly apart in time. This reduces self-collision but stretches the burst.
- **More repair** on meshes with many hops.

## 7. Resilience invariant, restated

The CLAUDE.md invariant says every *packet* must decode from its own bytes plus the static dictionary. For multi-frame messages it becomes:

> Every **message** (a single frame, or any K fragments of one fragment set) decodes from its own frames plus the static dictionary. There is no state shared between messages. Losing frames affects only the message they belong to.

Concretely:

- Compression stays one-shot over the whole message. No zstd streaming API, no adaptive dictionary.
- The reassembler holds raw bytes per `(sender, msg_id)` only. It never shares decoder or compression context between messages, and an evicted or timed-out partial cannot affect another message.
- `ResilienceTest` in every binding gains a case that interleaves fragments of messages A and B, drops some of A's, and asserts that B still decodes to its exact golden.

## 8. Schema and limits impact

- **`atak.proto`: no wire change.** `TAKPacketV2` is unchanged and no new messages are needed. Only comments change: "capped at 32 by the nanopb pool; senders MUST truncate" becomes "firmware-built packets are capped at 32 by the nanopb pool; the SDK truncates single-frame packets to 32 by default, and fragmented messages may carry more."
- **Parser limits become a parameter.** `MAX_VERTICES = 32` and `MAX_ROUTE_LINKS = 16` stay the defaults (single-frame limits). Add a `LARGE` limit set, for example 512 vertices and 64 route links, used when the caller allows fragmentation. The single-frame defaults are a wire-size choice, not a firmware limit: firmware never decodes port-78 payloads (§4), so a single frame, compressed or `0xFF`, may carry more than 32 vertices if it fits.
- **Decompression cap becomes a parameter.** Single frames keep `MAX_DECOMPRESSED_SIZE = 4096` (today's bomb guard and tests unchanged). Reassembled messages use `LARGE_CAP = MAX_REASSEMBLED_DECOMPRESSED_SIZE = 32768`. A full 32-fragment message carries about 7 KB of compressed payload, and the measured ratios (about 2:1 for text, 1.7:1 for routes, up to 4.7:1 for remarks) put its protobuf well inside 32 KB. The sender checks the protobuf size *before* choosing the single-frame path (§6.1 step 3): a highly compressible packet whose protobuf exceeds 4,096 B may fit in one frame, but every receiver would reject it, so it goes through the fragment path (K ≥ 1) and gets the larger cap. This check also closes the same gap in today's single-frame path.
- **Dictionaries: no change.** A 2 KB payload compresses fine against the 512 KB dictionary. TypeScript's `windowLog = 21` already covers it.

## 9. SDK API sketch (Kotlin; the other four mirror it)

```kotlin
/** Splits one wire payload into frames. Pure function of (inner, msgId, config): byte-identical across bindings. */
public class TakFragmenter(
    public val maxFrameBytes: Int = 225,             // clients' cap today (§4); a parameter, not a constant
    public val maxSourceFragments: Int = 8,           // cap when a CoT opts in; protocol max is 32
    public val repairPolicy: (k: Int) -> Int = { k -> maxOf(1, (k + 3) / 4) },  // K + R clamped to 32
    private val msgIds: MessageIdSource = MessageIdSource.random(),
) {
    /**
     * [inner] unchanged if it fits one frame AND [protobufSize] <= 4096; otherwise K source + R repair
     * frames (K may be 1 when only the protobuf-size rule forces fragmenting, §5.2); null if
     * K > [maxSourceFragments].
     */
    public fun split(inner: ByteArray, protobufSize: Int): List<ByteArray>?

    /** Additional repair frames for a message already sent (fresh ESIs). Needs the original inner bytes. */
    public fun moreRepair(inner: ByteArray, msgId: Int, firstEsi: Int, count: Int): List<ByteArray>
}

/** Buffers fragments per sender. Clock and airtime are injected (commonMain has no wall clock; tests stay deterministic). */
public class TakReassembler(
    public val frameAirtimeMillis: Long,               // from the modem preset
    public val hopFactor: Int = 3,
    public val toleratedConsecutiveLosses: Int = 4,    // idle deadline (§6.2)
    public val slackMillis: Long = 30_000,
    public val maxPendingPerSender: Int = 4,
    public val maxPendingTotal: Int = 32,
) {
    /** Returns the completed inner wire payload, or null while waiting / on a dropped frame. */
    public fun accept(sender: Long, frame: ByteArray, nowMillis: Long): ByteArray?
    public fun evictExpired(nowMillis: Long)
}

// TakCompressor additions
public fun decompress(wirePayload: ByteArray, maxDecompressedSize: Int = MAX_DECOMPRESSED_SIZE): TakPacketV2Data

/** One-call send path: compress, bounded fallbacks, then fragment. Generalizes compressWithRemarksFallbackDetailed. */
/** Takes the CoT XML: step 1 parses with LARGE limits when fragmentIfOversize is true, otherwise with
 *  single-frame limits. The single-frame fallback (§6.1 step 5) cuts vertices and route links to the
 *  single-frame limits, which gives the same packet as re-parsing the XML with them. An overload
 *  takes an already-parsed packet. */
public fun compressForMesh(
    cotXml: String,
    fragmenter: TakFragmenter,
    fragmentIfOversize: Boolean = false,              // per-CoT opt-in (§6.1); false = today's behaviour
    airtimeLeftMillis: Long? = null,                   // remaining duty-cycle budget, null = no limit (§6.1)
): MeshEncodeResult
public data class MeshEncodeResult(
    val outcome: Outcome,
    val frames: List<ByteArray>?,                      // non-null only for SINGLE_FRAME / FRAGMENTED
    val remarksStripped: Boolean,
    val truncated: Boolean,
) {
    public enum class Outcome {
        SINGLE_FRAME,          // one frame, today's format
        FRAGMENTED,            // a fragment set
        TOO_LARGE,             // still over the limits after every fallback: drop
        INSUFFICIENT_AIRTIME,  // fits, but not in this hour's duty-cycle budget: retry later
    }
}

/** One-call receive path: routes 0xFE to the reassembler, everything else to decompress(). */
public class TakMeshReceiver(compressor: TakCompressor, reassembler: TakReassembler) {
    public fun onPayload(sender: Long, payload: ByteArray, nowMillis: Long): TakPacketV2Data?
}
```

All new code lives in `commonMain` (the fragmenter, reassembler, GF(256) code and CRC-32 are pure byte logic), so iOS consumers of the KMP klibs get it too. These are additions only: existing `compress` / `decompress` / `compressWithRemarksFallback*` signatures keep working. They still need a `CHANGELOG.md` entry because `kotlin/api/*.api` moves.

Pacing frames against `QueueStatus` and the airtime check (§6.1 steps 6 and 8) stay in the apps, which own the radio connection. The SDK returns the frame list; it does not transmit.

## 10. Later: repair on demand (not in v1)

Rateless coding makes repair requests simple. A receiver does not need to say *which* fragments it lacks, only *how many*:

```
[0xFD][origin node number: 4 B LE][msg_id: 2 B][need: 1 B]      // broadcast, like everything else
```

- **The request is a broadcast** and names the originator explicitly, because `msg_id` is only unique per sender.
- **The originator answers** with `need` fresh repair fragments, also broadcast, using ESIs it hasn't sent yet. Every receiver missing *any* fragments benefits from the same frames, so one answer can serve many receivers with different losses. A per-fragment re-send cannot do that.
- **Receivers that hear** someone else's request for the same `(origin, msg_id)` hold off, to avoid request storms. Because requests are broadcast, every receiver can hear them.
- **Amplification guard.** A request costs 8 bytes, and the answer can be many full frames, so the originator caps what it will send. It answers only `msg_id`s it still has cached. It coalesces requests that arrive within a short window into one answer of the largest `need`. It sends at most K repair fragments per message in total across all requests. This guards cheaply against misbehaving or malicious nodes. A hostile node on the channel can already jam it, so this is about not *amplifying* bad traffic, not about defending against an attacker.
- **Helper nodes.** Any node that has *decoded* the message holds the full blob and can mint repair fragments itself, so a nearer node could answer instead of the originator. Helper-minted frames would need to carry the originator's node number, since the receiver keys by the frame's sender. That could be a 4-byte origin field signalled by a reserved `code` value. The same caps apply to helpers. Helpers should pick ESIs at random from the unused range, to make collisions between helpers unlikely.
- **State.** The originator caches its recent blobs for the timeout window. That is transport state, not compression state, so §7 still holds.
- **Reserve `0xFD` and this layout in v1** so it can be added without a format break. Old receivers mask `0xFD` to unknown dictionary `0x3D` and reject it.

Integrity note: the CRC detects accidents, not forgery. Anyone on the channel can inject fragments, exactly as they can inject single frames today; channel encryption remains the guard.

## 10a. Out of scope: files, images, data packages

ATAK also moves non-CoT data: data/mission packages (`.zip` + `MANIFEST.xml`), images, marker attachments, and Data Sync mission content. None of it is carried *inside* CoT. ATAK sends a small CoT notice (e.g. a `b-f-t-r` file-transfer request, or a TAK Server content notice) holding a URL and hash, and the receiver fetches the bytes over TCP/HTTP: from the sender's local web server, or from TAK Server (`/Marti/sync/content`). On a mesh with no IP route, that notice is undeliverable as-is.

This design deliberately does not cover those payloads:

- **Size class.** Files are tens of KB to MB. 100 KB is about 430 frames, roughly 16 minutes of airtime per hop on LongFast, against this design's wire limit of 32 source frames / about 7 KB.
- **No compression gain.** JPEGs and zips are already compressed, so the TAK dictionary adds nothing.
- **Different delivery model.** Bulk transfer needs resumable, sender-cached delivery, repair-on-demand per block, and explicit opt-in. It must work over channel broadcast, not only over DMs. PKI DMs are not post-quantum secure, so some deployments disable them outright, and file transfer cannot depend on them. That is a protocol of its own, even though the **erasure code is shared**. Split into 128-symbol source blocks, the same Cauchy GF(256) codec handles files (§5.5), so no second FEC implementation is needed.

If mesh file transfer is wanted, it should be a separate bulk-transfer protocol, likely on its own portnum with wider K/ESI fields. It would reuse the **`FecCodec` from §5.5 unchanged**, plus this design's ideas (CRC per object, keying by sender and object ID, repair-on-demand by count, bounded reassembly), but not its frame layout or caps. The `b-f-t-r` notice itself is small CoT and already fits a single frame. A receiving app could show it as "file offered, not reachable over mesh" rather than offering a dead URL.

## 11. Testing plan

- **Golden fragment sets** in `testdata/fragment/`, generated by Kotlin from a new `testdata/cot_xml_large/` fixture set. Fixtures: a large freehand, a 40-waypoint route, long remarks, an inner payload of exactly `maxFrame + 1`, and a highly compressible packet whose protobuf exceeds 4,096 B but fits one frame (the K=1 forced path). They live in a separate directory so the existing "100% of fixtures under MTU" assertion and the compression report stay as they are.
- **Fragmenting is byte-identical across bindings.** Slicing and repair symbols are deterministic given the inner bytes, `msg_id` and R, so each binding encodes the golden `.bin` and must match the Kotlin frames exactly, including every repair fragment. That is a stronger check than the zstd layer allows. Compressed bytes still differ per binding, so end-to-end cross-language tests assert decodability plus the size tolerance, as today.
- **Codec conformance suite** (shared with any future file transfer, §5.5): partition sizes, blocks of K_b up to 128 (any-K decode sampled), and GF(256) vectors: shared log/exp table checksums, `coef(e, j, K)` spot values, `coef(K, j, K) == 1`, and an MDS property test over all K×K subsets for small K (sampled for K ≥ 9).
- **Reassembly matrix in every binding:** every K-subset of K+R (sampled for large K), shuffled order, duplicates, loss of the last slice, fewer than K (waits, then times out), CRC mismatch, `K`/`code` conflict, unknown `code`, a truncated header, the per-sender and global caps, idle-deadline extension by a long repair run, ceiling expiry, and a forced `msg_id` collision (CRC rejects, no other message affected).
- **Sender policy:** `fragmentIfOversize = false` never emits `0xFE`, and an oversized packet is then dropped with a failure result rather than sent. A set that doesn't fit the remaining duty-cycle budget is never started.
- **Malformed-input fuzzing** of the reassembler, alongside the existing `testdata/malformed/` suite.
- **ResilienceTest:** the interleaving case from §7.
- **Backward compatibility:** a pre-change decoder fed `0xFE` frames rejects them as an unknown dictionary and does not throw past its existing contract.

## 12. Rollout and open questions

**Compatibility.** Additive on the wire. New senders only fragment what old senders would have truncated or dropped, and only for CoTs that opt in.
- **Receivers ship first.** Neither client has a reassembler yet, and today Android logs a warning with a stack trace for every port-78 frame it can't decode, which would be about 10 per message at the default cap. So the reassembler ships in both clients first. Senders start setting `fragmentIfOversize` only after that.
- **Opt-in by design.** Because the flag is per CoT and defaults to `false`, fragmentation stays opt-in even after rollout; it is never a global switch.

Old decoders reject `0xFE` and `0xFD` frames by throwing their existing "unknown dictionary" exception; I checked that Python raises `ValueError: Unknown dictionary ID: 62`, TypeScript throws `Error`, and Kotlin throws `IllegalArgumentException`. That is safe, but apps must catch it quietly, or a single 20-fragment message turns into 20 user-visible errors or log lines. The new `TakMeshReceiver` routes these frames before decoding, so the issue only affects code that calls `decompress()` directly. Meshtastic-Android and Meshtastic-Apple need to route port-78 frames through `TakMeshReceiver` (or the reassembler), pass the sender's node number, and pace fragment sends against `QueueStatus`.

**Open questions**

1. ~~**233 vs 237.**~~ **Resolved: 225 B.** 233 B is the firmware ceiling for `Data.payload`, 228 B is what Android's `sendData` check leaves for a port-78 payload, and both clients cap at 225 B. 237 describes a frame that cannot be sent. The SDK's MTU constant and `WIRE_FORMAT.md` move to these figures in their own change, ahead of this one.
2. ~~**Source fragment cap.**~~ **Resolved: 3-bit `code`, 5-bit count.** The wire format allows up to 32 source fragments (about 7 KB, about 70 s per hop on LongFast), and 8 `code` values. The SDK's default policy cap stays at 8; going higher is a per-deployment choice that no longer needs a wire change.
3. **Default `maxSourceFragments` and repair policy per event type and modem preset.** Should the SDK ship a policy table, or leave it entirely to apps?
4. ~~**Should truncation stay the default?**~~ **Resolved: yes, per CoT.** Fragmentation is opt-in through `fragmentIfOversize` (default `false`), and receivers ship before any sender opts in (see Compatibility above).
5. **Large-mode parser limits:** 512 vertices and 64 links are placeholders. What do ATAK users actually draw?
6. **Repair on demand (§10):** worth building in v1.x, or wait for field data on loss rates?
7. **One codec at every size:** Cauchy GF(256) with source blocks (recommended, §5.5), or RaptorQ for both? RaptorQ removes the 6–8% blocking cost for feedback-free file broadcast. Against that, it is not MDS at small K, it has no Swift/C#/TypeScript implementation, and phones would need a licence on Qualcomm's terms (§5.6). **Without a licence on Qualcomm's terms for every phone, RaptorQ is off the table**, and the `code 1` slot should not be reserved for it.
8. **Verify IPR #2554 verbatim** (§5.6). The clauses here are third-party quotations, and someone with access to the IETF datatracker should confirm them.
