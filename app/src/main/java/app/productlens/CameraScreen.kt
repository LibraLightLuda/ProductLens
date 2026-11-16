package app.productlens

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.view.MotionEvent
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.view.doOnLayout
import androidx.lifecycle.Observer
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

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
    val preview = remember { PreviewView(context).apply { implementationMode = PreviewView.ImplementationMode.COMPATIBLE; scaleType = PreviewView.ScaleType.FILL_CENTER } }
    val executor = remember { Executors.newSingleThreadExecutor() }
    val main = remember { ContextCompat.getMainExecutor(context) }
    val capture = remember { ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
        .setResolutionSelector(androidx.camera.core.resolutionselector.ResolutionSelector.Builder()
            .setResolutionStrategy(androidx.camera.core.resolutionselector.ResolutionStrategy(android.util.Size(1920, 1440), androidx.camera.core.resolutionselector.ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)).build()).build() }
    var camera by remember { mutableStateOf<Camera?>(null) }
    var flash by remember { mutableStateOf(false) }
    var ready by remember { mutableStateOf(false) }
    var captureBusy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("바코드 양옆 여백까지 프레임 안에 맞추세요") }
    var hitBox by remember { mutableStateOf<ScanBox?>(null) }
    var guide by remember { mutableStateOf(scanGuide) }
    var focusPoint by remember { mutableStateOf(Offset(.5f, .5f)) }
    var focusConfirmed by remember { mutableStateOf(false) }
    var zoom by remember { mutableFloatStateOf(1f) }
    val delivered = remember { AtomicBoolean(false) }
    val active = remember { AtomicBoolean(true) }
    val focusing = remember { AtomicBoolean(false) }
    val focusSequence = remember { AtomicInteger(0) }
    val blockedUntil = remember { AtomicLong(0) }
    val stable = remember { StableBarcode() }
    val currentPhoto by rememberUpdatedState(onPhoto)
    val currentError by rememberUpdatedState(onError)
    fun focus(x: Float = preview.width * (guide.left + guide.right) / 2, y: Float = preview.height * (guide.top + guide.bottom) / 2) {
        val bound = camera ?: return
        if (!active.get() || preview.width <= 0 || preview.height <= 0) return
        val px = x.coerceIn(0f, (preview.width - 1).toFloat())
        val py = y.coerceIn(0f, (preview.height - 1).toFloat())
        val sequence = focusSequence.incrementAndGet()
        stable.reset(); hitBox = null; focusing.set(true)
        focusPoint = Offset(px / preview.width, py / preview.height); focusConfirmed = false
        guide = scanGuide.centeredAt(focusPoint.x, focusPoint.y)
        message = "초점을 맞추는 중…"
        val point = preview.meteringPointFactory.createPoint(px, py, .15f)
        val future = bound.cameraControl.startFocusAndMetering(FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE)
            .setAutoCancelDuration(3, TimeUnit.SECONDS).build())
        future.addListener({
            if (active.get() && sequence == focusSequence.get()) {
                focusing.set(false); blockedUntil.set(android.os.SystemClock.elapsedRealtime() + 200)
                message = try { focusConfirmed = future.get().isFocusSuccessful; if (focusConfirmed) "초점 확인 · 움직이지 않으면 3회 확인 후 인식합니다" else "바코드를 선명하게 맞추고 잠시 멈춰 주세요" }
                    catch (_: Exception) { "바코드를 터치해 초점을 다시 맞추세요" }
            }
        }, main)
    }
    DisposableEffect(owner) {
        active.set(true)
        val future = ProcessCameraProvider.getInstance(context)
        var provider: ProcessCameraProvider? = null
        var cases: Array<UseCase> = emptyArray()
        val streamObserver = Observer<PreviewView.StreamState> { state ->
            if (state == PreviewView.StreamState.STREAMING && active.get()) focus() else stable.reset()
        }
        preview.previewStreamState.observe(owner, streamObserver)
        preview.setOnTouchListener { view, event ->
            if (event.action == MotionEvent.ACTION_UP) { view.performClick(); focus(event.x, event.y) }
            true
        }
        future.addListener({ preview.doOnLayout {
            if (active.get()) try {
                val bound = future.get(); provider = bound
                val rotation = preview.display.rotation
                capture.targetRotation = rotation
                val p = Preview.Builder().setTargetRotation(rotation).build().apply { surfaceProvider = preview.surfaceProvider }
                val analyzer = ImageAnalysis.Builder().setTargetRotation(rotation).setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setResolutionSelector(androidx.camera.core.resolutionselector.ResolutionSelector.Builder()
                        .setResolutionStrategy(androidx.camera.core.resolutionselector.ResolutionStrategy(android.util.Size(1920, 1080), androidx.camera.core.resolutionselector.ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER)).build()).build()
                var last = 0L
                analyzer.setAnalyzer(executor) { image ->
                    try {
                        val now = android.os.SystemClock.elapsedRealtime()
                        if (!delivered.get() && !focusing.get() && now >= blockedUntil.get() && now - last >= 250) {
                            last = now
                            val generation = focusSequence.get()
                            val area = guide
                            val target = focusPoint
                            val hit = BarcodeScan.find(CameraFrames.luminance(image).region(area),
                                ((target.x - area.left) / area.width).coerceIn(0f, 1f), ((target.y - area.top) / area.height).coerceIn(0f, 1f))
                            if (generation != focusSequence.get() || focusing.get() || android.os.SystemClock.elapsedRealtime() < blockedUntil.get()) return@setAnalyzer
                            val confirmed = stable.observe(hit, now)
                            val count = stable.count
                            main.execute { if (active.get() && !focusing.get()) {
                                hitBox = hit?.box
                                message = if (hit != null) "${hit.code.format} · 같은 위치 확인 ${count.coerceAtMost(3)}/3" else "프레임 안의 바코드를 터치해 초점을 맞추세요"
                            } }
                            if (confirmed != null && delivered.compareAndSet(false, true)) {
                                val bitmap = CameraFrames.photo(image, area)
                                main.execute { if (active.get()) { preview.performHapticFeedback(android.view.HapticFeedbackConstants.CONFIRM); currentPhoto(bitmap, confirmed.code) } }
                            }
                        }
                    } catch (e: Exception) { stable.reset(); android.util.Log.w("ProductLens", "분석 프레임을 건너뜁니다", e) }
                      finally { image.close() }
                }
                cases = arrayOf(p, capture, analyzer)
                val viewport = checkNotNull(preview.viewPort) { "카메라 표시 영역을 준비하지 못했습니다." }
                camera = bound.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA,
                    UseCaseGroup.Builder().setViewPort(viewport).addUseCase(p).addUseCase(capture).addUseCase(analyzer).build())
                ready = true
            } catch (e: Exception) { currentError("카메라를 열 수 없습니다: ${e.message}") }
        } }, main)
        onDispose { active.set(false); preview.previewStreamState.removeObserver(streamObserver); preview.setOnTouchListener(null); camera?.cameraControl?.enableTorch(false); provider?.unbind(*cases); executor.shutdown() }
    }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(if (scanOnly) "바코드를 프레임 안에 맞추세요" else "제품이나 라벨을 가까이 비춰 주세요", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 20.dp))
        Text(message, modifier = Modifier.padding(horizontal = 20.dp), style = MaterialTheme.typography.bodySmall, minLines = 2, maxLines = 2)
        Box(Modifier.weight(1f).fillMaxWidth().background(Color.Black)) {
            AndroidView(factory = { preview }, modifier = Modifier.fillMaxSize())
            Canvas(Modifier.fillMaxSize()) {
                drawRoundRect(Color(0xFF59E7D8), Offset(size.width * guide.left, size.height * guide.top), Size(size.width * guide.width, size.height * guide.height), style = Stroke(3.dp.toPx()))
                hitBox?.let { b -> drawRect(Color.Yellow, Offset(size.width * (guide.left + b.left * guide.width), size.height * (guide.top + b.top * guide.height)),
                    Size(size.width * b.width * guide.width, size.height * b.height * guide.height), style = Stroke(2.dp.toPx())) }
                drawCircle(if (focusConfirmed) Color(0xFF59E7D8) else Color.White, 22.dp.toPx(), Offset(size.width * focusPoint.x, size.height * focusPoint.y), style = Stroke(1.5.dp.toPx()))
            }
        }
        val maxZoom = minOf(4f, camera?.cameraInfo?.zoomState?.value?.maxZoomRatio ?: 1f)
        Row(Modifier.padding(horizontal = 20.dp).height(48.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text("확대 ${"%.1f".format(java.util.Locale.US, zoom)}×")
            Slider(zoom, { zoom = it; camera?.cameraControl?.setZoomRatio(it); stable.reset(); blockedUntil.set(android.os.SystemClock.elapsedRealtime() + 500) },
                valueRange = 1f..maxOf(1.01f, maxZoom), enabled = ready && maxZoom > 1f, onValueChangeFinished = { focus() }, modifier = Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { flash = !flash; camera?.cameraControl?.enableTorch(flash); focus() }, enabled = camera?.cameraInfo?.hasFlashUnit() == true) { Text(if (flash) "플래시 끄기" else "플래시") }
            OutlinedButton(onClick = { focus() }, enabled = ready) { Text("초점") }
            Button(onClick = {
                if (!delivered.compareAndSet(false, true)) return@Button
                captureBusy = true
                capture.takePicture(executor, object : ImageCapture.OnImageCapturedCallback() {
                    override fun onCaptureSuccess(image: ImageProxy) {
                        try {
                            val bitmap = CameraFrames.photo(image, if (scanOnly) guide else null)
                            main.execute { if (active.get()) { captureBusy = false; currentPhoto(bitmap, null) } }
                        } catch (e: Exception) { main.execute { if (active.get()) { delivered.set(false); captureBusy = false; currentError("사진 처리 실패: ${e.message}") } } }
                          finally { image.close() }
                    }
                    override fun onError(exception: ImageCaptureException) { main.execute { if (active.get()) { delivered.set(false); captureBusy = false; currentError("촬영 실패: ${exception.message}") } } }
                })
            }, enabled = ready && !captureBusy && !focusing.get(), modifier = Modifier.weight(1f)) { Text(if (captureBusy) "촬영 중" else "사진으로 인식") }
        }
    }
}
