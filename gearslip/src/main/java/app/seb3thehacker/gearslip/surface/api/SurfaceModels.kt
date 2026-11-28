package app.seb3thehacker.gearslip.surface.api

/**
 * Immutable session values shared by the protocol core and any head-unit surface.
 *
 * This package is deliberately pure Kotlin: no Android, Compose, View or renderer type appears in
 * it, so a surface in another process can serialize these values and a JVM test can construct them
 * without Android stubs. Phase 0 freezes the vocabulary; later phases widen `SurfaceContext` to
 * publish it and add the remaining state snapshots (media, calls, templates and so on) here.
 */

/** Connection phase a surface can render, independent of the phone UI's wording. */
enum class SessionPhase { IDLE, CONNECTING, PROJECTING, FAILED, DISCONNECTED }

/** Which physical link a "still connecting" hint refers to. */
enum class LinkType { CABLE, WIRELESS }

/** A complete, immutable view of the session's connection state. */
data class SessionSnapshot(
    val phase: SessionPhase,
    val headline: String,
    val detail: String,
    val stuckOn: LinkType? = null,
)

/** The video format the car negotiated, including the link it travels over. */
data class FrameGeometry(
    val width: Int,
    val height: Int,
    val frameRate: Int,
    val densityDpi: Int,
    val wireless: Boolean,
)

/** Pixels of the frame the car crops away; a surface keeps its content inside these. */
data class ScreenInsets(val top: Int, val bottom: Int, val left: Int, val right: Int) {
    companion object {
        val NONE = ScreenInsets(0, 0, 0, 0)
    }
}

/** A decoded GPS fix, with the same units the sensor channel publishes. */
data class VehicleLocation(
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Float?,
    val altitudeMeters: Double?,
    val speedMps: Float?,
    val bearingDegrees: Float?,
)

/** Decoded vehicle readings a renderer may show; null means the car never sent the value. */
data class VehicleSnapshot(
    val speedMps: Float? = null,
    val odometerKm: Float? = null,
    val fuelPercent: Int? = null,
    val rangeKm: Int? = null,
    val fuelLow: Boolean? = null,
    val compassDegrees: Float? = null,
    val location: VehicleLocation? = null,
)

/** What the car says it is, from service discovery. */
data class VehicleModel(val name: String, val year: Int?)
