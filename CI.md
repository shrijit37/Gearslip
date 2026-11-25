# CI/CD

Every push to `main` builds the debug APK and publishes it as a prerelease in Releases, with a tag derived from `git describe`.

You can also run that workflow manually from the Actions tab.

## Shared build pipeline

All three publishing workflows (`release.yml`, `dev-publish.yml` and `promote.yml`) call
`.github/actions/gearslip-build`. It is the single place for checkout, JDK 21, Gradle setup,
the stable signing key, the build identity (SHA, version name, version code) and the
`assembleDebug` build. `release.yml` tolerates a missing key — a fork gets a throwaway
signature and a warning — while the dev and promotion workflows require it, because their
APKs have to install over an app a driver already has.

The action's outputs are what the workflows publish: the dev feed writes the same SHA and
sequence that were compiled into its APK, and the stable release body records the same
sequence. The APK check (one file per ABI plus the universal fallback) also lives there, so
a release can't ship an empty build if the naming ever changes.

`gradle.properties` enables the Gradle build cache and parallel task execution.
`gradle/actions/setup-gradle` restores the Gradle user home in every workflow, so task outputs
Gradle has already produced are reused instead of rebuilt. The configuration cache is left
opt-in (`--configuration-cache`): the build reads the git HEAD through `providers.exec`, and
reusing a stale entry could give a build the wrong commit identity.

A publish still gets a fresh version code (minutes since the epoch), and AGP writes that code
into `BuildConfig`, so a new publish recompiles Kotlin and reruns R8 even when the sources are
unchanged. That is the price of the monotonic version code the updater relies on. For local
iteration, `-PnoR8` skips shrinking entirely and `-PpseudoLocales` generates the stretched
and right-to-left inspection locales. CodeQL uses `-PnoR8` too: it needs the Kotlin compile,
not the shrinker's output.

## Update channels

Both channels install over the same package with the same signing key, so third-party car apps keep recognising Gearslip. They differ only in what they offer:

| Channel | Source | Published by |
|---|---|---|
| Stable | Non-prerelease releases from hand-written `v*` tags | `promote.yml`, on a tag push |
| Dev | One branch head or `pr-<number>` at a time | `dev-publish.yml`, on a push or a same-repository PR |

- `promote.yml` requires the four signing secrets; a stable release without them could not replace an installed Gearslip.
- `dev-publish.yml` builds the selected ref and uploads per-ABI APKs to the `dev-builds` prerelease, then writes `manifest.json` on the `dev-feed` branch. The manifest pins a commit and a SHA-256 per asset; the app refuses bytes whose digest doesn't match. Release assets are pruned to those still named by the manifest, so every ref in the feed has a downloadable APK.
- Fork PRs are skipped: they cannot use the signing key, so an APK from them could not be installed over the app a driver has.
- The stable updater ignores prereleases, so until a tag is promoted it reports that no stable release is available. Main pushes remain reachable from the Releases page. It pages through the releases list so a promotion older than the first 100 releases is still found.
- Both workflows publish the APK's version code (minutes since the epoch): the dev feed as `seq`, the stable release as an HTML comment in its body. The app also reads the code from the downloaded APK before installing, and skips any offered build that would be an out-of-order downgrade from a later dev build.
- To promote, push a hand-written tag such as `v0.6.0` to the repository. CI-generated tags contain `-g<sha>` and are never promoted.
