package it.pulizias24.app.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import it.pulizias24.app.MainViewModel
import it.pulizias24.app.device.startSafely
import it.pulizias24.app.scan.AppReport
import it.pulizias24.app.scan.AppScanner
import it.pulizias24.app.scan.Finding
import it.pulizias24.app.scan.RiskLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun AppsScreen(vm: MainViewModel, modifier: Modifier = Modifier) {
    val state by vm.apps.collectAsStateWithLifecycle()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.onAppsScreenResumed() }
    var expanded by rememberSaveable { mutableStateOf<String?>(null) }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { SummaryCard(state.reports, state.scanning, vm::scanApps) }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Includi app di sistema", Modifier.weight(1f))
                Switch(checked = state.includeSystem, onCheckedChange = vm::setIncludeSystem)
            }
        }
        val reports = state.reports
        if (reports != null) {
            items(reports, key = { it.packageName }) { report ->
                AppCard(
                    report = report,
                    expanded = expanded == report.packageName,
                    onClick = {
                        expanded = if (expanded == report.packageName) null else report.packageName
                    },
                )
            }
        }
    }
}

@Composable
private fun SummaryCard(reports: List<AppReport>?, scanning: Boolean, onScan: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when {
                scanning -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 3.dp)
                    Spacer(Modifier.width(12.dp))
                    Text("Analisi delle app in corso…")
                }
                reports == null -> {
                    Text("Controlla le app installate", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "L'analisi cerca i segnali tipici di virus, app spia e adware: origine " +
                            "dell'app, permessi pericolosi, accessibilità, amministratori del " +
                            "dispositivo e icone nascoste. Tutto avviene sul telefono, offline.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                else -> {
                    val high = reports.count { it.level == RiskLevel.HIGH }
                    val medium = reports.count { it.level == RiskLevel.MEDIUM }
                    val title = when {
                        high > 0 -> "$high app pericolose trovate"
                        medium > 0 -> "$medium app sospette da controllare"
                        else -> "Nessuna minaccia evidente"
                    }
                    val color = when {
                        high > 0 -> RiskColors.Danger
                        medium > 0 -> RiskColors.Warning
                        else -> RiskColors.Ok
                    }
                    Text(title, style = MaterialTheme.typography.titleLarge, color = color)
                    Text(
                        "${reports.size} app analizzate · $high pericolose · $medium sospette. " +
                            "Tocca un'app per i dettagli. Un punteggio alto non significa per " +
                            "forza virus: disinstalla solo ciò che non riconosci.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            Button(onClick = onScan, enabled = !scanning) {
                Text(if (reports == null) "Avvia scansione" else "Ripeti scansione")
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AppCard(report: AppReport, expanded: Boolean, onClick: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val icon = remember(report.packageName) {
        runCatching {
            context.packageManager.getApplicationIcon(report.packageName)
                .toBitmap(96, 96).asImageBitmap()
        }.getOrNull()
    }
    val color = RiskColors.of(report.level)

    Card(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (icon != null) {
                    Image(icon, contentDescription = null, modifier = Modifier.size(40.dp))
                } else {
                    Box(Modifier.size(40.dp))
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(report.label, fontWeight = FontWeight.SemiBold)
                    Text(
                        report.packageName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Surface(color = color.copy(alpha = 0.15f), shape = CircleShape) {
                    Text(
                        report.level.label,
                        color = color,
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    )
                }
            }

            AnimatedVisibility(expanded) {
                Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Origine: ${report.installerLabel}" +
                            (report.versionName?.let { " · versione $it" } ?: ""),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (report.findings.isEmpty()) {
                        Text("Nessun segnale sospetto.", style = MaterialTheme.typography.bodyMedium)
                    }
                    report.findings.forEach { FindingRow(it) }
                    HorizontalDivider()
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (!report.isSystem) {
                            FilledTonalButton(onClick = {
                                if (report.isDeviceAdmin) {
                                    Toast.makeText(
                                        context,
                                        "Prima disattiva l'amministratore del dispositivo",
                                        Toast.LENGTH_LONG,
                                    ).show()
                                    context.startSafely(Intent(Settings.ACTION_SECURITY_SETTINGS))
                                } else {
                                    context.startSafely(
                                        Intent(Intent.ACTION_DELETE, Uri.parse("package:${report.packageName}")),
                                        appDetails(report.packageName),
                                    )
                                }
                            }) { Text("Disinstalla") }
                        }
                        OutlinedButton(onClick = { context.startSafely(appDetails(report.packageName)) }) {
                            Text("Info app")
                        }
                        if (report.hasAccessibilityEnabled) {
                            OutlinedButton(onClick = {
                                context.startSafely(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                            }) { Text("Accessibilità") }
                        }
                        if (report.isDeviceAdmin) {
                            OutlinedButton(onClick = {
                                context.startSafely(Intent(Settings.ACTION_SECURITY_SETTINGS))
                            }) { Text("Amministratori") }
                        }
                        val apk = report.apkPath
                        if (apk != null) {
                            OutlinedButton(onClick = {
                                scope.launch {
                                    val hash = withContext(Dispatchers.IO) {
                                        runCatching { AppScanner.sha256(apk) }.getOrNull()
                                    }
                                    if (hash == null) {
                                        Toast.makeText(context, "Impossibile leggere l'APK", Toast.LENGTH_SHORT).show()
                                    } else {
                                        // Si apre solo la pagina dell'impronta: il file non viene caricato.
                                        context.startSafely(
                                            Intent(
                                                Intent.ACTION_VIEW,
                                                Uri.parse("https://www.virustotal.com/gui/file/$hash"),
                                            ),
                                            null,
                                        )
                                    }
                                }
                            }) { Text("Verifica su VirusTotal") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FindingRow(finding: Finding) {
    val color = RiskColors.of(finding.severity)
    Row {
        Surface(
            color = color,
            shape = CircleShape,
            modifier = Modifier.padding(top = 6.dp).size(8.dp),
        ) {}
        Spacer(Modifier.width(8.dp))
        Column {
            Text(
                "${finding.title} · rischio ${finding.severity.label.lowercase()}",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = color,
            )
            Text(finding.detail, style = MaterialTheme.typography.bodySmall)
        }
    }
}

private fun appDetails(pkg: String) =
    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg"))
