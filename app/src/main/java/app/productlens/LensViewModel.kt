package app.productlens

import android.app.Application
import android.graphics.Bitmap
import android.graphics.RectF
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class LensViewModel(app: Application) : AndroidViewModel(app) {
    val prefs = app.getSharedPreferences("settings", 0)
    private val store = LensStore(app)
    private val vision = Vision(app)
    private val source: ProductSource = OpenFactsSource(app, store)
    var page by mutableStateOf("home")
    var busy by mutableStateOf(false); private set
    var status by mutableStateOf(""); private set
    var error by mutableStateOf("")
    var photo by mutableStateOf<Bitmap?>(null); private set
    var regions by mutableStateOf<List<Region>>(emptyList()); private set
    var region by mutableStateOf(RectF(0f, 0f, 1f, 1f))
    var result by mutableStateOf<ScanResult?>(null); private set
    var history by mutableStateOf<List<ScanResult>>(emptyList()); private set
    var catalog by mutableStateOf<List<CatalogItem>>(emptyList()); private set
    var query by mutableStateOf("")
    private var photoNotes = emptyList<String>()
    init {
        work("기록을 불러오는 중") {
            withContext(Dispatchers.IO) { store.removeUnreferencedPhotos() }
            refreshInternal()
        }
    }
    fun enabled(key: String) = prefs.getBoolean(key, true)
    fun setting(key: String, enabled: Boolean) { prefs.edit().putBoolean(key, enabled).apply() }
    private fun work(label: String, action: suspend () -> Unit) {
        if (busy) return
        viewModelScope.launch {
            busy = true; status = label
            try { action() }
            catch (e: CancellationException) { throw e }
            catch (_: OutOfMemoryError) { error = "사진을 처리할 메모리가 부족합니다. 다른 앱을 닫고 더 작은 사진으로 시도하세요." }
            catch (_: LinkageError) { error = "이 기기에서 추론 라이브러리를 불러올 수 없습니다. ARM64 지원 및 앱 설치 상태를 확인하세요." }
            catch (e: Exception) { error = e.message ?: "처리 중 오류가 발생했습니다. 다시 시도하세요." }
            finally { busy = false; status = "" }
        }
    }
    fun load(uri: Uri) = work("사진을 불러오고 주요 영역을 찾는 중") {
        val b = withContext(Dispatchers.IO) { Images.load(getApplication(), uri) }
        prepare(b); page = "crop"
    }
    fun cameraPhoto(bitmap: Bitmap, code: Code? = null) = work("촬영 사진을 처리하는 중") {
        prepare(bitmap)
        if (code != null) analyzeInternal(bitmap, code) else page = "crop"
    }
    private suspend fun prepare(bitmap: Bitmap) {
        photo = bitmap; photoNotes = emptyList()
        regions = if (enabled("detection")) try { withContext(Dispatchers.Default) { vision.regions(bitmap) } }
            catch (e: Exception) { photoNotes = listOf("객체 검출을 사용할 수 없습니다. 직접 영역을 선택하세요: ${e.message?.take(100)}"); emptyList() }
            else emptyList()
        region = regions.firstOrNull()?.box?.let(::RectF) ?: RectF(0f, 0f, 1f, 1f)
    }
    fun analyze() { val b = photo ?: return; work("바코드 · 한글 OCR · 등록 사진을 함께 분석하는 중") { analyzeInternal(b) } }
    private suspend fun analyzeInternal(bitmap: Bitmap, known: Code? = null) {
        val notes = photoNotes.toMutableList()
        val crop = withContext(Dispatchers.Default) { Images.crop(bitmap, region) }
        val code = known ?: withContext(Dispatchers.Default) { Images.decode(bitmap) ?: Images.decode(crop) }
        val text = if (enabled("ocr")) try { withContext(Dispatchers.Default) { vision.text(bitmap) } }
            catch (e: Exception) { notes += "OCR 실행 실패: ${e.message?.take(100)}"; "" } else ""
        val vector = if (enabled("embedding")) try { withContext(Dispatchers.Default) { vision.embedding(crop) } }
            catch (e: Exception) { notes += "이미지 모델 실행 실패: ${e.message?.take(100)}"; null } else null
        val candidates = withContext(Dispatchers.IO) { Fusion.rank(store.catalog(), vector, code, text) }
        var product: Product? = null
        val gtin = code?.let(BarcodeRules::lookup)
        if (gtin != null && enabled("online")) { status = "공개 상품 DB를 조회하는 중"; val lookup = source.lookup(gtin); product = lookup.product; notes += lookup.note }
        else if (gtin != null) notes += "온라인 조회 꺼짐 · 바코드와 사진 인식은 기기에서 처리했습니다."
        else if (code != null) notes += "상품 바코드가 아니거나 체크섬이 올바르지 않아 DB 조회하지 않았습니다."
        if (product != null && text.isNotBlank()) {
            if (product.brand.isNotBlank()) notes += if (product.brand.split(',').map(String::trim).filter(String::isNotEmpty).any { text.contains(it, true) }) "DB 브랜드와 OCR 텍스트 교차 일치" else "DB 브랜드를 OCR에서 확인하지 못했습니다. 상품 라벨을 확인하세요."
        }
        if (candidates.isEmpty()) notes += "비교할 등록 상품이 없거나 일치 근거가 없습니다. 사진과 상품명을 내 카탈로그에 등록할 수 있습니다."
        val path = withContext(Dispatchers.IO) { Images.save(getApplication(), crop) }
        result = ScanResult(code, text, TextClues.extract(text), product, candidates, notes, path, vector,
            regions.firstOrNull { it.box == region }?.label.orEmpty())
        query = product?.let { listOf(it.brand, it.name, it.quantity).filter(String::isNotBlank).joinToString(" ") } ?: result!!.clues.query.ifBlank { code?.value.orEmpty() }
        if (enabled("history")) result = withContext(Dispatchers.IO) { store.save(result!!) }
        page = "result"; refreshInternal()
    }
    fun manual(value: String) {
        val normalized = value.trim().replace(" ", "").replace("-", "")
        if (!BarcodeRules.validGtin(normalized)) { error = "EAN-8/13, UPC-A 또는 GTIN-14의 숫자와 체크섬을 확인하세요. UPC-E는 카메라로 스캔하세요."; return }
        work("바코드로 공개 상품 DB를 조회하는 중") {
            val lookup = if (enabled("online")) source.lookup(normalized) else Lookup(null, "온라인 조회가 꺼져 있습니다.")
            val code = Code(normalized, "MANUAL")
            result = ScanResult(code = code, product = lookup.product, candidates = withContext(Dispatchers.IO) { Fusion.rank(store.catalog(), null, code, "") }, messages = listOf(lookup.note))
            query = lookup.product?.let { "${it.brand} ${it.name}" } ?: normalized
            if (enabled("history")) result = withContext(Dispatchers.IO) { store.save(result!!) }
            page = "result"; refreshInternal()
        }
    }
    fun editText(text: String) {
        result = result?.copy(text = text, clues = TextClues.extract(text))
        query = result?.clues?.query.orEmpty()
        work("수정한 텍스트로 후보를 다시 비교하는 중") {
            val r = result ?: return@work
            result = r.copy(candidates = withContext(Dispatchers.IO) { Fusion.rank(store.catalog(), r.vector, r.code, text) })
            if (r.id > 0) result = withContext(Dispatchers.IO) { store.save(result!!) }
        }
    }
    fun save() = work("기록을 저장하는 중") { result?.let { result = withContext(Dispatchers.IO) { store.save(it) } }; refreshInternal() }
    fun retryLookup() = work("공개 상품 DB를 다시 조회하는 중") {
        val r = result ?: return@work
        val code = r.code?.let(BarcodeRules::lookup) ?: return@work
        val lookup = if (enabled("online")) source.lookup(code) else Lookup(null, "온라인 조회가 꺼져 있습니다.")
        result = r.copy(product = lookup.product, messages = r.messages + lookup.note)
        lookup.product?.let { query = "${it.brand} ${it.name}" }
        if (r.id > 0) result = withContext(Dispatchers.IO) { store.save(result!!) }
        refreshInternal()
    }
    fun register(name: String, brand: String, model: String) = work("사진 특징과 상품을 기기에 등록하는 중") {
        val r = result ?: return@work
        val vector = r.vector ?: withContext(Dispatchers.IO) {
            val file = File(r.photo); require(file.isFile) { "등록할 사진이 없습니다." }
            val b = Images.load(getApplication(), Uri.fromFile(file)); vision.embedding(b)
        }
        require(name.isNotBlank()) { "상품명을 입력하세요." }
        withContext(Dispatchers.IO) { store.register(Product(listOf(name.trim(), model.trim()).filter(String::isNotBlank).joinToString(" "), brand.trim(), r.code?.let(BarcodeRules::lookup).orEmpty(), r.clues.size, image = r.photo, source = "사용자 등록"), vector) }
        refreshInternal(); error = "내 카탈로그에 등록했습니다. 다른 각도의 사진도 같은 이름으로 등록하면 비교에 도움이 됩니다."
    }
    fun open(record: ScanResult) { result = record; query = record.product?.name ?: record.clues.query.ifBlank { record.code?.value.orEmpty() }; page = "result" }
    fun delete(table: String, id: Long? = null) = work("기기 데이터를 삭제하는 중") {
        withContext(Dispatchers.IO) { store.delete(table, id) }; if (table == "history" && (id == null || result?.id == id)) result = null
        refreshInternal()
    }
    fun refresh() = work("기록을 불러오는 중") { refreshInternal() }
    private suspend fun refreshInternal() { withContext(Dispatchers.IO) { history = store.history(); catalog = store.catalog() } }
    override fun onCleared() { vision.close(); store.close() }
}
