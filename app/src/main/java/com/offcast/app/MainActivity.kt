package com.offcast.app

import android.Manifest
import android.app.Activity
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
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Podcasts
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.SideEffect
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
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

// ---------- Estructura ----------

private val ContentMaxWidth: Dp = 600.dp

@Composable
fun OffcastApp(vm: AppViewModel) {
    val sun = vm.sunMode
    val view = LocalView.current
    SideEffect {
        val window = (view.context as? Activity)?.window ?: return@SideEffect
        val bars = WindowCompat.getInsetsController(window, view)
        bars.isAppearanceLightStatusBars = sun
        bars.isAppearanceLightNavigationBars = sun
    }
    OffcastTheme(sunMode = sun) {
        val c = Offcast.colors
        var showRestore by remember { mutableStateOf(Core.restoreAvailable) }
        val ctx = androidx.compose.ui.platform.LocalContext.current
        Box(
            Modifier
                .fillMaxSize()
                .drawBehind {
                    drawRect(
                        Brush.verticalGradient(
                            listOf(
                                c.backgroundTop,
                                c.backgroundMid,
                                c.backgroundBottom,
                                c.backgroundTop
                            )
                        )
                    )
                    // Halo azul difuminado arriba a la derecha.
                    drawRect(
                        Brush.radialGradient(
                            listOf(c.haloTop, Color.Transparent),
                            center = Offset(size.width * 0.85f, 0f),
                            radius = size.width * 1.15f
                        )
                    )
                    // Halo azul tenue abajo a la izquierda.
                    drawRect(
                        Brush.radialGradient(
                            listOf(c.haloBottom, Color.Transparent),
                            center = Offset(size.width * 0.05f, size.height * 0.95f),
                            radius = size.width * 1.05f
                        )
                    )
                }
        ) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = Color.Transparent,
                contentColor = c.text
            ) {
                BackHandler(enabled = vm.screen != Screen.Home) { vm.back() }
                Column(Modifier.fillMaxSize().systemBarsPadding().imePadding()) {
                    Box(Modifier.weight(1f), contentAlignment = Alignment.TopCenter) {
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
                    if (vm.screen != Screen.Player && vm.playing != null && !vm.miniHidden) {
                        MiniPlayer(vm)
                    }
                    if (vm.screen == Screen.Home || vm.screen == Screen.Library || vm.screen == Screen.Queue) {
                        BottomBar(vm)
                    }
                }
                if (showRestore) {
                    AlertDialog(
                        onDismissRequest = { showRestore = false; Core.restoreAvailable = false },
                        title = { Text("Respaldo encontrado", style = Offcast.type.itemTitle) },
                        text = { Text("Hay un respaldo de Offcast en Descargas. ¿Restaurarlo para continuar tu sesión?", style = Offcast.type.body) },
                        confirmButton = {
                            TextButton(onClick = {
                                val ok = Backup.restore(ctx)
                                if (ok) {
                                    vm.loadPrefs()
                                    vm.screen = Screen.Home
                                }
                                showRestore = false
                                Core.restoreAvailable = false
                            }, modifier = Modifier.heightIn(min = 48.dp)) {
                                Text("Restaurar")
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { showRestore = false; Core.restoreAvailable = false }, modifier = Modifier.heightIn(min = 48.dp)) {
                                Text("Ahora no")
                            }
                        }
                    )
                }
            }
        }
    }
}

/** Ancho de lectura cómodo en tablet: el contenido se centra y no se estira de lado a lado. */
private fun Modifier.screenWidth(): Modifier =
    this.widthIn(max = ContentMaxWidth).fillMaxWidth().fillMaxHeight()

// ---------- Componentes base ----------

@Composable
fun Glass(
    modifier: Modifier = Modifier,
    padding: Dp = Space.l,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val c = Offcast.colors
    val shape = RoundedCornerShape(Radius.l)
    var m = modifier
        .fillMaxWidth()
        .clip(shape)
        .background(Brush.verticalGradient(listOf(c.surfaceTop, c.surfaceBottom)))
        .border(
            1.dp,
            Brush.verticalGradient(listOf(c.borderTop, c.borderBottom)),
            shape
        )
    if (onClick != null) {
        m = m.clickable(role = Role.Button, onClick = onClick)
    }
    Column(m.padding(padding), content = content)
}

@Composable
fun Sub(
    text: String,
    maxLines: Int = 2,
    modifier: Modifier = Modifier,
    textAlign: TextAlign? = null
) {
    Text(
        text,
        modifier = modifier,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        textAlign = textAlign,
        style = Offcast.type.caption,
        color = Offcast.colors.textMuted
    )
}

@Composable
fun Cover(url: String, modifier: Modifier, radius: Dp = Radius.m) {
    AsyncImage(
        model = url,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = modifier
            .clip(RoundedCornerShape(radius))
            .background(Offcast.colors.surfaceFlat)
    )
}

@Composable
fun PrimaryButton(
    text: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
    onClick: () -> Unit
) {
    val c = Offcast.colors
    val shape = RoundedCornerShape(Radius.l)
    val active = enabled && !loading
    Box(
        modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .clip(shape)
            .then(
                if (active) Modifier.background(accentBrush())
                else Modifier
                    .background(c.surfaceFlat)
                    .border(1.dp, c.borderTop, shape)
            )
            .clickable(enabled = active, role = Role.Button, onClick = onClick)
            .padding(horizontal = Space.l),
        contentAlignment = Alignment.Center
    ) {
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                strokeWidth = 2.dp,
                color = c.onAccent
            )
        } else {
            Text(
                text,
                style = Offcast.type.body,
                fontWeight = FontWeight.SemiBold,
                color = if (active) c.onAccent else c.textMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/** Botón secundario (vidrio) con ícono, para repartir en una fila. */
@Composable
fun SoftButton(
    text: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    highlighted: Boolean = false,
    onClick: () -> Unit
) {
    val c = Offcast.colors
    val shape = RoundedCornerShape(Radius.l)
    Row(
        modifier
            .heightIn(min = 48.dp)
            .clip(shape)
            .then(
                if (highlighted) Modifier.background(accentBrush())
                else Modifier
                    .background(c.surfaceTop)
                    .border(1.dp, c.borderTop, shape)
            )
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = Space.m),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        val tint = if (highlighted) c.onAccent else c.accentSoft
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(Space.s))
        Text(
            text,
            style = Offcast.type.caption,
            fontWeight = FontWeight.SemiBold,
            color = tint,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
fun Chip(text: String) {
    val shape = RoundedCornerShape(Radius.s)
    Box(
        Modifier
            .clip(shape)
            .background(accentBrush())
            .padding(horizontal = Space.s, vertical = 2.dp)
    ) {
        Text(text, color = Offcast.colors.onAccent, style = Offcast.type.chip)
    }
}

fun fmt(ms: Long): String {
    val t = ms / 1000
    val h = t / 3600
    val m = (t % 3600) / 60
    val s = t % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

/** Línea de estado de un episodio: "Escuchado" o "Seguís en 12:30" + barrita de avance. */
@Composable
fun ListenStatus(l: Listen?) {
    if (l == null) return
    val c = Offcast.colors
    if (l.played) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(14.dp), tint = c.success)
            Spacer(Modifier.width(Space.xs))
            Sub("Escuchado", 1)
        }
    } else if (l.pos > 10_000) {
        Sub("Seguís en ${fmt(l.pos)}", 1)
        if (l.dur > 0) {
            Spacer(Modifier.height(6.dp))
            ProgressBar((l.pos.toFloat() / l.dur).coerceIn(0f, 1f))
        }
    }
}

@Composable
fun ScreenTitle(
    title: String,
    subtitle: String? = null,
    trailing: @Composable (() -> Unit)? = null
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = Offcast.type.pageTitle, color = Offcast.colors.text)
            if (subtitle != null) {
                Spacer(Modifier.height(Space.xs))
                Sub(subtitle)
            }
        }
        trailing?.invoke()
    }
}

@Composable
fun SectionHeader(
    title: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            title,
            style = Offcast.type.sectionTitle,
            color = Offcast.colors.text,
            modifier = Modifier.weight(1f)
        )
        if (actionLabel != null && onAction != null) {
            TextButton(onClick = onAction, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(actionLabel, style = Offcast.type.caption, color = Offcast.colors.accentSoft)
            }
        }
    }
}

@Composable
fun BackBar(
    onBack: () -> Unit,
    actions: @Composable RowScope.() -> Unit = {}
) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = Space.xs),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Volver")
        }
        Spacer(Modifier.weight(1f))
        actions()
    }
}

@Composable
fun ErrorBanner(
    text: String,
    onDismiss: () -> Unit,
    onRetry: (() -> Unit)? = null
) {
    val c = Offcast.colors
    Glass(padding = Space.m) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Filled.Warning,
                contentDescription = null,
                tint = c.danger,
                modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.width(Space.m))
            Text(
                text,
                style = Offcast.type.caption,
                color = c.text,
                modifier = Modifier.weight(1f),
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
            if (onRetry != null) {
                TextButton(onClick = onRetry, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text("Reintentar", style = Offcast.type.caption, color = c.accentSoft)
                }
            }
            IconButton(onClick = onDismiss) {
                Icon(Icons.Filled.Close, contentDescription = "Descartar error", tint = c.textMuted)
            }
        }
    }
}

@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    body: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null
) {
    val c = Offcast.colors
    Glass {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(c.accentSoft.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = null, tint = c.accentSoft, modifier = Modifier.size(26.dp))
            }
            Spacer(Modifier.height(Space.m))
            Text(title, style = Offcast.type.itemTitle, color = c.text, textAlign = TextAlign.Center)
            Spacer(Modifier.height(Space.xs))
            Sub(body, 3, textAlign = TextAlign.Center)
            if (actionLabel != null && onAction != null) {
                Spacer(Modifier.height(Space.l))
                PrimaryButton(actionLabel, onClick = onAction)
            }
        }
    }
}

@Composable
fun SkeletonCard() {
    val c = Offcast.colors
    Glass {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(Radius.m))
                    .background(c.surfaceFlat)
            )
            Spacer(Modifier.width(Space.m))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.s)) {
                Box(Modifier.fillMaxWidth(0.65f).height(14.dp).clip(RoundedCornerShape(Radius.s)).background(c.surfaceFlat))
                Box(Modifier.fillMaxWidth(0.4f).height(10.dp).clip(RoundedCornerShape(Radius.s)).background(c.surfaceFlat))
            }
        }
    }
}

/**
 * Fila de episodio reutilizable. Si [onClick] no es null, toda la fila es un solo target.
 * [trailing] debe ser decorativo en ese caso: no anidar botones dentro de una fila clickeable.
 */
@Composable
fun EpisodeRow(
    title: String,
    subtitle: String,
    artwork: String,
    meta: String? = null,
    listen: Listen? = null,
    dimmed: Boolean = false,
    progress: Float? = null,
    trailing: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null
) {
    val c = Offcast.colors
    Glass(padding = 0.dp, onClick = onClick) {
        Row(
            Modifier.fillMaxWidth().padding(Space.l),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Cover(artwork, Modifier.size(56.dp), Radius.m)
            Spacer(Modifier.width(Space.m))
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = Offcast.type.itemTitle,
                    color = if (dimmed) c.textMuted else c.text,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Sub(subtitle, 1)
                if (meta != null) Sub(meta, 1)
                ListenStatus(listen)
                if (progress != null) {
                    Spacer(Modifier.height(6.dp))
                    ProgressBar(progress.coerceIn(0f, 1f))
                }
            }
            trailing?.invoke()
        }
    }
}

@Composable
fun PodcastRow(name: String, author: String, artwork: String, onClick: () -> Unit) {
    Glass(padding = 0.dp, onClick = onClick) {
        Row(Modifier.fillMaxWidth().padding(Space.l), verticalAlignment = Alignment.CenterVertically) {
            Cover(artwork, Modifier.size(56.dp), Radius.m)
            Spacer(Modifier.width(Space.m))
            Column(Modifier.weight(1f)) {
                Text(
                    name,
                    style = Offcast.type.itemTitle,
                    color = Offcast.colors.text,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Sub(author, 1)
            }
        }
    }
}

// ---------- Pantalla 1: pegar link ----------

@Composable
fun HomeScreen(vm: AppViewModel) {
    val c = Offcast.colors
    val clipboard = LocalClipboardManager.current
    val filters = listOf("todo" to "Todo", "nuevos" to "Nuevos", "en progreso" to "En progreso")

    val resumable by remember { derivedStateOf {
        vm.library.filter { s ->
            val l = vm.listen[s.id]
            l != null && !l.played && l.pos > 10_000
        }.sortedByDescending { s -> vm.listen[s.id]?.pos ?: 0L }
            .take(3)
    } }

    LazyColumn(
        Modifier.screenWidth(),
        contentPadding = PaddingValues(Space.xl),
        verticalArrangement = Arrangement.spacedBy(Space.m)
    ) {
        item {
            ScreenTitle(
                "Offcast",
                "Pegá un link y escuchalo sin internet",
                trailing = {
                    IconButton(onClick = { vm.goSettings() }) {
                        Icon(Icons.Filled.Settings, contentDescription = "Ajustes")
                    }
                }
            )
        }
        item {
            Glass(padding = Space.xs) {
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
                    textStyle = Offcast.type.body,
                    modifier = Modifier.fillMaxWidth(),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        focusedTextColor = c.text,
                        unfocusedTextColor = c.text,
                        cursorColor = c.accentSoft,
                        focusedPlaceholderColor = c.textMuted,
                        unfocusedPlaceholderColor = c.textMuted
                    ),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { vm.submit() })
                )
            }
        }
        item {
            PrimaryButton(
                "Buscar",
                enabled = vm.input.isNotBlank(),
                loading = vm.loading,
                onClick = { vm.submit() }
            )
        }
        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                items(filters, key = { it.first }) { (key, label) ->
                    FilterChip(label, selected = vm.homeFilter == key) { vm.homeFilter = key }
                }
            }
        }

        vm.message?.let { msg ->
            item {
                ErrorBanner(
                    msg,
                    onDismiss = { vm.message = null },
                    onRetry = if (vm.input.isNotBlank()) ({ vm.submit() }) else null
                )
            }
        }

        if (vm.loading && vm.results.isEmpty()) {
            items(3) { SkeletonCard() }
        }

        if (vm.results.isNotEmpty()) {
            item { SectionHeader("¿Es alguno de estos?") }
            items(vm.results) { p ->
                PodcastRow(p.name, p.author, p.artwork) { vm.open(p) }
            }
        }

        if (vm.follows.isNotEmpty()) {
            item { SectionHeader("Siguiendo") }
            items(vm.follows, key = { "f" + it.feedUrl }) { f ->
                Glass(padding = 0.dp, onClick = { vm.openFollow(f) }) {
                    Row(
                        Modifier.fillMaxWidth().padding(Space.l),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Cover(f.artwork, Modifier.size(56.dp), Radius.m)
                        Spacer(Modifier.width(Space.m))
                        Column(Modifier.weight(1f)) {
                            Text(
                                f.name,
                                style = Offcast.type.itemTitle,
                                color = c.text,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (f.newIds.isNotEmpty()) {
                                Spacer(Modifier.height(Space.xs))
                                Row { Chip(if (f.newIds.size == 1) "1 NUEVO" else "${f.newIds.size} NUEVOS") }
                            } else {
                                Sub(f.author, 1)
                            }
                        }
                    }
                }
            }
        }

        // ---------- Sugerencias (paso 1: seguir escuchando) ----------
        if (resumable.isNotEmpty() && (vm.homeFilter == "todo" || vm.homeFilter == "en progreso")) {
            item {
                SectionHeader("Seguí escuchando", "Ver todo") { vm.goResume() }
            }
            items(resumable, key = { "sug" + it.id }) { s ->
                SuggestionCard(vm, s)
            }
        }
        if (vm.newEpisodes.isNotEmpty() && (vm.homeFilter == "todo" || vm.homeFilter == "nuevos")) {
            item { SectionHeader("Nuevos de tus podcasts") }
            items(vm.newEpisodes, key = { "new" + it.episode.id }) { se ->
                SuggestionCard(vm, se.episode, se.follow)
            }
        }

        if (vm.moreEpisodes.isNotEmpty() && vm.homeFilter == "todo") {
            item { SectionHeader("Más de lo que te gusta") }
            items(vm.moreEpisodes, key = { "more" + it.episode.id }) { se ->
                SuggestionCard(vm, se.episode, se.follow)
            }
        }

        if (vm.discoverPodcasts.isNotEmpty() && vm.homeFilter == "todo") {
            item { SectionHeader("Descubrir") }
            items(vm.discoverPodcasts, key = { "disc" + it.feedUrl }) { p ->
                PodcastRow(p.name, p.author, p.artwork) { vm.open(p) }
            }
        }

        val empty = vm.library.isEmpty() && vm.follows.isEmpty() && vm.results.isEmpty() &&
            !vm.loading && vm.newEpisodes.isEmpty() && vm.discoverPodcasts.isEmpty()
        if (empty) {
            item {
                EmptyState(
                    icon = Icons.Filled.Podcasts,
                    title = "Empezá con un link",
                    body = "Pegá un link de Spotify, YouTube o un feed RSS. También podés buscar por nombre.",
                    actionLabel = "Pegar del portapapeles",
                    onAction = {
                        val text = clipboard.getText()?.text?.trim()
                        if (text.isNullOrEmpty()) {
                            vm.message = "El portapapeles está vacío."
                        } else {
                            vm.submit(text)
                        }
                    }
                )
            }
        }
    }
}

@Composable
fun SuggestionCard(vm: AppViewModel, saved: Saved) {
    val l = vm.listen[saved.id]
    EpisodeRow(
        title = saved.title,
        subtitle = saved.podcast,
        artwork = saved.artwork,
        meta = if ((l?.dur ?: 0L) > 0L) fmt(l!!.dur) else null,
        listen = l,
        dimmed = l?.played == true,
        trailing = {
            Icon(
                Icons.Filled.PlayArrow,
                contentDescription = null,
                tint = Offcast.colors.accentSoft,
                modifier = Modifier.size(26.dp)
            )
        },
        onClick = { vm.play(saved) }
    )
}

@Composable
fun SuggestionCard(vm: AppViewModel, episode: Episode, follow: Follow) {
    val saved = vm.library.firstOrNull { it.id == episode.id }
    val l = vm.listen[episode.id]
    val action: () -> Unit = {
        if (saved != null) {
            vm.play(saved)
        } else {
            Core.enqueue(
                listOf(DownloadJob(episode.id, episode.audioUrl, episode.title, follow.name, follow.artwork))
            )
            Unit
        }
    }
    EpisodeRow(
        title = episode.title,
        subtitle = follow.name,
        artwork = follow.artwork,
        meta = episode.duration.ifEmpty { null },
        listen = l,
        dimmed = l?.played == true,
        trailing = {
            Icon(
                if (saved != null) Icons.Filled.PlayArrow else Icons.Filled.Download,
                contentDescription = null,
                tint = Offcast.colors.accentSoft,
                modifier = Modifier.size(24.dp)
            )
        },
        onClick = action
    )
}

// ---------- Pantalla: biblioteca ----------

@Composable
fun LibraryScreen(vm: AppViewModel) {
    val c = Offcast.colors
    var pendingDelete by remember { mutableStateOf<Saved?>(null) }

    LazyColumn(
        Modifier.screenWidth(),
        contentPadding = PaddingValues(start = Space.xl, end = Space.xl, top = Space.xl, bottom = Space.s),
        verticalArrangement = Arrangement.spacedBy(Space.m)
    ) {
        item {
            ScreenTitle(
                "Biblioteca",
                if (vm.library.isEmpty()) "Nada descargado todavía"
                else "${vm.library.size} ${if (vm.library.size == 1) "episodio" else "episodios"} en tu teléfono"
            )
        }
        Core.error?.let { err ->
            item {
                ErrorBanner(
                    err,
                    onDismiss = { Core.clearError() },
                    onRetry = if (Core.lastErrorJob != null) ({ Core.retryLastError() }) else null
                )
            }
        }
        if (vm.library.isEmpty()) {
            item {
                EmptyState(
                    icon = Icons.Filled.LibraryMusic,
                    title = "Tu biblioteca está vacía",
                    body = "Lo que bajes queda acá y suena sin internet.",
                    actionLabel = "Buscar podcasts",
                    onAction = { vm.goHome() }
                )
            }
        }
        items(vm.library, key = { it.id }) { s ->
            val l = vm.listen[s.id]
            Glass(padding = 0.dp) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    // Toda la zona de datos reproduce; borrar es un target separado.
                    Row(
                        Modifier
                            .weight(1f)
                            .clickable(role = Role.Button) { vm.play(s) }
                            .padding(Space.l),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Cover(s.artwork, Modifier.size(56.dp), Radius.m)
                        Spacer(Modifier.width(Space.m))
                        Column(Modifier.weight(1f)) {
                            Text(
                                s.title,
                                style = Offcast.type.itemTitle,
                                color = if (l?.played == true) c.textMuted else c.text,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                            Sub(s.podcast, 1)
                            ListenStatus(l)
                        }
                    }
                    IconButton(
                        onClick = { pendingDelete = s },
                        modifier = Modifier.padding(end = Space.s)
                    ) {
                        Icon(Icons.Filled.Delete, contentDescription = "Borrar ${s.title}", tint = c.textMuted)
                    }
                }
            }
        }
    }

    pendingDelete?.let { s ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Borrar descarga", style = Offcast.type.itemTitle) },
            text = { Text("Se borra \"${s.title}\" del teléfono. Podés volver a bajarlo cuando quieras.", style = Offcast.type.body) },
            confirmButton = {
                TextButton(onClick = {
                    vm.delete(s)
                    pendingDelete = null
                }) { Text("Borrar", color = c.danger) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("Cancelar") }
            }
        )
    }
}

// ---------- Pantalla: cola de descargas ----------

@Composable
fun QueueScreen(vm: AppViewModel) {
    val jobs = Core.queuedJobs()
    LazyColumn(
        Modifier.screenWidth(),
        contentPadding = PaddingValues(Space.xl),
        verticalArrangement = Arrangement.spacedBy(Space.m)
    ) {
        item {
            ScreenTitle(
                "Cola de descargas",
                "${jobs.size} ${if (jobs.size == 1) "episodio" else "episodios"} en cola"
            )
        }
        Core.error?.let { err ->
            item {
                ErrorBanner(
                    err,
                    onDismiss = { Core.clearError() },
                    onRetry = if (Core.lastErrorJob != null) ({ Core.retryLastError() }) else null
                )
            }
        }
        if (jobs.isEmpty()) {
            item {
                EmptyState(
                    icon = Icons.Filled.QueueMusic,
                    title = "No hay descargas en curso",
                    body = "Cuando bajes episodios vas a ver el avance acá.",
                    actionLabel = "Ver biblioteca",
                    onAction = { vm.goLibrary() }
                )
            }
        }
        items(jobs, key = { it.id }) { job ->
            val prog = Core.progress[job.id] ?: 0f
            val saved = vm.library.firstOrNull { it.id == job.id }
            EpisodeRow(
                title = job.title,
                subtitle = job.podcast,
                artwork = job.artwork,
                meta = if (saved != null) "Listo" else "${(prog * 100).toInt()}%",
                progress = if (saved == null) prog else null,
                trailing = if (saved == null) {
                    {
                        IconButton(onClick = { Core.removeJob(job.id) }) {
                            Icon(Icons.Filled.Close, contentDescription = "Cancelar ${job.title}")
                        }
                    }
                } else null
            )
        }
    }
}

// ---------- Pantalla: continuar escuchando ----------

@Composable
fun ResumeScreen(vm: AppViewModel) {
    val resumable by remember { derivedStateOf {
        vm.library.filter { s ->
            val l = vm.listen[s.id]
            l != null && !l.played && l.pos > 10_000
        }.sortedByDescending { s -> vm.listen[s.id]?.pos ?: 0L }
    } }

    Column(Modifier.screenWidth()) {
        BackBar(onBack = { vm.back() })
        LazyColumn(
            Modifier.weight(1f),
            contentPadding = PaddingValues(start = Space.xl, end = Space.xl, bottom = Space.xl),
            verticalArrangement = Arrangement.spacedBy(Space.m)
        ) {
            item {
                ScreenTitle(
                    "Continuar escuchando",
                    "${resumable.size} ${if (resumable.size == 1) "episodio sin terminar" else "episodios sin terminar"}"
                )
            }
            if (resumable.isEmpty()) {
                item {
                    EmptyState(
                        icon = Icons.Filled.PlayArrow,
                        title = "Nada en progreso",
                        body = "Los episodios que empieces y no termines aparecen acá.",
                        actionLabel = "Ver biblioteca",
                        onAction = { vm.goLibrary() }
                    )
                }
            }
            items(resumable, key = { it.id }) { s ->
                val l = vm.listen[s.id]
                EpisodeRow(
                    title = s.title,
                    subtitle = s.podcast,
                    artwork = s.artwork,
                    meta = if (l != null) "${fmt(l.pos)} de ${fmt(l.dur)}" else null,
                    listen = l,
                    trailing = {
                        Icon(
                            Icons.Filled.PlayArrow,
                            contentDescription = null,
                            tint = Offcast.colors.accentSoft,
                            modifier = Modifier.size(26.dp)
                        )
                    },
                    onClick = { vm.play(s) }
                )
            }
        }
    }
}

// ---------- Pantalla: ajustes ----------

@Composable
fun SettingsScreen(vm: AppViewModel) {
    val c = Offcast.colors
    Column(Modifier.screenWidth()) {
        BackBar(onBack = { vm.goHome() })
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(start = Space.xl, end = Space.xl, bottom = Space.xl),
            verticalArrangement = Arrangement.spacedBy(Space.m)
        ) {
            ScreenTitle("Ajustes", "Cómo se descarga y cómo se ve la app")

            Glass {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Download, contentDescription = null, modifier = Modifier.size(20.dp), tint = c.accentSoft)
                    Spacer(Modifier.width(Space.m))
                    Column(Modifier.weight(1f)) {
                        Text("Auto-descargar episodios nuevos", style = Offcast.type.itemTitle, color = c.text)
                        Sub("Baja solo cada episodio nuevo que aparece. Conviene hacerlo con Wi-Fi.", 3)
                    }
                    androidx.compose.material3.Switch(
                        checked = vm.autoDownload,
                        onCheckedChange = { vm.saveAutoDownload(it) },
                        colors = androidx.compose.material3.SwitchDefaults.colors(
                            checkedThumbColor = c.onAccent,
                            checkedTrackColor = c.accentStart
                        )
                    )
                }
            }

            Glass {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Settings, contentDescription = null, modifier = Modifier.size(20.dp), tint = c.accentSoft)
                    Spacer(Modifier.width(Space.m))
                    Column(Modifier.weight(1f)) {
                        Text("Usar datos móviles", style = Offcast.type.itemTitle, color = c.text)
                        Sub("Permite descargar con saldo. Si está apagado, solo se baja con Wi-Fi.", 3)
                    }
                    androidx.compose.material3.Switch(
                        checked = vm.downloadOnMobile,
                        onCheckedChange = { vm.saveDownloadOnMobile(it) },
                        colors = androidx.compose.material3.SwitchDefaults.colors(
                            checkedThumbColor = c.onAccent,
                            checkedTrackColor = c.accentStart
                        )
                    )
                }
            }
            Glass {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Settings, contentDescription = null, modifier = Modifier.size(20.dp), tint = c.accentSoft)
                    Spacer(Modifier.width(Space.m))
                    Column(Modifier.weight(1f)) {
                        Text("Tema claro", style = Offcast.type.itemTitle, color = c.text)
                        Sub("Fondo claro y azules suaves. Se guarda para la próxima vez.", 3)
                    }
                    androidx.compose.material3.Switch(
                        checked = vm.sunMode,
                        onCheckedChange = { vm.saveSunMode(it) },
                        colors = androidx.compose.material3.SwitchDefaults.colors(
                            checkedThumbColor = c.onAccent,
                            checkedTrackColor = c.accentStart
                        )
                    )
                }
            }

            Glass {
                Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
                    Text("Respaldo", style = Offcast.type.itemTitle, color = c.text)
                    Sub("Guarda un respaldo en Descargas/Offcast para no perder tu biblioteca.", 3)
                    val ctx = androidx.compose.ui.platform.LocalContext.current
                    Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                        SoftButton(
                            text = "Exportar",
                            icon = Icons.Filled.Download,
                            modifier = Modifier.weight(1f)
                        ) {
                            val ok = Backup.export(ctx)
                            android.widget.Toast.makeText(
                                ctx,
                                if (ok) "Respaldo guardado en Descargas/Offcast" else "No se pudo exportar",
                                android.widget.Toast.LENGTH_LONG
                            ).show()
                        }
                        SoftButton(
                            text = "Importar",
                            icon = Icons.Filled.Add,
                            modifier = Modifier.weight(1f)
                        ) {
                            val ok = Backup.restore(ctx)
                            if (ok) {
                                vm.loadPrefs()
                                vm.screen = Screen.Home
                            }
                            android.widget.Toast.makeText(
                                ctx,
                                if (ok) "Respaldo restaurado" else "No se encontró respaldo",
                                android.widget.Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                }
            }
            Glass {
                Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
                    Text("Resumen con IA (opcional)", style = Offcast.type.itemTitle, color = c.text)
                    Sub("Usa una API compatible con OpenAI. La key se guarda en el teléfono.", 3)
                    TextField(
                        value = vm.aiApiKey,
                        onValueChange = { vm.saveAiSettings(it, vm.aiEndpoint, vm.aiModel) },
                        placeholder = { Text("API key") },
                        singleLine = true,
                        textStyle = Offcast.type.body,
                        modifier = Modifier.fillMaxWidth(),
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                            focusedTextColor = c.text,
                            unfocusedTextColor = c.text,
                            cursorColor = c.accentSoft,
                            focusedPlaceholderColor = c.textMuted,
                            unfocusedPlaceholderColor = c.textMuted
                        )
                    )
                    TextField(
                        value = vm.aiEndpoint,
                        onValueChange = { vm.saveAiSettings(vm.aiApiKey, it, vm.aiModel) },
                        placeholder = { Text("Endpoint (opcional)") },
                        singleLine = true,
                        textStyle = Offcast.type.body,
                        modifier = Modifier.fillMaxWidth(),
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                            focusedTextColor = c.text,
                            unfocusedTextColor = c.text,
                            cursorColor = c.accentSoft,
                            focusedPlaceholderColor = c.textMuted,
                            unfocusedPlaceholderColor = c.textMuted
                        )
                    )
                    TextField(
                        value = vm.aiModel,
                        onValueChange = { vm.saveAiSettings(vm.aiApiKey, vm.aiEndpoint, it) },
                        placeholder = { Text("Modelo (opcional, ej. gpt-4o-mini)") },
                        singleLine = true,
                        textStyle = Offcast.type.body,
                        modifier = Modifier.fillMaxWidth(),
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                            focusedTextColor = c.text,
                            unfocusedTextColor = c.text,
                            cursorColor = c.accentSoft,
                            focusedPlaceholderColor = c.textMuted,
                            unfocusedPlaceholderColor = c.textMuted
                        )
                    )
                }
            }
        }
    }
}

// ---------- Pantalla 2: episodios ----------

@Composable
fun EpisodesScreen(vm: AppViewModel) {
    val c = Offcast.colors
    val pod = vm.current ?: return
    val filters = listOf(
        "todos" to "Todos",
        "nuevos" to "Nuevos",
        "sin escuchar" to "Sin escuchar",
        "descargados" to "Descargados"
    )

    val filtered by remember { derivedStateOf {
        when (vm.epFilter) {
            "nuevos" -> vm.episodes.filter { it.id in vm.newIds }
            "sin escuchar" -> vm.episodes.filter { e ->
                (vm.listen[e.id]?.played == false || vm.listen[e.id] == null) &&
                    e.id !in vm.library.map { it.id }
            }
            "descargados" -> vm.episodes.filter { e -> vm.library.any { lib -> lib.id == e.id } }
            else -> vm.episodes
        }
    } }

    Column(Modifier.screenWidth()) {
        BackBar(onBack = { vm.back() })
        LazyColumn(
            Modifier.weight(1f),
            contentPadding = PaddingValues(start = Space.xl, end = Space.xl, bottom = Space.xl),
            verticalArrangement = Arrangement.spacedBy(Space.m)
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Cover(pod.artwork, Modifier.size(96.dp), Radius.l)
                    Spacer(Modifier.width(Space.l))
                    Column(Modifier.weight(1f)) {
                        Text(
                            pod.name,
                            style = Offcast.type.sectionTitle,
                            color = c.text,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(Modifier.height(Space.xs))
                        Sub(pod.author, 1)
                    }
                }
            }
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.s)) {
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
                LazyRow(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                    items(filters, key = { it.first }) { (key, label) ->
                        FilterChip(label, selected = vm.epFilter == key) { vm.epFilter = key }
                    }
                }
            }
            (vm.message ?: Core.error)?.let { msg ->
                item {
                    ErrorBanner(
                        msg,
                        onDismiss = {
                            if (vm.message != null) vm.message = null else Core.clearError()
                        },
                        onRetry = when {
                            vm.message != null -> ({ vm.submit(vm.current?.name ?: vm.input) })
                            Core.lastErrorJob != null -> ({ Core.retryLastError() })
                            else -> null
                        }
                    )
                }
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
                        style = Offcast.type.itemTitle,
                        color = if (l?.played == true) c.textMuted else c.text,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                    val info = listOf(e.date, e.duration).filter { it.isNotEmpty() }.joinToString(" · ")
                    if (info.isNotEmpty()) Sub(info, 1)
                    ListenStatus(l)
                    Spacer(Modifier.height(Space.m))
                    when {
                        saved != null -> {
                            val resume = l != null && !l.played && l.pos > 10_000
                            PrimaryButton(if (resume) "Seguir escuchando" else "Reproducir") { vm.play(saved) }
                        }
                        prog != null -> {
                            ProgressBar(prog.coerceIn(0f, 1f), height = 6.dp)
                            Spacer(Modifier.height(Space.s))
                            Sub("Descargando… ${(prog * 100).toInt()}%", 1)
                        }
                        else -> PrimaryButton("Descargar") { vm.download(e) }
                    }
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
        title = { Text("Descargar varios", style = Offcast.type.itemTitle) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                if (options.isEmpty()) {
                    Text("Ya descargaste todo lo que hay en este podcast.", style = Offcast.type.body)
                }
                options.forEach { o ->
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .clip(RoundedCornerShape(Radius.m))
                            .clickable(role = Role.Button) {
                                vm.bulkOpen = false
                                vm.downloadMany(o.episodes)
                            }
                            .padding(horizontal = Space.m, vertical = Space.s),
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(o.label, style = Offcast.type.itemTitle, color = Offcast.colors.text)
                        Sub(o.detail, 1)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { vm.bulkOpen = false }, modifier = Modifier.heightIn(min = 48.dp)) {
                Text("Cancelar")
            }
        }
    )
}

// ---------- Pantalla 3: reproductor ----------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerScreen(vm: AppViewModel) {
    val c = Offcast.colors
    val s = vm.playing
    var drag by remember { mutableStateOf<Float?>(null) }
    var menu by remember { mutableStateOf(false) }
    var sleepDialog by remember { mutableStateOf(false) }

    Column(Modifier.screenWidth()) {
        BackBar(
            onBack = { vm.back() },
            actions = {
                IconButton(onClick = { menu = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = "Más opciones")
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text("Marcar como escuchado") },
                        onClick = {
                            vm.markPlayed()
                            menu = false
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Resumir con IA") },
                        onClick = {
                            vm.summarizeCurrent()
                            menu = false
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Borrar descarga") },
                        onClick = {
                            vm.deletePlaying()
                            menu = false
                        }
                    )
                }
            }
        )
        if (s == null) {
            Box(Modifier.fillMaxSize().padding(Space.xl)) {
                EmptyState(
                    icon = Icons.Filled.Podcasts,
                    title = "Nada sonando",
                    body = "Elegí un episodio de tu biblioteca para empezar.",
                    actionLabel = "Ir a la biblioteca",
                    onAction = { vm.goLibrary() }
                )
            }
            return@Column
        }
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Space.xl),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(Space.s))
            BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                val art = (maxWidth * 0.78f).coerceAtMost(320.dp)
                Cover(s.artwork, Modifier.size(art).aspectRatio(1f), Radius.xl)
            }
            Spacer(Modifier.height(Space.xl))
            Text(
                s.title,
                style = Offcast.type.sectionTitle,
                color = c.text,
                textAlign = TextAlign.Center,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(Space.xs))
            Sub(s.podcast, 1, textAlign = TextAlign.Center)
            Spacer(Modifier.height(Space.xl))
            Slider(
                value = drag ?: vm.position.toFloat(),
                onValueChange = { drag = it },
                onValueChangeFinished = {
                    drag?.let { vm.seekTo(it.toLong()) }
                    drag = null
                },
                valueRange = 0f..maxOf(vm.duration.toFloat(), 1f),
                colors = SliderDefaults.colors(
                    thumbColor = c.accentSoft,
                    activeTrackColor = c.accentSoft,
                    inactiveTrackColor = c.surfaceFlat,
                    activeTickColor = Color.Transparent,
                    inactiveTickColor = Color.Transparent
                ),
                track = { sliderState ->
                    SliderDefaults.Track(
                        sliderState = sliderState,
                        modifier = Modifier.height(8.dp),
                        colors = SliderDefaults.colors(
                            activeTrackColor = c.accentSoft,
                            inactiveTrackColor = c.surfaceFlat
                        ),
                        drawStopIndicator = {}
                    )
                }
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Sub(fmt((drag ?: vm.position.toFloat()).toLong()), 1)
                Sub(fmt(vm.duration), 1)
            }
            Spacer(Modifier.height(Space.l))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.xl)) {
                IconButton(onClick = { vm.skip(-10000) }, modifier = Modifier.size(56.dp)) {
                    Icon(Icons.Filled.Replay10, contentDescription = "Retroceder 10 segundos", modifier = Modifier.size(34.dp))
                }
                Box(
                    Modifier
                        .size(76.dp)
                        .clip(CircleShape)
                        .background(accentBrush())
                        .clickable(role = Role.Button) { vm.toggle() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        if (vm.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = if (vm.isPlaying) "Pausar" else "Reproducir",
                        tint = c.onAccent,
                        modifier = Modifier.size(40.dp)
                    )
                }
                IconButton(onClick = { vm.skip(30000) }, modifier = Modifier.size(56.dp)) {
                    Icon(Icons.Filled.Forward30, contentDescription = "Adelantar 30 segundos", modifier = Modifier.size(34.dp))
                }
            }
            Spacer(Modifier.height(Space.xl))
            LazyRow(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Space.s, Alignment.CenterHorizontally)
            ) {
                items(listOf(1f to "1x", 1.25f to "1.25x", 1.5f to "1.5x", 2f to "2x")) { (value, label) ->
                    SpeedChip(label, selected = kotlin.math.abs(vm.speed - value) < 0.01f) { vm.changeSpeed(value) }
                }
            }
            Spacer(Modifier.height(Space.m))
            TextButton(onClick = { sleepDialog = true }) {
                Text(
                    if (vm.sleepTimerMinutes > 0) "Dormir en ${vm.sleepTimerMinutes} min"
                    else "Temporizador de sueño",
                    style = Offcast.type.caption,
                    color = Offcast.colors.accentSoft
                )
            }
            Spacer(Modifier.height(Space.xxl))
        }
    }

    if (sleepDialog) {
        AlertDialog(
            onDismissRequest = { sleepDialog = false },
            title = { Text("Dormir después de…", style = Offcast.type.itemTitle) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                    listOf(0 to "Desactivar", 15 to "15 minutos", 30 to "30 minutos", 45 to "45 minutos", 60 to "60 minutos").forEach { (m, label) ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .clickable(role = Role.Button) {
                                    vm.setSleepTimer(m)
                                    sleepDialog = false
                                }
                                .padding(horizontal = Space.m),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(label, style = Offcast.type.body, color = Offcast.colors.text)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { sleepDialog = false }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text("Cerrar")
                }
            }
        )
    }

    if (vm.summaryDialog) {
        AlertDialog(
            onDismissRequest = { vm.summaryDialog = false },
            title = { Text("Resumen del episodio", style = Offcast.type.itemTitle) },
            text = {
                if (vm.summaryLoading) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(Space.m))
                        Text("Generando…", style = Offcast.type.body)
                    }
                } else {
                    Text(vm.summaryText ?: "", style = Offcast.type.body)
                }
            },
            confirmButton = {
                TextButton(onClick = { vm.summaryDialog = false }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text("Cerrar")
                }
            }
        )
    }
}

@Composable
fun SpeedChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val c = Offcast.colors
    val shape = RoundedCornerShape(Radius.l)
    Box(
        Modifier
            .heightIn(min = 48.dp)
            .clip(shape)
            .then(
                if (selected) Modifier.background(accentBrush())
                else Modifier
                    .background(c.surfaceTop)
                    .border(1.dp, c.borderTop, shape)
            )
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = Space.l),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            style = Offcast.type.caption,
            color = if (selected) c.onAccent else c.accentSoft,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
        )
    }
}

// ---------- Barra inferior ----------

@Composable
fun BottomBar(vm: AppViewModel) {
    val c = Offcast.colors
    val shape = RoundedCornerShape(Radius.xl)
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = Space.xl, vertical = Space.s)
            .clip(shape)
            .background(Brush.verticalGradient(listOf(c.surfaceTop, c.surfaceBottom)))
            .border(
                1.dp,
                Brush.verticalGradient(listOf(c.borderTop, c.borderBottom)),
                shape
            )
            .padding(6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        NavItem("Inicio", Icons.Filled.Home, vm.screen == Screen.Home, Modifier.weight(1f)) { vm.goHome() }
        NavItem("Biblioteca", Icons.Filled.LibraryMusic, vm.screen == Screen.Library, Modifier.weight(1f)) { vm.goLibrary() }
        NavItem("Cola", Icons.Filled.QueueMusic, vm.screen == Screen.Queue, Modifier.weight(1f)) { vm.goQueue() }
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
    val c = Offcast.colors
    val shape = RoundedCornerShape(Radius.l)
    Column(
        modifier
            .heightIn(min = 64.dp)
            .clip(shape)
            .then(if (selected) Modifier.background(accentBrush()) else Modifier)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = Space.s),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        val tint = if (selected) c.onAccent else c.navIdle
        Icon(
            icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(22.dp)
        )
        Spacer(Modifier.height(2.dp))
        Text(
            label,
            style = Offcast.type.label,
            color = tint,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

// ---------- Mini reproductor ----------

@Composable
fun MiniPlayer(vm: AppViewModel) {
    val s = vm.playing ?: return
    val c = Offcast.colors
    Glass(
        modifier = Modifier.padding(horizontal = Space.l, vertical = Space.s),
        padding = 0.dp
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(
                Modifier
                    .weight(1f)
                    .clickable(role = Role.Button) { vm.openPlayer() }
                    .padding(start = Space.m, top = Space.m, bottom = Space.m, end = Space.s),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Cover(s.artwork, Modifier.size(44.dp), Radius.s)
                Spacer(Modifier.width(Space.m))
                Column(Modifier.weight(1f)) {
                    Text(
                        s.title,
                        style = Offcast.type.itemTitle,
                        color = c.text,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Sub(s.podcast, 1)
                }
            }
            IconButton(onClick = { vm.toggle() }) {
                Icon(
                    if (vm.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (vm.isPlaying) "Pausar" else "Reproducir"
                )
            }
            IconButton(onClick = { vm.hideMini() }) {
                Icon(Icons.Filled.Close, contentDescription = "Ocultar reproductor")
            }
        }
        if (vm.duration > 0) {
            ProgressBar(
                (vm.position.toFloat() / vm.duration).coerceIn(0f, 1f),
                height = 3.dp,
                track = false
            )
        }
    }
}
