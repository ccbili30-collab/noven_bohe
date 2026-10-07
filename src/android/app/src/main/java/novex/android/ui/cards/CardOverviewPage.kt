package novex.android.ui.cards

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import novex.android.ui.AlertDialog
import novex.android.ui.Button
import novex.android.ui.OutlinedButton
import novex.android.ui.TextButton
import novex.android.ui.NovexColors
import novex.android.ui.NovexType
import com.openminis.app.ui.noven.NovenColors
import novex.content.ContentDocument
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * [T008] 设定总览管理页：阅读优先。细信息条 + 带来源角标的总览正文 +
 * 底部操作条（手动编辑 / AI 提纯）；提纯稿先进 diff 待审，采纳才落卡。
 */
@Composable internal fun CardOverviewPage(card:ContentDocument,model:CardSessionModel) {
    val state=model.state
    var text by remember(card){mutableStateOf<String?>(null)}
    var loaded by remember(card){mutableStateOf(false)}
    LaunchedEffect(card){text=model.overviewText(card);loaded=true}
    if(state.purifyDraft!=null)OverviewDiffDialog(model,oldText=text)
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal=16.dp)) {
            Spacer(Modifier.height(8.dp))
            OverviewInfoBar(state.overviewMeta,text)
            Spacer(Modifier.height(14.dp))
            if(state.purifying)LinearProgressIndicator(Modifier.fillMaxWidth())
            when {
                !loaded->Text("载入中",style=NovexType.Metadata,color=NovexColors.SecondaryText)
                text==null->OverviewEmptyGuide(model)
                else->OverviewBody(text.orEmpty())
            }
            Spacer(Modifier.height(120.dp))
        }
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(start=16.dp,end=16.dp,bottom=16.dp)) {
            Text("总览是每轮对话默认注入的背景；详细模块可在携带设置中按需附加。",
                style=NovexType.Metadata,color=NovexColors.TertiaryText,
                modifier=Modifier.align(Alignment.CenterHorizontally).padding(bottom=8.dp))
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                OutlinedButton(enabled=!state.busy && !state.purifying,onClick=model::overview,
                    modifier=Modifier.weight(1f),shape=RoundedCornerShape(14.dp),
                    border=androidx.compose.foundation.BorderStroke(1.dp,NovenColors.Mint),
                    colors=androidx.compose.material3.ButtonDefaults.outlinedButtonColors(contentColor=NovenColors.Mint)) {Text("手动编辑")}
                Button(enabled=!state.busy && !state.purifying,onClick=model::purifyOverview,
                    modifier=Modifier.weight(1f),shape=RoundedCornerShape(14.dp),
                    colors=androidx.compose.material3.ButtonDefaults.buttonColors(containerColor=NovenColors.Mint,contentColor=NovenColors.OnMint)) {Text(if(text==null)"AI 提纯" else "AI 重新提纯")}
            }
        }
    }
}

@Composable private fun OverviewInfoBar(meta:OverviewMeta?,text:String?) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
        .background(NovenColors.Mint.copy(alpha=0.10f))
        .padding(horizontal=12.dp,vertical=10.dp),
        verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
        val purified=meta?.purifiedAtMs?.let {SimpleDateFormat("M月d日 HH:mm",Locale.CHINA).format(Date(it))}
        val covered=meta?.coveredModules ?: 0
        val tokens=meta?.approxTokens?.takeIf {it>0} ?: (text?.length?.times(3)?.div(4)) ?: 0
        Text(buildString {
            if(purified!=null)append("上次提纯 ").append(purified).append(" · ")
            append("覆盖 ").append(covered).append(" 个模块 · 约 ").append(tokens).append(" tokens")
        },style=NovexType.Metadata,color=NovexColors.SecondaryText)
    }
}

@Composable private fun OverviewEmptyGuide(model:CardSessionModel) {
    Column(Modifier.fillMaxWidth().padding(vertical=24.dp,horizontal=8.dp)) {
        Text("还没有总览",style=NovexType.PageTitle,color=NovexColors.Text,fontWeight=FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        Text("让 AI 读完全部模块，提纯成一份精简背景；或手动写一份。总览会默认注入每轮对话。",
            style=NovexType.Body,color=NovexColors.SecondaryText)
    }
}

@Composable private fun OverviewBody(text:String) {
    val paragraphs=remember(text){overviewParagraphs(text)}
    Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(14.dp)) {
        paragraphs.forEach {(paragraph,source)->
            Column {
                com.openminis.app.ui.chat.MarkdownBlock(rawText=paragraph,isStreaming=false)
                if(source!=null)Row(Modifier.padding(top=4.dp),verticalAlignment=Alignment.CenterVertically) {
                    Box(Modifier.clip(RoundedCornerShape(6.dp)).background(NovexColors.SurfaceMuted).padding(horizontal=8.dp,vertical=2.dp)) {
                        Text("来源：$source",style=NovexType.Metadata,color=NovexColors.SecondaryText)
                    }
                }
            }
        }
    }
}

/** 提纯稿待审：diff 对话框——覆盖前必须先看对比，防止静默覆盖手改内容。 */
@Composable private fun OverviewDiffDialog(model:CardSessionModel,oldText:String?) {
    val draft=requireNotNull(model.state.purifyDraft)
    AlertDialog(onDismissRequest=model::dismissPurifyDraft,
        title={Text("AI 提纯完成，采纳前请过目")},
        text={Column(Modifier.verticalScroll(rememberScrollState()).heightIn(max=420.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
            Text("新稿（${draft.length} 字）",style=NovexType.ItemTitle,color=NovexColors.Text)
            Text(draft,style=NovexType.Body,color=NovexColors.SecondaryText)
            if(oldText!=null) {
                Spacer(Modifier.height(4.dp))
                Text("旧稿（${oldText.length} 字，采纳后将被覆盖）",style=NovexType.ItemTitle,color=NovexColors.Text)
                Text(oldText.take(600),style=NovexType.Body,color=NovexColors.TertiaryText)
            } else Text("当前没有总览，采纳后新建。",style=NovexType.Metadata,color=NovexColors.TertiaryText)
        }},
        confirmButton={Button(enabled=!model.state.busy,onClick={model.adoptPurifiedOverview()}){Text("采纳")}},
        dismissButton={TextButton(onClick=model::dismissPurifyDraft){Text("拒绝")}})
}
