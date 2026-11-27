# Head-unit surfaces

Gearslip's Android Auto Protocol (AAP) core owns the car connection, the frame clock and input
decoding. It does **not** own any pixels. A head-unit UI is a plug-in: implement
`app.seb3thehacker.gearslip.surface.HeadunitSurface`, register it once in the phone app, and the
session will drive it. The core contains no Compose, View or drawing code.

## The contract

`HeadunitSurface` declares:

- `id` — used in session logs.
- `capabilities` — `TOUCH`, `KEYS`, `PROVIDES_FRAMES`. The core forwards input only when the
  surface declares it can handle it.
- `attach(context: SurfaceContext, spec: VideoSpec): FrameSource?` — called once per session with
  the negotiated frame size/density and a read-only view of session state. Return a `FrameSource`
  only when `PROVIDES_FRAMES` is declared.
- `detach()` — called when the session ends; release everything here.
- `onFocus(projected: Boolean)` — the car took its screen back, or handed it over again.
- `onTouch(action, actionIndex, points)` / `onKey(keyCode, down, longPress)` / `onRotate(delta)` —
  head-unit input, in frame coordinates. Callbacks may arrive off the main thread; a UI
  implementation must dispatch to its own main thread.

`SurfaceContext` exposes read-only `StateFlow`s for connection phase, frame geometry, crop insets,
day/night, decoded vehicle data and the matched vehicle profile. Nothing can be pushed back into
the protocol through it.

## Frames

The session always owns the H.264 byte stream sent to the car. By default it uses
`PlaceholderFrameSource`, which generates colour bars. A surface that declares `PROVIDES_FRAMES`
returns its own `FrameSource` from `attach`; the session starts it, routes codec config and encoded
frames to the AAP media channel, and stops it on detach. The frames themselves still travel over
the protocol's `FrameOutput` — a surface never writes to the car transport directly.

## Registering a surface

In the phone app's composition root (today `GearslipActivity`), before a session starts:

```kotlin
HeadunitSurfaces.install { context -> MyHeadunitSurface(context.applicationContext) }
```

The factory is invoked for each session, so a surface instance never outlives the car connection.
`HeadunitSurfaces.install(surface)` installs a pre-built instance instead, and
`HeadunitSurfaces.clear()` restores the headless placeholder mode. No reflection or service
loading is involved: adding a surface is a compile-time dependency plus one line of wiring.

## Rules

- `surface/` is the only package the session uses for UI concerns; core files must not import
  `androidx.compose`, Android `View`s, or a concrete UI package.
- A surface must not open its own car connection or send protocol messages. It renders state and
  forwards input; the session remains the single owner of the transport.
- Pure-JVM tests for source selection and input routing live under
  `gearslip/src/test/.../surface/` and must not need Android stubs.
