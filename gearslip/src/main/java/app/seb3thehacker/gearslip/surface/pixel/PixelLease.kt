package app.seb3thehacker.gearslip.surface.pixel

import android.view.Surface
import app.seb3thehacker.gearslip.surface.VideoSpec

/**
 * A core-owned encoder surface lent to a pixel-mode renderer.
 *
 * This is the one surface-layer type allowed to name `android.view.Surface`: it is not a UI
 * hierarchy type, and the car-app host needs the same lease for templated navigation apps. The
 * renderer draws into [surface]; the core keeps bitrate control, keyframe resync and the transport.
 */
data class PixelLease(
    val surface: Surface,
    val width: Int,
    val height: Int,
    val densityDpi: Int,
)

/** Supplies a pixel lease for one session. The placeholder returns null. */
interface PixelLeaseProvider {
    fun acquire(spec: VideoSpec): PixelLease?
    fun release(lease: PixelLease)
}
