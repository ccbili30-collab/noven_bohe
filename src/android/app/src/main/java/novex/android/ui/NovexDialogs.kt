package novex.android.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.material3.Text
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

internal enum class NovexDecisionTone { PRIMARY, NEUTRAL, DESTRUCTIVE }

internal data class NovexDecisionAction(
    val label: String,
    @DrawableRes val icon: Int,
    val tone: NovexDecisionTone = NovexDecisionTone.NEUTRAL,
    val enabled: Boolean = true,
    val onClick: () -> Unit,
)

/** One compact decision surface for save, discard, delete and notice flows. */
@Composable
internal fun NovexDecisionDialog(
    title: String,
    message: String,
    actions: List<NovexDecisionAction>,
    onDismiss: () -> Unit,
) {
    NovexDialogSurface(
        onDismissRequest = onDismiss,
        title = { Text(title, color = NovexColors.Text, style = NovexType.SectionTitle) },
        content = { Text(message, color = NovexColors.SecondaryText, style = NovexType.Body) },
        actionLayout = NovexDialogActionLayout.VERTICAL,
        actionCount = actions.size,
        actions = {
            actions.forEachIndexed { index, action ->
                if (index > 0) NovexDivider(Modifier.padding(horizontal = 12.dp))
                NovexDecisionActionRow(action)
            }
        },
    )
}

@Composable
private fun NovexDecisionActionRow(action: NovexDecisionAction) {
    val tint = when (action.tone) {
        NovexDecisionTone.PRIMARY -> NovexColors.Primary
        NovexDecisionTone.NEUTRAL -> NovexColors.Text
        NovexDecisionTone.DESTRUCTIVE -> NovexColors.Danger
    }
    val tile = when (action.tone) {
        NovexDecisionTone.PRIMARY -> NovexColors.PrimarySoft
        NovexDecisionTone.NEUTRAL -> NovexColors.SurfaceMuted
        NovexDecisionTone.DESTRUCTIVE -> NovexColors.Danger.copy(alpha = 0.12f)
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 54.dp)
            .alpha(if (action.enabled) 1f else 0.42f)
            .clickable(enabled = action.enabled, onClick = action.onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(NovexDimensions.ActionIconTile)
                .clip(RoundedCornerShape(NovexDimensions.SmallRadius))
                .background(tile),
        ) {
            Icon(
                painter = painterResource(action.icon),
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(18.dp),
            )
        }
        Spacer(Modifier.width(12.dp))
        Text(action.label, color = tint, style = NovexType.Body)
    }
}

@Composable
internal fun NovexUnsavedChangesDialog(
    saving: Boolean,
    onSaveAndExit: () -> Unit,
    onDiscard: () -> Unit,
    onContinueEditing: () -> Unit,
) {
    NovexDecisionDialog(
        title = "保存更改？",
        message = "你修改的内容尚未保存。",
        onDismiss = { if (!saving) onContinueEditing() },
        actions = listOf(
            NovexDecisionAction(
                label = if (saving) "保存中" else "保存并退出",
                icon = com.openminis.app.R.drawable.ic_phosphor_check,
                tone = NovexDecisionTone.PRIMARY,
                enabled = !saving,
                onClick = onSaveAndExit,
            ),
            NovexDecisionAction(
                label = "不保存",
                icon = com.openminis.app.R.drawable.ic_phosphor_trash,
                tone = NovexDecisionTone.DESTRUCTIVE,
                enabled = !saving,
                onClick = onDiscard,
            ),
            NovexDecisionAction(
                label = "继续编辑",
                icon = com.openminis.app.R.drawable.ic_phosphor_arrow_left,
                enabled = !saving,
                onClick = onContinueEditing,
            ),
        ),
    )
}

@Composable
internal fun NovexDestructiveConfirmationDialog(
    title: String,
    message: String,
    confirming: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    confirmEnabled: Boolean = true,
) {
    NovexDecisionDialog(
        title = title,
        message = message,
        onDismiss = { if (!confirming) onDismiss() },
        actions = listOf(
            NovexDecisionAction(
                label = if (confirming) "删除中" else if (confirmEnabled) "删除" else "读取引用中",
                icon = com.openminis.app.R.drawable.ic_phosphor_trash,
                tone = NovexDecisionTone.DESTRUCTIVE,
                enabled = !confirming && confirmEnabled,
                onClick = onConfirm,
            ),
            NovexDecisionAction(
                label = "取消",
                icon = com.openminis.app.R.drawable.ic_phosphor_arrow_left,
                enabled = !confirming,
                onClick = onDismiss,
            ),
        ),
    )
}

@Composable
internal fun NovexNoticeDialog(
    title: String,
    message: String,
    onDismiss: () -> Unit,
) {
    NovexDecisionDialog(
        title = title,
        message = message,
        onDismiss = onDismiss,
        actions = listOf(
            NovexDecisionAction(
                label = "知道了",
                icon = com.openminis.app.R.drawable.ic_phosphor_check,
                tone = NovexDecisionTone.PRIMARY,
                onClick = onDismiss,
            ),
        ),
    )
}

/** Shared shell for richer pickers and decisions that need custom body content. */
@Composable
internal fun NovexContentDialog(
    title: String,
    onDismiss: () -> Unit,
    confirmButton: @Composable () -> Unit,
    dismissButton: @Composable (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(NovexDimensions.DialogRadius),
        containerColor = NovexColors.Surface,
        titleContentColor = NovexColors.Text,
        textContentColor = NovexColors.SecondaryText,
        title = { Text(title, style = NovexType.SectionTitle) },
        text = { androidx.compose.foundation.layout.Column(content = content) },
        confirmButton = confirmButton,
        dismissButton = { dismissButton?.invoke() },
    )
}

/**
 * [T-learning-banner] 学习任务的非阻断状态条：贴在会话顶部、不遮罩不抢焦点，
 * 用户可继续聊天。收起按钮只隐藏投影（任务继续后台跑），停止任务走显式
 * 暂停/取消——"关闭界面"与"停止任务"从此是两个动作。
 */
@Composable
internal fun NovexLearningStatusBanner(
    title: String,
    subtitle: String?,
    controls: Set<novex.core.NovexLearningControl>,
    onDetails: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onExtendBudget: () -> Unit,
    onCancel: () -> Unit,
    onHide: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(NovexDimensions.SectionRadius),
        color = NovexColors.Surface,
        tonalElevation = 2.dp,
        shadowElevation = 6.dp,
        border = androidx.compose.foundation.BorderStroke(1.dp, NovexColors.Divider),
    ) {
        Column(modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    painter = painterResource(com.openminis.app.R.drawable.ic_phosphor_brain),
                    contentDescription = null,
                    tint = NovexColors.Primary,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        title,
                        color = NovexColors.Text,
                        style = NovexType.ItemTitle,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    )
                    if (!subtitle.isNullOrBlank()) {
                        Text(
                            subtitle,
                            color = NovexColors.SecondaryText,
                            style = NovexType.Metadata,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        )
                    }
                }
                // 收起=只藏状态条，任务照跑；终态用「知道了」清投影。
                Icon(
                    painter = painterResource(com.openminis.app.R.drawable.ic_phosphor_arrow_up),
                    contentDescription = "收起状态条",
                    tint = NovexColors.TertiaryText,
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(NovexDimensions.SmallRadius))
                        .clickable(onClick = onHide)
                        .padding(8.dp),
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    "详情",
                    color = NovexColors.Primary,
                    style = NovexType.Metadata,
                    modifier = Modifier
                        .clip(RoundedCornerShape(NovexDimensions.SmallRadius))
                        .clickable(onClick = onDetails)
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                )
                Spacer(Modifier.weight(1f))
                if (novex.core.NovexLearningControl.PAUSE in controls) {
                    BannerAction("暂停", onPause)
                }
                if (novex.core.NovexLearningControl.RESUME in controls) {
                    BannerAction("继续", onResume)
                }
                if (novex.core.NovexLearningControl.EXTEND_BUDGET in controls) {
                    BannerAction("增加预算", onExtendBudget)
                }
                if (novex.core.NovexLearningControl.CANCEL in controls) {
                    BannerAction("取消", onCancel, destructive = true)
                }
                if (novex.core.NovexLearningControl.DISMISS in controls) {
                    BannerAction("知道了", onDismiss)
                }
            }
        }
    }
}

@Composable
private fun BannerAction(label: String, onClick: () -> Unit, destructive: Boolean = false) {
    Text(
        label,
        color = if (destructive) NovexColors.Danger else NovexColors.Text,
        style = NovexType.Metadata,
        modifier = Modifier
            .clip(RoundedCornerShape(NovexDimensions.SmallRadius))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
    )
}
