package com.example.musicplayer

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.media3.common.Player
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

@Composable
fun MusicApp(player: Player?) {
    val ctx = LocalContext.current
    val data = remember { UserData(ctx.getSharedPreferences("user", Context.MODE_PRIVATE)) }

    // ---- Permissions ----
    val audioPerm = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO
    else Manifest.permission.READ_EXTERNAL_STORAGE
    val perms = remember {
        listOfNotNull(
            audioPerm,
            if (Build.VERSION.SDK_INT >= 33) Manifest.permission.POST_NOTIFICATIONS else null
        ).toTypedArray()
    }
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(ctx, audioPerm) == PackageManager.PERMISSION_GRANTED)
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        granted = ContextCompat.checkSelfPermission(ctx, audioPerm) == PackageManager.PERMISSION_GRANTED
    }
    LaunchedEffect(Unit) { launcher.launch(perms) }

    // ---- Library ----
    var musicOnly by remember { mutableStateOf(true) }
    var query by remember { mutableStateOf("") }
    var all by remember { mutableStateOf(emptyList<Song>()) }
    var loaded by remember { mutableStateOf(false) }
    LaunchedEffect(granted) {
        if (granted) {
            all = withContext(Dispatchers.IO) { loadSongs(ctx) }
            loaded = true
        }
    }
    val songs = remember(all, musicOnly) { if (musicOnly) all.filter { it.music } else all }
    val byId = remember(all) { all.associateBy { it.id } }
    val shown = remember(songs, query) {
        if (query.isBlank()) songs
        else songs.filter { it.title.contains(query, true) || it.artist.contains(query, true) }
    }
    val favSongs = remember(data.favs, byId) {
        data.favs.mapNotNull { byId[it] }.sortedBy { it.title.lowercase() }
    }
    val artists = remember(songs) { songs.groupBy { it.artist }.toSortedMap(String.CASE_INSENSITIVE_ORDER) }
    val genres = remember(songs) { songs.groupBy { it.genre }.toSortedMap(String.CASE_INSENSITIVE_ORDER) }

    // Preload the library so the Play button works before any song is tapped
    LaunchedEffect(player, songs) {
        val p = player ?: return@LaunchedEffect
        if (p.mediaItemCount == 0 && songs.isNotEmpty()) {
            p.setMediaItems(songs.map { it.toItem() })
            p.prepare()
        }
    }

    var showNow by remember { mutableStateOf(false) }

    // Tapping a song queues the list it was tapped in (library, favourites or a playlist)
    fun playQueue(list: List<Song>, index: Int) {
        val p = player ?: return
        val same = p.mediaItemCount == list.size &&
            list.indices.all { p.getMediaItemAt(it).mediaId == list[it].id.toString() }
        if (same) p.seekTo(index, 0) else p.setMediaItems(list.map { it.toItem() }, index, 0L)
        p.prepare()
        p.play()
        showNow = true
    }

    // ---- Player state ----
    var playing by remember { mutableStateOf(false) }
    var repeat by remember { mutableIntStateOf(Player.REPEAT_MODE_OFF) }
    var shuffle by remember { mutableStateOf(false) }
    var currentId by remember { mutableStateOf<String?>(null) }
    var nowTitle by remember { mutableStateOf("Nothing playing") }
    var nowArtist by remember { mutableStateOf("") }
    var pos by remember { mutableLongStateOf(0L) }
    var dur by remember { mutableLongStateOf(0L) }
    var dragging by remember { mutableStateOf(false) }

    DisposableEffect(player) {
        val p = player
        fun sync() {
            if (p == null) return
            playing = p.isPlaying || (p.playWhenReady && p.playbackState == Player.STATE_BUFFERING)
            repeat = p.repeatMode
            shuffle = p.shuffleModeEnabled
            val item = p.currentMediaItem
            currentId = item?.mediaId
            nowTitle = item?.mediaMetadata?.title?.toString() ?: "Nothing playing"
            nowArtist = item?.mediaMetadata?.artist?.toString() ?: ""
        }
        sync()
        val l = object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) = sync()
        }
        p?.addListener(l)
        onDispose { p?.removeListener(l) }
    }

    LaunchedEffect(player) {
        while (true) {
            val p = player
            if (p != null && !dragging) {
                pos = p.currentPosition
                dur = p.duration.coerceAtLeast(0)
            }
            delay(500)
        }
    }

    // ---- UI state ----
    var tab by remember { mutableIntStateOf(0) }
    var openList by remember { mutableStateOf<String?>(null) }
    var openArtist by remember { mutableStateOf<String?>(null) }
    var openGenre by remember { mutableStateOf<String?>(null) }
    var showNew by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf<Long?>(null) }
    BackHandler(
        enabled = (tab == 1 && openArtist != null) || (tab == 2 && openGenre != null) || (tab == 4 && openList != null)
    ) {
        when (tab) { 1 -> openArtist = null; 2 -> openGenre = null; else -> openList = null }
    }
    BackHandler(enabled = showNow) { showNow = false }

    if (showNew) {
        var name by remember { mutableStateOf("") }
        val close = { showNew = false; pending = null }
        AlertDialog(
            onDismissRequest = { close() },
            title = { Text("New playlist") },
            text = {
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    singleLine = true, placeholder = { Text("Playlist name") }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val n = name.trim()
                    if (n.isNotEmpty()) {
                        data.createList(n)
                        pending?.let { data.addToList(n, it) }
                    }
                    close()
                }) { Text("Create") }
            },
            dismissButton = { TextButton(onClick = { close() }) { Text("Cancel") } }
        )
    }

    Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize()) {
        ScrollableTabRow(selectedTabIndex = tab, edgePadding = 8.dp) {
            listOf("Songs", "Artists", "Genres", "Favourites", "Playlists", "EQ").forEachIndexed { i, t ->
                Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t, maxLines = 1) })
            }
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (tab) {
                0 -> Column(Modifier.fillMaxSize()) {
                    Row(
                        Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "Library (${shown.size})",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.weight(1f)
                        )
                        Text("Music only", style = MaterialTheme.typography.labelMedium)
                        Spacer(Modifier.width(8.dp))
                        Switch(checked = musicOnly, onCheckedChange = { musicOnly = it })
                    }
                    OutlinedTextField(
                        value = query, onValueChange = { query = it },
                        singleLine = true,
                        placeholder = { Text("Search songs or artists") },
                        leadingIcon = { Icon(Icons.Filled.Search, null) },
                        trailingIcon = {
                            if (query.isNotEmpty()) IconButton(onClick = { query = "" }) {
                                Icon(Icons.Filled.Close, "Clear")
                            }
                        },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        when {
                            !granted -> Column(
                                Modifier.fillMaxSize(),
                                verticalArrangement = Arrangement.Center,
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text("Allow access to your audio files")
                                Spacer(Modifier.height(8.dp))
                                Button(onClick = { launcher.launch(perms) }) { Text("Grant permission") }
                            }
                            !loaded -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                            songs.isEmpty() -> Text(
                                if (musicOnly) "No music found. Try turning off \"Music only\"." else "No audio files found",
                                Modifier.align(Alignment.Center).padding(24.dp)
                            )
                            shown.isEmpty() -> Text("No matches", Modifier.align(Alignment.Center))
                            else -> SongList(shown, currentId, data, null,
                                onPlay = { playQueue(shown, it) },
                                onNew = { pending = it; showNew = true })
                        }
                    }
                }

                1 -> GroupTab(
                    artists, Icons.Filled.Person, openArtist, { openArtist = it }, currentId, data,
                    onPlay = { list, i -> playQueue(list, i) }, onNew = { pending = it; showNew = true }
                )

                2 -> GroupTab(
                    genres, Icons.Filled.Album, openGenre, { openGenre = it }, currentId, data,
                    onPlay = { list, i -> playQueue(list, i) }, onNew = { pending = it; showNew = true }
                )

                3 -> Column(Modifier.fillMaxSize()) {
                    Text(
                        "Favourites (${favSongs.size})",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(16.dp)
                    )
                    if (favSongs.isEmpty()) Text("Tap the heart on any song to add it here.", Modifier.padding(horizontal = 16.dp))
                    else SongList(favSongs, currentId, data, null,
                        onPlay = { playQueue(favSongs, it) },
                        onNew = { pending = it; showNew = true })
                }

                4 -> PlaylistsTab(
                    data, byId, openList, { openList = it }, currentId,
                    onPlay = { list, i -> playQueue(list, i) },
                    onNew = { pending = it; showNew = true }
                )

                else -> EqualizerTab()
            }
        }

        // ---- Bottom player bar: tap it to open the Now Playing window ----
        Surface(modifier = Modifier.clickable { showNow = true }, tonalElevation = 3.dp) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(nowTitle, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (nowArtist.isNotEmpty()) {
                            Text(nowArtist, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    val curFav = currentId?.toLongOrNull()?.let { it in data.favs } ?: false
                    IconButton(onClick = { currentId?.toLongOrNull()?.let { data.toggleFav(it) } }) {
                        Icon(
                            if (curFav) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder, "Favourite",
                            tint = if (curFav) Color(0xFFE91E63) else LocalContentColor.current
                        )
                    }
                }
                SeekBar(pos, dur, onDrag = { dragging = true; pos = it }, onDone = { player?.seekTo(pos); dragging = false })
                Controls(player, playing, shuffle, repeat)
            }
        }
    }

    // ---- Now Playing window (slides up over everything) ----
    AnimatedVisibility(visible = showNow, enter = slideInVertically { it }, exit = slideOutVertically { it }) {
        val fav = currentId?.toLongOrNull()?.let { it in data.favs } ?: false
        NowPlaying(
            title = nowTitle, artist = nowArtist, playing = playing, shuffle = shuffle, repeat = repeat,
            fav = fav, pos = pos, dur = dur, player = player,
            onFav = { currentId?.toLongOrNull()?.let { data.toggleFav(it) } },
            onDrag = { dragging = true; pos = it },
            onDone = { player?.seekTo(pos); dragging = false },
            onClose = { showNow = false }
        )
    }
    }
}

/** Numbered song list with a heart (favourite) and a ⋮ menu (add to playlist) on every row. */
@Composable
fun SongList(
    list: List<Song>, currentId: String?, data: UserData, inPlaylist: String?,
    onPlay: (Int) -> Unit, onNew: (Long) -> Unit
) {
    LazyColumn(Modifier.fillMaxSize()) {
        itemsIndexed(list, key = { _, s -> s.id }) { i, s ->
            var menu by remember { mutableStateOf(false) }
            val cur = s.id.toString() == currentId
            val fav = s.id in data.favs
            ListItem(
                leadingContent = { Text("${i + 1}", style = MaterialTheme.typography.labelLarge) },
                headlineContent = {
                    Text(
                        s.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        color = if (cur) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        fontWeight = if (cur) FontWeight.Bold else FontWeight.Normal
                    )
                },
                supportingContent = { Text(s.artist, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                trailingContent = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { data.toggleFav(s.id) }) {
                            Icon(
                                if (fav) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder, "Favourite",
                                tint = if (fav) Color(0xFFE91E63) else LocalContentColor.current
                            )
                        }
                        Box {
                            IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "More") }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                data.lists.keys.forEach { n ->
                                    DropdownMenuItem(
                                        text = { Text("Add to $n") },
                                        onClick = { data.addToList(n, s.id); menu = false })
                                }
                                DropdownMenuItem(
                                    text = { Text("New playlist…") },
                                    onClick = { menu = false; onNew(s.id) })
                                if (inPlaylist != null) DropdownMenuItem(
                                    text = { Text("Remove from playlist") },
                                    onClick = { data.removeFromList(inPlaylist, s.id); menu = false })
                            }
                        }
                    }
                },
                modifier = Modifier.clickable { onPlay(i) }
            )
        }
    }
}

@Composable
fun PlaylistsTab(
    data: UserData, byId: Map<Long, Song>, open: String?, onOpen: (String?) -> Unit,
    currentId: String?, onPlay: (List<Song>, Int) -> Unit, onNew: (Long?) -> Unit
) {
    val inside = open?.let { n -> data.lists[n]?.mapNotNull { byId[it] } }
    if (open != null && inside != null) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { onOpen(null) }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                Text(
                    "$open (${inside.size})", style = MaterialTheme.typography.titleLarge,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)
                )
                TextButton(onClick = { if (inside.isNotEmpty()) onPlay(inside, 0) }) { Text("Play all") }
            }
            if (inside.isEmpty()) Text("Empty playlist. Use ⋮ on any song to add it here.", Modifier.padding(16.dp))
            else SongList(inside, currentId, data, open, onPlay = { onPlay(inside, it) }, onNew = { onNew(it) })
        }
    } else {
        Column(Modifier.fillMaxSize()) {
            Button(onClick = { onNew(null) }, modifier = Modifier.padding(16.dp)) {
                Icon(Icons.Filled.Add, null)
                Spacer(Modifier.width(8.dp))
                Text("New playlist")
            }
            if (data.lists.isEmpty()) Text("No playlists yet.", Modifier.padding(horizontal = 16.dp))
            LazyColumn(Modifier.fillMaxSize()) {
                items(data.lists.keys.toList()) { n ->
                    ListItem(
                        leadingContent = { Icon(Icons.Filled.LibraryMusic, null) },
                        headlineContent = { Text(n) },
                        supportingContent = { Text("${data.lists[n]?.size ?: 0} songs") },
                        trailingContent = {
                            IconButton(onClick = { data.deleteList(n) }) { Icon(Icons.Filled.Delete, "Delete playlist") }
                        },
                        modifier = Modifier.clickable { onOpen(n) }
                    )
                }
            }
        }
    }
}

@Composable
fun EqualizerTab() {
    val e = EqController.eq
    if (e == null) {
        Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Text("Equalizer isn't available on this device.")
        }
        return
    }
    val bands = e.numberOfBands.toInt()
    val lo = e.bandLevelRange[0].toFloat()
    val hi = e.bandLevelRange[1].toFloat()
    var on by remember { mutableStateOf(e.enabled) }
    val levels = remember { mutableStateListOf<Int>().also { l -> for (i in 0 until bands) l.add(e.getBandLevel(i.toShort()).toInt()) } }
    val bassOk = EqController.bass?.strengthSupported == true
    var bass by remember { mutableIntStateOf(EqController.bass?.roundedStrength?.toInt() ?: 0) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Equalizer", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Switch(checked = on, onCheckedChange = { on = it; EqController.setOn(it) })
        }
        Spacer(Modifier.height(8.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items((0 until e.numberOfPresets).toList()) { i ->
                AssistChip(
                    onClick = {
                        EqController.usePreset(i)
                        for (b in 0 until bands) levels[b] = e.getBandLevel(b.toShort()).toInt()
                    },
                    label = { Text(e.getPresetName(i.toShort())) }
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        for (b in 0 until bands) {
            val hz = e.getCenterFreq(b.toShort()) / 1000
            Text(if (hz >= 1000) "%.1f kHz".format(hz / 1000f) else "$hz Hz", style = MaterialTheme.typography.labelMedium)
            Slider(
                value = levels[b].toFloat(), valueRange = lo..hi, enabled = on,
                onValueChange = { levels[b] = it.toInt(); EqController.setBand(b, it.toInt()) }
            )
        }
        if (bassOk) {
            Text("Bass boost", style = MaterialTheme.typography.labelMedium)
            Slider(
                value = bass.toFloat(), valueRange = 0f..1000f, enabled = on,
                onValueChange = { bass = it.toInt(); EqController.setBass(it.toInt()) }
            )
        }
    }
}

/** Seek bar with elapsed / total time. */
@Composable
fun SeekBar(pos: Long, dur: Long, onDrag: (Long) -> Unit, onDone: () -> Unit) {
    val max = maxOf(dur, 1L).toFloat()
    Slider(
        value = pos.toFloat().coerceIn(0f, max),
        valueRange = 0f..max,
        onValueChange = { onDrag(it.toLong()) },
        onValueChangeFinished = onDone
    )
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(fmt(pos), style = MaterialTheme.typography.labelSmall)
        Text(fmt(dur), style = MaterialTheme.typography.labelSmall)
    }
}

/** Shuffle, previous, play/pause, next, loop. */
@Composable
fun Controls(player: Player?, playing: Boolean, shuffle: Boolean, repeat: Int, big: Boolean = false) {
    val dim = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
    val on = MaterialTheme.colorScheme.primary
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = { player?.shuffleModeEnabled = !shuffle }) {
            Icon(Icons.Filled.Shuffle, "Shuffle", tint = if (shuffle) on else dim)
        }
        IconButton(onClick = { player?.seekToPrevious() }) {
            Icon(Icons.Filled.SkipPrevious, "Previous", Modifier.size(if (big) 36.dp else 24.dp))
        }
        FilledIconButton(
            onClick = {
                player?.let { p ->
                    if (playing) p.pause() else {
                        if (p.playbackState == Player.STATE_IDLE) p.prepare()
                        if (p.playbackState == Player.STATE_ENDED) p.seekToDefaultPosition(0)
                        p.play()
                    }
                }
            },
            modifier = Modifier.size(if (big) 80.dp else 64.dp)
        ) {
            Icon(
                if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                contentDescription = if (playing) "Pause" else "Play",
                modifier = Modifier.size(if (big) 46.dp else 36.dp)
            )
        }
        IconButton(onClick = { player?.seekToNext() }) {
            Icon(Icons.Filled.SkipNext, "Next", Modifier.size(if (big) 36.dp else 24.dp))
        }
        IconButton(onClick = {
            player?.repeatMode = when (repeat) {
                Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                else -> Player.REPEAT_MODE_OFF
            }
        }) {
            Icon(
                if (repeat == Player.REPEAT_MODE_ONE) Icons.Filled.RepeatOne else Icons.Filled.Repeat,
                "Loop", tint = if (repeat == Player.REPEAT_MODE_OFF) dim else on
            )
        }
    }
}

/** Vinyl record that spins while music plays and keeps its angle when paused. */
@Composable
fun Disk(spinning: Boolean, modifier: Modifier = Modifier) {
    var angle by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(spinning) {
        if (!spinning) return@LaunchedEffect
        var last = withFrameNanos { it }
        while (true) {
            val now = withFrameNanos { it }
            angle = (angle + (now - last) / 1e9f * 36f) % 360f   // one turn every 10 seconds
            last = now
        }
    }
    Box(modifier.graphicsLayer { rotationZ = angle }, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val r = size.minDimension / 2f
            drawCircle(Color(0xFF141414), r, center)
            var g = r * 0.97f
            while (g > r * 0.5f) {                       // grooves
                drawCircle(Color(0x1FFFFFFF), g, center, style = Stroke(1.5f))
                g -= r * 0.04f
            }
            drawCircle(                                   // light reflections
                brush = Brush.sweepGradient(
                    0f to Color.Transparent, 0.10f to Color(0x44FFFFFF), 0.22f to Color.Transparent,
                    0.50f to Color.Transparent, 0.60f to Color(0x44FFFFFF), 0.72f to Color.Transparent,
                    1f to Color.Transparent, center = center
                ),
                radius = r, center = center
            )
            drawCircle(Color(0xFFFFB300), r, center, style = Stroke(r * 0.02f))   // gold rim
        }
        Image(painterResource(R.drawable.disk_label), null, Modifier.fillMaxSize(0.46f).clip(CircleShape))
        Box(Modifier.size(12.dp).clip(CircleShape).background(Color(0xFF141414)))   // spindle hole
    }
}

@Composable
fun NowPlaying(
    title: String, artist: String, playing: Boolean, shuffle: Boolean, repeat: Int, fav: Boolean,
    pos: Long, dur: Long, player: Player?,
    onFav: () -> Unit, onDrag: (Long) -> Unit, onDone: () -> Unit, onClose: () -> Unit
) {
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose) { Icon(Icons.Filled.KeyboardArrowDown, "Close", Modifier.size(32.dp)) }
                Text(
                    "Now Playing", Modifier.weight(1f), textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(Modifier.size(48.dp))
            }
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Disk(playing, Modifier.size(minOf(maxWidth, maxHeight) * 0.92f))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                    Text(artist, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                IconButton(onClick = onFav) {
                    Icon(
                        if (fav) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder, "Favourite",
                        tint = if (fav) Color(0xFFE91E63) else LocalContentColor.current
                    )
                }
            }
            SeekBar(pos, dur, onDrag, onDone)
            Spacer(Modifier.height(4.dp))
            Controls(player, playing, shuffle, repeat, big = true)
            Spacer(Modifier.height(16.dp))
        }
    }
}

/** Artists / Genres: list of groups; tap one to see its songs. */
@Composable
fun GroupTab(
    groups: Map<String, List<Song>>, icon: ImageVector, open: String?, onOpen: (String?) -> Unit,
    currentId: String?, data: UserData, onPlay: (List<Song>, Int) -> Unit, onNew: (Long) -> Unit
) {
    val inside = open?.let { groups[it] }
    if (open != null && inside != null) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { onOpen(null) }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                Text(
                    "$open (${inside.size})", style = MaterialTheme.typography.titleLarge,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)
                )
                TextButton(onClick = { onPlay(inside, 0) }) { Text("Play all") }
            }
            SongList(inside, currentId, data, null, onPlay = { onPlay(inside, it) }, onNew = onNew)
        }
    } else if (groups.isEmpty()) {
        Text("Nothing here yet.", Modifier.padding(16.dp))
    } else {
        LazyColumn(Modifier.fillMaxSize()) {
            items(groups.keys.toList()) { n ->
                ListItem(
                    leadingContent = { Icon(icon, null) },
                    headlineContent = { Text(n, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    supportingContent = { Text("${groups[n]?.size ?: 0} songs") },
                    modifier = Modifier.clickable { onOpen(n) }
                )
            }
        }
    }
}
