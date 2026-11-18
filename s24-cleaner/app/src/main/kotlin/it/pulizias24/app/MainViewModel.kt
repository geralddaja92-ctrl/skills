package it.pulizias24.app

import android.app.Application
import android.text.format.Formatter
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import it.pulizias24.app.clean.JunkCategory
import it.pulizias24.app.clean.JunkItem
import it.pulizias24.app.clean.JunkScanner
import it.pulizias24.app.device.DeviceCheck
import it.pulizias24.app.device.DeviceChecks
import it.pulizias24.app.scan.AppReport
import it.pulizias24.app.scan.AppScanner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class AppScanState(
    val scanning: Boolean = false,
    val includeSystem: Boolean = false,
    val reports: List<AppReport>? = null,
)

data class JunkState(
    val hasAccess: Boolean = false,
    val scanning: Boolean = false,
    val deleting: Boolean = false,
    val progress: String = "",
    val items: List<JunkItem>? = null,
    val selected: Set<String> = emptySet(),
    val message: String? = null,
) {
    val selectedBytes: Long
        get() = items.orEmpty().filter { it.path in selected }.sumOf { it.size }
}

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val appScanner = AppScanner(app)
    private val junkScanner = JunkScanner(app)

    private val _apps = MutableStateFlow(AppScanState())
    val apps: StateFlow<AppScanState> = _apps.asStateFlow()

    private val _junk = MutableStateFlow(JunkState(hasAccess = junkScanner.hasAccess()))
    val junk: StateFlow<JunkState> = _junk.asStateFlow()

    private val _device = MutableStateFlow<List<DeviceCheck>>(emptyList())
    val device: StateFlow<List<DeviceCheck>> = _device.asStateFlow()

    // ---- Scansione app ----

    fun scanApps() {
        if (_apps.value.scanning) return
        _apps.update { it.copy(scanning = true) }
        viewModelScope.launch {
            val result = withContext(Dispatchers.Default) {
                appScanner.scan(_apps.value.includeSystem)
            }
            _apps.update { it.copy(scanning = false, reports = result) }
        }
    }

    fun setIncludeSystem(include: Boolean) {
        _apps.update { it.copy(includeSystem = include) }
        if (_apps.value.reports != null) scanApps()
    }

    /** Dopo una disinstallazione o un cambio di permessi, aggiorna i risultati. */
    fun onAppsScreenResumed() {
        if (_apps.value.reports != null && !_apps.value.scanning) scanApps()
    }

    // ---- Pulizia file ----

    fun refreshStorageAccess() {
        _junk.update { it.copy(hasAccess = junkScanner.hasAccess()) }
    }

    fun scanJunk() {
        if (_junk.value.scanning || !junkScanner.hasAccess()) return
        _junk.update { it.copy(scanning = true, progress = "Avvio…", message = null) }
        viewModelScope.launch {
            val items = withContext(Dispatchers.IO) {
                junkScanner.scan { scanned, current ->
                    _junk.update { it.copy(progress = "$scanned file analizzati\n$current") }
                }
            }
            _junk.update { state ->
                state.copy(
                    scanning = false,
                    items = items,
                    selected = items.filter { it.category.preselected }.map { it.path }.toSet(),
                )
            }
        }
    }

    fun toggleItem(path: String) {
        _junk.update {
            it.copy(selected = if (path in it.selected) it.selected - path else it.selected + path)
        }
    }

    fun setCategorySelected(category: JunkCategory, selected: Boolean) {
        _junk.update { state ->
            val paths = state.items.orEmpty().filter { it.category == category }.map { it.path }
            state.copy(selected = if (selected) state.selected + paths else state.selected - paths.toSet())
        }
    }

    fun deleteSelected() {
        val state = _junk.value
        val toDelete = state.items.orEmpty().filter { it.path in state.selected }
        if (toDelete.isEmpty() || state.deleting) return
        _junk.update { it.copy(deleting = true) }
        viewModelScope.launch {
            val (result, deletedPaths) = withContext(Dispatchers.IO) {
                val r = junkScanner.delete(toDelete)
                r to toDelete.map { it.path }.filterNot { java.io.File(it).exists() }.toSet()
            }
            val freed = Formatter.formatShortFileSize(getApplication(), result.freedBytes)
            _junk.update { s ->
                s.copy(
                    deleting = false,
                    items = s.items.orEmpty().filter { it.path !in deletedPaths },
                    selected = emptySet(),
                    message = buildString {
                        append("Eliminati ${result.deleted} elementi, liberati $freed.")
                        if (result.failed > 0) append(" ${result.failed} non eliminabili.")
                    },
                )
            }
        }
    }

    // ---- Controlli dispositivo ----

    fun refreshDevice() {
        viewModelScope.launch {
            _device.value = withContext(Dispatchers.Default) { DeviceChecks.run(getApplication()) }
        }
    }
}
