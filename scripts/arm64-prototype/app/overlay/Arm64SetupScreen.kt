package app.gamenative

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

@Composable
fun Arm64SetupScreen(onComplete: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prepared = Arm64Diagnostics.isPrepared(context)
    var running by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) {
            scope.launch {
                Arm64Diagnostics.exportLatest(context, uri).fold(
                    onSuccess = { message = "Exported latest report; missing logs remain listed in report.json." },
                    onFailure = { message = "Export failed: ${it.message ?: it}" },
                )
            }
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("ARM64 diagnostics", style = MaterialTheme.typography.headlineMedium)
        Text("Run the three custom games in order: Hello (exit 42 is normal), API (exit 0), then DynCode (exit 0).")
        Text("Each setup creates a new immutable run-id and log path. Existing sessions are not deleted.")
        Text("GameNative may still provision its Bionic ImageFS from its existing source; setup does not claim runtime readiness.")
        if (running) {
            CircularProgressIndicator(modifier = Modifier.padding(24.dp))
            Text("Installing runtime profiles and registering probes…")
        } else {
            Button(onClick = {
                running = true
                message = null
                scope.launch {
                    Arm64Diagnostics.installAndRegister(context).fold(
                        onSuccess = { running = false; message = "New session prepared. Open GameNative to run each entry." },
                        onFailure = { running = false; message = "Setup failed: ${it.message ?: it}" },
                    )
                }
            }) {
                Text(if (prepared) "Prepare new session (keep old logs)" else "Prepare ARM64 diagnostics")
            }
            if (prepared) {
                Button(onClick = onComplete, modifier = Modifier.padding(top = 8.dp)) {
                    Text("Open GameNative custom games")
                }
                Button(
                    onClick = { exportLauncher.launch("arm64-diagnostics-${Arm64Diagnostics.latestSessionId(context)}.zip") },
                    modifier = Modifier.padding(top = 8.dp),
                ) {
                    Text("Export latest diagnostics (SAF)")
                }
            }
        }
        message?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 16.dp)) }
    }
}
