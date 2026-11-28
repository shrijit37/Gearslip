# Plug-in migration: audit and plan

> **Status: in progress.** This records the findings of a full-tree review of `main` at `e5e86e3`
> and the plan to finish the UI-as-plug-in migration. The design target is in
> [PLUGIN_ARCHITECTURE.md](PLUGIN_ARCHITECTURE.md). Each phase lands as its own small PR; phases
> marked **done** below are implemented, the rest are still planned. The currently shipped contract
> is [HEADUNIT_SURFACES.md](HEADUNIT_SURFACES.md).

## Current state, with evidence

| Area | Today |
| --- | --- |
| Plug-in contract | `gearslip/src/main/java/app/seb3thehacker/gearslip/surface/HeadunitSurface.kt:28` — `id`, three capabilities, `attach/detach/onFocus/onTouch/onKey/onRotate` |
| State exposed | `surface/SurfaceContext.kt:16-34` — six read-only flows: session, frame, insets, night, carData, vehicle |
| Frame production | `surface/FrameSource.kt:25-37` (H.264 bytes into core-owned `FrameOutput`); one production implementation, `PlaceholderFrameSource.kt:19`, which feeds YUV straight into `MediaCodec`; tests add `FakeFrameSource` at `surface/Fixtures.kt:19` |
| Registry | `surface/HeadunitSurfaces.kt:12-31` — one volatile global slot, factory or instance, `install`/`clear` |
| Runner wiring | `GearslipRunner.kt:1221-1242, 1325-1331` attaches/starts a source, routes input by capability, releases on stop and EOF |
| App wiring | `GearslipActivity.kt:28,355,401` creates a surface from the registry but **never installs one** |
| Hosted car apps | `host/CarAppConnection.kt` binds templated apps and lends surfaces through `attachSurface()` at `:507`; the method currently has no caller |
| Session state | Process-wide `object`s: `SessionStatus`, `CarEnvironment`, `CarServices`, `CarKeys`, and internal `CarAlerts`/`CarToasts` flows |
| Tests | 23 pure-JVM files; `surface/SurfacePluginTest.kt:16-120` has seven plug-in tests, including a focused natural-EOF teardown case, and `surface/ArchitectureTest.kt` guards the core boundary; no instrumentation tests exist |
| Modules | One Gradle module (`:gearslip`); Compose and the car-app library are declared together. MapLibre and OkHttp were removed in Phase 0 |

## Findings

1. **No renderer is installed.** `GearslipActivity` imports `HeadunitSurfaces` and calls `create()`
   at lines 355 and 401, but never calls `install(...)`. Every real session runs the placeholder.
   [HEADUNIT_SURFACES.md](HEADUNIT_SURFACES.md#registering-a-surface) describes an install call that
   is not in the code.

2. **Hosted navigation apps have nowhere to draw.** `CarAppConnection.attachSurface()` (`:507`) is
   dead code; its comment still describes a `SurfaceView` in a `Presentation` that was deleted.
   `surfaceEpoch` (`:537`) expects a "stage" that no longer exists. `detachSurface`,
   `replaceDeadSurface`, `reportAreas` and the gesture methods at `:622-642` have no callers either.
   The host's `template` (`:87`), `trip` (`:689`), `panMode`/`atRoot` (`:462-473`) and `surfaceEpoch`
   have no external reader: templates arrive and are dropped. [BUILDING_APPS.md](BUILDING_APPS.md#drawing-a-map)
   promises a lent surface that nobody supplies.

3. **`SurfaceContext` is not a complete view model.** It exposes six flows, implemented inline in
   `GearslipRunner.kt:1241-1249`. Media playback (`media/CarMediaStatus.kt`), calls
   (`call/CarPhoneStatus.kt`), notifications (`notify/CarNotifications.kt`), navigation turns
   (`notify/PhoneNavigation.kt`), sensor readings (`car/CarSensors.kt`), hosted-app templates and
   status, the car-app catalog (`host/CustomCarApps.kt`), the alert/toast queues
   (`car/CarAlerts.kt:26`, `car/CarToasts.kt:24`) and the music player's state
   (`music/playback/MusicPlayer.kt`) are all session state a renderer cannot see. Rebuilding the old
   car UI as a plug-in is impossible without widening this boundary.

4. **Plug-in callbacks are not failure-isolated.** `GearslipRunner.kt:970-971, 1004-1005, 1325, 1331`
   and `releaseSurface()` at `:134-138` call plug-in code without `runCatching`. `attach()` is called
   by `FrameSources.choose` without a guard, and `requestSyncFrame`/`applyBitRate` at `:1104`,
   `:1344`, `:1603` and `:1642-1658` can also propagate. An exception from a WebView bridge or a
   renderer can reach the `run()` catch-all at `:213-217`, be reported as `"transport failed"` and
   tear down the session. The only `FrameOutput` implementation is the anonymous object at `:1209`
   (no test fake implements it), so nothing else stands between the plug-in and the transport. A
   plug-in must never be able to end a drive.

5. **Session state is process-global, not session-scoped.** `object SessionStatus`,
   `object CarEnvironment`, `object CarServices`, `object CarKeys`, `object CarData`,
   `object CarFocus`, the alert/toast objects, `CarNotifications`, `PhoneNavigation`, `MusicPlayer`,
   `LocationKeepAlive` and the wireless callback holders are shared across sessions. Nothing
   enforces the "read-only" claim in `SurfaceContext.kt:12`: their mutators are public, and a plug-in
   could call `CarFocus.exitToCar()` (`CarFocus.kt:21`, no current caller) or a `CarSettings` setter.
   `CarSettings.setPipeAudio()` (`:494-505`) even reaches the audio transport from a settings toggle.
   That is acceptable for one headless session but blocks multiple plug-ins, parallel sessions and
   clean tests. State needs a session owner with a generation.

6. **The plug-in registry has no generation or swap semantics.** `HeadunitSurfaces.kt:12-31` holds one
   volatile slot. There is no version negotiation, no multi-surface routing, and no equivalent of the
   updater's `generation` counter (`update/UpdateChecker.kt:90-130`) for a late renderer to re-check.

7. **Only encoded frames are accepted.** `FrameSource` requires the plug-in to produce H.264 and to
   understand bitrate limits and keyframe resync. A WebView or Compose renderer needs an easier mode
   where the core lends an encoder-backed `Surface` and the plug-in only draws pixels.

8. **UI policy still lives in the core.** `car/CarKeys.kt:59-100` consumes media and call keys itself
   and publishes `screenKeys` to a flow with no subscriber; `CarFocus.exitToCar()` is also orphaned.
   Keycodes are `android.view.KeyEvent` constants in `CarKeys.kt:6`, `CarPhoneStatus.kt:4` and
   `CarMedia.kt:7`. A plug-in cannot customize key maps or intercept a key before core acts on it.

9. **Isolation is not build-enforced.** Everything is in one module;
   `gearslip/build.gradle.kts:239-244` declares Compose alongside protocol dependencies. Phase 0
   added `surface/ArchitectureTest.kt` to fail on View/Compose/renderer imports in core packages,
   but the module split itself is still pending. The unused `maplibre` and `okhttp` dependencies and
   their build-script comments were removed in Phase 0 (no `com.maplibre` or `okhttp3` imports ever
   existed in `gearslip/src`). `androidTest` dependencies (`:252-255`) still have no source set; the
   comment at `:252-253` is stale. Bitmap work also sits in core helpers —
   `host/CarModels.kt:76` keeps a global `LruCache<String, Bitmap>` and `:85` returns a `Bitmap` — so
   pure screen models need rasterization moved into the renderer.

10. **Test coverage has holes at exactly the migration seam.** `SurfacePluginTest` covers selection,
    basic routing, explicit detach and natural-EOF detach (`:85-90`), but not the factory install
    path, `onFocus`, `requestSyncFrame`/`applyBitRate` forwarding, attach-under-stop, a throwing
    plug-in, or a plug-in swapped mid-session. `UpdateCheckerTest` does not cover generation bumps
    or queued installs.

11. **Docs and comments were stale about rendering.** [BUILDING_APPS.md](BUILDING_APPS.md#drawing-a-map)
    described a virtual display and chrome overlays that no longer exist, said Gearslip renders
    third-party screens in Compose, and claimed Gearslip draws its own keyboard and media UI.
    [CONTRIBUTING.md](../CONTRIBUTING.md#pull-requests) now reflects that feature PRs land on
    `main`; it previously directed work to `experimental`. Those sections are corrected here.
    Remaining drift: `host/CarAppConnection.kt`
    comments at `:499-502`, `:539-551`, `:589-591`, `:622-626` and `:753-761`; the build-script
    comments at `gearslip/build.gradle.kts:157-158,177,185,252-253`; empty car-UI headings in
    `res/values/strings.xml:294-395`; duplicate session text at `:408-432`; unused `<queries>` for
    recognition and TTS services in `AndroidManifest.xml:121-128`.

12. **The phone UI bypasses any command layer.** Composables call core globals directly:
    `ui/SettingsScreen.kt:289-351,403-556`, `ui/HomeCarSettings.kt:99-435`,
    `ui/BluetoothAddressCard.kt`, `ui/WirelessButton.kt` and `ui/SetupGuideScreen.kt:335-347` mutate
    `AppSettings`, `CarSettings`, `CarBluetooth`, `WirelessLink` and `AndroidAuto`.
    `GearslipActivity.kt:125` wires `WirelessLink.sessionWaiting` to a `GearslipRunner` field, and
    `:290,344` calls `SessionStatus.failed()` from the UI. There is no audited UI→core boundary to
    reuse for a renderer.

13. **Dead UI state still has an owner but no consumer.** `car/CarEnvironment.kt:43-48` keeps
    `phoneDark`, `display` and `battery` with no readers; `CarSettings` holds roughly 35 flows,
    many unread (`scale`, `playerTitle`, `navBarMode`, `screensaver`, `whatsNew*`, …);
    `car/CarSensors.kt`, `notify/CarNotifications.kt`, `notify/PhoneNavigation.kt` and
    `GearslipLog.version/snapshot()` remain live but undrawn. `PermissionActivity.permissionResults`
    (`:45-48`) has no subscriber. The log-share `FileProvider` survives in the manifest with no
    screen that calls it. This is dead weight to prune or expose deliberately in Phase 1, not to
    carry into a plug-in SDK.

## State boundary inventory

The renderer must see session state, but it must not own it. The state owners below are currently
process-global; each needs a session-scoped replacement before a plug-in can be trusted. The last
row is an explicit exception: `UpdateChecker`, `WirelessLink` and `PermissionActivity` stay in the
app. Only their callbacks and generation need tightening, not a session-scoped replacement.

| Current owner | State it holds | Destination |
| --- | --- | --- |
| `SessionStatus` | phase, headlines, stuck-on link | Immutable session snapshot; user-facing text belongs to the renderer |
| `CarEnvironment` | frame, insets, night, vehicle, plus unread `phoneDark`/`display`/`battery` | Snapshot; prune unread fields |
| `CarData`, `CarSensors` | decoded vehicle readings | Snapshot |
| `CarServices`, `CarAppConnection`, `CarModels` | nav/browse/media connections, templates, trip state, surface lease, icon bitmaps | Session-scoped host service; pure template models; rasterization in renderer |
| `CarKeys`, `CarFocus` | key policy, screen keys, focus request | Command/intent layer; plain key codes instead of `KeyEvent` constants |
| `CarAlerts`, `CarToasts`, `CarNotifications`, `PhoneNavigation`, `MusicPlayer` | queues and overlays waiting for a renderer | Snapshot state; keep timers in core |
| `CarSettings`, `AppSettings`, `CustomCarApps` | persisted preferences, including audio transport toggles | Settings repository; transport-affecting changes go through commands |
| `UpdateChecker`, `WirelessLink`, `PermissionActivity` | channels/generation, wireless callbacks, permission results | Stay in the app; add subscriber checks and mirror generation to renderer swaps |

## Phased plan

Each phase ends with a green `./gradlew :gearslip:testDebugUnitTest :gearslip:assembleDebug --no-daemon`
and a docs update. Do not combine phases into one large PR.

### Phase 0 — Freeze the boundary (no behavior change) — **done**

- Introduce a pure-Kotlin `surface/api` package of immutable models and command types.
- Add a JVM architecture test that fails if a core package (`protocol`, `car`, `host`, `media`,
  `call`, `notify`, `update`, `surface/api`) imports `androidx.compose.*`, `android.view.View`,
  `android.view.ViewGroup`, `android.widget.*` or a concrete renderer package. Keep it as a guard,
  not a one-off. Explicitly allow `android.view.Surface`: `host/CarAppConnection.kt:17` needs it for
  the surface lease the target architecture retains, and it is not a UI hierarchy type. Keycodes
  and bitmaps are deliberately out of scope here — `KeyEvent` and `Bitmap` imports stay until
  Phase 1 (finding 8 and the state boundary inventory); a gate that also banned them would fail
  the existing tree.
- Extract `FakeContext`/`FakeSurface`/`FakeFrameSource` into shared test fixtures.
- Remove the dead `maplibre` and `okhttp` dependencies.
- **Exit:** the architecture test fails on a deliberately bad import; all existing tests pass.

### Phase 1 — Complete the session view model

- Extend `SurfaceContext` to cover the whole session: media, call, notifications, navigation turns,
  sensors, hosted-app templates and status, alerts, toasts, catalog, surface leases, and settings.
  Use immutable models; move `Bitmap`/`Drawable` conversion out of `host/CarModels.kt:76-109` into a
  renderer adapter, and replace `KeyEvent` constants in core with plain key codes.
- Move every owner in the inventory above except `UpdateChecker`, `WirelessLink` and
  `PermissionActivity` into a session scope, exposing read-only snapshots. Prune unread flows and
  dead state (finding 13) instead of publishing it. Core keeps alert timeouts and
  transport-affecting rules so a headless session still answers apps.
- Add JVM tests for template/snapshot mapping, state immutability across a session boundary, and
  absence of cross-session state leakage.
- **Exit:** a fake renderer can drive every screen the old car UI had, from flows alone.

### Phase 2 — Plug-in contract v2 and failure isolation

- Add a version field and a capability enum; support more than one surface with focus routing.
- Add one command entry point; forbid plug-ins from calling core singletons directly. Route the
  phone UI through it too, starting with `AudioChoiceDialog` → `CarSettings.setPipeAudio()` and the
  activity's direct `SessionStatus`/runner access (finding 12).
- Wrap **every** plug-in call in `runCatching`, log it, and fall back to the placeholder: `attach`,
  `detach`, `onFocus`, `onTouch`, `onKey`, `onRotate`, `requestSyncFrame` and `applyBitRate`. Add the
  same guard to `releaseSurface()` so a bad `detach()` cannot skip cleanup.
- Give the registry a generation counter, re-checked after `attach()` and before publishing any state.
- Write a reusable contract test suite that any plug-in implementation must pass, including a
  plug-in that throws on each callback.
- **Exit:** a deliberately broken plug-in cannot stop a session or publish stale state after a swap.

### Phase 3 — Pixel mode and hosted-app surface lease

- Add a `PROVIDES_PIXELS` mode: the core lends an encoder-backed `Surface`; the plug-in draws into it
  and the core keeps bitrate control, resync and ack handling. The placeholder remains the fallback.
- Make `CarAppConnection` request a lease from the attached plug-in instead of expecting a
  `SurfaceView` that no longer exists; honor `surfaceEpoch` when a dead surface is replaced.
- Keep any virtual-display/Presentation machinery **inside the renderer module**, never in core.
- **Exit:** a small Compose test plug-in renders frames, and a templated navigation app draws into
  the lease; core contains no view code.

### Phase 4 — Split modules

- Candidate modules: `:core-api` (pure models and contracts), `:protocol-core`, `:car-app-host`,
  `:surface-placeholder`, `:surface-compose`, `:surface-web`, `:app` (phone UI, updater, wiring).
- Keep `applicationId`, shared signing, monotonic `versionCode`, ABI splits and APK naming in the
  app module, per [AGENTS.md](../AGENTS.md). Move the `-keep class androidx.car.app.**` rule with
  the car-app host module.
- Remove the residue left behind by the Phase 0 dependency removal (findings 9 and 11): the MapLibre
  packaging config and its stale comment at `gearslip/build.gradle.kts:185-186`, unused `androidTest`
  declarations, the Nothing-UI comments, unused recognition/TTS `<queries>`, empty car-UI string
  headings and the orphaned log-share `FileProvider` if no screen uses it. Phase 0 already removed the
  dependencies and their build-script comments; this phase does not repeat that step.
- Update Gradle task paths in `.github/actions/gearslip-build/action.yml`,
  `.github/workflows/kotlin-checks.yml`, `.github/workflows/codeql.yml`, and the commands in
  `AGENTS.md`, `CI.md`, `README.md` and `CONTRIBUTING.md`.
- **Exit:** a build without `:surface-web` or `:surface-compose` still produces a working app with
  the placeholder; CI and the documented verification commands work unchanged in effect.

### Phase 5 — External plug-ins and a WebView reference renderer

- Implement the binder "Gearslip apps" specification in [BUILDING_APPS.md](BUILDING_APPS.md#gearslip-apps) as
  an external surface provider, with version negotiation, a permission/signing allowlist and the
  same command contract. Keep untrusted plug-ins out of the transport path.
- Build the HTML/CSS/JS renderer as the reference plug-in: JSON state events in, JSON commands out;
  use the old interactive mocks as design source, not as runtime code.
- Measure frame pacing, touch latency and memory with this renderer before treating it as shippable.
- **Exit:** an external plug-in and a WebView plug-in both render and take input with no core changes.

## Risks and gates

- **Performance:** a real renderer pays for composition and pixel copies; the placeholder numbers do
  not predict it. Gate Phase 3 on measured frame deadlines with the slowest intended renderer.
- **Safety:** plug-in failures and plug-in swaps must never reach the installer or transport. Keep
  the updater's generation rules in [AGENTS.md](../AGENTS.md) separate from, but mirrored by, the
  renderer generation counter.
- **Compatibility:** keep one package name, one signing key and one monotonic version-code sequence.
  Do not promote a build made with local pinning flags.

## Docs to keep in sync

[README.md](../README.md) · [HEADUNIT_SURFACES.md](HEADUNIT_SURFACES.md) ·
[BUILDING_APPS.md](BUILDING_APPS.md) · [CONTRIBUTING.md](../CONTRIBUTING.md) ·
[CI.md](../CI.md) · [AGENTS.md](../AGENTS.md) · this file
