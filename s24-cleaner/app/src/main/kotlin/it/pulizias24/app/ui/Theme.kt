package it.pulizias24.app.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import it.pulizias24.app.device.CheckStatus
import it.pulizias24.app.scan.RiskLevel
import it.pulizias24.app.scan.Severity

private val Brand = Color(0xFF0B5CAD)

@Composable
fun PuliziaTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val colors = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> darkColorScheme(primary = Color(0xFF9ECAFF))
        else -> lightColorScheme(primary = Brand)
    }
    MaterialTheme(colorScheme = colors, content = content)
}

object RiskColors {
    val Ok = Color(0xFF2E7D32)
    val Info = Color(0xFF1565C0)
    val Warning = Color(0xFFEF6C00)
    val Danger = Color(0xFFC62828)

    fun of(level: RiskLevel): Color = when (level) {
        RiskLevel.SAFE -> Ok
        RiskLevel.LOW -> Info
        RiskLevel.MEDIUM -> Warning
        RiskLevel.HIGH -> Danger
    }

    fun of(severity: Severity): Color = when (severity) {
        Severity.LOW -> Info
        Severity.MEDIUM -> Warning
        Severity.HIGH -> Danger
    }

    fun of(status: CheckStatus): Color = when (status) {
        CheckStatus.OK -> Ok
        CheckStatus.INFO -> Info
        CheckStatus.WARNING -> Warning
        CheckStatus.DANGER -> Danger
    }
}
