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
- Before submitting updater or workflow changes, run
  `./gradlew :gearslip:testDebugUnitTest --no-daemon` and `./gradlew :gearslip:assembleDebug --no-daemon`.
- Release-process behaviour is documented in `CI.md`; keep it in sync with workflow changes.
