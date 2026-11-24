package app.seb3thehacker.gearslip.car

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import app.seb3thehacker.gearslip.R
import kotlin.math.roundToInt

// The Nothing OS car dock from the approved Stitch design: a full-width bar with a volume stepper
// on the left, NAV / MEDIA / PHONE / CAR / apps on a centre line, and VOICE on the right. Buttons
// are pills, the only rounded shapes the design allows. Press feedback is an opacity change, not a
// colour flash or a ripple, as design.md asks.

/** The system dock's active app, used to latch the matching pill. */
private enum class DockApp { NONE, NAV, MEDIA, CAR, APPS }

private fun dockApp(screen: CarScreen): DockApp = when (screen) {
    CarScreen.Home, CarScreen.DotMap, CarScreen.PlaceSearch -> DockApp.NAV
    CarScreen.Media, CarScreen.FirstPartyMusic, CarScreen.FirstPartyPlayer -> DockApp.MEDIA
    CarScreen.VehicleData -> DockApp.CAR
    CarScreen.Apps, CarScreen.Browse, CarScreen.Settings, CarScreen.Dashboard,
    CarScreen.Weather, is CarScreen.App -> DockApp.APPS
    else -> DockApp.NONE
}

/**
 * The car's system dock. It replaces both the old left rail and the mini player: media now opens
 * the full-screen player, and every other rail destination is reachable from the apps grid.
 *
 * Below 880 dp the labels give way to icon-only pills so the dock fits a narrow head unit.
 */
@Composable
fun CarDock(navigator: CarNavigator, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val volume = rememberVolumeState()
    val active = dockApp(navigator.current)
    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .height(NAV_BAR_HEIGHT)
            .background(scheme.surface),
    ) {
        val compact = maxWidth < 880.dp
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxWidth().height(1.dp).background(scheme.outline))
            Row(
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(horizontal = if (compact) 12.dp else 24.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                VolumeStepper(volume)
                DockDivider(Modifier.padding(horizontal = if (compact) 8.dp else 24.dp))
                Row(
                    Modifier.weight(1f),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    DockPill(
                        label = stringResource(R.string.car_dock_nav),
                        icon = DockIcons.Nav,
                        active = active == DockApp.NAV,
                        compact = compact,
                        onClick = navigator::dotMap,
                    )
                    DockSpacer()
                    DockPill(
                        label = stringResource(R.string.car_dock_media),
                        icon = DockIcons.Media,
                        active = active == DockApp.MEDIA,
                        indicator = true,
                        compact = compact,
                        onClick = navigator::firstPartyPlayer,
                    )
                    DockSpacer()
                    // PHONE and VOICE are drawn to match the design but have no action yet.
                    DockPill(label = stringResource(R.string.car_dock_phone), icon = DockIcons.Phone, compact = compact)
                    DockSpacer()
                    DockPill(
                        label = stringResource(R.string.car_dock_car),
                        icon = DockIcons.Car,
                        active = active == DockApp.CAR,
                        compact = compact,
                        onClick = navigator::vehicleData,
                    )
                    DockSpacer()
                    DockPill(
                        label = stringResource(R.string.car_dock_apps),
                        icon = DockIcons.Apps,
                        active = active == DockApp.APPS,
                        compact = compact,
                        onClick = navigator::apps,
                        iconOnly = true,
                    )
                }
                DockDivider(Modifier.padding(horizontal = if (compact) 8.dp else 24.dp))
                DockPill(
                    label = stringResource(R.string.car_dock_voice),
                    icon = DockIcons.Voice,
                    compact = compact,
                )
            }
        }
    }
}

@Composable
private fun DockSpacer() = Spacer(Modifier.width(8.dp))

@Composable
private fun DockDivider(modifier: Modifier = Modifier) {
    Box(
        modifier
            .width(1.dp)
            .fillMaxHeight()
            .padding(vertical = 8.dp)
            .background(MaterialTheme.colorScheme.outline),
    )
}

/** One pill: white when latched, black with a hairline when not. */
@Composable
private fun DockPill(
    label: String,
    icon: ImageVector,
    active: Boolean = false,
    indicator: Boolean = false,
    compact: Boolean,
    iconOnly: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    val scheme = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val background = if (active) scheme.primary else scheme.background
    val content = if (active) scheme.onPrimary else scheme.onSurfaceVariant
    val shape = CircleShape
    var base = Modifier
        .height(48.dp)
        .alpha(if (pressed && onClick != null) 0.6f else 1f)
        .background(background, shape)
        .then(if (active) Modifier else Modifier.border(1.dp, scheme.outline, shape))
    base = if (compact || iconOnly) base.width(48.dp) else base.padding(horizontal = 16.dp)
    val clickable = if (onClick != null) {
        base.clickable(interactionSource = interaction, indication = null, onClick = onClick)
    } else {
        base
    }
    // The Box centres the whole icon/label cluster inside the pill. A Row with the default
    // spacedBy alignment (Start) would pin icon-only pills' icons to the left edge instead.
    Box(clickable, contentAlignment = Alignment.Center) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(18.dp))
            if (!compact && !iconOnly) {
                Text(
                    label.uppercase(),
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        letterSpacing = 0.1.em,
                    ),
                    color = content,
                    maxLines = 1,
                )
            }
        }
        // Drawn over the pill so the playing indicator never shifts the icon out of line.
        if (indicator && active) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp)
                    .size(8.dp)
                    .background(scheme.error, CircleShape),
            )
        }
    }
}

/** The tactile volume stepper: minus, the live percentage, plus. */
@Composable
private fun VolumeStepper(volume: VolumeState) {
    val scheme = MaterialTheme.colorScheme
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        RoundButton(DockIcons.VolumeDown, stringResource(R.string.car_dock_volume_down), volume.lower)
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                "VOL",
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, letterSpacing = 0.1.em),
                color = scheme.onSurfaceVariant,
                maxLines = 1,
            )
            Text(
                "${volume.percent}%",
                style = MaterialTheme.typography.labelLarge.copy(fontSize = 15.sp, letterSpacing = 0.02.em),
                color = scheme.onSurface,
                maxLines = 1,
            )
        }
        RoundButton(DockIcons.VolumeUp, stringResource(R.string.car_dock_volume_up), volume.raise)
    }
}

@Composable
private fun RoundButton(icon: ImageVector, label: String, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Box(
        Modifier
            .size(40.dp)
            .alpha(if (pressed) 0.6f else 1f)
            .background(scheme.background, CircleShape)
            .border(1.dp, scheme.outline, CircleShape)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = label, tint = scheme.onSurface, modifier = Modifier.size(16.dp))
    }
}

private class VolumeState(val percent: Int, val lower: () -> Unit, val raise: () -> Unit)

private fun volumePercent(audio: AudioManager?): Int {
    if (audio == null) return 0
    val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
    return (audio.getStreamVolume(AudioManager.STREAM_MUSIC) * 100f / max).roundToInt().coerceIn(0, 100)
}

@Composable
private fun rememberVolumeState(): VolumeState {
    val context = LocalContext.current
    val audio = remember { context.getSystemService(AudioManager::class.java) }
    var percent by remember { mutableIntStateOf(volumePercent(audio)) }

    fun step(direction: Int) {
        audio?.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, AudioManager.FLAG_SHOW_UI)
        percent = volumePercent(audio)
    }

    DisposableEffect(audio) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                percent = volumePercent(audio)
            }
        }
        runCatching {
            ContextCompat.registerReceiver(
                context,
                receiver,
                IntentFilter("android.media.VOLUME_CHANGED_ACTION"),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
        }
        onDispose { runCatching { context.unregisterReceiver(receiver) } }
    }
    return VolumeState(percent, { step(AudioManager.ADJUST_LOWER) }, { step(AudioManager.ADJUST_RAISE) })
}

/** Stroke and square-cap glyphs from the design's SVGs; no icon dependency needed. */
private object DockIcons {
    val VolumeDown = stroke("VolumeDown", "M6,12h12")
    val VolumeUp = stroke("VolumeUp", "M12,6v12", "M6,12h12")
    val Nav = stroke("Nav", "M3,11L21,3L13,21L11,13Z")
    val Media = stroke("Media", "M3,5h18v14H3Z", "M10,9l6,3-6,3Z")
    val Phone = stroke(
        "Phone",
        "M5,4h4l2,5-2.5,1.5c1.3,2.7 3.3,4.7 6,6L16,14l5,2v4c0,0.5-0.5,1-1,1 " +
            "C10.6,21 3,13.4 3,4c0-0.5 0.5-1 1-1Z",
    )
    val Car = stroke("Car", "M4,10h16v7H4Z", "M6,10l2-5h8l2,5", "M7,17v3", "M17,17v3")
    val Apps = filled(
        "Apps",
        "M4,4h3v3H4zM10.5,4h3v3h-3zM17,4h3v3h-3z" +
            "M4,10.5h3v3H4zM10.5,10.5h3v3h-3zM17,10.5h3v3h-3z" +
            "M4,17h3v3H4zM10.5,17h3v3h-3zM17,17h3v3h-3z",
    )
    val Voice = stroke("Voice", "M4,10v4", "M8,7v10", "M12,4v16", "M16,7v10", "M20,10v4")

    private fun stroke(name: String, vararg paths: String): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
            paths.forEach { path ->
                addPath(
                    addPathNodes(path),
                    pathFillType = PathFillType.NonZero,
                    stroke = SolidColor(Color.Black),
                    strokeLineWidth = 2f,
                    strokeLineCap = StrokeCap.Square,
                )
            }
        }.build()

    private fun filled(name: String, path: String): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
            .addPath(addPathNodes(path), fill = SolidColor(Color.Black))
            .build()
}
