package dev.codexpocket.app

import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.*

class ServiceRoutingTest {
    @get:Rule val compose=createEmptyComposeRule()
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private val backups=mutableMapOf<String,JSONObject>()
    private var previousLan:Boolean?=null
    private var scenario:ActivityScenario<MainActivity>?=null
    private fun backup(file:String){backups[file]=SecureStore(context,file).load()}
    @After fun cleanup(){scenario?.close();backups.forEach{(file,value)->SecureStore(context,file).save(value)};previousLan?.let{context.getSharedPreferences("connection-mode",0).edit().putBoolean("lan",it).commit()}}
    @Test fun scanningCurrentPublicServiceFromLanReturnsToPublicLogin(){
        val preferences=context.getSharedPreferences("connection-mode",0)
        previousLan=preferences.getBoolean("lan",false)
        listOf("pocket-service.enc","pocket-account.enc","pocket-state.enc","pocket-lan-state.enc").forEach{backup(it);SecureStore(context,it).save(JSONObject())}
        preferences.edit().putBoolean("lan",true).commit()
        val bundled=AccountApi.bundled(context)
        val pair=JSONObject(InstrumentationRegistry.getArguments().getString("servicePairing")!!)
            .put("relay",bundled.getString("relay")).put("pin",bundled.getString("pin"))
            .put("expiresAt",System.currentTimeMillis()+600000)
        scenario=ActivityScenario.launch(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        context.startActivity(Intent(context,MainActivity::class.java).setData(Uri.parse("codexpocket://pair?data="+Encoding.encode(pair.toString().toByteArray()))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        compose.waitUntil(10000){compose.onAllNodesWithText("欢迎回来").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithText("默认公网服务").assertIsDisplayed()
        compose.onNodeWithText("自建中继 / 切换服务").assertIsDisplayed()
        Assert.assertFalse("A public QR must leave LAN mode without bypassing login",preferences.getBoolean("lan",true))
    }
    @Test fun scanningAnotherServerRequiresConfirmationAndNeverReusesBuiltinCredentials(){
        val args=InstrumentationRegistry.getArguments()
        val pair=JSONObject(args.getString("servicePairing")!!).put("pairCode",Encoding.secret()).put("expiresAt",System.currentTimeMillis()+600000)
        backup("pocket-service.enc");backup("pocket-account.enc");backup("pocket-state.enc")
        val custom="pocket-account${AccountApi.suffix(context,pair)}.enc";backup(custom);backup("pocket-state${AccountApi.suffix(context,pair)}.enc")
        SecureStore(context,"pocket-service.enc").save(JSONObject());SecureStore(context,"pocket-account.enc").save(JSONObject());SecureStore(context,custom).save(JSONObject())
        val intent=Intent(context,MainActivity::class.java).setData(Uri.parse("codexpocket://pair?data="+Encoding.encode(pair.toString().toByteArray()))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        scenario=ActivityScenario.launch(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        context.startActivity(intent)
        compose.waitUntil(10000){compose.onAllNodesWithText("连接这个中继服务？").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithText("连接这个中继服务？").assertIsDisplayed()
        val sentinel="builtin-local-test-token-never-forward"
        SecureStore(context,"pocket-account.enc").save(JSONObject().put("token",sentinel))
        compose.onNodeWithText("切换并登录").performClick()
        compose.onNodeWithText("自建中继：${pair.getString("relay")}").assertIsDisplayed()
        compose.onNodeWithText("欢迎回来").assertIsDisplayed()
        Assert.assertEquals(pair.getString("relay"),AccountApi.defaults(context).getString("relay"))
        Assert.assertEquals(sentinel,SecureStore(context,"pocket-account.enc").load().getString("token"))
        Assert.assertTrue(SecureStore(context,custom).load().optString("token").isBlank())
        SecureStore(context,"pocket-account.enc").save(JSONObject())
        compose.onNodeWithText("返回默认公网服务").performClick()
        compose.onNodeWithText("默认公网服务").assertIsDisplayed()
        Assert.assertTrue(AccountApi.same(AccountApi.defaults(context),AccountApi.bundled(context)))
    }
}
