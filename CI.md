# CI/CD

Every push to `main` builds the debug APK and publishes it as a prerelease in Releases, with a tag derived from `git describe`.

You can also run that workflow manually from the Actions tab.

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
