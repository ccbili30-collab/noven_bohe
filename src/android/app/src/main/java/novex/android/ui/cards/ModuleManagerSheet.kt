package novex.android.ui.cards

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import novex.android.ui.NovexColors
import novex.android.ui.NovexIcons
import novex.android.ui.NovexType
import com.openminis.app.ui.noven.NovenColors
import novex.content.ContentDocument
import novex.content.ReadingLayout

/**
 * [T008-P3] 模块管理抽屉（设计稿 15）：显隐（眼睛位=novex-hidden 标签）、
 * 根级上下移、连续阅读开关、完整编辑与添加入口。操作即时提交，不留草稿。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun ModuleManagerSheet(card:ContentDocument,model:CardSessionModel,onAddModule:()->Unit,onOpenEditor:()->Unit,onDismiss:()->Unit) {
    val state=model.state
    val rootModules=remember(card){card.modules.filterNot {it.id==card.introductionModule()?.id || it.id==card.overviewModule()?.id}}
    val continuous=remember(model.readingLayoutVersion){ReadingViewPrefs.current()==ReadingLayout.CONTINUOUS}
    ModalBottomSheet(onDismissRequest={if(!state.busy)onDismiss()}) {
        Column(Modifier.fillMaxWidth().padding(horizontal=16.dp).padding(bottom=28.dp).verticalScroll(rememberScrollState())) {
            Text("模块管理",style=NovexType.PageTitle,color=NovenColors.Text,modifier=Modifier.padding(bottom=12.dp))
            // 连续阅读开关：全局偏好，即时生效
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("连续阅读模式",style=NovexType.ItemTitle,color=NovenColors.Text)
                    Text("开启后所有模块连成一页，上下滑动阅读",style=NovexType.Metadata,color=NovexColors.SecondaryText)
                }
                Switch(checked=continuous,onCheckedChange={if(!state.busy)model.setReadingLayout(
                    if(continuous)ReadingLayout.PAGED else ReadingLayout.CONTINUOUS)},enabled=!state.busy)
            }
            Spacer(Modifier.height(10.dp))
            if(rootModules.isEmpty())Text("这张卡还没有模块。",style=NovexType.Metadata,color=NovexColors.TertiaryText,modifier=Modifier.padding(vertical=12.dp))
            rootModules.forEachIndexed {index,module->
                val hidden=module.tags.contains("novex-hidden")
                Row(Modifier.fillMaxWidth().padding(vertical=2.dp),verticalAlignment=Alignment.CenterVertically) {
                    Icon(if(hidden)NovexIcons.VisibilityOff else NovexIcons.Visibility,
                        if(hidden)"显示模块" else "从阅读器隐藏",
                        Modifier.clickable(enabled=!state.busy){onDismiss();model.setModuleHiddenFromReading(module.id,!hidden)}
                            .padding(horizontal=10.dp,vertical=12.dp).size(20.dp),
                        tint=if(hidden)NovexColors.TertiaryText else NovenColors.Mint)
                    Text(module.name.ifBlank {"未命名模块"}+(if(hidden)"（已隐藏）" else ""),
                        style=NovexType.ItemTitle,color=if(hidden)NovexColors.TertiaryText else NovenColors.Text,
                        modifier=Modifier.weight(1f))
                    Icon(NovexIcons.ArrowUpward,"上移",Modifier.clickable(enabled=!state.busy){onDismiss();model.moveModuleFromReading(module.id,true)}
                        .padding(horizontal=10.dp,vertical=12.dp).size(18.dp)
                        .semantics{contentDescription="上移${module.name}"},tint=if(index==0)NovexColors.SurfaceMuted else NovexColors.SecondaryText)
                    Icon(NovexIcons.ArrowDownward,"下移",Modifier.clickable(enabled=!state.busy){onDismiss();model.moveModuleFromReading(module.id,false)}
                        .padding(horizontal=10.dp,vertical=12.dp).size(18.dp),tint=if(index==rootModules.lastIndex)NovexColors.SurfaceMuted else NovexColors.SecondaryText)
                }
            }
            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                novex.android.ui.OutlinedButton(onClick={onDismiss();onAddModule()},enabled=!state.busy,
                    modifier=Modifier.weight(1f),shape=androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
                    border=androidx.compose.foundation.BorderStroke(1.dp,NovenColors.Mint),
                    colors=androidx.compose.material3.ButtonDefaults.outlinedButtonColors(contentColor=NovenColors.Mint)) {Text("添加模块")}
                novex.android.ui.Button(onClick={onDismiss();onOpenEditor()},enabled=!state.busy,
                    modifier=Modifier.weight(1f),shape=androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
                    colors=androidx.compose.material3.ButtonDefaults.buttonColors(containerColor=NovenColors.Mint,contentColor=NovenColors.OnMint)) {Text("完整编辑")}
            }
        }
    }
}
