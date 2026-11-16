package app.productlens

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

data class ScanResult(val code: Code? = null, val text: String = "", val clues: Clues = TextClues.extract(text),
    val product: Product? = null, val candidates: List<Candidate> = emptyList(), val messages: List<String> = emptyList(),
    val photo: String = "", val vector: FloatArray? = null, val category: String = "", val id: Long = 0, val time: Long = System.currentTimeMillis()) {
    val title get() = product?.name ?: code?.let(BarcodeRules::lookup)?.let { "바코드 $it" } ?: clues.query.ifBlank { code?.value ?: "사진 인식" }
}

fun Product.json() = JSONObject().put("name", name).put("brand", brand).put("barcode", barcode).put("quantity", quantity)
    .put("categories", categories).put("image", image).put("source", source).put("manufacturer", manufacturer).put("description", description)
fun productJson(j: JSONObject) = Product(j.optString("name"), j.optString("brand"), j.optString("barcode"), j.optString("quantity"),
    j.optString("categories"), j.optString("image"), j.optString("source"), j.optString("manufacturer"), j.optString("description"))
private fun ScanResult.json() = JSONObject().put("code", code?.let { JSONObject().put("value", it.value).put("format", it.format) })
    .put("text", text).put("query", clues.query).put("brand", clues.brand).put("model", clues.model).put("size", clues.size)
    .put("product", product?.json()).put("messages", JSONArray(messages)).put("photo", photo).put("category", category)
    .put("candidates", JSONArray(candidates.map { c -> JSONObject().put("id", c.item.id).put("product", c.item.product.json())
        .put("cosine", c.cosine).put("reasons", JSONArray(c.reasons)) }))
private fun strings(a: JSONArray?): List<String> = if (a == null) emptyList() else (0 until a.length()).map(a::getString)
private fun scanJson(j: JSONObject, id: Long, time: Long): ScanResult {
    val a = j.optJSONArray("candidates") ?: JSONArray()
    return ScanResult(j.optJSONObject("code")?.let { Code(it.getString("value"), it.getString("format")) }, j.optString("text"),
        Clues(j.optString("query"), j.optString("brand"), j.optString("model"), j.optString("size")), j.optJSONObject("product")?.let(::productJson),
        (0 until a.length()).map { i -> val c = a.getJSONObject(i); Candidate(CatalogItem(c.getLong("id"), productJson(c.getJSONObject("product")), floatArrayOf()),
            if (c.has("cosine") && !c.isNull("cosine")) c.getDouble("cosine") else null, strings(c.optJSONArray("reasons")), 0.0) },
        strings(j.optJSONArray("messages")), j.optString("photo"), null, j.optString("category"), id, time)
}

class LensStore(context: Context, name: String = "lens.db") : SQLiteOpenHelper(context, name, null, 2) {
    private val photoDir = File(context.filesDir, "photos").apply { mkdirs() }
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE history(id INTEGER PRIMARY KEY AUTOINCREMENT,time INTEGER NOT NULL,name TEXT NOT NULL,payload TEXT NOT NULL,photo TEXT NOT NULL)")
        db.execSQL("CREATE TABLE catalog(id INTEGER PRIMARY KEY AUTOINCREMENT,name TEXT NOT NULL,product TEXT NOT NULL,vector TEXT NOT NULL,photo TEXT NOT NULL)")
        db.execSQL("CREATE TABLE cache(code TEXT PRIMARY KEY,time INTEGER NOT NULL,product TEXT)")
        db.execSQL("CREATE TABLE barcode_names(code TEXT PRIMARY KEY,product TEXT NOT NULL)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        check(oldVersion == 1 && newVersion == 2) { "지원하지 않는 DB 버전: $oldVersion → $newVersion" }
        db.execSQL("CREATE TABLE IF NOT EXISTS barcode_names(code TEXT PRIMARY KEY,product TEXT NOT NULL)")
    }
    @Synchronized fun barcodeNames(): List<Product> = readableDatabase.rawQuery("SELECT product FROM barcode_names ORDER BY code", null).use { c ->
        buildList { while (c.moveToNext()) add(productJson(JSONObject(c.getString(0)))) }
    }
    @Synchronized fun saveBarcodeName(product: Product) {
        require(BarcodeRules.validGtin(product.barcode) && product.name.isNotBlank())
        writableDatabase.insertWithOnConflict("barcode_names", null, ContentValues().apply {
            put("code", product.barcode.padStart(14, '0')); put("product", product.copy(image = "", source = "사용자 등록").json().toString())
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }
    @Synchronized fun deleteBarcodeName(code: String?) { writableDatabase.delete("barcode_names", if (code == null) null else "code=?", code?.let { arrayOf(it.padStart(14, '0')) }) }
    @Synchronized fun findBarcode(code: String): Product? {
        readableDatabase.query("barcode_names", arrayOf("product"), "code=?", arrayOf(code.padStart(14, '0')), null, null, null).use { c ->
            if (c.moveToFirst()) return productJson(JSONObject(c.getString(0)))
        }
        readableDatabase.rawQuery("SELECT product FROM catalog", null).use { c ->
            while (c.moveToNext()) productJson(JSONObject(c.getString(0))).let { if (sameGtin(it.barcode, code)) return it }
        }
        return null
    }
    @Synchronized fun save(result: ScanResult): ScanResult {
        val cv = ContentValues().apply { put("time", result.time); put("name", result.title); put("payload", result.json().toString()); put("photo", result.photo) }
        val id = if (result.id > 0) { writableDatabase.update("history", cv, "id=?", arrayOf(result.id.toString())); result.id }
            else writableDatabase.insertOrThrow("history", null, cv)
        return result.copy(id = id)
    }
    @Synchronized fun history(query: String = ""): List<ScanResult> = readableDatabase.query("history", null,
        "name LIKE ? ESCAPE '\\'", arrayOf("%${query.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")}%"), null, null, "time DESC", "200").use { c ->
        buildList { while (c.moveToNext()) add(scanJson(JSONObject(c.getString(c.getColumnIndexOrThrow("payload"))), c.getLong(0), c.getLong(1))) }
    }
    @Synchronized fun register(product: Product, vector: FloatArray): Long {
        require(product.name.isNotBlank() && vector.isNotEmpty() && vector.all { it.isFinite() })
        return writableDatabase.insertOrThrow("catalog", null, ContentValues().apply { put("name", product.name); put("product", product.json().toString()); put("vector", JSONArray(vector.toList()).toString()); put("photo", product.image) })
    }
    @Synchronized fun catalog(): List<CatalogItem> = readableDatabase.query("catalog", null, null, null, null, null, "id DESC").use { c ->
        buildList { while (c.moveToNext()) { val a = JSONArray(c.getString(3)); add(CatalogItem(c.getLong(0), productJson(JSONObject(c.getString(2))), FloatArray(a.length()) { a.getDouble(it).toFloat() })) } }
    }
    @Synchronized fun delete(table: String, id: Long? = null) {
        require(table in setOf("history", "catalog", "cache"))
        val deletedPhotos = mutableSetOf<String>()
        if (table != "cache") readableDatabase.query(table, arrayOf("photo"), if (id == null) null else "id=?", id?.let { arrayOf(it.toString()) }, null, null, null).use { c -> while (c.moveToNext()) deletedPhotos += c.getString(0) }
        writableDatabase.delete(table, if (id == null) null else "id=?", id?.let { arrayOf(it.toString()) })
        val used = mutableSetOf<String>()
        for (t in listOf("history", "catalog")) readableDatabase.rawQuery("SELECT photo FROM $t", null).use { c -> while (c.moveToNext()) used += c.getString(0) }
        photoDir.listFiles()?.filter { it.absolutePath in deletedPhotos && it.absolutePath !in used }?.forEach { it.delete() }
    }
    @Synchronized fun cached(code: String): Pair<Boolean, Product?> {
        readableDatabase.query("cache", null, "code=?", arrayOf(code), null, null, null).use { c ->
            if (!c.moveToFirst()) return false to null
            val hasProduct = !c.isNull(2)
            if (System.currentTimeMillis() - c.getLong(1) > if (hasProduct) 86400000 else 3600000) return false to null
            return true to if (hasProduct) productJson(JSONObject(c.getString(2))) else null
        }
    }
    @Synchronized fun cache(code: String, product: Product?) {
        writableDatabase.insertWithOnConflict("cache", null, ContentValues().apply { put("code", code); put("time", System.currentTimeMillis()); put("product", product?.json()?.toString()) }, SQLiteDatabase.CONFLICT_REPLACE)
    }
    @Synchronized fun removeUnreferencedPhotos() {
        val used = mutableSetOf<String>()
        for (table in listOf("history", "catalog")) readableDatabase.rawQuery("SELECT photo FROM $table", null).use { c -> while (c.moveToNext()) used += c.getString(0) }
        photoDir.listFiles()?.filter { it.absolutePath !in used }?.forEach { it.delete() }
    }
}

enum class LookupState { FOUND, NOT_FOUND, ERROR, RATE_LIMITED, OFFLINE }
data class Lookup(val product: Product?, val note: String, val state: LookupState = if (product != null) LookupState.FOUND else LookupState.NOT_FOUND)
interface ProductSource { suspend fun lookup(barcode: String, refresh: Boolean = false): Lookup }

class OpenFactsSource(private val context: Context, private val store: LensStore,
    private val baseUrl: String = "https://world.openfoodfacts.org", private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS).readTimeout(8, TimeUnit.SECONDS).callTimeout(12, TimeUnit.SECONDS)
        .followRedirects(false).retryOnConnectionFailure(false).build()) : ProductSource {
    private val mutex = Mutex()
    private val prefs = context.getSharedPreferences("off-rate", Context.MODE_PRIVATE)
    override suspend fun lookup(barcode: String, refresh: Boolean): Lookup = withContext(Dispatchers.IO) { mutex.withLock {
        require(BarcodeRules.validGtin(barcode))
        val cached = store.cached(barcode)
        if (!refresh && cached.first) return@withLock Lookup(cached.second, "Open Facts · ${if (cached.second == null) "미등록 응답 캐시" else "상품 캐시"} (상품 24시간 / 미등록 1시간)")
        val now = System.currentTimeMillis()
        val calls = prefs.getString("calls", "").orEmpty().split(',').mapNotNull(String::toLongOrNull).filter { now - it in 0..59999 }.toMutableList()
        if (calls.size >= 12 || calls.lastOrNull()?.let { now - it < 5000 } == true || prefs.getLong("until", 0) > now)
            return@withLock Lookup(null, "Open Facts API 호출 제한 보호 중입니다. 잠시 후 다시 조회하세요.", LookupState.RATE_LIMITED)
        fun reserve() { calls += System.currentTimeMillis(); prefs.edit().putString("calls", calls.joinToString(",")).commit() }
        val fields = "code,product_name,product_name_ko,brands,quantity,categories,image_front_url,manufacturing_places,generic_name,product_type"
        var url = "$baseUrl/api/v3/product/$barcode?product_type=all&lc=ko&fields=$fields"
        try {
            repeat(3) {
                if (calls.size >= 12) return@withLock Lookup(null, "Open Facts API 호출 제한 보호 중입니다.", LookupState.RATE_LIMITED)
                reserve()
                val request = Request.Builder().url(url).header("User-Agent", "ProductLens/${BuildConfig.VERSION_NAME} (https://github.com/LibraLightLuda/ProductLens)").header("Accept", "application/json").build()
                client.newCall(request).execute().use { response ->
                    if (response.code == 429) {
                        val delay = response.header("Retry-After")?.toLongOrNull()?.coerceIn(60, 86400) ?: 60
                        prefs.edit().putLong("until", System.currentTimeMillis() + delay * 1000).commit()
                        return@withLock Lookup(null, "Open Facts 호출 제한입니다. ${delay}초 이후 다시 시도하세요.", LookupState.RATE_LIMITED)
                    }
                    if (response.code in listOf(301, 302, 307, 308)) {
                        val next = response.request.url.resolve(response.header("Location").orEmpty()) ?: throw IOException("올바르지 않은 이동 주소")
                        val allowed = setOf("world.openfoodfacts.org", "world.openbeautyfacts.org", "world.openpetfoodfacts.org", "world.openproductsfacts.org")
                        if (next.scheme != "https" || next.host !in allowed) throw IOException("허용되지 않은 데이터 출처")
                        url = next.toString(); return@repeat
                    }
                    val raw = response.body?.byteStream()?.readLimited(1024 * 1024)?.toString(Charsets.UTF_8) ?: throw IOException("응답이 비어 있습니다")
                    val result = parseOpenFacts(raw, response.code, barcode, response.request.url.host)
                    if (result.state in setOf(LookupState.FOUND, LookupState.NOT_FOUND)) store.cache(barcode, result.product)
                    return@withLock result
                }
            }
            Lookup(null, "데이터 출처 이동 횟수를 초과했습니다.", LookupState.ERROR)
        } catch (e: IOException) { Lookup(null, "Open Facts 연결 실패: ${e.message}. 오프라인 인식은 계속 사용할 수 있습니다.", LookupState.ERROR) }
          catch (e: org.json.JSONException) { Lookup(null, "Open Facts 응답을 읽지 못했습니다. 미등록 여부를 확인할 수 없습니다.", LookupState.ERROR) }
    } }
}
