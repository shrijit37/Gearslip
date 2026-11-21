package app.seb3thehacker.gearslip.catalog

import android.content.Context
import app.seb3thehacker.gearslip.car.CarServices
import app.seb3thehacker.gearslip.car.CarSettings
import app.seb3thehacker.gearslip.car.CarSettings.MusicSource
import app.seb3thehacker.gearslip.media.CarMedia
import app.seb3thehacker.gearslip.media.MediaCatalog
import app.seb3thehacker.gearslip.media.MediaEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The music catalogue presented by Gearslip's Nothing-style screens. There is no first-party
 * streaming any more: browse, search and playback all come from the connected media app (Vivi
 * Music, Echo Music, or any other app with a MediaBrowserService). This facade keeps the car
 * screens independent of which app is providing the music.
 */
object MusicCatalog {

    data class Track(
        val id: String,
        val title: String,
        val artists: String,
        val album: String?,
        val thumbnailUrl: String?,
        val durationSeconds: Long,
        val playable: Boolean = true,
        /** Set for rows from the app's play queue. A queue ID is not a browse-tree media ID and
         * must be jumped to with skipToQueueItem, never playFromMediaId. */
        val queueId: Long? = null,
    )

    data class Shelf(val title: String, val tracks: List<Track>)

    @Volatile
    private var appContext: Context? = null

    /** Connect to the configured music app and wait until it is usable. */
    suspend fun initialize(context: Context) {
        appContext = context.applicationContext
        val apps = withContext(Dispatchers.IO) { MediaCatalog.installed(context.applicationContext) }
        val source = CarSettings.musicSource.value
        val app = when (source) {
            MusicSource.VIVI -> apps.firstOrNull { it.component.packageName.contains("vivi", true) }
            MusicSource.ECHO -> apps.firstOrNull { it.component.packageName.startsWith("echo.music") }
            MusicSource.LAST_USED -> CarSettings.lastMedia.value?.let { saved ->
                apps.firstOrNull { it.component.flattenToString() == saved }
            }
            MusicSource.AUTO -> apps.firstOrNull {
                it.component.packageName.contains("vivi", true) ||
                    it.component.packageName.startsWith("echo.music")
            }
                ?: CarSettings.lastMedia.value?.let { saved ->
                    apps.firstOrNull { it.component.flattenToString() == saved }
                }
                ?: apps.firstOrNull()
        } ?: throw IllegalStateException("No music app found — install Vivi Music or Echo Music")

        CarServices.openMedia(app)
        val phase = withTimeoutOrNull(10_000) {
            CarServices.media.phase.first { it == CarMedia.Phase.READY || it == CarMedia.Phase.REJECTED }
        }
        // The screen shows OFFLINE rather than NOTHING HERE when we can't connect, so a rejected
        // or unanswered connection must fail here instead of leaving an empty browse tree behind.
        check(phase == CarMedia.Phase.READY) {
            if (phase == null) {
                "${app.label} did not respond"
            } else {
                val reason = when (CarServices.media.rejection.value) {
                    CarMedia.Rejection.NEEDS_NOTIFICATION_ACCESS -> "needs notification access"
                    CarMedia.Rejection.NEEDS_APP_RUNNING -> "is not running"
                    else -> "refused the connection"
                }
                "${app.label} $reason"
            }
        }
    }

    /** One live reading of the connected app's browse tree: shelves, or why there are none. */
    data class HomeSnapshot(val shelves: List<Shelf>, val error: String?)

    /**
     * Shelves follow the app's browse state instead of a one-shot snapshot, so entering a folder
     * or the app updating its library shows up without a wait or a second connection.
     */
    fun homeFlow(): Flow<HomeSnapshot> = CarServices.media.browse.map { browse ->
        when {
            browse.failed -> HomeSnapshot(emptyList(), "Could not load the music library")
            browse.unavailable -> HomeSnapshot(emptyList(), "This app keeps its library to itself")
            browse.loading && browse.entries.isEmpty() -> HomeSnapshot(emptyList(), null)
            else -> HomeSnapshot(shelvesOf(browse.entries), null)
        }
    }

    /** Groups the app's entries under their section headings, as its own library files them. */
    internal fun shelvesOf(entries: List<MediaEntry>): List<Shelf> =
        entries.groupBy { it.group ?: it.subtitle.takeIf(String::isNotBlank) ?: "MUSIC" }
            .map { (title, items) -> Shelf(title, items.map(::toTrack)) }

    /** Shelves from the connected app's browse tree. Folders are non-playable and open on tap. */
    suspend fun home(): List<Shelf> {
        val media = CarServices.mediaOrNull() ?: return emptyList()
        withTimeoutOrNull(6_000) { media.browse.first { !it.loading || it.failed || it.unavailable } }
        return shelvesOf(media.browse.value.entries)
    }

    /** Ask the connected app's own library for matches, then let the driver pick one. */
    suspend fun search(query: String): List<Track> {
        val media = CarServices.mediaOrNull() ?: return emptyList()
        media.searchLibrary(query)
        val found = withTimeoutOrNull(10_000) {
            media.results.first { it?.query == query.trim() && it.entries != null }
        }
        val entries = found?.entries ?: return emptyList()
        return entries.filter { it.playable }.map(::toTrack)
    }

    suspend fun suggestions(query: String): List<String> = emptyList()

    /** Descend into a browsable folder from the home shelves, identified by its media ID. */
    suspend fun browse(entryId: String) {
        val media = CarServices.mediaOrNull() ?: return
        val entry = media.browse.value.entries.firstOrNull { it.id == entryId } ?: return
        media.select(entry)
        delay(300)
    }

    private fun toTrack(entry: MediaEntry) = Track(
        id = entry.id,
        title = entry.title,
        artists = entry.subtitle,
        album = null,
        thumbnailUrl = entry.iconUri?.toString(),
        durationSeconds = 0L,
        playable = entry.playable,
    )
}
