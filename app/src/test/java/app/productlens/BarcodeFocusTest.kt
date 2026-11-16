package app.productlens

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatWriter
import com.google.zxing.RGBLuminanceSource
import org.junit.Assert.*
import org.junit.Test

class BarcodeFocusTest {
    @Test fun focusMovesTheGuideWithoutLeavingThePreview() {
        val moved = scanGuide.centeredAt(.9f, .8f)
        assertEquals(1f, moved.right, .0001f); assertEquals(1f, moved.bottom, .0001f)
        assertEquals(scanGuide.width, moved.width, .0001f)
        val upperLeft = scanGuide.centeredAt(0f, 0f)
        assertEquals(0f, upperLeft.left, 0f); assertEquals(0f, upperLeft.top, 0f)
    }
    @Test fun barcodeAtTheFocusedPositionWinsOverAnotherValidCode() {
        val w = 900; val h = 600; val pixels = ByteArray(w * h) { 0xff.toByte() }
        for ((value, format, top) in listOf(Triple("84141873", BarcodeFormat.EAN_8, 40), Triple("8801097136618", BarcodeFormat.EAN_13, 400))) {
            val matrix = MultiFormatWriter().encode(value, format, w, 140, mapOf(EncodeHintType.MARGIN to 24))
            for (y in 0 until 140) for (x in 0 until w) if (matrix[x, y]) pixels[(top + y) * w + x] = 0
        }
        val source = LumaFrame(w, h, pixels)
        assertEquals("8801097136618", BarcodeScan.find(source, .5f, .8f)?.code?.value)
        assertEquals("84141873", BarcodeScan.find(source, .5f, .15f)?.code?.value)
    }
    @Test fun invertedAndVerticalProductCodesRemainSupported() {
        val w = 900; val h = 360
        val matrix = MultiFormatWriter().encode("8801097136618", BarcodeFormat.EAN_13, w, h, mapOf(EncodeHintType.MARGIN to 24))
        val frame = LumaFrame(w, h, ByteArray(w * h) { i -> if (matrix[i % w, i / w]) 0 else 0xff.toByte() })
        for (rotation in listOf(0, 90, 180, 270)) assertEquals("8801097136618", BarcodeScan.find(frame.rotated(rotation))?.code?.value)
        assertEquals("8801097136618", BarcodeScan.find(frame.invert())?.code?.value)
    }
    @Test fun consecutiveFramesMustAgreeOnValueAndPosition() {
        val stable = StableBarcode()
        val correct = BarcodeHit(Code("8801097136618", "EAN_13"), ScanBox(.2f, .3f, .8f, .6f), 8)
        val wrong = correct.copy(code = Code("84141873", "EAN_8"))
        assertNull(stable.observe(wrong, 0))
        assertNull(stable.observe(correct, 250))
        assertNull(stable.observe(correct, 500))
        assertEquals(correct, stable.observe(correct, 750))
        stable.reset()
        assertNull(stable.observe(correct, 1000))
        assertNull(stable.observe(correct.copy(box = ScanBox(.2f, .7f, .8f, .9f)), 1250))
        assertEquals(1, stable.count)
    }
    @Test fun missingFramesLongGapsAndFocusResetCannotReuseOldConfirmation() {
        val stable = StableBarcode()
        val hit = BarcodeHit(Code("8801097136618", "EAN_13"), ScanBox(.2f, .3f, .8f, .6f), 8)
        stable.observe(hit, 0); stable.observe(hit, 250)
        assertNull(stable.observe(null, 500)); assertEquals(0, stable.count)
        stable.observe(hit, 750); stable.observe(hit, 1000)
        assertNull(stable.observe(hit, 3000)); assertEquals(1, stable.count)
        stable.reset(); assertNull(stable.observe(hit, 3250)); assertEquals(1, stable.count)
    }
    @Test fun croppedAndRotatedLuminanceMatchesTheDisplayedOrientation() {
        val frame = LumaFrame(4, 3, ByteArray(12) { it.toByte() })
        assertArrayEquals(byteArrayOf(8, 4, 0, 9, 5, 1, 10, 6, 2, 11, 7, 3), frame.rotated(90).matrix)
        assertArrayEquals(byteArrayOf(11, 10, 9, 8, 7, 6, 5, 4, 3, 2, 1, 0), frame.rotated(180).matrix)
        assertArrayEquals(frame.matrix, frame.rotated(90).rotated(270).matrix)
        assertArrayEquals(byteArrayOf(5, 6, 9, 10), frame.region(ScanBox(.25f, 1f / 3, .75f, 1f)).matrix)
    }
    @Test fun barcodeOutsideTheGuideIsNotAccepted() {
        val w = 900; val h = 600
        val matrix = MultiFormatWriter().encode("8801097136618", BarcodeFormat.EAN_13, w, 90, mapOf(EncodeHintType.MARGIN to 24))
        val pixels = ByteArray(w * h) { 0xff.toByte() }
        for (y in 0 until 90) for (x in 0 until w) if (matrix[x, y]) pixels[y * w + x] = 0
        assertNotNull(BarcodeScan.find(LumaFrame(w, h, pixels)))
        assertNull(BarcodeScan.find(LumaFrame(w, h, pixels).region(scanGuide)))
    }
    @Test fun smallOneDimensionalNoiseCannotSatisfySpatialSupport() {
        val matrix = MultiFormatWriter().encode("84141873", BarcodeFormat.EAN_8, 900, 3, mapOf(EncodeHintType.MARGIN to 24))
        val pixels = IntArray(900 * 400) { -1 }
        for (y in 0 until 3) for (x in 0 until 900) if (matrix[x, y]) pixels[(199 + y) * 900 + x] = 0xff000000.toInt()
        assertNull(Images.decode(RGBLuminanceSource(900, 400, pixels)))
    }
    @Test fun singleScanlineDecoyMustNotOverrideTheActualProductBarcode() {
        val width = 900; val height = 400
        val pixels = IntArray(width * height) { -1 }
        fun draw(value: String, format: BarcodeFormat, top: Int, rows: Int) {
            val matrix = MultiFormatWriter().encode(value, format, width, rows, mapOf(EncodeHintType.MARGIN to 24))
            for (y in 0 until rows) for (x in 0 until width) if (matrix[x, y]) pixels[(top + y) * width + x] = 0xff000000.toInt()
        }
        draw("84141873", BarcodeFormat.EAN_8, 199, 3)
        draw("8801097136618", BarcodeFormat.EAN_13, 240, 140)
        assertEquals("8801097136618", Images.decode(RGBLuminanceSource(width, height, pixels))?.value)
    }
}
