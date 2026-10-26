package dev.codexpocket.app

import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.graphics.Bitmap
import java.io.File
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.*

/** Uses the production relay/companion in an isolated fixture, including TCP forwarding. */
class PreviewIntegrationTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    private val args get()=InstrumentationRegistry.getArguments()
    private lateinit var model:PocketModel
    @Before fun setup(){
        Assume.assumeTrue("Requires isolated preview-ui-server",args.getString("previewProfile")!=null)
        compose.runOnUiThread{WebView(compose.activity).apply{clearCache(true);destroy()}}
        SecureStore(compose.activity,"qa-preview-integration.enc").save(JSONObject().put("profile",JSONObject(args.getString("previewProfile")!!)))
        compose.runOnUiThread{model=PocketModel(compose.activity,"qa-preview-integration.enc");model.start()}
        compose.waitUntil(20000){model.connected}
        compose.setContent{PocketTheme("light"){PreviewPane(model,Modifier.fillMaxSize(),true,{})}}
    }
    @After fun cleanup(){if(::model.isInitialized){compose.runOnUiThread{model.closePreview();model.stop()};SecureStore(compose.activity,"qa-preview-integration.enc").save(JSONObject())}}
    private fun find(view:View):WebView?=if(view is WebView)view else if(view is ViewGroup)(0 until view.childCount).firstNotNullOfOrNull{find(view.getChildAt(it))}else null
    @Test fun stalledLoadShowsRetryAndRetryRebuildsTheConnection(){
        val fixture=args.getString("previewFixtureUrl")
        Assume.assumeTrue("Needs fixture URL",fixture!=null)
        compose.runOnUiThread{model.openBrowser(fixture+"stalled",true)}
        compose.waitUntil(55000){compose.onAllNodesWithText("网页加载未完成",substring=true).fetchSemanticsNodes().isNotEmpty()||compose.onAllNodesWithText("未能从电脑收到网页",substring=true).fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithText("重新打开网页").assertIsDisplayed()
        compose.onNodeWithContentDescription("网页地址").performTextReplacement(fixture!!)
        compose.onNodeWithText("重新打开网页").performClick()
        var result=""
        compose.waitUntil(20000){compose.runOnUiThread{find(compose.activity.window.decorView)?.evaluateJavascript("document.body.innerText"){result=it}};result.contains("MODULES_READY 30")}
    }
    @Test fun desktopModulePageRendersThroughTheRealRelay(){
        val url=args.getString("previewUrl")!!
        compose.runOnUiThread{model.openBrowser(url,true)}
        var result="";var firstMatch=0L
        try {compose.waitUntil(60000){
            compose.runOnUiThread{find(compose.activity.window.decorView)?.evaluateJavascript("JSON.stringify({title:document.title,text:document.body?.innerText,resources:performance.getEntriesByType('resource').length})"){result=it}}
            val matches=result.contains(args.getString("previewExpected")?:"MODULES_READY 30")
            if(!matches)firstMatch=0 else if(firstMatch==0L)firstMatch=android.os.SystemClock.elapsedRealtime()
            matches && android.os.SystemClock.elapsedRealtime()-firstMatch>=2000
        }} finally {
            val dir=compose.activity.getExternalFilesDir(null)!!
            File(dir,"preview-result.txt").writeText("${model.status}\n$result")
            File(dir,"preview-result.png").outputStream().use{InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG,100,it)}
            println("PREVIEW_RENDER_RESULT=$result");println("PREVIEW_CONNECTION=${model.status}");compose.onRoot().printToLog("PREVIEW_UI")
        }
    }
}
