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
 * `android.view.Surface` is explicitly allowed: the car-app host needs it for the surface lease
 * the target architecture keeps, and it is not a UI hierarchy type. Keycodes and bitmaps are out
 * of scope until Phase 1; banning them here would fail the existing tree.
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
                    forbiddenImport(numbered.second)?.let { "$path:${numbered.first} imports $it" }
                }
        }
        assertTrue("UI imports leaked into the core:\n${violations.joinToString("\n")}", violations.isEmpty())
    }

    @Test fun `the guard rejects view and renderer imports but allows Surface`() {
        assertTrue(forbiddenImport("import androidx.compose.material3.Text") != null)
        assertTrue(forbiddenImport("import androidx.compose.runtime.Composable") != null)
        assertTrue(forbiddenImport("import android.view.View") != null)
        assertTrue(forbiddenImport("import android.view.ViewGroup") != null)
        assertTrue(forbiddenImport("import android.widget.Button") != null)
        assertTrue(forbiddenImport("import app.seb3thehacker.gearslip.ui.HomeScreen") != null)
        assertTrue(forbiddenImport("import app.seb3thehacker.gearslip.renderer.ComposeSurface") != null)

        assertEquals(null, forbiddenImport("import android.view.Surface"))
        assertEquals(null, forbiddenImport("import kotlinx.coroutines.flow.StateFlow"))
        assertEquals(null, forbiddenImport("import app.seb3thehacker.gearslip.surface.api.SessionSnapshot"))
        assertEquals(null, forbiddenImport("// import android.view.View in a comment"))
    }

    private fun forbiddenImport(line: String): String? {
        val trimmed = line.trim()
        if (!trimmed.startsWith("import ")) return null
        val name = trimmed.removePrefix("import ").substringBefore(" as ").trim()
        return when {
            name.startsWith("androidx.compose.") -> name
            name == "android.view.View" || name == "android.view.ViewGroup" -> name
            name.startsWith("android.widget.") -> name
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
            val candidate = File(dir, "src/main/java/app/seb3thehacker/gearslip")
            if (candidate.isDirectory) return candidate
            dir = dir.parentFile ?: error("Could not locate the Gearslip source root from $workingDir")
        }
        error("Could not locate the Gearslip source root from $workingDir")
    }

    private companion object {
        val CORE_PREFIXES = listOf(
            "protocol/", "car/", "host/", "media/", "call/", "notify/", "update/", "surface/api/",
        )
    }
}
