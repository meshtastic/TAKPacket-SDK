<!--
Thank you for contributing to TAKPacket-SDK!
Fill out the sections below. Delete any that don't apply.
-->

## Summary

<!-- One or two sentences: what does this PR do? -->

## Type of change

- [ ] Bug fix (non-breaking)
- [ ] New feature (non-breaking)
- [ ] Breaking / wire-incompatible change (forces a lockstep mesh upgrade — batch into a minor bump)
- [ ] Documentation only
- [ ] Infrastructure / CI / build
- [ ] Proto submodule bump
- [ ] Dictionary retraining

## Related issue / discussion

<!-- Link the issue: Fixes #123 / Refs #456. -->

## Checklist

- [ ] Tests pass across the affected bindings (Kotlin `jvmTest`, Swift, Python, TypeScript, C#).
- [ ] If wire behavior, the proto schema, or a dictionary changed, I regenerated the Kotlin goldens (`.pb` / `.bin`) and the compression report, then re-ran all binding suites against them.
- [ ] Wire payloads remain byte-interoperable (decodable + within size tolerance) across all five bindings.
- [ ] CHANGELOG / release notes updated if this is a user-facing change.
- [ ] No PII (real coords, ANDROID IDs, private IPs, callsigns) in any added/changed fixture.

## How was this verified?

<!--
Describe testing — unit tests, cross-binding decode checks, manual runs.
For wire-protocol changes, list which bindings you cross-checked against.
-->

## Notes for reviewers

<!-- Anything else the reviewer should know. Tricky areas, open questions, follow-ups. -->
