package io.lawallet.wallet.ui.screens

import io.lawallet.wallet.ui.components.DialogTitle
import android.Manifest
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import dev.chrisbanes.haze.HazeInputScale
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import io.lawallet.wallet.ui.theme.LocalBrand
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import io.lawallet.wallet.ui.components.LwAlertDialog
import io.lawallet.wallet.ui.components.ModalAction
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.navigation.NavHostController
import com.google.mlkit.common.MlKit
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import io.lawallet.wallet.R
import io.lawallet.wallet.app.AppContainer
import io.lawallet.wallet.core.ln.CashuUrScanner
import io.lawallet.wallet.core.ln.PluginLink
import io.lawallet.wallet.core.ln.ScanResult
import io.lawallet.wallet.ui.LocalContainer
import io.lawallet.wallet.ui.LocalNav
import io.lawallet.wallet.ui.rememberSettled
import io.lawallet.wallet.ui.R0
import io.lawallet.wallet.ui.components.BtnVariant
import io.lawallet.wallet.ui.components.ErrorText
import io.lawallet.wallet.ui.components.LwButton
import io.lawallet.wallet.ui.components.MutedText
import io.lawallet.wallet.ui.components.ScreenHeader
import io.lawallet.wallet.ui.components.Spinner
import io.lawallet.wallet.ui.components.Txt
import io.lawallet.wallet.ui.theme.Lw
import io.lawallet.wallet.ui.theme.GlassLevel
import io.lawallet.wallet.ui.theme.Glass
import io.lawallet.wallet.ui.theme.glass
import kotlinx.coroutines.launch
import java.util.concurrent.Executors
import com.composables.icons.lucide.R as L

sealed interface ScanOutcome {
    data object Handled : ScanOutcome
    data class Foreign(val url: String, val host: String) : ScanOutcome
    data class Failed(val message: String) : ScanOutcome
}

/** scan-screen.tsx dispatch: activation → withdraw → send flow. Shared by camera, paste, deep links and NFC. */
suspend fun routeScan(c: AppContainer, nav: NavHostController, raw: String, viaNfc: Boolean = false): ScanOutcome {
    // Magic link: the landing checks the link's community against ours (it may offer to switch).
    io.lawallet.wallet.core.ln.EmailLoginUrl.parse(raw)?.let { nav.navigate(R0.emailAuth(it.token, it.host)) { launchSingleTop = true }; return ScanOutcome.Handled }
    return when (val r = c.scanRouter.route(raw, c.session.instanceHosts)) {
        is ScanResult.Activate -> if (r.sameInstance) { nav.navigate(R0.activate(r.tokenId)); ScanOutcome.Handled } else ScanOutcome.Foreign(r.url, java.net.URI(r.url).host)
        is ScanResult.Withdraw -> { c.flow.withdraw = r.params; nav.navigate(R0.WITHDRAW); ScanOutcome.Handled }
        is ScanResult.Cashu -> { c.flow.cashu = r.token; nav.navigate(R0.CASHU); ScanOutcome.Handled }
        is ScanResult.Pay -> runCatching { nav.navigate(startSend(c, r.destination, viaNfc)) { launchSingleTop = true }; ScanOutcome.Handled }.getOrElse { ScanOutcome.Failed(it.message ?: "Unsupported code") }
        is ScanResult.WalletKey -> {
            // Not a payment: say what it is, and open the screen that takes it (import, with it already filled in).
            c.flow.onchainImport = r.text
            c.activityBridge.activity?.let { a ->
                android.widget.Toast.makeText(a, a.getString(if (r.private) R.string.onchain_scan_privateKey else R.string.onchain_scan_walletKey, r.label), android.widget.Toast.LENGTH_LONG).show()
            }
            nav.navigate(R0.ONCHAIN_IMPORT) { launchSingleTop = true }
            ScanOutcome.Handled
        }
        is ScanResult.Onchain -> startOnchainSend(c, r.request)?.let { nav.navigate(it) { launchSingleTop = true }; ScanOutcome.Handled }
            ?: ScanOutcome.Failed(c.text(R.string.onchain_send_noWallet, "No onchain wallet here can pay this address"))
        is ScanResult.InstallPlugin -> {
            // Through the Plugins list, so Install lands back on it (and its sign-in) like Add Plugin does.
            nav.navigate(R0.PLUGINS) { launchSingleTop = true }
            nav.navigate(R0.pluginAdd(r.url))
            ScanOutcome.Handled
        }
        ScanResult.PluginsUnavailable -> ScanOutcome.Failed(c.text(R.string.scan_plugins_unavailable, "Plugins aren't available in this build"))
        is ScanResult.Error -> ScanOutcome.Failed(if (r.code == PluginLink.BAD_URL) c.text(R.string.scan_plugin_bad_url, r.message) else r.message)
    }
}

/** A string in the activity's (per-app) locale; scans always arrive with an activity up. */
private fun AppContainer.text(id: Int, fallback: String) = activityBridge.activity?.getString(id) ?: fallback

/** Deep links / NFC taps outside the receive screen. */
suspend fun handleScanPayload(c: AppContainer, nav: NavHostController, raw: String, viaNfc: Boolean = false) {
    when (val o = routeScan(c, nav, raw, viaNfc)) {
        is ScanOutcome.Foreign -> c.activityBridge.activity?.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(o.url)))
        is ScanOutcome.Failed -> c.activityBridge.activity?.let { android.widget.Toast.makeText(it, o.message, android.widget.Toast.LENGTH_LONG).show() }
        ScanOutcome.Handled -> {}
    }
}

@Volatile private var mlKitReady = false

/** ML Kit's startup provider is removed from the manifest; initialize() throws if called twice per process. Main thread only. */
private fun initMlKit(context: android.content.Context) {
    if (!mlKitReady) { MlKit.initialize(context.applicationContext); mlKitReady = true }
}

@Composable
fun ScanScreen() {
    val c = LocalContainer.current
    val nav = LocalNav.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var foreign by remember { mutableStateOf<ScanOutcome.Foreign?>(null) }
    val ur = remember { CashuUrScanner() }
    var urProgress by remember { mutableStateOf<Float?>(null) }

    fun handle(raw: String) {
        if (busy) return
        // Animated QR (NUT-16): collect frames until the token assembles, then route it like a static one.
        if (CashuUrScanner.isUr(raw)) {
            val (done, text) = ur.receive(raw)
            // The decoder's estimate can dip as fountain parts mix: the bar only moves forward.
            urProgress = maxOf(urProgress ?: 0f, done.toFloat())
            if (text == null) return
            urProgress = null
            if (CashuUrScanner.isUr(text)) return
            return handle(text)
        }
        busy = true; error = null
        scope.launch {
            // Handled stays busy while navigating away, or the next camera frame routes the same code again.
            when (val o = routeScan(c, nav, raw)) {
                ScanOutcome.Handled -> {}
                is ScanOutcome.Foreign -> { foreign = o; busy = false }
                is ScanOutcome.Failed -> { error = o.message; busy = false }
            }
        }
    }

    val granted = rememberCameraPermission()
    val settled = rememberSettled()
    val haze = rememberHazeState()
    // Full bleed: the camera runs under the status and navigation bars; the controls stay in the safe area.
    BoxWithConstraints(Modifier.bleedSafeDrawing().background(Color.Black)) {
        val side = minOf(maxWidth * 0.72f, 300.dp)
        val left = (maxWidth - side) / 2
        val top = (maxHeight - side) * 0.42f
        val density = LocalDensity.current
        val cutout = remember(left, top, side) { with(density) { CutoutShape(Rect(Offset(left.toPx(), top.toPx()), Size(side.toPx(), side.toPx())), 28.dp.toPx()) } }

        if (granted.value && settled) CameraFeed(busy, { error = it }, ::handle, textureView = CameraBlur, Modifier.fillMaxSize().then(if (CameraBlur) Modifier.hazeSource(haze) else Modifier))
        // Frosted glass everywhere but the window: one GPU blur pass over a downscaled frame. Without blur, a dense tint
        // over the cheaper SurfaceView feed.
        Box(Modifier.fillMaxSize().clip(cutout).then(if (CameraBlur) Modifier.hazeEffect(haze, ScanGlass) { inputScale = HazeInputScale.Fixed(0.25f) } else Modifier.background(ScanGlass.fallbackTint.color)))
        Box(Modifier.offset(left, top).size(side), contentAlignment = Alignment.Center) {
            ScanFrame(urProgress, Modifier.fillMaxSize())
            when {
                !granted.value -> Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(24.dp)) {
                    MutedText(stringResource(R.string.scan_camera_permission), align = TextAlign.Center)
                    LwButton(stringResource(R.string.scan_grant), { granted.request() }, variant = BtnVariant.Secondary)
                }
                !settled || busy -> Spinner(32.dp)
            }
        }
        Column(Modifier.offset(y = top + side + 24.dp).fillMaxWidth().padding(horizontal = 32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            urProgress?.let { UrProgressPill(it, Modifier) }
            Txt(
                stringResource(if (urProgress != null) R.string.scan_animatedHint else if (busy) R.string.scan_readingCode else R.string.scan_hintCashu),
                size = 14.sp, color = Color.White.copy(alpha = 0.85f), align = TextAlign.Center,
            )
            ErrorText(error, Modifier.fillMaxWidth())
        }
        // The NavHost already consumed the insets, so pad with their raw values.
        Column(Modifier.fillMaxSize().padding(WindowInsets.safeDrawing.asPaddingValues())) {
            ScreenHeader(stringResource(R.string.scan_title), { nav.popBackStack() }, close = true)
            Spacer(Modifier.weight(1f))
            LwButton(stringResource(R.string.scan_paste), {
                val clip = context.getSystemService(ClipboardManager::class.java).primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()
                if (!clip.isNullOrBlank()) handle(clip)
            }, Modifier.fillMaxWidth().padding(16.dp), BtnVariant.Secondary, icon = L.drawable.lucide_ic_clipboard)
        }
    }

    foreign?.let { f ->
        LwAlertDialog(
        onDismissRequest = { foreign = null },
        title = { DialogTitle(stringResource(R.string.scan_differentCommunity)) },
        text = { Txt(stringResource(R.string.scan_foreignCard, f.host, c.session.instanceHost), color = Lw.MutedFg) },
        confirmButton = { ModalAction(stringResource(R.string.scan_openLink), { foreign = null; context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(f.url))) }) },
    )
    }
}

/** Square camera preview that reports QR payloads; requests the camera permission itself. */
@Composable
fun QrCamera(onError: (String?) -> Unit, onCode: (String) -> Unit) {
    val granted = rememberCameraPermission()
    // The camera (CameraX bind, ML Kit init) starts once the screen has landed: mid-slide it drops frames.
    val settled = rememberSettled()
    Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(24.dp)).background(Color.Black), contentAlignment = Alignment.Center) {
        if (granted.value && !settled) Spinner(32.dp)
        else if (granted.value) {
            CameraFeed(false, onError, onCode, textureView = false, Modifier.fillMaxSize())
            Box(Modifier.fillMaxWidth(0.75f).aspectRatio(1f).border(2.dp, Color.White.copy(alpha = 0.8f), RoundedCornerShape(20.dp)))
        } else Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(24.dp)) {
            MutedText(stringResource(R.string.scan_camera_permission), align = TextAlign.Center)
            LwButton(stringResource(R.string.scan_grant), { granted.request() }, variant = BtnVariant.Secondary)
        }
    }
}

class CameraPermission(private val state: androidx.compose.runtime.MutableState<Boolean>, private val launch: () -> Unit) {
    val value get() = state.value
    fun request() = launch()
}

/** Camera permission, requested once on first composition. */
@Composable
private fun rememberCameraPermission(): CameraPermission {
    val context = LocalContext.current
    val granted = remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted.value = it }
    LaunchedEffect(Unit) { if (!granted.value) launcher.launch(Manifest.permission.CAMERA) }
    return remember { CameraPermission(granted) { launcher.launch(Manifest.permission.CAMERA) } }
}

/**
 * CameraX preview + ML Kit QR analysis. [textureView]: draw the feed in the view hierarchy (TextureView) so a blur
 * can sample it; otherwise the cheaper SurfaceView.
 */
@androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)
@Composable
private fun CameraFeed(busy: Boolean, onError: (String?) -> Unit, onCode: (String) -> Unit, textureView: Boolean, modifier: Modifier) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current
    val paused by rememberUpdatedState(busy)
    val failed by rememberUpdatedState(onError)
    val code by rememberUpdatedState(onCode)
    val analyzerExecutor = remember { Executors.newSingleThreadExecutor() }
    val scanner = remember {
        initMlKit(context)
        BarcodeScanning.getClient(BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build())
    }
    // CameraX keeps the last use cases in process-wide state after unbinding, so ours must drop what they point at: the
    // analyzer (this screen's callbacks) and the preview's surface (the PreviewView, so the Activity). LeakCanary caught
    // a destroyed MainActivity held through ImageAnalysis' analyzer.
    val bound = remember { arrayOfNulls<androidx.camera.core.UseCase>(2) }
    var disposed by remember { mutableStateOf(false) }
    DisposableEffect(Unit) {
        onDispose {
            disposed = true
            (bound[0] as? Preview)?.surfaceProvider = null
            (bound[1] as? ImageAnalysis)?.clearAnalyzer()
            // Only once bound: the provider is ready then, so get() doesn't block.
            bound.filterNotNull().takeIf { it.isNotEmpty() }?.let { cases -> runCatching { ProcessCameraProvider.getInstance(context.applicationContext).get().unbind(*cases.toTypedArray()) } }
            bound.fill(null)
            analyzerExecutor.shutdown(); scanner.close()
        }
    }
    AndroidView({ ctx ->
        PreviewView(ctx).also { view ->
            if (textureView) view.implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            val main = ContextCompat.getMainExecutor(ctx.applicationContext)
            val providerFuture = ProcessCameraProvider.getInstance(ctx)
            providerFuture.addListener({
                if (disposed) return@addListener // left before the camera was ready
                val provider = providerFuture.get()
                val preview = Preview.Builder().build().also { it.surfaceProvider = view.surfaceProvider }
                val analysis = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
                analysis.setAnalyzer(analyzerExecutor) { proxy ->
                    val media = proxy.image
                    if (media == null || paused) { proxy.close(); return@setAnalyzer }
                    // Callbacks on the analyzer's thread, not Main (they'd wake it for every analysed frame). Once the
                    // screen is gone the executor is shut: drop them, the camera is unbound by then.
                    val bg = java.util.concurrent.Executor { r -> runCatching { analyzerExecutor.execute(r) } }
                    scanner.process(InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees))
                        .addOnSuccessListener(bg) { codes -> codes.firstNotNullOfOrNull { it.rawValue }?.let { v -> main.execute { if (!disposed) code(v) } } }
                        .addOnCompleteListener(bg) { proxy.close() }
                }
                provider.unbindAll()
                bound[0] = preview; bound[1] = analysis
                runCatching { provider.bindToLifecycle(lifecycle, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis) }.onFailure { failed(it.message) }
            }, main)
        }
    }, modifier)
}

/**
 * Whether the scanner blurs the camera. A live blur needs the feed in a TextureView (a GPU copy of every camera frame)
 * plus a blur pass per frame: Sentry had 100% slow frames on Scan on mid-range phones (6% on flagships). Only phones
 * that declare a media performance class (S or later) get it; the rest get the SurfaceView under [ScanGlass]'s scrim.
 */
private val CameraBlur = android.os.Build.VERSION.SDK_INT >= 31 && android.os.Build.VERSION.MEDIA_PERFORMANCE_CLASS >= android.os.Build.VERSION_CODES.S

/** The scanner's glass: soft blur under a dark tint, no grain. Below API 31 (no blur) a dense scrim. */
private val ScanGlass = HazeStyle(
    backgroundColor = Color.Black,
    tint = HazeTint(Color.Black.copy(alpha = 0.45f)),
    blurRadius = 28.dp,
    noiseFactor = 0f, // grain is an extra full-screen pass on every camera frame
    fallbackTint = HazeTint(Color.Black.copy(alpha = 0.72f)),
)

/** Everything but a rounded window. */
private class CutoutShape(private val window: Rect, private val radius: Float) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density) = Outline.Generic(Path().apply {
        fillType = PathFillType.EvenOdd
        addRect(Rect(Offset.Zero, size))
        addRoundRect(RoundRect(window, CornerRadius(radius)))
    })
}

/** Lays out over the safe-drawing insets the NavHost pads its screens with: edge to edge. */
@Composable
private fun Modifier.bleedSafeDrawing(): Modifier {
    val insets = WindowInsets.safeDrawing
    return layout { measurable, constraints ->
        val l = insets.getLeft(this, layoutDirection)
        val t = insets.getTop(this)
        val r = insets.getRight(this, layoutDirection)
        val b = insets.getBottom(this)
        val placeable = measurable.measure(Constraints.fixed(constraints.maxWidth + l + r, constraints.maxHeight + t + b))
        layout(constraints.maxWidth, constraints.maxHeight) { placeable.place(-l, -t) }
    }
}

/** The window's frame: a faint outline with bright corner brackets; an animated QR's progress traces it in the brand colour. */
@Composable
private fun ScanFrame(progress: Float?, modifier: Modifier) {
    val brand = LocalBrand.current
    val shown = animateFloatAsState(progress ?: 0f, tween(350), label = "ur-frame")
    Box(modifier.drawWithCache {
        val radius = 28.dp.toPx()
        val outline = Path().apply { addRoundRect(RoundRect(Rect(Offset.Zero, size), CornerRadius(radius))) }
        val measure = PathMeasure().apply { setPath(outline, false) }
        val filled = Path()
        val thin = Stroke(1.dp.toPx())
        val bold = Stroke(4.dp.toPx(), cap = StrokeCap.Round)
        // Corner brackets: an arc plus a short leg each way.
        val leg = 22.dp.toPx()
        val w = size.width
        val h = size.height
        val corners = Path().apply {
            fun bracket(cx: Float, cy: Float, sx: Float, sy: Float) {
                moveTo(cx, cy + sy * (radius + leg))
                lineTo(cx, cy + sy * radius)
                quadraticTo(cx, cy, cx + sx * radius, cy)
                lineTo(cx + sx * (radius + leg), cy)
            }
            bracket(0f, 0f, 1f, 1f); bracket(w, 0f, -1f, 1f); bracket(0f, h, 1f, -1f); bracket(w, h, -1f, -1f)
        }
        onDrawBehind {
            drawPath(outline, Color.White.copy(alpha = 0.25f), style = thin)
            drawPath(corners, Color.White, style = bold)
            val p = shown.value
            if (p > 0f) {
                filled.reset()
                measure.getSegment(0f, measure.length * p, filled, true)
                drawPath(filled, brand.t400, style = bold)
            }
        }
    })
}

/** Glass pill under the window: "Animated QR · 42%" with a bar. */
@Composable
private fun UrProgressPill(progress: Float, modifier: Modifier) {
    val brand = LocalBrand.current
    val shown = animateFloatAsState(progress, tween(350), label = "ur-bar")
    Column(modifier.glass(RoundedCornerShape(16.dp), GlassLevel.Strong).padding(horizontal = 16.dp, vertical = 10.dp).width(200.dp).testTag("scan-ur-progress")) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Txt(stringResource(R.string.scan_animated), size = 13.sp, weight = FontWeight.Medium)
            Txt("${(progress * 100).toInt()}%", size = 13.sp, weight = FontWeight.SemiBold, color = brand.t300)
        }
        Box(Modifier.padding(top = 8.dp).fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(Color.White.copy(alpha = 0.15f)).drawBehind {
            drawRoundRect(brand.t400, size = Size(size.width * shown.value, size.height), cornerRadius = CornerRadius(size.height / 2))
        })
    }
}
