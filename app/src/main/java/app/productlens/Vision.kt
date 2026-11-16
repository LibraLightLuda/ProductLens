package app.productlens

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.RectF
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.imageembedder.ImageEmbedder
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetector
import com.google.zxing.*
import com.google.zxing.common.HybridBinarizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File
import java.util.UUID

data class Region(val box: RectF, val label: String)
class Vision(private val context: Context) : Closeable {
    private val ocr = TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
    private var embedder: ImageEmbedder? = null
    private var detector: ObjectDetector? = null
    @Synchronized fun regions(bitmap: Bitmap): List<Region> {
        val det = detector ?: ObjectDetector.createFromOptions(context, ObjectDetector.ObjectDetectorOptions.builder()
            .setBaseOptions(BaseOptions.builder().setModelAssetPath("efficientdet_lite0.tflite").build()).setScoreThreshold(0.35f).setMaxResults(5).build()).also { detector = it }
        val image = BitmapImageBuilder(bitmap).build()
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
        val image = BitmapImageBuilder(bitmap).build()
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
        var source: LuminanceSource = RGBLuminanceSource(bitmap.width, bitmap.height, pixels)
        repeat(2) {
            val result = decode(source)
            if (result != null) return result
            val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, Matrix().apply { postRotate(90f) }, false)
            val p = IntArray(rotated.width * rotated.height); rotated.getPixels(p, 0, rotated.width, 0, 0, rotated.width, rotated.height)
            source = RGBLuminanceSource(rotated.width, rotated.height, p); rotated.recycle()
        }
        return null
    }
    fun decode(source: LuminanceSource): Code? {
        val reader = MultiFormatReader()
        reader.setHints(mapOf(DecodeHintType.TRY_HARDER to true, DecodeHintType.CHARACTER_SET to "UTF-8"))
        for (s in listOf(source, source.invert())) {
            try { val r = reader.decodeWithState(BinaryBitmap(HybridBinarizer(s))); return Code(r.text, r.barcodeFormat.name) }
            catch (_: NotFoundException) { } finally { reader.reset() }
        }
        return null
    }
}
