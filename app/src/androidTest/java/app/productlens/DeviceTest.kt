package app.productlens

import android.content.Context
import android.graphics.*
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class DeviceTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private fun barcode(value: String, format: BarcodeFormat): Bitmap {
        val matrix = MultiFormatWriter().encode(value, format, 1000, 400, mapOf(com.google.zxing.EncodeHintType.MARGIN to 20))
        return Bitmap.createBitmap(1000, 400, Bitmap.Config.ARGB_8888).also { b ->
            val pixels = IntArray(400000) { i -> if (matrix[i % 1000, i / 1000]) Color.BLACK else Color.WHITE }
            b.setPixels(pixels, 0, 1000, 0, 0, 1000, 400)
        }
    }
    @Test fun realZxingDecodesEanUpcQrAndRotatedGalleryImages() {
        for ((value, f) in listOf("3017620422003" to BarcodeFormat.EAN_13, "96385074" to BarcodeFormat.EAN_8,
            "036000291452" to BarcodeFormat.UPC_A, "04252614" to BarcodeFormat.UPC_E, "hello-productlens" to BarcodeFormat.QR_CODE)) {
            val b = barcode(value, f)
            val r = Images.decode(b)!!
            if (f == BarcodeFormat.UPC_A) assertEquals(value, r.value.takeLast(12)) else assertEquals(value, r.value)
            val rotated = Bitmap.createBitmap(b, 0, 0, b.width, b.height, Matrix().apply { postRotate(90f) }, false)
            assertNotNull(Images.decode(rotated))
            val path = Images.save(context, b)
            val gallery = Images.load(context, android.net.Uri.fromFile(File(path)))
            assertNotNull(Images.decode(gallery)); File(path).delete()
        }
    }
    @Test fun bundledKoreanAndEnglishOcrWorksWithoutModelDownload() = runBlocking {
        val b = Bitmap.createBitmap(1200, 600, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(b); canvas.drawColor(Color.WHITE)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 76f; typeface = Typeface.create("sans-serif", Typeface.BOLD) }
        canvas.drawText("서울우유", 80f, 140f, paint)
        canvas.drawText("SAMSUNG SM-R510", 80f, 300f, paint)
        canvas.drawText("1000 ml", 80f, 460f, paint)
        val vision = Vision(context)
        try { val text = vision.text(b); android.util.Log.i("LensTest", "OCR=$text"); assertTrue(text.contains("서울우유")); assertTrue(text.contains("SAMSUNG")); assertTrue(text.contains("SM-R510")) }
        finally { vision.close() }
    }
    @Test fun actualModelEmbeddingsFindRegisteredPhotoAfterResize() {
        val assets = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().context.assets
        val a = assets.open("burger.jpg").use { BitmapFactory.decodeStream(it) }!!
        val b = assets.open("cat.jpg").use { BitmapFactory.decodeStream(it) }!!
        val query = Bitmap.createScaledBitmap(a, a.width * 3 / 4, a.height * 3 / 4, true)
        val vision = Vision(context)
        val store = LensStore(context, "test-${UUID.randomUUID()}.db")
        try {
            val regions = vision.regions(a)
            assertFalse("Detection must preserve the original photo", a.isRecycled)
            val va = vision.embedding(a); val vb = vision.embedding(b); val vq = vision.embedding(query)
            assertFalse("Embedding must preserve photos for OCR and saving", a.isRecycled || b.isRecycled || query.isRecycled)
            assertTrue(va.size >= 128); assertTrue(va.all { it.isFinite() })
            store.register(Product("테스트 버거 사진"), va); store.register(Product("테스트 고양이 사진"), vb)
            val rank = Fusion.rank(store.catalog(), vq, null, "")
            assertEquals("테스트 버거 사진", rank.first().item.product.name)
            assertTrue(rank.first().cosine!! > rank.last().cosine!!)
            android.util.Log.i("LensTest", "Embedding dim=${va.size}, ranking=${rank.map { it.item.product.name to it.cosine }}")
            assertTrue(regions.all { it.box.left >= 0 && it.box.right <= 1 && it.box.width() > 0 })
            android.util.Log.i("LensTest", "Detected=${regions.map { it.label }}")
        } finally { vision.close(); val name = store.databaseName; store.close(); context.deleteDatabase(name) }
    }
    @Test fun sqliteHistoryAndCatalogSurviveReopenAndDeleteIndependently() {
        val name = "test-${UUID.randomUUID()}.db"
        var store = LensStore(context, name)
        val photo = Images.save(context, barcode("3017620422003", BarcodeFormat.EAN_13))
        try {
            val record = store.save(ScanResult(code = Code("3017620422003", "EAN_13"), text = "서울우유", photo = photo))
            val id = store.register(Product("서울우유", image = photo), floatArrayOf(1f, 0f))
            store.close(); store = LensStore(context, name)
            assertEquals("서울우유", store.history().single().text)
            assertEquals(1, store.catalog().size)
            store.delete("history", record.id); assertTrue(store.history().isEmpty()); assertTrue(File(photo).exists())
            store.delete("catalog", id); assertTrue(store.catalog().isEmpty()); assertFalse(File(photo).exists())
        } finally { store.close(); context.deleteDatabase(name) }
    }
    @Test fun apiSuccessCacheMissingFailureAndRateLimits() = runBlocking {
        val cert = HeldCertificate.Builder().commonName("localhost").addSubjectAlternativeName("localhost").build()
        val serverCerts = HandshakeCertificates.Builder().heldCertificate(cert).build()
        val clientCerts = HandshakeCertificates.Builder().addTrustedCertificate(cert.certificate).build()
        val server = MockWebServer(); server.useHttps(serverCerts.sslSocketFactory(), false); server.start()
        val store = LensStore(context, "test-${UUID.randomUUID()}.db")
        fun clearRate() { context.getSharedPreferences("off-rate", 0).edit().clear().commit() }
        fun source() = OpenFactsSource(context, store, server.url("").toString().trimEnd('/'), OkHttpClient.Builder().sslSocketFactory(clientCerts.sslSocketFactory(), clientCerts.trustManager).followRedirects(false).build())
        try {
            clearRate()
            server.enqueue(MockResponse().setBody("{\"product\":{\"code\":\"3017620422003\",\"product_name\":\"Nutella\",\"brands\":\"Ferrero\"}}"))
            val p = source().lookup("3017620422003"); assertEquals("Nutella", p.product?.name)
            val req = server.takeRequest(); assertTrue(req.path!!.contains("product_type=all")); assertTrue(req.getHeader("User-Agent")!!.startsWith("ProductLens/"))
            assertEquals("Nutella", source().lookup("3017620422003").product?.name); assertEquals(1, server.requestCount)
            clearRate(); server.enqueue(MockResponse().setResponseCode(404).setBody("{\"result\":{\"id\":\"product_not_found\"}}")); assertNull(source().lookup("96385074").product)
            assertTrue(store.cached("96385074").first)
            clearRate(); server.enqueue(MockResponse().setResponseCode(500)); assertTrue(source().lookup("036000291452").note.contains("연결 실패")); assertFalse(store.cached("036000291452").first)
            clearRate(); server.enqueue(MockResponse().setResponseCode(429).addHeader("Retry-After", "120")); assertTrue(source().lookup("036000291452").note.contains("120"))
            assertTrue(source().lookup("042100005264").note.contains("호출 제한"))
        } finally { clearRate(); val n = store.databaseName; store.close(); context.deleteDatabase(n); server.shutdown() }
    }
}
