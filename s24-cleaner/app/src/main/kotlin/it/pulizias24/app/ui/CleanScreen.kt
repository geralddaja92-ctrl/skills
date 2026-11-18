package it.pulizias24.app.ui

import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.Settings
import android.text.format.Formatter
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import it.pulizias24.app.MainViewModel
import it.pulizias24.app.clean.JunkCategory
import it.pulizias24.app.clean.JunkItem
import it.pulizias24.app.device.CheckAction
import it.pulizias24.app.device.DeviceChecks
import it.pulizias24.app.device.perform
import it.pulizias24.app.device.startSafely

private const val MAX_VISIBLE_PER_CATEGORY = 300

@Composable
fun CleanScreen(vm: MainViewModel, modifier: Modifier = Modifier) {
    val state by vm.junk.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refreshStorageAccess() }
    var expandedCategories by rememberSaveable { mutableStateOf(setOf<String>()) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    val size = { bytes: Long -> Formatter.formatShortFileSize(context, bytes) }

    Column(modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (!state.hasAccess) {
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Serve l'accesso ai file", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "Per trovare APK sospetti, file temporanei, duplicati e resti di app " +
                                    "disinstallate, attiva \"Consenti l'accesso per gestire tutti i " +
                                    "file\" per Pulizia S24. Nessun file viene inviato fuori dal telefono.",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Button(onClick = {
                                context.startSafely(
                                    Intent(
                                        Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                        Uri.parse("package:${context.packageName}"),
                                    ),
                                    Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION),
                                )
                            }) { Text("Concedi accesso") }
                        }
                    }
                }
            } else {
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            val items = state.items
                            when {
                                state.scanning -> {
                                    Text("Analisi della memoria…", style = MaterialTheme.typography.titleMedium)
                                    LinearProgressIndicator(Modifier.fillMaxWidth())
                                    Text(
                                        state.progress.replace(Environment.getExternalStorageDirectory().absolutePath, ""),
                                        style = MaterialTheme.typography.bodySmall,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                items == null -> {
                                    Text("Libera spazio e rimuovi file sospetti", style = MaterialTheme.typography.titleMedium)
                                    Text(
                                        "Vengono preselezionati solo file sicuri da eliminare. Duplicati " +
                                            "e file grandi vanno scelti a mano.",
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                }
                                items.isEmpty() -> Text("Memoria pulita: niente da eliminare.")
                                else -> Text(
                                    "Trovati ${items.size} elementi (${size(items.sumOf { it.size })})",
                                    style = MaterialTheme.typography.titleMedium,
                                )
                            }
                            state.message?.let {
                                Text(it, color = RiskColors.Ok, style = MaterialTheme.typography.bodyMedium)
                            }
                            Button(onClick = vm::scanJunk, enabled = !state.scanning && !state.deleting) {
                                Text(if (state.items == null) "Analizza memoria" else "Ripeti analisi")
                            }
                        }
                    }
                }

                val grouped = state.items.orEmpty().groupBy { it.category }
                JunkCategory.entries.filter { it in grouped }.forEach { category ->
                    val catItems = grouped.getValue(category)
                    val selectedCount = catItems.count { it.path in state.selected }
                    val isExpanded = category.name in expandedCategories
                    item(key = "header-${category.name}") {
                        CategoryHeader(
                            category = category,
                            count = catItems.size,
                            totalSize = size(catItems.sumOf { it.size }),
                            toggleState = when (selectedCount) {
                                0 -> ToggleableState.Off
                                catItems.size -> ToggleableState.On
                                else -> ToggleableState.Indeterminate
                            },
                            expanded = isExpanded,
                            onToggle = { vm.setCategorySelected(category, selectedCount < catItems.size) },
                            onExpand = {
                                expandedCategories =
                                    if (isExpanded) expandedCategories - category.name
                                    else expandedCategories + category.name
                            },
                        )
                    }
                    if (isExpanded) {
                        items(catItems.take(MAX_VISIBLE_PER_CATEGORY), key = { "${category.name}-${it.path}" }) { item ->
                            JunkRow(item, item.path in state.selected, size(item.size)) { vm.toggleItem(item.path) }
                        }
                        if (catItems.size > MAX_VISIBLE_PER_CATEGORY) {
                            item(key = "more-${category.name}") {
                                Text(
                                    "…e altri ${catItems.size - MAX_VISIBLE_PER_CATEGORY} elementi " +
                                        "(inclusi nella selezione della categoria)",
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.padding(start = 48.dp),
                                )
                            }
                        }
                    }
                }
            }

            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Cache delle altre app", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Android non permette a nessuna app esterna di svuotare la cache delle " +
                                "altre app. Usa Assistenza dispositivo › Memoria di Samsung, oppure " +
                                "Impostazioni › App › (app) › Memoria › Cancella cache.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        OutlinedButton(onClick = {
                            context.perform(
                                CheckAction.LaunchApp(
                                    "Device Care",
                                    DeviceChecks.DEVICE_CARE_PACKAGE,
                                    Settings.ACTION_INTERNAL_STORAGE_SETTINGS,
                                ),
                            )
                        }) { Text("Apri Assistenza dispositivo") }
                    }
                }
            }
        }

        if (state.selected.isNotEmpty()) {
            Button(
                onClick = { confirmDelete = true },
                enabled = !state.deleting && !state.scanning,
                colors = ButtonDefaults.buttonColors(containerColor = RiskColors.Danger),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Text(
                    if (state.deleting) "Eliminazione in corso…"
                    else "Elimina ${state.selected.size} elementi (${size(state.selectedBytes)})",
                )
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Eliminare definitivamente?") },
            text = {
                Text(
                    "Verranno eliminati ${state.selected.size} elementi " +
                        "(${size(state.selectedBytes)}). I file non finiscono nel Cestino e non " +
                        "potranno essere recuperati.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    vm.deleteSelected()
                }) { Text("Elimina", color = RiskColors.Danger) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("Annulla") }
            },
        )
    }
}

@Composable
private fun CategoryHeader(
    category: JunkCategory,
    count: Int,
    totalSize: String,
    toggleState: ToggleableState,
    expanded: Boolean,
    onToggle: () -> Unit,
    onExpand: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .clickable(onClick = onExpand)
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TriStateCheckbox(state = toggleState, onClick = onToggle)
            Column(Modifier.weight(1f)) {
                Text("${category.title} · $count", fontWeight = FontWeight.SemiBold)
                Text(totalSize, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                Text(category.description, style = MaterialTheme.typography.bodySmall)
            }
            Icon(
                if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = if (expanded) "Comprimi" else "Espandi",
            )
        }
    }
}

@Composable
private fun JunkRow(item: JunkItem, checked: Boolean, size: String, onToggle: () -> Unit) {
    val root = Environment.getExternalStorageDirectory().absolutePath
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(start = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = { onToggle() })
        Spacer(Modifier.width(4.dp))
        Column(Modifier.weight(1f)) {
            Text(
                item.path.removePrefix(root).ifEmpty { "/" },
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            item.note?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (item.size > 0) Text(size, style = MaterialTheme.typography.labelMedium)
    }
}
