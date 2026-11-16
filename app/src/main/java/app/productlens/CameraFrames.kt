package app.productlens

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.RectF
import androidx.camera.core.ImageProxy

internal object CameraFrames {
    fun luminance(image: ImageProxy): LumaFrame {
        val crop = image.cropRect
        val plane = image.planes[0]
        val data = plane.buffer.duplicate()
        val start = data.position()
        val pixels = ByteArray(crop.width() * crop.height())
        for (y in 0 until crop.height()) for (x in 0 until crop.width())
            pixels[y * crop.width() + x] = data.get(start + (crop.top + y) * plane.rowStride + (crop.left + x) * plane.pixelStride)
        return LumaFrame(crop.width(), crop.height(), pixels).rotated(image.imageInfo.rotationDegrees)
    }
    fun photo(image: ImageProxy, region: ScanBox? = null): Bitmap {
        val raw = image.toBitmap()
        val crop = image.cropRect
        val visible = Bitmap.createBitmap(raw, crop.left, crop.top, crop.width(), crop.height(),
            Matrix().apply { postRotate(image.imageInfo.rotationDegrees.toFloat()) }, true)
        return if (region != null) Images.crop(visible, RectF(region.left, region.top, region.right, region.bottom)) else visible
    }
}
