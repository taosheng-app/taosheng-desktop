package app.resound.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** 共享 UI：所有平台通用，平台相关代码放到各自的 source set。 */
@Composable
fun App() {
    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            var clicks by remember { mutableStateOf(0) }

            Column(
                modifier = Modifier.fillMaxSize().padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(greeting(), style = MaterialTheme.typography.headlineMedium)
                Text("Running on: ${platformName()}")
                Button(onClick = { clicks++ }) {
                    Text("Clicked $clicks times")
                }
            }
        }
    }
}

fun greeting(): String = "涛声桌面版"

/** 平台差异通过 expect/actual 隔离。 */
expect fun platformName(): String