package app.seb3thehacker.gearslip.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.core.graphics.drawable.toBitmap
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.seb3thehacker.gearslip.R
import app.seb3thehacker.gearslip.car.CarSettings
import app.seb3thehacker.gearslip.car.NightMode
import app.seb3thehacker.gearslip.car.SavedPlaces
import app.seb3thehacker.gearslip.car.Units
import app.seb3thehacker.gearslip.host.CarAppCatalog
import app.seb3thehacker.gearslip.host.CustomCarApps
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The car's settings on the phone's home screen, grouped by what they affect. */
enum class CarGroup(@StringRes val title: Int) {
    APPS(R.string.crumb_apps),
    SCREEN(R.string.car_group_screen),
    SOUND(R.string.car_group_sound),
    PLACES(R.string.places_title),
    WARNINGS(R.string.car_group_warnings),
    MORE(R.string.car_group_more),
}

@Composable
private fun nightChoices() = listOf(
    NightMode.AUTO to stringResource(R.string.choice_auto),
    NightMode.DAY to stringResource(R.string.choice_night_day),
    NightMode.NIGHT to stringResource(R.string.choice_night_night),
)

@Composable
private fun unitChoices() = listOf(
    Units.AUTO to stringResource(R.string.choice_auto),
    Units.IMPERIAL to stringResource(R.string.choice_units_imperial),
    Units.METRIC to stringResource(R.string.choice_units_metric),
)

@Composable
private fun musicSourceChoices() = listOf(
    CarSettings.MusicSource.AUTO to "Automatic",
    CarSettings.MusicSource.VIVI to "Vivi Music",
    CarSettings.MusicSource.ECHO to "Echo Music",
    CarSettings.MusicSource.LAST_USED to "Last used",
)

/** Session settings that survive a rewrite of the head-unit UI, shown from the phone when parked. */
@Composable
internal fun HomeCarSettings(onOpen: (CarGroup) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val pipeAudio by CarSettings.pipeAudio.collectAsState()
    val bluetoothWarning by CarSettings.bluetoothWarning.collectAsState()
    val locationWarning by CarSettings.locationWarning.collectAsState()
    val experimental by CarSettings.experimentalFeaturesEnabled.collectAsState()
    val places = remember { SavedPlaces.all(context) }

    @Composable
    fun summary(group: CarGroup) = when (group) {
        CarGroup.APPS -> stringResource(R.string.car_apps_sub)
        CarGroup.SCREEN -> stringResource(R.string.car_map_day_night)
        CarGroup.SOUND -> stringResource(if (pipeAudio) R.string.summary_usb_audio else R.string.summary_bluetooth_audio)
        CarGroup.PLACES -> places.mapIndexedNotNull { i, place ->
            when {
                place.address.isBlank() -> null
                i == SavedPlaces.HOME -> stringResource(R.string.places_home)
                i == SavedPlaces.WORK -> stringResource(R.string.places_work)
                else -> place.label.ifBlank { stringResource(R.string.places_other, i - SavedPlaces.WORK) }
            }
        }.ifEmpty { listOf(stringResource(R.string.places_sub)) }.joinToString(" · ")
        CarGroup.WARNINGS -> listOf(
            stringResource(if (bluetoothWarning && !pipeAudio) R.string.summary_bluetooth_on else R.string.summary_bluetooth_off),
            stringResource(if (locationWarning) R.string.summary_location_on else R.string.summary_location_off),
        ).joinToString(" · ")
        CarGroup.MORE -> stringResource(if (experimental) R.string.summary_experimental_shown else R.string.summary_experimental_hidden)
    }

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            stringResource(R.string.home_car),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(start = 4.dp),
        )
        SettingsCard {
            CarGroup.entries.forEachIndexed { i, group ->
                if (i > 0) SettingsDivider()
                SettingsRow(
                    stringResource(group.title),
                    Modifier.clickable { onOpen(group) },
                    subtitle = summary(group),
                    trailing = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null) },
                )
            }
        }
    }
}

/** One group of the car's settings, on a screen of its own. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CarGroupScreen(group: CarGroup, onBack: () -> Unit) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(group.title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(horizontal = 24.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SettingsCard { GroupRows(group) }
            if (group == CarGroup.SOUND) CarAudioSection()
        }
    }
}

@Composable
private fun GroupRows(group: CarGroup) {
    val night by CarSettings.nightMode.collectAsState()
    val units by CarSettings.units.collectAsState()
    val pipeAudio by CarSettings.pipeAudio.collectAsState()
    val bluetoothWarning by CarSettings.bluetoothWarning.collectAsState()
    val locationWarning by CarSettings.locationWarning.collectAsState()
    val experimental by CarSettings.experimentalFeaturesEnabled.collectAsState()
    val carSensors by CarSettings.carSensorsEnabled.collectAsState()
    val adaptiveVideo by CarSettings.adaptiveVideoEnabled.collectAsState()
    val clusterVideo by CarSettings.clusterVideoEnabled.collectAsState()
    val musicSource by CarSettings.musicSource.collectAsState()

    when (group) {
        CarGroup.APPS -> AppRows()
        CarGroup.SCREEN -> {
            Choice(stringResource(R.string.car_map_day_night), nightChoices(), night, CarSettings::setNightMode)
            SettingsDivider()
            Choice(stringResource(R.string.car_units), unitChoices(), units, CarSettings::setUnits)
        }
        CarGroup.SOUND -> Choice("MUSIC SOURCE", musicSourceChoices(), musicSource, CarSettings::setMusicSource)
        CarGroup.PLACES -> Unit
        CarGroup.WARNINGS -> {
            if (!pipeAudio) {
                Toggle(stringResource(R.string.car_warn_bluetooth), bluetoothWarning, CarSettings::setBluetoothWarning)
                SettingsDivider()
            }
            Toggle(stringResource(R.string.car_warn_location), locationWarning, CarSettings::setLocationWarning)
        }
        CarGroup.MORE -> {
            Toggle(stringResource(R.string.car_experimental), experimental, CarSettings::setExperimentalFeaturesEnabled, note = stringResource(R.string.car_experimental_note))
            if (experimental) {
                SettingsDivider()
                Toggle(stringResource(R.string.car_vehicle_data), carSensors, CarSettings::setCarSensorsEnabled, note = stringResource(R.string.car_vehicle_data_note))
                SettingsDivider()
                Toggle(stringResource(R.string.car_adaptive_video), adaptiveVideo, CarSettings::setAdaptiveVideoEnabled, note = stringResource(R.string.car_adaptive_video_note))
                SettingsDivider()
                Toggle(stringResource(R.string.car_cluster_video), clusterVideo, CarSettings::setClusterVideoEnabled, note = stringResource(R.string.car_cluster_video_note))
            }
        }
    }
}

/** One row per installed car app, ticked while it is offered to the car. */
@Composable
private fun AppRows() {
    val context = LocalContext.current
    val apps by produceState<List<app.seb3thehacker.gearslip.host.TemplateApp>?>(null) {
        value = withContext(Dispatchers.IO) { CarAppCatalog.installed(context) }
    }
    val hidden by CarSettings.hiddenApps.collectAsState()
    val custom by CustomCarApps.state.collectAsState()
    var adding by remember { mutableStateOf(false) }

    val rows = apps.orEmpty().map { it.component.flattenToString() to it }
    if (rows.isEmpty()) {
        SettingsRow(stringResource(if (apps == null) R.string.car_apps_finding else R.string.car_apps_none))
    }
    rows.forEachIndexed { i, (id, app) ->
        val shown = id !in hidden
        val pkg = app.component.packageName
        val added = custom.orEmpty().any { it.packageName == pkg }
        if (i > 0) SettingsDivider()
        SettingsRow(
            app.label,
            Modifier.toggleable(shown, role = Role.Checkbox) { CarSettings.setAppHidden(id, !it) },
            subtitle = if (added) stringResource(R.string.car_apps_added_by_you) else null,
            trailing = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (added) {
                        IconButton({ CustomCarApps.remove(context, pkg) }) {
                            Icon(Icons.Filled.Delete, stringResource(R.string.car_apps_remove, app.label))
                        }
                    }
                    Checkbox(checked = shown, onCheckedChange = null)
                }
            },
        )
    }
    if (apps != null) {
        SettingsDivider()
        SettingsRow(
            stringResource(R.string.car_apps_add),
            Modifier.clickable { adding = true },
            subtitle = stringResource(R.string.car_apps_add_note),
            icon = Icons.Filled.Add,
        )
    }
    if (adding) {
        AddCarAppDialog(
            listed = rows.map { (_, app) -> app.component.packageName }.toSet(),
            onDismiss = { adding = false },
        ) { pkg, kind ->
            CustomCarApps.add(context, pkg, kind)
            adding = false
        }
    }
}

/**
 * Picks an installed app the car doesn't list, then whether it is music or messages (issue #84).
 */
@Composable
private fun AddCarAppDialog(
    listed: Set<String>,
    onDismiss: () -> Unit,
    onAdd: (String, CustomCarApps.Kind) -> Unit,
) {
    val context = LocalContext.current
    var picked by remember { mutableStateOf<Pair<String, String>?>(null) }
    val candidates by produceState<List<Triple<String, String, androidx.compose.ui.graphics.ImageBitmap?>>?>(null) {
        value = withContext(Dispatchers.IO) {
            val manager = context.packageManager
            manager.queryIntentActivities(
                android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_LAUNCHER), 0,
            )
                .map { it.activityInfo.packageName to it.loadLabel(manager).toString() }
                .distinctBy { it.first }
                .filter { (pkg, _) -> pkg != context.packageName && pkg !in listed }
                .sortedBy { it.second.lowercase() }
                .map { (pkg, label) ->
                    val icon = runCatching { manager.getApplicationIcon(pkg).toBitmap(96, 96).asImageBitmap() }.getOrNull()
                    Triple(pkg, label, icon)
                }
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(picked?.let { stringResource(R.string.car_apps_add_as, it.second) } ?: stringResource(R.string.car_apps_add_pick)) },
        text = {
            val choice = picked
            if (choice == null) {
                val list = candidates
                if (list == null) {
                    Text(stringResource(R.string.car_apps_finding))
                } else {
                    LazyColumn(Modifier.heightIn(max = 420.dp)) {
                        items(list.size) { i ->
                            val (pkg, label, icon) = list[i]
                            Row(
                                Modifier.fillMaxWidth().clickable { picked = pkg to label }.padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(16.dp),
                            ) {
                                icon?.let { Image(it, contentDescription = null, Modifier.size(36.dp)) }
                                Text(label, style = MaterialTheme.typography.bodyLarge)
                            }
                        }
                    }
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    KindRow(stringResource(R.string.car_apps_as_media), stringResource(R.string.car_apps_as_media_note)) {
                        onAdd(choice.first, CustomCarApps.Kind.MEDIA)
                    }
                    KindRow(stringResource(R.string.car_apps_as_messaging), stringResource(R.string.car_apps_as_messaging_note)) {
                        onAdd(choice.first, CustomCarApps.Kind.MESSAGING)
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton({ if (picked != null) picked = null else onDismiss() }) {
                Text(stringResource(if (picked != null) R.string.back else R.string.cancel))
            }
        },
    )
}

@Composable
private fun KindRow(title: String, note: String, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary)
        Text(note, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun <T> List<Pair<T, String>>.label(value: T): String = firstOrNull { it.first == value }?.second.orEmpty()

/** A setting with a few named choices: its current one shown, the rest a tap away. */
@Composable
private fun <T> Choice(title: String, choices: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit, note: String? = null) {
    var menu by remember { mutableStateOf(false) }
    SettingsRow(
        title,
        Modifier.clickable { menu = true },
        subtitle = note,
        trailing = {
            Box {
                Text(choices.label(selected), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary)
                DropdownMenu(menu, onDismissRequest = { menu = false }) {
                    choices.forEach { (value, label) ->
                        DropdownMenuItem(
                            text = { Text(label, fontWeight = if (value == selected) FontWeight.Bold else FontWeight.Normal) },
                            onClick = { onSelect(value); menu = false },
                        )
                    }
                }
            }
        },
    )
}

@Composable
private fun Toggle(title: String, on: Boolean, onChange: (Boolean) -> Unit, note: String? = null) {
    SettingsRow(
        title,
        Modifier.clickable { onChange(!on) },
        subtitle = note,
        trailing = { Switch(checked = on, onCheckedChange = null) },
    )
}

/** How music reaches the car: by Bluetooth as usual, or through Gearslip over USB. Same choice as setup. */
@Composable
internal fun CarAudioSection() {
    val usb by CarSettings.pipeAudio.collectAsState()
    SettingsSection(
        stringResource(R.string.car_audio),
        footer = stringResource(if (usb) R.string.car_audio_usb_footer else R.string.car_audio_bt_footer),
    ) {
        SettingsRow(
            stringResource(R.string.car_audio_bt),
            Modifier.selectable(!usb, role = Role.RadioButton) { CarSettings.setPipeAudio(false) },
            subtitle = stringResource(R.string.car_audio_bt_sub),
            trailing = { RadioButton(selected = !usb, onClick = null) },
        )
        SettingsDivider()
        SettingsRow(
            stringResource(R.string.guide_audio_usb),
            Modifier.selectable(usb, role = Role.RadioButton) { CarSettings.setPipeAudio(true) },
            subtitle = stringResource(R.string.car_audio_usb_sub),
            trailing = { RadioButton(selected = usb, onClick = null) },
        )
    }
}
