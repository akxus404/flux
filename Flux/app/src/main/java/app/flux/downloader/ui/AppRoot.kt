package app.flux.downloader.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Public
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import app.flux.downloader.browser.BrowserScreen

@Composable
fun AppRoot(vm: MainViewModel) {
    var tab by rememberSaveable { mutableIntStateOf(0) }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .systemBarsPadding(),
    ) {
        Box(Modifier.weight(1f)) {
            if (tab == 0) {
                MainScreen(vm)
            } else {
                BrowserScreen(
                    session = vm.browserSession,
                    onDownloadRequest = { url ->
                        vm.onSharedText(url)
                        tab = 0
                    },
                )
            }
        }
        NavigationBar(
            containerColor = MaterialTheme.colorScheme.surface,
            windowInsets = WindowInsets(0, 0, 0, 0),
        ) {
            NavigationBarItem(
                selected = tab == 0,
                onClick = { tab = 0 },
                icon = { Icon(Icons.Default.Download, contentDescription = null) },
                label = { Text("Descargas") },
            )
            NavigationBarItem(
                selected = tab == 1,
                onClick = { tab = 1 },
                icon = { Icon(Icons.Default.Public, contentDescription = null) },
                label = { Text("Navegador") },
            )
        }
    }
}
