# UI as a plug-in: target architecture

> **Status: design target, not shipped.** The contract that exists today is documented in
> [HEADUNIT_SURFACES.md](HEADUNIT_SURFACES.md). The gap between today and this target, with
> file-level evidence and a phased plan, is in [PLUGIN_MIGRATION.md](PLUGIN_MIGRATION.md).

## Why this direction

Gearslip installs itself from its own updater and drives a car over AAP. A bad release can leave a
phone unable to reconnect, so the connection and the updater must not depend on the shape of a
screen. The headless refactor made that possible: the core owns the transport, the frame clock and
input decoding; a head-unit renderer is a plug-in.

The previous design put a Compose tree inside the app process and projected it through a
`VirtualDisplay` + `Presentation` into the MediaCodec input surface. It gave total pixel control
but coupled UI lifecycle, the main thread and platform display permissions into the session.
Interactive HTML/CSS/JS mocks were design instruments that were hand-translated into Compose, not
a runtime frontend, and the only WebView code in history was a car browser with a small focus-tracking
JS bridge.

The target keeps the "full control" property without the coupling: any renderer — Compose, a
canvas, a WebView running HTML/CSS/JS, or a process on the other side of a binder — implements one
versioned contract. The core does not change when the renderer changes.

## Layer model

```
 Phone app (Compose)          Car surface plug-ins               Updater
 setup, logs, settings        Compose | WebView | canvas |       stable/dev channels,
                              out-of-process app                digest + version checks
        |                            |                              |
        +--------- plug-in API: immutable state, commands, ----------+
                          capabilities, versions
                                    |
              session: GearslipRunner, hosted car apps,
              media/call/notification channels, frame clock
                                    |
              protocol core: TLS, certificates, discovery,
              framing, input decoding, vehicle data
```

Two extension boundaries serve different audiences:

| Boundary | Who uses it | Status |
| --- | --- | --- |
| `HeadunitSurface` (in-process Kotlin) | A renderer compiled into the app or supplied by a library module | Contract exists; no surface is installed in the app today |
| Gearslip apps (binder/AIDL, JSON screen models) | Third-party APKs that cannot be compiled into Gearslip | Specified in [BUILDING_APPS.md](BUILDING_APPS.md); not implemented |

The second boundary should be built **on top of** the first, not instead of it: an external app's
screen model becomes one source of state for the in-process surface to render.

## Isolation rules

**The core may:** own sockets, TLS, codecs, audio, service bindings and session-scoped state; publish
immutable snapshots; accept commands through one entry point; check capabilities before forwarding
input; fall back to the placeholder when no renderer is attached.

**The core may not:** import `androidx.compose`, `android.view.View`, `android.widget`, or a concrete
UI package; hold a reference to a renderer's view hierarchy; assume a renderer exists; let a
renderer throw into the transport loop.

**A plug-in may:** render state, produce frames, forward input, and request core actions through the
command API.

**A plug-in may not:** open a car connection, write protocol messages, mutate core state directly, or
keep state that outlives its session. Callbacks arrive off the main thread; a UI implementation
dispatches to its own main thread.

These rules are an architectural contract, not yet a build-enforced one. [PLUGIN_MIGRATION.md](PLUGIN_MIGRATION.md)
proposes a JVM architecture test to enforce the import boundary.

## Contract pieces

1. **Version and capabilities.** A plug-in declares a protocol version and what it handles:
   frames or pixels, touch, keys, hosted app surfaces, its own settings page. `HeadunitSurface`
   now exposes a default `version = 1` and three capabilities (`PROVIDES_FRAMES`, `TOUCH`, `KEYS`);
   version negotiation, mismatch refusal and the remaining capabilities are still pending.

2. **State snapshots.** Everything a renderer needs is an immutable, pure-Kotlin value produced by
   the core: session phase, frame geometry, crop insets, day/night, vehicle data and profile, plus
   the parts not exposed yet — media playback, calls, notifications, hosted-app templates and
   status, alert/toast queues, car-app catalog and surface leases. `SurfaceContext` currently
   exposes six `StateFlow`s; it must cover the whole session view model before a real renderer can
   replace the old UI.

3. **Commands.** One sealed-command entry point (`mediaCommand`, `navigateTo`, `launchApp`,
   `dismissAlert`, `requestTextInput`, `setInsets`, `focus`, `finish`, …) replaces direct calls into
   core singletons. A command is validated, version-checked and logged in one place. Because
   commands and state are plain data, a WebView plug-in can serialize them to JSON and an external
   plug-in can carry them over a binder.

4. **Frame production.** Two modes, both owned by the session:
   - `PROVIDES_FRAMES` — the plug-in returns a `FrameSource` that writes H.264 bytes to a core-owned
     `FrameOutput`. Lowest latency, but the plug-in owns an encoder.
   - `PROVIDES_PIXELS` *(proposed)* — the core lends a `Surface` backed by the encoder and the plug-in
     draws into it. This is the cheap path for Compose or WebView renderers and avoids asking every
     plug-in to reimplement bitrate control, keyframe resync and ack handling.

   The existing placeholder is a `FrameSource` that feeds YUV buffers straight into `MediaCodec`
   without a drawing surface. It stays the fallback for a session with no plug-in.

5. **Surface lease for hosted apps.** Navigation apps that draw their own maps need a `Surface`,
   lent through `CarAppConnection.attachSurface()`. The lease must be requested by the core and
   supplied by the attached plug-in, with an epoch that survives a dead surface. Today
   `attachSurface()` has no caller, so those apps have nowhere to draw.

6. **Lifecycle and generations.** A plug-in instance belongs to one session: created at attach,
   detached at stop or EOF. Swapping a plug-in or its settings must bump a generation counter just
   as a channel or dev-ref change does in the updater; late work must re-check it before publishing
   state or touching the transport. A failing plug-in must degrade to the placeholder, never bring
   down the session.

## Renderer possibilities

| Renderer | How it satisfies the contract | Cost / caveat |
| --- | --- | --- |
| Placeholder (shipped) | Generated YUV → `MediaCodec`, no drawing surface | Cheap and stable; not a usable car UI |
| Compose (old UI, as a plug-in) | Draws into a core-lent surface via a virtual display owned by the plug-in | Familiar code; main thread and compositor cost return inside the plug-in |
| WebView / HTML-CSS-JS | Renders to a canvas or lent surface; bridge maps state events and commands to JSON | Fastest UI iteration; frame pacing and JS-thread latency must be measured |
| Canvas / custom GL | Produces pixels or H.264 directly | Maximum control, most code |
| Out-of-process app | Binder screen models and commands | Strongest isolation; new SDK, permissions and versioning |

## Trade-offs

| | Bundled Compose UI (before) | Headless core + plug-in (now) | HTML/JS plug-in (target option) |
| --- | --- | --- | --- |
| Frame path | Virtual display → Presentation → compose → encode | YUV → encoder for the placeholder; plug-in chooses for real frames | WebView/composite → copy → encode |
| UI iteration | Recompile the whole app | Replace one plug-in module | Web assets only |
| Car compatibility risk | UI work could disturb the session | Very low; car sees only AAP + H.264 | Very low if the plug-in honors the contract |
| Stability risk | UI bug = session bug | Plug-in bug can still kill the session until failure isolation lands | Same, plus bridge bugs |
| Testability | Instrumentation-heavy | Pure-JVM decision and routing tests | Needs WebView instrumentation for pixels |
| Product readiness | Had a full car UI | No renderer is wired | Proposal only |

The architecture buys isolation and freedom of renderer choice, not free performance. A real
plug-in has to produce frames on deadline; the H.264 path, not the plug-in framework, sets that
budget. Prototype the slowest intended renderer early rather than assuming the web path will fit.
