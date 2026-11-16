package app.productlens

import com.google.zxing.*
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.oned.MultiFormatOneDReader
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

internal data class ScanBox(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width get() = right - left
    val height get() = bottom - top
    fun centeredAt(x: Float, y: Float): ScanBox {
        val l = (x - width / 2).coerceIn(0f, 1f - width)
        val t = (y - height / 2).coerceIn(0f, 1f - height)
        return ScanBox(l, t, l + width, t + height)
    }
    fun overlaps(other: ScanBox): Boolean {
        val intersection = max(0f, min(right, other.right) - max(left, other.left)) * max(0f, min(bottom, other.bottom) - max(top, other.top))
        return intersection >= min(width * height, other.width * other.height) * .5f
    }
}
internal val scanGuide = ScanBox(.08f, .26f, .92f, .74f)
internal data class BarcodeHit(val code: Code, val box: ScanBox, val rows: Int)

internal class LumaFrame(width: Int, height: Int, private val pixels: ByteArray) : LuminanceSource(width, height) {
    init { require(width > 0 && height > 0 && pixels.size == width * height) }
    override fun getMatrix() = pixels
    override fun getRow(y: Int, row: ByteArray?): ByteArray = (row?.takeIf { it.size >= width } ?: ByteArray(width)).also { pixels.copyInto(it, 0, y * width, (y + 1) * width) }
    fun rotated(degrees: Int): LumaFrame {
        require(degrees in setOf(0, 90, 180, 270))
        if (degrees == 0) return this
        val newWidth = if (degrees == 180) width else height
        val newHeight = if (degrees == 180) height else width
        val out = ByteArray(pixels.size)
        for (y in 0 until height) for (x in 0 until width) {
            val target = when (degrees) {
                90 -> x * newWidth + height - 1 - y
                180 -> (height - 1 - y) * width + width - 1 - x
                else -> (width - 1 - x) * newWidth + y
            }
            out[target] = pixels[y * width + x]
        }
        return LumaFrame(newWidth, newHeight, out)
    }
    fun region(box: ScanBox): LumaFrame {
        val left = (box.left * width).toInt().coerceIn(0, width - 1)
        val top = (box.top * height).toInt().coerceIn(0, height - 1)
        val w = ((box.right * width).toInt() - left).coerceIn(1, width - left)
        val h = ((box.bottom * height).toInt() - top).coerceIn(1, height - top)
        return LumaFrame(w, h, ByteArray(w * h).also { out -> for (y in 0 until h) pixels.copyInto(out, y * w, (top + y) * width + left, (top + y) * width + left + w) })
    }
}

internal object BarcodeScan {
    private val matrixFormats = listOf(BarcodeFormat.QR_CODE, BarcodeFormat.DATA_MATRIX, BarcodeFormat.AZTEC, BarcodeFormat.PDF_417, BarcodeFormat.MAXICODE)
    private data class Row(val code: Code, val left: Float, val right: Float, val y: Float)
    fun find(source: LuminanceSource, focusX: Float = .5f, focusY: Float = .5f): BarcodeHit? {
        val frame = LumaFrame(source.width, source.height, source.matrix)
        val horizontal = rows(frame)
        val vertical = rows(frame.rotated(90)).map { hit -> hit.copy(box = ScanBox(hit.box.top, 1f - hit.box.right, hit.box.bottom, 1f - hit.box.left)) }
        fun nearest(hits: List<BarcodeHit>) = hits.minWithOrNull(compareBy<BarcodeHit> {
            val dx = max(0f, max(it.box.left - focusX, focusX - it.box.right))
            val dy = max(0f, max(it.box.top - focusY, focusY - it.box.bottom))
            dx * dx + dy * dy
        }.thenByDescending { it.rows * max(it.box.width, it.box.height) })
        nearest(horizontal + vertical)?.let { return it }
        val inverted = LumaFrame(frame.width, frame.height, frame.invert().matrix)
        (rows(inverted) + rows(inverted.rotated(90)).map { hit -> hit.copy(box = ScanBox(hit.box.top, 1f - hit.box.right, hit.box.bottom, 1f - hit.box.left)) })
            .let(::nearest)?.let { return it }
        val reader = MultiFormatReader().apply { setHints(mapOf(DecodeHintType.POSSIBLE_FORMATS to matrixFormats, DecodeHintType.TRY_HARDER to true, DecodeHintType.CHARACTER_SET to "UTF-8")) }
        for (input in listOf(frame, frame.invert())) try {
            val result = reader.decodeWithState(BinaryBitmap(HybridBinarizer(input)))
            val points = result.resultPoints.filterNotNull()
            if (points.size < 2) continue
            val box = ScanBox(points.minOf { it.x } / frame.width, points.minOf { it.y } / frame.height,
                points.maxOf { it.x } / frame.width, points.maxOf { it.y } / frame.height)
            if (box.width > .03f && box.height > .03f) return BarcodeHit(Code(result.text, result.barcodeFormat.name), box, 1)
        } catch (_: ReaderException) { } finally { reader.reset() }
        return null
    }
    private fun rows(frame: LumaFrame): List<BarcodeHit> {
        val binary = BinaryBitmap(HybridBinarizer(frame))
        val hints = emptyMap<DecodeHintType, Any>()
        val reader = MultiFormatOneDReader(hints)
        val found = mutableListOf<Row>()
        // A checksum alone accepts some text/table scanlines; require spatial support across rows.
        for (y in (1..31).map { frame.height * it / 32 }.distinct()) {
            val bits = try { binary.getBlackRow(y, null) } catch (_: NotFoundException) { continue }
            for (reverse in listOf(false, true)) {
                if (reverse) bits.reverse()
                try {
                    val result = reader.decodeRow(y, bits, hints)
                    val code = Code(result.text, result.barcodeFormat.name)
                    if (code.format in setOf("EAN_13", "EAN_8", "UPC_A", "UPC_E") && BarcodeRules.lookup(code) == null) continue
                    val points = result.resultPoints.filterNotNull()
                    if (points.size < 2) continue
                    val left = points.minOf { if (reverse) frame.width - 1 - it.x else it.x } / frame.width
                    val right = points.maxOf { if (reverse) frame.width - 1 - it.x else it.x } / frame.width
                    if (right - left >= .2f) found += Row(code, left, right, y.toFloat() / frame.height)
                    break
                } catch (_: ReaderException) { }
            }
        }
        val groups = mutableListOf<MutableList<Row>>()
        for (row in found) {
            val group = groups.firstOrNull { it.first().code == row.code && abs(it.first().left - row.left) < .06f && abs(it.first().right - row.right) < .06f }
            if (group == null) groups += mutableListOf(row) else group += row
        }
        return groups.filter { it.size >= 3 && it.maxOf { row -> row.y } - it.minOf { row -> row.y } >= .04f }.map { group ->
            BarcodeHit(group.first().code, ScanBox(group.minOf { it.left }, group.minOf { it.y }, group.maxOf { it.right }, group.maxOf { it.y }), group.size)
        }
    }
}

internal class StableBarcode {
    private var previous: BarcodeHit? = null
    private var lastAt = Long.MIN_VALUE
    private var firstAt = 0L
    var count = 0; private set
    @Synchronized fun reset() { previous = null; count = 0; lastAt = Long.MIN_VALUE }
    @Synchronized fun observe(hit: BarcodeHit?, now: Long): BarcodeHit? {
        if (hit == null) { reset(); return null }
        val old = previous
        if (old == null || old.code != hit.code || !old.box.overlaps(hit.box) || now - lastAt !in 150..1200) {
            previous = hit; count = 1; firstAt = now; lastAt = now; return null
        }
        count++; lastAt = now; previous = hit
        return hit.takeIf { count >= 3 && now - firstAt >= 400 }
    }
}
