package com.example.turbo

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Shell.getShell { }   // triggers the su permission prompt at launch
        setContent { Terminal() }
    }
}

@Composable
fun Terminal() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val lines = remember { mutableStateListOf("TURBO CUT - session-only system trimmer", "Tap SCAN to begin.") }
    var targets by remember { mutableStateOf(listOf<Target>()) }
    var busy by remember { mutableStateOf(false) }
    var armed by remember { mutableStateOf(false) }
    val list = rememberLazyListState()
    LaunchedEffect(lines.size) { if (lines.isNotEmpty()) list.animateScrollToItem(lines.size - 1) }

    fun colorOf(s: String) = when {
        s.startsWith("[ OK ]") || s.startsWith("[DONE]") -> Color(0xFF00E676)
        s.startsWith("[FAIL]") || s.startsWith("[DANGER]") -> Color(0xFFFF5252)
        s.startsWith("[WARN]") -> Color(0xFFFFD740)
        s.startsWith("[FOUND]") -> Color(0xFF40C4FF)
        else -> Color(0xFFB0BEC5)
    }

    Column(Modifier.fillMaxSize().background(Color.Black).padding(12.dp)) {
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = list) {
            items(lines) { Text(it, color = colorOf(it), fontFamily = FontFamily.Monospace, fontSize = 13.sp) }
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = !busy, onClick = {
                scope.launch {
                    lines.add("[ROOT] requesting root...")
                    withContext(Dispatchers.IO) {
                        Shell.getCachedShell()?.close()   // drop any cached non-root shell so su is asked again
                        val s = Shell.getShell()
                        if (s.isRoot) { lines.add("[ OK ] root granted"); lines.add(Engine.sh("id")) }
                        else lines.add("[FAIL] root denied - allow Turbo Cut in Magisk/KernelSU Superuser list, then tap ROOT")
                    }
                }
            }) { Text("ROOT") }

            Button(enabled = !busy, onClick = {
                busy = true
                scope.launch {
                    withContext(Dispatchers.IO) {
                        if (!Shell.getShell().isRoot) { lines.add("[FAIL] root not granted - allow Turbo Cut in your root manager (Magisk/KernelSU), then tap SCAN"); return@withContext }
                        targets = Engine.scan { lines.add(it) }
                    }
                    busy = false
                }
            }) { Text("SCAN") }

            Button(enabled = !busy && targets.any { !it.danger },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD50000)),
                onClick = {
                    busy = true
                    scope.launch {
                        val safe = targets.filter { !it.danger }
                        lines.add("[TURBO] stopping ${safe.size} items...")
                        withContext(Dispatchers.IO) {
                            safe.forEach { t -> lines.add(Engine.stop(ctx, t)); delay(120) }
                        }
                        lines.add("[DONE] turbo complete. Reverts on reboot.")
                        targets = targets.filter { it.danger }; busy = false
                    }
                }) { Text("STOP ALL") }

            Button(enabled = !busy, onClick = {
                scope.launch {
                    val n = withContext(Dispatchers.IO) { Engine.restore(ctx) }
                    lines.add("[ OK ] restored $n saved settings")
                }
            }) { Text("RESTORE") }

            Button(enabled = !busy && targets.any { it.danger },
                colors = ButtonDefaults.buttonColors(containerColor = if (armed) Color(0xFFFF6D00) else Color(0xFF4E342E)),
                onClick = {
                    if (!armed) {
                        armed = true
                        lines.add("[DANGER] logd/surfaceflinger next. Screen may go black. Tap DANGER again to confirm.")
                    } else {
                        armed = false; busy = true
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                targets.filter { it.danger }.forEach { t -> lines.add(Engine.stop(ctx, t)); delay(300) }
                            }
                            targets = targets.filter { !it.danger }; busy = false
                        }
                    }
                }) { Text(if (armed) "CONFIRM" else "DANGER") }

            Button(onClick = { lines.clear() }) { Text("CLEAR") }
        }
    }
}
