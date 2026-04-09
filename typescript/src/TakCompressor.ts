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
   * Compress a packet using whichever format yields the smaller wire payload:
   * the fully-typed TAKPacketV2 (the default {@link compress} path) or a
   * `raw_detail` fallback carrying the original `<detail>` bytes.
   *
   * On every bundled fixture the typed path wins — delta-encoded geometry
   * and palette-enum colors compress much tighter than raw XML tag names,
   * even with a 16KB zstd dictionary.  This method is a safety net for CoT
   * types the structured parser can't decompose or for shapes with geometry
   * beyond `MAX_VERTICES` that would otherwise be silently truncated.
   *
   * The fallback packet has every detail-derived top-level field (callsign,
   * takVersion, …) stripped so the `<detail>` content isn't duplicated on
   * the wire; the receiver re-parses those fields out of the raw bytes.
   *
   * @param packet          Typed-variant packet from `parseCotXml`.
   * @param rawDetailBytes  Raw `<detail>` inner bytes from
   *                        {@link extractRawDetailBytes}.
   * @returns Whichever wire payload is smaller.  Ties go to the typed packet
   *          since it preserves strong typing on the receiver side.
   */
  async compressBestOf(
    packet: Record<string, unknown>,
    rawDetailBytes: Buffer,
  ): Promise<Buffer> {
    const typedWire = await this.compress(packet);
    if (rawDetailBytes.length === 0) return typedWire;

    // Build the raw_detail fallback packet by cloning the typed packet and
    // stripping every detail-derived top-level field.  Leaves the envelope
    // (uid, cotTypeId, how, stale, lat/lon/alt) intact.  Setting rawDetail
    // sets the oneof and clears the previous payload_variant case.
    const rawPacket: Record<string, unknown> = { ...packet };
    delete rawPacket.callsign;
    delete rawPacket.team;
    delete rawPacket.role;
    delete rawPacket.battery;
    delete rawPacket.speed;
    delete rawPacket.course;
    delete rawPacket.deviceCallsign;
    delete rawPacket.takVersion;
    delete rawPacket.takDevice;
    delete rawPacket.takPlatform;
    delete rawPacket.takOs;
    delete rawPacket.endpoint;
    delete rawPacket.phone;
    delete rawPacket.geoSrc;
    delete rawPacket.altSrc;
    // Clear the existing oneof variant before setting raw_detail.
    delete rawPacket.pli;
    delete rawPacket.chat;
    delete rawPacket.aircraft;
    delete rawPacket.shape;
    delete rawPacket.marker;
    delete rawPacket.rab;
    delete rawPacket.route;
    rawPacket.rawDetail = rawDetailBytes;

    const rawWire = await this.compress(rawPacket);
    return rawWire.length < typedWire.length ? rawWire : typedWire;
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
