package com.xcluice.ytgrab.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.xcluice.ytgrab.DlItem
import com.xcluice.ytgrab.Engine
import java.io.File
import java.util.Locale

private fun size(b: Long): String =
    if (b >= 1_000_000_000) String.format(Locale.US, "%.1f GB", b / 1e9) else String.format(Locale.US, "%.0f MB", b / 1e6)

private fun play(ctx: android.content.Context, item: DlItem) {
    val f = File(Engine.dir, item.file)
    val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", f)
    val mime = if (item.label == "Audio") "audio/mp4" else "video/mp4"
    try {
        ctx.startActivity(
            Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(ctx, "No app found to play this file", Toast.LENGTH_SHORT).show()
    }
}

@Composable
fun DownloadsScreen(onGoHome: () -> Unit) {
    val items by Engine.items.collectAsState()
    val job by Engine.job.collectAsState()
    val ctx = LocalContext.current
    var confirm by remember { mutableStateOf<DlItem?>(null) }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
            Header("Downloads")
            Spacer(Modifier.height(18.dp))

            if (items.isEmpty() && !job.active) {
                Empty(onGoHome)
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 120.dp)) {
                    if (job.active) item(key = "active") {
                        Row(
                            Modifier.fillMaxWidth().animateItem().clip(RoundedCornerShape(22.dp)).background(C.Surface).padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Ring(job.progress, 56.dp)
                                Text("${(job.progress * 100).toInt()}", fontSize = 13.sp, color = C.Teal, fontWeight = FontWeight.SemiBold)
                            }
                            Spacer(Modifier.width(16.dp))
                            Column(Modifier.weight(1f)) {
                                Text(job.title, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(job.phase, fontSize = 13.sp, color = C.Muted, modifier = Modifier.padding(top = 4.dp))
                            }
                            Box(Modifier.pressable { Engine.cancel() }.padding(8.dp)) { Glyph(G.Close, C.Muted, 20.dp) }
                        }
                    }
                    items(items, key = { it.id }) { item ->
                        Row(
                            Modifier.fillMaxWidth().animateItem().clip(RoundedCornerShape(22.dp)).background(C.Surface)
                                .pressable { play(ctx, item) }.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Thumb(item.id, Modifier.size(112.dp, 70.dp).clip(RoundedCornerShape(14.dp))) {
                                item.thumb?.let { runCatching { File(Engine.dir, it).readBytes() }.getOrNull() }
                            }
                            Spacer(Modifier.width(14.dp))
                            Column(Modifier.weight(1f)) {
                                Text(item.title, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text("${item.label}  ${size(item.bytes)}", fontSize = 13.sp, color = C.Muted, modifier = Modifier.padding(top = 4.dp))
                            }
                            Spacer(Modifier.width(8.dp))
                            Box(
                                Modifier.pressable { confirm = item }.size(42.dp).clip(RoundedCornerShape(21.dp))
                                    .background(C.Red.copy(alpha = .16f)),
                                contentAlignment = Alignment.Center,
                            ) { Glyph(G.Trash, C.Red, 20.dp) }
                        }
                    }
                }
            }
        }

        AnimatedVisibility(confirm != null, enter = fadeIn(tween(200)), exit = fadeOut(tween(200))) {
            val target = confirm
            Box(
                Modifier.fillMaxSize().background(Color.Black.copy(alpha = .6f))
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { confirm = null },
                contentAlignment = Alignment.Center,
            ) {
                AnimatedVisibility(true, enter = scaleIn(initialScale = .85f) + fadeIn(), exit = scaleOut() + fadeOut()) {
                    Column(
                        Modifier.padding(32.dp).clip(RoundedCornerShape(26.dp)).background(C.Surface)
                            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                            .padding(22.dp)
                    ) {
                        Text("Delete this download?", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                        Text(target?.title ?: "", fontSize = 14.sp, color = C.Muted, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp, bottom = 20.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Box(Modifier.weight(1f).pressable { confirm = null }.clip(RoundedCornerShape(16.dp)).background(C.Surface2).padding(vertical = 14.dp), contentAlignment = Alignment.Center) {
                                Text("Keep", color = C.Text, fontWeight = FontWeight.Medium)
                            }
                            Box(Modifier.weight(1f).pressable { target?.let(Engine::delete); confirm = null }.clip(RoundedCornerShape(16.dp)).background(C.Red).padding(vertical = 14.dp), contentAlignment = Alignment.Center) {
                                Text("Delete", color = Color.White, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Empty(onGoHome: () -> Unit) {
    val y by rememberInfiniteTransition(label = "float").animateFloat(
        -8f, 8f, infiniteRepeatable(tween(1400, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "fy",
    )
    Column(Modifier.fillMaxSize().padding(bottom = 100.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Box(Modifier.offset(y = y.dp).size(84.dp).clip(RoundedCornerShape(28.dp)).background(C.Surface), contentAlignment = Alignment.Center) {
            Glyph(G.Down, C.Teal, 38.dp, 2.4f)
        }
        Spacer(Modifier.height(22.dp))
        Text("Nothing downloaded yet", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        Text("Paste a YouTube link on Home to save a video.", color = C.Muted, fontSize = 14.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp, bottom = 22.dp))
        Box(Modifier.pressable { onGoHome() }.clip(RoundedCornerShape(16.dp)).background(C.Surface2).padding(horizontal = 22.dp, vertical = 13.dp)) {
            Text("Go to Home", fontWeight = FontWeight.Medium)
        }
    }
}
