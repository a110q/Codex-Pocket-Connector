package dev.codexpocket.app

import android.content.ContentValues
import android.graphics.Bitmap
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.*

// Opt-in acceptance against an isolated LAN relay and the real installed Codex.
// The companion fixture rejects every mutating RPC. No chats are created or changed.
class RealToolboxReadTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    private lateinit var model:PocketModel
    private val context get()=compose.activity.applicationContext
    private val store="qa-real-toolbox-read.enc"
    private var page by mutableStateOf("home")
    @Before fun setup(){
        val value=InstrumentationRegistry.getArguments().getString("pocketReadOnlyProfile")
        Assume.assumeTrue("Requires an explicit isolated read-only companion profile",!value.isNullOrBlank())
        val profile=JSONObject(value!!);Assert.assertTrue(PocketConnection.isLanPairing(profile))
        SecureStore(context,store).save(JSONObject().put("profile",profile))
        compose.runOnUiThread{model=PocketModel(context,store,true);model.start()}
        compose.setContent{PocketTheme("light"){key(page){ToolboxScreen(model,page){page="home"}}}}
        compose.waitUntil(30000){model.connected&&model.projects.isNotEmpty()}
    }
    @After fun cleanup(){if(::model.isInitialized){compose.runOnUiThread{model.stop()};SecureStore(context,store).save(JSONObject())}}
    private fun open(value:String){compose.runOnUiThread{page=value};compose.waitForIdle()}
    private fun shot(name:String){
        compose.waitForIdle()
        Thread.sleep(350) // Wait for the Android dialog/window transition, outside Compose.
        compose.waitForIdle()
        val values=ContentValues().apply{put(MediaStore.Images.Media.DISPLAY_NAME,"real-$name.png");put(MediaStore.Images.Media.MIME_TYPE,"image/png");put(MediaStore.Images.Media.RELATIVE_PATH,"Pictures/CodexPocket-review");put(MediaStore.Images.Media.IS_PENDING,1)}
        val uri=context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,values)!!
        context.contentResolver.openOutputStream(uri)!!.use{InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG,100,it)}
        context.contentResolver.update(uri,ContentValues().apply{put(MediaStore.Images.Media.IS_PENDING,0)},null,null)
    }
    @Test fun actualProjectWithoutGitShowsAnEmptyStateAndKeepsFilesUsable(){
        open("git");compose.waitUntil(30000){compose.onAllNodesWithText("这个项目还没有 Git 仓库").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithText("fatal",substring=true).assertDoesNotExist();compose.onNodeWithText("提交").assertDoesNotExist();shot("git")
        open("files");compose.waitUntil(30000){compose.onAllNodesWithText("android").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithText(".private").assertDoesNotExist();compose.onNodeWithText("android").assertIsDisplayed()
    }
    @Test fun actualSkillsLoadInCompactNativeCards(){
        open("skills");compose.waitUntil(30000){compose.onAllNodesWithText("项技能",substring=true).fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithText("/Users/",substring=true).assertDoesNotExist();shot("skills")
        open("home");shot("home")
    }
    @Test fun actualMcpStatusUsesReadableLabelsAndHidesUnsupportedLogin(){
        open("mcp");compose.waitUntil(30000){compose.onAllNodesWithText("codex_app").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithText("unsupported",substring=true).assertDoesNotExist();compose.onNodeWithText("登录 / 重新授权").assertDoesNotExist();shot("services")
    }
}
