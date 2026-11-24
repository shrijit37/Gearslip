package app.seb3thehacker.gearslip.update

import org.json.JSONObject

/**
 * The dev channel's index: one small JSON file published by CI, keyed by ref. A ref is a branch
 * name or `pr-<number>`; each entry pins one commit and the APKs built from it. The updater reads
 * the ref the driver picked, compares the commit with this build's [app.seb3thehacker.gearslip.BuildConfig.BUILD_SHA],
 * and only offers a build whose bytes it can check against a SHA-256 in the feed.
 *
 * Parsing is the only part that touches org.json (a stub in JVM unit tests); [select] and
 * [pickAsset] are plain functions so the choice can be tested without Android.
 */
internal object DevFeed {

    const val URL = "https://raw.githubusercontent.com/shrijit37/Gearslip/dev-feed/manifest.json"
    const val PAGE_URL = "https://github.com/shrijit37/Gearslip/releases/tag/dev-builds"

    internal data class Asset(val name: String, val url: String, val size: Long, val sha256: String?)
    internal data class Build(
        val ref: String,
        val sha: String,
        val version: String,
        val assets: List<Asset>,
        /** The APK's version code, published by CI as "seq" so installs can be checked for downgrades. */
        val versionCode: Int = -1,
    )
    internal data class Feed(val builds: List<Build>)

    internal fun parse(body: String): Feed {
        val root = JSONObject(body)
        require(root.optInt("schema", -1) == 1) { "Unknown dev feed format." }
        val refs = root.optJSONObject("refs") ?: return Feed(emptyList())
        val builds = refs.keys().asSequence().map { ref ->
            val json = refs.getJSONObject(ref)
            val files = json.optJSONArray("assets")
            val assets = if (files == null) emptyList() else (0 until files.length()).map { i ->
                val file = files.getJSONObject(i)
                Asset(
                    name = file.optString("name"),
                    url = file.optString("url"),
                    size = file.optLong("size", -1),
                    sha256 = file.optString("sha256").takeIf { it.length == 64 },
                )
            }
            Build(
                ref,
                json.optString("sha"),
                json.optString("version"),
                assets,
                json.optInt("seq", -1),
            )
        }.toList()
        return Feed(builds)
    }

    /** The chosen build with only the one verified APK the phone can use, or null for no update. */
    internal fun select(
        feed: Feed,
        ref: String,
        installedSha: String,
        supportedAbis: List<String>,
        installedVersionCode: Int = -1,
    ): Build? {
        val build = feed.builds.firstOrNull { it.ref == ref } ?: return null
        if (build.sha.equals(installedSha, ignoreCase = true)) return null
        // Channel switches install out of chronological order. A dev APK built before the
        // installed one has a lower version code, which Android would refuse at install time.
        // Equal codes are installable, and CI stamps them per minute, so skip only strict
        // downgrades — otherwise a fix force-published in the same minute is never offered.
        if (installedVersionCode >= 0 && build.versionCode in 0 until installedVersionCode) return null
        val verified = build.assets.filter { !it.sha256.isNullOrBlank() }
        val name = pickAsset(verified.map { it.name }, supportedAbis) ?: return null
        return build.copy(assets = listOf(verified.first { it.name == name }))
    }

    /** Dev assets are named `Gearslip-<abi>-dev-<sha>.apk`, with a universal build as fallback. */
    internal fun pickAsset(assetNames: List<String>, supportedAbis: List<String>): String? {
        for (abi in supportedAbis) {
            val prefix = "Gearslip-$abi-dev-"
            assetNames.firstOrNull { it.startsWith(prefix) && it.endsWith(".apk") }?.let { return it }
        }
        return assetNames.firstOrNull { it.startsWith("Gearslip-universal-dev-") && it.endsWith(".apk") }
    }
}
