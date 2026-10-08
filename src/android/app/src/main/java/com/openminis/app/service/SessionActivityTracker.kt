package com.openminis.app.service

import android.content.Context
import kotlinx.coroutines.flow.StateFlow
import novex.android.runtime.LiveSessionHub

/**
 * 会话活跃跟踪门面（P3.5b 重写）。实现全部在
 * [novex.android.runtime.LiveSessionHub]：占据状态（流式/在场）、
 * 工具信号、服务起停裁决都在那边；本对象只把历史 API
 * 原名转发过去，全仓调用点（含 UI 层的全限定引用）零改动。
 *
 * 公开成员名是冻结面——ChatViewModel / ChatScreen / NovenSessionRow /
 * MainActivity 以全限定名引用其中的流与方法。
 */
object SessionActivityTracker {

    val activeSessions: StateFlow<Set<String>> get() = LiveSessionHub.streamingIds
    val presentSessions: StateFlow<Set<String>> get() = LiveSessionHub.presenceIds
    val currentToolStatus: StateFlow<String> get() = LiveSessionHub.toolStatus
    val lastTaskFinishedAtMs: StateFlow<Long?> get() = LiveSessionHub.lastRunFinishedAtMs
    val currentRunStartedAtMs: StateFlow<Long?> get() = LiveSessionHub.currentRunStartedAtMs
    val currentToolName: StateFlow<String?> get() = LiveSessionHub.toolKind
    val currentToolTitle: StateFlow<String?> get() = LiveSessionHub.toolHeadline
    val isToolRunning: StateFlow<Boolean> get() = LiveSessionHub.toolBusy
    fun setCompletionListener(listener: ((sessionId: String, isError: Boolean) -> Unit)?) =
        LiveSessionHub.setCompletionListener(listener)

    fun init(context: Context) = LiveSessionHub.attach(context)

    fun setActive(sessionId: String, onStop: (() -> Unit)? = null) =
        LiveSessionHub.markStreaming(sessionId, onStop)

    fun setInactive(sessionId: String) = LiveSessionHub.markStreamEnded(sessionId)

    fun markStreamError(sessionId: String) = LiveSessionHub.flagStreamFailure(sessionId)

    fun setPresent(sessionId: String) = LiveSessionHub.markPresent(sessionId)

    fun setAbsent(sessionId: String) = LiveSessionHub.markAbsent(sessionId)

    fun clearPresence() = LiveSessionHub.dropPresence()

    fun cancelAllActiveStreams() = LiveSessionHub.cancelEveryStream()

    fun isActive(sessionId: String): Boolean = LiveSessionHub.isStreaming(sessionId)

    /** 旧式入口：只改状态文案，工具名与运行位不动。 */
    fun updateToolStatus(status: String) = LiveSessionHub.pushStatusText(status)

    fun updateToolStatus(status: String, toolName: String?, isRunning: Boolean) =
        LiveSessionHub.pushToolSignal(status, toolName, isRunning, null)

    fun updateToolStatus(status: String, toolName: String?, isRunning: Boolean, toolTitle: String?) =
        LiveSessionHub.pushToolSignal(status, toolName, isRunning, toolTitle)

    fun clearToolRunning() = LiveSessionHub.closeToolRun()
}
