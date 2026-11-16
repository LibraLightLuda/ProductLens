package app.productlens

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ProductLookupTest {
    private val code = "8801097136618"
    @Test fun actualOffMissingResponseIsDistinctFromApiErrors() {
        assertEquals(LookupState.NOT_FOUND, parseOpenFacts("{\"result\":{\"id\":\"product_not_found\"},\"status\":\"failure\"}", 404, code).state)
        assertEquals(LookupState.ERROR, parseOpenFacts("{}", 404, code).state)
        assertEquals(LookupState.ERROR, parseOpenFacts("gateway error", 503, code).state)
        assertEquals(LookupState.ERROR, parseOpenFacts("{}", 200, code).state)
    }
    @Test fun offProductMustContainMatchingBarcodeAndAnActualName() {
        val good = "{\"product\":{\"code\":\"3017620422003\",\"product_name\":\"Nutella\",\"brands\":\"Ferrero\"}}"
        assertEquals("Nutella", parseOpenFacts(good, 200, "3017620422003").product!!.name)
        assertEquals(LookupState.ERROR, parseOpenFacts(good, 200, code).state)
        assertEquals(LookupState.ERROR, parseOpenFacts("{\"product\":{\"code\":\"$code\"}}", 200, code).state)
    }
    @Test fun upcEmptyMatchingMissingAndWrongResponsesAreNotConfused() {
        assertEquals(LookupState.NOT_FOUND, parseUpcItemDb("{\"code\":\"OK\",\"total\":0,\"items\":[]}", 200, code).state)
        val item = "{\"ean\":\"$code\",\"title\":\"테스트 응답 상품\",\"brand\":\"테스트\",\"images\":[\"http://unsafe/image\",\"https://example.com/image\"]}"
        val good = "{\"code\":\"OK\",\"items\":[$item]}"
        val p = parseUpcItemDb(good, 200, code).product!!
        assertEquals("UPCitemdb", p.source); assertEquals("https://example.com/image", p.image)
        assertEquals(LookupState.ERROR, parseUpcItemDb(good, 200, "3017620422003").state)
        assertEquals(LookupState.ERROR, parseUpcItemDb("{\"code\":\"OK\",\"items\":[$item,$item]}", 200, code).state)
        assertEquals(LookupState.ERROR, parseUpcItemDb("{}", 404, code).state)
        assertEquals(LookupState.ERROR, parseUpcItemDb("unavailable", 503, code).state)
    }
    @Test fun gtinComparisonPreservesPackagingAndAcceptsLeadingZeroEquivalence() {
        assertTrue(sameGtin("036000291452", "0036000291452"))
        assertFalse(sameGtin("036000291452", "0000000000000"))
        assertFalse(sameGtin("bad", "bad"))
    }
    @Test fun sourceLinksDoNotTreatLocalOrUpcDataAsOpenFacts() {
        assertEquals("https://www.upcitemdb.com/upc/$code", Product("n", barcode = code, source = "UPCitemdb").sourceUrl)
        assertNull(Product("n", barcode = code, source = "사용자 등록").sourceUrl)
        assertNull(Product("n", barcode = code, source = "evil.example").sourceUrl)
    }
    @Test fun localRegistrationWorksOfflineAndSkipsAllNetworkSources() = runBlocking {
        var requested = 0
        val remote = object : ProductSource { override suspend fun lookup(barcode: String, refresh: Boolean): Lookup { requested++; error("Must not query") } }
        val p = Product("내가 확인한 상품", barcode = code, source = "사용자 등록")
        assertEquals(p, ProductResolver({ p }, listOf(remote)).lookup(code, online = false).product)
        assertEquals(0, requested)
        assertEquals(LookupState.OFFLINE, ProductResolver({ null }, listOf(remote)).lookup(code, online = false).state)
    }
    @Test fun fallbackUsesSecondSourceAndPassesExplicitRefresh() = runBlocking {
        val calls = mutableListOf<Boolean>()
        val missing = object : ProductSource { override suspend fun lookup(barcode: String, refresh: Boolean) = Lookup(null, "OFF missing") }
        val second = object : ProductSource { override suspend fun lookup(barcode: String, refresh: Boolean): Lookup { calls += refresh; return Lookup(Product("보조 DB 테스트 응답", barcode = barcode), "UPC found") } }
        assertNotNull(ProductResolver({ null }, listOf(missing, second)).lookup(code, refresh = true).product)
        assertEquals(listOf(true), calls)
    }
    @Test fun failedProviderNeverBecomesDefiniteDatabaseAbsence() = runBlocking {
        val missing = object : ProductSource { override suspend fun lookup(barcode: String, refresh: Boolean) = Lookup(null, "missing") }
        val failed = object : ProductSource { override suspend fun lookup(barcode: String, refresh: Boolean) = Lookup(null, "HTTP 503", LookupState.ERROR) }
        assertEquals(LookupState.ERROR, ProductResolver({ null }, listOf(missing, failed)).lookup(code).state)
        assertEquals(LookupState.NOT_FOUND, ProductResolver({ null }, listOf(missing, missing)).lookup(code).state)
    }
}
