package app.seb3thehacker.gearslip.surface

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Keeps the UI-free core boundary honest.
 *
 * The plug-in migration's Phase 0 turns "core code never imports a View" from a review convention
 * into a test. It runs on the JVM and reads the checked-in Kotlin sources, so it needs no Android
 * stubs and fails in CI the moment a view-layer import lands in a core package.
 *
 * `android.view.Surface` is allowed only in `host/`, which needs it for the car-app surface lease;
 * it stays forbidden in `surface/api` and every other guarded package so the pure API remains
 * Android-free. Keycodes and bitmaps are out of scope until Phase 1; banning them here would fail
 * the existing tree.
 */
class ArchitectureTest {

    @Test fun `core packages contain no view-layer imports`() {
        val root = mainSourceRoot()
        val coreFiles = root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .map { it to it.relativeTo(root).path.replace(File.separatorChar, '/') }
            .filter { (_, relative) -> CORE_PREFIXES.any(relative::startsWith) }
            .toList()

        assertTrue("architecture guard found no core sources under ${root.path}", coreFiles.isNotEmpty())

        val violations = coreFiles.flatMap { (file, relative) ->
            file.readLines().mapIndexed { index, line -> relative to (index + 1 to line) }
                .mapNotNull { (path, numbered) ->
                    forbiddenImport(numbered.second, path)?.let { "$path:${numbered.first} imports $it" }
                }
        }
        assertTrue("UI imports leaked into the core:\n${violations.joinToString("\n")}", violations.isEmpty())
    }

    @Test fun `the guarded packages include the surface contract itself`() {
        assertTrue("surface/ must cover HeadunitSurface and SurfaceContext too", "surface/" in CORE_PREFIXES)
    }

    @Test fun `the guard rejects view imports everywhere but allows Surface in the host`() {
        val hostPath = "host/CarAppConnection.kt"
        val apiPath = "surface/api/SurfaceModels.kt"
        assertTrue(forbiddenImport("import androidx.compose.material3.Text") != null)
        assertTrue(forbiddenImport("import androidx.compose.runtime.Composable") != null)
        assertTrue(forbiddenImport("import android.view.View") != null)
        assertTrue(forbiddenImport("import android.view.ViewGroup") != null)
        assertTrue(forbiddenImport("import android.view.ViewGroup.LayoutParams") != null)
        assertTrue(forbiddenImport("import android.view.ViewGroup.*") != null)
        assertTrue(forbiddenImport("import android.view.*") != null)
        assertTrue(forbiddenImport("import android.*") != null)
        assertTrue(forbiddenImport("import android.widget.Button") != null)
        assertTrue(forbiddenImport("import app.seb3thehacker.gearslip.ui.HomeScreen") != null)
        assertTrue(forbiddenImport("import app.seb3thehacker.gearslip.renderer.ComposeSurface") != null)

        // The car-app host needs Surface for the lease; surface/api must stay Android-free.
        assertEquals(null, forbiddenImport("import android.view.Surface", hostPath))
        assertTrue(forbiddenImport("import android.view.Surface", apiPath) != null)
        assertTrue(forbiddenImport("import android.view.Surface", "media/CarMedia.kt") != null)
        assertEquals(null, forbiddenImport("import kotlinx.coroutines.flow.StateFlow", apiPath))
        assertEquals(null, forbiddenImport("import app.seb3thehacker.gearslip.surface.api.SessionSnapshot", apiPath))
        assertEquals(null, forbiddenImport("// import android.view.View in a comment", apiPath))
    }

    private fun forbiddenImport(line: String, path: String = ""): String? {
        val trimmed = line.trim()
        if (!trimmed.startsWith("import ")) return null
        val name = trimmed.removePrefix("import ").substringBefore(" as ").trim()
        return when {
            name.startsWith("androidx.compose.") -> name
            name == "android.view.View" || name == "android.view.ViewGroup" ||
                name.startsWith("android.view.View.") || name.startsWith("android.view.ViewGroup.") ||
                name == "android.view.*" || name == "android.*" -> name
            name.startsWith("android.widget.") -> name
            // Explicitly allowed only in the host, which holds the car-app surface lease.
            name == "android.view.Surface" && !path.startsWith("host/") -> name
            name.startsWith("app.seb3thehacker.gearslip.ui.") -> name
            name.startsWith("app.seb3thehacker.gearslip.renderer.") -> name
            else -> null
        }
    }

    /** Finds the module source root whether Gradle runs tests from the module or the repo root. */
    private fun mainSourceRoot(): File {
        val workingDir = System.getProperty("user.dir") ?: "."
        var dir = File(workingDir).absoluteFile
        repeat(6) {
            // Gradle runs unit tests from the module directory; a plain JVM launch may use the
            // repository root. Try both layouts at each level before walking up.
            val candidates = listOf(
                File(dir, "src/main/java/app/seb3thehacker/gearslip"),
                File(dir, "gearslip/src/main/java/app/seb3thehacker/gearslip"),
            )
            candidates.firstOrNull { it.isDirectory }?.let { return it }
            dir = dir.parentFile ?: error("Could not locate the Gearslip source root from $workingDir")
        }
        error("Could not locate the Gearslip source root from $workingDir")
    }

    private companion object {
        val CORE_PREFIXES = listOf(
            "protocol/", "car/", "host/", "media/", "call/", "notify/", "update/", "surface/",
        )
    }
}
