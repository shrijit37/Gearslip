package app.seb3thehacker.gearslip.surface

/** The video format the car negotiated and the link the frames will travel over. */
data class VideoSpec(
    val width: Int,
    val height: Int,
    val frameRate: Int,
    val densityDpi: Int,
    val wireless: Boolean,
)

/** Where a [FrameSource] sends its encoded bytes. Owned by the AAP session. */
interface FrameOutput {
    fun onCodecConfig(csd: ByteArray)
    fun onFrame(data: ByteArray, presentationTimeUs: Long, keyFrame: Boolean)
}

/**
 * Produces the H.264 byte stream the car decodes.
 *
 * The session does not care whether the bytes come from a generated placeholder or from a
 * surface that a head-unit UI draws into. A plug-in surface can supply its own implementation;
 * with none installed the session uses [PlaceholderFrameSource].
 */
interface FrameSource {
    /** Current encoder bitrate in bits/s, for session stats and adaptive bitrate decisions. */
    val bitRate: Int

    /** Highest bitrate this source will use, the ceiling adaptive bitrate climbs back to. */
    val fullBitRate: Int

    fun start(output: FrameOutput)
    fun requestSyncFrame()
    /** Ask the source to encode at [bps]; it may clamp to its supported range. */
    fun applyBitRate(bps: Int)
    fun stop()
}
