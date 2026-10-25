package dev.codexpocket.app
import android.net.Uri
import android.content.ContentValues
import android.graphics.Bitmap
import android.provider.MediaStore
import androidx.test.platform.app.InstrumentationRegistry
import androidx.compose.runtime.*
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import java.io.File
class FilesApprovalsTest {
 @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
 private val context get()=compose.activity.applicationContext
 private lateinit var model:PocketModel
 private var revision by mutableIntStateOf(0)
 private lateinit var peer:ConnectionRecoveryTest.Peer
 private val store="qa-files-approvals.enc"
 private val bytes=java.io.ByteArrayOutputStream()
 @Before fun setup(){SecureStore(context,store).save(JSONObject());peer=ConnectionRecoveryTest.Peer();peer.capabilities.put("file.upload",true);peer.extra={action,args->when(action){"file.upload.start"->JSONObject().put("nextOffset",bytes.size());"file.upload.chunk"->{Assert.assertEquals(bytes.size(),args.getInt("offset"));bytes.write(android.util.Base64.decode(args.getString("data"),0));JSONObject().put("nextOffset",bytes.size())};"file.upload.finish"->JSONObject().put("done",true);else->null}};SecureStore(context,store).save(JSONObject().put("profile",peer.profile));compose.runOnUiThread{model=PocketModel(context,store);revision++;model.start()};compose.setContent{PocketTheme("light"){key(revision){PocketApp(model,{},null,{})}}};until{model.connected&&model.supportsFiles};compose.runOnUiThread{model.openThread(JSONObject().put("id","local-network-test").put("hostId","local"))};until{!model.loadingThread}}
 @After fun cleanup(){compose.runOnUiThread{model.stop()};peer.close();SecureStore(context,store).save(JSONObject())}
 private fun until(condition:()->Boolean){compose.waitUntil(15000){var ok=false;compose.runOnUiThread{ok=condition()};ok}}
 private fun shot(name:String){compose.waitForIdle();val values=ContentValues().apply{put(MediaStore.Images.Media.DISPLAY_NAME,"features-$name.png");put(MediaStore.Images.Media.MIME_TYPE,"image/png");put(MediaStore.Images.Media.RELATIVE_PATH,"Pictures/CodexPocket-${BuildConfig.VERSION_NAME}");put(MediaStore.Images.Media.IS_PENDING,1)};val uri=context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,values)!!;context.contentResolver.openOutputStream(uri)!!.use{InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG,100,it)};context.contentResolver.update(uri,ContentValues().apply{put(MediaStore.Images.Media.IS_PENDING,0)},null,null)}
 @Test fun attachmentMenuMakesImageAndFilePickersAvailable(){
  compose.onNodeWithContentDescription("添加附件").performClick();compose.onNodeWithText("添加文件").assertIsDisplayed();compose.onNodeWithText("添加图片").assertIsDisplayed();compose.onNodeWithText("添加文件").assertIsEnabled()
 }
 @Test fun fileUploadAndDraftSurviveModelRecreation(){
  val file=File(context.cacheDir,"report.txt").apply{writeBytes(ByteArray(410000){(it%127).toByte()})}
  compose.runOnUiThread{model.addFiles(listOf(Uri.fromFile(file)))};until{!model.busy&&model.filesFor(model.imageDraftKey).size==1};val id=model.filesFor(model.imageDraftKey).single().id
  shot("files");compose.onNodeWithContentDescription("移除文件 report.txt").assertIsDisplayed()
  compose.runOnUiThread{model.stop();model=PocketModel(context,store);revision++;model.start()};until{model.connected&&model.supportsFiles};compose.runOnUiThread{model.openThread(JSONObject().put("id","local-network-test").put("hostId","local"))};until{!model.loadingThread};Assert.assertEquals(id,model.filesFor(model.imageDraftKey).single().id)
  compose.onNodeWithContentDescription("聊天输入框").performTextInput("查看文档");compose.onNodeWithContentDescription("发送").performClick();until{!model.busy&&peer.messages.size==1};Assert.assertArrayEquals(file.readBytes(),bytes.toByteArray());Assert.assertEquals("查看文档",peer.messages.single().getString("text"));Assert.assertEquals(id,peer.messages.single().getJSONArray("fileUploadIds").getString(0));Assert.assertTrue(model.filesFor(model.imageDraftKey).isEmpty());Assert.assertNull(model.pendingOperation)
 }
 private fun request(method:String,params:JSONObject){peer.snapshot(JSONObject().put("thread",JSONObject().put("id","local-network-test").put("hostId","local")).put("messages",JSONArray()).put("requests",JSONArray().put(JSONObject().put("id","approval-qa").put("method",method).put("params",params))).put("historyComplete",true));until{model.snapshot?.optJSONArray("requests")?.optJSONObject(0)?.optJSONObject("params")?.toString()==params.toString()}}
 @Test fun directoryPermissionsCanBeNarrowedAndSubmittedWithSessionScope(){
  request("item/permissions/requestApproval",JSONObject().put("reason","权限验收").put("permissions",JSONObject().put("network",JSONObject().put("enabled",true)).put("fileSystem",JSONObject().put("read",JSONArray().put("/project")))))
  compose.onNodeWithText("查看并处理").performClick();compose.onNodeWithText("权限申请").assertIsDisplayed();shot("permissions");compose.onAllNodes(isToggleable()).onFirst().performClick();compose.onNodeWithText("本次会话").performClick();compose.onNodeWithText("允许所选").performClick();until{peer.commands.any{it.optString("action")=="approval.respond"}}
  val result=peer.commands.last{it.optString("action")=="approval.respond"}.getJSONObject("args").getJSONObject("response");Assert.assertEquals("session",result.getString("scope"));Assert.assertFalse(result.getJSONObject("permissions").has("network"));Assert.assertEquals("/project",result.getJSONObject("permissions").getJSONObject("fileSystem").getJSONArray("read").getString(0))
 }
 @Test fun mcpFormSendsTypedFieldsAndUnsupportedVerificationStaysOnComputer(){
  request("mcpServer/elicitation/request",JSONObject().put("mode","form").put("serverName","QA").put("requestedSchema",JSONObject().put("type","object").put("required",JSONArray().put("count")).put("properties",JSONObject().put("count",JSONObject().put("title","数量").put("type","integer")))))
  compose.onNodeWithText("查看并处理").performClick();compose.onNodeWithContentDescription("数量 *").performTextInput("3");shot("mcp");compose.onNodeWithText("提交").performClick();until{peer.commands.any{it.optString("action")=="approval.respond"}};Assert.assertEquals(3,peer.commands.last{it.optString("action")=="approval.respond"}.getJSONObject("args").getJSONObject("response").getJSONObject("content").getInt("count"))
  until{!model.busy&&!model.loadingThread&&model.pendingOperation==null};request("mcpServer/elicitation/request",JSONObject().put("mode","openai/userVerification"));compose.onNodeWithText("此类官方身份验证或专用控件需要在电脑完成。").assertIsDisplayed();compose.onNodeWithText("查看详情").performClick();compose.onNodeWithText("提交").assertDoesNotExist();compose.onNodeWithContentDescription("关闭授权详情").performClick()
 }
 @Test fun titledMcpOptionsPreserveDefaultsAndTypedValues(){
  val properties=JSONObject().put("mode",JSONObject().put("type","string").put("oneOf",JSONArray().put(JSONObject().put("const","read").put("title","只读模式"))).put("default","read")).put("count",JSONObject().put("type","integer").put("default",0)).put("confirm",JSONObject().put("type","boolean").put("default",false)).put("scopes",JSONObject().put("type","array").put("items",JSONObject().put("anyOf",JSONArray().put(JSONObject().put("const","files").put("title","项目文件")))).put("default",JSONArray().put("files")))
  request("mcpServer/elicitation/request",JSONObject().put("mode","form").put("requestedSchema",JSONObject().put("type","object").put("properties",properties).put("required",JSONArray().put("mode").put("count").put("confirm").put("scopes"))))
  compose.onNodeWithText("查看并处理").performClick();compose.onNodeWithText("只读模式").assertIsDisplayed();compose.onNodeWithText("提交").performClick();until{peer.commands.any{it.optString("action")=="approval.respond"}};val c=peer.commands.last{it.optString("action")=="approval.respond"}.getJSONObject("args").getJSONObject("response").getJSONObject("content");Assert.assertEquals("read",c.getString("mode"));Assert.assertEquals(0,c.getInt("count"));Assert.assertFalse(c.getBoolean("confirm"));Assert.assertEquals("files",c.getJSONArray("scopes").getString(0))
 }
 @Test fun networkRuleOnlySubmitsTheOfferedObject(){
  val decision=JSONObject().put("applyNetworkPolicyAmendment",JSONObject().put("network_policy_amendment",JSONObject().put("action","allow").put("host","qa.example.invalid")))
  request("item/commandExecution/requestApproval",JSONObject().put("command","QA_NETWORK_ONLY").put("availableDecisions",JSONArray().put("decline").put(decision)))
  compose.onNodeWithText("查看详情").performClick();compose.onNodeWithText("保存允许规则 · qa.example.invalid").performClick();until{peer.commands.any{it.optString("action")=="approval.respond"}};Assert.assertEquals(decision.toString(),peer.commands.last{it.optString("action")=="approval.respond"}.getJSONObject("args").getJSONObject("decision").toString())
 }
 @Test fun nestedMcpFormAcceptsExplicitJsonAndDoesNotPretendIdentityVerification(){
  request("mcpServer/elicitation/request",JSONObject().put("mode","openai/form").put("requestedSchema",JSONObject().put("type","object").put("properties",JSONObject().put("nested",JSONObject().put("type","object").put("properties",JSONObject().put("value",JSONObject().put("type","integer")))))))
  compose.onNodeWithText("查看并处理").performClick();compose.onNodeWithText("表单 JSON").performTextReplacement("{\"nested\":{\"value\":2}}");compose.onNodeWithText("提交").performClick();until{peer.commands.any{it.optString("action")=="approval.respond"}};Assert.assertEquals(2,peer.commands.last{it.optString("action")=="approval.respond"}.getJSONObject("args").getJSONObject("response").getJSONObject("content").getJSONObject("nested").getInt("value"))
 }

}
