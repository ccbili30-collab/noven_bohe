package com.openminis.app.cards

import com.openminis.app.data.character.*
import novex.content.*
import novex.storage.*
import org.json.JSONObject
import java.io.InputStream
import java.nio.file.Files
import java.util.UUID
import java.util.zip.ZipFile
import java.security.MessageDigest

/** Legacy archives are converted at the boundary, never routed through the old database importer. */
class LegacyArchiveImport(private val store:CardStore,private val directory:java.nio.file.Path) {
    fun prepare(input:InputStream,name:String,kind:CardKind):CardDraft {
        Files.createDirectories(directory)
        val pending=Files.createTempFile(directory,"legacy-",null)
        try {
            input.use {source->Files.newOutputStream(pending).use {source.copyTo(it,65536)}}
            val signature=Files.newInputStream(pending).use {it.read()==0x50 && it.read()==0x4b}
            if(!signature) {
                // 酒馆角色卡 JSON：走方言区整卡转译（世界书→触发模块、前端
                // 正则→novex-frontend 模块），不再是原文字卡——转译报告与
                // 原件归档进 extensions，运行时不接触酒馆 schema。
                val text=runCatching {Files.newInputStream(pending).bufferedReader().use {it.readText()}}.getOrNull()
                if(text!=null && novex.core.tavern.TavernModuleTranslator.isTavernCardJson(text))
                    return prepareTavern(text,name,kind)
                return CardFiles(store).prepare(Files.newInputStream(pending),name,kind)
            }
            ZipFile(pending.toFile()).use {zip->
                if(zip.getEntry("structure.json")!=null)return CardFiles(store).prepare(Files.newInputStream(pending),name,kind)
                val paths=zip.entries().asSequence().map {it.name}.toList()
                require(paths.distinct().size==paths.size){"卡包包含重名文件，未导入"}
                val manifest=JSONObject(zip.getInputStream(requireNotNull(zip.getEntry("manifest.json")){"未识别的压缩包，没有卡片清单"}).bufferedReader().use {it.readText()})
                val oldKind=NovexCardKind.entries.firstOrNull {it.packageType==manifest.optString("packageType")}?:error("未识别的卡包类型")
                require(manifest.optInt("schemaVersion")==1 && manifest.getString("entry")==oldKind.entryName){"旧卡包版本或主文件无效"}
                val raw=zip.getInputStream(requireNotNull(zip.getEntry(oldKind.entryName))).bufferedReader().use {it.readText()}
                val media=manifest.optJSONArray("media")
                val refs=linkedMapOf<String,ContentRef>();val descriptors=mutableListOf<NovexCardMedia>()
                for(i in 0 until (media?.length()?:0)) {
                    val item=requireNotNull(media).getJSONObject(i);val path=item.getString("path")
                    require(path !in refs && path !in setOf("manifest.json",oldKind.entryName)){"媒体文件声明重复"}
                    val entry=requireNotNull(zip.getEntry(path)){"卡包缺少图片"};val reference=store.contents.allocator()()
                    val digest=MessageDigest.getInstance("SHA-256");var count=0L
                    store.contents.receive(listOf(ContentTransfer(ContentRef("import"),reference))) {
                        object:java.io.FilterInputStream(zip.getInputStream(entry)) {
                            override fun read():Int=super.read().also {if(it>=0){digest.update(it.toByte());count++}}
                            override fun read(buffer:ByteArray,offset:Int,length:Int):Int=`in`.read(buffer,offset,length).also {n->if(n>0){digest.update(buffer,offset,n);count+=n}}
                        }
                    }
                    require(count==item.getLong("byteLength") && digest.digest().joinToString(""){"%02x".format(it)}==item.getString("sha256").lowercase()){ "图片校验失败，未导入" }
                    refs[path]=reference;descriptors+=NovexCardMedia(path,item.getString("mimeType"),ByteArray(0),item.getString("sha256"))
                }
                require(paths.toSet()==refs.keys+setOf("manifest.json",oldKind.entryName)){"卡包包含未声明文件，未丢弃后继续导入"}
                val preview=NovexCardPackagePreview(oldKind,manifest.getString("packageId"),manifest.getString("displayName"),raw,descriptors,manifest.toString())
                val parsed=NovexCardTransferParser.parse(preview).document
                val id=UUID.randomUUID().toString()
                fun text(value:String):ContentRef {
                    val ref=store.contents.allocator()();store.contents.receive(listOf(ContentTransfer(ContentRef("text"),ref))){value.byteInputStream()};return ref
                }
                val resources=descriptors.map {CardResource(UUID.randomUUID().toString(),refs.getValue(it.path),it.mimeType)}
                val resourceIds=descriptors.zip(resources).associate {it.first.path to it.second.id}
                fun block(value:String)=ContentBlock.Text(UUID.randomUUID().toString(),text(value))
                fun module(value:NovexModuleImportDocument):ContentModule {
                    val blocks=mutableListOf<ContentBlock>()
                    value.imagePath?.let {blocks+=ContentBlock.Image(UUID.randomUUID().toString(),resourceIds.getValue(it))}
                    val document=value.document
                    if(document is ContentModuleDocument.Collection)document.items.forEach {item->
                        blocks+=block(listOf(item.name,item.summary,item.description).filter(String::isNotBlank).joinToString("\n"))
                        value.itemImagePaths[item.id]?.let {blocks+=ContentBlock.Image(UUID.randomUUID().toString(),resourceIds.getValue(it))}
                    } else blocks+=block(document.toPlainText())
                    return ContentModule(UUID.randomUUID().toString(),value.title,blocks)
                }
                var avatar:String?=null;var cover:String?=null
                val modules=when(parsed) {
                    is NovexWorldImportDocument->{avatar=parsed.logoPath;cover=parsed.coverPath;listOfNotNull(parsed.overview.takeIf(String::isNotBlank)?.let {ContentModule(UUID.randomUUID().toString(),"",listOf(block(it)))})+parsed.modules.map(::module)}
                    is NovexInteractiveFictionImportDocument->{cover=parsed.coverPath;listOfNotNull(parsed.summary.takeIf(String::isNotBlank)?.let {ContentModule(UUID.randomUUID().toString(),"",listOf(block(it)))})+parsed.modules.map(::module)}
                    is NovexCharacterImportDocument->{
                        if(parsed.versions.size==1){val version=parsed.versions.single();avatar=version.avatarPath
                            listOf(ContentModule(UUID.randomUUID().toString(),"",listOf(block(version.profileJson))))+version.modules.map(::module)
                        }else listOf(ContentModule(UUID.randomUUID().toString(),"旧卡原文（含多个版本）",listOf(block(raw))))
                    }
                }
                val preserved=linkedMapOf("legacy/document" to text(raw),"legacy/manifest" to text(manifest.toString()),
                    "legacy/media-map" to text(JSONObject(resourceIds).toString()))
                val content=ContentDocument(id,if(oldKind==NovexCardKind.CHARACTER)CardKind.CHARACTER else CardKind.WORLD,parsed.name,
                    modules,resources,extensions=preserved,appearance=CardAppearance(avatar?.let(resourceIds::getValue),cover?.let(resourceIds::getValue))).validate()
                return CardDrafts(store).create(content,ChangeSource.IMPORT)
            }
        } finally {Files.deleteIfExists(pending)}
    }

    /** 酒馆 JSON → ContentDocument：字段模块 + 世界书触发模块 + 前端件模块。
     *  转译期零网络——远程引用由导入预览页的 TavernAssetHarvester 后台收割
     *  为卡资源（URL→novex-asset 映射进 extensions），此函数秒回。 */
    private fun prepareTavern(sourceJson:String,name:String,kind:CardKind):CardDraft {
        val outcome=novex.core.tavern.TavernModuleTranslator.translate(sourceJson)
        fun text(value:String):ContentRef {
            val ref=store.contents.allocator()();store.contents.receive(listOf(ContentTransfer(ContentRef("text"),ref))){value.byteInputStream()};return ref
        }
        fun block(value:String)=ContentBlock.Text(UUID.randomUUID().toString(),text(value))
        fun useOf(spec:novex.core.tavern.TavernModuleTranslator.UseSpec?):ModuleUse?=when(spec){
            novex.core.tavern.TavernModuleTranslator.UseSpec.Always->ModuleUse.Always
            novex.core.tavern.TavernModuleTranslator.UseSpec.Manual->ModuleUse.Manual
            is novex.core.tavern.TavernModuleTranslator.UseSpec.Keywords->ModuleUse.Keywords(spec.words,spec.caseSensitive,spec.requireAll)
            null->null
        }
        val modules=mutableListOf<ContentModule>()
        outcome.fieldModules.forEach {f->if(f.text.isNotBlank())modules+=ContentModule(UUID.randomUUID().toString(),f.title,listOf(block(f.text)))}
        outcome.bookModules.forEach {m->modules+=ContentModule(UUID.randomUUID().toString(),m.title,listOf(block(m.text)),use=useOf(m.use))}
        outcome.frontendModules.forEach {m->modules+=ContentModule(UUID.randomUUID().toString(),m.title,listOf(block(m.text)),tags=m.tags)}
        // 原件归档键复用 novex.import.original——「按原文导入」既有语义
        // （isVerbatimSource 等既有判定一致生效）；转译报告单独留档。
        val preserved=linkedMapOf(RawTextImport.ORIGINAL to text(sourceJson),
            "legacy/tavern-report" to text(outcome.report.joinToString("\n")))
        val content=ContentDocument(UUID.randomUUID().toString(),kind,
            outcome.cardName.ifBlank{name},modules,emptyList(),extensions=preserved).validate()
        return CardDrafts(store).create(content,ChangeSource.IMPORT)
    }
}
