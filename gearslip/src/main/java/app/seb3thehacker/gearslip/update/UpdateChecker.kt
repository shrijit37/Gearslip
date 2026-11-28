package app.seb3thehacker.gearslip.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import app.seb3thehacker.gearslip.BuildConfig
import app.seb3thehacker.gearslip.GearslipLog
import app.seb3thehacker.gearslip.R
import app.seb3thehacker.gearslip.SessionStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * A deliberately small self-updater: the driver taps Check for updates, we ask GitHub, download
 * the APK built for this phone's ABI, check it against GitHub's SHA-256, and hand it to Android's
 * installer. Nothing runs in the background and nothing about the phone is sent anywhere.
 *
 * Releases ship one APK per ABI (plus a universal fallback), so an update is about 20 MB instead
 * of the 35 MB universal build. [pickAsset] chooses which one to download.
 *
 * Two tracks share the app's identity and signing key: stable reads promoted GitHub releases,
 * dev reads a manifest of branch-head builds. Both are manual, and dev compares commits rather
 * than version numbers, since a branch head is not a semver successor of the installed build.
 */
object UpdateChecker {

    data class Release(
        val version: String,
        val apkUrl: String,
        val size: Long,
        val sha256: String?,
        val pageUrl: String,
        val channel: String = CHANNEL_STABLE,
        /** Ref shown on the card: a version tag for stable, a branch or `pr-<n>` for dev. */
        val target: String = "",
        /** The commit the APK was built from, pinned when the update was offered. */
        val buildSha: String = "",
        /** The APK's version code, when the feed publishes it. -1 means unknown. */
        val versionCode: Int = -1,
    )

    sealed interface State {
        data object Idle : State
        data object Checking : State
        data object UpToDate : State
        /** No promoted stable release exists yet, rather than a stable release that is merely older. */
        data object NoStableRelease : State
        data class Available(val release: Release) : State
        data class Downloading(val release: Release, val fraction: Float) : State
        /** [error] is set when starting the install failed, so the card can offer a retry without re-downloading. */
        data class Ready(val release: Release, val apk: File, val error: String? = null) : State
        data class Failed(val message: String, val release: Release? = null) : State
    }

    // /releases/latest only sees a full release, and stable ones sit behind many prereleases
    // from main. Page through the list so a promotion older than the first page is still found.
    private const val RELEASES_URL = "https://api.github.com/repos/shrijit37/gearslip/releases"
    private const val RELEASES_PER_PAGE = 100
    private const val STABLE_PAGES = 5
    private val VERSION_CODE_MARKER = Regex("""<!--\s*gearslip-version-code:\s*(\d+)\s*-->""")
    private const val UNIVERSAL_ASSET = "Gearslip-universal-debug.apk"
    private val ABI_ASSETS = mapOf(
        "arm64-v8a" to "Gearslip-arm64-v8a-debug.apk",
        "armeabi-v7a" to "Gearslip-armeabi-v7a-debug.apk",
        "x86" to "Gearslip-x86-debug.apk",
        "x86_64" to "Gearslip-x86_64-debug.apk",
    )
    private const val EXTRA_VERSION = "version"

    const val CHANNEL_STABLE = "stable"
    const val CHANNEL_DEV = "dev"
    private const val PREFS = "gearslip_updates"
    private const val KEY_CHANNEL = "channel"
    private const val KEY_DEV_REF = "dev_ref"
    private const val DEFAULT_DEV_REF = "main"

    private val flow = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = flow.asStateFlow()

    // Bumped whenever the channel or dev ref changes, so work started for the old target can't
    // finish later and install an APK the driver no longer asked for.
    /** Pure generation/queue state; all access goes through [UpdateDecision]'s synchronized API. */
    private val decision = UpdateDecision()

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, 0)

    /** A build defaults to the channel that produced it; the driver's choice survives updates. */
    fun channel(context: Context): String =
        prefs(context).getString(KEY_CHANNEL, BuildConfig.UPDATE_CHANNEL) ?: BuildConfig.UPDATE_CHANNEL

    fun devRef(context: Context): String =
        prefs(context).getString(KEY_DEV_REF, DEFAULT_DEV_REF) ?: DEFAULT_DEV_REF

    fun setChannel(context: Context, value: String) {
        if (value != CHANNEL_STABLE && value != CHANNEL_DEV || value == channel(context)) return
        prefs(context).edit().putString(KEY_CHANNEL, value).apply()
        reset()
    }

    /** Only refs that can appear in a URL path or a manifest key are accepted. */
    fun validDevRef(value: String): Boolean {
        val ref = value.trim()
        return ref.isNotEmpty() && ref.length <= 100 && ref.matches(Regex("""[A-Za-z0-9._/-]+"""))
    }

    /** Returns false when the ref was rejected, so the caller can keep the old value in view. */
    fun setDevRef(context: Context, value: String): Boolean {
        val ref = value.trim()
        if (!validDevRef(ref)) return false
        if (ref == devRef(context)) return true
        prefs(context).edit().putString(KEY_DEV_REF, ref).apply()
        reset()
        return true
    }

    private fun reset() {
        decision.reset()
        flow.value = State.Idle
    }

    /** Called from Settings. Ignores taps while a check or download is already running. */
    fun check(context: Context) {
        if (flow.value is State.Checking || flow.value is State.Downloading) return
        flow.value = State.Checking
        val app = context.applicationContext
        // Build.SUPPORTED_ABIS is an Android property, so read it here rather than in the pure
        // [newestFrom] helper the unit tests call directly.
        val abis = Build.SUPPORTED_ABIS.toList()
        val checkGeneration = decision.generation
        val target = channel(app)
        background("gearslip-update") {
            val result = runCatching {
                if (target == CHANNEL_DEV) checkDev(app, abis) else checkStable(abis)
            }
            if (!decision.isCurrent(checkGeneration)) return@background
            result.onFailure { GearslipLog.w("update: check failed: ${it.message}") }
            flow.value = result.getOrElse { State.Failed(it.message ?: "Couldn't reach the update feed.") }
        }
    }

    private fun checkStable(abis: List<String>): State {
        val candidates = mutableListOf<Candidate>()
        for (page in 1..STABLE_PAGES) {
            val connection = (URL("$RELEASES_URL?per_page=$RELEASES_PER_PAGE&page=$page").openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 15_000
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", "Gearslip/${BuildConfig.VERSION_NAME}")
            }
            val pageOfReleases = try {
                if (connection.responseCode !in 200..299) error("GitHub answered ${connection.responseCode}")
                connection.inputStream.bufferedReader().use { parseCandidates(it.readText()) }
            } finally {
                connection.disconnect()
            }
            candidates.addAll(pageOfReleases)
            if (pageOfReleases.size < RELEASES_PER_PAGE) break
        }
        return newestFrom(candidates, BuildConfig.VERSION_NAME, abis, BuildConfig.VERSION_CODE)
    }

    /** One manifest read, no GitHub API calls: an unauthenticated app has a small rate limit. */
    private fun checkDev(app: Context, abis: List<String>): State {
        val ref = devRef(app)
        // Raw files are cached; the query makes a fresh head visible after a new publish.
        val url = "${DevFeed.URL}?t=${System.currentTimeMillis()}"
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 15_000
            setRequestProperty("User-Agent", "Gearslip/${BuildConfig.VERSION_NAME}")
            setRequestProperty("Cache-Control", "no-cache")
        }
        try {
            if (connection.responseCode !in 200..299) error("GitHub answered ${connection.responseCode}")
            val feed = connection.inputStream.bufferedReader().use { DevFeed.parse(it.readText()) }
            val build = DevFeed.select(feed, ref, BuildConfig.BUILD_SHA, abis, BuildConfig.VERSION_CODE)
                ?: return State.UpToDate
            val asset = build.assets.first()
            GearslipLog.i("update: dev ${build.sha} on $ref is out (this is ${BuildConfig.BUILD_SHA})")
            return State.Available(
                Release(
                    version = build.version.ifBlank { build.sha },
                    apkUrl = asset.url,
                    size = asset.size,
                    sha256 = asset.sha256,
                    pageUrl = DevFeed.PAGE_URL,
                    channel = CHANNEL_DEV,
                    target = ref,
                    buildSha = build.sha,
                    versionCode = build.versionCode,
                ),
            )
        } finally {
            connection.disconnect()
        }
    }

    /** A GitHub release stripped down to what the choice needs. Kept separate from org.json so
     * the decision can be unit tested on the JVM, where the platform JSON classes are stubs. */
    internal data class Candidate(
        val tag: String,
        val draft: Boolean,
        val pageUrl: String,
        val assets: List<Asset>,
        val prerelease: Boolean = false,
        /** Published by promote.yml in the release body; -1 on older releases without a marker. */
        val versionCode: Int = -1,
    ) {
        internal data class Asset(val name: String, val url: String, val size: Long, val sha256: String?)
    }

    /** Parses the releases API response. The only part that touches Android's org.json. */
    private fun parseCandidates(body: String): List<Candidate> {
        val releases = JSONArray(body)
        return (0 until releases.length()).map { releases.getJSONObject(it) }.map { json ->
            val assets = json.optJSONArray("assets")
            val files = if (assets == null) emptyList() else (0 until assets.length()).map { assets.getJSONObject(it) }
            Candidate(
                tag = normalizeTag(json.optString("tag_name")),
                draft = json.optBoolean("draft"),
                prerelease = json.optBoolean("prerelease"),
                assets = files.map { file ->
                    Candidate.Asset(
                        name = file.optString("name"),
                        url = file.optString("browser_download_url"),
                        size = file.optLong("size", -1),
                        sha256 = file.optString("digest").takeIf { it.startsWith("sha256:") }?.removePrefix("sha256:"),
                    )
                },
                pageUrl = json.optString("html_url"),
                versionCode = VERSION_CODE_MARKER.find(json.optString("body"))?.groupValues?.get(1)?.toIntOrNull() ?: -1,
            )
        }
    }

    /**
     * CI names a build after the newest hand-written tag plus its commit count, e.g.
     * "v0.4.0-alpha-30-g5e6c76e", while Gradle gives that same build the version name
     * "0.4.0-alpha+30.5e6c76e". Put both in one form before comparing them.
     */
    internal fun normalizeTag(tag: String): String {
        val version = tag.removePrefix("v")
        val postTag = Regex("""^(.*?)-(\d+)-g([0-9a-f]+)$""").find(version) ?: return version
        return "${postTag.groupValues[1]}+${postTag.groupValues[2]}.${postTag.groupValues[3]}"
    }

    /** Picks the newest published release that has an APK for this phone, if it's newer than [installedVersion]. */
    internal fun newestFrom(
        candidates: List<Candidate>,
        installedVersion: String,
        supportedAbis: List<String>,
        installedVersionCode: Int = -1,
    ): State {
        // Main builds and dev builds are both prereleases; only a promoted tag is stable.
        val newestFirst = candidates
            .filterNot { it.draft || it.prerelease }
            .sortedWith { a, b ->
                when {
                    isNewer(a.tag, b.tag) -> -1
                    isNewer(b.tag, a.tag) -> 1
                    else -> 0
                }
            }
        if (newestFirst.isEmpty()) return State.NoStableRelease
        for (release in newestFirst) {
            // A promoted APK has a frozen version code. If a newer dev build is installed, this
            // release would be a downgrade even though its version name is higher, and Android
            // would reject the install. Equal codes install fine (CI stamps them per minute), so
            // only strict downgrades are skipped. Releases without a published code rely on the
            // check against the downloaded APK in download().
            if (release.versionCode >= 0 && release.versionCode < installedVersionCode) continue
            // Only install bytes we can check. If the phone's preferred ABI split has no digest,
            // fall through to a verified asset in the same release (the universal APK) instead of
            // reporting no update at all.
            val verified = release.assets.filter { it.sha256 != null }
            val name = pickAsset(verified.map { it.name }, supportedAbis) ?: continue
            val apk = verified.first { it.name == name }
            val hash = apk.sha256 ?: continue
            val version = release.tag.removePrefix("v")
            if (isNewer(version, installedVersion)) {
                GearslipLog.i("update: $version is out (this is $installedVersion)")
                return State.Available(
                    Release(
                        version,
                        apk.url,
                        apk.size,
                        hash,
                        release.pageUrl,
                        target = release.tag.removePrefix("v"),
                        versionCode = release.versionCode,
                    ),
                )
            }
        }
        return State.UpToDate
    }

    /** The first ABI we have an asset for wins; the universal APK is the last resort. */
    internal fun pickAsset(assetNames: List<String>, supportedAbis: List<String>): String? {
        for (abi in supportedAbis) {
            val name = ABI_ASSETS[abi]
            if (name != null && name in assetNames) return name
        }
        return UNIVERSAL_ASSET.takeIf { it in assetNames }
    }

    /** Downloads the chosen APK and installs it once the checksum has passed. */
    fun download(context: Context, release: Release) {
        if (flow.value is State.Downloading) return
        if (release.channel != channel(context)) return
        val app = context.applicationContext
        // The ref can change between the UI reading the card and this tap.
        if (release.channel == CHANNEL_DEV && release.target != devRef(app)) return
        val downloadGeneration = decision.generation
        flow.value = State.Downloading(release, 0f)
        background("gearslip-update") {
            val dir = updatesDir(app, release.channel).apply { deleteRecursively(); mkdirs() }
            val apk = File(dir, "Gearslip-${release.version}.apk")
            val result = runCatching { fetchApk(release, apk, downloadGeneration) }
            if (!decision.isCurrent(downloadGeneration)) return@background
            if (result.isSuccess) {
                // Authoritative check: read the version code from the APK itself. This also covers
                // releases that predate the published version-code marker.
                val apkCode = runCatching {
                    app.packageManager.getPackageArchiveInfo(apk.absolutePath, 0)?.longVersionCode
                }.getOrNull()
                if (apkCode != null && apkCode < BuildConfig.VERSION_CODE.toLong()) {
                    GearslipLog.w("update: $apkCode is older than installed ${BuildConfig.VERSION_CODE}; not installing")
                    apk.delete()
                    flow.value = State.Failed("This build is older than the one installed. Pick a newer dev ref or wait for a newer stable release.")
                    return@background
                }
                // reset() may have run after the earlier generation check but before this point;
                // never republish a Ready the driver no longer asked for.
                if (!decision.isCurrent(downloadGeneration)) return@background
                GearslipLog.i("update: downloaded ${release.version}")
                flow.value = State.Ready(release, apk)
                install(app)
            } else {
                apk.delete()
                val message = result.exceptionOrNull()?.message ?: "The download failed."
                GearslipLog.w("update: download failed: $message")
                flow.value = State.Failed(message, release)
            }
        }
    }

    private fun fetchApk(release: Release, into: File, downloadGeneration: Int) {
        val connection = (URL(release.apkUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("User-Agent", "Gearslip/${BuildConfig.VERSION_NAME}")
        }
        try {
            if (connection.responseCode !in 200..299) error("GitHub answered ${connection.responseCode}")
            val total = release.size.takeIf { it > 0 } ?: connection.contentLengthLong
            val digest = MessageDigest.getInstance("SHA-256")
            var done = 0L
            var shown = -1
            connection.inputStream.use { input ->
                into.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        if (!decision.isCurrent(downloadGeneration)) error("The update target changed.")
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        digest.update(buffer, 0, n)
                        done += n
                        // Whole percent only, so the screen isn't redrawn for every buffer.
                        val percent = if (total > 0) (done * 100 / total).toInt() else 0
                        if (percent != shown) {
                            shown = percent
                            flow.value = State.Downloading(release, percent / 100f)
                        }
                    }
                }
            }
            if (release.size > 0 && done != release.size) error("The download stopped early. Try again.")
            val hex = digest.digest().joinToString("") { "%02x".format(it) }
            if (release.sha256 != null && !hex.equals(release.sha256, ignoreCase = true)) {
                error("The download came out damaged. Try again.")
            }
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Hands the APK to Android. The first time, Android wants the driver to allow installs from
     * Gearslip; that opens the system page, and [onAppResumed] carries on when they come back.
     */
    fun install(context: Context) {
        val ready = flow.value as? State.Ready ?: return
        // The driver may have switched channel while the APK was downloading or waiting for the
        // car. Never hand bytes from the other track to the installer.
        if (ready.release.channel != channel(context)) return
        // A dev ref change keeps the channel, so check the target too: every install entry point
        // (the card's button, the return from install settings, the car disconnect) is covered.
        if (ready.release.channel == CHANNEL_DEV && ready.release.target != devRef(context)) return
        // A published code lower than the installed one would be rejected as a downgrade. The
        // APK itself was checked in download(); this guards older feeds without a published code.
        if (ready.release.versionCode in 0 until BuildConfig.VERSION_CODE) {
            flow.value = State.Failed("This build is older than the one installed. Pick a newer dev ref or wait for a newer stable release.")
            return
        }
        // Installing closes Gearslip, which would drop the car screen mid-drive. Queue it and
        // say so on the card rather than silently throwing the tap away.
        if (carConnected) {
            decision.queueWhenCarGone()
            flow.value = State.Ready(ready.release, ready.apk, context.getString(R.string.update_waiting_car))
            return
        }
        decision.clearCarGone()
        val app = context.applicationContext
        if (!app.packageManager.canRequestPackageInstalls()) {
            decision.queueWhenAllowed()
            runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${app.packageName}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }.onFailure {
                decision.clearAllowed()
                flow.value = State.Failed("Android couldn't open the install settings.", ready.release)
            }
            return
        }
        background("gearslip-install") {
            runCatching { commit(app, ready) }.onFailure {
                GearslipLog.w("update: install failed to start: ${it.message}")
                // The download already passed its checksum. Keep it and go back to Ready so the
                // card's Install button can try again without pulling ~20 MB over again.
                flow.value = State.Ready(ready.release, ready.apk, "Android couldn't start the install. Tap Install to try again.")
            }
        }
    }

    /** Back from the unknown-sources page: start the install the driver just allowed. */
    fun onAppResumed(context: Context) {
        if (!decision.hasAllowed() || !context.packageManager.canRequestPackageInstalls()) return
        decision.consumeAllowed()
        install(context)
    }

    /** The car let go: finish an Install tap that had to wait until the drive ended. */
    fun onCarDisconnected(context: Context) {
        if (!decision.hasCarGone()) return
        // Don't drop the flag first: if the state moved on while the car was connected (say the
        // driver checked for updates again), install() returns without starting and the tap must
        // stay queued. install() clears the flag itself once it actually proceeds.
        install(context)
    }

    private fun commit(app: Context, ready: State.Ready) {
        val installer = app.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(app.packageName)
            setSize(ready.apk.length())
            setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = installer.createSession(params)
        var handedOff = false
        try {
            installer.openSession(id).use { session ->
                session.openWrite("gearslip.apk", 0, ready.apk.length()).use { out ->
                    ready.apk.inputStream().use { it.copyTo(out) }
                    session.fsync(out)
                }
                val status = Intent(app, UpdateReceiver::class.java)
                    .setAction(UpdateReceiver.ACTION_STATUS)
                    .putExtra(EXTRA_VERSION, ready.release.version)
                // Mutable: the installer adds the outcome to it.
                val pending = PendingIntent.getBroadcast(
                    app, id, status, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
                )
                session.commit(pending.intentSender)
            }
            handedOff = true
        } finally {
            // A failed write or commit leaves a session we still own. Abandon it so repeated
            // Install taps don't pile up orphaned sessions until the system reclaims them.
            if (!handedOff) runCatching { installer.abandonSession(id) }
        }
        GearslipLog.i("update: handed ${ready.release.version} to Android's installer")
    }

    /** Android's answer about the install session; on success this process is replaced. */
    internal fun onInstallStatus(context: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT) as? Intent
                if (confirm != null) context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            PackageInstaller.STATUS_SUCCESS ->
                GearslipLog.i("update: ${intent.getStringExtra(EXTRA_VERSION) ?: "new version"} installed")
            else -> {
                val detail = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "unknown error"
                GearslipLog.w("update: install failed: $detail")
                // Leave the cached file alone: a status can arrive for an older session while a
                // newer attempt is copying the same APK. The Failed card's Retry calls download(),
                // which clears the cache before fetching fresh bytes.
                flow.value = State.Failed("Android couldn't install the update. $detail")
            }
        }
    }

    /** The new build is running: drop the downloaded files and the card that offered them. */
    fun onReplaced(context: Context) {
        decision.clearAll()
        updatesDir(context, CHANNEL_STABLE).deleteRecursively()
        updatesDir(context, CHANNEL_DEV).deleteRecursively()
        flow.value = State.Idle
    }

    private fun updatesDir(context: Context, target: String) = File(File(context.cacheDir, "updates"), target)

    private val carConnected: Boolean
        get() = SessionStatus.state.value.phase.let {
            it == SessionStatus.Phase.CONNECTING || it == SessionStatus.Phase.PROJECTING
        }

    private fun background(name: String, work: () -> Unit) {
        Thread(work).apply { this.name = name }.start()
    }

    /**
     * "0.4.0-alpha" beats "0.1.10-alpha": the numbers before the dash compare one by one. With
     * the same numbers, a plain release beats a beta, and a beta beats an alpha. Anything after
     * a "+" only says which commit a build came from, so "0.4.0-alpha+30.5e6c76e" is newer than
     * "0.4.0-alpha" but older than "0.4.0-alpha+31.aaaaaaa".
     */
    internal fun isNewer(candidate: String, installed: String): Boolean {
        fun core(v: String) = v.removePrefix("v").substringBefore('+')
        fun parts(v: String) = core(v).substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
        fun stage(v: String): Int {
            val tag = core(v).substringAfter('-', "").lowercase()
            return when {
                tag.isEmpty() -> 3
                "beta" in tag -> 2
                "alpha" in tag -> 1
                else -> 0
            }
        }
        val a = parts(candidate)
        val b = parts(installed)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        val candidateStage = stage(candidate)
        val installedStage = stage(installed)
        if (candidateStage != installedStage) return candidateStage > installedStage
        // Same tag and stage: a higher "+N.sha" means more commits since that tag, so it's newer.
        fun commits(v: String) = Regex("""\+(\d+)\.""").find(v)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        return commits(candidate) > commits(installed)
    }
}
