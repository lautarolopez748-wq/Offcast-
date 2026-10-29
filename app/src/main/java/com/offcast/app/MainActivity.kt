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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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

    // "Compartir" un podcast de Spotify/YouTube directo a Offcast, o tocar el aviso de un episodio nuevo
    private fun handleShare(i: Intent?) {
        if (i == null) return
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

private val Accent = Brush.horizontalGradient(listOf(Color(0xFF7C5CFF), Color(0xFF4FA3FF)))

@Composable
fun OffcastApp(vm: AppViewModel) {
    val dark = isSystemInDarkTheme()
    val bg = if (dark) {
        listOf(Color(0xFF1B1035), Color(0xFF0E1A3D), Color(0xFF2A1240))
    } else {
        listOf(Color(0xFFEDE7FF), Color(0xFFE3F0FF), Color(0xFFFFE8F3))
    }
    MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
        Box(Modifier.fillMaxSize().background(Brush.linearGradient(bg))) {
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
                            Screen.Episodes -> EpisodesScreen(vm)
                            Screen.Player -> PlayerScreen(vm)
                        }
                    }
                    if (vm.screen != Screen.Player) MiniPlayer(vm)
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
    val dark = isSystemInDarkTheme()
    val shape = RoundedCornerShape(24.dp)
    var m = modifier
        .fillMaxWidth()
        .clip(shape)
        .background(if (dark) Color.White.copy(alpha = 0.09f) else Color.White.copy(alpha = 0.7f))
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
    Box(
        Modifier
            .fillMaxWidth()
            .height(50.dp)
            .clip(RoundedCornerShape(25.dp))
            .background(Accent)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(text, color = Color.White, fontWeight = FontWeight.SemiBold)
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
    val dark = isSystemInDarkTheme()
    val base = if (dark) Color.White.copy(alpha = 0.12f) else Color.White.copy(alpha = 0.75f)
    Row(
        modifier
            .height(46.dp)
            .clip(RoundedCornerShape(23.dp))
            .then(if (highlighted) Modifier.background(Accent) else Modifier.background(base))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        val tint = if (highlighted) Color.White else LocalContentColor.current
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
            Column {
                Text("Offcast", fontSize = 34.sp, fontWeight = FontWeight.Bold)
                Sub("Pegá un link y escuchalo sin internet")
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

        item { Text("Descargados", fontSize = 20.sp, fontWeight = FontWeight.SemiBold) }
        if (vm.library.isEmpty()) {
            item { Sub("Todavía no descargaste nada. Lo que bajes queda en tu teléfono.") }
        }
        Core.error?.let { err -> item { Text(err, color = MaterialTheme.colorScheme.error) } }
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
        (vm.message ?: Core.error)?.let { msg ->
            item { Text(msg, color = MaterialTheme.colorScheme.error) }
        }
        items(vm.episodes, key = { it.id }) { e ->
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
    val dark = isSystemInDarkTheme()
    Box(
        Modifier
            .clip(RoundedCornerShape(18.dp))
            .then(
                if (selected) Modifier.background(Accent)
                else Modifier.background(if (dark) Color.White.copy(alpha = 0.12f) else Color.White.copy(alpha = 0.75f))
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            color = if (selected) Color.White else LocalContentColor.current,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            fontSize = 14.sp
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
