# Plug-in migration: audit and plan

> **Status: in progress.** This records the findings of a full-tree review of `main` at `e5e86e3`
> and the plan to finish the UI-as-plug-in migration. The design target is in
> [PLUGIN_ARCHITECTURE.md](PLUGIN_ARCHITECTURE.md). Each phase lands as its own small PR; phases
> marked **done** below are implemented, the rest are still planned. The currently shipped contract
> is [HEADUNIT_SURFACES.md](HEADUNIT_SURFACES.md).

## Current state, with evidence

| Area | Today |
| --- | --- |
| Plug-in contract | `gearslip/src/main/java/app/seb3thehacker/gearslip/surface/HeadunitSurface.kt:32` — `id`, four capabilities, `attach/detach/onFocus/onTouch/onKey/onRotate` |
| State exposed | `surface/SurfaceContext.kt:16-34` — six read-only flows: session, frame, insets, night, carData, vehicle |
| Frame production | `surface/FrameSource.kt:25-37` (H.264 bytes into core-owned `FrameOutput`); one production implementation, `PlaceholderFrameSource.kt:19`, which feeds YUV straight into `MediaCodec`; tests add `FakeFrameSource` at `surface/Fixtures.kt:19` |
| Registry | `surface/HeadunitSurfaces.kt:28-92` — one global slot, factory or instance; `install`/`installSurface`/`installSurfaceIfAbsent`/`clear`, and `select` captures surface, generation and trust |
| Runner wiring | `GearslipRunner.kt:1300-1400, 161-195` attaches/starts a source, routes input by capability, releases on stop and EOF |
| App wiring | `GearslipActivity.kt:29,30,119` installs the untrusted `PlaceholderSurface` at `onCreate` when no surface is registered, and selects it at 367/415 |
| Hosted car apps | `host/CarAppConnection.kt` binds templated apps and lends surfaces through `attachSurface()` at `:508`; called for a trusted pixel-mode lease |
| Session state | Process-wide `object`s: `SessionStatus`, `CarEnvironment`, `CarServices`, `CarKeys`, and internal `CarAlerts`/`CarToasts` flows |
| Tests | 26 pure-JVM files (verified with `find gearslip/src/test -name "*.kt"`); `surface/SurfacePluginTest.kt:16-130` has eight plug-in tests, including a focused natural-EOF teardown case, and `surface/ArchitectureTest.kt` guards the core boundary; no instrumentation tests exist |
| Modules | One Gradle module (`:gearslip`); Compose and the car-app library are declared together. MapLibre and OkHttp were removed in Phase 0 |

## Findings

1. **Only the headless renderer is installed.** `GearslipActivity` installs `PlaceholderSurface` at
   the composition root and selects it at lines 367 and 415. It declares no capabilities, so every
   real session still generates the placeholder frame. A real Compose/WebView renderer is still
   needed before the old car UI can live in a plug-in.

2. **Hosted navigation apps have nowhere to draw.** `CarAppConnection.attachSurface()` (`:508`) is
   now called when an installed renderer declares `PROVIDES_PIXELS` and supplies a lease; no such
   renderer ships yet, so it stays effectively unused. `detachSurface` is released with the lease,
   but `replaceDeadSurface`, `reportAreas` and the gesture methods at `:623-643` still have no
   callers. The host's `template` (`:88`), `trip` (`:692`), `panMode`/`atRoot` (`:465-473`) and
   `surfaceEpoch` have no external reader: templates arrive and are dropped, while
   [BUILDING_APPS.md](BUILDING_APPS.md#drawing-a-map) promises a lent surface nobody supplies.

3. **`SurfaceContext` is not a complete view model.** It exposes six flows, implemented inline in
   `GearslipRunner.kt:1241-1249`. Media playback (`media/CarMediaStatus.kt`), calls
   (`call/CarPhoneStatus.kt`), notifications (`notify/CarNotifications.kt`), navigation turns
   (`notify/PhoneNavigation.kt`), sensor readings (`car/CarSensors.kt`), hosted-app templates and
   status, the car-app catalog (`host/CustomCarApps.kt`), the alert/toast queues
   (`car/CarAlerts.kt:26`, `car/CarToasts.kt:24`) and the music player's state
   (`music/playback/MusicPlayer.kt`) are all session state a renderer cannot see. Rebuilding the old
   car UI as a plug-in is impossible without widening this boundary.

4. **Plug-in callbacks are failure-isolated.** `SurfaceGuard` wraps every surface and frame-source
   call, including capability reads, `attach`, `detach`, focus, input, keyframe requests, bitrate
   reads and writes, `start` and `stop`; a failure is logged and dropped instead of reaching the
   `run()` catch-all. A reusable throwing-plug-in suite lives in
   `surface/SurfaceFailureIsolationTest.kt`. The remaining gap is `FrameOutput`: the only
   implementation is the anonymous object at `:1254` (no test fake implements it), so a plug-in
   that writes bytes directly into the encoder still has no buffer between it and the transport.
   A plug-in must never be able to end a drive.

5. **Session state is process-global, not session-scoped.** `object SessionStatus`,
   `object CarEnvironment`, `object CarServices`, `object CarKeys`, `object CarData`,
   `object CarFocus`, the alert/toast objects, `CarNotifications`, `PhoneNavigation`, `MusicPlayer`,
   `LocationKeepAlive` and the wireless callback holders are shared across sessions. Nothing
   enforces the "read-only" claim in `SurfaceContext.kt:12`: their mutators are public, and a plug-in
   could call `CarFocus.exitToCar()` (`CarFocus.kt:21`, no current caller) or a `CarSettings` setter.
   `CarSettings.setPipeAudio()` (`:494-505`) even reaches the audio transport from a settings toggle.
   That is acceptable for one headless session but blocks multiple plug-ins, parallel sessions and
   clean tests. State needs a session owner with a generation.

6. **The registry has a generation but no swap routing.** `HeadunitSurfaces.generation()` bumps on
   every install/clear; a `SessionState` pins it at attach and stops publishing after a swap. There
   is still no version negotiation or multi-surface routing: one surface owns the screen end to end.

7. **Only encoded frames are accepted by default.** `FrameSource` still requires the plug-in to
   produce H.264. `PROVIDES_PIXELS` and `PixelLeaseProvider` now let a renderer supply a Surface that
   the core lends to hosted apps, but no encoder-backed pixel frame source ships yet.

8. **UI policy still lives in the core.** `car/CarKeys.kt:59-100` consumes media and call keys itself
   and publishes `screenKeys` to a flow with no subscriber; `CarFocus.exitToCar()` is also orphaned.
   Keycodes are now plain `surface/api/KeyCodes.kt` integers in the core and the plug-in contract;
   `CarMedia` still builds a `KeyEvent` only for the Android media-session Intent it must send.
   `HeadunitSurface.onKey` can now consume a key before core acts on it; per-renderer custom key
   maps are still pending.

9. **Isolation is not build-enforced.** Everything is in one module;
   `gearslip/build.gradle.kts:239-244` declares Compose alongside protocol dependencies. Phase 0
   added `surface/ArchitectureTest.kt` to fail on View/Compose/renderer imports in core packages,
   but the module split itself is still pending. The unused `maplibre`/`okhttp` dependencies, their
   comments, and the `androidTest` declarations (no source set existed) were removed in Phase 0 and
   the Phase 4 residue cleanup (no `org.maplibre` or `okhttp3` imports ever existed in
   `gearslip/src`). Bitmap work also sits in core helpers — `host/CarModels.kt:76` keeps a global
   `LruCache<String, Bitmap>` and `:85` returns a `Bitmap` — so pure screen models need rasterization
   moved into the renderer.

10. **Test coverage has holes at exactly the migration seam.** `SurfacePluginTest` covers selection,
    basic routing and detach; `SurfaceFailureIsolationTest` covers the factory path, throwing plug-ins
    and generation swaps; `SurfaceForwardingTest` covers focus, bitrate forwarding and trusted vs
    untrusted key interception and sync-frame forwarding; and `UpdateDecisionTest` covers generation
    bumps and queued installs. Still untested: attach-under-stop and the factory-selected surface's
    exact frame-source routing.

11. **Docs and comments were stale about rendering.** [BUILDING_APPS.md](BUILDING_APPS.md#drawing-a-map)
    described a virtual display and chrome overlays that no longer exist, said Gearslip renders
    third-party screens in Compose, and claimed Gearslip draws its own keyboard and media UI.
    [CONTRIBUTING.md](../CONTRIBUTING.md#pull-requests) now reflects that feature PRs land on
    `main`; it previously directed work to `experimental`. Those sections are corrected here.
    Remaining drift was: `host/CarAppConnection.kt` comments about a Presentation/VirtualDisplay
    stage (corrected here), stale build-script comments, empty car-UI string headings, duplicate
    session text, unused speech `<queries>` and the orphaned log-share `FileProvider` (all removed in
    the earlier Phase 4 residue cleanup). No stale rendering claims remain in those comments.

12. **Most of the phone UI still bypasses the command layer.** `AppCommands`/`SurfaceCommands` now
    route audio choice and renderer commands, but composables still call core globals directly:
    `ui/SettingsScreen.kt:289-351,403-556`, `ui/HomeCarSettings.kt:99-435`,
    `ui/BluetoothAddressCard.kt`, `ui/WirelessButton.kt` and `ui/SetupGuideScreen.kt:335-347` mutate
    `AppSettings`, `CarSettings`, `CarBluetooth`, `WirelessLink` and `AndroidAuto`.
    `GearslipActivity.kt:128` wires `WirelessLink.sessionWaiting` to a `GearslipRunner` field, and
    `:302,356` calls `SessionStatus.failed()` from the UI.

13. **Dead UI state still has an owner but no consumer.** `CarEnvironment`'s unread `phoneDark`,
    `display` and `battery` flows and the battery receiver, and `PermissionActivity.permissionResults`,
    were pruned; the log-share `FileProvider` went with the Phase 4 residue cleanup. `CarSettings`
    still holds roughly 35 flows, many unread (`scale`, `playerTitle`, `navBarMode`, `screensaver`,
    `whatsNew*`, …); `CarSensors`, `CarNotifications`, `PhoneNavigation` and
    `GearslipLog.version/snapshot()` remain live but undrawn. This remaining dead weight belongs in
    a later prune, not in a plug-in SDK.

## State boundary inventory

The renderer must see session state, but it must not own it. The state owners below are currently
process-global; each needs a session-scoped replacement before a plug-in can be trusted. The last
row is an explicit exception: `UpdateChecker`, `WirelessLink` and `PermissionActivity` stay in the
app. Only their callbacks and generation need tightening, not a session-scoped replacement.

| Current owner | State it holds | Destination |
| --- | --- | --- |
| `SessionStatus` | phase, headlines, stuck-on link | Immutable session snapshot; user-facing text belongs to the renderer |
| `CarEnvironment` | frame, insets, night, vehicle | Snapshot; unread fields already pruned |
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

- Version field and capability enum are in place; focus routing for more than one surface is still
  pending.
- The command entry point (`AppCommands`/`SurfaceCommands`) is in place and `AudioChoiceDialog` now
  uses it; the rest of the phone UI still calls core singletons directly (finding 12).
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
- **Done:** residue from the Phase 0 dependency removal was cleaned up: the MapLibre ABI-split and
  packaging comments/config, the unused `androidTest` declarations, the Nothing-UI comment, unused
  recognition/TTS `<queries>`, empty car-UI string headings and the orphaned log-share
  `FileProvider` (no screen calls `GearslipLog.shareableFile`). Phase 0 removed the dependencies
  themselves; this phase does not repeat that step.
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

## Remaining gaps and blockers

These items are still open after the incremental phases above. They are recorded here rather than
silently dropped; each names the work that is actually blocking it.

- **Session-owned state (Phase 1 / finding 5).** The renderer now gets a session-scoped, redacted
  snapshot, but `SessionStatus`, `CarServices`, `CarKeys`, `CarData` and the alert/notification
  owners are still process-wide. Moving them needs a session owner that owns mutation and a
  generation, so parallel sessions and clean tests remain blocked until that refactor lands.
- **Multi-surface routing and negotiation (Phase 2).** One surface still owns focus and input for
  the whole session; `version` exists but no negotiation or mismatch refusal is implemented, and
  renderers cannot supply their own key maps (finding 8).
- **Encoder-backed pixel frames (Phase 3 / finding 7).** The lease API and `attachSurface` caller
  exist, but no `FrameSource` consumes the renderer's pixels into H.264. That needs a MediaCodec
  input-surface implementation and frame-pacing measurements on a device; it cannot be validated by
  the pure-JVM suite.
- **Module split (Phase 4).** Moving `:core-api`, `:protocol-core`, `:car-app-host` and the two
  renderer modules must keep one application id, signing key and monotonic version code, and must
  update every CI task path in the same change. This is a coordinated build/CI change, not a small
  PR, and is blocked on scheduling that window.
- **External plug-ins and WebView renderer (Phase 5).** The binder spec, permission/signing
  allowlist and WebView reference renderer need a security decision about which external callers may
  receive the redacted snapshot and submit commands. That decision is the blocker for implementation.
- **Remaining test seams (finding 10).** Attach-under-stop and the factory-selected surface's exact
  frame-source routing still lack tests. Driving the successful start path needs Android's main
  looper, so covering the routing case needs an instrumentation test, not another pure-JVM case.

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
