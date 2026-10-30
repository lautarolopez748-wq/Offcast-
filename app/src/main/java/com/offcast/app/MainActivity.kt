package com.offcast.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Forward30
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.List as ListIcon
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import coil.compose.AsyncImage

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        askNotificationPermission()
        if (savedInstanceState == null) handleShare(intent)
        setContent { OffcastApp(vm) }
    }

    override fun onStart() {
        super.onStart()
        Core.appVisible = true
    }

    override fun onStop() {
        Core.appVisible = false
        super.onStop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShare(intent)
    }

    // Android 13+: sin este permiso no se ven los controles de la notificación ni los avisos.
    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // "Compartir" un podcast, tocar aviso de episodio nuevo, o abrir episodio desde el widget
    private fun handleShare(i: Intent?) {
        if (i == null) return
        val action = i.action
        if (action == "com.offcast.app.OPEN_EPISODE") {
            val epId = i.getStringExtra("episode_id")
            if (epId != null) {
                vm.screen = Screen.Library
                val s = vm.library.firstOrNull { it.id == epId }
                s?.let { vm.play(it) }
            }
            return
        }
        val feed = i.getStringExtra(Notif.EXTRA_FEED)
        if (feed != null) {
            i.removeExtra(Notif.EXTRA_FEED)
            vm.screen = Screen.Home
            vm.follows.firstOrNull { it.feedUrl == feed }?.let { vm.openFollow(it) }
            return
        }
        if (i.action == Intent.ACTION_SEND) {
            val t = i.getStringExtra(Intent.EXTRA_TEXT)
            if (!t.isNullOrBlank()) {
                vm.screen = Screen.Home
                vm.submit(t)
            }
        }
    }
}

// ---------- Estilo ----------

/** Negro con azul difuminado. La app usa siempre este tema. */
private val Accent = Brush.horizontalGradient(listOf(Color(0xFF3B7BFF), Color(0xFF1F4FD8)))

/** Azul claro para textos y acentos sueltos. */
private val BlueLight = Color(0xFF9CC2FF)

/** Texto apagado de la barra inferior. */
private val NavIdle = Color(0xFF8496B8)

private val Scheme = darkColorScheme(
    primary = Color(0xFF6FA8FF),
    onPrimary = Color(0xFF04101F),
    secondary = Color(0xFF4D8DFF),
    background = Color(0xFF04060A),
    onBackground = Color(0xFFE8F0FF),
    surface = Color(0xFF0A1220),
    onSurface = Color(0xFFE8F0FF),
    error = Color(0xFFFF7A8A)
)

@Composable
fun OffcastApp(vm: AppViewModel) {
    MaterialTheme(colorScheme = Scheme) {
        Box(
            Modifier
                .fillMaxSize()
                .drawBehind {
                    // Base: negro con azul profundo.
                    drawRect(
                        Brush.verticalGradient(
                            listOf(Color(0xFF04060A), Color(0xFF081426), Color(0xFF05090F), Color(0xFF04060A))
                        )
                    )
                    // Halo azul difuminado arriba a la derecha.
                    drawRect(
                        Brush.radialGradient(
                            listOf(Color(0xFF2F6FE4).copy(alpha = 0.38f), Color.Transparent),
                            center = Offset(size.width * 0.85f, 0f),
                            radius = size.width * 1.15f
                        )
                    )
                    // Halo azul tenue abajo a la izquierda.
                    drawRect(
                        Brush.radialGradient(
                            listOf(Color(0xFF1B4C9E).copy(alpha = 0.30f), Color.Transparent),
                            center = Offset(size.width * 0.05f, size.height * 0.95f),
                            radius = size.width * 1.05f
                        )
                    )
                }
        ) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = Color.Transparent,
                contentColor = MaterialTheme.colorScheme.onBackground
            ) {
                BackHandler(enabled = vm.screen != Screen.Home) { vm.back() }
                Column(Modifier.fillMaxSize().systemBarsPadding().imePadding()) {
                    Box(Modifier.weight(1f)) {
                        when (vm.screen) {
                            Screen.Home -> HomeScreen(vm)
                            Screen.Library -> LibraryScreen(vm)
                            Screen.Episodes -> EpisodesScreen(vm)
                            Screen.Player -> PlayerScreen(vm)
                            Screen.Queue -> QueueScreen(vm)
                            Screen.Resume -> ResumeScreen(vm)
                            Screen.Settings -> SettingsScreen(vm)
                        }
                    }
                    if (vm.screen != Screen.Player) MiniPlayer(vm)
                    if (vm.screen == Screen.Home || vm.screen == Screen.Library || vm.screen == Screen.Queue || vm.screen == Screen.Resume) BottomBar(vm)
                }
            }
        }
    }
}

@Composable
fun Glass(
    modifier: Modifier = Modifier,
    padding: Dp = 16.dp,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val shape = RoundedCornerShape(24.dp)
    var m = modifier
        .fillMaxWidth()
        .clip(shape)
        .background(
            Brush.verticalGradient(
                listOf(Color(0xFF12243D).copy(alpha = 0.82f), Color(0xFF0A1524).copy(alpha = 0.72f))
            )
        )
        .border(
            1.dp,
            Brush.verticalGradient(
                listOf(Color(0xFF7FB0FF).copy(alpha = 0.24f), Color.White.copy(alpha = 0.04f))
            ),
            shape
        )
    if (onClick != null) m = m.clickable(onClick = onClick)
    Column(m.padding(padding), content = content)
}

@Composable
fun Sub(text: String, maxLines: Int = 2, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier = modifier,
        fontSize = 14.sp,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        color = LocalContentColor.current.copy(alpha = 0.6f)
    )
}

@Composable
fun Cover(url: String, modifier: Modifier, radius: Dp = 14.dp) {
    AsyncImage(
        model = url,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = modifier
            .clip(RoundedCornerShape(radius))
            .background(Color.Gray.copy(alpha = 0.25f))
    )
}

@Composable
fun GradientButton(text: String, onClick: () -> Unit) {
    val shape = RoundedCornerShape(26.dp)
    Box(
        Modifier
            .fillMaxWidth()
            .height(52.dp)
            .clip(shape)
            .background(
                Brush.horizontalGradient(
                    listOf(Color(0xFF2E6BFF).copy(alpha = 0.30f), Color(0xFF4D8DFF).copy(alpha = 0.14f))
                )
            )
            .border(
                1.dp,
                Brush.horizontalGradient(
                    listOf(Color(0xFF8FB8FF).copy(alpha = 0.55f), Color(0xFF8FB8FF).copy(alpha = 0.12f))
                ),
                shape
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        // Reflejo superior tenue: da la sensación de vidrio.
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(Color.White.copy(alpha = 0.10f), Color.Transparent, Color.Transparent)
                    )
                )
        )
        Text(text, color = Color(0xFFDCE9FF), fontWeight = FontWeight.SemiBold)
    }
}

fun fmt(ms: Long): String {
    val t = ms / 1000
    val h = t / 3600
    val m = (t % 3600) / 60
    val s = t % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

@Composable
fun Chip(text: String) {
    Box(
        Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Accent)
            .padding(horizontal = 8.dp, vertical = 2.dp)
    ) {
        Text(text, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
    }
}

/** Botón secundario (vidrio) con ícono, para repartir en una fila. */
@Composable
fun SoftButton(text: String, icon: ImageVector, modifier: Modifier = Modifier, highlighted: Boolean = false, onClick: () -> Unit) {
    val shape = RoundedCornerShape(23.dp)
    val base = Brush.horizontalGradient(
        listOf(Color(0xFF2E6BFF).copy(alpha = 0.26f), Color(0xFF4D8DFF).copy(alpha = 0.12f))
    )
    Row(
        modifier
            .height(46.dp)
            .clip(shape)
            .then(
                if (highlighted) Modifier.background(Accent)
                else Modifier
                    .background(base)
                    .border(1.dp, Color(0xFF8FB8FF).copy(alpha = 0.30f), shape)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        val tint = if (highlighted) Color.White else BlueLight
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(text, color = tint, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Línea de estado de un episodio: "Escuchado" o "Seguís en 12:30" + barrita de avance. */
@Composable
fun ListenStatus(l: Listen?) {
    if (l == null) return
    if (l.played) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(14.dp), tint = Color(0xFF4FA3FF))
            Spacer(Modifier.width(4.dp))
            Sub("Escuchado", 1)
        }
    } else if (l.pos > 10_000) {
        Sub("Seguís en ${fmt(l.pos)}", 1)
        if (l.dur > 0) {
            Spacer(Modifier.height(6.dp))
            LinearProgressIndicator(
                progress = { (l.pos.toFloat() / l.dur).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().height(3.dp)
            )
        }
    }
}

@Composable
fun dimIf(played: Boolean): Color =
    if (played) LocalContentColor.current.copy(alpha = 0.55f) else LocalContentColor.current

// ---------- Pantalla 1: pegar link ----------

@Composable
fun HomeScreen(vm: AppViewModel) {
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column {
                    Text("Offcast", fontSize = 34.sp, fontWeight = FontWeight.Bold)
                    Sub("Pegá un link y escuchalo sin internet")
                }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { vm.goSettings() }) {
                    Icon(Icons.Filled.Settings, contentDescription = "Ajustes")
                }
            }
        }
        item {
            Glass(padding = 4.dp) {
                TextField(
                    value = vm.input,
                    onValueChange = { vm.input = it },
                    placeholder = { Text("Link de Spotify o YouTube, o el nombre") },
                    trailingIcon = {
                        if (vm.input.isNotEmpty()) {
                            IconButton(onClick = { vm.clearSearch() }) {
                                Icon(Icons.Filled.Close, contentDescription = "Borrar búsqueda")
                            }
                        }
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent
                    ),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { vm.submit() })
                )
            }
        }
        item { GradientButton("Buscar") { vm.submit() } }

        if (vm.loading) {
            item {
                Box(Modifier.fillMaxWidth().padding(8.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
        }
        vm.message?.let { msg ->
            item { Text(msg, color = MaterialTheme.colorScheme.error) }
        }

        if (vm.results.isNotEmpty()) {
            item { Text("¿Es alguno de estos?", fontSize = 20.sp, fontWeight = FontWeight.SemiBold) }
            items(vm.results) { p ->
                Glass(onClick = { vm.open(p) }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Cover(p.artwork, Modifier.size(56.dp))
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(p.name, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Sub(p.author, 1)
                        }
                    }
                }
            }
        }

        if (vm.follows.isNotEmpty()) {
            item { Text("Siguiendo", fontSize = 20.sp, fontWeight = FontWeight.SemiBold) }
            items(vm.follows, key = { "f" + it.feedUrl }) { f ->
                Glass(onClick = { vm.openFollow(f) }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Cover(f.artwork, Modifier.size(56.dp))
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(f.name, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            if (f.newIds.isNotEmpty()) {
                                Spacer(Modifier.height(4.dp))
                                Row {
                                    Chip(if (f.newIds.size == 1) "1 NUEVO" else "${f.newIds.size} NUEVOS")
                                }
                            } else {
                                Sub(f.author, 1)
                            }
                        }
                    }
                }
            }
        }
    }
}

// ---------- Pantalla: biblioteca ----------

@Composable
fun LibraryScreen(vm: AppViewModel) {
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Column {
                Text("Biblioteca", fontSize = 34.sp, fontWeight = FontWeight.Bold)
                Sub(
                    if (vm.library.isEmpty()) "Nada descargado todavía"
                    else "${vm.library.size} ${if (vm.library.size == 1) "episodio" else "episodios"} en tu teléfono"
                )
            }
        }
        Core.error?.let { err -> item { Text(err, color = MaterialTheme.colorScheme.error) } }
        if (vm.library.isEmpty()) {
            item { Sub("Lo que bajes queda acá y suena sin internet.") }
        }
        items(vm.library, key = { it.id }) { s ->
            val l = vm.listen[s.id]
            Glass(onClick = { vm.play(s) }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Cover(s.artwork, Modifier.size(56.dp))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            s.title,
                            color = dimIf(l?.played == true),
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Sub(s.podcast, 1)
                        ListenStatus(l)
                    }
                    IconButton(onClick = { vm.delete(s) }) {
                        Icon(Icons.Filled.Delete, contentDescription = "Borrar")
                    }
                }
            }
        }
    }
}

// ---------- Pantalla: cola de descargas ----------

@Composable
fun QueueScreen(vm: AppViewModel) {
    val jobs = Core.queuedJobs()
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Column {
                Text("Cola de descargas", fontSize = 34.sp, fontWeight = FontWeight.Bold)
                Sub("${jobs.size} ${if (jobs.size == 1) "episodio" else "episodios"} en cola")
            }
        }
        if (jobs.isEmpty()) {
            item { Sub("La cola está vacía.") }
        }
        items(jobs, key = { it.id }) { job ->
            val prog = Core.progress[job.id] ?: 0f
            val saved = vm.library.firstOrNull { it.id == job.id }
            Glass {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Cover(job.artwork, Modifier.size(56.dp), 14.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(job.title, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Sub(job.podcast, 1)
                        if (saved != null) {
                            Spacer(Modifier.height(4.dp))
                            Text("Listo", color = Color(0xFF4FA3FF), fontSize = 13.sp, fontWeight = FontWeight.Medium)
                        } else {
                            LinearProgressIndicator(
                                progress = { prog.coerceIn(0f, 1f) },
                                modifier = Modifier.fillMaxWidth().height(4.dp)
                            )
                            Sub("${(prog * 100).toInt()}%", 1)
                        }
                    }
                    if (saved == null) {
                        IconButton(onClick = { Core.removeJob(job.id) }) {
                            Icon(Icons.Filled.Close, contentDescription = "Cancelar")
                        }
                    }
                }
            }
        }
    }
}

// ---------- Pantalla: continuar escuchando ----------

@Composable
fun ResumeScreen(vm: AppViewModel) {
    val resumable = vm.library.filter { s ->
        val l = vm.listen[s.id]
        l != null && !l.played && l.pos > 10_000
    }.sortedByDescending { s -> vm.listen[s.id]?.pos ?: 0L }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Column {
                Text("Continuar escuchando", fontSize = 34.sp, fontWeight = FontWeight.Bold)
                Sub("${resumable.size} episodio sin terminar")
            }
        }
        if (resumable.isEmpty()) {
            item { Sub("No hay episodios en progreso.") }
        }
        items(resumable, key = { it.id }) { s ->
            val l = vm.listen[s.id]
            Glass(onClick = { vm.play(s) }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Cover(s.artwork, Modifier.size(56.dp), 14.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(s.title, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Sub(s.podcast, 1)
                        Spacer(Modifier.height(6.dp))
                        LinearProgressIndicator(
                            progress = { (l?.pos.toFloat() / (l?.dur ?: 1L).coerceAtLeast(1L)).coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth().height(4.dp)
                        )
                        Row(horizontalArrangement = Arrangement.SpaceBetween) {
                            Sub(fmt(l?.pos ?: 0L), 1)
                            Sub(fmt(l?.dur ?: 0L), 1)
                        }
                    }
                    IconButton(onClick = { vm.play(s) }) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = "Reproducir", tint = Color(0xFF4FA3FF))
                    }
                }
            }
        }
    }
}

// ---------- Pantalla: ajustes ----------

@Composable
fun SettingsScreen(vm: AppViewModel) {
    Column(
        Modifier.fillMaxSize().padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("Ajustes", fontSize = 34.sp, fontWeight = FontWeight.Bold)
        Sub("Configuración de descarga automática", 1)

        Glass {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Download, contentDescription = null, modifier = Modifier.size(20.dp), tint = BlueLight)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Auto-descargar episodios nuevos", fontWeight = FontWeight.SemiBold)
                    Sub("Al aparecer un episodio nuevo en un podcast que seguís, se baja solo. Solo Wi-Fi recomendado.", 1)
                }
                androidx.compose.material3.Switch(
                    checked = vm.autoDownload,
                    onCheckedChange = { vm.saveAutoDownload(it) },
                    colors = androidx.compose.material3.SwitchDefaults.colors(
                        checkedThumbColor = Color(0xFF6FA8FF),
                        checkedTrackColor = Color(0xFF3B7BFF)
                    )
                )
            }
        }

        Spacer(Modifier.height(20.dp))
        GradientButton("Volver") { vm.screen = Screen.Home }
    }
}

// ---------- Pantalla 2: episodios ----------

@Composable
fun EpisodesScreen(vm: AppViewModel) {
    val pod = vm.current ?: return
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Column {
                IconButton(onClick = { vm.back() }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Volver")
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Cover(pod.artwork, Modifier.size(96.dp), 20.dp)
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text(pod.name, fontSize = 22.sp, fontWeight = FontWeight.Bold, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        Sub(pod.author, 1)
                    }
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                val following = vm.isFollowing()
                SoftButton(
                    text = if (following) "Siguiendo" else "Seguir",
                    icon = if (following) Icons.Filled.Check else Icons.Filled.Add,
                    modifier = Modifier.weight(1f),
                    highlighted = !following
                ) { vm.toggleFollow() }
                SoftButton(
                    text = "Bajar varios",
                    icon = Icons.Filled.Download,
                    modifier = Modifier.weight(1f)
                ) { vm.bulkOpen = true }
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val labels = listOf("todos" to "Todos", "nuevos" to "Nuevos", "sin escuchar" to "Sin escuchar", "descargados" to "Descargados")
                labels.forEach { (key, label) ->
                    val selected = vm.epFilter == key
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(20.dp))
                            .then(if (selected) Modifier.background(Accent) else Modifier
                                .background(Color(0xFF12243D).copy(alpha = 0.75f))
                                .border(1.dp, Color(0xFF8FB8FF).copy(alpha = 0.22f), RoundedCornerShape(20.dp)))
                            .clickable { vm.epFilter = key }
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(label, color = if (selected) Color.White else BlueLight, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                    }
                }
            }
        }
        (vm.message ?: Core.error)?.let { msg ->
            item { Text(msg, color = MaterialTheme.colorScheme.error) }
        }
        // Filtro de episodios
        val filtered = when (vm.epFilter) {
            "nuevos" -> vm.episodes.filter { it.id in vm.newIds }
            "sin escuchar" -> vm.episodes.filter { e -> (vm.listen[e.id]?.played == false || vm.listen[e.id] == null) && e.id !in vm.library.map { it.id } }
            "descargados" -> vm.episodes.filter { e -> vm.library.any { lib -> lib.id == e.id } }
            else -> vm.episodes
        }
        items(filtered, key = { it.id }) { e ->
            val id = e.id
            val saved = vm.library.firstOrNull { it.id == id }
            val prog = vm.progress[id]
            val l = vm.listen[id]
            Glass {
                if (id in vm.newIds) {
                    Row { Chip("NUEVO") }
                    Spacer(Modifier.height(6.dp))
                }
                Text(
                    e.title,
                    color = dimIf(l?.played == true),
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
                val info = listOf(e.date, e.duration).filter { it.isNotEmpty() }.joinToString(" · ")
                if (info.isNotEmpty()) Sub(info, 1)
                ListenStatus(l)
                Spacer(Modifier.height(12.dp))
                if (saved != null) {
                    val resume = l != null && !l.played && l.pos > 10_000
                    GradientButton(if (resume) "Seguir escuchando" else "Reproducir") { vm.play(saved) }
                } else if (prog != null) {
                    if (prog > 0f) {
                        LinearProgressIndicator(progress = { prog }, modifier = Modifier.fillMaxWidth())
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                } else {
                    GradientButton("Descargar") { vm.download(e) }
                }
            }
        }
    }
    BulkDialog(vm)
}

@Composable
fun BulkDialog(vm: AppViewModel) {
    if (!vm.bulkOpen) return
    val options = vm.bulkOptions()
    AlertDialog(
        onDismissRequest = { vm.bulkOpen = false },
        title = { Text("Descargar varios") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (options.isEmpty()) {
                    Text("Ya descargaste todo lo que hay en este podcast.")
                }
                options.forEach { o ->
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .clickable {
                                vm.bulkOpen = false
                                vm.downloadMany(o.episodes)
                            }
                            .padding(horizontal = 12.dp, vertical = 10.dp)
                    ) {
                        Text(o.label, fontWeight = FontWeight.SemiBold)
                        Sub(o.detail, 1)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { vm.bulkOpen = false }) { Text("Cancelar") } }
    )
}

// ---------- Pantalla 3: reproductor ----------

@Composable
fun PlayerScreen(vm: AppViewModel) {
    val s = vm.playing
    var drag by remember { mutableStateOf<Float?>(null) }
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(Modifier.fillMaxWidth()) {
            IconButton(onClick = { vm.back() }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Volver")
            }
        }
        Spacer(Modifier.weight(1f))
        if (s != null) {
            Cover(s.artwork, Modifier.fillMaxWidth(0.8f).aspectRatio(1f), 32.dp)
            Spacer(Modifier.height(24.dp))
            Text(
                s.title,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
            Sub(s.podcast, 1)
            Spacer(Modifier.height(24.dp))
            Slider(
                value = drag ?: vm.position.toFloat(),
                onValueChange = { drag = it },
                onValueChangeFinished = {
                    drag?.let { vm.seekTo(it.toLong()) }
                    drag = null
                },
                valueRange = 0f..maxOf(vm.duration.toFloat(), 1f)
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Sub(fmt((drag ?: vm.position.toFloat()).toLong()), 1)
                Sub(fmt(vm.duration), 1)
            }
            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                IconButton(onClick = { vm.skip(-10000) }, modifier = Modifier.size(56.dp)) {
                    Icon(Icons.Filled.Replay10, contentDescription = "Retroceder 10 segundos", modifier = Modifier.size(34.dp))
                }
                Box(
                    Modifier
                        .size(76.dp)
                        .clip(CircleShape)
                        .background(Accent)
                        .clickable { vm.toggle() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        if (vm.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = if (vm.isPlaying) "Pausar" else "Reproducir",
                        tint = Color.White,
                        modifier = Modifier.size(40.dp)
                    )
                }
                IconButton(onClick = { vm.skip(30000) }, modifier = Modifier.size(56.dp)) {
                    Icon(Icons.Filled.Forward30, contentDescription = "Adelantar 30 segundos", modifier = Modifier.size(34.dp))
                }
            }
            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(1f to "1x", 1.25f to "1.25x", 1.5f to "1.5x", 2f to "2x").forEach { (value, label) ->
                    SpeedChip(label, selected = kotlin.math.abs(vm.speed - value) < 0.01f) { vm.changeSpeed(value) }
                }
            }
        }
        Spacer(Modifier.weight(1f))
    }
}

@Composable
fun SpeedChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(18.dp))
            .then(
                if (selected) Modifier.background(Accent)
                else Modifier
                    .background(Color(0xFF12243D).copy(alpha = 0.75f))
                    .border(1.dp, Color(0xFF8FB8FF).copy(alpha = 0.22f), RoundedCornerShape(18.dp))
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            color = if (selected) Color.White else BlueLight,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            fontSize = 14.sp
        )
    }
}

// ---------- Barra inferior ----------

@Composable
fun BottomBar(vm: AppViewModel) {
    val shape = RoundedCornerShape(28.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 10.dp)
            .clip(shape)
            .background(
                Brush.verticalGradient(
                    listOf(Color.White.copy(alpha = 0.10f), Color.White.copy(alpha = 0.03f))
                )
            )
            .border(
                1.dp,
                Brush.verticalGradient(
                    listOf(Color(0xFF8FB8FF).copy(alpha = 0.22f), Color.White.copy(alpha = 0.04f))
                ),
                shape
            )
            .padding(6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        NavItem("Inicio", Icons.Filled.Home, vm.screen == Screen.Home, Modifier.weight(1f)) { vm.goHome() }
        NavItem("Biblioteca", Icons.Filled.LibraryMusic, vm.screen == Screen.Library, Modifier.weight(1f)) { vm.goLibrary() }
        NavItem("Cola", ListIcon, vm.screen == Screen.Queue, Modifier.weight(1f)) { vm.goQueue() }
        NavItem("Continuar", Icons.Filled.PlayArrow, vm.screen == Screen.Resume, Modifier.weight(1f)) { vm.goResume() }
    }
}

@Composable
private fun NavItem(
    label: String,
    icon: ImageVector,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(22.dp)
    Row(
        modifier
            .height(46.dp)
            .clip(shape)
            .then(if (selected) Modifier.background(Accent) else Modifier)
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        val tint = if (selected) Color.White else NavIdle
        Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(
            label,
            color = tint,
            fontSize = 14.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            maxLines = 1
        )
    }
}

// ---------- Mini reproductor ----------

@Composable
fun MiniPlayer(vm: AppViewModel) {
    val s = vm.playing ?: return
    Glass(
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        padding = 12.dp,
        onClick = { vm.openPlayer() }
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Cover(s.artwork, Modifier.size(44.dp), 10.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(s.title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Sub(s.podcast, 1)
            }
            IconButton(onClick = { vm.toggle() }) {
                Icon(
                    if (vm.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (vm.isPlaying) "Pausar" else "Reproducir"
                )
            }
        }
    }
}
