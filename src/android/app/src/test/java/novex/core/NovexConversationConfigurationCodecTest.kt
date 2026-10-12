package novex.core

import org.junit.Assert.assertEquals
import org.junit.Test
import org.json.JSONObject

class NovexConversationConfigurationCodecTest {
    @Test
    fun unreadableOptionalRestorationIdentityDoesNotDiscardTheConversationOrState() {
        val saved = """{"preGameAnswerIdentity":{"kind":"future-persona"},"activeInteractiveFiction":{"projectId":"game","snapshotId":"v1","title":"生生"},"playthroughStates":[{"branchId":"branch","values":{"health":{"kind":"number","value":72}}}]}"""
        val restored = NovexConversationConfigurationCodec.decode(saved, "chat")
        assertEquals("game", restored.activeInteractiveFiction?.projectId)
        assertEquals(PlaythroughValue.Number(72.0), restored.playthroughStates["branch"]?.values?.get("health"))
        assertEquals(null, restored.preGameAnswerIdentity)
    }

    @Test
    fun selectedPersonaSurvivesRestartAndMountingBackgroundWithoutBecomingNova() {
        val saved = """{"version":1,"answerIdentity":{"kind":"personaPreset","presetId":"historian","label":"历史共创者","instructions":"区分历史事实与架空推演。"}}"""
        val restored = NovexConversationConfigurationCodec.decode(saved, "chat-1")
        val changed = NovexConversationConfiguration.open(restored)
            .apply(NovexConversationCommand.AddBackground(NovexContentAddress.world("world-1")))
            .apply(NovexConversationCommand.MountSubject(
                NovexContentAddress.characterVersion("fusheng"), ManagedAccess.EDIT,
            ))
        val persisted = JSONObject(NovexConversationConfigurationCodec.encode(changed.snapshot))
            .getJSONObject("answerIdentity")

        assertEquals("personaPreset", persisted.getString("kind"))
        assertEquals("historian", persisted.getString("presetId"))
        assertEquals("历史共创者", persisted.getString("label"))
        assertEquals("区分历史事实与架空推演。", persisted.getString("instructions"))
        assertEquals(changed.snapshot, NovexConversationConfigurationCodec.decode(
            NovexConversationConfigurationCodec.encode(changed.snapshot), "chat-1",
        ))
    }

    @Test
    fun everyConversationConfigurationRelationSurvivesPersistenceRoundTrip() {
        val sharedWorld = NovexContentAddress.world("world-1")
        val snapshot = NovexConversationConfigurationSnapshot(
            conversationId = "chat-1",
            answerIdentity = AnswerIdentity.CharacterVersion("version-1"),
            backgroundSettings = listOf(BackgroundSetting(sharedWorld)),
            managedSubjects = listOf(ManagedSubject(sharedWorld, ManagedAccess.EDIT)),
            activeInteractiveFiction = ActiveInteractiveFictionSnapshot(
                "game-1",
                "snapshot-1",
                "云岚问道",
                contentJson = """{"summary":"修行冒险"}""",
                presetControls = listOf(
                    ConversationControlDefinition(
                        id = "project-status",
                        label = "文游状态",
                        behavior = ConversationControlBehavior.VIEW,
                        source = ConversationControlSource.PROJECT_PRESET,
                        actionKey = "project.status",
                    ),
                ),
            ),
            playthroughStates = mapOf(
                "branch-1" to PlaythroughState(
                    "branch-1",
                    mapOf(
                        "生命" to PlaythroughValue.Number(80.0),
                        "地点" to PlaythroughValue.Text("山门"),
                        "已入门" to PlaythroughValue.Flag(true),
                    ),
                ),
            ),
            controls = listOf(
                ConversationControlDefinition(
                    id = "project-status",
                    label = "文游状态",
                    behavior = ConversationControlBehavior.VIEW,
                    source = ConversationControlSource.PROJECT_PRESET,
                    actionKey = "project.status",
                ),
                ConversationControlDefinition(
                    id = "status",
                    label = "角色档案",
                    behavior = ConversationControlBehavior.VIEW,
                    source = ConversationControlSource.USER,
                    actionKey = "show_status",
                    enabled = false,
                    branchId = "reply-a",
                ),
            ),
        )

        val restored = NovexConversationConfigurationCodec.decode(
            NovexConversationConfigurationCodec.encode(snapshot),
            conversationId = "chat-1",
        )

        assertEquals(snapshot, restored)
        assertEquals("reply-a", restored.controls.last().branchId)
    }

    @Test
    fun artifactDefsRoundTripThroughCodec() {
        val snapshot = NovexConversationConfiguration.empty("chat-1").snapshot.copy(
            artifactDefs = listOf(
                ConversationArtifactDefinition(
                    id = "panel", kind = "html", template = "<b>{{hp}}</b>",
                    name = "状态面板", branchId = "reply-a", fullscreen = true,
                ),
            ),
        )
        val restored = NovexConversationConfigurationCodec.decode(
            NovexConversationConfigurationCodec.encode(snapshot), "chat-1",
        )
        assertEquals(snapshot.artifactDefs, restored.artifactDefs)
    }

    @Test
    fun artifactDefOwnInputRoundTripsAndDefaultsFalseOnOldData() {
        // v0.3：ownInput 随注册表持久化；旧数据无此字段时按 false 读
        // （optBoolean 缺省），不能崩也不能误开。
        val snapshot = NovexConversationConfiguration.empty("chat-1").snapshot.copy(
            artifactDefs = listOf(
                ConversationArtifactDefinition(
                    id = "stage", kind = "html", template = "<div/>",
                    branchId = "reply-a", fullscreen = true, ownInput = true,
                ),
            ),
        )
        val restored = NovexConversationConfigurationCodec.decode(
            NovexConversationConfigurationCodec.encode(snapshot), "chat-1",
        )
        assertEquals(snapshot.artifactDefs, restored.artifactDefs)
        val legacy = NovexConversationConfigurationCodec.decode(
            """{"artifactDefs":[{"id":"s","kind":"html","template":"<div/>","branchId":"m1"}]}""",
            "chat-1",
        )
        assertEquals(false, legacy.artifactDefs.single().ownInput)
    }

    @Test
    fun emptyLegacyValueIsNewButBrokenDataIsPreservedReadOnly() {
        val empty = NovexConversationConfigurationCodec.decode(null, "chat-legacy")
        val broken = NovexConversationConfigurationCodec.decode("{broken", "chat-legacy")

        assertEquals(NovexConversationConfiguration.empty("chat-legacy").snapshot, empty)
        assertEquals("{broken", broken.unreadableConfiguration)
        assertEquals(NovexExecutionMode.READ_ONLY, broken.executionMode)
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) { NovexConversationConfigurationCodec.encode(broken) }
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            NovexConversationConfiguration.open(broken).apply(NovexConversationCommand.SetExecutionMode(NovexExecutionMode.FREE))
        }
    }

    @Test fun unknownPermissionsDoNotEraseValidBackgroundsOrGrantTools() {
        val raw = """{"executionMode":"future","backgroundSettings":[{"kind":"world","id":"world-1"}]}"""
        val restored = NovexConversationConfigurationCodec.decode(raw, "chat")
        assertEquals(listOf(BackgroundSetting(NovexContentAddress.world("world-1"))), restored.backgroundSettings)
        assertEquals(NovexExecutionMode.READ_ONLY, restored.executionMode)
        assertEquals(raw, restored.unreadableConfiguration)
    }

    @Test
    fun invalidLegacyCharacterIdentityDoesNotDiscardValidBackgroundRelations() {
        val raw = """{"answerIdentity":{"kind":"characterVersion","versionId":""},"backgroundSettings":[{"kind":"world","id":"world-1"}]}"""

        val restored = NovexConversationConfigurationCodec.decode(raw, "legacy")

        assertEquals(AnswerIdentity.Nova, restored.answerIdentity)
        assertEquals(listOf(BackgroundSetting(NovexContentAddress.world("world-1"))), restored.backgroundSettings)
    }
}
