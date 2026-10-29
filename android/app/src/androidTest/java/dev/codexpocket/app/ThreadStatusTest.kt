package dev.codexpocket.app

import android.content.ContentValues
import android.graphics.Bitmap
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

// Real encrypted TLS/WebSocket transport, isolated from user computers/chats.
class ThreadStatusTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val filename = "qa-thread-status.enc"
    private lateinit var backup: JSONObject
    private lateinit var peer: ConnectionRecoveryTest.Peer
    private var other: ConnectionRecoveryTest.Peer? = null
    private lateinit var model: PocketModel
    private var oldNotifications = false
    private val appearance = mutableStateOf("light")
    @Volatile private var catalog = emptyList<JSONObject>()
    @Volatile private var listHook: ((JSONObject) -> JSONObject)? = null
    private var releaseList: CountDownLatch? = null

    private fun thread(id: String, title: String, status: String, project: String) = JSONObject()
        .put("id", id).put("hostId", "local").put("title", title).put("status", status)
        .put("projectId", project).put("updatedAt", "2026-10-29T06:20:00Z")
    private fun data(id: String, status: String, revision: Int = 1, stream: String = "status-test", requests: JSONArray = JSONArray()): JSONObject = JSONObject()
        .put("thread", JSONObject(catalog.first { it.getString("id") == id }.toString()).put("status", status))
        .put("source", "desktop-live").put("streamId", stream).put("revision", revision)
        .put("messages", JSONArray()).put("requests", requests)
        .put("history", JSONObject().put("total", 0).put("hasEarlier", false).put("complete", true))
    private fun push(id: String, status: String, revision: Int = 1, requests: JSONArray = JSONArray()) {
        val update = data(id, status, revision, requests = requests)
        val activity = threadActivity(update.getJSONObject("thread"), requests)
        catalog = catalog.map { if (it.getString("id") == id) JSONObject(it.toString()).put("status", activity.wireStatus) else it }
        peer.snapshot(update)
    }
    @Before fun setup() {
        backup = SecureStore(context, filename).load()
        oldNotifications = TaskNotifications.enabled(context)
        context.getSharedPreferences("task-alerts", 0).edit().putBoolean("enabled", false).commit()
        peer = ConnectionRecoveryTest.Peer("thread-status-qa-abcdefghijklmnop")
        peer.profile.put("name", "办公室 Mac")
        catalog = listOf(
            thread("run", "重构手机工作空间", "active", "pocket"),
            thread("approval", "检查目录访问权限", "waitingForApproval", "pocket"),
            thread("idle", "整理安装说明", "idle", "pocket"),
            thread("web", "构建项目预览页面", "inProgress", "web"),
            thread("input", "确认发布说明", "waitingForUserInput", "web"),
        )
        peer.extra = { action, args -> when (action) {
            "threads.list" -> JSONObject().put("threads", JSONArray(catalog)).let { listHook?.invoke(it) ?: it }
            "projects.list" -> JSONObject().put("projects", JSONArray()
                .put(JSONObject().put("projectId", "pocket").put("hostId", "local").put("title", "codex_android"))
                .put(JSONObject().put("projectId", "web").put("hostId", "local").put("title", "web_project")))
            "thread.read" -> data(args.getString("threadId"), catalog.first { it.getString("id") == args.getString("threadId") }.getString("status"), 0)
            else -> null
        } }
    }
    @After fun cleanup() {
        releaseList?.countDown()
        if (::model.isInitialized) compose.runOnUiThread { model.stop() }
        peer.close(); other?.close()
        SecureStore(context, filename).save(backup)
        context.getSharedPreferences("task-alerts", 0).edit().putBoolean("enabled", oldNotifications).commit()
    }
    private fun start() {
        SecureStore(context, filename).save(JSONObject().put("profile", peer.profile)
            .put("computers", JSONArray().put(peer.profile).also { values -> other?.let { values.put(it.profile) } }))
        compose.runOnUiThread { compose.activity.enableEdgeToEdge(); model = PocketModel(context, filename); model.start() }
        until { model.connected && model.threads.size == 5 && model.projects.size == 2 }
    }
    private fun until(timeout: Long = 8000, condition: () -> Boolean) {
        compose.waitUntil(timeout) { var done = false; instrumentation.runOnMainSync { done = condition() }; done }
    }
    private fun activity(id: String) = threadActivity(model.threads.first { it.optString("id") == id })
    private fun show() { compose.setContent { PocketTheme(appearance.value) { PocketApp(model, {}, null, {}) } } }
    private fun screenshot(name: String) {
        compose.waitForIdle(); Thread.sleep(400)
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "$name.png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/CodexPocket-QA")
        }
        val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)!!
        context.contentResolver.openOutputStream(uri)!!.use { instrumentation.uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun leftMarkersProjectCountsAndLiveCompletionStayInSync() {
        start(); show()
        compose.onNodeWithContentDescription("重构手机工作空间，运行中").assertIsDisplayed()
        compose.onNodeWithContentDescription("检查目录访问权限，等待审批").assertIsDisplayed()
        compose.onNodeWithContentDescription("确认发布说明，等待输入").assertIsDisplayed()
        compose.onNodeWithText("2 个运行中 · 2 个待处理").assertIsDisplayed()
        screenshot("task-status-light")
        compose.onNodeWithText("codex_android").performClick()
        compose.onNodeWithContentDescription("codex_android：1 个运行中").assertIsDisplayed()
        compose.onNodeWithContentDescription("codex_android：1 个等待处理").assertIsDisplayed()
        compose.onNodeWithContentDescription("重构手机工作空间，运行中").assertDoesNotExist()
        screenshot("task-status-collapsed")
        compose.onNodeWithText("codex_android").performClick()
        compose.runOnUiThread { appearance.value = "dark" }
        screenshot("task-status-dark")
        // No chat has been opened. An update for another chat must still repaint.
        push("run", "idle", 2); push("approval", "idle", 2)
        until { activity("run") == ThreadActivity.IDLE && activity("approval") == ThreadActivity.IDLE }
        compose.onNodeWithText("1 个运行中 · 1 个待处理").assertIsDisplayed()
        compose.onNodeWithContentDescription("重构手机工作空间，可继续").assertIsDisplayed()
        compose.onNodeWithContentDescription("重构手机工作空间，运行中").assertDoesNotExist()
        compose.onNodeWithContentDescription("codex_android：1 个运行中").assertDoesNotExist()
        compose.runOnUiThread { appearance.value = "light" }
        compose.onNodeWithContentDescription("查看运行中与待处理任务").performClick()
        compose.onNodeWithText("运行中与待处理").assertIsDisplayed()
        compose.onNodeWithText("整理安装说明").assertDoesNotExist()
        compose.onNodeWithContentDescription("构建项目预览页面，运行中").assertIsDisplayed()
        screenshot("task-status-active-filter")
        peer.status(false)
        until { !model.connected }
        compose.onNodeWithContentDescription("构建项目预览页面，上次状态：运行中").assertIsDisplayed()
        compose.onNodeWithText("上次状态 · 1 个运行中 · 1 个待处理").assertIsDisplayed()
        screenshot("task-status-offline")
        peer.status(true)
        until { model.connected }
        compose.onNodeWithContentDescription("构建项目预览页面，运行中").assertIsDisplayed()
        Assert.assertFalse(peer.actions.contains("thread.read"))
    }

    @Test fun summariesRefreshWithNotificationsOffAndStopInBackground() {
        start()
        Assert.assertFalse(TaskNotifications.enabled(context))
        catalog = catalog.map { JSONObject(it.toString()).put("status", "idle") }
        until(12000) { model.threads.all { threadActivity(it) == ThreadActivity.IDLE } }
        Assert.assertTrue(peer.actions.count { it == "threads.list" } >= 2)
        Assert.assertFalse("List polling must never read histories", peer.actions.any { it in listOf("thread.read", "thread.history") })
        Assert.assertEquals("Projects should not be repeatedly loaded by the status poll", 1, peer.actions.count { it == "projects.list" })
        compose.runOnUiThread { model.stop() }
        val reads = peer.actions.size
        Thread.sleep(5500)
        Assert.assertEquals(reads, peer.actions.size)
        catalog = catalog.map { if (it.optString("id") == "run") JSONObject(it.toString()).put("status", "active") else it }
        compose.runOnUiThread { model.start() }
        until { model.connected && activity("run") == ThreadActivity.RUNNING }
        Assert.assertNull(model.error)
    }

    @Test fun unknownSummariesNeverEraseRunningWaitingOrCompletedRuntime() {
        start(); show()
        peer.snapshot(data("run", "active", 10).also {
            it.getJSONObject("thread").put("activeTurnId", "long-turn").put("lastTurnStatus", "inProgress")
        })
        until { model.threads.first { it.optString("id") == "run" }.optString("activeTurnId") == "long-turn" }
        // Simulate local catalog / unloaded desktop rows. They contain no
        // runtime evidence, even when they arrive well after the live event.
        catalog = catalog.map { JSONObject(it.toString()).put("status", "notLoaded") }
        peer.snapshot(data("run", "unknown", 11))
        val firstPoll = peer.actions.count { it == "threads.list" }
        until(75000) { peer.actions.count { it == "threads.list" } >= firstPoll + 12 }
        compose.waitForIdle()
        Assert.assertEquals(ThreadActivity.RUNNING, activity("run"))
        Assert.assertEquals(ThreadActivity.RUNNING, activity("web")) // List-only status also survives.
        compose.onNodeWithContentDescription("重构手机工作空间，运行中").assertIsDisplayed()
        compose.onNodeWithContentDescription("codex_android：1 个运行中").assertIsDisplayed()
        screenshot("task-status-sustained-running")

        peer.snapshot(data("run", "active", 12, requests = JSONArray().put(JSONObject()
            .put("method", "item/commandExecution/requestApproval"))))
        until { activity("run") == ThreadActivity.APPROVAL }
        val waitingPoll = peer.actions.count { it == "threads.list" }
        until(12000) { peer.actions.count { it == "threads.list" } >= waitingPoll + 1 }
        compose.waitForIdle()
        Assert.assertEquals(ThreadActivity.APPROVAL, activity("run"))

        peer.snapshot(data("run", "idle", 13).also {
            it.getJSONObject("thread").put("activeTurnId", JSONObject.NULL).put("lastTurnStatus", "completed")
        })
        until { activity("run") == ThreadActivity.IDLE }
        val completedPoll = peer.actions.count { it == "threads.list" }
        until(16000) { peer.actions.count { it == "threads.list" } >= completedPoll + 2 }
        compose.waitForIdle()
        Assert.assertEquals(ThreadActivity.IDLE, activity("run"))
        compose.onNodeWithContentDescription("重构手机工作空间，可继续").assertIsDisplayed()
        Assert.assertFalse("Status refresh must not read histories", peer.actions.any { it in listOf("thread.read", "thread.history") })
    }

    @Test fun authoritativeListCompletionClearsOldLiveRuntimeAndFreshConnectionsDoNotReuseIt() {
        start()
        peer.snapshot(data("run", "active", 20).also { it.getJSONObject("thread").put("activeTurnId", "old-turn").put("lastTurnStatus", "inProgress") })
        until { model.threads.first { it.optString("id") == "run" }.optString("activeTurnId") == "old-turn" }
        catalog = catalog.map { if (it.optString("id") == "run") JSONObject(it.toString()).put("status", "idle") else it }
        compose.runOnUiThread { model.refresh() }
        until { activity("run") == ThreadActivity.IDLE }
        catalog = catalog.map { JSONObject(it.toString()).put("status", "unknown") }
        val reads = peer.actions.count { it == "threads.list" }
        compose.runOnUiThread { model.refresh() }
        until { peer.actions.count { it == "threads.list" } > reads }
        compose.waitForIdle()
        Assert.assertEquals("An old live activeTurnId must not resurrect after list completion", ThreadActivity.IDLE, activity("run"))
        peer.snapshot(data("run", "active", 19))
        Thread.sleep(200); compose.waitForIdle()
        Assert.assertEquals(ThreadActivity.IDLE, activity("run"))

        // Both a new socket and a desktop returning on the same relay must
        // obtain fresh evidence rather than relabel cached activity as live.
        compose.runOnUiThread { model.reconnectNow() }
        until { peer.opens.get() == 2 && model.connected && activity("run") == ThreadActivity.UNKNOWN }
        peer.snapshot(data("run", "active", 0, stream = "new-desktop"))
        until { activity("run") == ThreadActivity.RUNNING }
        peer.status(false)
        until { !model.connected }
        peer.status(true)
        until { model.connected && activity("run") == ThreadActivity.UNKNOWN }
        Assert.assertNull(model.error)
    }

    @Test fun lateListsAndOldEventsCannotRollBackStatusAndArchiveChangesStayIsolated() {
        start()
        val entered = CountDownLatch(1); val release = CountDownLatch(1); releaseList = release
        listHook = { result -> listHook = null; entered.countDown(); release.await(10, TimeUnit.SECONDS); result }
        compose.runOnUiThread { model.refresh() }
        Assert.assertTrue(entered.await(5, TimeUnit.SECONDS))
        push("run", "idle", 20)
        until { activity("run") == ThreadActivity.IDLE }
        release.countDown()
        until { peer.actions.count { it == "models.list" } >= 2 }
        compose.waitForIdle()
        Assert.assertEquals(ThreadActivity.IDLE, activity("run"))
        peer.snapshot(data("run", "active", 19))
        Thread.sleep(200); compose.waitForIdle()
        Assert.assertEquals(ThreadActivity.IDLE, activity("run"))
        peer.snapshot(data("run", "active", 0, stream = "restarted"))
        until { activity("run") == ThreadActivity.RUNNING }
        // A freshly subscribed/selected snapshot updates the list too.
        catalog = catalog.map { if (it.optString("id") == "run") JSONObject(it.toString()).put("status", "idle") else it }
        compose.runOnUiThread { model.openThread(model.threads.first { it.optString("id") == "run" }) }
        until { !model.loadingThread && activity("run") == ThreadActivity.IDLE }
        Assert.assertEquals(ThreadActivity.IDLE, threadActivity(model.selected!!))

        val archiveEntered = CountDownLatch(1); val archiveRelease = CountDownLatch(1); releaseList = archiveRelease
        listHook = { result -> listHook = null; archiveEntered.countDown(); archiveRelease.await(10, TimeUnit.SECONDS); result }
        compose.runOnUiThread { model.refresh() }
        Assert.assertTrue(archiveEntered.await(5, TimeUnit.SECONDS))
        val archived = thread("archived", "已归档的对话", "idle", "pocket").put("archived", true)
        peer.extra = { action, args -> if (action == "threads.list") JSONObject().put("threads", if (args.optBoolean("includeArchived")) JSONArray().put(archived) else JSONArray(catalog)) else null }
        compose.runOnUiThread { model.toggleArchived() }
        archiveRelease.countDown()
        until { model.threads.size == 1 && model.threads.first().optString("id") == "archived" }
        Assert.assertTrue(model.showArchived)
    }

    @Test fun statusCachesAndSocketEventsNeverLeakAcrossComputers() {
        other = ConnectionRecoveryTest.Peer("thread-status-other-abcdefghijklmnop").also { next ->
            next.extra = { action, _ -> if (action == "threads.list") JSONObject().put("threads", JSONArray().put(thread("run", "另一台电脑的任务", "idle", "pocket"))) else null }
        }
        start(); push("run", "active", 50)
        until { activity("run") == ThreadActivity.RUNNING }
        compose.runOnUiThread { Assert.assertTrue(model.switchComputer(other!!.profile.getString("hostId"))) }
        until { model.connected && model.threads.size == 1 && model.threads.first().optString("title") == "另一台电脑的任务" }
        peer.snapshot(data("run", "active", 51))
        Thread.sleep(200); compose.waitForIdle()
        Assert.assertEquals(ThreadActivity.IDLE, activity("run"))
        compose.runOnUiThread { model.switchComputer(peer.profile.getString("hostId")) }
        until { model.connected && model.threads.size == 5 }
        Assert.assertEquals(ThreadActivity.RUNNING, activity("run"))
        Assert.assertNull(model.error)
    }

    @Test fun runtimeShapesDistinguishApprovalsInputErrorsAndFinishedTurns() {
        Assert.assertEquals(ThreadActivity.APPROVAL, threadActivity(JSONObject().put("status", JSONObject().put("type", "active").put("activeFlags", JSONArray().put("awaitingApproval")))))
        Assert.assertEquals(ThreadActivity.INPUT, threadActivity(JSONObject().put("status", JSONObject().put("type", "active").put("activeFlags", JSONArray().put("awaitingUserInput")))))
        Assert.assertEquals(ThreadActivity.APPROVAL, threadActivity(JSONObject().put("status", "active"), JSONArray().put(JSONObject().put("method", "item/commandExecution/requestApproval"))))
        Assert.assertEquals(ThreadActivity.INPUT, threadActivity(JSONObject().put("status", "active"), JSONArray().put(JSONObject().put("method", "item/tool/requestUserInput"))))
        Assert.assertEquals(ThreadActivity.IDLE, threadActivity(JSONObject().put("status", "idle").put("lastTurnStatus", "inProgress"), JSONArray().put(JSONObject().put("method", "item/commandExecution/requestApproval").put("completed", true))))
        Assert.assertEquals(ThreadActivity.ERROR, threadActivity(JSONObject().put("status", "systemError")))
        Assert.assertEquals(ThreadActivity.IDLE, threadActivity(JSONObject().put("status", JSONObject().put("type", "idle").put("activeFlags", JSONArray().put("awaitingApproval")))))
        Assert.assertEquals(ThreadActivity.UNKNOWN, threadActivity(JSONObject().put("status", "notLoaded")))
    }
}
