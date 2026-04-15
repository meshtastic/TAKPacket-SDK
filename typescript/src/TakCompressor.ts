import { Compressor, Decompressor } from "zstd-napi";
import { getTAKPacketV2Type } from "./proto.js";
import {
  DICT_ID_NON_AIRCRAFT, DICT_ID_AIRCRAFT, DICT_ID_UNCOMPRESSED,
  nonAircraftDict, aircraftDict, getDictionary, selectDictId,
} from "./DictionaryProvider.js";

/** Maximum allowed decompressed payload size (bytes). Prevents decompression bombs. */
const MAX_DECOMPRESSED_SIZE = 4096;

export interface CompressionResult {
  protobufSize: number;
  compressedSize: number;
  dictId: number;
  dictName: string;
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

export class TakCompressor {
  private compressors: Map<number, Compressor> = new Map();
  private decompressors: Map<number, Decompressor> = new Map();
  private initialized = false;

  private init(): void {
    if (this.initialized) return;

    const cNonAc = new Compressor();
    cNonAc.loadDictionary(nonAircraftDict());
    cNonAc.setParameters({ compressionLevel: 19 });
    this.compressors.set(DICT_ID_NON_AIRCRAFT, cNonAc);

    const cAc = new Compressor();
    cAc.loadDictionary(aircraftDict());
    cAc.setParameters({ compressionLevel: 19 });
    this.compressors.set(DICT_ID_AIRCRAFT, cAc);

    const dNonAc = new Decompressor();
    dNonAc.loadDictionary(nonAircraftDict());
    this.decompressors.set(DICT_ID_NON_AIRCRAFT, dNonAc);

    const dAc = new Decompressor();
    dAc.loadDictionary(aircraftDict());
    this.decompressors.set(DICT_ID_AIRCRAFT, dAc);

    this.initialized = true;
  }

  /** Compress a TAKPacketV2 object into wire payload: [flags][zstd compressed protobuf] */
  async compress(packet: Record<string, unknown>): Promise<Buffer> {
    this.init();
    const TAKPacketV2 = await getTAKPacketV2Type();
    const err = TAKPacketV2.verify(packet);
    if (err) throw new Error(`Invalid TAKPacketV2: ${err}`);
    const msg = TAKPacketV2.create(packet);
    const protobufBytes = Buffer.from(TAKPacketV2.encode(msg).finish());

    const cotTypeId = (packet.cotTypeId as number) ?? 0;
    const cotTypeStr = (packet.cotTypeStr as string) ?? undefined;
    const dictId = selectDictId(cotTypeId, cotTypeStr);

    const compressor = this.compressors.get(dictId);
    if (!compressor) throw new Error(`No compressor for dict ${dictId}`);

    const compressed = compressor.compress(protobufBytes);
    const wire = Buffer.alloc(1 + compressed.length);
    wire[0] = dictId & 0x3f;
    compressed.copy(wire, 1);
    return wire;
  }

  /** Decompress wire payload back to a TAKPacketV2 object. */
  async decompress(wirePayload: Buffer): Promise<Record<string, unknown>> {
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
        protobufBytes = decompressor.decompress(Buffer.from(compressedBytes));
      } catch (e) {
        throw new Error(`Zstd decompression failed: ${e}`);
      }
    }

    if (protobufBytes.length > MAX_DECOMPRESSED_SIZE) {
      throw new Error(`Payload size ${protobufBytes.length} exceeds limit ${MAX_DECOMPRESSED_SIZE}`);
    }

    try {
      const TAKPacketV2 = await getTAKPacketV2Type();
      const msg = TAKPacketV2.decode(protobufBytes);
      return TAKPacketV2.toObject(msg) as Record<string, unknown>;
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
    packet: Record<string, unknown>,
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
    packet: Record<string, unknown>,
    maxWireBytes: number,
  ): Promise<RemarksFallbackResult> {
    const full = await this.compress(packet);
    if (full.length <= maxWireBytes) {
      return { wirePayload: full, remarksStripped: false };
    }

    const remarks = (packet.remarks as string) ?? "";
    if (!remarks) {
      return { wirePayload: null, remarksStripped: false };
    }

    const stripped = await this.compress({ ...packet, remarks: "" });
    if (stripped.length <= maxWireBytes) {
      return { wirePayload: stripped, remarksStripped: true };
    }
    return { wirePayload: null, remarksStripped: true };
  }

  /** Compress with stats for reporting. */
  async compressWithStats(packet: Record<string, unknown>): Promise<CompressionResult> {
    const TAKPacketV2 = await getTAKPacketV2Type();
    const msg = TAKPacketV2.create(packet);
    const protobufBytes = TAKPacketV2.encode(msg).finish();

    const wirePayload = await this.compress(packet);
    const cotTypeId = (packet.cotTypeId as number) ?? 0;
    const cotTypeStr = (packet.cotTypeStr as string) ?? undefined;
    const dictId = selectDictId(cotTypeId, cotTypeStr);

    return {
      protobufSize: protobufBytes.length,
      compressedSize: wirePayload.length,
      dictId,
      dictName: dictId === DICT_ID_NON_AIRCRAFT ? "non-aircraft" : dictId === DICT_ID_AIRCRAFT ? "aircraft" : "unknown",
      wirePayload,
    };
  }
}
