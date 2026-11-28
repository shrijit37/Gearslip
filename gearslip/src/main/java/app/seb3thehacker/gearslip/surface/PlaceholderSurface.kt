package app.seb3thehacker.gearslip.surface

/**
 * The headless surface installed at the composition root until a real renderer is chosen.
 *
 * It declares no capabilities and returns no frame source, so the runner keeps generating the
 * placeholder H.264 frame and drops input exactly as it did when no surface was installed. Having
 * an explicit instance means the documented `install` call exists and there is one place to swap in
 * a Compose or WebView renderer later.
 */
object PlaceholderSurface : HeadunitSurface {
    override val id = "placeholder"
    override val capabilities: Set<SurfaceCapability> = emptySet()
}
