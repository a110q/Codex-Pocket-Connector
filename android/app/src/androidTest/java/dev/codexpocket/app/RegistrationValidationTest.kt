package dev.codexpocket.app

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import okhttp3.mockwebserver.*
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.json.JSONObject
import org.junit.*
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList

class RegistrationValidationTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var server:MockWebServer
    private lateinit var account:AccountModel
    private val registrations=CopyOnWriteArrayList<JSONObject>()
    @Before fun setup(){
        val cert=HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
        server=MockWebServer();server.useHttps(HandshakeCertificates.Builder().heldCertificate(cert).build().sslSocketFactory(),false)
        server.dispatcher=object:Dispatcher(){override fun dispatch(request:RecordedRequest):MockResponse {
            if(request.path=="/api/account/register")registrations.add(JSONObject(request.body.readUtf8()))
            return MockResponse().setBody("{}")
        }}
        server.start()
        val pin=MessageDigest.getInstance("SHA-256").digest(cert.certificate.encoded).joinToString(""){"%02x".format(it)}
        account=AccountModel(context,AccountApi(JSONObject().put("relay",server.url("/").toString()).put("pin",pin)))
        compose.setContent{PocketTheme("light"){AccountGate(account)}}
        compose.onNodeWithText("没有账号，申请使用").performScrollTo().performClick()
        compose.onNodeWithText("登录账号",substring=false).performScrollTo().performTextInput("new_user")
        compose.onNodeWithText("你的称呼").performScrollTo().performTextInput("新用户")
        compose.onNodeWithText("申请说明").performScrollTo().performTextInput("申请使用")
    }
    @After fun cleanup(){
        compose.runOnUiThread{account.close()}
        SecureStore(context,"pocket-account${AccountApi.suffix(context,account.api.defaults)}.enc").save(JSONObject())
        server.shutdown()
    }
    private fun password(value:String){compose.onNodeWithText("密码",substring=false).performScrollTo().performTextReplacement(value);androidx.test.espresso.Espresso.closeSoftKeyboard()}
    private fun submit(){compose.onNodeWithText("提交申请").performScrollTo().assertIsEnabled().performClick()}
    @Test fun shortPasswordExplainsFailureAndCorrectedPasswordSubmits(){
        password("123456789");submit()
        compose.onNodeWithTag("account-password-hint",useUnmergedTree=true).performScrollTo().assertTextEquals("密码至少需要 10 个字符，当前 9 个")
        Assert.assertTrue(registrations.isEmpty())
        File(compose.activity.getExternalFilesDir(null),"registration-password-error.png").outputStream().use{InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG,100,it)}
        password("1234567890");submit()
        compose.waitUntil(15000){var done=false;compose.runOnUiThread{done=account.notice!=null};done}
        Assert.assertEquals(1,registrations.size)
        Assert.assertEquals("1234567890",registrations.single().getString("password"))
        compose.onNodeWithText("申请已提交，请登录查看审批进度").performScrollTo().assertIsDisplayed()
    }
    @Test fun longPasswordIsExplainedWithoutSilentlyTruncating(){
        password("a".repeat(129));submit()
        compose.onNodeWithTag("account-password-hint",useUnmergedTree=true).performScrollTo().assertTextEquals("密码最多支持 128 个字符，当前 129 个")
        Assert.assertTrue(registrations.isEmpty())
        password("a".repeat(128));submit()
        compose.waitUntil(15000){registrations.isNotEmpty()}
        Assert.assertEquals(128,registrations.single().getString("password").length)
    }
}
