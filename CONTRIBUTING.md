# Contributing to TAKPacket-SDK

Thanks for helping improve the SDK. This guide covers building, testing, documenting, and
releasing across the five language bindings. For a deep architectural reference (and the rules an
AI coding agent should follow), see [`CLAUDE.md`](CLAUDE.md); this document is its human-facing
companion and does not duplicate it wholesale.

## What this repo is

TAKPacket-SDK converts ATAK Cursor-on-Target (CoT) XML into Meshtastic's `TAKPacketV2` protobuf
and compresses it with zstd dictionary compression for LoRa mesh transport (237-byte MTU,
port 78). **Five parallel implementations** — Kotlin (canonical), Swift, Python, TypeScript, C# —
produce cross-decodable wire payloads, validated by 47 shared test fixtures under
[`testdata/`](testdata/).

> **Interop nuance:** every binding decodes every other binding's frames, and the intermediate
> protobuf goldens (`.pb`) are byte-identical across bindings. The *compressed* bytes (`.bin`) may
> differ slightly per binding (zstd encoders differ), so cross-language tests assert
> **decodability + a size tolerance**, never compressed-byte-identity.

```
protobufs/     Git submodule (meshtastic/protobufs) — proto schema source of truth
dictionaries/  Canonical zstd dictionaries
testdata/      47 CoT XML fixtures + .pb/.bin goldens + sanitizer fixtures
kotlin/ swift/ python/ typescript/ csharp/   the five bindings
```

## Prerequisites

| Binding | Needs |
|---------|-------|
| Kotlin | **JDK 21** (`export JAVA_HOME=…jdk21`). Use the bundled `./gradlew`. |
| Swift | Xcode toolchain. Unit tests need the Xcode `Testing` module (won't run CLI-only). |
| Python | `python/.venv` with `protobuf` + `zstandard`: `python -m venv python/.venv && python/.venv/bin/pip install -e "python[dev]"` |
| TypeScript | Node.js + npm. |
| C# | .NET 9 SDK. |

Always initialize the proto submodule first:

```sh
git submodule update --init --recursive
```

## Build & test

```sh
# Individual bindings
cd kotlin && ./gradlew jvmTest            # needs JAVA_HOME=JDK21 (KMP has no root `test` task — use jvmTest)
cd swift && swift test                    # needs Xcode Testing module
cd python && .venv/bin/python -m pytest -q
cd typescript && npm install && npm run build && npm test
cd csharp && dotnet test

# All at once
./build.sh test
```

## Regenerating golden files (Kotlin is canonical)

Kotlin generates every `.pb` and `.bin` golden and the compression report; the other four
bindings validate against them. After **any** wire / schema / dictionary change:

```sh
cd kotlin && ./gradlew jvmTest --tests "*CompressionTest*generate compression report*"
```

This writes `testdata/golden/*.bin`, `testdata/protobuf/*.pb`, and
`testdata/compression-report.md`. Then re-run the other four suites against the new goldens.
`CompatibilityTest` mismatches *after* such a change are expected — regenerate, don't "fix" the
test.

## Adding a test fixture

Drop a `.xml` file into [`testdata/cot_xml/`](testdata/cot_xml/) — `TestFixtures.kt`
auto-discovers it. Run `gradle jvmTest` to regenerate the goldens, then commit the new `.xml`,
`.bin`, `.pb`, and the updated `compression-report.md`.

### ⚠️ PII / sensitive-data redaction — read before adding any real capture

Real ATAK captures have leaked operator data into fixtures before, and the binary `.pb`/`.bin`
intermediates retain it even after the source XML is fixed. **Never** commit: high-precision
lat/lon that isn't a public landmark, real `ANDROID-<hex>` device IDs, RFC 1918 IPs, MAC
addresses, or real callsigns. Edit a redacted copy in `/tmp/` first, substituting DC-area public
landmarks, sequential `ANDROID-000…0N` IDs, and the RFC 5737 docs IP range, then sanity-grep
before staging. The full substitution table and recovery playbook live in
[`.github/copilot-instructions.md`](.github/copilot-instructions.md) under "PII and test-fixture
sanitization."

## Proto schema changes

The schema lives in the `protobufs` submodule (`meshtastic/atak.proto`). To change it: commit +
push in the submodule, bump the submodule ref here, and regenerate the checked-in bindings
(Swift `atak.pb.swift`, Python `atak_pb2.py`, C# `Atak.cs`; TypeScript loads the `.proto` at
runtime). For **Kotlin**, additionally publish a new `org.meshtastic:protobufs` release and bump
its version in `kotlin/gradle/libs.versions.toml` — Kotlin gets its proto types from that
published artifact, not from local codegen.

## Building the documentation

Each binding generates browsable API docs from its in-source doc comments; the
[`docs.yml`](.github/workflows/docs.yml) workflow assembles them into one
[GitHub Pages site](https://meshtastic.github.io/TAKPacket-SDK/).

```sh
cd kotlin && ./gradlew dokkaGeneratePublicationHtml        # → kotlin/build/dokka/html
cd swift && swift package generate-documentation \         # DocC (macOS)
  --target MeshtasticTAK --transform-for-static-hosting \
  --hosting-base-path TAKPacket-SDK/swift --output-path ../site/swift
cd typescript && npm run docs                              # TypeDoc → typescript/docs
cd python && .venv/bin/pdoc meshtastic_tak -o docs         # pdoc → python/docs
cd csharp && docfx docfx.json                              # DocFX → csharp/_site
```

The same comments ship through each ecosystem's native channel: a Dokka **javadoc jar** on Maven
Central, the **XML doc file** in the NuGet package, the **`.d.ts`** TSDoc in npm, the README as
the **PyPI** long description, and **DocC** Quick Help in Xcode.

> **One-time setup:** to publish the Pages site, a repo admin must set **Settings → Pages →
> Source = "GitHub Actions"**. This can't be automated from a PR.

## Changelog

[`CHANGELOG.md`](CHANGELOG.md) lives at the repo root and covers **all five bindings**, because
one `VERSION` produces one tag and one GitHub Release. It is hand-written in
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) form; the JetBrains
[gradle-changelog-plugin](https://github.com/JetBrains/gradle-changelog-plugin) parses and renders
it and never generates an entry from a commit.

Add an entry under `## [Unreleased]` for anything a consumer would notice — a new or changed
public API in any binding, a behaviour change, a fix to something they could have hit, a security
property, a wire-format or dictionary change. Refactors, test-only changes and CI work need none.

Two rules on top of that:

- **A change that moves `kotlin/api/takpacket-sdk.api` or `kotlin/api/takpacket-sdk.klib.api`
  always needs an entry**, and it goes under `### Breaking` if a consumer has to change code
  rather than just recompile.
- **A change that lands in more than one binding gets one entry**, naming the bindings. The
  bindings ship together; describing the same change five times is how the descriptions diverge.

Groups, in order: `Breaking`, `Added`, `Changed`, `Deprecated`, `Removed`, `Fixed`, `Security`.
`Breaking` leads because the SDK carries committed ABI dumps — the first thing a consumer needs
to know is whether recompiling is enough.

## Releasing

The [`bump-version.yml`](.github/workflows/bump-version.yml) workflow (manual dispatch) stamps the
new version into all five coordinates via [`scripts/bump-version.sh`](scripts/bump-version.sh),
runs `./gradlew patchChangelog` to cut `## [Unreleased]` into a dated `## [x.y.z]` section with
comparison links, and opens a PR. Review the changelog diff in that PR: it is what the GitHub
Release page will say.

Then the [`release.yml`](.github/workflows/release.yml) workflow (manual dispatch, or a `v*` tag)
reads `VERSION` / `kotlin/gradle.properties:VERSION_NAME`, checks all five version sources **and
the changelog section** agree, tests all platforms, publishes the Kotlin artifacts to **Maven
Central**, and cuts a GitHub Release whose body is `./gradlew getChangelog` — not GitHub's
generated commit list, which would describe the release a second time and drift from the
hand-written one. A version with no `## [x.y.z]` section fails the workflow before it publishes:
`getChangelog` silently falls back to the most recent released section, so the check is explicit
rather than left to the plugin.

npm / PyPI / NuGet publishing follows each ecosystem's standard flow. Dictionary retraining is
wire-incompatible — batch it into a minor version bump.

## Commit conventions

- The repo owner prefers to be the commit author — **do not add `Co-Authored-By` trailers.**
- Imperative mood; a detailed body explaining *what* and *why*.
- Don't auto-commit on a contributor's behalf — stage changes and describe them.
