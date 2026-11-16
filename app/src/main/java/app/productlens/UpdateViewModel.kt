package app.productlens

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

class UpdateFileProvider : FileProvider()

@Suppress("DEPRECATION")
private fun signingFlags() = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES

@Suppress("DEPRECATION")
private fun PackageInfo.identity(): ApkIdentity {
    val certificates = if (Build.VERSION.SDK_INT >= 28) signingInfo?.apkContentsSigners.orEmpty() else signatures.orEmpty()
    val signers = certificates.map { certificate -> MessageDigest.getInstance("SHA-256").digest(certificate.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 0xff) } }.toSet()
    return ApkIdentity(packageName, versionName, PackageInfoCompat.getLongVersionCode(this), signers)
}

class UpdateViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("updates", Context.MODE_PRIVATE)
    private val directory = File(app.cacheDir, "updates")
    private val installedVersion = requireNotNull(ReleaseVersion.parse(BuildConfig.VERSION_NAME))
    internal var latest by mutableStateOf<UpdateAsset?>(null); private set
    internal var notice by mutableStateOf<UpdateAsset?>(null); private set
    internal var pending by mutableStateOf<UpdateAsset?>(null); private set
    var autoCheck by mutableStateOf(prefs.getBoolean("autoCheck", true)); private set
    var busy by mutableStateOf(false); private set
    var progress by mutableStateOf<Int?>(null); private set
    var status by mutableStateOf("앱 시작 시 GitHub 정식 릴리스를 확인합니다."); private set
    internal val available get() = latest?.takeIf { it.version > installedVersion }
    init { if (autoCheck) check(startup = true) }
    fun changeAutoCheck(value: Boolean) { autoCheck = value; prefs.edit().putBoolean("autoCheck", value).apply() }
    private fun runTask(action: suspend () -> Unit) {
        if (busy) return
        busy = true
        viewModelScope.launch {
            try { action() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { status = e.message ?: "업데이트 처리에 실패했습니다. 네트워크와 저장 공간을 확인하세요." }
            finally { busy = false; progress = null }
        }
    }
    fun check(startup: Boolean = false) = runTask {
        status = "GitHub 최신 버전 확인 중…"
        val release = withContext(Dispatchers.IO) { ReleaseUpdate.latest() }
        latest = release
        if (pending?.version != release.version) pending = null
        status = when {
            release.version > installedVersion -> "새 버전 ${release.version}을 설치할 수 있습니다."
            release.version == installedVersion -> "최신 버전을 사용하고 있습니다."
            else -> "설치 버전이 현재 공개 릴리스보다 새 버전입니다."
        }
        if (startup && shouldNotifyUpdate(installedVersion, release.version, prefs.getString("lastNotified", "").orEmpty())) notice = release
    }
    internal fun markNotified(asset: UpdateAsset) {
        prefs.edit().putString("lastNotified", asset.version.toString()).apply()
    }
    fun dismissNotice() { notice = null }
    @Suppress("DEPRECATION")
    private fun verifyApk(file: File, asset: UpdateAsset) {
        val app = getApplication<Application>()
        verifyDownloadedApk(file, asset)
        val candidate = app.packageManager.getPackageArchiveInfo(file.absolutePath, signingFlags())
            ?: throw UpdateException("다운로드 파일을 Android APK로 읽지 못했습니다.")
        val installed = app.packageManager.getPackageInfo(app.packageName, signingFlags())
        verifyApkIdentity(installed.identity(), candidate.identity(), asset.version)
    }
    fun download() {
        val asset = available ?: return
        runTask {
            progress = 0; status = "설치 파일을 다운로드하는 중…"
            withContext(Dispatchers.IO) {
                ReleaseUpdate.download(asset, directory, { verifyApk(it, asset) }) { value -> withContext(Dispatchers.Main.immediate) { progress = value } }
            }
            pending = asset
            status = "검증을 완료했습니다. ‘설치 계속’을 눌러 Android 설치 화면을 여세요."
        }
    }
    fun openInstaller(context: Context) {
        val asset = pending ?: return
        runTask {
            val apk = File(directory, asset.filename)
            withContext(Dispatchers.IO) { verifyApk(apk, asset) }
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", apk)
            context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
            status = "Android 설치 화면을 열었습니다. 설치 완료 여부는 해당 화면에서 확인하세요."
        }
    }
    fun permissionNeeded() { status = "Android 설정에서 제품렌즈의 ‘이 출처 허용’을 켠 뒤 설치를 계속하세요." }
}
