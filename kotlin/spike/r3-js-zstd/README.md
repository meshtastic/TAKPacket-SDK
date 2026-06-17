# R3 spike — js/wasm zstd compress byte-compat gate

This spike gates the `js` / `wasmJs` **compress** path (R3/R5) before the SDK
trusts `@bokuweb/zstd-wasm` for it. Decompress on js/wasmJs/wasmWasi does NOT use
this library — it goes through the proven pure-Kotlin `PureZstdDecoder`.

## What it checks

For every dict-0 (non-aircraft) compressed golden fixture, `spike.mjs`:

1. compresses the fixture's golden `.pb` with `@bokuweb/zstd-wasm`
   `compressUsingDict(cctx, pb, dict, 19)`,
2. strips the 4-byte zstd magic to the SDK wire form `[flags=0x00][body]`,
3. re-decompresses (round-trip self-check) with the same library,
4. measures the wire size against the committed golden `.bin` (cross-binding
   tolerance = ratio in `0.5..2.0`, the same `CompatibilityTest` uses),
5. emits the wire frames as base64 so the JVM decoders can cross-decode them.

The JVM cross-decode half (run in a throwaway `jvmTest`) fed each js frame
through **both** `zstd-jni` and `PureZstdDecoder` and asserted byte-identical
`.pb` output.

## How to run

```bash
cd kotlin/spike/r3-js-zstd
npm init -y && npm install @bokuweb/zstd-wasm@0.0.22
node spike.mjs ../../..        # <repo-root>
# then (optional) feed /tmp/.../spike_frames.json through a jvmTest cross-decode
```

## Result — PASS (2026-06-13, @bokuweb/zstd-wasm 0.0.22, node v22)

- **round-trip:** 43/43 fixtures decompress back to identical `.pb` in Node.
- **size tolerance:** 43/43 within `0.5..2.0` (observed ratios ~1.02–1.10).
- **cross-binding decode:** 43/43 js frames decoded by **zstd-jni** AND by
  **PureZstdDecoder** to byte-identical `.pb`.

### Key finding (documented byte/size delta)

`@bokuweb/zstd-wasm@0.0.22`'s simple API (`compressUsingDict`) exposes **no
frame-parameter control** — it cannot suppress `dictID` / `contentSize` /
`checksum`. So every js frame embeds a 4-byte dictionary-ID field (frame header
descriptor `dictIdFlag == 3`), making js wire frames ~4 bytes LARGER than the
JVM/native goldens and therefore **NOT byte-identical** to them.

This is allowed and expected: per the SDK's interop contract the compressed
`.bin` bytes may differ slightly per binding (zstd encoders differ); cross-binding
tests assert **decodability + size tolerance**, never compressed-byte-identity.
The embedded dictID does not break decode — the decoder ignores the frame's
dictID and uses the dict supplied out-of-band (selected by the SDK flags byte).

Conclusion: the `js` / `wasmJs` compress path via `@bokuweb/zstd-wasm` is
**byte-compatible enough to ship** (cross-decodable, within tolerance). The
dependency is committed for js + wasmJs only.
