package app.productlens

import java.io.ByteArrayInputStream
import java.io.File
import java.security.MessageDigest
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ReleaseUpdateTest {
    private fun fixture() = javaClass.getResource("/github-release-1.0.0.json")!!.readText().removePrefix("\uFEFF")
    private fun release() = JSONObject(fixture())
    private fun rejects(json: JSONObject) { assertThrows(UpdateException::class.java) { parseLatestRelease(json.toString()) } }
    @Test fun parsesActuallyPublishedInitialRelease() {
        val asset = parseLatestRelease(fixture())
        assertEquals("1.0.0", asset.version.toString())
        assertEquals("ProductLens-1.0.0-debug.apk", asset.filename)
        assertEquals(69459603L, asset.bytes)
        assertEquals("7f63291c38f6d4fb29361c7238bbfeed8ac9e79db5bd82763d79b6e9ec445c53", asset.sha256)
        assertTrue(asset.notes.isNotBlank())
    }
    @Test fun comparesNumericVersionsAndRejectsNonStableSyntax() {
        assertTrue(ReleaseVersion.parse("1.10.0")!! > ReleaseVersion.parse("1.9.9")!!)
        listOf("1.1", "v1.1.0", "1.1.0-beta", "1.01.0", "-1.0.0", "9999999999.0.0").forEach { assertNull(it, ReleaseVersion.parse(it)) }
    }
    @Test fun startupNoticeAppearsOnlyOncePerNewerVersion() {
        val current = ReleaseVersion.parse("1.0.0")!!
        val next = ReleaseVersion.parse("1.1.0")!!
        assertTrue(shouldNotifyUpdate(current, next, ""))
        assertFalse(shouldNotifyUpdate(current, next, "1.1.0"))
        assertFalse(shouldNotifyUpdate(current, next, "1.2.0"))
        assertFalse(shouldNotifyUpdate(next, next, ""))
        assertTrue(shouldNotifyUpdate(next, ReleaseVersion.parse("1.2.0")!!, "1.1.0"))
    }
    @Test fun excludesDraftPrereleaseAndUnrecognizedTags() {
        rejects(release().put("draft", true)); rejects(release().put("prerelease", true)); rejects(release().put("tag_name", "v1.0.0-beta"))
    }
    @Test fun rejectsWrongRepositoryFilenameDigestAndMissingApk() {
        for ((key, value) in listOf("browser_download_url" to "https://github.com/other/repo/releases/download/v1.0.0/ProductLens-1.0.0-debug.apk",
            "name" to "other.apk", "digest" to "sha256:bad", "state" to "new")) {
            val j = release(); j.getJSONArray("assets").getJSONObject(0).put(key, value); rejects(j)
        }
        val j = release(); j.getJSONArray("assets").getJSONObject(0).put("size", 0); rejects(j)
        val tooLarge = release(); tooLarge.getJSONArray("assets").getJSONObject(0).put("size", 101L * 1024 * 1024); rejects(tooLarge)
        rejects(release().put("assets", org.json.JSONArray()))
    }
    @Test fun rejectsDuplicateInstallAssetsAndLimitsNotes() {
        val j = release(); val a = j.getJSONArray("assets"); a.put(a.getJSONObject(0)); rejects(j)
        assertEquals(8000, parseLatestRelease(release().put("body", "x".repeat(9000)).toString()).notes.length)
    }
    @Test fun onlyAllowsExpectedGithubHttpsDownloadRedirects() {
        assertTrue(validDownloadRedirect("https://release-assets.githubusercontent.com/path?token=public".toHttpUrl()))
        for (url in listOf("http://release-assets.githubusercontent.com/x", "https://evil.example/x", "https://release-assets.githubusercontent.com:8443/x", "https://user:pass@release-assets.githubusercontent.com/x")) assertFalse(url, validDownloadRedirect(url.toHttpUrl()))
    }
    @Test fun boundedReleaseBodyRejectsOversizedResponses() {
        assertEquals(4, ByteArrayInputStream(ByteArray(4)).readLimited(4).size)
        assertThrows(UpdateException::class.java) { ByteArrayInputStream(ByteArray(5)).readLimited(4) }
    }
    @Test fun rejectsDowngradeWrongPackageVersionAndSigner() {
        val installed = ApkIdentity("app.productlens", "1.0.0", 1, setOf("certificate"))
        val candidate = installed.copy(versionName = "1.1.0", versionCode = 2)
        val expected = ReleaseVersion.parse("1.1.0")!!
        verifyApkIdentity(installed, candidate, expected)
        for (bad in listOf(candidate.copy(packageName = "other.app"), candidate.copy(versionName = "9.9.9"), candidate.copy(versionCode = 1), candidate.copy(signers = setOf("other")), candidate.copy(signers = emptySet())))
            assertThrows(UpdateException::class.java) { verifyApkIdentity(installed, bad, expected) }
    }
    @Test fun downloadedBytesMustMatchSizeAndSha256() {
        val file = File.createTempFile("productlens-test-", ".apk")
        try {
            val bytes = "fixture bytes".toByteArray(); file.writeBytes(bytes)
            val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }
            val asset = UpdateAsset(ReleaseVersion.parse("1.1.0")!!, bytes.size.toLong(), hash)
            verifyDownloadedApk(file, asset)
            file.writeText("tampered data")
            assertThrows(UpdateException::class.java) { verifyDownloadedApk(file, asset) }
            file.writeBytes(ByteArray(bytes.size))
            assertThrows(UpdateException::class.java) { verifyDownloadedApk(file, asset) }
        } finally { file.delete() }
    }
}
