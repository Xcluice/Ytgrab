package com.xcluice.ytgrab.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.view.TextureView
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import com.xcluice.ytgrab.DlItem
import com.xcluice.ytgrab.Engine
import com.xcluice.ytgrab.Ts
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private enum class PI { Back, Play, Pause, Replay, Rew10, Fwd10, Full, ExitFull, VolOn, VolOff, Loop }

@Composable
private fun PIcon(icon: PI, tint: Color = Color.White, size: Dp = 24.dp) {
    Canvas(Modifier.size(size)) {
        val w = this.size.width
        val st = Stroke(width = (size.value * 0.095f).dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        fun Path.m(x: Float, y: Float) = moveTo(x * w, y * w)
        fun Path.l(x: Float, y: Float) = lineTo(x * w, y * w)
        when (icon) {
            PI.Back -> drawPath(Path().apply {
                m(.62f, .2f); l(.32f, .5f); l(.62f, .8f)
            }, tint, style = st)
            PI.Play -> drawPath(Path().apply {
                m(.3f, .18f); l(.3f, .82f); l(.82f, .5f); close()
            }, tint, style = Fill)
            PI.Pause -> {
                val rw = .17f * w
                val rh = .62f * w
                drawRoundRect(tint, Offset(.26f * w, .19f * w), Size(rw, rh), CornerRadius(.05f * w))
                drawRoundRect(tint, Offset(.57f * w, .19f * w), Size(rw, rh), CornerRadius(.05f * w))
            }
            PI.Replay -> {
                drawArc(tint, -45f, 290f, false, Offset(.18f * w, .18f * w), Size(.64f * w, .64f * w), style = st)
                drawPath(Path().apply {
                    m(.72f, .14f); l(.78f, .34f); l(.58f, .34f)
                }, tint, style = st)
            }
            PI.Rew10 -> {
                drawPath(Path().apply {
                    m(.48f, .24f); l(.18f, .5f); l(.48f, .76f); close()
                    m(.82f, .24f); l(.52f, .5f); l(.82f, .76f); close()
                }, tint, style = Fill)
            }
            PI.Fwd10 -> {
                drawPath(Path().apply {
                    m(.18f, .24f); l(.48f, .5f); l(.18f, .76f); close()
                    m(.52f, .24f); l(.82f, .5f); l(.52f, .76f); close()
                }, tint, style = Fill)
            }
            PI.Full -> drawPath(Path().apply {
                m(.18f, .38f); l(.18f, .18f); l(.38f, .18f)
                m(.62f, .18f); l(.82f, .18f); l(.82f, .38f)
                m(.82f, .62f); l(.82f, .82f); l(.62f, .82f)
                m(.38f, .82f); l(.18f, .82f); l(.18f, .62f)
            }, tint, style = st)
            PI.ExitFull -> drawPath(Path().apply {
                m(.36f, .18f); l(.36f, .36f); l(.18f, .36f)
                m(.64f, .18f); l(.64f, .36f); l(.82f, .36f)
                m(.64f, .82f); l(.64f, .64f); l(.82f, .64f)
                m(.36f, .82f); l(.36f, .64f); l(.18f, .64f)
            }, tint, style = st)
            PI.VolOn -> {
                drawPath(Path().apply {
                    m(.16f, .38f); l(.34f, .38f); l(.54f, .2f); l(.54f, .8f); l(.34f, .62f); l(.16f, .62f); close()
                }, tint, style = Fill)
                drawArc(tint, -42f, 84f, false, Offset(.46f * w, .32f * w), Size(.24f * w, .36f * w), style = st)
                drawArc(tint, -48f, 96f, false, Offset(.52f * w, .2f * w), Size(.36f * w, .6f * w), style = st)
            }
            PI.VolOff -> {
                drawPath(Path().apply {
                    m(.16f, .38f); l(.34f, .38f); l(.54f, .2f); l(.54f, .8f); l(.34f, .62f); l(.16f, .62f); close()
                }, tint, style = Fill)
                drawPath(Path().apply {
                    m(.64f, .36f); l(.86f, .64f); m(.86f, .36f); l(.64f, .64f)
                }, tint, style = st)
            }
            PI.Loop -> {
                drawPath(Path().apply {
                    m(.22f, .44f); l(.22f, .3f); l(.76f, .3f)
                    m(.66f, .2f); l(.78f, .3f); l(.66f, .4f)
                    m(.78f, .56f); l(.78f, .7f); l(.24f, .7f)
                    m(.34f, .6f); l(.22f, .7f); l(.34f, .8f)
                }, tint, style = st)
            }
        }
    }
}

private val SPEEDS = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f)

private fun fmtSpeed(s: Float): String =
    if (s == 1f) "1x" else if (s == 2f) "2x" else "${s}x"

private fun fmtSize(b: Long): String =
    if (b >= 1_000_000_000) String.format(Locale.US, "%.1f GB", b / 1e9)
    else String.format(Locale.US, "%.1f MB", b / 1e6)

@Composable
fun PlayerScreen(item: DlItem, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val activity = remember(ctx) { ctx.findActivity() }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scope = rememberCoroutineScope()
    val isAudioOnly = item.label == "Audio"

    var savedToGallery by remember(item.id) { mutableStateOf(false) }
    var savingGallery by remember(item.id) { mutableStateOf(false) }

    fun doExport() {
        if (savingGallery) return
        savingGallery = true
        scope.launch {
            val res = Engine.exportToGallery(ctx, item)
            savingGallery = false
            if (res.isSuccess) savedToGallery = true
            Toast.makeText(
                ctx,
                res.getOrElse { "Couldn't save to Gallery: ${it.message ?: "error"}" },
                Toast.LENGTH_SHORT,
            ).show()
        }
    }

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) doExport()
        else Toast.makeText(ctx, "Storage permission needed to save to Gallery", Toast.LENGTH_SHORT).show()
    }

    fun requestExport() {
        if (Build.VERSION.SDK_INT < 29 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) {
            permLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else {
            doExport()
        }
    }

    val player = remember(item.id) {
        ExoPlayer.Builder(ctx).build().apply {
            val file = File(Engine.dir, item.file)
            setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
            prepare()
            playWhenReady = true
        }
    }

    var playing by remember { mutableStateOf(true) }
    var ended by remember { mutableStateOf(false) }
    var posMs by remember { mutableLongStateOf(0L) }
    var durMs by remember { mutableLongStateOf(0L) }
    var videoRatio by remember { mutableFloatStateOf(16f / 9f) }
    var videoW by remember { mutableIntStateOf(0) }
    var videoH by remember { mutableIntStateOf(0) }

    var controls by remember { mutableStateOf(true) }
    var controlsToken by remember { mutableIntStateOf(0) }
    var fullscreen by remember { mutableStateOf(false) }
    var speedIdx by remember { mutableIntStateOf(2) } // 1.0x
    var showSpeeds by remember { mutableStateOf(false) }
    var loop by remember { mutableStateOf(false) }
    var muted by remember { mutableStateOf(false) }
    var seekBadge by remember { mutableStateOf<Pair<Int, Int>?>(null) } // dir (-1/+1) to token

    var dragging by remember { mutableStateOf(false) }
    var dragMs by remember { mutableLongStateOf(0L) }

    fun bumpControls() {
        controls = true
        controlsToken++
    }

    fun seekBy(deltaMs: Long) {
        val target = (player.currentPosition + deltaMs).coerceIn(0L, durMs.coerceAtLeast(0L))
        player.seekTo(target)
        posMs = target
        if (ended && deltaMs < 0) ended = false
        seekBadge = (if (deltaMs < 0) -1 else 1) to ((seekBadge?.second ?: 0) + 1)
    }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                playing = isPlaying
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                ended = playbackState == Player.STATE_ENDED
                if (ended) controls = true
                if (player.duration > 0) durMs = player.duration
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (videoSize.width > 0 && videoSize.height > 0) {
                    videoW = videoSize.width
                    videoH = videoSize.height
                    videoRatio = (videoSize.width.toFloat() * videoSize.pixelWidthHeightRatio) / videoSize.height.toFloat()
                }
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }

    DisposableEffect(lifecycle, player) {
        val obs = LifecycleEventObserver { _, ev ->
            if (ev == Lifecycle.Event.ON_PAUSE) player.pause()
        }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }

    DisposableEffect(activity, playing) {
        val win = activity?.window
        if (playing) win?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else win?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { win?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }

    DisposableEffect(activity, fullscreen, videoRatio) {
        val win = activity?.window
        if (activity != null && win != null) {
            val ctrl = WindowCompat.getInsetsController(win, win.decorView)
            if (fullscreen) {
                activity.requestedOrientation = if (videoRatio < 1f) {
                    ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                } else {
                    ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                }
                ctrl.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                ctrl.hide(WindowInsetsCompat.Type.systemBars())
            } else {
                activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                ctrl.show(WindowInsetsCompat.Type.systemBars())
            }
        }
        onDispose {
            if (activity != null && win != null) {
                activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                WindowCompat.getInsetsController(win, win.decorView).show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }

    LaunchedEffect(player) {
        while (true) {
            if (!dragging) {
                posMs = player.currentPosition.coerceAtLeast(0L)
                if (player.duration > 0) durMs = player.duration
            }
            delay(200)
        }
    }

    LaunchedEffect(controls, controlsToken, playing, dragging, showSpeeds) {
        if (controls && playing && !dragging && !showSpeeds) {
            delay(3000)
            controls = false
        }
    }

    LaunchedEffect(seekBadge) {
        if (seekBadge != null) {
            delay(650)
            seekBadge = null
        }
    }

    BackHandler {
        when {
            showSpeeds -> showSpeeds = false
            fullscreen -> fullscreen = false
            else -> onClose()
        }
    }

    val shownMs = if (dragging) dragMs else posMs
    val progress = if (durMs > 0L) (shownMs.toFloat() / durMs.toFloat()).coerceIn(0f, 1f) else 0f

    val videoSurface = remember(player) {
        movableContentOf<Modifier> { surfaceModifier ->
            AndroidView(
                factory = { context ->
                    TextureView(context).also { tv -> player.setVideoTextureView(tv) }
                },
                onRelease = { tv -> player.clearVideoTextureView(tv) },
                modifier = surfaceModifier,
            )
        }
    }

    @Composable
    fun VideoViewport(modifier: Modifier) {
        Box(
            modifier
                .background(Color.Black)
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = {
                            if (showSpeeds) showSpeeds = false
                            else {
                                controls = !controls
                                if (controls) controlsToken++
                            }
                        },
                        onDoubleTap = { offset ->
                            if (offset.x < size.width / 2f) seekBy(-10_000L) else seekBy(10_000L)
                        },
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            if (isAudioOnly) {
                Thumb(item.id, Modifier.fillMaxSize()) {
                    item.thumb?.let { runCatching { File(Engine.dir, it).readBytes() }.getOrNull() }
                }
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .45f)))
            } else {
                BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    val ratio = videoRatio.coerceIn(0.25f, 4f)
                    val boxRatio = if (maxHeight.value > 0f) maxWidth.value / maxHeight.value else 16f / 9f
                    val (vw, vh) = if (ratio > boxRatio) {
                        maxWidth to (maxWidth / ratio)
                    } else {
                        (maxHeight * ratio) to maxHeight
                    }
                    videoSurface(Modifier.size(vw, vh))
                }
            }

            AnimatedVisibility(
                seekBadge != null,
                enter = fadeIn(tween(120)) + scaleIn(initialScale = .85f),
                exit = fadeOut(tween(220)) + scaleOut(targetScale = .9f),
                modifier = Modifier
                    .align(if (seekBadge?.first == -1) Alignment.CenterStart else Alignment.CenterEnd)
                    .padding(horizontal = 36.dp),
            ) {
                Box(
                    Modifier
                        .clip(RoundedCornerShape(24.dp))
                        .background(Color.Black.copy(alpha = .68f))
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                ) {
                    Text(
                        if (seekBadge?.first == -1) "-10s" else "+10s",
                        color = Color.White,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }

            AnimatedVisibility(
                controls,
                enter = fadeIn(tween(180)),
                exit = fadeOut(tween(220)),
                modifier = Modifier.fillMaxSize(),
            ) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                listOf(
                                    Color.Black.copy(alpha = .72f),
                                    Color.Black.copy(alpha = .22f),
                                    Color.Black.copy(alpha = .82f),
                                )
                            )
                        )
                ) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .align(Alignment.TopCenter)
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            Modifier
                                .pressable {
                                    if (fullscreen) fullscreen = false else onClose()
                                }
                                .padding(8.dp)
                        ) { PIcon(PI.Back, Color.White, 24.dp) }

                        Text(
                            item.title,
                            color = Color.White,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f).padding(horizontal = 6.dp),
                        )

                        Box(
                            Modifier
                                .pressable {
                                    bumpControls()
                                    showSpeeds = !showSpeeds
                                }
                                .clip(RoundedCornerShape(10.dp))
                                .background(Color.White.copy(alpha = if (speedIdx != 2) .24f else .14f))
                                .padding(horizontal = 10.dp, vertical = 5.dp),
                        ) {
                            Text(fmtSpeed(SPEEDS[speedIdx]), color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        }

                        Spacer(Modifier.width(6.dp))

                        Box(
                            Modifier
                                .pressable {
                                    bumpControls()
                                    muted = !muted
                                    player.volume = if (muted) 0f else 1f
                                }
                                .padding(6.dp),
                        ) { PIcon(if (muted) PI.VolOff else PI.VolOn, Color.White, 20.dp) }

                        Box(
                            Modifier
                                .pressable {
                                    bumpControls()
                                    loop = !loop
                                    player.repeatMode = if (loop) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
                                }
                                .padding(6.dp),
                        ) { PIcon(PI.Loop, if (loop) C.Red else Color.White.copy(alpha = .78f), 20.dp) }
                    }

                    Row(
                        Modifier.align(Alignment.Center),
                        horizontalArrangement = Arrangement.spacedBy(32.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .background(Color.Black.copy(alpha = .42f))
                                .pressable { bumpControls(); seekBy(-10_000L) },
                            contentAlignment = Alignment.Center,
                        ) { PIcon(PI.Rew10, Color.White, 22.dp) }

                        Box(
                            Modifier
                                .size(62.dp)
                                .clip(CircleShape)
                                .background(Color.Black.copy(alpha = .58f))
                                .pressable {
                                    bumpControls()
                                    if (ended) {
                                        player.seekTo(0L)
                                        player.play()
                                        ended = false
                                    } else if (player.isPlaying) {
                                        player.pause()
                                    } else {
                                        player.play()
                                    }
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            PIcon(
                                when {
                                    ended -> PI.Replay
                                    playing -> PI.Pause
                                    else -> PI.Play
                                },
                                Color.White,
                                30.dp,
                            )
                        }

                        Box(
                            Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .background(Color.Black.copy(alpha = .42f))
                                .pressable { bumpControls(); seekBy(10_000L) },
                            contentAlignment = Alignment.Center,
                        ) { PIcon(PI.Fwd10, Color.White, 22.dp) }
                    }

                    Column(
                        Modifier
                            .fillMaxWidth()
                            .align(Alignment.BottomCenter)
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                    ) {
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                "${Ts.clock(shownMs / 1000)} / ${Ts.clock(durMs / 1000)}",
                                color = Color.White,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                            )
                            Box(
                                Modifier
                                    .pressable {
                                        bumpControls()
                                        fullscreen = !fullscreen
                                    }
                                    .padding(6.dp),
                            ) { PIcon(if (fullscreen) PI.ExitFull else PI.Full, Color.White, 20.dp) }
                        }

                        var barWidth by remember { mutableIntStateOf(1) }
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(26.dp)
                                .onSizeChanged { barWidth = it.width.coerceAtLeast(1) }
                                .pointerInput(durMs) {
                                    detectTapGestures { offset ->
                                        if (durMs > 0L) {
                                            val p = (offset.x / barWidth.toFloat()).coerceIn(0f, 1f)
                                            val target = (p * durMs).toLong()
                                            player.seekTo(target)
                                            posMs = target
                                            bumpControls()
                                        }
                                    }
                                }
                                .pointerInput(durMs) {
                                    detectHorizontalDragGestures(
                                        onDragStart = { offset ->
                                            dragging = true
                                            val p = (offset.x / barWidth.toFloat()).coerceIn(0f, 1f)
                                            dragMs = (p * durMs).toLong()
                                        },
                                        onHorizontalDrag = { change, _ ->
                                            change.consume()
                                            val p = (change.position.x / barWidth.toFloat()).coerceIn(0f, 1f)
                                            dragMs = (p * durMs).toLong()
                                        },
                                        onDragEnd = {
                                            player.seekTo(dragMs)
                                            posMs = dragMs
                                            dragging = false
                                            bumpControls()
                                        },
                                        onDragCancel = { dragging = false },
                                    )
                                },
                            contentAlignment = Alignment.CenterStart,
                        ) {
                            Canvas(Modifier.fillMaxWidth().height(16.dp)) {
                                val cy = size.height / 2f
                                val trackH = if (dragging) 5.dp.toPx() else 3.5.dp.toPx()
                                val r = CornerRadius(trackH / 2f)
                                drawRoundRect(
                                    Color.White.copy(alpha = .32f),
                                    Offset(0f, cy - trackH / 2f),
                                    Size(size.width, trackH),
                                    r,
                                )
                                val fillW = size.width * progress
                                drawRoundRect(
                                    C.Red,
                                    Offset(0f, cy - trackH / 2f),
                                    Size(fillW, trackH),
                                    r,
                                )
                                val thumbR = if (dragging) 8.dp.toPx() else 6.dp.toPx()
                                drawCircle(C.Red, thumbR, Offset(fillW.coerceIn(thumbR, size.width - thumbR), cy))
                            }
                        }
                    }
                }
            }

            AnimatedVisibility(
                showSpeeds,
                enter = fadeIn(tween(150)) + scaleIn(initialScale = .92f),
                exit = fadeOut(tween(120)) + scaleOut(targetScale = .92f),
                modifier = Modifier.align(Alignment.TopEnd).padding(top = 48.dp, end = 12.dp),
            ) {
                Row(
                    Modifier
                        .clip(RoundedCornerShape(14.dp))
                        .background(C.Surface.copy(alpha = .95f))
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    SPEEDS.forEachIndexed { idx, sp ->
                        val active = idx == speedIdx
                        Box(
                            Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (active) C.Red else Color.Transparent)
                                .pressable {
                                    speedIdx = idx
                                    player.playbackParameters = PlaybackParameters(sp)
                                    showSpeeds = false
                                    bumpControls()
                                }
                                .padding(horizontal = 9.dp, vertical = 6.dp),
                        ) {
                            Text(
                                fmtSpeed(sp),
                                color = if (active) Color.White else C.Text,
                                fontSize = 12.sp,
                                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                            )
                        }
                    }
                }
            }
        }
    }

    if (fullscreen) {
        VideoViewport(Modifier.fillMaxSize())
    } else {
        Column(
            Modifier
                .fillMaxSize()
                .background(C.Bg)
                .statusBarsPadding(),
        ) {
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val ratio = if (isAudioOnly) 16f / 9f else videoRatio.coerceIn(0.45f, 3f)
                val targetH = (maxWidth / ratio).coerceAtMost(520.dp)
                VideoViewport(Modifier.fillMaxWidth().height(targetH))
            }

            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 18.dp)) {
                Text(
                    item.title,
                    color = C.Text,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(8.dp))
                val resTag = if (!isAudioOnly && videoW > 0 && videoH > 0) "${videoW}×${videoH}" else item.label
                Text(
                    "${item.label}  •  $resTag  •  ${fmtSize(item.bytes)}",
                    color = C.Muted,
                    fontSize = 13.sp,
                )

                Spacer(Modifier.height(20.dp))
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Chip(
                        label = when {
                            savingGallery -> "Saving…"
                            savedToGallery -> "In Gallery"
                            else -> "Show in Gallery"
                        },
                        selected = savedToGallery,
                    ) { requestExport() }
                    Chip("Speed ${fmtSpeed(SPEEDS[speedIdx])}", speedIdx != 2) {
                        speedIdx = (speedIdx + 1) % SPEEDS.size
                        player.playbackParameters = PlaybackParameters(SPEEDS[speedIdx])
                    }
                    Chip(if (loop) "Loop On" else "Loop", loop) {
                        loop = !loop
                        player.repeatMode = if (loop) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
                    }
                    Chip("Fullscreen", false) { fullscreen = true }
                }
            }
        }
    }
}
