/**
 * TAKPacketV2 protobuf schema defined using protobufjs.
 * This matches the wire format from protobufs/meshtastic/atak.proto.
 */
import protobuf from "protobufjs";
import path from "path";
import { fileURLToPath } from "url";

const __dirname = path.dirname(fileURLToPath(import.meta.url));

let _root: protobuf.Root | null = null;

export async function loadProto(): Promise<protobuf.Root> {
  if (_root) return _root;
  _root = await protobuf.load(path.resolve(__dirname, "../../protobufs/meshtastic/atak.proto"));
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
