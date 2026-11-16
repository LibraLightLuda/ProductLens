package app.productlens

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RectF
import com.google.mediapipe.framework.image.BitmapExtractor
import com.google.mediapipe.framework.image.BitmapImageBuilder
import java.io.ByteArrayOutputStream
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
class BitmapOwnershipTest {
    private fun photo() = Bitmap.createBitmap(32, 24, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GREEN) }

    @Test fun exactSdkRecyclesTheBitmapPassedToItsBuilder() {
        val source = photo()
        BitmapImageBuilder(source).build().close()
        assertTrue("MediaPipe close owns and recycles the supplied bitmap", source.isRecycled)
    }

    @Test fun closingInferenceImageReleasesOnlyItsCopy() {
        val source = photo()
        val image = source.toMpImageCopy()
        val owned = BitmapExtractor.extract(image)
        image.close()
        assertFalse("Inference must leave the caller's photo alive", source.isRecycled)
        assertNotSame(source, owned)
        assertTrue("Inference copy must still be released", owned.isRecycled)
        assertEquals(Color.GREEN, source.getPixel(0, 0))
    }

    @Test fun detectorThenEmbeddingLeaveOriginalAndBothCropModesUsable() {
        for (region in listOf(RectF(0f, 0f, 1f, 1f), RectF(.2f, .2f, .8f, .8f))) {
            val source = photo().copy(Bitmap.Config.ARGB_8888, false)!!
            source.toMpImageCopy().close()
            assertFalse("Object detection recycled the source", source.isRecycled)
            val crop = Images.crop(source, region)
            crop.toMpImageCopy().close()
            assertFalse("Embedding recycled the crop", crop.isRecycled)
            assertFalse("The full-image crop may share the source", source.isRecycled)
            assertEquals(Color.GREEN, source.getPixel(0, 0))
            assertTrue(crop.compress(Bitmap.CompressFormat.JPEG, 88, ByteArrayOutputStream()))
            assertNotNull(Images.crop(source, region))
        }
    }

    @Test fun inferenceFailureStillClosesOnlyTheOwnedCopy() {
        val source = photo()
        val image = source.toMpImageCopy()
        val owned = BitmapExtractor.extract(image)
        try { throw IllegalStateException("simulated inference failure") }
        catch (_: IllegalStateException) { }
        finally { image.close() }
        assertFalse(source.isRecycled)
        assertTrue(owned.isRecycled)
        assertNotNull(Images.crop(source, RectF(0f, 0f, 1f, 1f)))
    }
}
