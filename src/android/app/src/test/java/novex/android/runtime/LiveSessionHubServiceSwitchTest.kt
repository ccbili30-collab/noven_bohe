package novex.android.runtime

import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * P3.5b 重写后的保活中枢关键行为守护：
 *  - 流式边沿起服务、全空停服务、纯草稿在场不起服务；
 *  - 工具信号两入口的差别（旧式只改文案 / 完整信号）与收尾归零；
 *  - 运行计时锚点的起落；
 *  - 通知栏停止按键扇出到各会话的取消回调。
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28], manifest = Config.NONE)
class LiveSessionHubServiceSwitchTest {

    private lateinit var app: Application

    @Before
    fun setUp() {
        app = RuntimeEnvironment.getApplication()
        LiveSessionHub.attach(app)
        // 吸干前序状态可能残留的服务命令，让每个用例从干净的命令队列出发。
        while (shadowOf(app).nextStartedService != null) { /* drain */ }
        while (shadowOf(app).nextStoppedService != null) { /* drain */ }
    }

    @Test fun streamingEdgeStartsServiceAndEmptySetStopsIt() {
        LiveSessionHub.markStreaming("s1")
        assertNotNull("首个流式会话必须起前台服务", shadowOf(app).nextStartedService)

        LiveSessionHub.markStreamEnded("s1")
        assertNotNull("流式与在场全空必须停服务", shadowOf(app).nextStoppedService)
    }

    @Test fun plainDraftPresenceNeverStartsTheService() {
        LiveSessionHub.markPresent("__new__draft-9")
        assertNull("纯草稿在场不得起前台服务", shadowOf(app).nextStartedService)
        LiveSessionHub.markAbsent("__new__draft-9")
    }

    @Test fun realPresenceKeepsServiceUntilUserLeaves() {
        LiveSessionHub.markPresent("chat-1")
        assertNotNull("真实在场必须起前台服务", shadowOf(app).nextStartedService)
        LiveSessionHub.markPresent("chat-1") // 幂等
        LiveSessionHub.markAbsent("chat-1")
        assertNotNull("离场后无流式无在场必须停服务", shadowOf(app).nextStoppedService)
    }

    @Test fun legacyStatusTextLeavesToolKindAndRunningBitAlone() {
        LiveSessionHub.pushToolSignal("Running: shell", "shell_execute", true, "装环境")
        LiveSessionHub.pushStatusText("Downloading packages")
        assertEquals("Downloading packages", LiveSessionHub.toolStatus.value)
        assertEquals("shell_execute", LiveSessionHub.toolKind.value)
        assertTrue(LiveSessionHub.toolBusy.value)
        assertEquals("装环境", LiveSessionHub.toolHeadline.value)
    }

    @Test fun closeToolRunDropsKindBusyAndHeadline() {
        LiveSessionHub.pushToolSignal("Running: browser", "browser_use", true, "查资料")
        LiveSessionHub.closeToolRun()
        assertNull(LiveSessionHub.toolKind.value)
        assertNull(LiveSessionHub.toolHeadline.value)
        assertEquals(false, LiveSessionHub.toolBusy.value)
    }


    @Test fun runClockAnchorsRiseAndFallWithTheStreamingSet() {
        LiveSessionHub.markStreaming("r1")
        assertNotNull(LiveSessionHub.currentRunStartedAtMs.value)
        assertNull(LiveSessionHub.lastRunFinishedAtMs.value)

        LiveSessionHub.markStreamEnded("r1")
        assertNotNull("末位会话结束时落下完成时间戳", LiveSessionHub.lastRunFinishedAtMs.value)

        LiveSessionHub.markStreaming("r2")
        assertNull("新一轮必须作废上一轮的完成戳", LiveSessionHub.lastRunFinishedAtMs.value)
        LiveSessionHub.markStreamEnded("r2")
    }

    @Test fun stopActionFansOutToEveryRegisteredCanceller() {
        var cancelledA = false
        var cancelledB = false
        LiveSessionHub.markStreaming("a") { cancelledA = true }
        LiveSessionHub.markStreaming("b") { cancelledB = true }
        LiveSessionHub.cancelEveryStream()
        assertTrue(cancelledA)
        assertTrue(cancelledB)
        LiveSessionHub.markStreamEnded("a")
        LiveSessionHub.markStreamEnded("b")
    }

    @Test fun completionCallbackFiresOnlyForGenuinelyActiveSessions() {
        val endings = mutableListOf<Pair<String, Boolean>>()
        LiveSessionHub.setCompletionListener { id, failed -> endings += id to failed }
        LiveSessionHub.markStreaming("e1")
        LiveSessionHub.flagStreamFailure("e1")
        LiveSessionHub.markStreamEnded("e1")
        LiveSessionHub.markStreamEnded("never-started")
        assertEquals(listOf("e1" to true), endings)
        LiveSessionHub.setCompletionListener(null)
    }
}
