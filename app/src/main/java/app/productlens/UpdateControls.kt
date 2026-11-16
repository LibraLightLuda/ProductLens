package app.productlens

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

@Composable internal fun StartupUpdateNotice(updates: UpdateViewModel, openSettings: () -> Unit) {
    val notice = updates.notice ?: return
    LaunchedEffect(notice.version) { updates.markNotified(notice) }
    AlertDialog(onDismissRequest = updates::dismissNotice, title = { Text("새 버전 ${notice.version}이 나왔습니다") },
        text = { Text("현재 ${BuildConfig.VERSION_NAME} → ${notice.version}\n설정에서 변경사항을 확인하고 업데이트할 수 있습니다. 이 버전의 시작 알림은 한 번만 표시합니다.") },
        confirmButton = { TextButton(onClick = { updates.dismissNotice(); openSettings() }) { Text("업데이트 보기") } },
        dismissButton = { TextButton(onClick = updates::dismissNotice) { Text("나중에") } })
}

@Composable internal fun UpdateControls(updates: UpdateViewModel, open: (String) -> Unit) {
    val context = LocalContext.current
    fun requestInstall() {
        if (context.packageManager.canRequestPackageInstalls()) updates.openInstaller(context)
        else updates.permissionNeeded()
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { requestInstall() }
    fun install() {
        if (context.packageManager.canRequestPackageInstalls()) updates.openInstaller(context)
        else {
            updates.permissionNeeded()
            try { permission.launch(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))) }
            catch (_: android.content.ActivityNotFoundException) { updates.permissionNeeded() }
        }
    }
    Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("앱 업데이트", style = MaterialTheme.typography.titleLarge)
        Text("설치 버전 ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        updates.latest?.let { Text("GitHub 정식 버전 ${it.version}") }
        Text(updates.status)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("앱 시작 시 자동 확인", Modifier.weight(1f))
            Switch(updates.autoCheck, updates::changeAutoCheck)
        }
        Text("같은 새 버전의 시작 알림은 한 번만 표시합니다. 다운로드와 설치는 직접 선택합니다.", style = MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick = { updates.check() }, enabled = !updates.busy, modifier = Modifier.fillMaxWidth()) { Text("최신 버전 확인") }
        if (updates.busy) {
            val progress = updates.progress
            if (progress != null) { LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth()); Text("다운로드 $progress% · 크기·SHA-256·앱 서명 확인") }
            else LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        if (updates.pending != null) Button(onClick = ::install, enabled = !updates.busy, modifier = Modifier.fillMaxWidth()) { Text("설치 계속") }
        else updates.available?.let { asset -> Button(onClick = updates::download, enabled = !updates.busy, modifier = Modifier.fillMaxWidth()) { Text("${asset.version} 다운로드") } }
        updates.latest?.notes?.takeIf(String::isNotBlank)?.let { Text("릴리스 변경사항", style = MaterialTheme.typography.titleMedium); Text(it.take(8000)) }
        TextButton(onClick = { open(RELEASE_PAGE) }) { Text("GitHub 릴리스 열기") }
    } }
}
