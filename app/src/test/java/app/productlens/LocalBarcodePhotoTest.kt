package app.productlens

import java.io.File
import com.google.zxing.RGBLuminanceSource
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class LocalBarcodePhotoTest {
    @Test fun providedPhotoMustNotConfirmAnUnrelatedEan8() {
        val path = System.getenv("PRODUCTLENS_BARCODE_PHOTO")
        assumeTrue(!path.isNullOrBlank())
        // Locally decoded attachment pixels stay outside the repository and release archives.
        val source = File(path!!).inputStream().buffered().use { stream ->
            val input = java.io.DataInputStream(stream)
            val width = input.readInt(); val height = input.readInt()
            RGBLuminanceSource(width, height, IntArray(width * height) { input.readInt() })
        }
        val code = Images.decode(source)
        val focused = Images.decode(source.crop(50, 160, 190, 100))
        println("Local photographed barcode full=$code; focused=$focused")
        assertNotEquals("84141873", code?.value)
        assertTrue("A compressed screenshot must be rejected rather than assigned another product", focused == null || focused.value == "8801097136618")
    }
}
