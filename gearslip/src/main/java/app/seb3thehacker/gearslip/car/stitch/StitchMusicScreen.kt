package app.seb3thehacker.gearslip.car.stitch

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.seb3thehacker.gearslip.car.CarServices
import app.seb3thehacker.gearslip.car.CarSettings
import app.seb3thehacker.gearslip.car.LocalCarNavigator
import app.seb3thehacker.gearslip.car.LocalNavBarInset
import app.seb3thehacker.gearslip.car.LyricsView
import app.seb3thehacker.gearslip.car.QueueView
import app.seb3thehacker.gearslip.car.formatDuration
import app.seb3thehacker.gearslip.car.theme.DotGlyphs
import app.seb3thehacker.gearslip.car.theme.DotGrid
import app.seb3thehacker.gearslip.car.theme.DotMatrixArt
import app.seb3thehacker.gearslip.car.theme.DotSeekBar
import app.seb3thehacker.gearslip.catalog.MusicCatalog
import app.seb3thehacker.gearslip.media.MediaArt
import app.seb3thehacker.gearslip.music.playback.MusicPlayer
import kotlinx.coroutines.CancellationException

private const val PANEL_LYRICS = 0
private const val PANEL_QUEUE = 1

/**
 * The alternate music front-end ported from the Stitch "Car Music" screens: one status strip and
 * three pages (NOW PLAYING, BROWSE, SEARCH) sharing the same shell. Every reading comes from the
 * connected media app through [MusicPlayer] / [MusicCatalog]; the demo tracks and device readings
 * in the mockups are not carried over.
 *
 * [initialTab] picks the page the dock's MEDIA button (player) or the music tile (browse) opens.
 */
@Composable
fun StitchMusicScreen(initialTab: Int, onOpenPlayer: () -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val navigator = LocalCarNavigator.current
    val state by MusicPlayer.state.collectAsStateWithLifecycle()
    val queue by MusicPlayer.queue.collectAsStateWithLifecycle()
    val source by CarSettings.musicSource.collectAsStateWithLifecycle()
    var tab by rememberSaveable(initialTab) { mutableIntStateOf(initialTab) }
    var initializing by remember(context, source) { mutableStateOf(true) }
    var initError by remember(context, source) { mutableStateOf<String?>(null) }

    LaunchedEffect(context, source) {
        try {
            MusicPlayer.init(context.applicationContext)
            MusicCatalog.initialize(context.applicationContext)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            initError = e.message ?: "Could not connect to the music app"
        } finally {
            initializing = false
        }
    }

    // Browsing a folder is a step Back undoes before leaving the screen. The player itself has no
    // folder trail, so it leaves Back to the navigator.
    val media = CarServices.media
    DisposableEffect(navigator, media, tab) {
        navigator.backInterceptor = if (tab == TAB_PLAYER) null else {
            {
                if (media.browse.value.trail.isNotEmpty()) {
                    media.up()
                    true
                } else {
                    false
                }
            }
        }
        onDispose { navigator.backInterceptor = null }
    }

    val track = state.track
    val art by produceState<Bitmap?>(state.art, state.art, track?.thumbnailUrl) {
        value = state.art ?: track?.thumbnailUrl?.let { MediaArt.load(context, Uri.parse(it)) }
    }
    val detail = if (queue.isEmpty()) {
        "NO QUEUE"
    } else {
        val n = (state.activeIndex + 1).coerceAtLeast(1).toString().padStart(2, '0')
        "TRACK $n / ${queue.size.toString().padStart(2, '0')}"
    }
    val scheme = MaterialTheme.colorScheme

    Surface(Modifier.fillMaxSize(), color = scheme.background) {
        androidx.compose.foundation.layout.Box(Modifier.fillMaxSize()) {
            DotGrid(Modifier.fillMaxSize())
            Column(
                Modifier.fillMaxSize().padding(
                    start = 16.dp,
                    end = 16.dp,
                    top = 6.dp,
                    bottom = 12.dp + LocalNavBarInset.current,
                ),
            ) {
                StitchStatusStrip(state.playing, detail)
                Spacer(Modifier.height(8.dp))
                StitchTabs(tab, { tab = it })
                Spacer(Modifier.height(12.dp))
                when (tab) {
                    TAB_PLAYER -> StitchPlayerPage(state, queue, art, onBack)
                    TAB_BROWSE -> StitchBrowsePage(initializing, initError, onOpenPlayer, onBack)
                    TAB_SEARCH -> StitchSearchPage(initializing, initError, onOpenPlayer, onBack)
                }
            }
        }
    }
}

@Composable
private fun StitchPlayerPage(
    state: MusicPlayer.State,
    queue: List<MusicCatalog.Track>,
    art: Bitmap?,
    onBack: () -> Unit,
) {
    var panel by remember { mutableIntStateOf(PANEL_LYRICS) }
    val track = state.track
    val scheme = MaterialTheme.colorScheme
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().height(32.dp), verticalAlignment = Alignment.CenterVertically) {
            StitchDotButton(DotGlyphs.Back, "Back", 30.dp, onBack)
            Spacer(Modifier.width(10.dp))
            Text(
                "CAR MUSIC PLAYBACK",
                style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 0.12.em),
                color = scheme.onSurfaceVariant,
                maxLines = 1,
            )
            Spacer(Modifier.weight(1f))
            StitchDotButton(DotGlyphs.Shuffle, "Shuffle", 28.dp, { MusicPlayer.toggleShuffle() }, state.shuffle)
            Spacer(Modifier.width(8.dp))
            StitchDotButton(DotGlyphs.Repeat, "Repeat", 28.dp, { MusicPlayer.cycleRepeat() }, state.repeat != 0)
        }
        Spacer(Modifier.height(6.dp))
        BoxWithConstraints(
            Modifier
                .weight(0.62f)
                .fillMaxWidth()
                .border(1.dp, scheme.outline, RectangleShape)
                .padding(12.dp),
        ) {
            val artSize = 160.dp.coerceAtMost(maxHeight - 16.dp).coerceAtLeast(72.dp)
            Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                DotMatrixArt(art, Modifier.size(artSize).border(1.dp, scheme.outline))
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    Text(
                        if (queue.isNotEmpty()) {
                            val n = (state.activeIndex + 1).coerceAtLeast(1)
                            "TRACK ${n.toString().padStart(2, '0')} / ${queue.size.toString().padStart(2, '0')}"
                        } else {
                            "NOW PLAYING"
                        },
                        style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.1.em),
                        color = scheme.error,
                        maxLines = 1,
                    )
                    Text(
                        (track?.title ?: "NOTHING PLAYING").uppercase(),
                        style = MaterialTheme.typography.displaySmall.copy(fontSize = 28.sp),
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        listOfNotNull(track?.artists?.takeIf(String::isNotEmpty), track?.album).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.weight(1f))
                    val duration = state.durationMs.coerceAtLeast(0)
                    DotSeekBar(
                        fraction = if (duration > 0) state.positionMs.toFloat() / duration else 0f,
                        onSeek = { f -> if (duration > 0) MusicPlayer.seek((f * duration).toLong()) },
                        modifier = Modifier.fillMaxWidth().height(20.dp),
                        enabled = duration > 0,
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween) {
                        val style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum")
                        Text(formatDuration(state.positionMs), style = style, color = scheme.onSurfaceVariant)
                        Text(formatDuration(duration), style = style, color = scheme.onSurfaceVariant)
                    }
                    Spacer(Modifier.height(6.dp))
                    Row(
                        Modifier.fillMaxWidth().height(52.dp),
                        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        StitchDotButton(DotGlyphs.Prev, "Previous", 46.dp, { MusicPlayer.previous() })
                        StitchDotButton(
                            if (state.playing) DotGlyphs.Pause else DotGlyphs.Play,
                            if (state.playing) "Pause" else "Play",
                            56.dp,
                            { MusicPlayer.togglePlay() },
                        )
                        StitchDotButton(DotGlyphs.Next, "Next", 46.dp, { MusicPlayer.next() })
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Column(Modifier.weight(0.38f).fillMaxWidth()) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
                StitchSubTab("LYRICS", panel == PANEL_LYRICS, { panel = PANEL_LYRICS }, Modifier.weight(1f))
                StitchSubTab("UP NEXT", panel == PANEL_QUEUE, { panel = PANEL_QUEUE }, Modifier.weight(1f))
            }
            Spacer(Modifier.height(6.dp))
            when (panel) {
                PANEL_LYRICS -> LyricsView(
                    track?.title.orEmpty(),
                    track?.artists.orEmpty(),
                    track?.album.orEmpty(),
                    state.durationMs.coerceAtLeast(0),
                    state.playing,
                    Modifier.fillMaxWidth().weight(1f),
                )
                else -> QueueView(queue, state.activeIndex, Modifier.fillMaxWidth().weight(1f))
            }
            if (state.error != null) {
                Text(
                    state.error.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.error,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
