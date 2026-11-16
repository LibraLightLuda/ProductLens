package app.productlens

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject

internal const val RELEASE_REPOSITORY = "https://github.com/LibraLightLuda/ProductLens"
internal const val RELEASE_PAGE = "$RELEASE_REPOSITORY/releases/latest"
private const val RELEASE_API = "https://api.github.com/repos/LibraLightLuda/ProductLens/releases/latest"
private const val MAX_APK_BYTES = 100L * 1024 * 1024

internal class UpdateException(message: String) : IOException(message)
internal data class ReleaseVersion(val major: Int, val minor: Int, val patch: Int) : Comparable<ReleaseVersion> {
    override fun compareTo(other: ReleaseVersion) = compareValuesBy(this, other, ReleaseVersion::major, ReleaseVersion::minor, ReleaseVersion::patch)
    override fun toString() = "$major.$minor.$patch"
    companion object {
        fun parse(value: String): ReleaseVersion? {
            if (!value.matches(Regex("(0|[1-9][0-9]{0,8})\\.(0|[1-9][0-9]{0,8})\\.(0|[1-9][0-9]{0,8})"))) return null
            val numbers = value.split('.').map(String::toInt)
            return ReleaseVersion(numbers[0], numbers[1], numbers[2])
        }
    }
}
internal data class UpdateAsset(val version: ReleaseVersion, val bytes: Long, val sha256: String, val notes: String = "") {
    val filename get() = "ProductLens-$version-debug.apk"
    val url get() = "$RELEASE_REPOSITORY/releases/download/v$version/$filename"
}
internal fun shouldNotifyUpdate(installed: ReleaseVersion, latest: ReleaseVersion, lastNotified: String): Boolean =
    latest > installed && (ReleaseVersion.parse(lastNotified)?.let { latest > it } ?: true)

internal fun InputStream.readLimited(limit: Int): ByteArray {
    val result = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        val count = read(buffer)
        if (count < 0) return result.toByteArray()
        if (result.size() + count > limit) throw UpdateException("응답이 허용 크기를 초과했습니다.")
        result.write(buffer, 0, count)
    }
}
internal fun parseLatestRelease(json: String): UpdateAsset {
    val data = JSONObject(json)
    val tag = data.optString("tag_name")
    val version = ReleaseVersion.parse(tag.removePrefix("v")) ?: throw UpdateException("정식 릴리스 버전을 읽을 수 없습니다.")
    if (tag != "v$version" || data.optBoolean("draft", true) || data.optBoolean("prerelease", true))
        throw UpdateException("정식 공개 릴리스만 설치할 수 있습니다.")
    val list = data.optJSONArray("assets") ?: throw UpdateException("설치 파일이 없습니다.")
    val matches = (0 until list.length()).mapNotNull(list::optJSONObject).filter { it.optString("name") == "ProductLens-$version-debug.apk" }
    val entry = matches.singleOrNull() ?: throw UpdateException("버전에 맞는 설치용 APK를 찾지 못했습니다.")
    val digest = entry.optString("digest")
    val asset = UpdateAsset(version, entry.optLong("size"), digest.removePrefix("sha256:"), data.optString("body", "").take(8000))
    if (entry.optString("state") != "uploaded" || entry.optString("browser_download_url") != asset.url ||
        asset.bytes !in 1..MAX_APK_BYTES || !digest.matches(Regex("sha256:[0-9a-f]{64}")))
        throw UpdateException("설치 파일의 주소·크기·SHA-256이 올바르지 않습니다.")
    return asset
}
internal fun validDownloadRedirect(url: HttpUrl) = url.isHttps && url.port == 443 && url.username.isEmpty() &&
    url.password.isEmpty() && url.host == "release-assets.githubusercontent.com"

internal data class ApkIdentity(val packageName: String, val versionName: String?, val versionCode: Long, val signers: Set<String>)
internal fun verifyApkIdentity(installed: ApkIdentity, candidate: ApkIdentity, expected: ReleaseVersion) {
    if (candidate.packageName != installed.packageName || candidate.versionName != expected.toString() || candidate.versionCode <= installed.versionCode)
        throw UpdateException("설치된 제품렌즈보다 새 버전인 동일 패키지가 아닙니다.")
    if (installed.signers.isEmpty() || candidate.signers != installed.signers)
        throw UpdateException("APK 서명이 현재 설치된 제품렌즈와 일치하지 않습니다.")
}
internal fun verifyDownloadedApk(file: File, asset: UpdateAsset) {
    if (file.length() != asset.bytes) throw UpdateException("APK 크기가 릴리스 정보와 다릅니다.")
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().buffered().use { input ->
        val buffer = ByteArray(64 * 1024)
        while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
    }
    val hash = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    if (hash != asset.sha256) throw UpdateException("APK의 SHA-256이 릴리스 정보와 다릅니다.")
}
internal object ReleaseUpdate {
    private val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
        .connectTimeout(10, TimeUnit.SECONDS).readTimeout(25, TimeUnit.SECONDS).callTimeout(3, TimeUnit.MINUTES).build()
    private val metadataClient = client.newBuilder().callTimeout(20, TimeUnit.SECONDS).build()
    private fun request(url: String) = Request.Builder().url(url).header("User-Agent", "ProductLens-updater")
        .header("Accept", "application/vnd.github+json").build()
    private fun requireSuccess(response: Response) {
        if (!response.isSuccessful) throw UpdateException(when (response.code) {
            403, 429 -> "GitHub 요청이 제한되었습니다. 잠시 후 다시 확인하세요."
            404 -> "공개 릴리스 또는 설치 파일이 없습니다."
            else -> "GitHub 연결 실패 (HTTP ${response.code})"
        })
    }
    fun latest(): UpdateAsset = metadataClient.newCall(request(RELEASE_API)).execute().use { response ->
        requireSuccess(response)
        val body = response.body ?: throw UpdateException("릴리스 응답이 비어 있습니다.")
        parseLatestRelease(body.byteStream().readLimited(1024 * 1024).toString(Charsets.UTF_8))
    }
    private fun openDownload(asset: UpdateAsset): Response {
        var target = asset.url.toHttpUrl()
        repeat(4) {
            val response = client.newCall(request(target.toString())).execute()
            if (response.isSuccessful) return response
            response.use {
                if (response.code !in setOf(301, 302, 303, 307, 308)) requireSuccess(response)
                target = response.header("Location")?.let(target::resolve)?.takeIf(::validDownloadRedirect)
                    ?: throw UpdateException("공식 GitHub 다운로드 서버 외의 이동을 차단했습니다.")
            }
        }
        throw UpdateException("다운로드 주소 이동 횟수가 너무 많습니다.")
    }
    suspend fun download(asset: UpdateAsset, directory: File, verifyApk: (File) -> Unit, progress: suspend (Int) -> Unit): File {
        require(asset.bytes in 1..MAX_APK_BYTES && asset.sha256.matches(Regex("[0-9a-f]{64}")))
        if (!directory.isDirectory && !directory.mkdirs()) throw UpdateException("업데이트 저장 폴더를 만들 수 없습니다.")
        val temporary = File.createTempFile("productlens-", ".part", directory)
        try {
            var count = 0L
            var shown = -1
            openDownload(asset).use { response ->
                val body = response.body ?: throw UpdateException("다운로드 응답이 비어 있습니다.")
                temporary.outputStream().buffered().use { output ->
                    val input = body.byteStream()
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val size = input.read(buffer)
                        if (size < 0) break
                        count += size
                        if (count > asset.bytes) throw UpdateException("다운로드 크기가 릴리스 정보보다 큽니다.")
                        output.write(buffer, 0, size)
                        val percent = (count * 100 / asset.bytes).toInt()
                        if (percent != shown) { shown = percent; progress(percent) }
                    }
                }
            }
            verifyDownloadedApk(temporary, asset)
            verifyApk(temporary)
            currentCoroutineContext().ensureActive()
            val destination = File(directory, asset.filename)
            if (!temporary.renameTo(destination)) throw UpdateException("검증한 설치 파일을 저장하지 못했습니다.")
            directory.listFiles()?.filter { it != destination && it.name.matches(Regex("ProductLens-[0-9.]+-debug\\.apk")) }?.forEach(File::delete)
            return destination
        } finally { temporary.delete() }
    }
}
