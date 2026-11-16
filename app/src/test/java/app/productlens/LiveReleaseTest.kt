package app.productlens

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class LiveReleaseTest {
    @Test fun actualPublicReleaseMetadataAndApkDownload() = runBlocking {
        assumeTrue(System.getenv("PRODUCTLENS_LIVE_UPDATE") == "1")
        val asset = ReleaseUpdate.latest()
        val dir = Files.createTempDirectory("productlens-live-").toFile()
        try {
            val apk = ReleaseUpdate.download(asset, dir, { verifyDownloadedApk(it, asset) }, {})
            assertTrue(apk.isFile)
            assertEquals(asset.bytes, apk.length())
            verifyDownloadedApk(apk, asset)
            val current = ReleaseVersion.parse(BuildConfig.VERSION_NAME)!!
            assertEquals(current, asset.version)
            assertTrue(shouldNotifyUpdate(ReleaseVersion.parse("1.0.0")!!, asset.version, ""))
            assertFalse(shouldNotifyUpdate(ReleaseVersion.parse("1.0.0")!!, asset.version, asset.version.toString()))
            println("PUBLIC_RELEASE=${asset.version}; APK_SHA256=${asset.sha256}; bytes=${apk.length()}; once-per-version=PASS")
        } finally { dir.listFiles()?.forEach(File::delete); dir.delete() }
    }
}
