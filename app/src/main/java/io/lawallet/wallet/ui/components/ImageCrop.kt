package io.lawallet.wallet.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.Spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** What a crop produces: the frame's aspect (w/h), whether it's shown round, and the uploaded size. */
enum class CropShape(val aspect: Float, val round: Boolean, val outW: Int, val outH: Int) {
    Avatar(1f, true, 512, 512),
    Banner(3f, false, 1500, 500),
}

/** Decodes [uri] small enough to crop (and mutable-free, so pixels can be read back). */
fun decodeForCrop(context: Context, uri: Uri, maxSide: Int = 2400): Bitmap =
    ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { d, info, _ ->
        d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        val s = info.size
        val f = maxSide.toFloat() / max(s.width, s.height)
        if (f < 1f) d.setTargetSize((s.width * f).roundToInt().coerceAtLeast(1), (s.height * f).roundToInt().coerceAtLeast(1))
    }

/** [src] in bitmap pixels → a [shape]-sized JPEG. */
fun cropToJpeg(bitmap: Bitmap, src: android.graphics.Rect, shape: CropShape): Pair<Bitmap, ByteArray> {
    val out = Bitmap.createBitmap(shape.outW, shape.outH, Bitmap.Config.ARGB_8888)
    android.graphics.Canvas(out).drawBitmap(bitmap, src, android.graphics.Rect(0, 0, shape.outW, shape.outH), android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG))
    val bytes = ByteArrayOutputStream().use { out.compress(Bitmap.CompressFormat.JPEG, 88, it); it.toByteArray() }
    return out to bytes
}

/**
 * The bitmap pixels under the frame: the photo is drawn at [scale], centred on the frame's centre
 * moved by [pan] (screen px).
 */
fun cropBounds(bw: Int, bh: Int, frame: Size, scale: Float, pan: Offset): IntRect {
    val left = (bw * scale / 2 - pan.x - frame.width / 2) / scale
    val top = (bh * scale / 2 - pan.y - frame.height / 2) / scale
    return IntRect(
        left.roundToInt().coerceIn(0, bw - 1), top.roundToInt().coerceIn(0, bh - 1),
        min(bw, (left + frame.width / scale).roundToInt()), min(bh, (top + frame.height / scale).roundToInt()),
    )
}

/**
 * Full-screen pinch/pan cropper. The frame stays put and the photo moves under it; the photo always
 * covers the frame, so there is never an empty edge to upload. Double-tap toggles 1x / 2.5x.
 */
@Composable
fun CropDialog(bitmap: Bitmap, shape: CropShape, title: String, confirm: String, cancel: String, onCancel: () -> Unit, onCrop: (Bitmap, ByteArray) -> Unit) {
    Dialog(onCancel, DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        val image = remember(bitmap) { bitmap.asImageBitmap() }
        val scope = rememberCoroutineScope()
        val zoom = remember { Animatable(1f) }
        var pan by remember { mutableStateOf(Offset.Zero) }
        // Entrance: the photo settles in from slightly zoomed out.
        val intro = remember { Animatable(0f) }
        LaunchedEffect(Unit) { intro.animateTo(1f, spring(Spring.DampingRatioLowBouncy, Spring.StiffnessLow)) }
        var frame by remember { mutableStateOf(Rect.Zero) }
        var base by remember { mutableFloatStateOf(1f) } // scale at which the photo just covers the frame
        val pad = with(LocalDensity.current) { 20.dp.toPx() }

        fun clamp(p: Offset, z: Float): Offset {
            val w = bitmap.width * base * z; val h = bitmap.height * base * z
            val mx = max(0f, (w - frame.width) / 2); val my = max(0f, (h - frame.height) / 2)
            return Offset(p.x.coerceIn(-mx, mx), p.y.coerceIn(-my, my))
        }

        Box(Modifier.fillMaxSize().background(Color.Black).graphicsLayer { alpha = intro.value.coerceIn(0f, 1f) }) {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val cw = constraints.maxWidth.toFloat(); val ch = constraints.maxHeight.toFloat()
                val fw = cw - pad * 2; val fh = fw / shape.aspect
                frame = Rect(Offset(pad, (ch - fh) / 2), Size(fw, fh))
                base = max(fw / bitmap.width, fh / bitmap.height)
                Canvas(
                    Modifier.fillMaxSize().testTag("crop-canvas")
                        .pointerInput(bitmap) {
                            detectTransformGestures { _, p, z, _ ->
                                val nz = (zoom.value * z).coerceIn(1f, 5f)
                                scope.launch { zoom.snapTo(nz) }
                                pan = clamp(pan + p, nz)
                            }
                        }
                        .pointerInput(bitmap) {
                            detectTapGestures(onDoubleTap = {
                                scope.launch {
                                    val target = if (zoom.value > 1.4f) 1f else 2.5f
                                    val from = pan
                                    zoom.animateTo(target, tween(260)) { pan = clamp(from * (value / zoom.targetValue), value) }
                                    pan = clamp(pan, target)
                                }
                            })
                        },
                ) {
                    val s = base * zoom.value * (0.92f + 0.08f * intro.value)
                    val w = bitmap.width * s; val h = bitmap.height * s
                    val c = frame.center + pan
                    drawImage(image, IntOffset.Zero, IntSize(bitmap.width, bitmap.height), IntOffset((c.x - w / 2).roundToInt(), (c.y - h / 2).roundToInt()), IntSize(w.roundToInt(), h.roundToInt()))
                    // Dim everything outside the frame, then outline it.
                    val hole = Path().apply {
                        if (shape.round) addOval(frame) else addRoundRect(RoundRect(frame, CornerRadius(16.dp.toPx())))
                    }
                    val scrim = Path().apply { fillType = PathFillType.EvenOdd; addRect(Rect(Offset.Zero, size)); addPath(hole) }
                    drawPath(scrim, Color.Black.copy(alpha = 0.62f))
                    drawPath(hole, Color.White.copy(alpha = 0.85f), style = Stroke(1.5.dp.toPx()))
                    if (!shape.round) {
                        // Rule-of-thirds guides help place a wide cover.
                        for (i in 1..2) {
                            val x = frame.left + frame.width * i / 3; val y = frame.top + frame.height * i / 3
                            drawLine(Color.White.copy(alpha = 0.18f), Offset(x, frame.top), Offset(x, frame.bottom), 1f)
                            drawLine(Color.White.copy(alpha = 0.18f), Offset(frame.left, y), Offset(frame.right, y), 1f)
                        }
                    }
                }
            }
            Column(Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp)) {
                Txt(title, Modifier.fillMaxWidth().padding(top = 8.dp), size = 17.sp, weight = FontWeight.SemiBold, align = androidx.compose.ui.text.style.TextAlign.Center)
                Box(Modifier.weight(1f))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    LwButton(cancel, onCancel, Modifier.weight(1f).testTag("crop-cancel"), BtnVariant.Secondary)
                    LwButton(confirm, {
                        val b = cropBounds(bitmap.width, bitmap.height, frame.size, base * zoom.value, pan)
                        val r = android.graphics.Rect(b.left, b.top, b.right, b.bottom)
                        val (out, bytes) = cropToJpeg(bitmap, r, shape)
                        onCrop(out, bytes)
                    }, Modifier.weight(1f).testTag("crop-confirm"), BtnVariant.Theme)
                }
            }
        }
    }
}
