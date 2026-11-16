package app.productlens

import org.junit.Assert.*
import org.junit.Test
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import com.google.zxing.RGBLuminanceSource

class CoreTest {
    @Test fun barcodeIdentityIsNotReplacedByNutritionOcrInResultTitles() {
        val result = ScanResult(code = Code("8801097136618", "EAN_13"), text = "지방0g 0% 내수있습니다")
        assertEquals("바코드 8801097136618", result.title)
        assertEquals("등록된 상품명", result.copy(product = Product("등록된 상품명")).title)
    }
    @Test fun productionZxingDecoderReadsActualEanUpcQrSymbols() {
        for ((value, format) in listOf("3017620422003" to BarcodeFormat.EAN_13, "96385074" to BarcodeFormat.EAN_8,
            "036000291452" to BarcodeFormat.UPC_A, "04252614" to BarcodeFormat.UPC_E, "https://example.org/product" to BarcodeFormat.QR_CODE)) {
            // UPC-E requires a complete trailing quiet zone; the writer's default can be too short.
            val matrix = MultiFormatWriter().encode(value, format, 900, 360, mapOf(com.google.zxing.EncodeHintType.MARGIN to 20))
            val pixels = IntArray(900 * 360) { i -> if (matrix[i % 900, i / 900]) 0xff000000.toInt() else 0xffffffff.toInt() }
            val decoded = Images.decode(RGBLuminanceSource(900, 360, pixels))
            assertNotNull(value, decoded)
            if (format == BarcodeFormat.UPC_A) assertEquals(value, decoded!!.value.takeLast(12)) else assertEquals(value, decoded!!.value)
        }
    }
    @Test fun gtinChecksumAndInputBoundaries() {
        listOf("3017620422003", "96385074", "036000291452", "00036000291452").forEach { assertTrue(it, BarcodeRules.validGtin(it)) }
        listOf("3017620422004", "123", "301762042200a", "３０１７６２０４２２００３", " 3017620422003", "").forEach { assertFalse(it, BarcodeRules.validGtin(it)) }
    }
    @Test fun upceExpandsAndGeneralQrNeverTriggersProductLookup() {
        assertEquals("042100005264", BarcodeRules.expandedUpce("04252614"))
        assertEquals("042100005264", BarcodeRules.lookup(Code("04252614", "UPC_E")))
        assertNull(BarcodeRules.lookup(Code("3017620422003", "QR_CODE")))
        assertNull(BarcodeRules.expandedUpce("24252614"))
        assertNull(BarcodeRules.lookup(Code("04252615", "UPC_E")))
    }
    @Test fun ocrExtractsKoreanEnglishModelAndRemovesLabelNoise() {
        val c = TextClues.extract("SAMSUNG\nGalaxy Buds SM-R510\n500mAh\n고객센터 1588-0000\n원재료 우유\n영양정보 30kcal")
        assertEquals("SAMSUNG", c.brand); assertEquals("SM-R510", c.model); assertEquals("500mAh", c.size)
        assertFalse(c.query.contains("고객센터")); assertFalse(c.query.contains("원재료"))
        assertEquals("서울우유", TextClues.extract("서울우유\n흰우유 1000ml\n보관방법 냉장").brand)
    }
    @Test fun cosineHandlesZeroNonFiniteAndShapeMismatch() {
        assertEquals(1.0, Fusion.cosine(floatArrayOf(1f, 2f), floatArrayOf(2f, 4f))!!, 1e-6)
        assertEquals(0.0, Fusion.cosine(floatArrayOf(1f, 0f), floatArrayOf(0f, 1f))!!, 1e-6)
        assertNull(Fusion.cosine(floatArrayOf(0f), floatArrayOf(0f)))
        assertNull(Fusion.cosine(floatArrayOf(1f), floatArrayOf(1f, 2f)))
        assertNull(Fusion.cosine(floatArrayOf(Float.NaN), floatArrayOf(1f)))
    }
    @Test fun fusionUsesExactCodeBeforeVisualSimilarityAndKeepsEvidenceSeparate() {
        val exact = CatalogItem(1, Product("Galaxy SM-R510", "Samsung", "3017620422003"), floatArrayOf(0f, 1f))
        val similar = CatalogItem(2, Product("다른 상품"), floatArrayOf(1f, 0f))
        val ranked = Fusion.rank(listOf(similar, exact), floatArrayOf(1f, 0f), Code("3017620422003", "EAN_13"), "Samsung SM-R510")
        assertEquals(1L, ranked.first().item.id)
        assertEquals(0.0, ranked.first().cosine!!, 0.0)
        assertEquals(4, ranked.first().reasons.size)
        assertEquals(1.0, ranked.last().cosine!!, 0.0)
    }
    @Test fun noCatalogNeverInventsProductCandidate() { assertTrue(Fusion.rank(emptyList(), floatArrayOf(1f), null, "운동화").isEmpty()) }
}
