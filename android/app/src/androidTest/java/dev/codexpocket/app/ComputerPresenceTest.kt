package dev.codexpocket.app

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import okhttp3.mockwebserver.*
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicReference

class ComputerPresenceTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var server:MockWebServer
    private lateinit var account:AccountModel
    private lateinit var defaults:JSONObject
    private var dark by mutableStateOf(false)
    private var stale by mutableStateOf(false)
    private val online=AtomicReference<Boolean?>(false)
    private val token=Encoding.secret()
    @Before fun setup(){
        val cert=HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
        server=MockWebServer();server.useHttps(HandshakeCertificates.Builder().heldCertificate(cert).build().sslSocketFactory(),false);server.start()
        val pin=MessageDigest.getInstance("SHA-256").digest(cert.certificate.encoded).joinToString(""){"%02x".format(it)}
        defaults=JSONObject().put("relay",server.url("/").toString()).put("pin",pin)
        val owner=JSONObject().put("id","owner").put("username","owner").put("role","admin").put("accessState","active")
        server.dispatcher=object:Dispatcher(){override fun dispatch(request:RecordedRequest):MockResponse{
            val computer=JSONObject().put("id","computer-a").put("name","办公室电脑")
            online.get()?.let{computer.put("online",it)}
            val result=when(request.path){
                "/api/account/login"->JSONObject().put("token",token).put("user",owner)
                "/api/account/me"->JSONObject().put("user",owner)
                "/api/admin/users"->JSONObject().put("users",JSONArray().put(JSONObject().put("id","visitor").put("displayName","测试用户").put("username","visitor").put("accessState","active").put("role","user").put("reason","在线状态验收").put("computers",JSONArray().put(computer))))
                else->JSONObject()
            };return MockResponse().setBody(result.toString())
        }}
        compose.runOnUiThread{account=AccountModel(context,AccountApi(defaults));account.login("owner","presence-test-password")}
        until{account.isAdmin&&account.usersVerified&&!account.busy}
        compose.setContent{PocketTheme(if(dark)"dark"else"light"){
            Surface(Modifier.fillMaxSize(),color=MaterialTheme.colorScheme.background){
                if(stale)ComputerOnlineIndicator(JSONObject().put("id","stale").put("online",true),fresh=false)
                else AdminScreen(account,"all")
            }
        }}
    }
    @After fun cleanup(){compose.runOnUiThread{account.close()};SecureStore(context,"pocket-account${AccountApi.suffix(context,defaults)}.enc").save(JSONObject());server.shutdown()}
    private fun until(condition:()->Boolean){compose.waitUntil(20000){var done=false;compose.runOnUiThread{done=condition()};done}}
    private fun refresh(expected:Boolean?){online.set(expected);compose.runOnUiThread{account.refresh()};until{!account.busy&&account.usersVerified&&account.users.firstOrNull()?.optJSONArray("computers")?.optJSONObject(0)?.opt("online")==expected}}
    private fun shot(name:String){compose.waitForIdle();File(compose.activity.getExternalFilesDir(null),"$name.png").outputStream().use{InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG,100,it)}}
    @Test fun sameComputerRowChangesFromOfflineToOnlineAndBackWithoutLeavingTheList(){
        compose.onNodeWithContentDescription("电脑离线").performScrollTo().assertIsDisplayed()
        compose.runOnUiThread{account.start()}
        until{account.usersVerified&&account.users.firstOrNull()?.optJSONArray("computers")?.optJSONObject(0)?.opt("online")==false}
        online.set(true)
        until{account.usersVerified&&account.users.firstOrNull()?.optJSONArray("computers")?.optJSONObject(0)?.opt("online")==true}
        compose.onNodeWithContentDescription("电脑在线").performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("电脑离线").assertDoesNotExist();shot("computer-presence-online-light")
        compose.runOnUiThread{dark=true};shot("computer-presence-online-dark")
        refresh(false);compose.onNodeWithContentDescription("电脑离线").performScrollTo().assertIsDisplayed();compose.onNodeWithContentDescription("电脑在线").assertDoesNotExist()
    }
    @Test fun oldServerAndUnverifiedCachedFlagsNeverShowAnOnlineDot(){
        refresh(null);compose.onNodeWithContentDescription("电脑在线状态未知").performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("电脑离线").assertDoesNotExist()
        compose.runOnUiThread{stale=true}
        compose.onNodeWithContentDescription("电脑在线状态未知").assertIsDisplayed();compose.onNodeWithContentDescription("电脑在线").assertDoesNotExist()
    }
}
