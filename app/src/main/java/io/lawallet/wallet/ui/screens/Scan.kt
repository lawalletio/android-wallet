package io.lawallet.wallet.ui.screens

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
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
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
import io.lawallet.wallet.core.ln.ScanResult
import io.lawallet.wallet.ui.LocalContainer
import io.lawallet.wallet.ui.LocalNav
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
suspend fun routeScan(c: AppContainer, nav: NavHostController, raw: String, viaNfc: Boolean = false): ScanOutcome =
    when (val r = c.scanRouter.route(raw, c.session.instanceHosts)) {
        is ScanResult.Activate -> if (r.sameInstance) { nav.navigate(R0.activate(r.tokenId)); ScanOutcome.Handled } else ScanOutcome.Foreign(r.url, java.net.URI(r.url).host)
        is ScanResult.Withdraw -> { c.flow.withdraw = r.params; nav.navigate(R0.WITHDRAW); ScanOutcome.Handled }
        is ScanResult.Pay -> runCatching { nav.navigate(startSend(c, r.destination, viaNfc)) { launchSingleTop = true }; ScanOutcome.Handled }.getOrElse { ScanOutcome.Failed(it.message ?: "Unsupported code") }
        is ScanResult.Error -> ScanOutcome.Failed(r.message)
    }

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

    fun handle(raw: String) {
        if (busy) return
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

    Column(Modifier.fillMaxSize()) {
        ScreenHeader(stringResource(R.string.scan_title), { nav.popBackStack() }, close = true)
        Column(Modifier.weight(1f).padding(horizontal = 16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            QrCamera(busy, { error = it }, ::handle)
            MutedText(stringResource(if (busy) R.string.scan_readingCode else R.string.scan_hint), align = TextAlign.Center)
            ErrorText(error, Modifier.fillMaxWidth())
        }
        LwButton(stringResource(R.string.scan_paste), {
            val clip = context.getSystemService(ClipboardManager::class.java).primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()
            if (!clip.isNullOrBlank()) handle(clip)
        }, Modifier.fillMaxWidth().padding(16.dp), BtnVariant.Secondary, icon = L.drawable.lucide_ic_clipboard)
    }

    foreign?.let { f ->
        AlertDialog(
            onDismissRequest = { foreign = null },
            title = { Txt(stringResource(R.string.scan_differentCommunity), size = 16.sp) },
            text = { Txt(stringResource(R.string.scan_foreignCard, f.host, c.session.instanceHost), color = Lw.MutedFg) },
            confirmButton = { TextButton({ foreign = null; context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(f.url))) }) { Txt(stringResource(R.string.scan_openLink)) } },
            dismissButton = { TextButton({ foreign = null }) { Txt(stringResource(R.string.scan_scanAnother)) } },
            containerColor = Glass.Dialog,
        )
    }
}

/** Square camera preview that reports QR payloads; requests the camera permission itself. */
@androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)
@Composable
fun QrCamera(busy: Boolean, onError: (String?) -> Unit, onCode: (String) -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current
    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    LaunchedEffect(Unit) { if (!granted) permission.launch(Manifest.permission.CAMERA) }
    val paused by rememberUpdatedState(busy)
    val failed by rememberUpdatedState(onError)
    val code by rememberUpdatedState(onCode)
    Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(24.dp)).background(Color.Black), contentAlignment = Alignment.Center) {
        if (granted) {
            val analyzerExecutor = remember { Executors.newSingleThreadExecutor() }
            val scanner = remember {
                initMlKit(context)
                BarcodeScanning.getClient(BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build())
            }
            DisposableEffect(Unit) { onDispose { analyzerExecutor.shutdown(); scanner.close() } }
            AndroidView({ ctx ->
                PreviewView(ctx).also { view ->
                    val providerFuture = ProcessCameraProvider.getInstance(ctx)
                    providerFuture.addListener({
                        val provider = providerFuture.get()
                        val preview = Preview.Builder().build().also { it.surfaceProvider = view.surfaceProvider }
                        val analysis = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
                        analysis.setAnalyzer(analyzerExecutor) { proxy ->
                            val media = proxy.image
                            if (media == null || paused) { proxy.close(); return@setAnalyzer }
                            scanner.process(InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees))
                                .addOnSuccessListener { codes -> codes.firstNotNullOfOrNull { it.rawValue }?.let { v -> ContextCompat.getMainExecutor(ctx).execute { code(v) } } }
                                .addOnCompleteListener { proxy.close() }
                        }
                        provider.unbindAll()
                        runCatching { provider.bindToLifecycle(lifecycle, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis) }.onFailure { failed(it.message) }
                    }, ContextCompat.getMainExecutor(ctx))
                }
            }, Modifier.fillMaxSize())
            Box(Modifier.fillMaxWidth(0.75f).aspectRatio(1f).border(2.dp, Color.White.copy(alpha = 0.8f), RoundedCornerShape(20.dp)))
            if (busy) Spinner(32.dp)
        } else Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(24.dp)) {
            MutedText(stringResource(R.string.scan_camera_permission), align = TextAlign.Center)
            LwButton(stringResource(R.string.scan_grant), { permission.launch(Manifest.permission.CAMERA) }, variant = BtnVariant.Secondary)
        }
    }
}
