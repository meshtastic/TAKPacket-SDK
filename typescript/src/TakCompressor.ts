import { Compressor, Decompressor } from "zstd-napi";
import { getTAKPacketV2Type } from "./proto.js";
import {
  DICT_ID_NON_AIRCRAFT, DICT_ID_AIRCRAFT, DICT_ID_UNCOMPRESSED,
  nonAircraftDict, aircraftDict, getDictionary, selectDictId,
} from "./DictionaryProvider.js";
import type { TAKPacketV2 } from "./types.js";

/** Maximum allowed decompressed payload size (bytes). Prevents decompression bombs. */
const MAX_DECOMPRESSED_SIZE = 4096;

/**
 * The 4-byte zstd frame magic (little-endian 0xFD2FB528). Stripped on compress
 * and prepended on decompress: the SDK is both ends and identifies the frame
 * from its own 1-byte flags prefix, so the magic is pure overhead. Done in app
 * code because zstd-napi cannot set the experimental "magicless" format — and
 * doing it manually keeps the wire bytes byte-identical across all 5 language
 * bindings. The magic is a fixed constant, so this stays fully stateless: every
 * frame is independently reconstructable.
 */
const ZSTD_MAGIC = Buffer.from([0x28, 0xb5, 0x2f, 0xfd]);

/**
 * Per-packet compression statistics returned by
 * {@link TakCompressor.compressWithStats}, primarily for reporting and the
 * golden compression-report tooling.
 */
export interface CompressionResult {
  /** Size in bytes of the serialized `TAKPacketV2` protobuf before compression. */
  protobufSize: number;
  /** Size in bytes of the final wire payload (flags byte + body). */
  compressedSize: number;
  /**
   * The mode actually emitted, read back from the wire payload's flags byte:
   * {@link DICT_ID_NON_AIRCRAFT}, {@link DICT_ID_AIRCRAFT}, or
   * {@link DICT_ID_UNCOMPRESSED} when the skip-compress path fired.
   */
  dictId: number;
  /** Human-readable name of {@link dictId} (`"non-aircraft"`, `"aircraft"`, `"uncompressed"`, or `"unknown"`). */
  dictName: string;
  /** The compressed `[flags][body]` wire payload. */
  wirePayload: Buffer;
}

/**
 * Result of {@link TakCompressor.compressWithRemarksFallbackDetailed}.
 *
 * | `wirePayload` | `remarksStripped` | Meaning                              |
 * |---------------|-------------------|--------------------------------------|
 * | Buffer        | false             | Fit as-is, no stripping needed       |
 * | Buffer        | true              | Stripped remarks to make it fit      |
 * | null          | false             | Too big, had no remarks to strip     |
 * | null          | true              | Stripped remarks, still too big      |
 */
export interface RemarksFallbackResult {
  /** The compressed wire bytes, or null if the packet should be dropped. */
  wirePayload: Buffer | null;
  /**
   * true if this call stripped the remarks field before compressing — either
   * successfully (`wirePayload` is non-null) or unsuccessfully (`wirePayload`
   * is null because even stripped it was too big).
   */
  remarksStripped: boolean;
}

/**
 * Encodes a {@link TAKPacketV2} into the on-wire `[flags][zstd body]` payload
 * and decodes it back, using the bundled zstd dictionaries.
 *
 * @remarks
 * **Wire format.** A compressed payload is one flags byte (bits 0–5 = dictionary
 * ID, bits 6–7 reserved/zero) followed by a zstd frame body whose 4-byte magic
 * number has been stripped (re-prepended on decode). When compression would not
 * shrink the packet, {@link compress} instead emits the flags byte `0xFF`
 * followed by the raw protobuf (skip-compress), so tiny payloads never expand.
 * The total wire payload must stay within the 237-byte LoRa MTU; use
 * {@link compressWithRemarksFallback} to enforce that with graceful remarks
 * stripping.
 *
 * **Resilience.** Each packet is compressed as one independent, one-shot zstd
 * frame against the static shipped dictionary — never the streaming API and
 * never any cross-packet/adaptive state — so any single packet decodes on its
 * own from its own bytes plus the dictionary. This is the hard resilience
 * invariant for a lossy LoRa link.
 *
 * **windowLog.** zstd-napi does not auto-size its compression window to a large
 * loaded dictionary, so this class sets `windowLog: 21` *before*
 * `loadDictionary` (and `windowLogMax: 27` on the decompressors) so small
 * inputs can still reference deep matches in the 512 KB dictionary and peer
 * frames with larger windows still decode. Setting `windowLog` after the
 * dictionary is loaded would silently reset the digested dictionary.
 *
 * Dictionaries are loaded and digested lazily on the first `compress`/
 * `decompress` call, then reused for the lifetime of the instance, so reuse one
 * `TakCompressor` rather than constructing one per packet.
 *
 * @example
 * ```ts
 * import { TakCompressor, parseCotXml, buildCotXml } from "@meshtastic/takpacket-sdk";
 *
 * const codec = new TakCompressor();
 *
 * // Encode CoT XML for the mesh:
 * const packet = parseCotXml(cotXmlString);
 * const wire = await codec.compress(packet); // Buffer, ≤ 237 bytes
 *
 * // Decode a received frame back to CoT XML:
 * const decoded = await codec.decompress(wire);
 * const xml = buildCotXml(decoded);
 * ```
 */
export class TakCompressor {
  private compressors: Map<number, Compressor> = new Map();
  private decompressors: Map<number, Decompressor> = new Map();
  private initialized = false;

  private init(): void {
    if (this.initialized) return;

    // IMPORTANT: set parameters BEFORE loadDictionary. zstd digests the dict
    // using the current compression parameters, and `windowLog` is a
    // compression parameter (unlike the frame-only contentSize/checksum/dictID
    // flags, which can be toggled after loading). Setting windowLog AFTER the
    // dict is loaded resets the digested dictionary -> worse ratios and
    // "Data corruption" on decode. Params-first is the zstd-recommended order.
    //
    // windowLog must cover the (large) dictionary so the compressor can
    // reference matches anywhere in it. Unlike zstd-jni/zstandard/ZstdSharp,
    // zstd-napi does NOT auto-size the window from the loaded dict, so for a
    // tiny input it would otherwise use a tiny window and miss deep dict
    // matches (observed: marker_icon_set compressed 2.6x worse). 2^21 = 2MB
    // covers any dict we ship. The frame still encodes only the minimal window
    // actually used, so decoders just need windowLogMax >= that (set below).
    const cNonAc = new Compressor();
    cNonAc.setParameters({ compressionLevel: 19, contentSizeFlag: false, checksumFlag: false, dictIDFlag: false, windowLog: 21 });
    cNonAc.loadDictionary(nonAircraftDict());
    this.compressors.set(DICT_ID_NON_AIRCRAFT, cNonAc);

    // windowLog set here too for parity; the aircraft dict is small so the
    // default window already covers it, but keeping both compressors identical
    // avoids a latent regression if the aircraft dict ever grows.
    const cAc = new Compressor();
    cAc.setParameters({ compressionLevel: 19, contentSizeFlag: false, checksumFlag: false, dictIDFlag: false, windowLog: 21 });
    cAc.loadDictionary(aircraftDict());
    this.compressors.set(DICT_ID_AIRCRAFT, cAc);

    // windowLogMax must be >= the window any peer binding encodes with. The
    // other four bindings auto-size their window to the (large) dict, so their
    // frames can carry windowLog ~20; raise the decoder ceiling so TS can
    // decode every binding's frames. Set BEFORE loadDictionary (same dict-reset
    // hazard as the compressor). Constant => still fully stateless.
    const dNonAc = new Decompressor();
    dNonAc.setParameters({ windowLogMax: 27 });
    dNonAc.loadDictionary(nonAircraftDict());
    this.decompressors.set(DICT_ID_NON_AIRCRAFT, dNonAc);

    const dAc = new Decompressor();
    dAc.setParameters({ windowLogMax: 27 });
    dAc.loadDictionary(aircraftDict());
    this.decompressors.set(DICT_ID_AIRCRAFT, dAc);

    this.initialized = true;
  }

  /**
   * Compress a {@link TAKPacketV2} into a wire payload: `[flags][zstd body]`.
   *
   * Serializes the packet to protobuf, picks the dictionary from the packet's
   * CoT type ({@link selectDictId}), compresses as one independent zstd frame
   * (level 19, content-size/checksum/dictID frame fields off), and strips the
   * 4-byte zstd magic. If the raw protobuf is no larger than the compressed
   * body, emits the skip-compress form `[0xFF][raw protobuf]` instead so the
   * payload never expands.
   *
   * @remarks
   * The caller is responsible for keeping the result within the 237-byte LoRa
   * MTU; this method does not enforce it. Use
   * {@link compressWithRemarksFallback} when you need MTU enforcement.
   * Asynchronous because the protobuf schema is loaded lazily.
   *
   * @param packet - The packet to encode. Field units are wire units: `speed`
   *   in cm/s, `course` in degrees×100, `altitude` in meters HAE (may be
   *   negative), `latitudeI`/`longitudeI` in degrees×1e7, shape radii in cm.
   *   A packet with no payload variant and an `a-f-*` CoT type is an implicit
   *   PLI.
   * @returns A Promise resolving to the compressed wire payload (`Buffer`).
   * @throws If the packet fails protobuf validation, or no compressor exists
   *         for the selected dictionary, or the zstd frame header is not the
   *         expected magic.
   *
   * @example
   * ```ts
   * const codec = new TakCompressor();
   * const wire = await codec.compress({ cotTypeId: 1, latitudeI: 388895000, longitudeI: -770353000 });
   * // wire[0] is the flags byte (dictionary ID, or 0xFF if uncompressed)
   * ```
   */
  async compress(packet: TAKPacketV2): Promise<Buffer> {
    this.init();
    const TAKPacketV2Type = await getTAKPacketV2Type();
    const err = TAKPacketV2Type.verify(packet);
    if (err) throw new Error(`Invalid TAKPacketV2: ${err}`);
    const msg = TAKPacketV2Type.create(packet);
    const protobufBytes = Buffer.from(TAKPacketV2Type.encode(msg).finish());

    const cotTypeId = packet.cotTypeId ?? 0;
    const cotTypeStr = packet.cotTypeStr ?? undefined;
    const dictId = selectDictId(cotTypeId, cotTypeStr);

    const compressor = this.compressors.get(dictId);
    if (!compressor) throw new Error(`No compressor for dict ${dictId}`);

    // One independent frame per packet (one-shot, never streaming).
    const framed = compressor.compress(protobufBytes);
    // Strip the 4-byte magic (see ZSTD_MAGIC). Defensive: the frame must start
    // with the known magic or our strip/prepend contract is broken.
    if (framed.length < 4 || framed[0] !== ZSTD_MAGIC[0] || framed[1] !== ZSTD_MAGIC[1]
      || framed[2] !== ZSTD_MAGIC[2] || framed[3] !== ZSTD_MAGIC[3]) {
      throw new Error("Unexpected zstd frame header (magic mismatch)");
    }
    const body = framed.subarray(4);

    // Skip compression when it doesn't pay (tiny payloads where frame +
    // dict-reference overhead exceeds entropy saved). The 0xFF uncompressed path
    // is already understood by every decoder. Ties -> raw (cheaper decode).
    if (protobufBytes.length <= body.length) {
      const raw = Buffer.alloc(1 + protobufBytes.length);
      raw[0] = DICT_ID_UNCOMPRESSED;
      protobufBytes.copy(raw, 1);
      return raw;
    }

    const wire = Buffer.alloc(1 + body.length);
    wire[0] = dictId & 0x3f;
    body.copy(wire, 1);
    return wire;
  }

  /**
   * Decompress a wire payload back into a {@link TAKPacketV2}.
   *
   * Reads the flags byte: `0xFF` means the body is raw protobuf; otherwise the
   * low 6 bits select the dictionary, the stripped 4-byte zstd magic is
   * re-prepended, and the body is decompressed with that dictionary. The
   * decompressed size is capped at 4096 bytes as a decompression-bomb guard
   * before the protobuf is parsed.
   *
   * @remarks
   * Reserved flag bits are ignored (the dictionary ID is masked with `& 0x3F`).
   * Asynchronous because the protobuf schema is loaded lazily.
   *
   * @param wirePayload - A received `[flags][body]` payload (must be ≥ 2 bytes).
   * @returns A Promise resolving to the decoded packet. Field units are wire
   *   units (see {@link compress}).
   * @throws If the payload is shorter than 2 bytes, the dictionary ID is
   *         unknown, zstd decompression fails, the decompressed size exceeds
   *         4096 bytes, or the protobuf fails to parse.
   *
   * @example
   * ```ts
   * const codec = new TakCompressor();
   * const packet = await codec.decompress(receivedWireBuffer);
   * ```
   */
  async decompress(wirePayload: Buffer): Promise<TAKPacketV2> {
    this.init();
    if (wirePayload.length < 2) throw new Error(`Payload too short: ${wirePayload.length}`);

    const flagsByte = wirePayload[0];
    const compressedBytes = wirePayload.subarray(1);

    let protobufBytes: Buffer;
    if (flagsByte === DICT_ID_UNCOMPRESSED) {
      protobufBytes = Buffer.from(compressedBytes);
    } else {
      const dictId = flagsByte & 0x3f;
      const decompressor = this.decompressors.get(dictId);
      if (!decompressor) throw new Error(`Unknown dict ID: ${dictId}`);
      try {
        // Re-attach the 4-byte magic stripped on compress (see ZSTD_MAGIC),
        // yielding a standard frame the stock decoder accepts. The supplied
        // dict (not a frame-embedded dict ID) selects the dictionary.
        const restored = Buffer.concat([ZSTD_MAGIC, compressedBytes]);
        protobufBytes = decompressor.decompress(restored);
      } catch (e) {
        throw new Error(`Zstd decompression failed: ${e}`);
      }
    }

    if (protobufBytes.length > MAX_DECOMPRESSED_SIZE) {
      throw new Error(`Payload size ${protobufBytes.length} exceeds limit ${MAX_DECOMPRESSED_SIZE}`);
    }

    try {
      const TAKPacketV2Type = await getTAKPacketV2Type();
      const msg = TAKPacketV2Type.decode(protobufBytes);
      // defaults:true materializes proto3 zero values (0, "", false) for
      // absent scalar fields, matching what the Wire/protobuf runtimes in the
      // other bindings return. protobufjs 8 elides default values on encode
      // (7.x wrote zero-valued own properties to the wire), so without this
      // option those fields come back undefined instead. Oneof members are
      // never defaulted, so payload_variant is unaffected.
      return TAKPacketV2Type.toObject(msg, { defaults: true }) as TAKPacketV2;
    } catch (e) {
      throw new Error(`Protobuf parsing failed: ${e}`);
    }
  }

  /**
   * Compress a packet, stripping remarks if the result exceeds maxWireBytes.
   *
   * First attempts compression with remarks intact. If the wire payload
   * fits within maxWireBytes, returns it as-is. Otherwise, clears the
   * remarks field and re-compresses. Returns null if even the stripped
   * packet exceeds the limit (caller should drop the packet).
   *
   * This is a thin wrapper over {@link compressWithRemarksFallbackDetailed}
   * that discards the `remarksStripped` flag. Use the Detailed variant if you
   * need to tell "fit as-is", "fit after strip", and "dropped" apart — e.g.
   * for observability or metrics.
   *
   * @param packet       The packet with remarks populated.
   * @param maxWireBytes Maximum allowed wire payload size (e.g. 225).
   * @returns The wire payload, or null if the packet is too large even
   *          without remarks.
   */
  async compressWithRemarksFallback(
    packet: TAKPacketV2,
    maxWireBytes: number,
  ): Promise<Buffer | null> {
    const result = await this.compressWithRemarksFallbackDetailed(packet, maxWireBytes);
    return result.wirePayload;
  }

  /**
   * Compress a packet, stripping remarks if needed, and return a detailed
   * result that distinguishes the four possible outcomes (see
   * {@link RemarksFallbackResult}). Callers that want to log/meter "how often
   * does remarks-stripping save a packet" should use this variant;
   * {@link compressWithRemarksFallback} loses the distinction.
   */
  async compressWithRemarksFallbackDetailed(
    packet: TAKPacketV2,
    maxWireBytes: number,
  ): Promise<RemarksFallbackResult> {
    const full = await this.compress(packet);
    if (full.length <= maxWireBytes) {
      return { wirePayload: full, remarksStripped: false };
    }

    const remarks = packet.remarks ?? "";
    if (!remarks) {
      return { wirePayload: null, remarksStripped: false };
    }

    const stripped = await this.compress({ ...packet, remarks: "" });
    if (stripped.length <= maxWireBytes) {
      return { wirePayload: stripped, remarksStripped: true };
    }
    return { wirePayload: null, remarksStripped: true };
  }

  /**
   * Compress a packet and report its sizes and emitted mode.
   *
   * Equivalent to {@link compress} plus a {@link CompressionResult} describing
   * the protobuf size, final wire size, and the dictionary/mode actually
   * emitted (read back from the flags byte, so it reflects a skip-compress
   * `0xFF` fallback). Used by the golden compression-report tooling.
   *
   * @param packet - The packet to encode (see {@link compress} for units).
   * @returns A Promise resolving to the compression statistics, including the
   *          wire payload itself.
   * @throws The same conditions as {@link compress}.
   */
  async compressWithStats(packet: TAKPacketV2): Promise<CompressionResult> {
    const TAKPacketV2Type = await getTAKPacketV2Type();
    const msg = TAKPacketV2Type.create(packet);
    const protobufBytes = TAKPacketV2Type.encode(msg).finish();

    const wirePayload = await this.compress(packet);
    // Report the ACTUAL emitted mode from the flags byte — skip-compress may
    // have emitted 0xFF (uncompressed) when compression didn't pay.
    const flags = wirePayload[0];
    const dictId = flags === DICT_ID_UNCOMPRESSED ? flags : (flags & 0x3f);

    return {
      protobufSize: protobufBytes.length,
      compressedSize: wirePayload.length,
      dictId,
      dictName: dictId === DICT_ID_NON_AIRCRAFT ? "non-aircraft"
        : dictId === DICT_ID_AIRCRAFT ? "aircraft"
        : dictId === DICT_ID_UNCOMPRESSED ? "uncompressed" : "unknown",
      wirePayload,
    };
  }
}
