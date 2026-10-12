package com.openminis.app.ui.chat

import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import androidx.core.content.ContextCompat
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.verticalDrag
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import novex.core.ConversationControlBehavior
import novex.core.NovexLearningControl
import novex.core.NovexLearningControlPolicy
import novex.core.NovexLearningTaskStatus
import novex.core.PlaythroughValue
import novex.android.ui.NovexNoticeDialog
import novex.android.ui.NovexDecisionAction
import novex.android.ui.NovexDecisionDialog
import novex.android.ui.NovexDecisionTone
import novex.android.ui.NovexSelectionAction
import novex.android.ui.NovexSelectionSheet
import androidx.compose.runtime.withFrameNanos
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import novex.android.ui.DropdownMenuItem
import com.openminis.app.BuildConfig
import com.openminis.app.R
import com.openminis.app.data.FileMentionIndex
import com.openminis.app.logging.AppLogger
import com.openminis.app.ui.components.MinisAlertDialog
import com.openminis.app.ui.components.MinisMenu
import com.openminis.app.ui.components.MinisMenuDivider
import novex.android.ui.NovexActionMenu
import novex.android.ui.NovexMenuAction
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Surface
import androidx.compose.material3.ExperimentalMaterial3Api
import novex.android.ui.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.TextFieldDefaults
import novex.android.ui.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import novex.android.ui.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.produceState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.paint
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.compose.rememberAsyncImagePainter
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.ast.getTextInNode
import novex.android.data.model.LLMModel
import novex.android.data.model.ModelEntry
import novex.android.data.model.ModelGroup
import novex.android.data.model.ProviderConfig
import novex.android.data.model.ProviderType
import novex.android.data.model.RoutingStrategy
import novex.android.data.model.ThinkingLevel
import com.openminis.app.data.character.effectiveAssistantAvatarPath
import com.openminis.app.data.character.effectiveAssistantName
import com.openminis.app.data.character.usesRolePresentation
import com.openminis.app.data.repository.ChatRepository
import com.openminis.app.data.repository.ProviderRepository
import com.openminis.app.ui.theme.ChatColors
import com.openminis.app.ui.components.MinisTextButton

/**
 * 转录区右上/右下的浮动层：工具状态条哨兵（含 T261 详情 Sheet）、
 * 回上一用户回合 FAB、回到底部 FAB。宿主在转录 Box 里以 BottomEnd 对齐
 * 调用，淡化态由 [chromeFadeAlpha] 同步透明（alpha 与阴影随其开关）。
 */
@Composable
internal fun androidx.compose.foundation.layout.BoxScope.ChatTranscriptOverlay(
    viewModel: ChatViewModel,
    messages: List<ChatMessage>,
    transcriptViewportReady: Boolean,
    isNearBottom: androidx.compose.runtime.State<Boolean>,
    contentOverflows: androidx.compose.runtime.State<Boolean>,
    chromeFadeAlpha: Float,
    isStreaming: Boolean,
    transcriptFollowState: TranscriptFollowState,
    onFollowStateChange: (TranscriptFollowState) -> Unit,
    onJumpedUserCleared: () -> Unit,
    scrollToPreviousUserTurn: suspend () -> Unit,
    scrollToNextUserTurn: suspend () -> Unit,
    scrollToLatestOnce: suspend (TranscriptViewportMove) -> Unit,
    scrollToTopOnce: suspend () -> Unit,
) {
    val coroutineScope = androidx.compose.runtime.rememberCoroutineScope()
    val context = LocalContext.current
                // Floating tool status bar — shows only actual tool calls (not text/thinking/info).
                // Matches iOS: filter on toolStatus != nil (text blocks have toolStatus = null).
                //
                // T-streaming-side-channel-tool-blocks: derive lastToolBlocks
                // from a state that combines messages + streamingById INSIDE
                // a LaunchedEffect (not via a top-level collectAsState read),
                // so streaming-tick churn stays off the ChatScreen invalidation
                // list. Without including streamingById, a tool pill clicked
                // mid-turn is missing from lastToolBlocks → ToolDetailSheet
                // never opens (and its sentinel LaunchedEffect immediately
                // closes the detail state because the id "doesn't exist").
                var lastToolBlocks by remember { mutableStateOf<List<AssistantBlock>>(emptyList()) }
                LaunchedEffect(messages) {
                    kotlinx.coroutines.flow.combine(
                        kotlinx.coroutines.flow.flowOf(messages),
                        viewModel.streamingById,
                    ) { msgs, stream ->
                        val merged = if (stream.isEmpty()) msgs else mergeStreamingOverlay(msgs, stream)
                        merged.filter { it.role == "assistant" }
                            .flatMap { it.toolBlocks }
                            .filter { it.toolStatus != null && it.kind != "thinking" && it.kind != "info" }
                    }.collect { lastToolBlocks = it }
                }
                // [P3.3 裁军] SpeechPlayerCapsule（语音播报浮动胶囊）随语音
                // 全家退役；浮动按钮堆叠的原避让参数一并删除。
                val upFabVisible = transcriptViewportReady &&
                    messages.isNotEmpty() && !isNearBottom.value
                val downFabVisible = transcriptViewportReady &&
                    !isNearBottom.value && contentOverflows.value && messages.isNotEmpty()

                // T261: tool-detail sheet hoisted out of LazyColumn item
                // scope. Visibility driven by ViewModel state so streaming /
                // pill-disposal / new-tool emissions can't snap it shut.
                // existence guard auto-closes the sheet when the underlying
                // block disappears (T258 retry-preserve removes in-flight
                // tools, clearChat, etc.). Reuses lastToolBlocks (already
                // computed above) so we don't traverse messages twice.
                val selectedToolDetailId by viewModel.selectedToolDetailId.collectAsState()
                LaunchedEffect(selectedToolDetailId, lastToolBlocks) {
                    val id = selectedToolDetailId ?: return@LaunchedEffect
                    if (lastToolBlocks.none { it.id == id }) viewModel.closeToolDetail()
                }
                val selectedToolBlock = selectedToolDetailId?.let { id ->
                    lastToolBlocks.firstOrNull { it.id == id }
                }
                if (selectedToolBlock != null) {
                    val initialIdx = lastToolBlocks
                        .indexOfFirst { it.id == selectedToolBlock.id }
                        .coerceAtLeast(0)
                    ToolDetailSheet(
                        toolBlocks = lastToolBlocks,
                        initialIndex = initialIdx,
                        onDismiss = { viewModel.closeToolDetail() },
                        // [P3.3 裁军] 工具详情里的链接改外跳系统浏览器（原
                        // openBrowserSheetForUrl 内置浏览器 Sheet 退役）。
                        onOpenUrl = { url ->
                            viewModel.closeToolDetail()
                            com.openminis.app.ui.components.openExternalUrl(context, url)
                        },
                    )
                }

                // Scroll-to-bottom FAB (iOS: circle chevron.down, bottom-right)
                // T138 phase 2 v3: show on user-scroll intent, not transient
                // layout state. Otherwise the FAB flickers whenever multi-tool
                // emissions briefly bump the bottom item off-screen during
                // re-anchoring.
                //
                // T170: gate also on `contentOverflows` so short sessions
                // (one Q+A on a tall screen) never flash the FAB if an IME
                // animation produces a synthetic drag-stop. iOS gets this
                // for free via `maxOffset > 0`; Compose needs the explicit
                // check.
                // [T-fab-stretch-jump] 2026-10-11 决策：浮动钮双层手势——
                // 单击 = 逐回合步进（上钮上一发言/下钮下一发言，可快速连点）；
                // 稍按住再向上/下拖动 = 拉条，出现方向指示，松手跳到顶/底。
                // 两钮都支持拉条；拉条是独立手势族，不与单击抢。
                var upStretchDy by androidx.compose.runtime.remember { androidx.compose.runtime.mutableFloatStateOf(0f) }
                var downStretchDy by androidx.compose.runtime.remember { androidx.compose.runtime.mutableFloatStateOf(0f) }

                if (transcriptViewportReady && messages.isNotEmpty() && !isNearBottom.value && chromeFadeAlpha > 0.01f) {
                    val upBaseBottom = 8.dp
                    novex.android.ui.NovexFilledIconButton(
                        onClick = {
                            coroutineScope.launch { scrollToPreviousUserTurn() }
                        },
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .graphicsLayer { alpha = chromeFadeAlpha }
                            .padding(end = 12.dp, bottom = upBaseBottom + 46.dp)
                            .shadow(if (chromeFadeAlpha >= 0.99f) 4.dp else 0.dp, CircleShape)
                            .size(36.dp)
                            .stretchDragGesture(
                                onDelta = { upStretchDy = it },
                                onRelease = { dir ->
                                    if (dir < 0) coroutineScope.launch { scrollToTopOnce() }
                                    else if (dir > 0) {
                                        onJumpedUserCleared()
                                        coroutineScope.launch { scrollToLatestOnce(TranscriptViewportMove.UserRequestedLatest) }
                                    }
                                },
                            ),
                        colors = androidx.compose.material3.IconButtonDefaults.filledIconButtonColors(
                            containerColor = ChatColors.inputBg,
                            contentColor = ChatColors.primaryText,
                        ),
                    ) {
                        Icon(
                            // 单步步进的符号——不许画成"回端点"（旧
                            // VerticalAlignTop 画的是到顶，干的是上一步）。
                            imageVector = novex.android.ui.NovexIcons.KeyboardArrowUp,
                            contentDescription = "上一条发言",
                            modifier = Modifier.size(20.dp),
                        )
                    }
                    // 拉条方向指示：拖出幅度时出现，位置跟着拖动方向贴
                    // 按钮外侧（上拉露上方、下拉露下方），方向箭头跟手。
                    if (kotlin.math.abs(upStretchDy) > STRETCH_TRIGGER_PX) {
                        StretchDirectionIndicator(
                            direction = if (upStretchDy < 0) -1 else 1,
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(
                                    end = 12.dp,
                                    bottom = if (upStretchDy < 0) upBaseBottom + 46.dp + 40.dp
                                        else (upBaseBottom + 46.dp - 26.dp).coerceAtLeast(0.dp),
                                ),
                        )
                    }
                }

                if (transcriptViewportReady && !isNearBottom.value &&
                    contentOverflows.value && messages.isNotEmpty() && chromeFadeAlpha > 0.01f
                ) {
                    val fabBottomPadding = 8.dp
                    novex.android.ui.NovexFilledIconButton(
                        onClick = {
                            coroutineScope.launch { scrollToNextUserTurn() }
                        },
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .graphicsLayer { alpha = chromeFadeAlpha }
                            .padding(end = 12.dp, bottom = fabBottomPadding)
                            .shadow(if (chromeFadeAlpha >= 0.99f) 4.dp else 0.dp, CircleShape)
                            .size(36.dp)
                            .stretchDragGesture(
                                onDelta = { downStretchDy = it },
                                onRelease = { dir ->
                                    if (dir > 0) {
                                        onJumpedUserCleared()
                                        coroutineScope.launch { scrollToLatestOnce(TranscriptViewportMove.UserRequestedLatest) }
                                    } else if (dir < 0) coroutineScope.launch { scrollToTopOnce() }
                                },
                            ),
                        colors = androidx.compose.material3.IconButtonDefaults.filledIconButtonColors(
                            containerColor = ChatColors.inputBg,
                            contentColor = ChatColors.primaryText,
                        ),
                    ) {
                        Icon(
                            imageVector = novex.android.ui.NovexIcons.KeyboardArrowDown,
                            contentDescription = "下一条发言",
                            modifier = Modifier.size(20.dp),
                        )
                    }
                    if (kotlin.math.abs(downStretchDy) > STRETCH_TRIGGER_PX) {
                        StretchDirectionIndicator(
                            direction = if (downStretchDy < 0) -1 else 1,
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(
                                    end = 12.dp,
                                    bottom = if (downStretchDy < 0) fabBottomPadding + 40.dp
                                        else (fabBottomPadding - 26.dp).coerceAtLeast(0.dp),
                                ),
                        )
                    }
                }

                // T51 / T185: the "Move to…" capsule was previously rendered
                // here, on top of the message list. After the user actually
                // sends the share-injected turn, the capsule was overlapping
                // the user-message bubble area and obscuring attachment chips.
                // Moved to the composer's top-right corner — see the Box
                // overlay around the input Column below, mirroring iOS
                // AIChatView.swift:1817 (.overlay(alignment: .topTrailing)).

                // T-chat-title-pill: sticky session title overlay. Sits
}

// ── 拉条跳端 ──────────────────────────────────────────────────────────────

private const val STRETCH_HOLD_MS = 280L
private const val STRETCH_TRIGGER_PX = 48f

/**
 * 长按拖动拉条手势：按住 ~280ms 进入拉伸态，之后累计纵向位移；
 * 松手时方向（上拉=负/下拉=正）交给调用方跳端。单击走按钮自己的
 * onClick，不在此手势竞争——按住超时前抬指或移动都原样退出。
 */
private fun Modifier.stretchDragGesture(
    onDelta: (Float) -> Unit,
    onRelease: (direction: Int) -> Unit,
): Modifier = this.pointerInput(Unit) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        // 等"稍微按住"：官方长按手势到期返回 = 进拉伸态；提前抬指、被
        // 其它手势消费、拖走出界都直接让位（单击/滚动照旧）。awaitPointerEvent
        // 是受限挂起，不能嵌进 withTimeoutOrNull——长按等待必须用它。
        val held = awaitLongPressOrCancellation(down.id, STRETCH_HOLD_MS)
        if (held == null || !held.pressed || held.isConsumed ||
            kotlin.math.abs(held.position.y - down.position.y) > STRETCH_TRIGGER_PX
        ) return@awaitEachGesture
        // 拉伸态：消耗位移，实时回报累计量（负=上拉，正=下拉）。
        var dy = 0f
        onDelta(0f)
        while (true) {
            val ev = awaitPointerEvent()
            val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
            if (!ch.pressed) break
            val d = ch.positionChange().y
            if (d != 0f) {
                dy += d
                ch.consume()
                onDelta(dy)
            }
        }
        onDelta(0f)
        onRelease(if (dy <= -STRETCH_TRIGGER_PX) -1 else if (dy >= STRETCH_TRIGGER_PX) 1 else 0)
    }
}

/** 拉条方向指示：跟手出现的小箭头（上拉=向上箭头，下拉=向下）。 */
@Composable
private fun StretchDirectionIndicator(direction: Int, modifier: Modifier = Modifier) {
    androidx.compose.foundation.layout.Box(
        modifier = modifier
            .size(width = 36.dp, height = 22.dp)
            .background(ChatColors.inputBg, RoundedCornerShape(11.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = if (direction < 0) novex.android.ui.NovexIcons.KeyboardArrowUp
                else novex.android.ui.NovexIcons.KeyboardArrowDown,
            contentDescription = null,
            tint = ChatColors.primaryText,
            modifier = Modifier.size(16.dp),
        )
    }
}
