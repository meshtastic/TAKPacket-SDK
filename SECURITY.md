# Security Policy

## Reporting a vulnerability

Please report security vulnerabilities **privately** — do not open a public issue.

File a private [GitHub Security Advisory](https://github.com/meshtastic/TAKPacket-SDK/security/advisories/new) on this repository. We aim to acknowledge reports within a few days and will coordinate a fix and disclosure with you. For broader Meshtastic security matters, see <https://meshtastic.org>.

## Supported versions

TAKPacket-SDK is pre-1.0; only the latest published release receives security fixes. There is no LTS branch.

## Scope

In scope: this SDK's own code across its language bindings — CoT XML parsing, `TAKPacketV2` protobuf conversion, the wire framing, and zstd dictionary handling. Out of scope: vulnerabilities in third-party dependencies (report those upstream) and the Meshtastic firmware or on-air protocol itself.
