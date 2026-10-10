package com.openminis.app.ui.chat

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import novex.core.*
import com.openminis.app.ui.theme.MinisTheme
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Native rendering/touch tests. Run in the isolated QA emulator, never against a user's library. */
@OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class NovexFoundationInteractionTest {
    @get:Rule val ui = createComposeRule(effectContext = kotlinx.coroutines.test.StandardTestDispatcher())

    private fun screenshot(name: String) {
        ui.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        instrumentation.uiAutomation.waitForIdle(500, 5_000)
        assertEquals("A system window must not obscure the surface being accepted",
            instrumentation.targetContext.packageName,
            instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString())
        val image = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        val target = File(instrumentation.targetContext.cacheDir, "foundation-ui/$name.png")
        target.parentFile!!.mkdirs()
        target.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        image.recycle()
    }


    @Test fun approvalSelectionNeverExecutesUntilExplicitConfirmation() {
        var visible by mutableStateOf(true)
        var approved = 0
        val operation = NovexToolOperation("chat", "reply", "call", "learning_start",
            """{"preflight_id":"internal-plan","collection_ref":"novex://source-collections/a"}""",
            "整理资料", "将整理 2 项资料。累计上限：输入 180000、输出 24000 个词元。")
        ui.setContent {
            MinisTheme(darkTheme = false) {
                Text("对话草稿保留在这里")
                if (visible) NovexToolApprovalDialog(operation, { approved++; visible = false }, { visible = false })
            }
        }
        ui.onNodeWithText("internal-plan", substring = true).assertDoesNotExist()
        ui.waitUntil(15_000) { ui.onNodeWithText("拒绝").isDisplayed() }
        ui.onNodeWithText("拒绝").assertIsDisplayed()
        screenshot("permission")
        ui.onNode(isToggleable()).performTouchInput { click() }
        ui.runOnIdle { assertEquals(0, approved) }
        ui.onNodeWithText("确认执行").assertIsEnabled()
        ui.onNode(isToggleable()).performTouchInput { click() }
        ui.runOnIdle { assertEquals(0, approved) }
        ui.waitUntil(15_000) { ui.onNodeWithText("拒绝").isDisplayed() }
        ui.onNodeWithText("确认执行").assertIsNotEnabled()
        ui.onNode(isToggleable()).performTouchInput { click() }
        ui.onNodeWithText("确认执行").performTouchInput { click() }
        ui.runOnIdle { assertEquals(1, approved) }
        ui.onNodeWithText("同意执行这一次").assertDoesNotExist()
        ui.onNodeWithText("对话草稿保留在这里").assertIsDisplayed()
    }

    @Test fun executionDetailsStayFoldedUntilOpenedAndFailureRemainsReachable() {
        val work = AssistantBlock("work", "text", "准备查找目标模块，然后写入正文。", executionText = true)
        val tool = AssistantBlock("call", "tool_use", "原始工具结果", ToolBlockStatus.FAILED, "保存模块", "novex_write_module", "{}")
        val rows = listOf(FlatChatItem.AssistantText("reply", work, false, messageMarkdown = ""),
            FlatChatItem.AssistantToolUse("reply", tool, listOf(work, tool)))
        val process = foldNovexExecutionProcesses(rows).single() as FlatChatItem.AssistantProcess
        var expanded by mutableStateOf(false)
        ui.setContent {
            MinisTheme(darkTheme = false) {
                Column {
                    Text("这一处修改尚未保存，原内容仍保留。")
                    NovexExecutionProcessRow(process) { expanded = true }
                }
                if (expanded) NovexExecutionProcessDialog(process, { expanded = false }, {})
            }
        }
        ui.onNodeWithText(work.content).assertDoesNotExist()
        ui.onNodeWithText("有操作未完成", substring = true).assertIsDisplayed()
        screenshot("execution-folded")
        ui.onNodeWithText("执行过程", substring = true).performTouchInput { click() }
        ui.onNodeWithText(work.content).assertIsDisplayed()
        ui.onNodeWithText("返回对话").performTouchInput { click() }
        ui.onNodeWithText(work.content).assertDoesNotExist()
        ui.onNodeWithText("这一处修改尚未保存，原内容仍保留。").assertIsDisplayed()
    }

    @Test fun approvalCannotSubmitTwiceAndNewOperationNeedsItsOwnSelection() {
        var operation by mutableStateOf(NovexToolOperation("chat", "reply", "first", "save_checkpoint", "{}", "保存进度"))
        var approved = 0
        var rejected = 0
        ui.setContent { MinisTheme(darkTheme = true) {
            NovexToolApprovalDialog(operation, { approved++ }, { rejected++ })
        } }
        ui.waitUntil(15_000) { ui.onNodeWithText("拒绝").isDisplayed() }
        ui.onNodeWithText("确认执行").assertIsNotEnabled()
        ui.onNode(isToggleable()).performTouchInput { click() }
        ui.onNodeWithText("确认执行").performTouchInput { click(); click() }
        ui.runOnIdle { assertEquals(1, approved); assertEquals(0, rejected) }
        ui.onNodeWithText("确认执行").assertIsNotEnabled()
        ui.runOnIdle { operation = operation.copy(callId = "second") }
        ui.onNode(isToggleable()).assertIsOff()
        ui.onNodeWithText("确认执行").assertIsNotEnabled()
        ui.onNode(isToggleable()).performTouchInput { click() }
        ui.onNodeWithText("拒绝").performTouchInput { click() }
        ui.runOnIdle { assertEquals(1, approved); assertEquals(1, rejected) }
    }

    @Test fun closingCheckedApprovalDoesNotExecute() {
        var visible by mutableStateOf(true)
        var approved = 0
        var rejected = 0
        ui.setContent { MinisTheme(darkTheme = false) {
            Text("原草稿仍在")
            if (visible) NovexToolApprovalDialog(NovexToolOperation("chat", "reply", "close", "novex_write_card", "{}", "保存卡片"),
                { approved++; visible = false }, { rejected++; visible = false })
        } }
        ui.waitUntil(15_000) { ui.onNodeWithText("拒绝").isDisplayed() }
        ui.onNode(isToggleable()).performTouchInput { click() }
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
        ui.runOnIdle { assertEquals(0, approved); assertEquals(1, rejected) }
        ui.onNodeWithText("原草稿仍在").assertIsDisplayed()
    }
}
