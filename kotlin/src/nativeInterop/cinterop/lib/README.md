# Vendored static `libzstd.a` per Kotlin/Native target

The native `ZstdCodec` actual binds libzstd through Kotlin/Native cinterop
(`../zstd.def`). Per **R6** we link libzstd **statically** so the produced klibs
are self-contained and consumers need no `-lzstd` / `libzstd-dev` at link time.

Each Kotlin/Native target needs its own `libzstd.a` built for that target's
architecture + sysroot. They live here, one directory per **konan target name**:

```
lib/
  ios_arm64/libzstd.a
  ios_simulator_arm64/libzstd.a
  ios_x64/libzstd.a
  macos_arm64/libzstd.a              <- host; obtained locally (Homebrew zstd 1.5.7), NOT committed
  tvos_arm64/libzstd.a
  tvos_simulator_arm64/libzstd.a
  linux_x64/libzstd.a
  linux_arm64/libzstd.a
  mingw_x64/libzstd.a
```

`build.gradle.kts` points each target's cinterop `libraryPaths` at the matching
directory and the header search path at `../include`. The archive **must** match
the vendored header version (`../include/zstd.h`, currently **v1.5.7**) so the
ABI matches.

> **No `libzstd.a` archive is committed** — `*.a` is gitignored (see
> `.gitignore` in this directory), and each target dir holds only a `.gitkeep`
> placeholder. On a macOS dev host the `macos_arm64/libzstd.a` is obtained
> locally (Homebrew `zstd` 1.5.7, a real arm64 macOS archive — `fetchZstdStatic`
> reports its presence) so a genuine host `cinteropZstdMacosArm64` +
> `compileKotlinMacosArm64` build runs. The archives for all targets — including
> the host — are provisioned at build time by `fetchZstdStatic` (see below)
> rather than checked in, because a single macOS dev box cannot cross-build
> every sysroot's static lib and binaries do not belong in git.

## How each archive is produced

All archives are built from the **same upstream zstd release** that matches the
vendored header version (pin `ZSTD_TAG=v1.5.7`). Source:
<https://github.com/facebook/zstd/releases/tag/v1.5.7>.

zstd's `lib/` builds a static `libzstd.a` with a plain make invocation; cross
targets need the right toolchain/sysroot passed as `CC`/`AR`/`CFLAGS`.

### Apple targets (built on a macOS host with Xcode)

Use the Xcode SDKs; build with the per-target arch + `-isysroot`.

```bash
ZSTD=zstd-1.5.7
build_apple() {            # $1=konan dir  $2=sdk  $3=arch  [$4=min-version flag]
  make -C "$ZSTD/lib" clean
  make -C "$ZSTD/lib" libzstd.a \
    CC="$(xcrun --sdk $2 -f clang)" \
    AR="$(xcrun --sdk $2 -f ar)" \
    CFLAGS="-O2 -arch $3 -isysroot $(xcrun --sdk $2 --show-sdk-path) ${4:-}"
  cp "$ZSTD/lib/libzstd.a" "lib/$1/libzstd.a"
}
build_apple macos_arm64           macosx          arm64  "-mmacosx-version-min=11.0"
build_apple ios_arm64             iphoneos        arm64  "-miphoneos-version-min=14.0"
build_apple ios_simulator_arm64   iphonesimulator arm64  "-mios-simulator-version-min=14.0"
build_apple ios_x64               iphonesimulator x86_64 "-mios-simulator-version-min=14.0"
build_apple tvos_arm64            appletvos       arm64  "-mtvos-version-min=14.0"
build_apple tvos_simulator_arm64  appletvsimulator arm64 "-mtvos-simulator-version-min=14.0"
```

(On a macOS host the local `macos_arm64/libzstd.a` can just be copied from
Homebrew's `$(brew --prefix zstd)/lib/libzstd.a`, which is equivalent to the
`build_apple macos_arm64 …` output for the host arch. It is not committed.)

### Linux targets

`linux_x64` builds natively on x86_64 Linux; `linux_arm64` needs a cross GCC
(`aarch64-linux-gnu-gcc`).

```bash
make -C zstd-1.5.7/lib clean && make -C zstd-1.5.7/lib libzstd.a
cp zstd-1.5.7/lib/libzstd.a lib/linux_x64/libzstd.a

make -C zstd-1.5.7/lib clean
make -C zstd-1.5.7/lib libzstd.a CC=aarch64-linux-gnu-gcc AR=aarch64-linux-gnu-ar
cp zstd-1.5.7/lib/libzstd.a lib/linux_arm64/libzstd.a
```

### Windows (mingwX64)

Cross-build with the MinGW-w64 GCC, or download the prebuilt `libzstd_static.lib`
from the zstd release's Windows assets and rename to `libzstd.a`.

```bash
make -C zstd-1.5.7/lib clean
make -C zstd-1.5.7/lib libzstd.a CC=x86_64-w64-mingw32-gcc AR=x86_64-w64-mingw32-ar
cp zstd-1.5.7/lib/libzstd.a lib/mingw_x64/libzstd.a
```

## Automation

`../../../../build.gradle.kts` registers a `fetchZstdStatic` task that documents
and (in CI) drives the above. It checks which `lib/<target>/libzstd.a` archives
are present and prints the exact build/fetch commands for any that are missing.
Wire the real downloads/builds into your CI before `cinterop…`/`compileKotlin…`
for the non-host targets.
