package com.xcluice.ytgrab.ui

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.animateDpAsState
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.composed
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.runtime.collectAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object C {
    val Bg = Color(0xFF0F1011)
    val Surface = Color(0xFF17191B)
    val Surface2 = Color(0xFF202326)
    val Line = Color(0xFF2B2F33)
    val Text = Color(0xFFECEDEE)
    val Muted = Color(0xFF8B9096)
    val Red = Color(0xFFC8453B)
    val Teal = Color(0xFF4FA79B)
}

@Composable
fun YtTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = darkColorScheme(background = C.Bg, surface = C.Surface, onSurface = C.Text, primary = C.Red)) {
        CompositionLocalProvider(
            LocalTextSelectionColors provides TextSelectionColors(C.Red, C.Red.copy(alpha = .35f)),
            LocalContentColor provides C.Text,
            content = content,
        )
    }
}

/** Tap = spring squish. No ripple, no highlight. */
fun Modifier.pressable(enabled: Boolean = true, onClick: () -> Unit): Modifier = composed {
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val s by animateFloatAsState(
        if (pressed && enabled) .94f else 1f,
        spring(dampingRatio = .5f, stiffness = Spring.StiffnessMedium), label = "press",
    )
    this
        .graphicsLayer { scaleX = s; scaleY = s }
        .clickable(interactionSource = src, indication = null, enabled = enabled, onClick = onClick)
}

enum class G { Home, Down, Trash, Paste, Close, Play, Check }

@Composable
fun Glyph(g: G, tint: Color, size: Dp = 22.dp, strokeDp: Float = 2f) {
    Canvas(Modifier.size(size)) {
        val w = this.size.width
        val st = Stroke(width = strokeDp.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        fun Path.m(x: Float, y: Float) = moveTo(x * w, y * w)
        fun Path.l(x: Float, y: Float) = lineTo(x * w, y * w)
        when (g) {
            G.Home -> drawPath(Path().apply {
                m(.1f, .48f); l(.5f, .14f); l(.9f, .48f)
                m(.2f, .42f); l(.2f, .86f); l(.8f, .86f); l(.8f, .42f)
            }, tint, style = st)
            G.Down -> drawPath(Path().apply {
                m(.5f, .12f); l(.5f, .68f)
                m(.27f, .46f); l(.5f, .7f); l(.73f, .46f)
                m(.2f, .88f); l(.8f, .88f)
            }, tint, style = st)
            G.Trash -> drawPath(Path().apply {
                m(.18f, .27f); l(.82f, .27f)
                m(.4f, .27f); l(.4f, .14f); l(.6f, .14f); l(.6f, .27f)
                m(.26f, .27f); l(.32f, .87f); l(.68f, .87f); l(.74f, .27f)
            }, tint, style = st)
            G.Paste -> {
                drawRoundRect(tint, Offset(.2f * w, .18f * w), Size(.6f * w, .72f * w), CornerRadius(.1f * w), style = st)
                drawPath(Path().apply { m(.38f, .1f); l(.62f, .1f) }, tint, style = st)
            }
            G.Close -> drawPath(Path().apply {
                m(.22f, .22f); l(.78f, .78f); m(.78f, .22f); l(.22f, .78f)
            }, tint, style = st)
            G.Play -> drawPath(Path().apply {
                m(.3f, .2f); l(.3f, .8f); l(.8f, .5f); close()
            }, tint, style = Fill)
            G.Check -> drawPath(Path().apply { m(.18f, .52f); l(.42f, .74f); l(.82f, .28f) }, tint, style = st)
        }
    }
}

/** t = 0..1 build-in animation. 1 = finished logo. */
@Composable
fun Logo(size: Dp, t: Float = 1f) {
    Canvas(Modifier.size(size)) {
        val w = this.size.width
        val a1 = (t / .45f).coerceIn(0f, 1f)
        val a2 = ((t - .3f) / .35f).coerceIn(0f, 1f)
        val a3 = ((t - .6f) / .4f).coerceIn(0f, 1f)
        val cx = .5f * w
        val cy = .4f * w
        val sc = .6f + .4f * a1
        val rw = .78f * w * sc
        val rh = .56f * w * sc
        drawRoundRect(
            C.Red.copy(alpha = a1), Offset(cx - rw / 2, cy - rh / 2), Size(rw, rh), CornerRadius(.14f * w * sc),
        )
        val p = Path().apply {
            moveTo(cx - .07f * w, cy - .13f * w); lineTo(cx - .07f * w, cy + .13f * w); lineTo(cx + .15f * w, cy); close()
        }
        val ts = .3f + .7f * a2
        scale(ts, Offset(cx, cy)) { drawPath(p, Color.White.copy(alpha = a2)) }
        val dy = -(1f - a3) * .12f * w
        val st = Stroke(.055f * w, cap = StrokeCap.Round, join = StrokeJoin.Round)
        drawPath(Path().apply {
            moveTo(.5f * w, .7f * w + dy); lineTo(.5f * w, .88f * w + dy)
            moveTo(.39f * w, .78f * w + dy); lineTo(.5f * w, .9f * w + dy); lineTo(.61f * w, .78f * w + dy)
        }, C.Teal.copy(alpha = a3), style = st)
    }
}

@Composable
fun Chip(label: String, selected: Boolean, onClick: () -> Unit) {
    val bg by animateColorAsState(if (selected) C.Red else C.Surface2, tween(250), label = "cbg")
    val fg by animateColorAsState(if (selected) Color.White else C.Muted, tween(250), label = "cfg")
    val sc by animateFloatAsState(if (selected) 1.06f else 1f, spring(.45f, 400f), label = "csc")
    Box(
        Modifier
            .graphicsLayer { scaleX = sc; scaleY = sc }
            .pressable(onClick = onClick)
            .clip(RoundedCornerShape(14.dp))
            .background(bg)
            .padding(horizontal = 18.dp, vertical = 11.dp)
    ) { Text(label, color = fg, fontSize = 14.sp, fontWeight = FontWeight.Medium) }
}

@Composable
fun Btn(text: String, enabled: Boolean = true, color: Color = C.Red, onClick: () -> Unit) {
    val bg by animateColorAsState(if (enabled) color else C.Surface2, tween(300), label = "bbg")
    val fg by animateColorAsState(if (enabled) Color.White else C.Muted, tween(300), label = "bfg")
    Box(
        Modifier.fillMaxWidth().pressable(enabled, onClick).clip(RoundedCornerShape(18.dp)).background(bg).padding(vertical = 17.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, color = fg, fontSize = 16.sp, fontWeight = FontWeight.SemiBold) }
}

@Composable
fun Bar(p: Float, color: Color = C.Teal) {
    val a by animateFloatAsState(p, tween(300), label = "bar")
    val x by rememberInfiniteTransition(label = "sweep").animateFloat(
        0f, 700f, infiniteRepeatable(tween(1400, easing = LinearEasing)), label = "sx",
    )
    Box(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(C.Line)) {
        Box(
            Modifier.fillMaxWidth(a.coerceIn(0f, 1f)).fillMaxHeight().clip(RoundedCornerShape(4.dp)).background(
                Brush.linearGradient(
                    listOf(color.copy(alpha = .7f), color, color.copy(alpha = .7f)),
                    start = Offset(x - 300f, 0f), end = Offset(x, 0f),
                )
            )
        )
    }
}

@Composable
fun Ring(progress: Float, size: Dp, color: Color = C.Teal) {
    val p by animateFloatAsState(progress, tween(300), label = "ring")
    Canvas(Modifier.size(size)) {
        val sw = 4.dp.toPx()
        val tl = Offset(sw / 2, sw / 2)
        val sz = Size(this.size.width - sw, this.size.height - sw)
        drawArc(C.Line, 0f, 360f, false, tl, sz, style = Stroke(sw))
        drawArc(color, -90f, 360f * p.coerceIn(0f, 1f), false, tl, sz, style = Stroke(sw, cap = StrokeCap.Round))
    }
}

@Composable
fun Field(
    value: String, onValue: (String) -> Unit, hint: String, error: Boolean = false,
    keyboard: KeyboardOptions = KeyboardOptions.Default, trailing: @Composable (() -> Unit)? = null,
) {
    val border by animateColorAsState(if (error) C.Red else C.Line, tween(250), label = "fb")
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(C.Surface)
            .border(1.dp, border, RoundedCornerShape(18.dp)).padding(horizontal = 16.dp, vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) Text(hint, color = C.Muted, fontSize = 16.sp)
            BasicTextField(
                value = TextFieldValue(value, TextRange(value.length)),
                onValueChange = { onValue(it.text) },
                singleLine = true,
                textStyle = TextStyle(color = C.Text, fontSize = 16.sp),
                cursorBrush = SolidColor(C.Red),
                keyboardOptions = keyboard,
            )
        }
        trailing?.invoke()
    }
}

object ImgCache { val lru = LruCache<String, ImageBitmap>(48) }

@Composable
fun Thumb(key: String, modifier: Modifier, loader: () -> ByteArray?) {
    val bmp by produceState<ImageBitmap?>(ImgCache.lru.get(key), key) {
        if (value == null) {
            value = withContext(Dispatchers.IO) {
                loader()?.let { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() }
            }?.also { ImgCache.lru.put(key, it) }
        }
    }
    Box(modifier.background(C.Surface2)) {
        AnimatedVisibility(bmp != null, enter = fadeIn(tween(450))) {
            bmp?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
        }
    }
}

@Composable
fun NavPill(tab: Int, activeDot: Boolean, onTab: (Int) -> Unit) {
    val x by animateDpAsState(if (tab == 0) 6.dp else 100.dp, spring(.7f, 380f), label = "ind")
    Box(
        Modifier.padding(bottom = 14.dp).width(200.dp).height(60.dp)
            .clip(RoundedCornerShape(30.dp)).background(C.Surface).border(1.dp, C.Line, RoundedCornerShape(30.dp))
    ) {
        Box(Modifier.offset(x = x, y = 5.dp).size(94.dp, 48.dp).clip(RoundedCornerShape(24.dp)).background(C.Surface2))
        Row(Modifier.fillMaxSize().padding(horizontal = 6.dp)) {
            listOf(G.Home, G.Down).forEachIndexed { i, g ->
                val tint by animateColorAsState(if (tab == i) C.Text else C.Muted, tween(250), label = "nt")
                Box(Modifier.weight(1f).fillMaxHeight().pressable { onTab(i) }, contentAlignment = Alignment.Center) {
                    Glyph(g, tint, 24.dp)
                    if (i == 1 && activeDot) {
                        val pulse by rememberInfiniteTransition(label = "dot").animateFloat(
                            .4f, 1f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "da",
                        )
                        Box(
                            Modifier.offset(x = 14.dp, y = (-12).dp).size(8.dp).graphicsLayer { alpha = pulse }
                                .clip(RoundedCornerShape(4.dp)).background(C.Teal)
                        )
                    }
                }
            }
        }
    }
}
