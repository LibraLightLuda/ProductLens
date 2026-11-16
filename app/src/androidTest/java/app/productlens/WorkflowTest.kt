package app.productlens

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class WorkflowTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    @Test fun offlineGalleryCropRecognizeEditRegisterFindAndHistoryDelete() {
        lateinit var vm: LensViewModel
        rule.activityRule.scenario.onActivity { vm = ViewModelProvider(it)[LensViewModel::class.java] }
        rule.waitUntil(15000) { !vm.busy }
        val context = rule.activity
        val image = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().context.assets.open("burger.jpg").use { BitmapFactory.decodeStream(it) }
        val file = File(context.cacheDir, "workflow.jpg").apply { outputStream().use { image.compress(android.graphics.Bitmap.CompressFormat.JPEG, 95, it) } }
        rule.runOnIdle { vm.setting("online", false); vm.load(Uri.fromFile(file)) }
        rule.waitUntil(30000) { !vm.busy && vm.page == "crop" }
        rule.onNodeWithText("이 영역으로 제품 찾기").performScrollTo().performClick()
        rule.waitUntil(60000) { !vm.busy && vm.page == "result" }
        assertNotNull(vm.result?.vector)
        assertTrue(vm.result!!.vector!!.all { it.isFinite() })
        rule.runOnIdle { vm.editText("테스트브랜드\n테스트버거 MVP-100\n250g") }
        rule.waitUntil(15000) { !vm.busy }
        assertTrue(vm.query.contains("MVP-100"))
        rule.onNodeWithText("내 상품 등록").performScrollTo().performClick()
        rule.onNodeWithText("등록", useUnmergedTree = true).performClick()
        rule.waitUntil(20000) { !vm.busy && vm.catalog.isNotEmpty() }
        rule.onNodeWithText("확인").performClick()
        val catalogId = vm.catalog.first().id
        rule.runOnIdle { vm.load(Uri.fromFile(file)) }
        rule.waitUntil(30000) { !vm.busy && vm.page == "crop" }
        rule.runOnIdle { vm.analyze() }
        rule.waitUntil(60000) { !vm.busy && vm.page == "result" }
        assertEquals(catalogId, vm.result!!.candidates.first().item.id)
        assertTrue(vm.result!!.candidates.first().cosine!! > .99)
        val recordId = vm.result!!.id
        assertTrue(recordId > 0)
        rule.runOnIdle { vm.delete("history", recordId) }
        rule.waitUntil(15000) { !vm.busy }
        assertFalse(vm.history.any { it.id == recordId })
        rule.runOnIdle { vm.delete("catalog", catalogId) }
        rule.waitUntil(15000) { !vm.busy }
        rule.runOnIdle { vm.setting("online", true) }
        file.delete()
    }
}
