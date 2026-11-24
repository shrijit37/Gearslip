package app.seb3thehacker.gearslip.car.stitch

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.seb3thehacker.gearslip.car.CarKeyboard
import app.seb3thehacker.gearslip.car.CarServices
import app.seb3thehacker.gearslip.car.EmptyState
import app.seb3thehacker.gearslip.car.EntryArt
import app.seb3thehacker.gearslip.car.theme.DotGlyphs
import app.seb3thehacker.gearslip.catalog.MusicCatalog
import app.seb3thehacker.gearslip.media.MediaArt
import app.seb3thehacker.gearslip.music.playback.MusicPlayer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val FILTER_ALL = 0
private const val FILTER_FOLDERS = 1
private const val FILTER_TRACKS = 2
private const val SEARCH_PAUSE_MS = 600L

private fun play(tracks: List<MusicCatalog.Track>, index: Int, onPlay: () -> Unit) {
    if (tracks.isEmpty() || index !in tracks.indices) return
    MusicPlayer.play(tracks, index)
    onPlay()
}

/** The Stitch BROWSE page: live shelves and the queue, in cover tiles rather than rows. */
@Composable
internal fun StitchBrowsePage(
    initializing: Boolean,
    initError: String?,
    onPlay: () -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val media = CarServices.media
    val browseState by media.browse.collectAsStateWithLifecycle()
    val snapshot by remember { MusicCatalog.homeFlow() }.collectAsStateWithLifecycle(
        MusicCatalog.HomeSnapshot(emptyList(), null),
    )
    val queue by MusicPlayer.queue.collectAsStateWithLifecycle()
    var filter by remember { mutableIntStateOf(FILTER_ALL) }
    val scheme = MaterialTheme.colorScheme

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().height(40.dp), verticalAlignment = Alignment.CenterVertically) {
            StitchDotButton(DotGlyphs.Back, "Back", 30.dp, onBack)
            Spacer(Modifier.width(10.dp))
            Text(
                if (browseState.trail.isNotEmpty()) browseState.trail.last().uppercase() else "BROWSE LIBRARY",
                style = MaterialTheme.typography.titleMedium.copy(letterSpacing = 0.1.em),
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.weight(1f))
            StitchSubTab("ALL", filter == FILTER_ALL, { filter = FILTER_ALL })
            Spacer(Modifier.width(6.dp))
            StitchSubTab("FOLDERS", filter == FILTER_FOLDERS, { filter = FILTER_FOLDERS })
            Spacer(Modifier.width(6.dp))
            StitchSubTab("TRACKS", filter == FILTER_TRACKS, { filter = FILTER_TRACKS })
        }
        Spacer(Modifier.height(8.dp))
        when {
            initializing || (initError == null && browseState.loading && snapshot.shelves.isEmpty()) ->
                EmptyState("LOADING CATALOG", "Asking the music service for its library", Modifier.weight(1f))
            initError != null -> EmptyState("OFFLINE", initError.orEmpty(), Modifier.weight(1f))
            snapshot.error != null -> EmptyState("OFFLINE", snapshot.error.orEmpty(), Modifier.weight(1f))
            snapshot.shelves.isEmpty() -> EmptyState("NOTHING HERE", "Try SEARCH to find a song by name", Modifier.weight(1f))
            else -> LazyColumn(
                Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                if (queue.isNotEmpty() && filter != FILTER_FOLDERS) {
                    item(key = "recent") {
                        Text(
                            "RECENTLY PLAYED",
                            style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 0.12.em),
                            color = scheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(8.dp))
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            items(queue, key = { "recent ${it.id}" }) { track ->
                                StitchCover(track, 112.dp) {
                                    play(queue, queue.indexOfFirst { it.id == track.id }, onPlay)
                                }
                            }
                        }
                    }
                }
                snapshot.shelves.forEachIndexed { i, shelf ->
                    val tracks = shelf.tracks.filter { track ->
                        when (filter) {
                            FILTER_FOLDERS -> !track.playable
                            FILTER_TRACKS -> track.playable
                            else -> true
                        }
                    }
                    if (tracks.isEmpty()) return@forEachIndexed
                    item(key = "shelf $i") {
                        Text(
                            shelf.title.uppercase(),
                            style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 0.12.em),
                            color = scheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(8.dp))
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            items(tracks, key = { "cover $i ${it.id}" }) { track ->
                                StitchCover(track, 132.dp) {
                                    if (track.playable) {
                                        val all = snapshot.shelves.flatMap { it.tracks }
                                        play(all, all.indexOfFirst { it.id == track.id }, onPlay)
                                    } else {
                                        scope.launch { MusicCatalog.browse(track.id) }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** The Stitch SEARCH page: the car keyboard, quick queries and live library results. */
@Composable
internal fun StitchSearchPage(
    initializing: Boolean,
    initError: String?,
    onPlay: () -> Unit,
    onBack: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var typing by remember { mutableStateOf(true) }
    var results by remember { mutableStateOf<List<MusicCatalog.Track>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val recent = remember { mutableStateListOf<String>() }
    val state by MusicPlayer.state.collectAsStateWithLifecycle()
    val trimmed = query.trim()
    val scheme = MaterialTheme.colorScheme

    LaunchedEffect(trimmed) {
        if (trimmed.length < 2) {
            results = emptyList()
            error = null
            busy = false
            return@LaunchedEffect
        }
        results = emptyList()
        error = null
        busy = true
        delay(SEARCH_PAUSE_MS)
        try {
            results = MusicCatalog.search(trimmed)
            if (results.isNotEmpty() && recent.firstOrNull() != trimmed) {
                recent.add(0, trimmed)
                while (recent.size > 4) recent.removeAt(recent.lastIndex)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.message ?: "The music app did not answer"
        } finally {
            busy = false
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().height(40.dp), verticalAlignment = Alignment.CenterVertically) {
            StitchDotButton(DotGlyphs.Back, "Back", 30.dp, onBack)
            Spacer(Modifier.width(10.dp))
            Text(
                "SEARCH AUDIO",
                style = MaterialTheme.typography.titleMedium.copy(letterSpacing = 0.1.em),
                fontWeight = FontWeight.Bold,
                maxLines = 1,
            )
            Spacer(Modifier.weight(1f))
            if (query.isNotEmpty()) {
                androidx.compose.material3.TextButton(onClick = { query = ""; typing = true }) {
                    Text("CLEAR", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(
            Modifier.fillMaxWidth().height(52.dp).border(1.dp, scheme.outline, RectangleShape).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (query.isEmpty()) "SONG, ARTIST OR ALBUM" else query,
                style = MaterialTheme.typography.titleMedium,
                color = if (query.isEmpty()) scheme.onSurfaceVariant else scheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).clickable { typing = true },
            )
            if (query.isNotEmpty()) {
                androidx.compose.material3.TextButton(onClick = { typing = false }) { Text("DONE") }
            }
        }
        if (typing) {
            Spacer(Modifier.height(8.dp))
            CarKeyboard(
                text = query,
                onTextChange = { query = it },
                onSubmit = { if (query.trim().isNotEmpty()) typing = false },
                onDismiss = { typing = false },
            )
        }
        Spacer(Modifier.height(8.dp))
        val quick = listOfNotNull(state.track?.artists?.takeIf(String::isNotEmpty))
            .plus(recent.take(3)).distinct().take(4)
        if (quick.isNotEmpty() && !typing) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                quick.forEach { term ->
                    StitchSubTab(term.uppercase(), false, { query = term; typing = false })
                }
            }
            Spacer(Modifier.height(8.dp))
        }
        when {
            initializing -> EmptyState("CONNECTING", "Asking the music app for its library", Modifier.weight(1f))
            initError != null -> EmptyState("OFFLINE", initError.orEmpty(), Modifier.weight(1f))
            trimmed.isEmpty() -> EmptyState("SEARCH", "Type a name with the keys above", Modifier.weight(1f))
            busy && results.isEmpty() -> EmptyState("SEARCHING", "Asking the music service for matches", Modifier.weight(1f))
            error != null && results.isEmpty() -> EmptyState("SEARCH FAILED", error.orEmpty(), Modifier.weight(1f))
            results.isEmpty() -> EmptyState("NO RESULTS", "Nothing matched $trimmed", Modifier.weight(1f))
            else -> LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(results, key = { "result ${it.id}" }) { track ->
                    StitchResultRow(track) { play(results, results.indexOfFirst { it.id == track.id }, onPlay) }
                }
            }
        }
    }
}

/** A cover tile: the real art as a dot-matrix halftone, or the deterministic dot stand-in. */
@Composable
private fun StitchCover(track: MusicCatalog.Track, size: androidx.compose.ui.unit.Dp, onClick: () -> Unit) {
    val context = LocalContext.current
    val art by produceState<Bitmap?>(null, track.thumbnailUrl) {
        value = track.thumbnailUrl?.let { MediaArt.load(context, Uri.parse(it)) }
    }
    Column(Modifier.width(size).clickable(onClick = onClick)) {
        EntryArt(art, !track.playable, Modifier.size(size).border(1.dp, MaterialTheme.colorScheme.outline), seed = track.id)
        Spacer(Modifier.height(8.dp))
        Text(
            track.title.uppercase(),
            style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 0.08.em),
            fontWeight = FontWeight.Bold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (track.artists.isNotEmpty()) {
            Text(
                track.artists,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun StitchResultRow(track: MusicCatalog.Track, onClick: () -> Unit) {
    val context = LocalContext.current
    val art by produceState<Bitmap?>(null, track.thumbnailUrl) {
        value = track.thumbnailUrl?.let { MediaArt.load(context, Uri.parse(it)) }
    }
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .border(1.dp, MaterialTheme.colorScheme.outline, RectangleShape)
            .clickable(onClick = onClick)
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        EntryArt(art, false, Modifier.size(52.dp).border(1.dp, MaterialTheme.colorScheme.outline), seed = track.id)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(track.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(track.artists.takeIf(String::isNotEmpty), track.album).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
