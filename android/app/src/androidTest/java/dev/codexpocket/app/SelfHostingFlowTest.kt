package dev.codexpocket.app

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.*
import java.io.File

// Docker-deployed production relay, real TLS, real accounts, real companion and
// real desktop Codex. This test may submit ONLY to the explicitly authorized QA chat.
class SelfHostingFlowTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    private val args get()=InstrumentationRegistry.getArguments()
    private val context get()=compose.activity.applicationContext
    private lateinit var account:AccountModel
    private lateinit var pocket:PocketModel
    private lateinit var api:AccountApi
    private lateinit var saved:JSONObject
    private lateinit var accountFile:String
    private val pocketFile="qa-selfhost-flow.enc"
    @Before fun setup(){
        Assume.assumeTrue("Requires explicitly configured dedicated self-host QA environment",!args.getString("selfRelay").isNullOrBlank()&&!args.getString("selfPin").isNullOrBlank())
        api=AccountApi(JSONObject().put("relay",args.getString("selfRelay")).put("pin",args.getString("selfPin")))
        accountFile="pocket-account${AccountApi.suffix(context,api.defaults)}.enc";saved=SecureStore(context,accountFile).load();SecureStore(context,accountFile).save(JSONObject())
        SecureStore(context,pocketFile).save(JSONObject())
        compose.runOnUiThread{pocket=PocketModel(context,pocketFile);account=AccountModel(context,api){auth->if(auth!=null){pocket.authorizeAccount(auth);pocket.start()}else pocket.clearAccountAccess()};account.start()}
        compose.setContent{PocketTheme("light"){if(account.canUse)PocketApp(pocket,{},null,{},account)else AccountGate(account)}}
    }
    @After fun cleanup(){if(this::account.isInitialized)compose.runOnUiThread{account.close();pocket.stop()};if(this::saved.isInitialized)SecureStore(context,accountFile).save(saved);SecureStore(context,pocketFile).save(JSONObject())}
    private fun until(timeout:Long=20000,condition:()->Boolean){compose.waitUntil(timeout){var ready=false;compose.runOnUiThread{ready=condition()};ready}}
    @Test fun selfHostedRegistrationApprovalPairingImageTextAndReconnection(){
        val username="self_${System.currentTimeMillis()}";val password="Selfhost-user-password-077"
        val registration=api.post("/api/account/register",JSONObject().put("username",username).put("password",password).put("displayName","自建中继验收").put("reason","Docker 全链路验收"))
        val admin=api.post("/api/account/login",JSONObject().put("username",args.getString("selfUsername")).put("password",args.getString("selfPassword")).put("client","desktop").put("deviceId",Encoding.secret())).getString("token")
        compose.onNodeWithText("登录账号").performTextInput(username);compose.onNodeWithText("密码",substring=false).performTextInput(password)
        compose.onNodeWithText("登录",substring=false).performScrollTo().performClick();until{account.verified&&!account.busy}
        Assert.assertFalse(account.canUse);compose.onNodeWithText("等待审批").assertIsDisplayed()
        api.post("/api/admin/users/decision",JSONObject().put("userId",registration.getJSONObject("user").getString("id")).put("action","approve").put("days",7),admin)
        compose.onNodeWithText("检查审批状态").performScrollTo().performClick();until{account.canUse&&!account.busy}
        val pairing=JSONObject(args.getString("selfPairing")!!)
        compose.runOnUiThread{pocket.scannedPair(pairing.toString())};until{pocket.profile?.optBoolean("pairSubmitted")==true}
        compose.onNodeWithText("配对中，等待电脑确认").assertIsDisplayed();Assert.assertNull(pocket.error)
        val fingerprint=Encoding.hex(java.security.MessageDigest.getInstance("SHA-256").digest(pocket.profile!!.getString("mobileToken").toByteArray())).take(12)
        PocketConnection.post(JSONObject(pairing.toString()).put("accountToken",args.getString("selfDesktop")),"/api/pair/decision",JSONObject().put("hostId",pairing.getString("hostId")).put("fingerprint",fingerprint).put("accept",true))
        until{pocket.connected};compose.runOnUiThread{account.refresh()};until{!account.busy&&account.hosts.any{it.getString("id")==pairing.getString("hostId")}}
        val threadId=args.getString("selfThread")!!
        compose.runOnUiThread{pocket.openThread(JSONObject().put("id",threadId).put("hostId","local").put("title","Codex Pocket 验收测试"))};until{!pocket.loadingThread&&pocket.snapshot!=null}
        Assert.assertEquals("Codex Pocket 验收测试",pocket.snapshot!!.getJSONObject("thread").getString("title"))
        val file=File(context.cacheDir,"selfhost-blue.png");val bitmap=Bitmap.createBitmap(100,80,Bitmap.Config.ARGB_8888);bitmap.eraseColor(android.graphics.Color.BLUE);file.outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
        compose.runOnUiThread{pocket.addImages(listOf(Uri.fromFile(file)))};until{!pocket.busy&&pocket.imagesFor(threadId).size==1}
        val marker="POCKET_IMAGE_077_"+System.currentTimeMillis()
        compose.onNodeWithContentDescription("聊天输入框").performTextInput("继续用户授权的 Codex Pocket 验收测试。请描述附件主色，并在同一条回复中包含 $marker；不要调用工具，不要修改文件。")
        compose.onNodeWithContentDescription("发送").performClick();until(60000){!pocket.busy&&pocket.pendingOperation==null}
        until(120000){pocket.snapshot?.optJSONArray("messages").objects().any{it.optString("role")=="assistant"&&it.optString("text").contains(marker)}&&pocket.snapshot?.optJSONObject("thread")?.optString("activeTurnId") in listOf(null,"","null")}
        val messages=pocket.snapshot!!.getJSONArray("messages").objects();val user=messages.filter{it.optString("role")=="user"&&it.optString("text").contains(marker)}
        Assert.assertEquals(1,user.size);val image=user.single().getJSONArray("attachments").getJSONObject(0);Assert.assertTrue(image.getBoolean("available"))
        val reply=messages.last{it.optString("role")=="assistant"&&it.optString("text").contains(marker)}.getString("text");Assert.assertTrue("Codex must see the attached blue image",reply.contains("蓝"))
        val preview=runBlocking{pocket.attachmentBitmap(image)};Assert.assertTrue(preview.width>0)
        compose.runOnUiThread{pocket.stop();pocket.start()};until{pocket.connected&&!pocket.loadingThread}
        Assert.assertNull(pocket.pendingOperation);Assert.assertEquals(1,pocket.snapshot!!.getJSONArray("messages").objects().count{it.optString("role")=="user"&&it.optString("text").contains(marker)})
    }
}
