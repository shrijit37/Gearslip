# Gearslip conventions for reviewers

Gearslip is an Android Auto client. The app is installed by the driver from its own in-app
updater, so updater changes are safety-critical: a bad release can leave the phone unable to
reconnect to a car. Treat the following as invariants.

## Update channels

- Stable and dev APKs share one application id and one signing key, so third-party car apps keep
  recognising Gearslip. Any change to signing, package name, or `versionCode` must preserve that.
- `versionCode` is one monotonic sequence (minutes since the epoch) shared by both channels.
  Both publish workflows pass the exact same value to Gradle and publish it with the release.
  Never revert to a tag-derived code; a channel switch would become a downgrade Android refuses.
- Before install, an offered APK's own version code must be read from the downloaded file and
  compared with the installed code, in addition to any published marker. A lower code is never
  installed.
- Every APK offered by either channel must have a SHA-256 digest verified before it is installed.
  An asset without a usable digest is skipped, not installed unverified.
- Dev builds are selected by commit identity, not semver. The selected ref's head SHA is pinned
  when the update is offered and shown on the update card.

## State and concurrency

- Checks, downloads and installs are manual and run on background threads. Any UI choice that can
  invalidate one (channel, dev ref) must bump the generation counter, and the background work must
  re-check that generation before publishing state or handing bytes to Android's installer.
- `install()` and `download()` must reject a release whose channel no longer matches the saved
  channel. A queued install must not survive a channel or ref change.
- Workflows that mutate shared state (the dev manifest, release assets) must run in one serialized
  concurrency group. Never prune assets against a stale or empty manifest read.
- Network calls need connect and read timeouts and must disconnect in `finally`.

## Verification

- Unit tests for selection/decision logic belong in `gearslip/src/test/.../update/` and must stay
  pure JVM tests (no Android stubs).
- **Only build or test when the change can affect the build.** Run the full check — both tasks in
  **one** invocation, `./gradlew :gearslip:testDebugUnitTest :gearslip:assembleDebug --no-daemon` —
  when Kotlin sources, resources, `build.gradle.kts`, `gradle.properties`, the Gradle wrapper or
  dependency versions changed, or when submitting an updater code change. Same coverage as two
  separate runs, but the build script is evaluated once instead of twice.
- **Do not build for changes that cannot affect it.** Workflow YAML, `CI.md`, docs and reviewer
  config do not compile. Validate those with a YAML parse, `git diff --check` and a read of the
  changed logic. A full rebuild there spends minutes proving nothing, and the result says nothing
  about whether the workflow is correct.
- While iterating on a change that *does* affect the build, use the daemon and skip work a final
  check still needs:
  `./gradlew :gearslip:testDebugUnitTest -PnoR8 -PgearslipAbi=arm64-v8a -PgearslipVersionName=local -PgearslipBuildSha=local -PgearslipBuildSeq=$(( $(date +%s) / 60 ))`.
  Pinning the version name skips `git describe`, pinning the SHA skips `git rev-parse`, and pinning
  the sequence skips the clock, so repeat builds can reuse the configuration cache. Those pinned
  values are for local iteration only — never publish a build made with them. Finish with the full
  command above when the change affects the build; skip it entirely when it does not.
- Release-process behaviour is documented in `CI.md`; keep it in sync with workflow changes.
- After pushing a PR, poll its check runs until every one is complete, then read the reviews and
  inline comments again. Triage every finding: fix valid ones, push, and repeat. Stop only when all
  checks are green and a fresh review brings no new findings. Treat a transient runner/dependency
  failure by re-running the job, not by changing code. Verify each finding against the full PR diff
  (`gh pr view <n> --json files` / the files API), not the last commit: reviewers sometimes see a
  stale or partial diff. Dismiss false positives with `@mira reject` on the inline comment rather
  than changing code that is already correct.
