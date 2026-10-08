package novex.android.runtime

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import com.openminis.app.crash.CrashFrequencyDetector
import com.openminis.app.crash.ProcessExitEvidence
import com.openminis.app.service.AgentForegroundService

/**
 * 保活前台服务的全部行为逻辑，宿主 [AgentForegroundService] 只剩一副
 * Manifest 壳（组件名与 mediaPlayback 类型是冻结面），生命周期回调
 * 逐个转发到这里。
 *
 * 两块职责：
 *  1. **前台身份**——onCreate 里第一时间 startForeground 占位（5 秒
 *     死线属于 startForegroundService，onStartCommand 里才补就迟了），
 *     之后每次状态跳动重发正式状态行；
 *  2. **唤醒锁**——部分 OEM 在熄屏后对前台服务照样降频，长流式会半路
 *     停摆，服务存活期间持一把 PARTIAL_WAKE_LOCK 兜底，onDestroy
 *     确定性释放。
 *
 * 悬浮胶囊已退役：后台进度只由通知行承担，不再往屏幕上叠悬浮窗。
 */
internal class AgentKeepAlive(private val host: AgentForegroundService) {

    private val statusLine = KeepAliveStatusLine(host)
    private var bootAnchorMs: Long = 0L
    private var wakeLock: PowerManager.WakeLock? = null

    // ── 生命周期转发 ─────────────────────────────────────────────────

    fun onCreated() {
        ProcessExitEvidence.record(host, "agent-service.create")
        bootAnchorMs = SystemClock.elapsedRealtime()
        statusLine.serviceBootAnchorMs = bootAnchorMs
        statusLine.ensureChannels()
        enterForeground(statusLine.bootstrapNotification())
        ProcessExitEvidence.record(host, "agent-service.foreground-registered")
        Log.i(TAG, "startForeground satisfied ${SystemClock.elapsedRealtime() - bootAnchorMs}ms after onCreate")
        // 安全模式（崩溃风暴刚触发）：MinisApp 跳过了仓库 lateinit 初始化，
        // 系统粘性重启的本次服务不得去碰它们——否则在崩溃检测器上再叠
        // 一层 UninitializedPropertyAccessException 崩溃。前台身份已占，
        // 剩下的交给 onStartCommand 收摊。
        if (CrashFrequencyDetector.isSafeMode()) {
            Log.w(TAG, "safe-mode on — wake-lock bring-up skipped")
            return
        }
        holdCpu()
        Log.d(TAG, "created")
    }

    fun onStartCommand(intent: Intent?, startId: Int): Int {
        ProcessExitEvidence.record(
            host,
            "agent-service.command startId=$startId stop=${intent?.action == KeepAliveStatusLine.ACTION_STOP}",
        )
        // 安全模式：前台通知已占（onCreate），这里撤下并自停；崩溃
        // 分享对话框接管后续 UX。
        if (CrashFrequencyDetector.isSafeMode()) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    host.stopForeground(Service.STOP_FOREGROUND_REMOVE)
                } else {
                    @Suppress("DEPRECATION")
                    host.stopForeground(true)
                }
            } catch (t: Throwable) {
                Log.w(TAG, "safe-mode foreground teardown failed: ${t.message}")
            }
            host.stopSelf()
            return Service.START_NOT_STICKY
        }
        if (intent?.action == KeepAliveStatusLine.ACTION_STOP) {
            // 通知栏「停止」：光 stopSelf 会留下还在飞的生成循环（用户
            // 看到通知没了、工具还在后台跑）。先扇出全部取消回调。
            LiveSessionHub.cancelEveryStream()
            host.stopSelf()
            return Service.START_NOT_STICKY
        }

        val sessionCount = intent?.getIntExtra(EXTRA_SESSION_COUNT, 0) ?: 0
        val toolStatus = intent?.getStringExtra(EXTRA_TOOL_STATUS) ?: "Idle"
        enterForeground(statusLine.build(sessionCount, toolStatus))
        return Service.START_STICKY
    }

    /**
     * 用户从最近任务划掉。部分 OEM 会顺势杀掉本服务；只要还有流在
     * 跑就要重锚前台身份。纯"人在看聊天然后划掉"不再是留活理由——
     * 用户已明确逐出，先清在场再评估。
     */
    fun onTaskRemoved() {
        LiveSessionHub.dropPresence()
        val streaming = LiveSessionHub.streamingIds.value
        if (streaming.isEmpty()) {
            Log.d(TAG, "task removed with nothing streaming — stopping")
            host.stopSelf()
            return
        }
        Log.d(TAG, "task removed with ${streaming.size} streaming — re-anchoring")
        // 重发当前状态行，让系统重新认下"活的前台服务"，防 OEM 把
        // 我们降级成普通后台进程在 ~60s 内回收。
        enterForeground(statusLine.build(streaming.size, LiveSessionHub.toolStatus.value))
    }

    fun onDestroyed() {
        releaseCpu()
        Log.d(TAG, "destroyed")
    }

    // ── 前台身份与唤醒锁 ─────────────────────────────────────────────

    private fun enterForeground(notification: android.app.Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            host.startForeground(
                KeepAliveStatusLine.NOTIFICATION_ONGOING,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
            )
        } else {
            host.startForeground(KeepAliveStatusLine.NOTIFICATION_ONGOING, notification)
        }
    }

    private fun holdCpu() {
        if (wakeLock != null) return
        try {
            val power = host.getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "minis:inference").apply {
                setReferenceCounted(false)
                // 不设超时：释放点在 onDestroy（流式清零时服务自停），
                // 挂超时反而可能在长任务中途先松手。
                acquire()
            }
            Log.d(TAG, "wake lock held (PARTIAL)")
        } catch (e: Exception) {
            Log.w(TAG, "wake lock acquire failed: ${e.message}")
        }
    }

    private fun releaseCpu() {
        try {
            wakeLock?.let { lock ->
                if (lock.isHeld) {
                    lock.release()
                    Log.d(TAG, "wake lock released")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "wake lock release failed: ${e.message}")
        } finally {
            wakeLock = null
        }
    }

    private companion object {
        private const val TAG = "AgentKeepAlive"
        private const val EXTRA_SESSION_COUNT = "session_count"
        private const val EXTRA_TOOL_STATUS = "tool_status"
    }
}
