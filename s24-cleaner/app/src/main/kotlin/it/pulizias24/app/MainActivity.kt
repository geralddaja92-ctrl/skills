package it.pulizias24.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.viewmodel.compose.viewModel
import it.pulizias24.app.ui.AppsScreen
import it.pulizias24.app.ui.CleanScreen
import it.pulizias24.app.ui.DeviceScreen
import it.pulizias24.app.ui.PuliziaTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            PuliziaTheme { PuliziaApp() }
        }
    }
}

private enum class Tab(val title: String, val icon: ImageVector) {
    APPS("Scansione app", Icons.Filled.Shield),
    CLEAN("Pulizia", Icons.Filled.CleaningServices),
    DEVICE("Dispositivo", Icons.Filled.PhoneAndroid),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PuliziaApp(vm: MainViewModel = viewModel()) {
    var current by rememberSaveable { mutableIntStateOf(0) }
    val tab = Tab.entries[current]
    Scaffold(
        topBar = { CenterAlignedTopAppBar(title = { Text("Pulizia S24 · ${tab.title}") }) },
        bottomBar = {
            NavigationBar {
                Tab.entries.forEachIndexed { index, t ->
                    NavigationBarItem(
                        selected = index == current,
                        onClick = { current = index },
                        icon = { Icon(t.icon, contentDescription = null) },
                        label = { Text(t.title) },
                    )
                }
            }
        },
    ) { padding ->
        val modifier = Modifier.padding(padding)
        when (tab) {
            Tab.APPS -> AppsScreen(vm, modifier)
            Tab.CLEAN -> CleanScreen(vm, modifier)
            Tab.DEVICE -> DeviceScreen(vm, modifier)
        }
    }
}
