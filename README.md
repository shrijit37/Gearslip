[![Build APK and release](https://github.com/shrijit37/Gearslip/actions/workflows/release.yml/badge.svg)](https://github.com/shrijit37/Gearslip/actions/workflows/release.yml)

# Gearslip — Nothing OS car UI

A standalone, Nothing OS-styled replacement for the Android Auto car interface. It speaks
the Android Auto protocol directly to a car over USB or wireless, with no Google software in
the path, and draws its own monochrome car shell on top.

> [!IMPORTANT]
> This is a standalone build, not a fork of upstream Gearslip in look or feature set. It keeps
> the car connection protocol and hosts third-party car apps, but the interface is a fixed
> Nothing OS-inspired skin: a left app rail, a stage, and a compact player. It is not
> affiliated with Nothing Technology Limited or Twink Software LLC. The protocol work began in
> [twinksoftware/Gearslip](https://github.com/twinksoftware/Gearslip).

> [!WARNING]
> This is alpha software. It can drop the connection, freeze the car screen, or fail to start.
> Set it up while parked and keep another way to navigate.

## What it does

| | |
|---|---|
| **Cars** | Most cars over USB or wireless. Some cars from about 2020 need a certificate of your own. [CARS.md](CARS.md) lists confirmed cars. |
| **Maps** | Third-party car navigation apps (Organic Maps, Vela, OsmAnd, Waze and others) render on the car stage. Place search is built in with an offline demo index and saved places; Google Maps can be mirrored from the phone. |
| **Music** | First-party Browse, full player with synced lyrics and queue, plus any car-ready media app as an option. |
| **Weather** | Current conditions, hourly and weekly forecast, with a dashboard showing the day ahead. |
| **Vehicle data** | Supported car sensor readings, shown raw. |
| **Controls** | Touch, steering-wheel buttons, control knobs and D-pads. |
| **Screensaver** | Rests on the dot-matrix clock, next turn, playback state and car readings. |

The shell is deliberately narrow: Nothing Red is the only accent, surfaces are pure black,
borders are 1px, and display text uses the bundled Doto font. See [design.md](design.md) for
the tokens and the [mockup](carplay-nothing-final.html) for the intended layout.

## Install

1. Install an APK from the [releases page](https://github.com/shrijit37/Gearslip/releases).
2. Open it and follow setup: choose a certificate source, grant each permission with its reason,
   then read the safety note.
3. Plug into the car's USB port. For wireless cars, pair over Bluetooth, tap **Connect
   wirelessly**, and pick the car.
4. Later releases can be installed from the app: **Settings → Updates → Check for updates**. It
   downloads the build for your phone's processor (~20 MB instead of the 35 MB universal APK),
   checks it against the checksum GitHub publishes, and hands it to Android's installer. Nothing
   happens in the background, and the install waits while a car is connected.

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

## License

Licensed under AGPL-3.0; see [LICENSE](LICENSE) and [NOTICE.md](NOTICE.md).
