package dev.codexpocket.app

import android.content.ContentValues
import android.graphics.Bitmap
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.webkit.WebView
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.espresso.action.*
import org.hamcrest.Matchers.*
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import okhttp3.*
import okhttp3.mockwebserver.*
import okhttp3.tls.*
import org.json.*
import org.junit.*
import java.net.Socket
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

// All requests terminate in this test process, using independent TLS credentials.
// No production pairing, chat, project, permission or computer setting is changed.
class UiRedesignTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
    private val context get()=instrumentation.targetContext
    private lateinit var backup:JSONObject
    private lateinit var peer:UiPeer
    private lateinit var model:PocketModel
    private var testFontScale by mutableFloatStateOf(1f)
    @Before fun setup(){
        backup=SecureStore(context).load();peer=UiPeer()
        SecureStore(context).save(JSONObject().put("profile",peer.profile).put("appearance","light"))
        compose.runOnUiThread{compose.activity.enableEdgeToEdge();model=PocketModel(context);model.start()}
        compose.setContent{
            val density=androidx.compose.ui.platform.LocalDensity.current
            CompositionLocalProvider(androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(density.density,testFontScale)){
                PocketTheme(model.appearance){PocketApp(model,{},null,{})}
            }
        }
        until{model.connected&&model.projects.size==3}
    }
    @After fun cleanup(){try{compose.runOnUiThread{model.stop()}}finally{try{peer.close()}finally{SecureStore(context).save(backup)}}}
    private fun until(timeout:Long=10000,condition:()->Boolean){compose.waitUntil(timeout){var result=false;compose.runOnUiThread{result=condition()};result}}
    private fun chat(){compose.runOnUiThread{model.openThread(peer.threads.getJSONObject(0))};until{!model.loadingThread&&model.selected!=null};compose.waitForIdle()}
    private fun shot(name:String){
        compose.waitForIdle();Thread.sleep(400)
        val values=ContentValues().apply{put(MediaStore.Images.Media.DISPLAY_NAME,"$name-${System.currentTimeMillis()}.png");put(MediaStore.Images.Media.MIME_TYPE,"image/png");put(MediaStore.Images.Media.RELATIVE_PATH,"Pictures/CodexPocket-${BuildConfig.VERSION_NAME}");put(MediaStore.Images.Media.IS_PENDING,1)}
        val uri=context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,values)!!
        context.contentResolver.openOutputStream(uri)!!.use{instrumentation.uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG,100,it)}
        context.contentResolver.update(uri,ContentValues().apply{put(MediaStore.Images.Media.IS_PENDING,0)},null,null)
    }
    @Test fun connectionDiagnosticsRemainAvailableAfterRecoveryAndContainNoPairingSecrets(){
        chat()
        compose.onNodeWithContentDescription("对话更多操作").performClick()
        compose.onNodeWithText("连接诊断").performScrollTo().performClick()
        compose.onNodeWithText("连接与操作状态").assertIsDisplayed()
        compose.onNodeWithText("hello.received",substring=true).assertExists()
        Assert.assertFalse(model.connectionDetails.contains(peer.profile.getString("key")))
        Assert.assertFalse(model.connectionDetails.contains(peer.profile.getString("mobileToken")))
        compose.onNodeWithText("复制诊断").assertIsEnabled().performClick()
        compose.onNodeWithText("知道了").performClick()
        Assert.assertFalse(peer.calls.any{it.optString("action")=="message.send"})
    }
    @Test fun projectsSearchCollapseAndCreateReachTheAuthenticatedPeer(){
        shot("01-workspace")
        compose.onNodeWithContentDescription("展开搜索").performClick()
        compose.onNodeWithContentDescription("搜索项目或对话").performTextInput("图片")
        compose.onNodeWithText("图片预览优化").assertIsDisplayed()
        compose.onNodeWithText("个人网站").assertDoesNotExist()
        compose.onNodeWithContentDescription("清空搜索").performClick()
        compose.onNodeWithText("codex_android").performClick()
        compose.onNodeWithText("图片预览优化").assertDoesNotExist()
        compose.onNodeWithText("codex_android").performClick()
        compose.onNodeWithContentDescription("在 codex_android 中新开对话").performClick()
        compose.onNodeWithContentDescription("新对话指令").performTextInput("本地 UI 验收新对话")
        shot("06-new-chat")
        compose.onNodeWithText("开始对话").performClick()
        try{until{peer.calls.any{it.optString("action")=="thread.create"}&&model.selected?.optString("id")=="ui-created"&&!model.loadingThread}}
        catch(e:Throwable){throw AssertionError("Create did not finish: selected=${model.selected}, loading=${model.loadingThread}, busy=${model.busy}, pending=${model.pendingOperation}, error=${model.error}, calls=${peer.calls.map{it.optString("action")}}",e)}
        Assert.assertEquals("p1",peer.calls.first{it.optString("action")=="thread.create"}.getJSONObject("args").getString("projectId"))
    }
    @Test fun compactWorkspaceKeepsSearchAndNavigationAccessible(){
        compose.onNodeWithContentDescription("搜索项目或对话").assertDoesNotExist()
        compose.onNodeWithText("移动端适配问题").assertIsDisplayed()
        compose.onNodeWithContentDescription("新建对话").assertIsDisplayed().assertIsEnabled().performClick()
        compose.onNodeWithContentDescription("新对话指令").assertIsDisplayed()
        compose.onNodeWithContentDescription("取消新建对话").performClick()
        compose.onNodeWithContentDescription("工作空间选项").performClick()
        compose.onNodeWithText("任务动态").assertIsDisplayed()
        compose.onNodeWithText("已归档").assertIsDisplayed()
        compose.onNodeWithText("刷新项目与对话").assertIsDisplayed().performClick()
        compose.onNodeWithContentDescription("展开搜索").performClick()
        compose.onNodeWithContentDescription("搜索项目或对话").performClick().performTextInput("图片")
        val bounds=compose.onNodeWithContentDescription("搜索项目或对话").fetchSemanticsNode().boundsInRoot
        Assert.assertTrue("Expanded search must keep its touch height",bounds.height>=52*context.resources.displayMetrics.density-1)
        compose.onNodeWithText("图片预览优化").assertIsDisplayed()
        shot("12-compact-search-keyboard")
        instrumentation.uiAutomation.executeShellCommand("input keyevent KEYCODE_BACK").close()
        compose.onNodeWithContentDescription("收起搜索").performClick()
        compose.onNodeWithText("个人网站").assertIsDisplayed()
    }
    @Test fun transcriptToolDetailsSettingsAndSendRemainFunctional(){
        chat()
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasContentDescription("展开执行过程"))
        compose.onNodeWithContentDescription("展开执行过程").performClick()
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("命令执行 · npm test"))
        compose.onNodeWithText("命令执行 · npm test").performClick()
        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(0)
        shot("02-chat")
        compose.onNodeWithText("查看完整详情").performScrollTo().performClick()
        until{model.detailDone}
        Assert.assertTrue(model.detailChunks.joinToString("").contains("LOCAL UI TEST"))
        compose.onNodeWithContentDescription("关闭记录详情").performClick()
        compose.onNodeWithContentDescription("打开模型与权限设置面板").performClick()
        compose.onNodeWithText("低",useUnmergedTree=true).performClick()
        shot("04-conversation-settings")
        compose.onNodeWithText("gpt-6-luna").performScrollTo().performClick()
        compose.onNodeWithText("只读").performScrollTo().performClick()
        compose.onNodeWithText("保存设置").performClick()
        until{!model.busy&&!model.loadingThread&&model.pendingOperation==null&&model.snapshot?.optJSONObject("thread")?.optJSONObject("settings")?.optString("model")=="gpt-6-luna"}
        Assert.assertEquals("read-only",peer.settings.getString("permissionMode"))
        compose.onNodeWithContentDescription("打开模型与权限设置面板").assertIsEnabled().performClick()
        shot("09-settings-reopen")
        compose.waitUntil(5000){compose.onAllNodesWithText("运行配置").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithText("运行配置").assertIsDisplayed()
        compose.onNodeWithText("完全访问").performScrollTo().performClick()
        compose.onNodeWithText("保存设置").performClick()
        until{!model.busy&&model.snapshot?.optJSONObject("thread")?.optJSONObject("settings")?.optString("permissionMode")=="full-access"}
        Assert.assertEquals("full-access",peer.settings.getString("permissionMode"))
        compose.onNodeWithContentDescription("聊天输入框").performTextInput("本地 UI 验收消息")
        compose.onNodeWithContentDescription("发送").performClick()
        until{peer.calls.any{it.optString("action")=="message.send"}&&!model.busy}
        Assert.assertEquals("本地 UI 验收消息",peer.calls.first{it.optString("action")=="message.send"}.getJSONObject("args").getString("text"))
    }
    @Test fun referenceStyleRunsRealChatActionsFromBottomSheets(){
        executionTranscript(listOf(execution("check","检查连接"),execution("retry","adb reverse tcp:8765 tcp:8765 → error: closed (exit 1)","failed").put("title","本机调试隧道未就绪").put("exitCode",1),execution("recover","恢复连接","inProgress")),reply=false)
        peer.messages.put(JSONObject().put("id","qa-reply").put("turnId","ui-turn").put("role","assistant").put("text","连接恢复逻辑已调整，正在检查断网重连和消息回执。\n\n> • 心跳超时阈值由 `30s` 收敛为指数退避 `2s → 16s`  \n> • 断线期间离线队列缓存序列号，重连后校验 ACK 回执"))
        peer.reference=true;peer.approval=true;peer.active=true
        peer.settings.put("model","gpt-6.1-sol")
        compose.runOnUiThread{model.renameComputer(peer.profile.getString("hostId"),"工作电脑");model.notice=null}
        chat()
        compose.onNode(hasScrollToIndexAction()).performTouchInput{swipeDown()}
        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(0)
        shot("reference-chat")
        compose.onNodeWithContentDescription("快速切换电脑").assertIsDisplayed().performClick()
        compose.onNodeWithText("切换连接的电脑").assertIsDisplayed();shot("reference-hosts")
        instrumentation.uiAutomation.executeShellCommand("input keyevent KEYCODE_BACK").close()
        compose.onNodeWithContentDescription("打开模型与权限设置面板").performClick()
        compose.onNodeWithText("运行配置").assertIsDisplayed();shot("reference-settings")
        compose.onNodeWithText("完成设置").assertIsEnabled().performClick()
        compose.onNodeWithContentDescription("添加附件").performClick()
        compose.onNodeWithText("引用电脑文件").assertIsDisplayed();shot("reference-attachments")
        instrumentation.uiAutomation.executeShellCommand("input keyevent KEYCODE_BACK").close()
        compose.onNodeWithContentDescription("对话更多操作").performClick()
        compose.onNodeWithText("打开项目文件").assertIsDisplayed()
        compose.onNodeWithText("打开网页预览").assertIsDisplayed();shot("reference-more")
        instrumentation.uiAutomation.executeShellCommand("input keyevent KEYCODE_BACK").close()
        compose.onNodeWithContentDescription("停止").assertIsDisplayed().assertIsEnabled().performClick()
        until{peer.calls.any{it.optString("action")=="turn.stop"}&&!model.busy}
        compose.onNodeWithContentDescription("聊天输入框").performTextInput("补充测试要求")
        compose.onNodeWithContentDescription("发送").assertIsEnabled().performClick()
        until{peer.calls.any{it.optString("action")=="message.send"}&&!model.busy}
        instrumentation.uiAutomation.executeShellCommand("input keyevent KEYCODE_BACK").close()
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("允许本次"))
        compose.onNodeWithText("允许本次").performClick()
        until{peer.calls.any{it.optString("action")=="approval.respond"}&&!model.busy}
        compose.runOnUiThread{model.updateAppearance("dark")}
        shot("reference-dark")
    }
    @Test fun largeTextKeepsConfigurationAndSendAccessible(){
        executionTranscript(listOf(execution("running","检查连接","inProgress")),reply=false)
        peer.active=true;peer.reference=true;chat()
        compose.runOnUiThread{testFontScale=1.5f}
        compose.onNodeWithContentDescription("打开模型与权限设置面板").assertIsDisplayed().performClick()
        compose.onNodeWithText("完全访问").performScrollTo().assertIsDisplayed()
        shot("large-text-settings")
        compose.onNodeWithContentDescription("关闭对话设置").performClick()
        compose.onNodeWithContentDescription("聊天输入框").performClick().performTextInput("大字号消息")
        compose.onNodeWithContentDescription("发送").assertIsDisplayed().assertIsEnabled()
        shot("large-text-keyboard")
    }
    private fun execution(id:String,title:String,status:String="completed",turn:String="ui-turn")=JSONObject()
        .put("id",id).put("turnId",turn).put("key","$turn:$id").put("role","tool")
        .put("type","commandExecution").put("title","命令执行").put("command",title)
        .put("text","$title\n隔离测试中的完整执行输出").put("status",status).put("hasDetail",true)
    private fun executionTranscript(records:List<JSONObject>,reply:Boolean=true){
        while(peer.messages.length()>0)peer.messages.remove(0)
        peer.messages.put(JSONObject().put("id","qa-user").put("turnId","ui-turn").put("role","user").put("text","检查项目连接，完成后告诉我结果。"))
        records.forEach{peer.messages.put(it)}
        if(reply)peer.messages.put(JSONObject().put("id","qa-reply").put("turnId","ui-turn").put("role","assistant").put("text","连接已恢复，可以继续工作。"))
    }
    @Test fun executionGroupsCollapseWithoutHidingRepliesAndOpenFullDetails(){
        executionTranscript(listOf(execution("check","检查连接"),execution("retry","首次连接检查","failed"),execution("recover","恢复连接").put("role","summary").put("type","reasoning").put("command",JSONObject.NULL).put("title","思考摘要")))
        chat()
        compose.onNodeWithText("执行过程 · 3 项").assertIsDisplayed()
        compose.onNodeWithText("1 条未成功记录").assertIsDisplayed()
        compose.onNodeWithText("执行失败 ·",substring=true).assertDoesNotExist()
        compose.onNodeWithText("任务运行出错").assertDoesNotExist()
        compose.onNodeWithText("命令执行 · 检查连接").assertDoesNotExist()
        onView(withText("连接已恢复，可以继续工作。")).check(androidx.test.espresso.assertion.ViewAssertions.matches(isDisplayed()))
        shot("tool-process-collapsed")
        compose.onNodeWithContentDescription("展开执行过程").performClick()
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("命令执行 · 检查连接"))
        compose.onNodeWithText("命令执行 · 检查连接").assertIsDisplayed()
        shot("tool-process-expanded")
        compose.onNodeWithText("命令执行 · 检查连接").performClick()
        compose.onNodeWithText("查看完整详情").performScrollTo().performClick()
        until{model.detailDone}
        Assert.assertEquals("ui-turn:check",peer.calls.last{it.optString("action")=="message.read"}.getJSONObject("args").getString("messageKey"))
        Assert.assertTrue(model.detailChunks.joinToString("").contains("LOCAL UI TEST"))
        compose.onNodeWithContentDescription("关闭记录详情").performClick()
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasContentDescription("收起执行过程"))
        compose.onNodeWithContentDescription("收起执行过程").performClick()
        compose.onNodeWithText("命令执行 · 检查连接").assertDoesNotExist()
        onView(withText("连接已恢复，可以继续工作。")).check(androidx.test.espresso.assertion.ViewAssertions.matches(isDisplayed()))
        Assert.assertEquals(5,model.snapshot!!.getJSONArray("messages").length())
        compose.runOnUiThread{model.updateAppearance("dark")}
        compose.onNodeWithContentDescription("展开执行过程").assertIsDisplayed()
        shot("tool-process-dark")
    }
    @Test fun nonzeroCommandResultsNeverBecomeTaskFailuresDuringWorkOrAfterRecovery(){
        val failed=execution("query","npm view package-that-does-not-exist version","failed").put("exitCode",1)
        val running=execution("retry","继续检查项目","inProgress")
        peer.active=true
        executionTranscript(listOf(failed,execution("read","读取项目配置"),running),reply=false)
        chat()
        compose.onNodeWithText("执行过程 · 3 项").assertIsDisplayed()
        compose.onNodeWithText("任务运行出错").assertDoesNotExist()
        compose.onNodeWithText("执行失败 ·",substring=true).assertDoesNotExist()
        shot("command-result-running")
        compose.onNodeWithContentDescription("展开执行过程").performClick()
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("命令返回非零 · 退出码 1"))
        compose.onNodeWithText("命令返回非零 · 退出码 1").assertIsDisplayed()
        compose.onNodeWithText("命令执行 · npm view package-that-does-not-exist version").performClick()
        compose.onNodeWithText("查看完整详情").performScrollTo().performClick()
        until{model.detailDone}
        Assert.assertEquals("ui-turn:query",peer.calls.last{it.optString("action")=="message.read"}.getJSONObject("args").getString("messageKey"))
        compose.onNodeWithContentDescription("关闭记录详情").performClick()
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasContentDescription("收起执行过程"))
        compose.onNodeWithContentDescription("收起执行过程").performClick()
        running.put("status","completed");peer.active=false;peer.threads.getJSONObject(0).put("status","idle")
        peer.messages.put(JSONObject().put("id","recovered").put("turnId","ui-turn").put("role","assistant").put("text","问题已绕过，项目继续正常运行。"))
        peer.publish();until{model.snapshot!!.getJSONArray("messages").length()==5}
        compose.onNodeWithText("任务运行出错").assertDoesNotExist()
        compose.onNodeWithText("执行失败 ·",substring=true).assertDoesNotExist()
        onView(withText("问题已绕过，项目继续正常运行。")).check(androidx.test.espresso.assertion.ViewAssertions.matches(isDisplayed()))
        Assert.assertEquals(1,model.snapshot!!.getJSONArray("messages").getJSONObject(1).getInt("exitCode"))
        compose.runOnUiThread{model.updateAppearance("dark")};shot("command-result-recovered-dark")
    }
    @Test fun actualTaskErrorIsShownOnceAndClearsWhenTheTaskResumes(){
        peer.threads.getJSONObject(0).put("status","systemError")
        val error=execution("turn-error","","failed").put("type","turnError").put("title","运行错误").put("text","隔离测试：任务连接已终止")
        executionTranscript(listOf(execution("query","rg missing-pattern .","failed").put("exitCode",1),error),reply=false)
        chat()
        compose.onAllNodesWithText("任务运行出错").assertCountEquals(1)
        compose.onNodeWithText("查看任务错误").performClick();until{model.detailDone}
        Assert.assertEquals("ui-turn:turn-error",peer.calls.last{it.optString("action")=="message.read"}.getJSONObject("args").getString("messageKey"))
        compose.onNodeWithContentDescription("关闭记录详情").performClick()
        shot("actual-task-error")
        peer.threads.getJSONObject(0).put("status","running");peer.active=true;peer.publish()
        until{model.snapshot!!.getJSONObject("thread").optString("status")=="running"}
        compose.onNodeWithText("任务运行出错").assertDoesNotExist()
        Assert.assertEquals(3,model.snapshot!!.getJSONArray("messages").length())
    }
    @Test fun streamingExecutionGroupsKeepFoldChoiceAndUpdateTheirStatus(){
        val running=execution("check","检查连接","inProgress")
        executionTranscript(listOf(running,execution("read","读取状态")),reply=false)
        chat()
        compose.onNodeWithText("执行过程 · 2 项").assertIsDisplayed()
        compose.onNodeWithText("1 运行中").assertIsDisplayed()
        val tail=execution("next","重新检查","inProgress")
        peer.messages.put(tail);peer.publish()
        until{model.snapshot!!.getJSONArray("messages").length()==4}
        compose.onNodeWithText("执行过程 · 3 项").assertIsDisplayed()
        compose.onNodeWithText("命令执行 · 检查连接").assertDoesNotExist()
        compose.onNodeWithContentDescription("展开执行过程").performClick()
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("命令执行 · 重新检查"))
        compose.onNodeWithText("命令执行 · 重新检查").assertIsDisplayed()
        running.put("status","completed");tail.put("status","completed");peer.publish()
        until{model.snapshot!!.getJSONArray("messages").getJSONObject(3).getString("status")=="completed"}
        compose.onNodeWithText("命令执行 · 重新检查").assertIsDisplayed()
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasContentDescription("收起执行过程"))
        compose.onNodeWithContentDescription("收起执行过程").performClick()
        compose.onNodeWithText("已完成").assertIsDisplayed()
        peer.messages.put(execution("last","检查文件"));peer.publish()
        until{model.snapshot!!.getJSONArray("messages").length()==5}
        compose.onNodeWithText("执行过程 · 4 项").assertIsDisplayed()
        compose.onNodeWithText("命令执行 · 检查文件").assertDoesNotExist()
    }
    @Test fun executionGroupsStayIndependentBetweenTurnsAndConversations(){
        executionTranscript(listOf(execution("first","第一步"),execution("second","第二步"),execution("other","另一轮",turn="other-turn")))
        chat()
        compose.onNodeWithText("执行过程 · 2 项").performClick()
        compose.onNodeWithContentDescription("收起执行过程").assertExists()
        compose.onNodeWithContentDescription("展开执行过程").assertExists()
        compose.onNodeWithText("命令执行 · 另一轮").assertDoesNotExist()
        compose.runOnUiThread{model.openThread(peer.threads.getJSONObject(1))}
        until{!model.loadingThread&&model.snapshot!!.getJSONObject("thread").getString("id")=="ui-1"}
        compose.onAllNodesWithContentDescription("展开执行过程").assertCountEquals(2)
        compose.onNodeWithContentDescription("收起执行过程").assertDoesNotExist()
        compose.onNodeWithText("命令执行 · 第一步").assertDoesNotExist()
    }
    @Test fun longExecutionGroupsCanCollapseAfterScrollingAndApprovalsStayAvailable(){
        executionTranscript((1..80).map{execution("tool-$it","执行步骤 $it")})
        peer.approval=true;chat()
        compose.onNodeWithText("允许本次").assertIsDisplayed().assertIsEnabled()
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasContentDescription("展开执行过程"))
        compose.onNodeWithContentDescription("展开执行过程").performClick()
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("命令执行 · 执行步骤 80"))
        compose.onNodeWithText("命令执行 · 执行步骤 80").assertIsDisplayed()
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasContentDescription("收起执行过程"))
        compose.onNodeWithContentDescription("收起执行过程").performClick()
        compose.onNodeWithText("执行过程 · 80 项").assertIsDisplayed()
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("允许本次"))
        compose.onNodeWithText("允许本次").performClick()
        until{peer.calls.any{it.optString("action")=="approval.respond"}&&!model.busy}
        Assert.assertEquals(82,model.snapshot!!.getJSONArray("messages").length())
        compose.onNodeWithText("命令执行 · 执行步骤 80").assertDoesNotExist()
    }
    @Test fun longModelAndMultilineInputKeepSendReachableWithKeyboard(){
        peer.settings.put("model","gpt-6.1-sol-a-very-long-model-name-for-layout")
        chat();compose.onNodeWithContentDescription("打开模型与权限设置面板").assertIsDisplayed()
        compose.onNodeWithContentDescription("聊天输入框").performClick().performTextInput("第一行指令\n第二行补充\n第三行条件\n第四行说明\n第五行结果\n第六行仍可编辑")
        compose.onNodeWithContentDescription("发送").assertIsDisplayed().assertIsEnabled()
        shot("10-multiline-keyboard")
        instrumentation.uiAutomation.executeShellCommand("input keyevent KEYCODE_BACK").close()
        compose.waitUntil(5000){compose.onAllNodesWithContentDescription("打开模型与权限设置面板").fetchSemanticsNodes().isNotEmpty()}
        shot("11-long-model-composer")
    }
    @Test fun offlinePendingOperationDoesNotCollapseTheComposerBehindTheKeyboard(){
        peer.active=true;chat();peer.ignoreMessages=true
        compose.runOnUiThread{model.send("QA offline layout")}
        until{model.pendingOperation!=null&&peer.calls.any{it.optString("action")=="message.send"}}
        peer.offline();until{!model.connected&&!model.busy}
        compose.onNodeWithContentDescription("聊天输入框").performClick().performTextInput("断线时仍可编辑草稿")
        compose.waitForIdle()
        val input=compose.onNodeWithContentDescription("聊天输入框")
        input.assertIsDisplayed()
        shot("offline-pending-keyboard")
        val density=context.resources.displayMetrics.density
        val composer=compose.onNodeWithTag("chat-composer").fetchSemanticsNode().layoutInfo.coordinates.boundsInWindow()
        val textBounds=input.fetchSemanticsNode().boundsInRoot
        val imeBottom=ViewCompat.getRootWindowInsets(compose.activity.window.decorView)!!.getInsets(WindowInsetsCompat.Type.ime()).bottom
        Assert.assertTrue("The software keyboard must be visible",imeBottom>0)
        Assert.assertTrue("Composer must retain a full touch row: $composer",composer.height>=48*density-1)
        // BasicTextField semantics exclude its 13 dp top and bottom padding.
        Assert.assertTrue("Input must retain a readable text line: $textBounds",textBounds.height>=22*density-1)
        Assert.assertTrue("Composer must stay above the keyboard: $composer",composer.bottom<=compose.activity.window.decorView.height-imeBottom+1)
        compose.onNodeWithContentDescription("发送").assertIsDisplayed().assertIsNotEnabled()
        compose.onNodeWithText("Codex 正在处理").assertDoesNotExist()
        compose.onNodeWithText("查看",substring=false).performClick()
        compose.onNodeWithText("操作结果待确认").assertIsDisplayed()
        compose.onNodeWithText("已查看，清除记录").performClick()
        compose.onNodeWithText("返回检查").performClick()
        Assert.assertNotNull(model.pendingOperation)
        Assert.assertEquals("断线时仍可编辑草稿",model.draft)
        Assert.assertEquals(1,peer.calls.count{it.optString("action")=="message.send"})
    }
    @Test fun webPreviewUsesTheRealTunnelAndCanCollapse(){
        chat();compose.runOnUiThread{model.openBrowser(peer.website.url("/").toString())}
        until(15000){peer.website.requestCount>0&&peer.calls.any{it.optString("action")=="preview.read"}}
        Thread.sleep(1200);shot("03-web-preview")
        compose.onNodeWithContentDescription("收起面板").performClick()
        compose.onNodeWithContentDescription("网页地址").assertDoesNotExist()
        compose.onNodeWithContentDescription("聊天输入框").assertIsDisplayed()
        compose.onNodeWithContentDescription("恢复预览").performClick()
        compose.onNodeWithContentDescription("网页地址").assertIsDisplayed()
        compose.onNodeWithContentDescription("关闭预览").performClick()
        compose.onNodeWithContentDescription("网页地址").assertDoesNotExist()
    }
    @Test fun chatLinksOpenTheDesktopWebsiteInAppAndCanReturnToTheConversation(){
        val url="http://127.0.0.1:${peer.website.port}/"
        peer.messages.put(JSONObject().put("id","web-link").put("turnId","ui-turn").put("role","assistant").put("text","项目已启动：[点击打开]($url)\n\n地址：$url").put("textComplete",true))
        chat()
        fun clickLink(label:String){
            onView(allOf(isAssignableFrom(TextView::class.java),withText(containsString("项目已启动：")))).perform(GeneralClickAction(Tap.SINGLE,{view->
                val text=view as TextView;val offset=text.text.indexOf(label)+label.length/2;val line=text.layout.getLineForOffset(offset)
                val location=IntArray(2);text.getLocationOnScreen(location)
                floatArrayOf(location[0]+text.totalPaddingLeft+text.layout.getPrimaryHorizontal(offset)-text.scrollX,location[1]+text.totalPaddingTop+(text.layout.getLineTop(line)+text.layout.getLineBottom(line))/2f-text.scrollY)
            },Press.FINGER,0,0))
        }
        fun webView(view:View):WebView?=if(view is WebView)view else if(view is ViewGroup)(0 until view.childCount).firstNotNullOfOrNull{webView(view.getChildAt(it))}else null
        fun awaitPage(){
            until(15000){peer.website.requestCount>0&&peer.calls.any{it.optString("action")=="preview.read"}}
            var html=""
            until(15000){webView(compose.activity.window.decorView)?.evaluateJavascript("document.body.innerText"){html=it};html.contains("构建下一种可能")}
            Assert.assertTrue(peer.calls.any{it.optString("action")=="preview.connect"&&it.getJSONObject("args").optString("host")=="127.0.0.1"})
        }
        clickLink("点击打开");until{model.previewExpanded&&model.previewKind=="browser"};awaitPage()
        compose.onNodeWithContentDescription("聊天输入框").assertDoesNotExist();shot("chat-link-fullscreen")
        compose.onNodeWithContentDescription("退出展开预览").performClick()
        compose.onNodeWithContentDescription("聊天输入框").assertIsDisplayed();shot("chat-link-small-window")
        compose.onNodeWithContentDescription("收起面板").performClick()
        clickLink("点击打开");until{model.previewExpanded};awaitPage()
        compose.onNodeWithContentDescription("聊天输入框").assertDoesNotExist()
        compose.onNodeWithContentDescription("关闭预览").performClick()
        clickLink(url);until{model.previewExpanded&&model.previewKind=="browser"};awaitPage()
        instrumentation.uiAutomation.executeShellCommand("input keyevent KEYCODE_BACK").close()
        until{!model.previewExpanded};compose.onNodeWithContentDescription("聊天输入框").assertIsDisplayed()
    }
    @Test fun approvalFilePreviewAppearancePersistenceAndErrorStates(){
        peer.approval=true;chat()
        compose.onNodeWithText("允许本次").performClick()
        until{peer.calls.any{it.optString("action")=="approval.respond"}&&!model.busy}
        Assert.assertEquals("accept",peer.calls.first{it.optString("action")=="approval.respond"}.getJSONObject("args").getString("decision"))
        compose.onNodeWithContentDescription("对话更多操作").performClick();compose.onNodeWithText("查看交付文件",substring=false).performClick()
        compose.onNodeWithText("验收说明.md").performScrollTo().performClick()
        until{model.previewFile!=null&&!model.previewLoading}
        Assert.assertTrue(model.previewFile!!.readText().contains("隔离环境"));shot("07-file-preview")
        compose.onNodeWithContentDescription("关闭预览").performClick()
        compose.onNodeWithContentDescription("返回项目").performClick()
        compose.onNodeWithContentDescription("打开设置").performClick()
        compose.onNodeWithText("深色").performClick()
        Assert.assertEquals("dark",SecureStore(context).load().getString("appearance"))
        compose.onNodeWithContentDescription("返回工作空间").performClick();shot("05-dark-workspace")
        compose.onNodeWithContentDescription("展开搜索").performClick()
        compose.onNodeWithContentDescription("搜索项目或对话").performTextInput("没有这样的项目或对话")
        compose.runOnUiThread{model.error="网络暂时不可用。已缓存的对话仍可查看，连接恢复后会重新同步。"}
        compose.onNodeWithText("没有找到相关项目或对话").assertIsDisplayed();shot("08-empty-error")
    }

    private inner class UiPeer {
        val server=MockWebServer();val website=MockWebServer()
        val calls=CopyOnWriteArrayList<JSONObject>()
        private val sockets=CopyOnWriteArrayList<WebSocket>()
        private val channels=ConcurrentHashMap<WebSocket,String>()
        var ignoreMessages=false;var active=false
        fun offline(){sockets.last().send(JSONObject().put("type","status").put("peerOnline",false).toString())}
        private val tcp=ConcurrentHashMap<String,Socket>()
        private val workers=Executors.newCachedThreadPool()
        private val host="ui-only-abcdefghijklmnopqrstuv"
        private val key=Encoding.secret();private val codec=UiDesktopCodec(key,host)
        val profile:JSONObject
        var approval=false
        var reference=false
        val settings=JSONObject().put("model","gpt-6.1-sol").put("reasoningEffort","high").put("permissionMode","auto")
        val projects=JSONArray().put(JSONObject().put("projectId","p1").put("title","codex_android").put("path","/workspace/codex_android")).put(JSONObject().put("projectId","p2").put("title","个人网站").put("path","/workspace/website")).put(JSONObject().put("projectId","p3").put("title","数据分析").put("path","/workspace/analysis"))
        val threads=JSONArray()
        val messages=JSONArray()
        private val files=mutableMapOf<String,ByteArray>()
        private val artifacts=JSONArray()
        init{
            listOf("修复自动重连","重新设计手机界面","图片预览优化","模型切换与访问权限设置","添加浏览器预览功能","首页布局优化","添加博客文章页面","移动端适配问题","生成销售数据图表","修复数据导入脚本").forEachIndexed{i,title->threads.put(JSONObject().put("id","ui-$i").put("hostId","local").put("projectId",if(i<5)"p1" else if(i<8)"p2" else "p3").put("title",title).put("status",if(i==0)"running" else "idle").put("updatedAt",java.time.Instant.now().minusSeconds(i*7200L).toString()))}
            fun message(id:String,role:String,text:String)=JSONObject().put("id",id).put("turnId","ui-turn").put("key","ui-turn:$id").put("role",role).put("text",text).put("textComplete",true)
            messages.put(message("user","user","电脑断网恢复后，手机一直连接不上。"))
            messages.put(message("progress","assistant","正在检查电脑与手机的连接状态。"))
            messages.put(message("tool","tool","LOCAL UI TEST\n✓ 连接恢复测试通过\n✓ 握手超时测试通过\n✓ 未确认消息没有重复发送\n\n3 tests passed").put("type","commandExecution").put("title","命令执行").put("command","npm test").put("status","completed").put("exitCode",0).put("hasDetail",true))
            messages.put(message("reply","assistant","已经定位到连接恢复的问题。\n\n修复包含：\n- 主动检测失效连接\n- 网络恢复后重新握手\n- 保留待确认的操作结果\n\n你可以继续查看工具详情和交付文件。"))
            val asset=instrumentation.context.assets.open("desktop-attachment.jpg").use{it.readBytes()};val imageId="a".repeat(32)
            files[imageId]=asset
            val image=JSONObject().put("id",imageId).put("path","desktop-attachment.jpg").put("name","电脑截图.jpg").put("size",asset.size).put("mime","image/jpeg").put("available",true)
            artifacts.put(image);messages.put(message("photo","user","这是我这边的错误截图。").put("attachments",JSONArray().put(image)))
            val docId="b".repeat(32);val doc="# 界面验收\n\n本页来自隔离环境，验证真实文件读取与 Markdown 预览。\n\n- 项目与对话\n- 工具记录\n- 模型与权限\n".toByteArray();files[docId]=doc
            artifacts.put(JSONObject().put("id",docId).put("path","验收说明.md").put("name","验收说明.md").put("size",doc.size).put("mime","text/markdown"))
            website.dispatcher=object:okhttp3.mockwebserver.Dispatcher(){override fun dispatch(request:RecordedRequest)=MockResponse().setHeader("Content-Type","text/html; charset=utf-8").setHeader("Connection","close").setBody("""<!doctype html><meta name="viewport" content="width=device-width,initial-scale=1"><style>body{font:15px system-ui;margin:0;color:#172332;background:#f7faff}nav{padding:18px 22px;border-bottom:1px solid #e7edf5;background:white}main{padding:28px 23px}small{color:#147aff}h1{font-size:30px;line-height:1.35;margin:18px 0}p{color:#78879b;line-height:1.8}button{background:#147aff;border:0;border-radius:9px;padding:13px 22px;color:white}.card{background:white;padding:18px;border:1px solid #e7edf5;border-radius:12px;margin-top:26px}</style><nav><b>⌘ Pocket Studio</b><span style="float:right;color:#147aff">项目</span></nav><main><small>YOUR IDEAS, IN MOTION</small><h1>构建下一种可能。</h1><p>从一个想法开始，<br>在你的工作空间里完成。</p><button>探索项目 →</button><div class="card">真实网页预览<br><p>由本地隔离服务提供，经加密通道访问。</p></div></main>""")}
            website.start()
            val cert=HeldCertificate.Builder().commonName("localhost").addSubjectAlternativeName("localhost").build()
            server.useHttps(HandshakeCertificates.Builder().heldCertificate(cert).build().sslSocketFactory(),false)
            server.dispatcher=object:okhttp3.mockwebserver.Dispatcher(){override fun dispatch(request:RecordedRequest)=MockResponse().withWebSocketUpgrade(object:WebSocketListener(){
                override fun onOpen(webSocket:WebSocket,response:Response){sockets.add(webSocket);webSocket.send(JSONObject().put("type","status").put("peerOnline",true).toString())}
                override fun onMessage(webSocket:WebSocket,text:String){
                    val payload=codec.decode(text);val channel=payload.getString("channel")
                    channels[webSocket]=channel
                    if(payload.optString("type")=="hello"){send(webSocket,JSONObject().put("type","hello").put("channel",channel).put("desktopReady",true));return}
                    calls.add(payload)
                    if(ignoreMessages&&payload.optString("action")=="message.send")return
                    workers.execute{
                        try{val result=handle(payload.getString("action"),payload.getJSONObject("args"));send(webSocket,JSONObject().put("type","response").put("channel",channel).put("id",payload.getString("id")).put("result",result))}
                        catch(e:Exception){send(webSocket,JSONObject().put("type","response").put("channel",channel).put("id",payload.getString("id")).put("error",e.message))}
                    }
                }
            })}
            server.start()
            profile=JSONObject().put("relay",server.url("/").toString().trimEnd('/')).put("hostId",host).put("key",key).put("pin",Encoding.hex(MessageDigest.getInstance("SHA-256").digest(cert.certificate.encoded))).put("mobileToken",Encoding.secret()).put("approved",true).put("name","本地 UI 验收电脑")
        }
        // Serialize encryption and enqueueing together so test workers cannot reorder sequence numbers.
        private fun send(socket:WebSocket,payload:JSONObject)=synchronized(codec){socket.send(codec.encode(payload))}
        fun publish(){
            sockets.forEach{socket->channels[socket]?.let{channel->
                val event=JSONObject().put("type","thread.updated").put("data",snapshot("ui-0"))
                send(socket,JSONObject().put("type","event").put("channel",channel).put("event",event))
            }}
        }
        private fun snapshot(id:String):JSONObject{
            val thread=JSONObject(threads.optJSONObject(0).toString()).put("id",id).put("settings",JSONObject(settings.toString())).put("cwd","/workspace/codex_android")
            if(active)thread.put("activeTurnId","qa-active-turn")
            return JSONObject().put("thread",thread).put("messages",JSONArray(messages.toString())).put("artifacts",if(reference)JSONArray()else artifacts).put("historyComplete",true).put("history",JSONObject().put("total",messages.length()).put("hasEarlier",false)).put("requests",if(approval)JSONArray().put(JSONObject().put("id","ui-approval").put("method","item/commandExecution/requestApproval").put("params",JSONObject().put("command",if(reference)"./gradlew :app:testDebugUnitTest --tests \"*ReconnectAckTest\""else"npm test").put("reason","仅用于隔离界面验收").put("cwd","~/Projects/codex_android")))else JSONArray())
        }
        private fun handle(action:String,args:JSONObject):JSONObject=when(action){
            "projects.list"->JSONObject().put("projects",projects)
            "threads.list"->JSONObject().put("threads",threads)
            "models.list"->JSONObject().put("models",JSONArray().also{listOf("gpt-6.1-sol","gpt-6-sol","gpt-6-luna").forEach{id->it.put(JSONObject().put("id",id).put("reasoningEfforts",JSONArray(listOf("low","medium","high"))))}})
            "thread.read"->snapshot(args.getString("threadId"))
            "thread.create"->JSONObject().put("threadId","ui-created").put("hostId","local")
            "thread.settings.update"->{listOf("model","reasoningEffort","permissionMode").forEach{if(args.has(it))settings.put(it,args.get(it))};JSONObject().put("verified",true)}
            "message.send"->JSONObject().put("sent",true)
            "turn.stop"->{active=false;JSONObject().put("interrupted",true)}
            "message.read"->JSONObject().put("text","LOCAL UI TEST\n完整工具记录，来自隔离服务。").put("nextOffset",30).put("done",true)
            "approval.respond"->{approval=false;JSONObject().put("accepted",true)}
            "file.read"->{require(args.getString("threadId").startsWith("ui-"));val bytes=files[args.getString("artifactId")]!!;val offset=args.optInt("offset");val end=(offset+192*1024).coerceAtMost(bytes.size);JSONObject().put("data",android.util.Base64.encodeToString(bytes.copyOfRange(offset,end),android.util.Base64.NO_WRAP)).put("total",bytes.size).put("nextOffset",end).put("done",end==bytes.size)}
            "preview.open"->JSONObject().put("previewId","local-ui-preview")
            "preview.connect"->{require(args.getString("host") in listOf("localhost","127.0.0.1")&&args.getInt("port")==website.port);val id=UUID.randomUUID().toString();tcp[id]=Socket("127.0.0.1",website.port).apply{soTimeout=1500};JSONObject().put("socketId",id)}
            "preview.write"->{tcp[args.getString("socketId")]!!.getOutputStream().apply{write(android.util.Base64.decode(args.getString("data"),android.util.Base64.DEFAULT));flush()};JSONObject()}
            "preview.read"->{val buffer=ByteArray(192*1024);val count=try{tcp[args.getString("socketId")]!!.getInputStream().read(buffer)}catch(_:java.net.SocketTimeoutException){0};JSONObject().put("data",android.util.Base64.encodeToString(buffer.copyOf(count.coerceAtLeast(0)),android.util.Base64.NO_WRAP)).put("done",count<0)}
            "preview.disconnect"->{tcp.remove(args.getString("socketId"))?.close();JSONObject()}
            "preview.close"->{tcp.values.forEach{it.close()};tcp.clear();JSONObject()}
            else->error("Unexpected local UI test request: $action")
        }
        fun close(){sockets.forEach{it.close(1000,"Local UI test finished")};tcp.values.forEach{it.close()};workers.shutdownNow();server.shutdown();website.shutdown()}
    }
}
