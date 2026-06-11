package app.shelfie.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Forward30
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.media3.session.MediaController
import app.shelfie.ui.theme.ShelfieSurface
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private val SPEED_OPTIONS = listOf(0.75f, 1.0f, 1.25f, 1.5f, 2.0f, 2.5f, 3.0f)

@Composable
fun PlayerScreen(
    state: PlayerUiState,
    controller: MediaController?,
    onBack: () -> Unit,
) {
    // Swipe-down to dismiss: once the scrollable content is at the top, further
    // downward drag translates the whole player; past a threshold (or on a fast
    // fling) it closes like the chevron button, otherwise it springs back.
    val offsetY = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val dismissThresholdPx = with(LocalDensity.current) { 160.dp.toPx() }
    val dismissConnection = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput && available.y < 0f && offsetY.value > 0f) {
                    val target = (offsetY.value + available.y).coerceAtLeast(0f)
                    val consumed = target - offsetY.value
                    scope.launch { offsetY.snapTo(target) }
                    return Offset(0f, consumed)
                }
                return Offset.Zero
            }

            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource,
            ): Offset {
                if (source == NestedScrollSource.UserInput && available.y > 0f) {
                    scope.launch { offsetY.snapTo(offsetY.value + available.y) }
                    return Offset(0f, available.y)
                }
                return Offset.Zero
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                return if (offsetY.value > dismissThresholdPx || available.y > 2500f) {
                    onBack()
                    available
                } else {
                    offsetY.animateTo(0f)
                    Velocity.Zero
                }
            }
        }
    }

    // This screen renders as an overlay outside the Scaffold, so it needs its own
    // Surface: without it LocalContentColor defaults to black and all text goes dark.
    Surface(
        color = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground,
        modifier = Modifier
            .fillMaxSize()
            .offset { IntOffset(0, offsetY.value.roundToInt()) }
            .nestedScroll(dismissConnection),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
        Row(modifier = Modifier.fillMaxWidth()) {
            IconButton(onClick = onBack) {
                Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Close")
            }
        }
        Spacer(Modifier.height(8.dp))

        CoverImage(
            model = state.artworkUri,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxWidth(0.8f)
                .aspectRatio(1f)
                .clip(RoundedCornerShape(16.dp)),
        )
        Spacer(Modifier.height(24.dp))

        Text(
            state.title,
            style = MaterialTheme.typography.titleLarge,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            state.artist,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(24.dp))

        SeekBar(state, controller)
        Spacer(Modifier.height(16.dp))

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            IconButton(onClick = { controller?.seekBack() }, modifier = Modifier.size(64.dp)) {
                Icon(Icons.Filled.Replay10, contentDescription = "Back 10 seconds", modifier = Modifier.size(40.dp))
            }
            FilledIconButton(
                onClick = { controller?.let { if (it.isPlaying) it.pause() else it.play() } },
                modifier = Modifier.size(80.dp),
            ) {
                if (state.isLoading) {
                    CircularProgressIndicator(modifier = Modifier.size(32.dp), strokeWidth = 3.dp)
                } else {
                    Icon(
                        if (state.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = if (state.isPlaying) "Pause" else "Play",
                        modifier = Modifier.size(44.dp),
                    )
                }
            }
            IconButton(onClick = { controller?.seekForward() }, modifier = Modifier.size(64.dp)) {
                Icon(Icons.Filled.Forward30, contentDescription = "Forward 30 seconds", modifier = Modifier.size(40.dp))
            }
        }
        Spacer(Modifier.height(24.dp))

            SpeedSelector(state = state, controller = controller)
        }
    }
}

@Composable
private fun SpeedSelector(state: PlayerUiState, controller: MediaController?) {
    var menuOpen by remember { mutableStateOf(false) }

    Box {
        OutlinedButton(onClick = { menuOpen = true }) {
            Icon(
                Icons.Filled.Speed,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(formatSpeed(state.speed))
        }
        DropdownMenu(
            expanded = menuOpen,
            onDismissRequest = { menuOpen = false },
        ) {
            SPEED_OPTIONS.forEach { speed ->
                val selected = kotlin.math.abs(state.speed - speed) < 0.01f
                DropdownMenuItem(
                    text = {
                        Text(
                            formatSpeed(speed),
                            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        )
                    },
                    trailingIcon = if (selected) {
                        {
                            Icon(
                                Icons.Filled.Check,
                                contentDescription = "Selected",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    } else {
                        null
                    },
                    onClick = {
                        controller?.setPlaybackSpeed(speed)
                        menuOpen = false
                    },
                )
            }
        }
    }
}

@Composable
private fun SeekBar(state: PlayerUiState, controller: MediaController?) {
    var dragPosition by remember { mutableStateOf<Float?>(null) }
    val duration = state.durationMs.coerceAtLeast(1L)
    val shownPosition = dragPosition ?: (state.positionMs.toFloat() / duration)

    Column(Modifier.fillMaxWidth()) {
        Slider(
            value = shownPosition.coerceIn(0f, 1f),
            onValueChange = { dragPosition = it },
            onValueChangeFinished = {
                dragPosition?.let { fraction ->
                    controller?.seekTo((fraction * duration).toLong())
                }
                dragPosition = null
            },
            modifier = Modifier.fillMaxWidth(),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            val shownMs = ((dragPosition ?: (state.positionMs.toFloat() / duration)) * duration).toLong()
            Text(
                formatDuration(shownMs / 1000),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                if (state.durationMs > 0) "-" + formatDuration(((state.durationMs - shownMs) / 1000).coerceAtLeast(0)) else "",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun formatSpeed(speed: Float): String =
    if (speed == speed.toInt().toFloat()) "${speed.toInt()}x" else "${speed}x"

@Composable
fun NowPlayingBar(
    state: PlayerUiState,
    controller: MediaController?,
    onExpand: () -> Unit,
) {
    if (!state.hasMedia) return

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .background(ShelfieSurface)
            .clickable(onClick = onExpand)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        CoverImage(
            model = state.artworkUri,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(6.dp)),
        )
        Column(
            Modifier
                .weight(1f)
                .padding(horizontal = 12.dp),
        ) {
            Text(
                state.title,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                state.artist,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = { controller?.seekBack() }) {
            Icon(Icons.Filled.Replay10, contentDescription = "Back 10 seconds")
        }
        IconButton(onClick = { controller?.let { if (it.isPlaying) it.pause() else it.play() } }) {
            if (state.isLoading) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
            } else {
                Icon(
                    if (state.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (state.isPlaying) "Pause" else "Play",
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
        IconButton(onClick = { controller?.seekForward() }) {
            Icon(Icons.Filled.Forward30, contentDescription = "Forward 30 seconds")
        }
    }
}
