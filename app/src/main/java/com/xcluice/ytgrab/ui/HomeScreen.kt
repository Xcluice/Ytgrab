package com.xcluice.ytgrab.ui

import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xcluice.ytgrab.Engine
import com.xcluice.ytgrab.Extractor
import com.xcluice.ytgrab.Net
import com.xcluice.ytgrab.Ts
import com.xcluice.ytgrab.VideoInfo
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

sealed interface Load {
    data object Idle : Load
    data object Busy : Load
    data class Ready(val info: VideoInfo) : Load
    data class Fail(val msg: String) : Load
}

class HomeVM : ViewModel() {
    var url by mutableStateOf("")
    var load by mutableStateOf<Load>(Load.Idle)
    var quality by mutableStateOf(0)
    var digits by mutableStateOf("")
    private var fetchJob: Job? = null

    fun onUrl(s: String) {
        url = s
        fetchJob?.cancel()
        if (s.isBlank()) { load = Load.Idle; return }
        if (!Extractor.looksLikeYoutube(s)) { load = Load.Fail("That doesn't look like a YouTube link."); return }
        load = Load.Busy
        fetchJob = viewModelScope.launch {
            delay(450)
            load = try {
                val info = Extractor.fetch(s)
                quality = when {
                    360 in info.heights -> 360
                    else -> info.heights.lastOrNull { it <= 360 } ?: info.heights.firstOrNull() ?: 0
                }
                Load.Ready(info)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                Load.Fail(
                    if (e is java.io.IOException) "No connection. Check your internet and try again."
                    else e.message?.take(140) ?: "Couldn't read that video."
                )
            }
        }
    }

    val startSec: Long? get() = Ts.seconds(digits)

    val tsError: String?
        get() {
            if (digits.isEmpty()) return null
            val s = startSec ?: return "Minutes and seconds must be under 60."
            val info = (load as? Load.Ready)?.info ?: return null
            if (info.duration > 0 && s >= info.duration - 1) return "This video is only ${Ts.clock(info.duration)} long."
            return null
        }

    fun download() {
        val info = (load as? Load.Ready)?.info ?: return
        Engine.start(info, if (quality == 0) null else quality, startSec ?: 0L)
    }
}

@Composable
private fun Label(t: String) =
    Text(t, color = C.Muted, fontSize = 13.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 24.dp, bottom = 10.dp))

@Composable
fun Header(title: String) {
    Row(Modifier.padding(top = 18.dp), verticalAlignment = Alignment.CenterVertically) {
        Logo(44.dp)
        Spacer(Modifier.width(12.dp))
        Text(title, fontSize = 30.sp, fontWeight = FontWeight.SemiBold, color = C.Text)
    }
}

@Composable
fun HomeScreen(vm: HomeVM, onGoDownloads: () -> Unit) {
    val job by Engine.job.collectAsState()
    val clip = LocalClipboardManager.current
    val load = vm.load

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
        Header("Home")
        Spacer(Modifier.height(20.dp))

        Field(
            value = vm.url, onValue = vm::onUrl, hint = "Paste a YouTube link",
            error = load is Load.Fail,
            keyboard = KeyboardOptions(keyboardType = KeyboardType.Uri),
            trailing = {
                Box(Modifier.pressable {
                    if (vm.url.isEmpty()) clip.getText()?.text?.let(vm::onUrl) else vm.onUrl("")
                }.padding(4.dp)) {
                    AnimatedContent(vm.url.isEmpty(), transitionSpec = {
                        (fadeIn(tween(200)) + scaleIn(initialScale = .6f)) togetherWith fadeOut(tween(120))
                    }, label = "pc") { empty ->
                        if (empty) Glyph(G.Paste, C.Muted, 22.dp) else Glyph(G.Close, C.Muted, 20.dp)
                    }
                }
            },
        )

        AnimatedVisibility(load is Load.Fail, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
            Text((load as? Load.Fail)?.msg ?: "", color = C.Red, fontSize = 13.sp, modifier = Modifier.padding(top = 10.dp, start = 4.dp))
        }

        AnimatedVisibility(load is Load.Busy, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
            val a by rememberInfiniteTransition(label = "sk").animateFloat(
                .35f, .9f, infiniteRepeatable(tween(800), RepeatMode.Reverse), label = "ska",
            )
            Row(Modifier.padding(top = 18.dp).graphicsLayer { alpha = a }) {
                Box(Modifier.size(128.dp, 72.dp).clip(RoundedCornerShape(14.dp)).background(C.Surface2))
                Spacer(Modifier.width(14.dp))
                Column(Modifier.padding(top = 6.dp)) {
                    Box(Modifier.size(150.dp, 14.dp).clip(RoundedCornerShape(7.dp)).background(C.Surface2))
                    Spacer(Modifier.height(10.dp))
                    Box(Modifier.size(90.dp, 12.dp).clip(RoundedCornerShape(6.dp)).background(C.Surface2))
                }
            }
        }

        AnimatedVisibility(
            load is Load.Ready,
            enter = fadeIn(tween(350)) + expandVertically(spring(.8f, Spring.StiffnessLow)) + slideInVertically { it / 8 },
            exit = fadeOut() + shrinkVertically(),
        ) {
            val info = (load as? Load.Ready)?.info
            if (info != null) Column {
                Row(Modifier.padding(top = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                    Thumb(info.thumb ?: info.url, Modifier.size(128.dp, 72.dp).clip(RoundedCornerShape(14.dp))) {
                        info.thumb?.let(Net::bytes)
                    }
                    Spacer(Modifier.width(14.dp))
                    Column {
                        Text(info.title, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        if (info.duration > 0) Text(Ts.clock(info.duration), color = C.Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
                    }
                }

                Label("Quality")
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    info.heights.forEach { h -> Chip("${h}p", vm.quality == h) { vm.quality = h } }
                    Chip("Audio", vm.quality == 0) { vm.quality = 0 }
                }
            }
        }

        Label("Start timestamp")
        Field(
            value = Ts.format(vm.digits),
            onValue = { vm.digits = it.filter(Char::isDigit).trimStart('0').take(6) },
            hint = "0:00 (e.g. 135 for 1:35)",
            error = vm.tsError != null,
            keyboard = KeyboardOptions(keyboardType = KeyboardType.Number),
            trailing = if (vm.digits.isNotEmpty()) {
                {
                    Box(Modifier.pressable { vm.digits = "" }.padding(4.dp)) {
                        Glyph(G.Close, C.Muted, 20.dp)
                    }
                }
            } else null,
        )
        AnimatedContent(
            vm.tsError ?: "Optional. Downloads only from this timestamp to save data (may start a few seconds early).",
            transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(150)) },
            label = "tsmsg",
        ) { m ->
            Text(m, color = if (vm.tsError != null) C.Red else C.Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp, start = 4.dp))
        }

        Spacer(Modifier.height(26.dp))

        val kind = when {
            job.active -> 1
            job.finished -> 2
            job.error != null -> 3
            else -> 0
        }
        AnimatedContent(kind, transitionSpec = {
            (fadeIn(tween(300)) + slideInVertically { it / 4 }) togetherWith fadeOut(tween(150))
        }, label = "action") { k ->
            when (k) {
                1 -> Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(C.Surface).padding(18.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(job.phase, fontSize = 14.sp, color = C.Text, fontWeight = FontWeight.Medium)
                        Text("${(job.progress * 100).toInt()}%", fontSize = 14.sp, color = C.Teal, fontWeight = FontWeight.SemiBold)
                    }
                    Spacer(Modifier.height(14.dp))
                    Bar(job.progress)
                    Spacer(Modifier.height(16.dp))
                    Box(
                        Modifier.pressable { Engine.cancel() }.clip(RoundedCornerShape(12.dp)).background(C.Surface2)
                            .padding(horizontal = 16.dp, vertical = 9.dp)
                    ) { Text("Cancel", color = C.Muted, fontSize = 14.sp) }
                }
                2 -> Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(C.Surface).padding(18.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(34.dp).clip(RoundedCornerShape(17.dp)).background(C.Teal.copy(alpha = .18f)), contentAlignment = Alignment.Center) {
                            Glyph(G.Check, C.Teal, 20.dp, 2.4f)
                        }
                        Spacer(Modifier.width(12.dp))
                        Text("Saved to Downloads", fontSize = 15.sp, fontWeight = FontWeight.Medium)
                    }
                    Spacer(Modifier.height(16.dp))
                    Btn("View downloads", color = C.Teal) { Engine.ack(); onGoDownloads() }
                }
                3 -> Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(C.Surface).padding(18.dp)) {
                    Text("Download failed", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = C.Red)
                    Text(job.error ?: "", fontSize = 13.sp, color = C.Muted, modifier = Modifier.padding(top = 6.dp, bottom = 16.dp))
                    Btn("Try again") { Engine.ack() }
                }
                else -> {
                    val ready = load is Load.Ready && vm.tsError == null
                    val label = when {
                        !ready -> "Download"
                        vm.quality == 0 && vm.digits.isNotEmpty() -> "Download audio from ${Ts.format(vm.digits)}"
                        vm.quality == 0 -> "Download audio"
                        vm.digits.isNotEmpty() -> "Download ${vm.quality}p from ${Ts.format(vm.digits)}"
                        else -> "Download ${vm.quality}p"
                    }
                    Btn(label, enabled = ready) { vm.download() }
                }
            }
        }
        Spacer(Modifier.height(120.dp))
    }
}
