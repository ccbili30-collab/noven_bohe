package novex.android.ui.cards

import novex.android.ui.NovexPageTopBar
import novex.android.ui.NovexColors
import novex.android.ui.NovexIcons

import novex.android.ui.Button
import novex.android.ui.TextButton
import novex.android.ui.Scaffold

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import novex.content.ContentTargets
import novex.content.CardKind
import novex.content.flattenModules
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * 导入预览两阶段：解析秒开（busy 期间只显示识别中，不锁出口）；
 * 素材收割在预览下方内联跑，可取消、可离开转后台、可随时确认。
 * 「取消收割」保留远程引用继续导入；「丢弃导入」才是放弃这张卡。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable fun ImportPage(files:FileTransferModel,reader:CardSessionModel,onClose:()->Unit) {
    val state=files.state
    var target by rememberSaveable(state.draft?.content?.id) {mutableStateOf<String?>(null)}
    val back:()->Unit={if(target!=null)target=null else {files.close();onClose()}}
    BackHandler {back()}
    Scaffold(containerColor=NovexColors.Canvas,topBar={NovexPageTopBar(title="导入预览",onBack=back)},
        bottomBar={Row(Modifier.navigationBarsPadding().padding(16.dp),horizontalArrangement=Arrangement.spacedBy(12.dp)) {
            TextButton(enabled=!state.busy&&state.draft!=null,onClick={files.discard()}){Text("丢弃导入")}
            Button(enabled=!state.busy && state.draft!=null,onClick={files.confirm()},modifier=Modifier.weight(1f),
                shape=RoundedCornerShape(14.dp),
                colors=ButtonDefaults.buttonColors(
                    containerColor=com.openminis.app.ui.noven.NovenColors.Mint,
                    contentColor=com.openminis.app.ui.noven.NovenColors.OnMint)){Text("确认导入")}
        }}){padding->Column(Modifier.fillMaxSize().padding(padding)) {
        if(state.busy&&state.draft==null)Row(Modifier.fillMaxWidth().padding(20.dp),
            verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
            CircularProgressIndicator(Modifier.size(18.dp),strokeWidth=2.dp)
            Text("正在识别文件…",style=MaterialTheme.typography.bodyMedium,color=NovexColors.SecondaryText)
        }
        state.error?.let {Text(it,color=MaterialTheme.colorScheme.error,modifier=Modifier.padding(20.dp))}
        state.draft?.let {draft->
            val shown=ContentTargets.find(draft.content,target?:draft.content.id)
            CardReading(shown,reader,onCharacter={target=it},byline={
                // 导入态横幅：真实卡名+人话成分，告诉用户"这是什么、会去哪"。
                val moduleCount=draft.content.modules.flattenModules().size
                val roleCount=draft.content.internalCharacters.size
                val pageCount=draft.content.modules.flattenModules().count{"novex-frontend" in it.tags}
                val summary=buildString {
                    append(if(draft.content.kind==CardKind.WORLD)"世界" else "角色")
                    if(moduleCount>0)append(" · $moduleCount 个条目")
                    if(roleCount>0)append(" · $roleCount 个角色")
                    if(pageCount>0)append(" · $pageCount 个互动页面")
                }
                Column(Modifier.fillMaxWidth()
                    .background(NovexColors.SurfaceMuted,RoundedCornerShape(10.dp))
                    .padding(horizontal=12.dp,vertical=10.dp),
                    verticalArrangement=Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        Icon(NovexIcons.Info,null,Modifier.size(16.dp),tint=NovexColors.SecondaryText)
                        Text("导入预览 · 确认后进入卡片库，点「扮演」开始",style=MaterialTheme.typography.labelLarge,color=NovexColors.Text)
                    }
                    Text(summary,style=MaterialTheme.typography.labelSmall,color=NovexColors.SecondaryText,
                        modifier=Modifier.padding(start=24.dp))
                }
            })
            // 收割进度：卡片引用的远程素材正在抓回本地；可取消、离开转后台。
            state.harvest?.let {h->
                Row(Modifier.fillMaxWidth().padding(horizontal=20.dp,vertical=8.dp),
                    verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                    if(h.running)CircularProgressIndicator(Modifier.size(16.dp),strokeWidth=2.dp)
                    Column(Modifier.weight(1f)) {
                        Text(if(h.running)"正在收集素材 ${h.done+h.failed}/${h.total}（离线也能玩）"
                            else "素材 ${h.done}/${h.total} 已存到本地"+
                                (if(h.failed>0)"，${h.failed} 项源站已失效"else"")+
                                (if(h.deferred>0)"，${h.deferred} 项保留远程引用"else""),
                            style=MaterialTheme.typography.labelMedium,color=NovexColors.SecondaryText)
                        h.current?.takeIf{h.running}?.let {
                            Text(it.substringAfterLast('/').take(40),style=MaterialTheme.typography.labelSmall,color=NovexColors.SecondaryText)
                        }
                    }
                    if(h.running)TextButton(onClick={files.cancelHarvest()}){Text("跳过")}
                }
            }
        }
    }}
}
