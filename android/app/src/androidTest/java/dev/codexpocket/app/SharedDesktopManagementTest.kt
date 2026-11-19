package dev.codexpocket.app

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.*

// Opt-in: the host creates disposable chats and a paired, isolated LAN relay.
class SharedDesktopManagementTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val store = "qa-shared-desktop.enc"
    private lateinit var model: PocketModel

    private fun until(condition: () -> Boolean) = compose.waitUntil(25000) {
        var result = false
        instrumentation.runOnMainSync { result = condition() }
        result
    }

    @After fun cleanup() {
        if (::model.isInitialized) compose.runOnUiThread { model.stop() }
        SecureStore(context, store).save(JSONObject())
    }

    @Test fun deletesLoadedDesktopChatAndReceivesDesktopArchive() {
        val value = InstrumentationRegistry.getArguments().getString("isolatedSharedDesktop")
        Assume.assumeTrue("Requires a disposable shared desktop fixture", !value.isNullOrBlank())
        val fixture = JSONObject(value!!)
        val profile = fixture.getJSONObject("profile")
        Assert.assertEquals("lan", profile.getString("mode"))
        Assert.assertEquals("127.0.0.1", java.net.URI(profile.getString("relay")).host)
        val target = fixture.getString("target")
        val archival = fixture.getString("archival")
        val untouched = fixture.getString("untouched")
        fun has(id: String) = model.threads.any { it.optString("id") == id }
        SecureStore(context, store).save(JSONObject().put("profile", profile))
        // The LAN fixture is reached through adb reverse, not a physical Wi-Fi network.
        compose.runOnUiThread { model = PocketModel(context, store); model.start() }
        compose.setContent { PocketTheme("light") { PocketApp(model, {}, null, {}) } }
        until { model.connected && has(target) && has(archival) && has(untouched) }
        compose.runOnUiThread { model.openThread(model.threads.first { it.optString("id") == target }) }
        until { !model.loadingThread && model.snapshot != null }
        compose.onNodeWithContentDescription("对话更多操作").performClick()
        compose.onNodeWithText("删除对话").performScrollTo().performClick()
        compose.onNodeWithText("取消").performClick()
        compose.runOnUiThread { Assert.assertTrue(has(target)); Assert.assertNotNull(model.selected) }
        compose.onNodeWithContentDescription("对话更多操作").performClick()
        compose.onNodeWithText("删除对话").performScrollTo().performClick()
        compose.onNodeWithText("确认删除").performClick()
        until { model.selected == null && !has(target) && has(untouched) }
        until { !has(archival) && has(untouched) }
    }
}
