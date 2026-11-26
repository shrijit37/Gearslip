package app.seb3thehacker.gearslip.car.stitch

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import app.seb3thehacker.gearslip.car.theme.DotIcon
import app.seb3thehacker.gearslip.car.theme.GsColors
import app.seb3thehacker.gearslip.car.theme.GsIconBox

/** The three pages of the Stitch music front-end. */
internal const val TAB_PLAYER = 0
internal const val TAB_BROWSE = 1
internal const val TAB_SEARCH = 2

/**
 * The hairline status strip at the top of every screen in the Stitch mockups. The mockup's device
 * and cabin readings are demo content; only live track state is shown here.
 */
@Composable
internal fun StitchStatusStrip(playing: Boolean, detail: String, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier
            .fillMaxWidth()
            .height(28.dp)
            .border(1.dp, scheme.outline, RectangleShape)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(6.dp).background(if (playing) scheme.error else scheme.onSurfaceVariant))
            Spacer(Modifier.width(8.dp))
            Text(
                if (playing) "PLAYING" else "PAUSED",
                style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.12.em),
                color = scheme.onSurface,
                maxLines = 1,
            )
        }
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            Text(
                "NOW PLAYING",
                style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.12.em),
                color = scheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        Text(
            detail,
            style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.08.em),
            color = scheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** NOW PLAYING / BROWSE / SEARCH, the top navigation of the Stitch screens. */
@Composable
internal fun StitchTabs(selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    Row(modifier.fillMaxWidth().height(56.dp), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
        listOf(TAB_PLAYER to "NOW PLAYING", TAB_BROWSE to "BROWSE", TAB_SEARCH to "SEARCH").forEach { (id, label) ->
            val on = id == selected
            GsIconBox(
                onClick = { onSelect(id) },
                modifier = Modifier.weight(1f).fillMaxHeight(),
                colors = if (on) GsColors(scheme.primary, scheme.onPrimary)
                else GsColors(scheme.surfaceContainerHigh, scheme.onSurface),
                shape = RectangleShape,
                latched = on,
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 0.08.em),
                    fontWeight = if (on) FontWeight.Bold else FontWeight.Medium,
                    maxLines = 1,
                )
            }
        }
    }
}

/** A small underlined switch, used for LYRICS / UP NEXT inside the player. */
@Composable
internal fun StitchSubTab(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    Box(
        modifier
            .height(34.dp)
            .clickable(onClick = onClick)
            .border(
                1.dp,
                if (selected) scheme.primary else scheme.outline,
                RectangleShape,
            )
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 0.1.em),
            color = if (selected) scheme.primary else scheme.onSurfaceVariant,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            maxLines = 1,
        )
    }
}

/** A bordered panel with a small technical label, as the mockup's UP NEXT and SOUNDSTAGE cards. */
@Composable
internal fun StitchPanel(title: String, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier
            .fillMaxWidth()
            .border(1.dp, scheme.outline, RectangleShape)
            .padding(12.dp),
    ) {
        Text(
            title.uppercase(),
            style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 0.12.em),
            color = scheme.onSurfaceVariant,
            maxLines = 1,
        )
        Spacer(Modifier.height(10.dp))
        content()
    }
}

/** A transport or mode button drawn from the same 7x7 dot glyph set as the classic player. */
@Composable
internal fun StitchDotButton(
    glyph: Array<String>,
    description: String,
    size: Dp,
    onClick: () -> Unit,
    active: Boolean = false,
) {
    val scheme = MaterialTheme.colorScheme
    Box(
        Modifier
            .size(size)
            .clickable(onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        DotIcon(glyph, if (active) scheme.error else scheme.onSurface, Modifier.size(size * 0.82f))
    }
}
