package app.productlens

import android.content.Intent
import android.graphics.RectF
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val colors = if (isSystemInDarkTheme()) darkColorScheme(primary = Color(0xFF6EDBD4), secondary = Color(0xFFFFB780))
                else lightColorScheme(primary = Color(0xFF087E83), secondary = Color(0xFFB9531E), background = Color(0xFFF5F8F8), surface = Color(0xFFFDFEFE))
            MaterialTheme(colorScheme = colors, shapes = Shapes(medium = RoundedCornerShape(18.dp), large = RoundedCornerShape(24.dp))) { LensApp() }
        }
    }
}

@Composable private fun onlineState(): Boolean {
    val ctx = LocalContext.current
    val manager = remember { ctx.getSystemService(ConnectivityManager::class.java) }
    fun online() = manager.getNetworkCapabilities(manager.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
    var connected by remember { mutableStateOf(online()) }
    DisposableEffect(manager) {
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) { connected = online() }
            override fun onLost(network: Network) { connected = online() }
        }
        manager.registerDefaultNetworkCallback(callback)
        onDispose { manager.unregisterNetworkCallback(callback) }
    }
    return connected
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun LensApp(vm: LensViewModel = viewModel()) {
    val updates: UpdateViewModel = viewModel()
    val context = LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { it?.let(vm::load) }
    fun pick() = picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    var manual by remember { mutableStateOf(false) }
    var manualValue by remember { mutableStateOf("") }
    var scanOnly by remember { mutableStateOf(false) }
    val online = onlineState()
    BackHandler(vm.page !in listOf("home", "history", "catalog", "settings") || vm.busy) { if (!vm.busy) vm.page = "home" }
    fun open(url: String) {
        try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
        catch (_: android.content.ActivityNotFoundException) { vm.error = "링크를 열 브라우저가 없습니다." }
    }
    Scaffold(topBar = {
        TopAppBar(title = { Column { Text("제품렌즈", fontWeight = FontWeight.Bold); Text("사진에서 시작하는 제품 찾기", style = MaterialTheme.typography.labelSmall) } },
            navigationIcon = { if (vm.page !in listOf("home", "history", "catalog", "settings")) IconButton(onClick = { if (!vm.busy) vm.page = "home" }) { Icon(Icons.Outlined.ArrowBack, "홈으로 돌아가기") } },
            actions = { if (!online) Text("오프라인", color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(end = 16.dp)) })
    }, bottomBar = {
        if (vm.page != "camera") NavigationBar {
            listOf(Triple("home", "인식", Icons.Outlined.CenterFocusStrong), Triple("history", "기록", Icons.Outlined.History), Triple("catalog", "내 상품", Icons.Outlined.Inventory2), Triple("settings", "설정", Icons.Outlined.Settings)).forEach { (page, title, icon) ->
                NavigationBarItem(selected = vm.page == page, onClick = { if (!vm.busy) { vm.page = page; if (page in listOf("history", "catalog")) vm.refresh() } }, icon = { Icon(icon, null) }, label = { Text(title) })
            }
        }
    }) { insets ->
        Box(Modifier.fillMaxSize().padding(insets)) {
            when (vm.page) {
                "home" -> ScrollPage {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Icon(Icons.Outlined.CenterFocusStrong, null, Modifier.size(42.dp))
                            Text("이 제품, 무엇일까요?", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                            Text("바코드와 포장 글자를 읽고,\n내가 등록한 사진과 함께 비교해요.")
                            Button(onClick = { scanOnly = false; vm.page = "camera" }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Outlined.PhotoCamera, null); Spacer(Modifier.width(8.dp)); Text("카메라로 제품 인식") }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedButton(onClick = { scanOnly = true; vm.page = "camera" }, modifier = Modifier.weight(1f)) { Text("바코드 스캔") }
                        OutlinedButton(onClick = ::pick, modifier = Modifier.weight(1f)) { Text("갤러리 이미지") }
                    }
                    TextButton(onClick = { manual = true }) { Text("바코드 번호 직접 입력") }
                    Title("상품 검색")
                    SearchField(vm.query, { vm.query = it }, "제품명 · 브랜드 · 모델번호")
                    SearchButtons(vm.query, ::open)
                    Info("사진·OCR·이미지 특징은 기기에서 처리합니다. 온라인 조회 시 바코드 번호만 공개 DB로 전송합니다. 외부 검색은 검색어를 브라우저에 전달합니다.")
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) { Title("최근 인식"); TextButton(onClick = { vm.page = "history" }) { Text("전체 보기") } }
                    if (vm.history.isEmpty()) Info("아직 인식 기록이 없어요. 제품 앞면이나 바코드를 촬영해 보세요.")
                    vm.history.take(3).forEach { r -> HistoryCard(r, { vm.open(r) }) }
                }
                "camera" -> CameraScreen(scanOnly, vm::cameraPhoto, { vm.error = it }, { vm.page = "home" })
                "crop" -> CropScreen(vm)
                "result" -> vm.result?.let { ResultScreen(vm, it, ::open) } ?: ScrollPage { Info("기록이 삭제되었습니다."); Button(onClick = { vm.page = "home" }) { Text("다시 인식하기") } }
                "history" -> HistoryScreen(vm)
                "catalog" -> CatalogScreen(vm, ::pick)
                "settings" -> SettingsScreen(vm, updates, ::open)
            }
            if (vm.busy) Surface(Modifier.fillMaxSize().clickable(enabled = true, onClick = {}), color = MaterialTheme.colorScheme.scrim.copy(alpha = .55f)) {
                Box(contentAlignment = Alignment.Center) { Card { Column(Modifier.padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(20.dp)) { CircularProgressIndicator(); Text(vm.status) } } }
            }
        }
    }
    if (!vm.busy && vm.error.isEmpty() && vm.page == "home") StartupUpdateNotice(updates) { vm.page = "settings" }
    if (vm.error.isNotEmpty()) AlertDialog(onDismissRequest = { vm.error = "" }, title = { Text("안내") }, text = { Text(vm.error) }, confirmButton = { TextButton(onClick = { vm.error = "" }) { Text("확인") } })
    if (manual) AlertDialog(onDismissRequest = { manual = false }, title = { Text("바코드 직접 입력") }, text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { Text("8·12·13·14자리 상품 바코드를 입력하세요."); OutlinedTextField(manualValue, { manualValue = it.take(32) }, label = { Text("바코드 번호") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true) } },
        confirmButton = { TextButton(onClick = { manual = false; vm.manual(manualValue) }) { Text("조회") } }, dismissButton = { TextButton(onClick = { manual = false }) { Text("취소") } })
}

@Composable private fun ScrollPage(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(16.dp), content = content)
}
@Composable private fun Title(text: String) { Text(text, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) }
@Composable private fun Info(text: String) { Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
@Composable private fun SearchField(value: String, onChange: (String) -> Unit, label: String) { OutlinedTextField(value, { onChange(it.take(200)) }, label = { Text(label) }, modifier = Modifier.fillMaxWidth(), singleLine = true) }
@Composable private fun SearchButtons(query: String, open: (String) -> Unit) {
    val q = URLEncoder.encode(query.trim().take(200), "UTF-8")
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Button(onClick = { open("https://search.shopping.naver.com/search/all?query=$q") }, enabled = query.isNotBlank(), modifier = Modifier.weight(1f)) { Text("네이버 쇼핑") }
        OutlinedButton(onClick = { open("https://www.google.com/search?tbm=shop&q=$q") }, enabled = query.isNotBlank(), modifier = Modifier.weight(1f)) { Text("Google 검색") }
    }
}

@Composable private fun CropScreen(vm: LensViewModel) {
    val bitmap = vm.photo ?: return
    ScrollPage {
        Title("제품 영역 선택")
        Info("제품을 감싸도록 사진 위를 드래그하세요. OCR과 바코드는 원본도 읽고, 이미지 비교에는 선택 영역을 사용합니다.")
        var start by remember { mutableStateOf(Offset.Zero) }
        Box(Modifier.fillMaxWidth().aspectRatio(bitmap.width.toFloat() / bitmap.height)) {
            Image(bitmap.asImageBitmap(), "분석할 제품 사진", Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
            Canvas(Modifier.fillMaxSize().pointerInput(bitmap) {
                detectDragGestures(onDragStart = { start = Offset((it.x / size.width).coerceIn(0f, 1f), (it.y / size.height).coerceIn(0f, 1f)) }, onDragEnd = { if (vm.region.width() < .03f || vm.region.height() < .03f) vm.region = RectF(0f, 0f, 1f, 1f) }) { change, _ ->
                    change.consume()
                    val end = Offset((change.position.x / size.width).coerceIn(0f, 1f), (change.position.y / size.height).coerceIn(0f, 1f))
                    vm.region = RectF(minOf(start.x, end.x), minOf(start.y, end.y), maxOf(start.x, end.x), maxOf(start.y, end.y))
                }
            }) {
                val r = vm.region
                drawRect(Color(0xFF23E4CB), Offset(r.left * size.width, r.top * size.height), androidx.compose.ui.geometry.Size(r.width() * size.width, r.height() * size.height), style = Stroke(3.dp.toPx()))
            }
        }
        TextButton(onClick = { vm.region = RectF(0f, 0f, 1f, 1f) }) { Text("사진 전체 사용") }
        vm.regions.forEach { region -> OutlinedButton(onClick = { vm.region = RectF(region.box) }, modifier = Modifier.fillMaxWidth()) { Text("자동 검출 영역: ${region.label}") } }
        if (vm.regions.isEmpty()) Info("자동 검출 영역이 없습니다. 전체 사진이나 직접 선택한 영역으로 인식할 수 있습니다.")
        Button(onClick = vm::analyze, modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp)) { Text("이 영역으로 제품 찾기") }
    }
}

@Composable private fun ResultScreen(vm: LensViewModel, r: ScanResult, open: (String) -> Unit) {
    var edit by remember(r.time) { mutableStateOf(r.text) }
    var register by remember { mutableStateOf(false) }
    val p = r.product
    ScrollPage {
        if (r.photo.isNotBlank()) AsyncImage(r.photo, "분석한 제품 영역", Modifier.fillMaxWidth().heightIn(min = 100.dp, max = 220.dp), contentScale = ContentScale.Fit)
        Text(if (p != null) "바코드로 공개 상품을 찾았습니다" else "제품을 정확히 식별하지 못했습니다", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
        Title(p?.name ?: r.clues.query.ifBlank { "확인 가능한 근거와 검색어를 확인하세요" })
        p?.let {
            if (it.image.isNotBlank() && vm.enabled("online")) AsyncImage(it.image, "공개 DB의 상품 사진", Modifier.fillMaxWidth().heightIn(max = 180.dp))
            SelectionContainer { Text(listOf("브랜드: ${it.brand.ifBlank { "정보 없음" }}", "규격: ${it.quantity.ifBlank { "정보 없음" }}", "제조 장소: ${it.manufacturer.ifBlank { "정보 없음" }}", "카테고리: ${it.categories.ifBlank { "정보 없음" }}", it.description).filter(String::isNotBlank).joinToString("\n")) }
            Info("출처: ${it.source}\nOpen Facts · 데이터 ODbL / 개별 내용 DbCL / 사진 CC BY-SA 3.0")
            TextButton(onClick = { open("https://${it.source}/product/${it.barcode}") }) { Text("출처 상품 페이지 확인") }
        }
        r.code?.let { code -> Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { Text("인식 코드 · ${code.format}", fontWeight = FontWeight.Bold); SelectionContainer { Text(code.value) }; Text(if (BarcodeRules.lookup(code) != null) "상품 바코드 체크섬 유효" else "일반 코드 또는 상품 바코드 검증 불가", style = MaterialTheme.typography.bodySmall) } } }
        Title("인식 근거")
        if (r.category.isNotBlank()) Info("범용 객체 모델: ${r.category}\n검출 점수는 영역·범주 판단이며 상품 식별 정확도가 아닙니다.")
        r.messages.forEach { Info("• $it") }
        if (p == null && r.code?.let(BarcodeRules::lookup) != null) TextButton(onClick = vm::retryLookup) { Text("상품 DB 다시 조회") }
        if (r.text.isNotBlank()) {
            Info("아래 브랜드·제품명·모델 추출은 텍스트 규칙에 따른 검색 제안입니다. 사용자가 확인하고 수정할 수 있습니다.")
            OutlinedTextField(edit, { edit = it.take(12000) }, label = { Text("인식 텍스트 수정") }, modifier = Modifier.fillMaxWidth(), minLines = 3, maxLines = 8)
            TextButton(onClick = { vm.editText(edit) }) { Text("수정한 글자로 후보 다시 비교") }
            Info("브랜드 후보: ${r.clues.brand.ifBlank { "없음" }}\n모델번호: ${r.clues.model.ifBlank { "없음" }}\n규격: ${r.clues.size.ifBlank { "없음" }}")
        }
        Title("내 상품의 유사 후보")
        Info("코사인 유사도는 사진 특징 사이의 수학적 유사도입니다. 정답 확률이 아니며, 유사 후보의 실제 모델을 확인하세요.")
        if (r.candidates.isEmpty()) Info("표시할 후보가 없습니다. 아래에서 상품 사진과 이름을 등록해 보세요.")
        r.candidates.forEach { c -> Card(Modifier.fillMaxWidth()) { Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            AsyncImage(c.item.product.image, "등록 후보 사진", Modifier.size(72.dp), contentScale = ContentScale.Crop)
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) { Text(c.item.product.name, fontWeight = FontWeight.Bold); Text(c.item.product.brand); c.cosine?.let { Text("코사인 유사도 ${String.format(Locale.US, "%.3f", it)}") }; c.reasons.forEach { Text(it, style = MaterialTheme.typography.bodySmall) } }
        } } }
        Title("검색어 확인 및 검색")
        SearchField(vm.query, { vm.query = it }, "제품명 · 브랜드 · 모델명")
        SearchButtons(vm.query, open)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = vm::save, modifier = Modifier.weight(1f)) { Text(if (r.id > 0) "기록 저장됨" else "기록 저장") }
            Button(onClick = { register = true }, enabled = r.photo.isNotBlank(), modifier = Modifier.weight(1f)) { Text("내 상품 등록") }
        }
    }
    if (register) {
        var name by remember { mutableStateOf(p?.name ?: r.clues.query) }
        var brand by remember { mutableStateOf(p?.brand ?: r.clues.brand) }
        var model by remember { mutableStateOf("") }
        AlertDialog(onDismissRequest = { register = false }, title = { Text("내 상품 등록") }, text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Info("선택한 제품 영역과 특징 벡터를 기기에만 저장합니다. 이름이 같은 여러 사진을 등록할 수 있습니다.")
            SearchField(name, { name = it }, "제품명 (필수)"); SearchField(brand, { brand = it }, "브랜드"); SearchField(model, { model = it }, "모델번호 (선택)")
        } }, confirmButton = { TextButton(onClick = { register = false; vm.register(name, brand, model) }, enabled = name.isNotBlank()) { Text("등록") } }, dismissButton = { TextButton(onClick = { register = false }) { Text("취소") } })
    }
}

@Composable private fun HistoryCard(r: ScanResult, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) { Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        if (r.photo.isNotBlank()) AsyncImage(r.photo, null, Modifier.size(56.dp), contentScale = ContentScale.Crop) else Icon(Icons.Outlined.QrCodeScanner, null, Modifier.size(36.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) { Text(r.product?.name ?: r.clues.query.ifBlank { r.code?.value ?: "사진 인식" }, maxLines = 2, fontWeight = FontWeight.SemiBold); Text(SimpleDateFormat("MM.dd HH:mm", Locale.KOREA).format(Date(r.time)), style = MaterialTheme.typography.bodySmall) }
        Icon(Icons.Outlined.ChevronRight, null)
    } }
}

@Composable private fun HistoryScreen(vm: LensViewModel) {
    var query by remember { mutableStateOf("") }
    var deletion by remember { mutableStateOf<Long?>(null) }
    var all by remember { mutableStateOf(false) }
    ScrollPage {
        Title("인식 기록")
        SearchField(query, { query = it }, "기록에서 상품명·바코드 검색")
        if (vm.history.isEmpty()) Info("저장된 기록이 없습니다.")
        vm.history.filter { (it.product?.name.orEmpty() + it.clues.query + it.code?.value.orEmpty()).contains(query, true) }.forEach { r ->
            HistoryCard(r, { vm.open(r) }); TextButton(onClick = { deletion = r.id }) { Text("이 기록 삭제") }
        }
        if (vm.history.isNotEmpty()) OutlinedButton(onClick = { all = true }) { Text("기록 전체 삭제") }
    }
    if (deletion != null || all) ConfirmDelete("인식 기록과 더 이상 사용하지 않는 사진을 삭제할까요?", { deletion = null; all = false }) { vm.delete("history", deletion.takeUnless { all }); deletion = null; all = false }
}

@Composable private fun CatalogScreen(vm: LensViewModel, pick: () -> Unit) {
    var deletion by remember { mutableStateOf<Long?>(null) }
    var query by remember { mutableStateOf("") }
    ScrollPage {
        Title("내 상품 카탈로그")
        Info("여러 각도의 사진을 같은 제품명으로 등록하세요. 인터넷 없이 등록 사진들과 이미지 특징을 비교합니다.")
        Button(onClick = pick, modifier = Modifier.fillMaxWidth()) { Text("갤러리 사진으로 상품 등록") }
        SearchField(query, { query = it }, "등록 상품 검색")
        if (vm.catalog.isEmpty()) Info("등록 상품이 없습니다. 사진을 선택하고 인식 결과에서 ‘내 상품 등록’을 눌러 제품명을 입력하세요.")
        vm.catalog.filter { (it.product.name + it.product.brand).contains(query, true) }.forEach { item -> Card(Modifier.fillMaxWidth()) { Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            AsyncImage(item.product.image, "등록 상품 사진", Modifier.size(72.dp), contentScale = ContentScale.Crop)
            Column(Modifier.weight(1f)) { Text(item.product.name, fontWeight = FontWeight.Bold); Text(item.product.brand); Text("${item.vector.size}차원 이미지 특징", style = MaterialTheme.typography.bodySmall) }
            IconButton(onClick = { deletion = item.id }) { Icon(Icons.Outlined.DeleteOutline, "${item.product.name} 삭제") }
        } } }
    }
    deletion?.let { id -> ConfirmDelete("이 상품의 등록 사진과 특징을 삭제할까요?", { deletion = null }) { vm.delete("catalog", id); deletion = null } }
}

@Composable private fun ConfirmDelete(text: String, dismiss: () -> Unit, confirm: () -> Unit) {
    AlertDialog(onDismissRequest = dismiss, title = { Text("삭제 확인") }, text = { Text(text) }, confirmButton = { TextButton(onClick = confirm) { Text("삭제") } }, dismissButton = { TextButton(onClick = dismiss) { Text("취소") } })
}

@Composable private fun SettingsScreen(vm: LensViewModel, updates: UpdateViewModel, open: (String) -> Unit) {
    val context = LocalContext.current
    var license by remember { mutableStateOf(false) }
    var delete by remember { mutableStateOf("") }
    ScrollPage {
        UpdateControls(updates, open)
        Title("인식 설정")
        listOf(Triple("ocr", "한국어·영어 OCR", "제품 포장의 글자를 기기에서 읽습니다"), Triple("detection", "제품 영역 자동 검출", "범용 객체 검출 후 직접 영역 수정 가능"), Triple("embedding", "등록 사진 유사도 비교", "MobileNet V3 이미지 특징 사용"), Triple("online", "공개 상품 DB 온라인 조회", "바코드 번호 전송 및 공개 상품 사진 다운로드"), Triple("history", "인식 기록 자동 저장", "인식 결과와 선택 영역 사진을 기기에 저장")).forEach { (key, title, subtitle) ->
            var checked by remember(key) { mutableStateOf(vm.enabled(key)) }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) { Column(Modifier.weight(1f)) { Text(title, fontWeight = FontWeight.SemiBold); Info(subtitle) }; Switch(checked, { checked = it; vm.setting(key, it) }) }
            HorizontalDivider()
        }
        Title("데이터와 모델")
        Info("OCR 모델, MobileNet V3 Small, EfficientDet Lite0는 앱에 포함되어 있습니다. 별도 모델 다운로드나 로그인이 필요하지 않습니다. 모델을 제거하려면 앱을 삭제하세요.\n\n공개 상품 조회 캐시: 상품 24시간, 미등록 1시간\n등록 사진 ${vm.catalog.size}개 · 최근 기록 ${vm.history.size}개 표시 (최대 200개)")
        OutlinedButton(onClick = { vm.delete("cache") }) { Text("상품 조회 캐시 비우기") }
        OutlinedButton(onClick = { delete = "catalog" }) { Text("내 상품 카탈로그 전체 삭제") }
        OutlinedButton(onClick = { delete = "history" }) { Text("인식 기록 전체 삭제") }
        Title("개인정보 안내")
        Info("카메라·갤러리 사진은 업로드하지 않습니다. OCR·객체 검출·사진 특징은 기기에서 처리합니다. 카메라 권한은 촬영에만 사용하며, 갤러리는 시스템 Photo Picker로 선택한 사진만 읽습니다.\n\n자동 업데이트 확인에는 앱 식별 User-Agent와 IP가 GitHub로 전송됩니다. 설정에서 자동 확인을 끌 수 있습니다.\n\n온라인 조회에는 바코드와 앱 식별 User-Agent, IP가 공개 DB로 전송됩니다. 외부 검색을 누르면 수정한 검색어가 해당 검색 서비스로 전달됩니다. ML Kit은 Google SDK이며 진단·사용 통계가 Google에 전송될 수 있습니다. Google의 약관과 개인정보 안내가 적용됩니다.\n\n기록 자동 저장을 꺼도 현재 결과 표시용 사진은 임시 보관되며 다음 앱 시작 시 저장·등록되지 않은 사진을 정리합니다. 앱 데이터의 OS 백업은 비활성화했습니다. 앱 삭제 시 기기의 기록·카탈로그도 삭제됩니다.")
        TextButton(onClick = { open("https://developers.google.com/ml-kit/terms") }) { Text("ML Kit 약관·개인정보 안내") }
        TextButton(onClick = { license = true }) { Text("오픈소스·모델 라이선스 및 출처") }
        Info("제품렌즈 ${BuildConfig.VERSION_NAME} · 공개 DB와 등록 사진은 정확한 상품을 보장하지 않습니다. 가격 정보는 제공하지 않습니다.")
    }
    if (delete.isNotBlank()) ConfirmDelete("${if (delete == "catalog") "카탈로그" else "기록"}의 모든 기기 데이터를 삭제할까요?", { delete = "" }) { vm.delete(delete); delete = "" }
    if (license) AlertDialog(onDismissRequest = { license = false }, title = { Text("라이선스 및 출처") }, text = {
        val text = remember { context.assets.open("licenses.txt").bufferedReader().use { it.readText() } }
        SelectionContainer { Text(text, modifier = Modifier.verticalScroll(rememberScrollState())) }
    }, confirmButton = { TextButton(onClick = { license = false }) { Text("닫기") } })
}
