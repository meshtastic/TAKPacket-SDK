# Releasing

One version covers all five bindings. `.github/workflows/release.yml` publishes
`org.meshtastic:takpacket-sdk` to Maven Central with the vanniktech maven-publish plugin, pushes
the `vX.Y.Z` tag that SwiftPM resolves, and attaches the Python wheel and sdist, npm tarball,
NuGet package, Swift source zip and JVM jar to the GitHub Release. Nothing publishes to PyPI, npm
or NuGet. JitPack (`com.github.meshtastic.TAKPacket-SDK`) builds from the tag as a fallback.

## Secrets

`SIGNING_KEY` (the in-memory GPG key, no password), `OSSRH_USERNAME` and `OSSRH_PASSWORD`
(Central Portal credentials), passed as the vanniktech `ORG_GRADLE_PROJECT_*` properties.

## Cutting a release

1. Pick `X.Y.Z` (SemVer; before 1.0 a minor may break, and a dictionary retrain is always a
   minor).
2. `gh workflow run bump-version.yml --repo meshtastic/TAKPacket-SDK -f version=X.Y.Z`. It runs
   `scripts/bump-version.sh` to stamp `VERSION`, `kotlin/gradle.properties`,
   `python/pyproject.toml`, `typescript/package.json` and the C# `.csproj`, then
   `scripts/changelog.sh cut X.Y.Z`, which moves `## [Unreleased]` under a dated `## [X.Y.Z]`
   heading and updates the compare links, touching nothing else. It refuses an empty
   Unreleased. Both scripts also run locally.
3. Review and merge the `release: bump version to X.Y.Z` PR. The changelog section is the
   Release body.
4. `gh workflow run release.yml --repo meshtastic/TAKPacket-SDK -f version=X.Y.Z`. Add
   `-f dry_run=true` to run every gate and build every package without tagging, attesting,
   publishing or releasing; a dry run may start from any branch. Pushing a `vX.Y.Z` tag on
   `main` runs the same workflow.

## What the workflow checks, in order

1. The commit is on `main` (skipped for a dry run).
2. The version equals all five version sources, and any existing `vX.Y.Z` tag points at this
   commit.
3. `scripts/changelog.sh notes X.Y.Z` finds a non-empty section.
4. Every check the `main` ruleset requires (Kotlin, Swift, Python, TypeScript, C#) passed on
   this commit (`scripts/release-checks.sh green-ci`).
5. The Kotlin, Swift, Python, TypeScript and C# tests, then `publishToMavenLocal` with signing.
6. No staged POM or Gradle module depends on a `-SNAPSHOT`
   (`scripts/release-checks.sh no-snapshots`). Central rejects that only after upload.
7. Every package for the Release builds.
8. If `X.Y.Z` is already on `repo1.maven.org` the publish is skipped, so a re-run is safe.

Then it attests every staged and attached artifact, pushes the annotated `vX.Y.Z` tag if it is
missing, runs `publishAndReleaseToMavenCentral`, creates or updates the GitHub Release, and
checks that the tag and the Central POM are both visible.

## After releasing

`repo1.maven.org` lags the Central Portal by 10 to 30 minutes. Downstream bumps wait until
`https://repo1.maven.org/maven2/org/meshtastic/takpacket-sdk-jvm/X.Y.Z/` resolves.
