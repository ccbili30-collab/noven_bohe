package novex.android.ui.cards

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import novex.android.ui.NovexColors
import novex.android.ui.NovexIcons
import novex.android.ui.NovexType
import com.openminis.app.ui.noven.NovenColors
import novex.content.ContentDocument
import novex.content.ContentModule
import novex.content.ContentBlock
import novex.content.ModuleRouting
import novex.content.flattenModules

/**
 * [T008] 注入管理页：总览恒开置顶 + 模块携带路由聚合视图。本页只读——
 * 改携带方式点行进入该模块编辑页的「携带与标签」（与编辑态同一数据源）。
 */
@Composable internal fun CardInjectionPage(card:ContentDocument,model:CardSessionModel) {
    val state=model.state
    data class InjectionRow(val id:String,val name:String,val depth:Int,val routing:ModuleRouting,val hidden:Boolean=false)
    val rows=remember(card){val out=mutableListOf<InjectionRow>()
        fun walk(modules:List<ContentModule>,depth:Int) {for(m in modules){
            out.add(InjectionRow(m.id,m.name.ifBlank {"未命名模块"},depth,m.routing?:ModuleRouting.DEFAULT,m.tags.contains("novex-hidden")))
            walk(m.children,depth+1)}}
        walk(card.editableBodyModules(),0)
        out}
    // 字数只做首屏估算：每文本块读首页 4096，超出标 4k+。
    var sizes by remember(card){mutableStateOf<Map<String,Pair<Int,Boolean>>>(emptyMap())}
    LaunchedEffect(card) {
        val next=mutableMapOf<String,Pair<Int,Boolean>>()
        for(module in card.modules.flattenModules()) {
            var total=0;var truncated=false
            for(block in module.blocks) {
                if(block !is ContentBlock.Text)continue
                val page=runCatching {model.textPage(block.content,0,4096)}.getOrNull()?:continue
                total+=page.text.length
                if(page.next!=null)truncated=true
            }
            next[module.id]=total to truncated
        }
        sizes=next
    }
    Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal=16.dp)) {
        Spacer(Modifier.height(8.dp))
        Text("设定总览默认注入；以下模块按「携带与标签」决定去向。",
            style=NovexType.Metadata,color=NovexColors.SecondaryText)
        Spacer(Modifier.height(12.dp))
        // 总览恒开行
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .background(NovenColors.Mint.copy(alpha=0.10f))
            .padding(horizontal=12.dp,vertical=12.dp),
            verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
            Icon(NovexIcons.Book,null,Modifier.size(20.dp),tint=NovenColors.Mint)
            Text("设定总览",style=NovexType.ItemTitle,color=NovexColors.Text,modifier=Modifier.weight(1f))
            Icon(NovexIcons.Lock,"恒开",Modifier.size(16.dp),tint=NovexColors.SecondaryText)
            Text("恒开",style=NovexType.Metadata,color=NovenColors.Mint)
        }
        Spacer(Modifier.height(12.dp))
        if(rows.isEmpty())Text("这张卡还没有模块。",style=NovexType.Metadata,color=NovexColors.TertiaryText,
            modifier=Modifier.padding(vertical=16.dp))
        rows.forEach {row->
            val size=sizes[row.id]
            val sizeLabel=when {
                size==null->""
                size.second->"4k+ 字"
                else->"${size.first} 字"
            }
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                .clickable(enabled=!state.busy){model.editPart(row.id)}
                .padding(start=(12+row.depth*16).coerceAtMost(60).dp,end=12.dp,top=12.dp,bottom=12.dp),
                verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                Icon(if(row.depth==0)NovexIcons.Description else NovexIcons.Folder,null,Modifier.size(20.dp),tint=NovexColors.SecondaryText)
                Text(row.name.ifBlank {"未命名模块"}+(if(row.hidden)"（已隐藏）" else ""),style=NovexType.ItemTitle,
                    color=if(row.hidden)NovexColors.TertiaryText else NovenColors.Text,
                    modifier=Modifier.weight(1f).semantics{contentDescription="编辑${row.name}的携带方式"})
                if(sizeLabel.isNotEmpty())Text(sizeLabel,style=NovexType.Metadata,color=NovexColors.TertiaryText)
                Text(routingLabel(row.routing),style=NovexType.Metadata,color=if(row.routing==ModuleRouting.DEFAULT)NovexColors.TertiaryText else NovenColors.Mint)
            }
        }
        Spacer(Modifier.height(96.dp))
    }
    Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(start=16.dp,end=16.dp,bottom=16.dp)
        .clip(RoundedCornerShape(12.dp)).background(NovexColors.Surface)) {
        val perTurn=rows.filter {it.routing==ModuleRouting.PER_TURN||it.routing==ModuleRouting.STYLE}
        val known=perTurn.sumOf {sizes[it.id]?.first?:0}
        Column(Modifier.padding(horizontal=12.dp,vertical=10.dp)) {
            Text("每轮固定注入 ${perTurn.size} 项"+(if(known>0)" · 约 ${known*3/4} tokens" else ""),
                style=NovexType.Metadata,color=NovexColors.SecondaryText)
            Text("隐藏只收起阅读器展示，不影响注入；注入去向由携带设置决定。",
                style=NovexType.Metadata,color=NovexColors.TertiaryText)
        }
    }
    }
}

internal fun routingLabel(routing:ModuleRouting):String=when(routing) {
    ModuleRouting.DEFAULT->"开局资料包"
    ModuleRouting.PER_TURN->"每轮注入"
    ModuleRouting.STYLE->"文风"
    ModuleRouting.STANDBY->"待命"
}
