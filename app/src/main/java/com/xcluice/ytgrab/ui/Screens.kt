package com.xcluice.ytgrab.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.xcluice.ytgrab.Engine
import com.xcluice.ytgrab.Update
import com.xcluice.ytgrab.Updater
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

@Composable
fun Splash(onDone: () -> Unit) {
    val t = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        t.animateTo(1f, tween(1100, easing = FastOutSlowInEasing))
        delay(450)
        onDone()
    }
    Box(Modifier.fillMaxSize().background(C.Bg), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(260.dp)) {
            val r = size.minDimension / 2
            val p = t.value
            drawCircle(C.Teal.copy(alpha = (1f - p) * .35f), r * (.35f + .65f * p), style = Stroke(2.dp.toPx()))
            drawCircle(C.Red.copy(alpha = (1f - p) * .25f), r * (.2f + .5f * p), style = Stroke(2.dp.toPx()))
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Logo(120.dp, t.value)
            Spacer(Modifier.height(18.dp))
            val a = ((t.value - .65f) / .35f).coerceIn(0f, 1f)
            Text(
                "Ytgrab", fontSize = 28.sp, fontWeight = FontWeight.SemiBold, color = C.Text,
                modifier = Modifier.graphicsLayer { alpha = a; translationY = (1f - a) * 24f },
            )
        }
    }
}

@Composable
fun ForceUpdate(req: Update.Required) {
    BackHandler(true) {}
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var phase by remember { mutableIntStateOf(0) } // 0 idle, 1 downloading, 2 ready, 3 error
    var progress by remember { mutableStateOf(0f) }
    var note by remember { mutableStateOf("") }
    var file by remember { mutableStateOf<File?>(null) }

    val inf = rememberInfiniteTransition(label = "pulse")
    val p1 by inf.animateFloat(0f, 1f, infiniteRepeatable(tween(2200, easing = LinearEasing)), label = "p1")
    val p2 by inf.animateFloat(0f, 1f, infiniteRepeatable(tween(2200, easing = LinearEasing), initialStartOffset = androidx.compose.animation.core.StartOffset(1100)), label = "p2")
    val bob by inf.animateFloat(-6f, 6f, infiniteRepeatable(tween(1300, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "bob")

    Column(
        Modifier.fillMaxSize().background(C.Bg).systemBarsPadding().padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
    ) {
        Box(Modifier.size(200.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val r = size.minDimension / 2
                listOf(p1, p2).forEach { p ->
                    drawCircle(C.Teal.copy(alpha = (1f - p) * .4f), r * (.45f + .55f * p), style = Stroke(2.dp.toPx()))
                }
            }
            Box(
                Modifier.size(92.dp).graphicsLayer { translationY = bob }.clip(RoundedCornerShape(30.dp)).background(C.Surface),
                contentAlignment = Alignment.Center,
            ) { Glyph(G.Down, C.Teal, 42.dp, 2.4f) }
        }
        Spacer(Modifier.height(28.dp))
        Text("Update required", fontSize = 28.sp, fontWeight = FontWeight.SemiBold)
        Text(
            "Ytgrab ${req.version} is out. This version stops working when YouTube changes, so you need to update to keep downloading.",
            color = C.Muted, fontSize = 15.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 12.dp, bottom = 30.dp),
        )
        AnimatedContent(phase, transitionSpec = { fadeIn(tween(250)) togetherWith fadeOut(tween(150)) }, label = "upd") { ph ->
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                when (ph) {
                    1 -> {
                        Bar(progress)
                        Text("Downloading ${(progress * 100).toInt()}%", color = C.Muted, fontSize = 14.sp, modifier = Modifier.padding(top = 12.dp))
                    }
                    else -> {
                        Btn(if (ph == 2) "Install update" else if (ph == 3) "Try again" else "Update now") {
                            scope.launch {
                                if (ph == 2 && file != null) {
                                    note = if (Updater.install(ctx, file!!)) "" else "Allow installs for Ytgrab, then come back and tap Install update."
                                } else {
                                    phase = 1; progress = 0f; note = ""
                                    try {
                                        val f = Updater.download(ctx, req.apkUrl) { progress = it }
                                        file = f
                                        phase = 2
                                        if (!Updater.install(ctx, f)) note = "Allow installs for Ytgrab, then come back and tap Install update."
                                    } catch (e: Exception) {
                                        phase = 3
                                        note = "Download failed. Check your connection and try again."
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        AnimatedVisibility(note.isNotEmpty(), enter = fadeIn(), exit = fadeOut()) {
            Text(note, color = C.Muted, fontSize = 13.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 14.dp))
        }
    }
}

@Composable
fun Root() {
    var splash by remember { mutableStateOf(true) }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var prev by remember { mutableIntStateOf(0) }
    val upd by Updater.state.collectAsState()
    val job by Engine.job.collectAsState()
    val vm: HomeVM = viewModel()
    LaunchedEffect(Unit) { Updater.check() }

    fun go(t: Int) { prev = tab; tab = t }

    Box(Modifier.fillMaxSize().background(C.Bg)) {
        Box(Modifier.fillMaxSize().systemBarsPadding().imePadding()) {
            AnimatedContent(
                tab,
                transitionSpec = {
                    val dir = if (targetState > initialState) 1 else -1
                    (slideInHorizontally(tween(380, easing = FastOutSlowInEasing)) { it / 3 * dir } + fadeIn(tween(300))) togetherWith
                        (slideOutHorizontally(tween(380, easing = FastOutSlowInEasing)) { -it / 3 * dir } + fadeOut(tween(200)))
                },
                label = "tabs",
            ) { t ->
                if (t == 0) HomeScreen(vm) { go(1) } else DownloadsScreen { go(0) }
            }
            Box(Modifier.align(Alignment.BottomCenter)) { NavPill(tab, job.active) { go(it) } }
        }

        AnimatedVisibility(upd is Update.Required, enter = fadeIn(tween(400)) + scaleIn(initialScale = 1.06f), exit = fadeOut()) {
            val r = upd
            if (r is Update.Required) ForceUpdate(r)
        }
        AnimatedVisibility(splash, exit = fadeOut(tween(450)) + scaleOut(targetScale = 1.08f, animationSpec = tween(450))) {
            Splash { splash = false }
        }
    }
}
