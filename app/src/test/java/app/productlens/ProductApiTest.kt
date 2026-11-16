package app.productlens

import android.content.Context
import org.robolectric.RuntimeEnvironment
import java.util.UUID
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class ProductApiTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    @Test fun actualSourcesCacheMissesRetryExplicitlyAndRespectRateErrors() = runBlocking {
        val client = OkHttpClient.Builder().followRedirects(false).build()
        val server = MockWebServer().apply { start() }
        val store = LensStore(context, "api-${UUID.randomUUID()}.db")
        val base = server.url("").toString().trimEnd('/')
        val code = "8801097136618"
        fun clearRate() { context.getSharedPreferences("off-rate", 0).edit().clear().commit(); context.getSharedPreferences("upc-rate", 0).edit().clear().commit() }
        try {
            clearRate()
            val off = OpenFactsSource(context, store, base, client)
            val upc = UpcItemDbSource(context, store, base, client)
            server.enqueue(MockResponse().setResponseCode(404).setBody("{\"result\":{\"id\":\"product_not_found\"}}"))
            server.enqueue(MockResponse().setBody("{\"code\":\"OK\",\"items\":[{\"ean\":\"$code\",\"title\":\"보조 DB 응답\"}]}"))
            val resolver = ProductResolver({ null }, listOf(off, upc))
            assertEquals("보조 DB 응답", resolver.lookup(code).product!!.name)
            assertTrue(server.takeRequest().path!!.contains("product_type=all"))
            assertEquals("/prod/trial/lookup?upc=$code", server.takeRequest().path)
            assertEquals("보조 DB 응답", resolver.lookup(code).product!!.name)
            assertEquals(2, server.requestCount)
            clearRate()
            server.enqueue(MockResponse().setBody("{\"product\":{\"code\":\"$code\",\"product_name\":\"새로 등록된 상품\"}}"))
            assertEquals("새로 등록된 상품", resolver.lookup(code, refresh = true).product!!.name)
            clearRate()
            server.enqueue(MockResponse().setResponseCode(429).addHeader("Retry-After", "120"))
            assertEquals(LookupState.RATE_LIMITED, upc.lookup(code, refresh = true).state)
            assertEquals(LookupState.RATE_LIMITED, upc.lookup("3017620422003").state)
            clearRate()
            server.enqueue(MockResponse().setResponseCode(404).setBody("<html>wrong route</html>"))
            assertEquals(LookupState.ERROR, off.lookup("96385074").state)
            assertFalse(store.cached("96385074").first)
        } finally { clearRate(); val name = store.databaseName; store.close(); context.deleteDatabase(name); server.shutdown() }
    }
    @Test fun versionOneDatabaseMigratesWithoutDroppingHistoryAndStoresNamesAcrossReopen() {
        val name = "migration-${UUID.randomUUID()}.db"
        context.openOrCreateDatabase(name, 0, null).use { db ->
            db.execSQL("CREATE TABLE history(id INTEGER PRIMARY KEY,time INTEGER,name TEXT,payload TEXT,photo TEXT)")
            db.execSQL("CREATE TABLE catalog(id INTEGER PRIMARY KEY,name TEXT,product TEXT,vector TEXT,photo TEXT)")
            db.execSQL("CREATE TABLE cache(code TEXT PRIMARY KEY,time INTEGER,product TEXT)")
            db.execSQL("INSERT INTO history VALUES(1,1,'보존할 기록','{\"text\":\"보존할 기록\"}','')")
            val product = Product("보존할 사진 상품", barcode = "3017620422003").json().toString()
            db.execSQL("INSERT INTO catalog VALUES(1,'보존할 사진 상품',?,'[1,0]','')", arrayOf(product))
            db.execSQL("INSERT INTO cache VALUES('3017620422003',?,?)", arrayOf(System.currentTimeMillis(), product))
            db.version = 1
        }
        var store = LensStore(context, name)
        try {
            assertEquals("보존할 기록", store.history().single().text)
            assertEquals("보존할 사진 상품", store.catalog().single().product.name)
            assertArrayEquals(floatArrayOf(1f, 0f), store.catalog().single().vector, 0f)
            assertEquals("보존할 사진 상품", store.cached("3017620422003").second!!.name)
            assertEquals("보존할 사진 상품", store.findBarcode("3017620422003")!!.name)
            store.saveBarcodeName(Product("사용자가 확인한 상품", "브랜드", "8801097136618"))
            store.close(); store = LensStore(context, name)
            assertEquals("사용자가 확인한 상품", store.findBarcode("8801097136618")!!.name)
            assertEquals("사용자가 확인한 상품", store.findBarcode("08801097136618")!!.name)
            assertEquals("보존할 기록", store.history().single().text)
            store.deleteBarcodeName("8801097136618")
            assertNull(store.findBarcode("8801097136618"))
        } finally { store.close(); context.deleteDatabase(name) }
    }
}
