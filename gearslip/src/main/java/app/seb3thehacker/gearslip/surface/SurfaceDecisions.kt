package app.seb3thehacker.gearslip.surface

/** Pure decisions about which renderer capabilities may affect core state. */
object SurfaceDecisions {
    /**
     * Lending a Surface to a hosted car app is a privileged act: only a trusted, current renderer
     * that explicitly declares [SurfaceCapability.PROVIDES_PIXELS] may do it.
     */
    fun mayLendPixels(
        trusted: Boolean,
        stale: Boolean,
        capabilities: Set<SurfaceCapability>,
    ): Boolean = trusted && !stale && SurfaceCapability.PROVIDES_PIXELS in capabilities
}
