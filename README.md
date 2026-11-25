[![Build APK and release](https://github.com/shrijit37/Gearslip/actions/workflows/release.yml/badge.svg)](https://github.com/shrijit37/Gearslip/actions/workflows/release.yml)

# Gearslip — Android Auto Protocol core

Gearslip speaks the Android Auto Protocol (AAP) directly to a car over USB or wireless, with no
Google software in the path. This branch is the headless protocol core: connection and TLS,
certificates, service discovery and message framing, the audio, media, call and notification
channels, and a host for templated car apps.

The video channel currently sends a placeholder test-card frame — colour bars with a marker that
advances each second — so the car sees a live, unmistakably ours picture while the session and
framing are exercised. **No headunit UI is bundled.** The plug-in headunit surface architecture is
coming next: the protocol core is being kept independent of whatever draws on the car screen so a
surface can be added later without changing the session.

> [!IMPORTANT]
> This is an independent implementation of the protocol, not affiliated with Google. The
> protocol work began in [twinksoftware/Gearslip](https://github.com/twinksoftware/Gearslip).

> [!WARNING]
> This is alpha software. It can drop the connection or fail to start. Set it up while parked and
> keep another way to navigate.

## What it does

- **Connection.** USB accessory mode and wireless AAP, with TLS, a certificate you supply or
  download, service discovery and protobuf framing. [CARS.md](CARS.md) lists cars that have
  reached the screen at least once; some cars from about 2020 need a certificate of your own.
- **Channels.** Phone audio, the car's microphone, media browsing and playback, calls,
  notifications and messaging, plus input and vehicle data. These talk to apps installed on the
  phone; Gearslip carries the protocol, it does not bundle a car interface.
- **Hosted car apps.** Installed apps built with the Android for Cars App Library can run as
  templated car apps over the session. See [docs/BUILDING_APPS.md](docs/BUILDING_APPS.md) for the
  app-side contract.
- **Video.** A placeholder test-card frame, as described above. There is no map, player, dock, or
  other screen drawing in this build.

The phone app is still there for setup, certificates, logs, settings and updates.

## Install

1. Install an APK from the [releases page](https://github.com/shrijit37/Gearslip/releases).
2. Open it and follow setup: choose a certificate source, grant each permission with its reason,
   then read the safety note.
3. Plug into the car's USB port. For wireless cars, pair over Bluetooth, tap **Connect
   wirelessly**, and pick the car.
4. Later releases can be installed from the app: **Settings → Updates → Check for updates**. It
   downloads the build for your phone's processor (~20 MB instead of the 35 MB universal APK),
   checks it against the checksum GitHub publishes and reads the version code from the downloaded
   file before handing it to Android's installer. A lower version code is never installed. Nothing
   happens in the background, and the install waits while a car is connected.

   **Updates** has two channels. Stable offers only releases promoted from a `v*` version tag;
   main-branch test builds stay on the Releases page and are not offered. Dev installs the head of
   a branch or `pr-<number>` from this repository, pinned to that commit and shown on the update
   card. Both replace the same app with the same signing key, so car apps keep working with either
   — but a dev build can break the install, and fork PRs are not published. A build older than the
   one installed is never offered; returning to a stable release promoted before your dev build
   needs a manual uninstall. See [CI.md](CI.md).

Releases are **debug builds**. Unless the signing secrets below are configured, each one is
signed by that runner's throwaway debug key, so a new release may need an uninstall first. There
is no fixed fingerprint to compare; get APKs only from the releases page.

> [!NOTE]
> On GrapheneOS, don't set **USB-C port control** to **Charging-only** — it blocks USB data, so
> the car never sees the phone.

## Stable signing for in-app updates

Android refuses to install an update signed by a different key. By default CI builds are signed
with the runner's throwaway debug key, so each release would need a manual uninstall first. To
enable in-app updates, set four repository secrets:

| Secret | Value |
|---|---|
| `UPDATE_KEYSTORE_B64` | Base64 of a committed-nowhere JKS keystore (`base64 -w0 gearslip-release.jks`) |
| `UPDATE_STORE_PASSWORD` | Keystore password |
| `UPDATE_KEY_ALIAS` | Key alias |
| `UPDATE_KEY_PASSWORD` | Key password |

The workflow writes `keystore.properties` from these, and `build.gradle.kts` already signs the
published debug build with that key when the file is present. Keep the keystore safe: it is the
only thing that lets an installed Gearslip accept the next one. Switching to it is a one-time
change, so existing installs need to be uninstalled once.

## Build

`./gradlew :gearslip:assembleDebug` produces one APK per ABI in `build/`
(`Gearslip-arm64-v8a-debug.apk`, `Gearslip-armeabi-v7a-debug.apk`, `Gearslip-x86-debug.apk`,
`Gearslip-x86_64-debug.apk`) plus `Gearslip-universal-debug.apk` as a fallback. CI builds, checks
and publishes them all for each tagged commit. A local Android SDK with platform 37 is required.

## Privacy

Gearslip keeps connection data on the phone. [PRIVACY.md](PRIVACY.md) lists the services it
contacts and what each one receives.

## Contributing

Bug reports from real cars are the most useful contribution. See
[CONTRIBUTING.md](CONTRIBUTING.md) for reporting, building and pull requests.

## License

Licensed under AGPL-3.0; see [LICENSE](LICENSE) and [NOTICE.md](NOTICE.md).
