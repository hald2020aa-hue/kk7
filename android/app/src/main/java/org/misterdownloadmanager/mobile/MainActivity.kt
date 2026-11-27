package org.misterdownloadmanager.mobile

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.*
import java.io.File
import java.util.Locale

internal fun formatBytes(bytes: Long): String {
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    var amount = bytes.toDouble().coerceAtLeast(0.0)
    var i = 0
    while (amount >= 1024 && i < units.lastIndex) { amount /= 1024; i++ }
    return String.format(Locale.ROOT, "%.1f %s", amount, units[i])
}

class MainActivity : ComponentActivity() {
    private var incoming by mutableStateOf("")
    private var exportSource: File? = null
    private var exportMessage by mutableStateOf("")
    private val saveFile = registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val source = exportSource
        exportSource = null
        if (uri != null && source != null) lifecycleScope.launch {
            exportMessage = try {
                withContext(Dispatchers.IO) {
                    val output = contentResolver.openOutputStream(uri) ?: error("Cannot open destination")
                    source.inputStream().use { input -> output.use { input.copyTo(it) } }
                }
                getString(R.string.saved)
            } catch (e: Exception) { e.message ?: getString(R.string.failed) }
        }
    }
    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        readIntent(intent)
        if (Build.VERSION.SDK_INT >= 33) permission.launch(Manifest.permission.POST_NOTIFICATIONS)
        setContent { MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFF55E6DE), secondary = Color(0xFFA99CFF), background = Color(0xFF0B111D), surface = Color(0xFF141D2D))) { Dashboard() } }
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); readIntent(intent) }
    private fun readIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND) incoming = intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
    }
    private fun start(id: Long) = startForegroundService(Intent(this, DownloadService::class.java).putExtra("id", id))
    private fun export(job: Job) {
        val source = job.file?.let(::File) ?: return
        if (!source.isFile) { exportMessage = getString(R.string.failed); return }
        exportSource = source
        saveFile.launch(job.name)
    }

    @Composable private fun Dashboard() {
        var jobs by remember { mutableStateOf(emptyList<Job>()) }
        var ready by remember { mutableStateOf(false) }
        var error by remember { mutableStateOf("") }
        var add by remember { mutableStateOf(false) }
        var filter by remember { mutableStateOf(0) }
        val scope = rememberCoroutineScope()
        LaunchedEffect(Unit) {
            try { withContext(Dispatchers.IO) { Engine.initialize(this@MainActivity) }; ready = true }
            catch (e: Exception) { error = e.message.orEmpty(); return@LaunchedEffect }
            catch (e: LinkageError) { error = e.message.orEmpty(); return@LaunchedEffect }
            while (isActive) {
                try { jobs = withContext(Dispatchers.IO) { Engine.list() } }
                catch (e: Exception) { error = e.message.orEmpty() }
                delay(750)
            }
        }
        LaunchedEffect(incoming) { if (incoming.isNotBlank()) add = true }
        fun action(block: suspend () -> Unit) { scope.launch { try { block() } catch (e: Exception) { error = e.message.orEmpty() } } }
        Scaffold(containerColor = MaterialTheme.colorScheme.background, floatingActionButton = {
            ExtendedFloatingActionButton(onClick = { if (ready) add = true }, containerColor = MaterialTheme.colorScheme.primary, contentColor = Color(0xFF071A1B)) { Text("+  ${getString(R.string.add)}") }
        }) { pad ->
            Column(Modifier.fillMaxSize().padding(pad).statusBarsPadding().padding(horizontal = 16.dp)) {
                Spacer(Modifier.height(16.dp))
                Text("MISTER", color = MaterialTheme.colorScheme.primary, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 3.sp)
                Text(getString(R.string.downloads), fontSize = 28.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(16.dp))
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Row(Modifier.fillMaxWidth().padding(18.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        Column { Text(getString(R.string.speed)); Text("${formatBytes(jobs.sumOf { it.speed })}/s", fontSize = 26.sp, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold) }
                        Column { Text(getString(R.string.active)); Text(jobs.count { it.running }.toString(), fontSize = 26.sp, fontWeight = FontWeight.Bold) }
                    }
                }
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(R.string.all, R.string.active, R.string.complete).forEachIndexed { i, label -> FilterChip(selected = i == filter, onClick = { filter = i }, label = { Text(getString(label)) }) }
                }
                if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
                if (exportMessage.isNotBlank()) Text(exportMessage, color = MaterialTheme.colorScheme.primary)
                val shown = jobs.filter { filter == 0 || (filter == 1 && it.running) || (filter == 2 && it.status == "complete") }
                if (shown.isEmpty()) Text(getString(if (ready) R.string.empty else R.string.loading), modifier = Modifier.padding(vertical = 24.dp))
                LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 100.dp)) {
                    items(shown, key = { it.id }) { j ->
                        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(16.dp)) {
                            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                                Text(j.name, maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                                Spacer(Modifier.height(10.dp))
                                if (j.total > 0) LinearProgressIndicator(progress = { (j.downloaded.toDouble() / j.total).toFloat().coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                                else if (j.running) LinearProgressIndicator(Modifier.fillMaxWidth())
                                Text("${formatBytes(j.downloaded)} / ${if (j.total > 0) formatBytes(j.total) else "—"}  •  ${formatBytes(j.speed)}/s", fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
                                if (j.error != null) Text(j.error.orEmpty(), color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    when (j.status) {
                                        "running" -> TextButton(onClick = { action { withContext(Dispatchers.IO) { Engine.call("pause", "id" to j.id) } } }) { Text(getString(R.string.pause)) }
                                        "stopping" -> Text(getString(R.string.stopping), modifier = Modifier.padding(12.dp))
                                        "complete" -> TextButton(onClick = { export(j) }) { Text(getString(R.string.export)) }
                                        else -> TextButton(onClick = { action { start(j.id) } }) { Text(getString(R.string.resume)) }
                                    }
                                    if (j.running) Text("${j.connections} ${getString(R.string.connections)}", modifier = Modifier.padding(12.dp), fontSize = 12.sp)
                                }
                            }
                        }
                    }
                }
            }
        }
        if (add) {
            var url by remember { mutableStateOf(incoming) }
            var name by remember { mutableStateOf("") }
            var connections by remember { mutableStateOf("8") }
            var limit by remember { mutableStateOf("") }
            AlertDialog(onDismissRequest = { add = false; incoming = "" }, title = { Text(getString(R.string.add)) }, text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(url, { url = it }, label = { Text("URL") }, singleLine = true)
                    OutlinedTextField(name, { name = it }, label = { Text(getString(R.string.name)) }, singleLine = true)
                    OutlinedTextField(connections, { connections = it }, label = { Text(getString(R.string.connections)) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true)
                    OutlinedTextField(limit, { limit = it }, label = { Text(getString(R.string.limit)) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true)
                }
            }, confirmButton = { TextButton(enabled = ready && url.isNotBlank(), onClick = {
                action {
                    val id = withContext(Dispatchers.IO) { (Engine.call("add", "url" to url, "name" to name, "connections" to (connections.toIntOrNull() ?: 8), "limit" to limit.toLongOrNull()?.coerceIn(0, Long.MAX_VALUE / 1024)?.times(1024)) as Number).toLong() }
                    add = false; incoming = ""; start(id)
                }
            }) { Text(getString(R.string.start)) } }, dismissButton = { TextButton(onClick = { add = false; incoming = "" }) { Text(getString(R.string.cancel)) } })
        }
    }
}
