package app.productlens

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.zxing.PlanarYUVLuminanceSource
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

@Composable
fun CameraScreen(scanOnly: Boolean, onPhoto: (Bitmap, Code?) -> Unit, onError: (String) -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    var permission by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    var denied by remember { mutableStateOf(false) }
    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { permission = it; denied = !it }
    DisposableEffect(owner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) permission = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(Unit) { if (!permission) request.launch(Manifest.permission.CAMERA) }
    if (!permission) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("카메라 사용 권한이 필요합니다", style = MaterialTheme.typography.headlineSmall)
            Text("촬영과 실시간 바코드 인식에만 사용합니다. 갤러리 선택과 수동 입력은 권한 없이 사용할 수 있습니다.")
            Button(onClick = { if (denied) context.startActivity(android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:${context.packageName}"))) else request.launch(Manifest.permission.CAMERA) }) { Text(if (denied) "앱 권한 설정 열기" else "카메라 권한 허용") }
            TextButton(onClick = onBack) { Text("돌아가기") }
        }; return
    }
    val preview = remember { PreviewView(context).apply { implementationMode = PreviewView.ImplementationMode.COMPATIBLE } }
    val executor = remember { Executors.newSingleThreadExecutor() }
    val main = remember { ContextCompat.getMainExecutor(context) }
    val capture = remember { ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
        .setResolutionSelector(androidx.camera.core.resolutionselector.ResolutionSelector.Builder()
            .setResolutionStrategy(androidx.camera.core.resolutionselector.ResolutionStrategy(android.util.Size(1600, 1200), androidx.camera.core.resolutionselector.ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER)).build()).build() }
    var camera by remember { mutableStateOf<Camera?>(null) }
    var flash by remember { mutableStateOf(false) }
    var ready by remember { mutableStateOf(false) }
    var captureBusy by remember { mutableStateOf(false) }
    val delivered = remember { AtomicBoolean(false) }
    val currentPhoto by rememberUpdatedState(onPhoto)
    val currentError by rememberUpdatedState(onError)
    DisposableEffect(owner) {
        val future = ProcessCameraProvider.getInstance(context)
        var disposed = false
        var boundProvider: ProcessCameraProvider? = null
        var boundCases: Array<UseCase> = emptyArray()
        future.addListener({
            if (!disposed) try {
                val provider = future.get(); boundProvider = provider
                val p = Preview.Builder().build().apply { surfaceProvider = preview.surfaceProvider }
                val analyzer = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setResolutionSelector(androidx.camera.core.resolutionselector.ResolutionSelector.Builder()
                        .setResolutionStrategy(androidx.camera.core.resolutionselector.ResolutionStrategy(android.util.Size(1280, 720), androidx.camera.core.resolutionselector.ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER)).build()).build()
                var last = 0L
                analyzer.setAnalyzer(executor) { image ->
                    try {
                        val now = android.os.SystemClock.elapsedRealtime()
                        if (!delivered.get() && now - last >= 700) {
                            last = now
                            val plane = image.planes[0]; val buffer = plane.buffer
                            val bytes = ByteArray(image.width * image.height)
                            for (y in 0 until image.height) for (x in 0 until image.width) bytes[y * image.width + x] = buffer.get(y * plane.rowStride + x * plane.pixelStride)
                            val lum = PlanarYUVLuminanceSource(bytes, image.width, image.height, 0, 0, image.width, image.height, false)
                            val code = Images.decode(lum) ?: run {
                                val rotated = ByteArray(bytes.size) { i -> bytes[(i % image.height) * image.width + image.width - 1 - i / image.height] }
                                Images.decode(PlanarYUVLuminanceSource(rotated, image.height, image.width, 0, 0, image.height, image.width, false))
                            }
                            if (code != null && delivered.compareAndSet(false, true)) {
                                val raw = image.toBitmap()
                                val bitmap = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, Matrix().apply { postRotate(image.imageInfo.rotationDegrees.toFloat()) }, true)
                                main.execute { if (!disposed) { preview.performHapticFeedback(android.view.HapticFeedbackConstants.CONFIRM); currentPhoto(bitmap, code) } }
                            }
                        }
                    } catch (e: Exception) { android.util.Log.w("ProductLens", "분석 프레임을 건너뜁니다", e) }
                      finally { image.close() }
                }
                boundCases = arrayOf(p, capture, analyzer)
                camera = provider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, *boundCases)
                ready = true
            } catch (e: Exception) { currentError("카메라를 열 수 없습니다: ${e.message}") }
        }, main)
        onDispose { disposed = true; camera?.cameraControl?.enableTorch(false); boundProvider?.unbind(*boundCases); executor.shutdown() }
    }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(if (scanOnly) "바코드를 프레임 안에 맞추세요" else "제품이나 라벨을 가까이 비춰 주세요", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 20.dp))
        Text("코드가 보이면 자동 인식합니다 · 사진은 기기에서 분석합니다", modifier = Modifier.padding(horizontal = 20.dp), style = MaterialTheme.typography.bodySmall)
        Box(Modifier.weight(1f).fillMaxWidth().background(Color.Black)) {
            AndroidView(factory = { preview }, modifier = Modifier.fillMaxSize())
            Canvas(Modifier.fillMaxSize()) {
                drawRoundRect(Color(0xFF59E7D8), topLeft = androidx.compose.ui.geometry.Offset(size.width * .08f, size.height * .25f), size = androidx.compose.ui.geometry.Size(size.width * .84f, size.height * .5f), style = Stroke(3.dp.toPx()))
            }
        }
        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = { flash = !flash; camera?.cameraControl?.enableTorch(flash) }, enabled = camera?.cameraInfo?.hasFlashUnit() == true) { Text(if (flash) "플래시 끄기" else "플래시") }
            Button(onClick = {
                captureBusy = true
                capture.takePicture(executor, object : ImageCapture.OnImageCapturedCallback() {
                    override fun onCaptureSuccess(image: ImageProxy) {
                        try {
                            val raw = image.toBitmap()
                            val rotated = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, Matrix().apply { postRotate(image.imageInfo.rotationDegrees.toFloat()) }, true)
                            val scale = minOf(1f, 1600f / maxOf(rotated.width, rotated.height))
                            val b = Bitmap.createScaledBitmap(rotated, (rotated.width * scale).toInt(), (rotated.height * scale).toInt(), true)
                            main.execute { captureBusy = false; if (delivered.compareAndSet(false, true)) currentPhoto(b, null) }
                        } catch (e: Exception) { main.execute { captureBusy = false; currentError("사진 처리 실패: ${e.message}") } }
                          finally { image.close() }
                    }
                    override fun onError(exception: ImageCaptureException) { main.execute { captureBusy = false; currentError("촬영 실패: ${exception.message}") } }
                })
            }, enabled = ready && !captureBusy, modifier = Modifier.weight(1f)) { Text(if (captureBusy) "촬영 중" else "사진으로 인식") }
        }
    }
}
