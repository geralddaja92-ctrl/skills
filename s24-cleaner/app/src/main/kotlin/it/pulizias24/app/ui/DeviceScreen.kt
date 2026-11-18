package it.pulizias24.app.ui

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import it.pulizias24.app.MainViewModel
import it.pulizias24.app.device.CheckStatus
import it.pulizias24.app.device.DeviceCheck
import it.pulizias24.app.device.perform

@Composable
fun DeviceScreen(vm: MainViewModel, modifier: Modifier = Modifier) {
    val checks by vm.device.collectAsStateWithLifecycle()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refreshDevice() }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            val problems = checks.count { it.status == CheckStatus.WARNING || it.status == CheckStatus.DANGER }
            Text(
                when {
                    checks.isEmpty() -> "Controllo in corso…"
                    problems == 0 -> "Impostazioni di sicurezza a posto"
                    else -> "$problems impostazioni da sistemare"
                },
                style = MaterialTheme.typography.titleLarge,
                color = if (problems == 0) RiskColors.Ok else RiskColors.Warning,
            )
        }
        items(checks, key = { it.title }) { CheckCard(it) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CheckCard(check: DeviceCheck) {
    val context = LocalContext.current
    val color = RiskColors.of(check.status)
    val icon = when (check.status) {
        CheckStatus.OK -> Icons.Filled.CheckCircle
        CheckStatus.INFO -> Icons.Filled.Info
        CheckStatus.WARNING -> Icons.Filled.Warning
        CheckStatus.DANGER -> Icons.Filled.Error
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(12.dp))
                Text(check.title, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleSmall)
            }
            Text(check.detail, style = MaterialTheme.typography.bodyMedium)
            if (check.actions.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    check.actions.forEach { action ->
                        OutlinedButton(onClick = { context.perform(action) }) { Text(action.label) }
                    }
                }
            }
        }
    }
}
