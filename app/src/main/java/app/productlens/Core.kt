package app.productlens

import kotlin.math.sqrt

data class Code(val value: String, val format: String)
data class Product(val name: String, val brand: String = "", val barcode: String = "", val quantity: String = "",
    val categories: String = "", val image: String = "", val source: String = "", val manufacturer: String = "", val description: String = "")
data class Clues(val query: String, val brand: String, val model: String, val size: String)
data class CatalogItem(val id: Long, val product: Product, val vector: FloatArray)
data class Candidate(val item: CatalogItem, val cosine: Double?, val reasons: List<String>, val order: Double)

object BarcodeRules {
    fun expandedUpce(code: String): String? {
        if (!code.matches(Regex("[01][0-9]{7}"))) return null
        val d = code.substring(1, 7)
        val body = when (d[5]) {
            '0', '1', '2' -> "${code[0]}${d.substring(0, 2)}${d[5]}0000${d.substring(2, 5)}"
            '3' -> "${code[0]}${d.substring(0, 3)}00000${d.substring(3, 5)}"
            '4' -> "${code[0]}${d.substring(0, 4)}00000${d[4]}"
            else -> "${code[0]}${d.substring(0, 5)}0000${d[5]}"
        }
        return body + code.last()
    }
    fun validGtin(code: String): Boolean {
        if (code.length !in listOf(8, 12, 13, 14) || !code.all { it in '0'..'9' }) return false
        val sum = code.dropLast(1).reversed().mapIndexed { i, c -> c.digitToInt() * if (i % 2 == 0) 3 else 1 }.sum()
        return (10 - sum % 10) % 10 == code.last().digitToInt()
    }
    fun lookup(code: Code): String? {
        val value = if (code.format == "UPC_E") expandedUpce(code.value) ?: return null else code.value
        return value.takeIf { code.format in setOf("EAN_13", "EAN_8", "UPC_A", "UPC_E", "MANUAL") && validGtin(it) }
    }
}

object TextClues {
    private val noise = Regex("영양정보|영양성분|원재료|유통기한|소비기한|보관방법|고객상담|고객센터|주의사항|반품|재활용|분리배출|제조일|nutrition|ingredients|best before|www\\.|https?://", RegexOption.IGNORE_CASE)
    private val model = Regex("(?i)\\b(?=[a-z0-9-]{4,24}\\b)(?=[a-z0-9-]*[a-z])(?=[a-z0-9-]*[0-9])[a-z0-9]+(?:-[a-z0-9]+)*\\b")
    private val size = Regex("(?i)\\b\\d+(?:[.,]\\d+)?\\s?(?:ml|mg|kg|gb|tb|mah|mm|cm|g|l|oz)\\b")
    fun extract(text: String): Clues {
        val lines = text.lines().map { it.trim().replace(Regex("\\s+"), " ") }
            .filter { it.length > 1 && !noise.containsMatchIn(it) && !it.matches(Regex("[\\d\\s%.,/:-]+")) }
        val m = model.findAll(lines.joinToString(" ")).map { it.value }.firstOrNull { !size.matches(it) }.orEmpty()
        val brand = lines.firstOrNull().orEmpty().split(" ").firstOrNull().orEmpty()
        return Clues(lines.take(3).joinToString(" ").take(160), brand.take(40), m, size.find(text)?.value.orEmpty())
    }
}

object Fusion {
    fun cosine(a: FloatArray, b: FloatArray): Double? {
        if (a.isEmpty() || a.size != b.size) return null
        var dot = 0.0; var aa = 0.0; var bb = 0.0
        for (i in a.indices) { dot += a[i].toDouble() * b[i]; aa += a[i].toDouble() * a[i]; bb += b[i].toDouble() * b[i] }
        return if (aa > 0 && bb > 0) (dot / sqrt(aa * bb)).takeIf { it.isFinite() }?.coerceIn(-1.0, 1.0) else null
    }
    fun rank(items: List<CatalogItem>, vector: FloatArray?, code: Code?, text: String): List<Candidate> = items.map { item ->
        val p = item.product
        val reasons = mutableListOf<String>()
        val exact = code?.let(BarcodeRules::lookup)?.let { p.barcode.isNotEmpty() && it.padStart(14, '0') == p.barcode.padStart(14, '0') } == true
        if (exact) reasons += "등록 바코드 일치"
        val brand = p.brand.length > 1 && text.contains(p.brand, true)
        if (brand) reasons += "등록 브랜드가 OCR 텍스트에 포함됨"
        val model = TextClues.extract(p.name).model
        val modelMatch = model.isNotBlank() && text.contains(model, true)
        if (modelMatch) reasons += "등록 모델번호가 OCR 텍스트에 포함됨"
        val cosine = vector?.let { cosine(it, item.vector) }
        if (cosine != null) reasons += "등록 사진과 이미지 특징 비교"
        Candidate(item, cosine, reasons, (if (exact) 10.0 else 0.0) + (if (modelMatch) 2.0 else 0.0) + (if (brand) 1.0 else 0.0) + (cosine ?: 0.0))
    }.filter { it.reasons.isNotEmpty() }.sortedByDescending { it.order }.take(5)
}
