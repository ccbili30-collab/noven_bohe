package com.openminis.app.cards

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import novex.content.ContentTargets
import novex.runtime.ManagementTarget
import novex.runtime.SourceSelection

/**
 * 从卡片开一段新会话：按 manage 构造 CardBinding（普通 → primary
 * SourceSelection；管理 → managed ManagementTarget），然后走
 * IntegratedCardEntry.draftId 落库。IO 线程执行，返回 chat id。
 */
object IntegratedCardStart {

    /**
     * 卡入口续聊：非管理模式下先找"以此卡为主绑定的最近一条会话"，
     * 有则直接续聊（返回旧会话 id），没有再开新草稿。管理模式
     * （managed 绑定）不享续聊——管理会话不是游玩对话。
     */
    suspend fun start(app: Context, rootId: String, targetId: String, manage: Boolean): String =
        withContext(Dispatchers.IO) {
            if (!manage) {
                latestSessionForCard(app, rootId, targetId)?.let { return@withContext it }
            }
            val cards = IntegratedCards(app)
            val name = ContentTargets.find(
                requireNotNull(cards.store.open(rootId)).content,
                targetId,
            ).name
            val binding = if (manage) {
                CardBinding(managed = setOf(ManagementTarget(rootId, targetId)))
            } else {
                CardBinding(primary = SourceSelection(rootId, targetId))
            }
            IntegratedCardEntry.draftId(app, name, binding)
        }

    /**
     * 找最近一条 primary=(root,target) 的会话 id。LIKE 匹配 cardBinding
     * 编码（{"primary":[{"root":...,"target":...}]} 顺序固定）；key 同时
     * 撞 character_id/world_id 两列兜住旧制卡会话。
     */
    private suspend fun latestSessionForCard(app: Context, rootId: String, targetId: String): String? {
        fun like(v: String) = v.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
        val pattern = "%\"primary\":[{\"root\":\"${like(rootId)}\",\"target\":\"${like(targetId)}\"%"
        return (app.applicationContext as? com.openminis.app.MinisApp)
            ?.database?.chatDao()?.latestSessionForCard(rootId, pattern)
    }
}
