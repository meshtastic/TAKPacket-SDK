/**
 * TAKPacketV2 protobuf schema defined using protobufjs.
 * This matches the wire format from protobufs/meshtastic/atak.proto.
 */
import protobuf from "protobufjs";
import path from "path";
import fs from "fs";
import { createRequire } from "module";
import { fileURLToPath } from "url";

const __dirname = path.dirname(fileURLToPath(import.meta.url));

const PROTOBUFS_ROOT = path.resolve(__dirname, "../../protobufs");

// protobufjs ships google/protobuf/*.proto on disk, but only a subset is in its
// `common` bundle - descriptor.proto, which field_metadata.proto imports, is not.
const PROTOBUFJS_ROOT = path.dirname(createRequire(import.meta.url).resolve("protobufjs"));

let _root: protobuf.Root | null = null;

// Imports inside the schema are repo-root relative ("meshtastic/field_metadata.proto"),
// but protobufjs resolves them against the importing file's directory by default.
function resolveImport(_origin: string, target: string): string {
  if (path.isAbsolute(target)) return target;
  const fromRepo = path.resolve(PROTOBUFS_ROOT, target);
  if (fs.existsSync(fromRepo)) return fromRepo;
  const fromProtobufjs = path.resolve(PROTOBUFJS_ROOT, target);
  if (fs.existsSync(fromProtobufjs)) return fromProtobufjs;
  return fromRepo;
}

export async function loadProto(): Promise<protobuf.Root> {
  if (_root) return _root;
  const root = new protobuf.Root();
  root.resolvePath = resolveImport;
  _root = await root.load(path.resolve(PROTOBUFS_ROOT, "meshtastic/atak.proto"));
  return _root;
}

export async function getTAKPacketV2Type(): Promise<protobuf.Type> {
  const root = await loadProto();
  return root.lookupType("meshtastic.TAKPacketV2");
}

export async function getGeoChatType(): Promise<protobuf.Type> {
  const root = await loadProto();
  return root.lookupType("meshtastic.GeoChat");
}

export async function getAircraftTrackType(): Promise<protobuf.Type> {
  const root = await loadProto();
  return root.lookupType("meshtastic.AircraftTrack");
}
