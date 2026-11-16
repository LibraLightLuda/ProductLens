package app.productlens

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

internal fun sameGtin(a: String, b: String) = BarcodeRules.validGtin(a) && BarcodeRules.validGtin(b) && a.padStart(14, '0') == b.padStart(14, '0')
private fun JSONObject.text(key: String) = optString(key).takeUnless { it == "null" }.orEmpty().trim()

internal fun parseOpenFacts(raw: String, http: Int, barcode: String, host: String = "world.openfoodfacts.org"): Lookup {
    if (http !in setOf(200, 404)) return Lookup(null, "Open Facts 연결 실패 (HTTP $http). 미등록으로 처리하지 않았습니다.", LookupState.ERROR)
    val json = JSONObject(raw)
    if (http in setOf(200, 404) && json.optJSONObject("result")?.optString("id") == "product_not_found")
        return Lookup(null, "Open Facts：미등록 (서버의 product_not_found 응답)")
    if (http != 200) return Lookup(null, "Open Facts 연결 실패 (HTTP $http). 미등록으로 처리하지 않았습니다.", LookupState.ERROR)
    val p = json.optJSONObject("product") ?: return Lookup(null, "Open Facts 응답에 상품 정보가 없습니다.", LookupState.ERROR)
    if (!sameGtin(p.text("code"), barcode)) return Lookup(null, "Open Facts 반환 바코드가 달라 상품을 확정하지 않았습니다.", LookupState.ERROR)
    val name = p.text("product_name_ko").ifBlank { p.text("product_name") }
    if (name.isBlank()) return Lookup(null, "Open Facts에 번호는 있으나 상품명이 없습니다.", LookupState.ERROR)
    return Lookup(Product(name, p.text("brands"), p.text("code"), p.text("quantity"), p.text("categories"),
        p.text("image_front_url").takeIf { it.startsWith("https://") }.orEmpty(), host, p.text("manufacturing_places"), p.text("generic_name")),
        "Open Facts API v3：바코드 일치. 공개 등록 정보에 오류가 있을 수 있습니다.")
}

internal fun parseUpcItemDb(raw: String, http: Int, barcode: String): Lookup {
    if (http !in setOf(200, 404)) return Lookup(null, "UPCitemdb 연결 실패 (HTTP $http).", LookupState.ERROR)
    val json = JSONObject(raw)
    if (http == 404 && json.text("code") == "NOT_FOUND") return Lookup(null, "UPCitemdb：미등록")
    if (http != 200 || json.text("code") != "OK") return Lookup(null, "UPCitemdb 응답 오류 (HTTP $http, ${json.text("code")}). 미등록으로 처리하지 않았습니다.", LookupState.ERROR)
    val items = json.optJSONArray("items") ?: return Lookup(null, "UPCitemdb 응답에 items가 없습니다.", LookupState.ERROR)
    if (items.length() == 0) return Lookup(null, "UPCitemdb：미등록 (정상 응답의 items가 비어 있음)")
    val matches = (0 until items.length()).map { items.getJSONObject(it) }.filter { p -> listOf("ean", "upc", "gtin").any { sameGtin(p.text(it), barcode) } }
    val item = matches.singleOrNull() ?: return Lookup(null, "UPCitemdb의 바코드가 다르거나 결과가 중복되어 확정하지 않았습니다.", LookupState.ERROR)
    if (item.text("title").isBlank()) return Lookup(null, "UPCitemdb에 상품명이 없습니다.", LookupState.ERROR)
    val images = item.optJSONArray("images")
    val image = images?.let { a -> (0 until a.length()).map { a.optString(it) }.firstOrNull { it.startsWith("https://") } }.orEmpty()
    return Lookup(Product(item.text("title"), item.text("brand"), barcode, item.text("size").ifBlank { item.text("weight") },
        item.text("category"), image, "UPCitemdb", description = item.text("description")), "UPCitemdb 무료 API：바코드 일치. 공급자 상품 정보에 오류가 있을 수 있습니다.")
}

class UpcItemDbSource(context: Context, private val store: LensStore, private val baseUrl: String = "https://api.upcitemdb.com",
    private val client: OkHttpClient = OkHttpClient.Builder().connectTimeout(5, TimeUnit.SECONDS).readTimeout(6, TimeUnit.SECONDS)
        .callTimeout(8, TimeUnit.SECONDS).followRedirects(false).retryOnConnectionFailure(false).build()) : ProductSource {
    private val prefs = context.getSharedPreferences("upc-rate", Context.MODE_PRIVATE)
    private val mutex = Mutex()
    override suspend fun lookup(barcode: String, refresh: Boolean): Lookup = withContext(Dispatchers.IO) { mutex.withLock {
        require(BarcodeRules.validGtin(barcode))
        val key = "upc:$barcode"
        val cached = store.cached(key)
        if (!refresh && cached.first) return@withLock Lookup(cached.second, "UPCitemdb：${if (cached.second == null) "미등록 응답 캐시" else "상품 캐시"}")
        val now = System.currentTimeMillis()
        val calls = prefs.getString("calls", "").orEmpty().split(',').mapNotNull(String::toLongOrNull).filter { now - it in 0..86_399_999 }.toMutableList()
        if (calls.size >= 90 || calls.lastOrNull()?.let { now - it < 10_000 } == true || prefs.getLong("until", 0) > now)
            return@withLock Lookup(null, "UPCitemdb 무료 한도 보호 중입니다 (10초 간격, 일일 최대 90회).", LookupState.RATE_LIMITED)
        calls += now; prefs.edit().putString("calls", calls.joinToString(",")).commit()
        try {
            val request = Request.Builder().url("$baseUrl/prod/trial/lookup?upc=$barcode")
                .header("User-Agent", "ProductLens/${BuildConfig.VERSION_NAME} (https://github.com/LibraLightLuda/ProductLens)")
                .header("Accept", "application/json").build()
            client.newCall(request).execute().use { response ->
                if (response.code == 429) {
                    val seconds = response.header("Retry-After")?.toLongOrNull()?.coerceIn(60, 86400) ?: 60
                    val reset = response.header("X-RateLimit-Reset")?.toLongOrNull()?.times(1000)?.coerceIn(now, now + 86_400_000) ?: now
                    prefs.edit().putLong("until", maxOf(now + seconds * 1000, reset)).commit()
                    return@withLock Lookup(null, "UPCitemdb 호출 제한입니다. 잠시 후 다시 시도하세요.", LookupState.RATE_LIMITED)
                }
                val body = response.body?.byteStream()?.readLimited(1024 * 1024)?.toString(Charsets.UTF_8) ?: throw IOException("빈 응답")
                val result = parseUpcItemDb(body, response.code, barcode)
                if (result.state in setOf(LookupState.FOUND, LookupState.NOT_FOUND)) store.cache(key, result.product)
                result
            }
        } catch (e: IOException) { Lookup(null, "UPCitemdb 연결 실패: ${e.message}", LookupState.ERROR) }
          catch (_: org.json.JSONException) { Lookup(null, "UPCitemdb 응답을 읽지 못했습니다.", LookupState.ERROR) }
    } }
}

class ProductResolver(private val local: suspend (String) -> Product?, private val sources: List<ProductSource>) {
    suspend fun lookup(barcode: String, online: Boolean = true, refresh: Boolean = false): Lookup {
        require(BarcodeRules.validGtin(barcode))
        local(barcode)?.let { return Lookup(it, "기기에 등록한 상품과 바코드가 일치합니다.") }
        if (!online) return Lookup(null, "온라인 조회가 꺼져 있습니다. 기기 등록 상품에는 일치 항목이 없습니다.", LookupState.OFFLINE)
        val results = mutableListOf<Lookup>()
        for (source in sources) {
            var result = source.lookup(barcode, refresh)
            if (result.product != null && !sameGtin(result.product!!.barcode, barcode)) result = Lookup(null, "공급자가 다른 바코드의 상품을 반환했습니다.", LookupState.ERROR)
            results += result
            if (result.product != null) return result.copy(note = results.joinToString("\n") { it.note })
        }
        val state = if (results.all { it.state == LookupState.NOT_FOUND }) LookupState.NOT_FOUND else LookupState.ERROR
        return Lookup(null, results.joinToString("\n") { it.note } + if (state == LookupState.NOT_FOUND) "\n바코드는 유효하지만 조회한 DB에 상품 정보가 없습니다. 정확한 번호로 웹에서 찾거나 상품명을 저장하세요." else "\n일부 DB 조회를 완료하지 못했습니다. 미등록으로 단정하지 않았습니다.", state)
    }
}

internal val Product.sourceUrl: String? get() = when (source) {
    "world.openfoodfacts.org", "world.openbeautyfacts.org", "world.openpetfoodfacts.org", "world.openproductsfacts.org" -> "https://$source/product/$barcode"
    "UPCitemdb" -> "https://www.upcitemdb.com/upc/$barcode"
    else -> null
}
