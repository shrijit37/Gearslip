package app.seb3thehacker.gearslip.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue

/**
 * Phone-app theme. The car's own look lives in whichever head-unit surface is attached; the
 * phone follows the driver's App theme choice so its controls stay legible when parked.
 */
@Composable
fun GearslipTheme(content: @Composable () -> Unit) {
    val dark = gearslipDark()
    MaterialTheme(
        colorScheme = if (dark) darkColorScheme() else lightColorScheme(),
        content = content,
    )
}

/** Whether the phone app is dark: the car's App theme setting, so Light means light everywhere. */
@Composable
fun gearslipDark(): Boolean {
    val theme by app.seb3thehacker.gearslip.car.CarSettings.appTheme.collectAsState()
    return when (theme) {
        app.seb3thehacker.gearslip.car.AppTheme.PHONE -> isSystemInDarkTheme()
        app.seb3thehacker.gearslip.car.AppTheme.LIGHT -> false
        app.seb3thehacker.gearslip.car.AppTheme.DARK -> true
    }
}
