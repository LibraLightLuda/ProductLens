package app.productlens

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.RectF
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.imageembedder.ImageEmbedder
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetector
import com.google.zxing.*
import com.google.zxing.common.HybridBinarizer
import kotlinx.coroutines.tasks.await
import java.io.Closeable
import java.io.File
import java.util.UUID

data class Region(val box: RectF, val label: String)
// MPImage.close() recycles its bitmap; UI, OCR and saving still need the caller's source.
internal fun Bitmap.toMpImageCopy(): MPImage =
    BitmapImageBuilder(checkNotNull(copy(Bitmap.Config.ARGB_8888, false)) { "추론용 사진을 복사하지 못했습니다." }).build()

class Vision(private val context: Context) : Closeable {
    private val ocr = TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
    private var embedder: ImageEmbedder? = null
    private var detector: ObjectDetector? = null
    @Synchronized fun regions(bitmap: Bitmap): List<Region> {
        val det = detector ?: ObjectDetector.createFromOptions(context, ObjectDetector.ObjectDetectorOptions.builder()
            .setBaseOptions(BaseOptions.builder().setModelAssetPath("efficientdet_lite0.tflite").build()).setScoreThreshold(0.35f).setMaxResults(5).build()).also { detector = it }
        val image = bitmap.toMpImageCopy()
        return try { det.detect(image).detections().map { d ->
            val b = d.boundingBox()
            val cat = d.categories().maxByOrNull { it.score() }
            Region(RectF((b.left / bitmap.width).coerceIn(0f, 1f), (b.top / bitmap.height).coerceIn(0f, 1f),
                (b.right / bitmap.width).coerceIn(0f, 1f), (b.bottom / bitmap.height).coerceIn(0f, 1f)), "${cat?.categoryName().orEmpty()} · 검출 점수 ${"%.2f".format(java.util.Locale.US, cat?.score() ?: 0f)}")
        }.filter { it.box.width() > 0.02f && it.box.height() > 0.02f }.sortedByDescending { it.box.width() * it.box.height() } } finally { image.close() }
    }
    @Synchronized fun embedding(bitmap: Bitmap): FloatArray {
        val model = embedder ?: ImageEmbedder.createFromOptions(context, ImageEmbedder.ImageEmbedderOptions.builder()
            .setBaseOptions(BaseOptions.builder().setModelAssetPath("mobilenet_v3_small.tflite").build()).setL2Normalize(true).build()).also { embedder = it }
        val image = bitmap.toMpImageCopy()
        return try { model.embed(image).embeddingResult().embeddings().first().floatEmbedding().clone() } finally { image.close() }
    }
    suspend fun text(bitmap: Bitmap): String = ocr.process(InputImage.fromBitmap(bitmap, 0)).await().text
    @Synchronized override fun close() { ocr.close(); embedder?.close(); detector?.close(); embedder = null; detector = null }
}

object Images {
    fun load(context: Context, uri: Uri): Bitmap {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri).use { BitmapFactory.decodeStream(it, null, opts) }
        require(opts.outWidth > 0 && opts.outHeight > 0) { "지원하지 않는 이미지입니다" }
        var sample = 1
        while (maxOf(opts.outWidth, opts.outHeight) / sample > 1800) sample *= 2
        val bitmap = context.contentResolver.openInputStream(uri).use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888 }) }
            ?: error("사진을 읽을 수 없습니다")
        val exif = context.contentResolver.openInputStream(uri).use { it?.let(::ExifInterface) }
        val matrix = Matrix().apply {
            if (exif?.isFlipped == true) postScale(-1f, 1f)
            postRotate((exif?.rotationDegrees ?: 0).toFloat())
        }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true).also { if (it !== bitmap) bitmap.recycle() }
    }
    fun crop(bitmap: Bitmap, rect: RectF): Bitmap {
        val x = (rect.left.coerceIn(0f, 0.98f) * bitmap.width).toInt()
        val y = (rect.top.coerceIn(0f, 0.98f) * bitmap.height).toInt()
        val w = ((rect.right.coerceIn(rect.left + 0.01f, 1f) * bitmap.width).toInt() - x).coerceIn(1, bitmap.width - x)
        val h = ((rect.bottom.coerceIn(rect.top + 0.01f, 1f) * bitmap.height).toInt() - y).coerceIn(1, bitmap.height - y)
        return Bitmap.createBitmap(bitmap, x, y, w, h)
    }
    fun save(context: Context, bitmap: Bitmap): String {
        val dir = File(context.filesDir, "photos").apply { mkdirs() }
        val f = File(dir, "${UUID.randomUUID()}.jpg")
        try { f.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.JPEG, 88, it)) }; return f.absolutePath }
        catch (e: Exception) { f.delete(); throw e }
    }
    fun decode(bitmap: Bitmap): Code? {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return decode(RGBLuminanceSource(bitmap.width, bitmap.height, pixels))
    }
    fun decode(source: LuminanceSource): Code? {
        return BarcodeScan.find(source)?.code
    }
}
