package novex.android.ui.cards

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.*
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import novex.content.CardKind
import novex.core.tavern.TavernAssetHarvester
import novex.storage.*
import java.nio.file.Files
import java.util.UUID

interface CardImportProvider {
    fun prepareCardImport(store:CardStore,input:java.io.InputStream,name:String,kind:CardKind):CardDraft
}

/** 素材收割进度：null = 本卡没有远程素材需要收割。 */
data class HarvestState(val total:Int=0,val done:Int=0,val failed:Int=0,
    val current:String?=null,val running:Boolean=false,val deferred:Int=0)

data class FileTransferState(val visible:Boolean=false,val draft:CardDraft?=null,val busy:Boolean=false,
    val harvest:HarvestState?=null,
    val error:String?=null,val message:String?=null,val importedId:String?=null,val exportTarget:String?=null)

/** 系统文件接口只负责流的取得和交付；格式、草稿、保存归独立核心。
 *  解析（秒开预览）与素材收割（后台、可取消）是两个阶段——确认导入
 *  不等收割，收割在已入库的卡上继续补齐本地资源。 */
class FileTransferModel(application:Application):AndroidViewModel(application) {
    private val resolver=application.contentResolver
    private val store by lazy {CardStore(application.filesDir.toPath().resolve("rewrite-content"))}
    private val harvester by lazy {TavernAssetHarvester(store)}
    private var harvestJob:Job?=null
    var state by mutableStateOf(FileTransferState())
        private set
    fun prepare(uri:Uri,kind:CardKind) {
        if(state.busy)return
        state=FileTransferState(visible=true,busy=true)
        launch {
            val draft=withContext(Dispatchers.IO) {
                val name=runCatching {resolver.query(uri,arrayOf(OpenableColumns.DISPLAY_NAME),null,null,null)?.use {cursor->
                    if(cursor.moveToFirst())cursor.getString(0) else null
                }}.getOrNull()?.takeIf {it.isNotBlank()}?:uri.lastPathSegment?.substringAfterLast('/')?:"导入作品"
                val input=requireNotNull(resolver.openInputStream(uri)){"无法读取所选文件"}
                (getApplication<Application>() as? CardImportProvider)?.prepareCardImport(store,input,name,kind)?:CardFiles(store).prepare(input,name,kind)
            }
            state=state.copy(draft=draft)
            beginHarvest(draft.content.id)
        }
    }
    fun resume(id:String) {
        if(state.busy)return
        state=FileTransferState(visible=true,busy=true)
        launch {
            val recovered=withContext(Dispatchers.IO){CardDrafts(store).read(id) to store.open(id)}
            state=if(recovered.first!=null)state.copy(draft=recovered.first)
                else if(recovered.second!=null)FileTransferState(importedId=id,busy=true)
                else throw IllegalArgumentException("导入草稿不存在")
            if(recovered.first!=null)beginHarvest(id)
        }
    }
    /** 确认导入不等待收割：先提交当前草稿，收割在已入库卡上继续补齐。 */
    fun confirm()=work {
        val id=requireNotNull(state.draft).content.id
        withContext(Dispatchers.IO){
            // 收割可能在后台刚更新过草稿版本：取最新版本重试提交。
            var attempt=0
            while(true){
                val current=CardDrafts(store).read(id)?:run{
                    requireNotNull(store.open(id)){"卡片已不存在"}
                    return@withContext
                }
                try{CardDrafts(store).commit(id,current.version);return@withContext}
                catch(conflict:DraftConflict){if(++attempt>4)throw conflict}
            }
        }
        state=FileTransferState(importedId=id,busy=true)
    }
    /** 取消收割≠丢弃导入：已抓部分保留，其余素材维持远程引用，预览照常可确认。 */
    fun cancelHarvest() {
        harvester.abort()
        harvestJob?.cancel()
        state=state.copy(harvest=state.harvest?.copy(running=false,current=null))
    }
    fun discard()=work {
        cancelHarvest()
        withContext(Dispatchers.IO){
            // 等收割 flush 收束，避免删草稿与收割落盘互踩。
            harvestJob?.join()
            val id=state.draft?.content?.id?:return@withContext
            var attempt=0
            while(true){
                val current=CardDrafts(store).read(id)?:break
                try{CardDrafts(store).discard(id,current.version);break}
                catch(conflict:DraftConflict){if(++attempt>4)throw conflict}
            }
        }
        state=FileTransferState(busy=true)
    }
    /** 预览页可随时离开：收割 job 不随页面关闭，完成后草稿/卡片已含本地资源。 */
    fun close(){state=state.copy(visible=false)}
    fun acknowledge(){state=state.copy(importedId=null)}
    fun export(id:String,uri:Uri,targetId:String=id)=work {
        state=state.copy(exportTarget=id)
        withContext(Dispatchers.IO) {
            val temporary=getApplication<Application>().cacheDir.toPath().resolve("export-${UUID.randomUUID()}.zip")
            try {
                CardFiles(store).export(id,temporary,targetId)
                val output=requireNotNull(resolver.openOutputStream(uri,"w")){"无法写入所选位置"}
                output.use {target->Files.newInputStream(temporary).use {it.copyTo(target,65536)}}
            } finally {Files.deleteIfExists(temporary)}
        }
        state=state.copy(message="已导出")
    }
    private fun beginHarvest(cardId:String) {
        // harvest 是阻塞 IO，cancel 打不断 socket——先 abort 断开连接。
        harvestJob?.cancel();harvester.abort()
        harvestJob=viewModelScope.launch(Dispatchers.IO) {
            try {
                val progress=harvester.harvest(cardId){p->
                    state=state.copy(harvest=HarvestState(p.total,p.done,p.failed,p.current,true,p.deferred))
                }
                val latest=runCatching{CardDrafts(store).read(cardId)}.getOrNull()
                state=state.copy(
                    draft=latest?:state.draft,
                    harvest=if(progress.total>0)HarvestState(progress.total,progress.done,progress.failed,null,false,progress.deferred) else null)
            } catch(cancelled:CancellationException){throw cancelled}
            catch(failure:Exception){state=state.copy(harvest=state.harvest?.copy(running=false,current=null))}
        }
    }
    private fun work(action:suspend ()->Unit) {
        if(state.busy)return
        state=state.copy(busy=true,error=null,message=null)
        launch(action)
    }
    private fun launch(action:suspend ()->Unit) {
        viewModelScope.launch {
            try{action()}
            catch(cancelled:CancellationException){throw cancelled}
            catch(failure:Exception){state=state.copy(error=failure.message?:"文件处理未完成")}
            finally{state=state.copy(busy=false)}
        }
    }
}
