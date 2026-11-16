package app.productlens

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Rule
import org.junit.Test

class UiTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    @Test fun launchNavigationManualValidationAndSettings() {
        rule.onNodeWithText("카메라로 제품 인식").assertExists()
        rule.onNodeWithText("바코드 번호 직접 입력").performClick()
        rule.onNodeWithText("바코드 번호").performTextInput("123")
        rule.onNodeWithText("조회", useUnmergedTree = true).performClick()
        rule.onNodeWithText("EAN-8/13", substring = true).assertExists()
        rule.onNodeWithText("확인").performClick()
        rule.onNodeWithText("기록", useUnmergedTree = true).performClick()
        rule.onNodeWithText("인식 기록").assertExists()
        rule.onNodeWithText("내 상품", useUnmergedTree = true).performClick()
        rule.onNodeWithText("내 상품 카탈로그").assertExists()
        rule.onNodeWithText("설정", useUnmergedTree = true).performClick()
        rule.onNodeWithText("인식 설정").assertExists()
        rule.onNodeWithText("공개 상품 DB 온라인 조회").assertExists()
    }
}
