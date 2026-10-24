package dev.codexpocket.app

import android.graphics.Bitmap
import android.content.ContentValues
import android.provider.MediaStore
import androidx.compose.runtime.*
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.json.JSONArray
import org.junit.*
import java.io.File

// Real AccountApi calls reach an isolated instance of the production Node relay
// through adb reverse. No server response or authorization state is mocked.
class AccountFlowTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    private val instrument get()=InstrumentationRegistry.getInstrumentation()
    private val context get()=instrument.targetContext
    private lateinit var backup:JSONObject
    private lateinit var pocketBackup:JSONObject
    private lateinit var api:AccountApi
    private lateinit var account:AccountModel
    private lateinit var pocket:PocketModel
    private lateinit var lanPocket:PocketModel
    private var local by mutableStateOf(false)
    private val password="Local-ui-test-password-060"
    @Before fun setup(){
        val args=InstrumentationRegistry.getArguments()
        Assert.assertNotNull("Start the isolated account-ui-server fixture and pass accountRelay",args.getString("accountRelay"))
        api=AccountApi(JSONObject().put("relay",args.getString("accountRelay")).put("pin",args.getString("accountPin")))
        val store=SecureStore(context,"pocket-account.enc");backup=store.load();store.save(JSONObject().put("deviceId",args.getString("accountDevice")))
        pocketBackup=SecureStore(context).load();SecureStore(context).save(JSONObject().put("appearance","light"))
        compose.runOnUiThread{pocket=PocketModel(context);account=AccountModel(context,api){auth->if(auth!=null)pocket.authorizeAccount(auth)else pocket.clearAccountAccess()};lanPocket=PocketModel(context,"qa-lan.enc",true);lanPocket.forget()}
        compose.setContent{PocketTheme("light"){if(local)PocketApp(lanPocket,{},null,{},onExitLan={lanPocket.stop();local=false})else if(account.canUse)PocketApp(pocket,{},null,{},account)else AccountGate(account){local=true;lanPocket.start()}}}
    }
    @After fun cleanup(){try{compose.runOnUiThread{account.stop();pocket.stop();lanPocket.stop()}}finally{SecureStore(context,"pocket-account.enc").save(backup);SecureStore(context).save(pocketBackup)}}
    private fun until(condition:()->Boolean){compose.waitUntil(15000){var value=false;compose.runOnUiThread{value=condition()};value}}
    private fun shot(name:String){compose.waitForIdle();val values=ContentValues().apply{put(MediaStore.Images.Media.DISPLAY_NAME,"account-$name-${System.currentTimeMillis()}.png");put(MediaStore.Images.Media.MIME_TYPE,"image/png");put(MediaStore.Images.Media.RELATIVE_PATH,"Pictures/CodexPocket-${BuildConfig.VERSION_NAME}");put(MediaStore.Images.Media.IS_PENDING,1)};val uri=context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,values)!!;context.contentResolver.openOutputStream(uri)!!.use{instrument.uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG,100,it)};context.contentResolver.update(uri,ContentValues().apply{put(MediaStore.Images.Media.IS_PENDING,0)},null,null)}
    @Test fun realRegistrationApprovalRoleMenusSecurityAlertAndDisable(){
        val username="native_${System.currentTimeMillis()}"
        shot("01-login")
        compose.onNodeWithText("没有账号，申请使用").performScrollTo().performClick()
        compose.onNodeWithText("登录账号").performTextInput(username)
        compose.onNodeWithText("密码",substring=false).performTextInput(password)
        compose.onNodeWithText("你的称呼").performScrollTo().performTextInput("原生界面验收用户")
        compose.onNodeWithText("申请说明").performScrollTo().performTextInput("仅使用本地隔离中继验证申请和角色权限")
        compose.onNodeWithText("提交申请").performScrollTo().performClick()
        until{account.notice!=null&&!account.busy}
        compose.onNodeWithText("已有账号，前往登录").performScrollTo().performClick()
        compose.onNodeWithText("登录",substring=false).performScrollTo().performClick()
        until{account.verified&&account.user?.optString("accessState")=="pending"}
        shot("02-pending")
        val id=account.user!!.getString("id")
        val admin=api.post("/api/account/login",JSONObject().put("username","owner").put("password",password).put("client","desktop").put("deviceId",Encoding.secret())).getString("token")
        api.post("/api/admin/users/decision",JSONObject().put("userId",id).put("action","approve").put("days",7),admin)
        compose.onNodeWithText("检查审批状态").performScrollTo().performClick();until{account.canUse&&!account.busy}
        compose.onNodeWithContentDescription("打开设置").performClick()
        compose.onNodeWithText("管理中心").assertDoesNotExist()
        compose.onNodeWithText("账号：$username").assertIsDisplayed()
        shot("03-user-settings")
        val token=SecureStore(context,"pocket-account.enc").load().getString("token")
        api.post("/api/account/security/report",JSONObject().put("certificate","b".repeat(64)).put("version","0.6.0-ui-test").put("signals",JSONArray().put("signature_mismatch")),token)
        compose.runOnUiThread{account.logout()};until{!account.loggedIn}
        compose.runOnUiThread{account.login("owner",password)};until{account.isAdmin&&!account.busy}
        compose.onNodeWithContentDescription("打开设置").performClick()
        compose.onNodeWithText("管理中心").performScrollTo().performClick()
        compose.onNodeWithText("安全提醒",substring=false).performClick()
        compose.onNodeWithText("客户端签名与正式发布证书不一致").assertIsDisplayed()
        shot("04-admin-alert")
        compose.onNodeWithText("停用此账号").performScrollTo().performClick()
        compose.onNodeWithText("确认",substring=false).performClick()
        until{!account.busy&&account.users.any{it.optString("id")==id&&it.optString("accessState")=="disabled"}}
        try{api.post("/api/account/me",token=token);Assert.fail("Logged-out session must remain revoked")}catch(e:AccountException){Assert.assertEquals(401,e.status)}
        val disabled=api.post("/api/account/login",JSONObject().put("username",username).put("password",password).put("client","desktop").put("deviceId",Encoding.secret()))
        Assert.assertEquals("disabled",disabled.getJSONObject("user").getString("accessState"))
        compose.onNodeWithText("标记已处理").performScrollTo().performClick()
        until{!account.busy&&account.alerts==0}
        compose.onNodeWithText("全部账号").performClick();shot("05-admin-users")
        compose.runOnUiThread{account.logout()};until{!account.loggedIn}
    }
    @Test fun publicPairingClaimsFromThePhoneAndSurvivesAccountRefreshWithoutDesktopLogin(){
        val args=InstrumentationRegistry.getArguments();val pairing=args.getString("cloudPairing")!!
        val username="claim_${System.currentTimeMillis()}"
        val registered=api.post("/api/account/register",JSONObject().put("username",username).put("password",password).put("displayName","手机绑定验收").put("reason","隔离公网配对测试"))
        compose.runOnUiThread{account.login(username,password);pocket.start()}
        until{account.verified&&!account.busy}
        compose.runOnUiThread{pocket.pair(pairing)}
        Assert.assertNull(pocket.profile)
        Assert.assertTrue(pocket.error!!.contains("登录已批准"))
        val admin=api.post("/api/account/login",JSONObject().put("username","owner").put("password",password).put("client","desktop").put("deviceId",Encoding.secret())).getString("token")
        api.post("/api/admin/users/decision",JSONObject().put("userId",registered.getJSONObject("user").getString("id")).put("action","approve").put("days",7),admin)
        compose.runOnUiThread{account.refresh()};until{account.canUse&&!account.busy}
        Assert.assertNull(account.hostId)
        // Opening the scanner stops MainActivity and temporarily revokes its
        // in-memory grant. The result can arrive before /me finishes on resume.
        compose.runOnUiThread{account.stop();pocket.scannedPair(pairing)}
        Assert.assertNull(pocket.profile);Assert.assertNull(pocket.error)
        compose.runOnUiThread{account.refresh()};until{account.canUse&&!account.busy}
        until{pocket.profile!=null};compose.runOnUiThread{pocket.start()};until{!pocket.busy&&pocket.profile!=null}
        Assert.assertNull(pocket.error)
        compose.runOnUiThread{account.refresh()};until{!account.busy}
        Assert.assertNotNull("An unclaimed account refresh must not erase a pending phone pairing",pocket.profile)
        val mobile=pocket.profile!!.getString("mobileToken")
        val fingerprint=Encoding.hex(java.security.MessageDigest.getInstance("SHA-256").digest(mobile.toByteArray())).take(12)
        PocketConnection.post(JSONObject(pairing).put("accountToken",args.getString("cloudDesktop")),"/api/pair/decision",JSONObject().put("hostId",JSONObject(pairing).getString("hostId")).put("fingerprint",fingerprint).put("accept",true))
        until{pocket.connected}
        compose.runOnUiThread{account.refresh()};until{!account.busy&&account.hostId==JSONObject(pairing).getString("hostId")}
        Assert.assertTrue(pocket.connected)
        Assert.assertEquals(mobile,pocket.profile!!.getString("mobileToken"))
        shot("07-phone-claims-computer")
        val token=SecureStore(context,"pocket-account.enc").load().getString("token")
        val current=api.post("/api/account/me",token=token);Assert.assertEquals(account.hostId,current.getJSONObject("host").getString("id"))
    }
    @Test fun encryptedStoresRemainIndependentAndIntegrityReadsActualInstalledSignature(){
        val a=SecureStore(context,"qa-a.enc");val b=SecureStore(context,"qa-b.enc")
        val first=Thread{repeat(30){a.save(JSONObject().put("a",it))}};val second=Thread{repeat(30){b.save(JSONObject().put("b",it))}}
        first.start();second.start();first.join();second.join();Assert.assertEquals(29,a.load().getInt("a"));Assert.assertEquals(29,b.load().getInt("b"))
        val actual=AppIntegrity.report(context,"");Assert.assertEquals(64,actual.getString("certificate").length)
        val mismatch=AppIntegrity.report(context,"0".repeat(64));Assert.assertTrue(mismatch.getJSONArray("signals").toString().contains("signature_mismatch"))
    }
    @Test fun lanEntryPairsAndConnectsWithoutLoginAndRejectsPublicAddresses(){
        val args=InstrumentationRegistry.getArguments();val pairing=args.getString("lanPairing")!!
        val config=JSONObject(pairing);Assert.assertTrue(PocketConnection.isLanPairing(config))
        Assert.assertFalse(PocketConnection.isLanPairing(JSONObject(pairing).put("relay","https://203.0.113.10:24443")))
        Assert.assertFalse(PocketConnection.isLanPairing(JSONObject(pairing).put("relay","https://192.168.1.300:24444")))
        compose.onNodeWithText("局域网直连 · 无需账号审批").performScrollTo().performClick()
        compose.runOnUiThread{lanPocket.pair(pairing)}
        until{!lanPocket.busy&&lanPocket.profile!=null}
        Assert.assertNull(lanPocket.error)
        Assert.assertFalse(account.loggedIn)
        val mobile=lanPocket.profile!!.getString("mobileToken")
        val fingerprint=Encoding.hex(java.security.MessageDigest.getInstance("SHA-256").digest(mobile.toByteArray())).take(12)
        val approval=JSONObject(pairing).put("accountToken",args.getString("lanDesktop"))
        PocketConnection.post(approval,"/api/pair/decision",JSONObject().put("hostId",config.getString("hostId")).put("fingerprint",fingerprint).put("accept",true))
        until{lanPocket.connected}
        compose.onNodeWithContentDescription("打开设置").performClick()
        compose.onNodeWithText("管理中心").assertDoesNotExist();compose.onNodeWithText("切换到公网远程登录").assertIsDisplayed()
        shot("06-lan-connected")
        compose.onNodeWithText("切换到公网远程登录").performClick()
        compose.onNodeWithText("欢迎回来").assertIsDisplayed();Assert.assertFalse(lanPocket.connected)
    }
}
