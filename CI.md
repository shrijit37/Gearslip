# CI/CD

Every push to `main` builds the debug APK and publishes it as a prerelease in Releases, with a tag derived from `git describe`.

You can also run that workflow manually from the Actions tab.

## Shared build pipeline

All three publishing workflows (`release.yml`, `dev-publish.yml` and `promote.yml`) call
`.github/actions/gearslip-build`. Each one checks out first — GitHub has to load a local
action from disk before it can run any of its steps — with full history so `gitVersion` can
name the build. The action is the single place for JDK 21, Gradle setup, the stable signing
key, the build identity (SHA, version name, version code) and the `assembleDebug` build.
`release.yml` tolerates a missing key — a fork gets a throwaway signature and a warning —
while the dev and promotion workflows require it, because their APKs have to install over an
app a driver already has.

The action's outputs are what the workflows publish: the dev feed writes the same SHA and
sequence that were compiled into its APK, and the stable release body records the same
sequence. The action also checks that the APK glob is non-empty, so a build that produces no
APK files cannot be published; it does not check that every ABI split is present.

`gradle.properties` enables the Gradle build cache, parallel task execution and the configuration
cache (`org.gradle.configuration-cache=true`, plus parallel entry storage). On a repeat invocation
Gradle reuses the cached task graph instead of re-evaluating the build scripts; pass
`--no-configuration-cache` to bypass it for one command. The git reads are `providers.exec`, which
Gradle tracks as configuration inputs, so a new commit or tag invalidates the entry instead of
being reused stale. The monotonic version-code clock is read through `BuildClockValueSource` for
the same reason: a plain `System.currentTimeMillis()` at configuration time would be baked into a
cached entry and give later builds the same version code.

`gradle/actions/setup-gradle` restores the Gradle user home in every workflow, so task outputs
Gradle has already produced are reused instead of rebuilt. Configuration-cache entries are saved
and restored only when `GRADLE_ENCRYPTION_KEY` is passed to setup-gradle — by the shared action in
the publishing workflows and directly in `kotlin-checks.yml`; generate one with
`openssl rand -base64 16`. Ephemeral runners still miss more often than not, so
on CI the cache is mainly regression prevention: a plugin or build-script change that breaks it
fails CI before it reaches a developer's machine.

A publish still gets a fresh version code (minutes since the epoch), and AGP writes that code
into `BuildConfig`, so a new publish recompiles Kotlin and reruns R8 even when the sources are
unchanged. That is the price of the monotonic version code the updater relies on. For local
iteration, `-PnoR8` skips shrinking entirely, `-PgearslipAbi=arm64-v8a` builds only that ABI with
no universal fallback, and `-PpseudoLocales` generates the stretched and right-to-left inspection
locales. CodeQL uses `-PnoR8 -PgearslipAbi=arm64-v8a` too: it needs the Kotlin compile, not the
shrinker's output or the other ABIs, and publishes nothing.

## What CI runs, and when

Workflows should not spend a runner on work that is duplicated or that can be done cheaply:

- A PR branch is published to the dev feed by its **push** event only; the old `pull_request`
  trigger built and uploaded the same commit a second time. The ref is the branch name.
- `kotlin-checks`, `codeql` and `security-scan` cancel superseded runs on the same ref
  (`cancel-in-progress: true`). Scheduled Trivy runs use a separate concurrency key so a Monday
  push cannot cancel the weekly scan. `release.yml` and `promote.yml` share one serialized
  `release-assets` group; `dev-publish` has its own serialized group. Asset uploads must finish
  rather than be interrupted mid-publish. A main push on a hand-written `v*` tag leaves that tag
  to `promote.yml`, so a prerelease cannot overwrite the stable release.
- CodeQL no longer runs on a schedule; it still runs on pushes, PRs and manual dispatch. A weekly
  scan of unchanged Kotlin added a full rebuild with no new source facts; Trivy's cheap filesystem
  scan keeps the `0 9 * * 1` slot as the scheduled safety net for newly published advisories.

## Update channels

Both channels install over the same package with the same signing key, so third-party car apps keep recognising Gearslip. They differ only in what they offer:

| Channel | Source | Published by |
|---|---|---|
| Stable | Non-prerelease releases from hand-written `v*` tags | `promote.yml`, on a tag push |
| Dev | Up to 30 newest branch heads | `dev-publish.yml`, on a branch push |

- `promote.yml` requires the four signing secrets; a stable release without them could not replace an installed Gearslip.
- `dev-publish.yml` builds the selected ref and uploads per-ABI APKs to the `dev-builds` prerelease, then writes `manifest.json` on the `dev-feed` branch. The manifest pins a commit and a SHA-256 per asset; the app refuses bytes whose digest doesn't match. Release assets are pruned to those still named by the manifest, so every ref in the feed has a downloadable APK.
- Fork PRs are skipped: their push lands in the fork, not this repository, and the signing key is never available to them. A PR branch in this repository is published from its push event under the branch name, so a commit is offered once rather than once for the push and again for the PR.
- The stable updater ignores prereleases, so until a tag is promoted it reports that no stable release is available. Main pushes remain reachable from the Releases page. It pages through the releases list so a promotion older than the first 100 releases is still found.
- Both workflows publish the APK's version code (minutes since the epoch): the dev feed as `seq`, the stable release as an HTML comment in its body. The app also reads the code from the downloaded APK before installing, and skips any offered build that would be an out-of-order downgrade from a later dev build.
- To promote, push a hand-written tag such as `v0.6.0` to the repository. CI-generated tags contain `-g<sha>` and are never promoted.
