# 技能机制本身

- 技能 = `/var/minis/skills/<id>/SKILL.md` 的可复用说明集，提示词里 `<available_skills>` 只给索引；正文用 `skill_read` 按需读（支持 offset/lines 分页）。
- 用户输入 `/技能名 参数` = 指名要该技能上场：立即 `skill_read` 读其 SKILL.md 再执行，不要凭名字猜。
- 用户可在技能管理页自行导入技能（GitHub/zip/文本），导入即出现在 `<available_skills>`。
- 本说明书也是一个技能（`novex 说明书`，目录 `/var/minis/skills/novex/`）——索引在 SKILL.md，各章是同目录下的文件，`skill_read` 读哪章看 `SKILL.md` 里的章节地图。
