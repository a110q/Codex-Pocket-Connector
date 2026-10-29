package dev.codexpocket.app

import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*

// Isolated encrypted desktop peer. No user tasks are created or submitted.
class QueuedMessagesTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val filename = "qa-queued-messages.enc"
    private lateinit var backup: JSONObject
    private lateinit var peer: ConnectionRecoveryTest.Peer
    private lateinit var model: PocketModel
    @Volatile private var current = JSONObject()
    @Volatile private var older = JSONObject()
    private val thread get() = JSONObject().put("id", "queue-qa").put("hostId", "local").put("title", "队列同步回归").put("status", "active").put("activeTurnId", "A")
    private fun message(id: String, text: String, order: Int, queued: Boolean = false) = JSONObject()
        .put("id", id).put("key", (if (queued) "queued:" else "A:") + id).put("turnId", if (queued) "queued" else "A")
        .put("role", "user").put("type", if (queued) "queuedUserMessage" else "userMessage")
        .put("status", if (queued) "queued" else "completed").put("text", text).put("order", order)
    private fun snapshot(messages: List<JSONObject>, queueRevision: Int = 1, hasEarlier: Boolean = false, stream: String = "queue-stream") = JSONObject()
        .put("thread", thread).put("source", "desktop-live").put("streamId", stream).put("revision", 10).put("queueRevision", queueRevision)
        .put("messages", JSONArray(messages)).put("requests", JSONArray()).put("historyComplete", true)
        .put("history", JSONObject().put("hasEarlier", hasEarlier).put("before", messages.firstOrNull()?.optString("key")).put("total", 140).put("complete", true))
    @Before fun setup() {
        backup = SecureStore(context, filename).load()
        peer = ConnectionRecoveryTest.Peer("queued-messages-qa-abcdefghijklmnop")
        current = snapshot(listOf(message("A", "任务 A：正在整理项目文件", 0), message("B", "任务 B：完成后生成安装说明", 1, true)))
        peer.extra = { action, _ -> when (action) {
            "threads.list" -> JSONObject().put("threads", JSONArray().put(thread))
            "thread.read" -> JSONObject(current.toString())
            "thread.history" -> JSONObject(older.toString())
            else -> null
        } }
    }
    @After fun cleanup() {
        if (::model.isInitialized) compose.runOnUiThread { model.stop() }
        peer.close(); SecureStore(context, filename).save(backup)
    }
    private fun until(timeout: Long = 10000, check: () -> Boolean) {
        compose.waitUntil(timeout) { var done = false; instrumentation.runOnMainSync { done = check() }; done }
    }
    private fun start(show: Boolean = true) {
        SecureStore(context, filename).save(JSONObject().put("profile", peer.profile).put("computers", JSONArray().put(peer.profile)))
        compose.runOnUiThread { compose.activity.enableEdgeToEdge(); model = PocketModel(context, filename); model.start() }
        until { model.connected }
        compose.runOnUiThread { model.openThread(thread) }
        until { !model.loadingThread && model.snapshot != null }
        if (show) compose.setContent { PocketTheme("light") { ChatScreen(model) } }
    }
    private fun messages() = model.snapshot?.optJSONArray("messages").objects()
    @Test fun existingQueueIsVisibleAndAcceptedMessageReplacesItWithoutDuplicates() {
        start()
        compose.onNodeWithText("任务 B：完成后生成安装说明").assertIsDisplayed()
        compose.onNodeWithText("排队中 · 当前任务结束后自动发送").assertIsDisplayed()
        val old = JSONObject(current.toString())
        current = snapshot(listOf(message("A", "任务 A：正在整理项目文件", 0), message("accepted-B", "任务 B：完成后生成安装说明", 1)), 2)
        peer.snapshot(current)
        until { model.snapshot?.optInt("queueRevision") == 2 }
        compose.onNodeWithText("排队中 · 当前任务结束后自动发送").assertDoesNotExist()
        compose.onAllNodesWithText("任务 B：完成后生成安装说明").assertCountEquals(1)
        peer.snapshot(old); Thread.sleep(200)
        Assert.assertEquals(2, model.snapshot?.optInt("queueRevision"))
        Assert.assertTrue(messages().none { it.optString("type") == "queuedUserMessage" })
    }
    @Test fun queueEditsClearAndReconnectAcceptsNewStream() {
        start()
        current = snapshot(listOf(message("B", "更新后的任务 B", 0, true)), 2)
        peer.snapshot(current); until { messages().firstOrNull()?.optString("text") == "更新后的任务 B" }
        compose.onNodeWithText("更新后的任务 B").assertIsDisplayed()
        current = snapshot(emptyList(), 3); peer.snapshot(current); until { messages().isEmpty() }
        compose.onNodeWithText("排队中 · 当前任务结束后自动发送").assertDoesNotExist()
        current = snapshot(listOf(message("C", "重连后保留的任务 C", 0, true)), 0, stream = "reconnected")
        peer.snapshot(current); until { model.snapshot?.optString("streamId") == "reconnected" }
        compose.onNodeWithText("重连后保留的任务 C").assertIsDisplayed()
        Assert.assertFalse(peer.actions.contains("message.send"))
    }
    @Test fun removedQueueCannotSurviveOutsideTheNewTailOrReturnFromAnOlderHistoryPage() {
        current = snapshot(listOf(message("removed", "已经删除的排队项", 0, true)), 1, true)
        start(false)
        val newTail = (100..139).map { message("message-$it", "后续消息 $it", it) }
        current = snapshot(newTail, 2, true); peer.snapshot(current)
        until { model.snapshot?.optInt("queueRevision") == 2 }
        Assert.assertTrue(messages().none { it.optString("id") == "removed" })
        older = snapshot(listOf(message("old", "更早的正文", 1), message("removed", "已经删除的排队项", 0, true)), 1, true)
        compose.runOnUiThread { model.loadEarlier() }
        until { peer.actions.contains("thread.history") && !model.loadingHistory }
        Assert.assertTrue(messages().any { it.optString("id") == "old" })
        Assert.assertTrue(messages().none { it.optString("id") == "removed" })
    }
}
