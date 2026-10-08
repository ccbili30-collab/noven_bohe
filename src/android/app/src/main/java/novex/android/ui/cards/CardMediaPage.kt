package novex.android.ui.cards

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import novex.android.ui.Button
import novex.android.ui.OutlinedButton
import novex.android.ui.TextButton
import novex.android.ui.NovexColors
import novex.android.ui.NovexType
import com.openminis.app.ui.noven.NovenColors
import novex.content.CardKind
import novex.content.ContentDocument

/**
 * [T008-P3] 媒体槽管理页（设计稿 14）：头像/封面现状 + 换图/清除；
 * 页内配图走模块编辑（提示行说明）。媒体永不进 AI 上下文。
 */
@Composable internal fun CardMediaPage(card:ContentDocument,model:CardSessionModel) {
    val state=model.state
    // 换图只落草稿：显示层优先读草稿，配保存/放弃条，与「清除」即时提交并存。
    val shown=state.shownDraft?:state.shownSaved?:card
    val hasCover=shown.appearance.coverResourceId!=null
    val hasAvatar=shown.appearance.avatarResourceId!=null
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal=16.dp)) {
        Spacer(Modifier.height(8.dp))
        var confirmDiscard by remember {mutableStateOf(false)}
        if(confirmDiscard)novex.android.ui.AlertDialog(onDismissRequest={if(!state.busy)confirmDiscard=false},
            title={Text("放弃更改？")},
            text={Text("将放弃本次全部未保存的草稿更改（含其他编辑）。")},
            confirmButton={TextButton(enabled=!state.busy,onClick={confirmDiscard=false;model.discardDraft {model.clearMediaAppearanceDraft()}}){Text("放弃",color=NovenColors.Mint)}},
            dismissButton={TextButton(enabled=!state.busy,onClick={confirmDiscard=false}){Text("取消")}})
        if(state.draft!=null && state.mediaAppearanceDraft)Row(Modifier.fillMaxWidth().padding(vertical=6.dp),horizontalArrangement=Arrangement.spacedBy(12.dp),verticalAlignment=Alignment.CenterVertically) {
            Text("有未保存的更改（保存将应用整卡草稿）",style=NovexType.Metadata,color=NovexColors.SecondaryText,modifier=Modifier.weight(1f))
            TextButton(onClick={confirmDiscard=true},enabled=!state.busy){Text("放弃",color=NovexColors.SecondaryText)}
            TextButton(onClick={model.save {model.clearMediaAppearanceDraft()}},enabled=!state.busy){Text("保存",color=NovenColors.Mint)}
        }
        MediaSlotRow(
            title=if(card.kind==CardKind.WORLD)"封面 · 世界卡门面" else "全屏背景 · 卡片主页头图",
            status=if(hasCover)"已设置" else "未设置",
            enabled=!state.busy,
            onChange={model.mediaPickAppearance(true)},
            onClear=if(hasCover){ {model.mediaClearAppearance(true)} } else null,
        )
        MediaSlotRow(
            title=if(card.kind==CardKind.WORLD)"徽记 · 世界标识图" else "头像 · 对话与列表显示",
            status=if(hasAvatar)"已设置" else "未设置",
            enabled=!state.busy,
            onChange={model.mediaPickAppearance(false)},
            onClear=if(hasAvatar){ {model.mediaClearAppearance(false)} } else null,
        )
        Spacer(Modifier.height(12.dp))
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(NovexColors.Surface).padding(horizontal=12.dp,vertical=10.dp)) {
            Text("模块插图",style=NovexType.ItemTitle,color=NovenColors.Text)
            Text("在模块正文中用「插入图片」添加；媒体随卡包导出携带，不会进入 AI 上下文。",
                style=NovexType.Metadata,color=NovexColors.SecondaryText)
        }
        Spacer(Modifier.height(80.dp))
    }
}

@Composable private fun MediaSlotRow(title:String,status:String,enabled:Boolean,onChange:()->Unit,onClear:(()->Unit)?) {
    Column(Modifier.fillMaxWidth().padding(vertical=6.dp)) {
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title,style=NovexType.ItemTitle,color=NovenColors.Text)
                Text(status,style=NovexType.Metadata,color=if(status=="已设置")NovenColors.Mint else NovexColors.TertiaryText)
            }
        }
        Row(Modifier.fillMaxWidth().padding(top=6.dp),horizontalArrangement=Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick=onChange,enabled=enabled,
                shape=RoundedCornerShape(12.dp),
                border=androidx.compose.foundation.BorderStroke(1.dp,NovenColors.Mint),
                colors=androidx.compose.material3.ButtonDefaults.outlinedButtonColors(contentColor=NovenColors.Mint)) {Text("更换")}
            if(onClear!=null)TextButton(onClick=onClear,enabled=enabled) {Text("清除",color=NovenColors.Mint)}
        }
    }
}
