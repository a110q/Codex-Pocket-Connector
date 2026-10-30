package dev.codexpocket.app

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
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
import java.util.concurrent.TimeUnit

class ContactsTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var server:MockWebServer
    private lateinit var model:ContactsModel
    private lateinit var api:AccountApi
    private lateinit var defaults:JSONObject
    private var dark by mutableStateOf(false)
    @Before fun setup(){
        val cert=HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
        server=MockWebServer();server.useHttps(HandshakeCertificates.Builder().heldCertificate(cert).build().sslSocketFactory(),false);server.start()
        val pin=MessageDigest.getInstance("SHA-256").digest(cert.certificate.encoded).joinToString(""){"%02x".format(it)}
        api=AccountApi(JSONObject().put("relay",server.url("/").toString()).put("pin",pin))
        defaults=JSONObject(context.assets.open("contacts.json").bufferedReader().use{it.readText()})
        model=ContactsModel(context,api=api)
    }
    @After fun cleanup(){compose.runOnUiThread{model.close()};SecureStore(context,"pocket-contacts${AccountApi.suffix(context,api.defaults)}.enc").save(JSONObject());server.shutdown()}
    private fun show(admin:Boolean=false){compose.setContent{PocketTheme(if(dark)"dark"else"light"){ContactScreen(model,admin){}}}}
    private fun ready(){compose.waitUntil(15000){var ready=false;compose.runOnUiThread{ready=!model.busy};ready}}
    private fun enqueue(data:JSONObject=defaults){server.enqueue(MockResponse().setBody(JSONObject().put("contacts",data).toString()))}
    private fun shot(name:String){compose.waitForIdle();File(compose.activity.getExternalFilesDir(null),"$name.png").outputStream().use{InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG,100,it)}}
    @Test fun suppliedImagesShowEnlargeAndSaveWithoutLogin(){
        for(key in listOf("personalQr","groupQr")){
            val bitmap=contactBitmap(defaults.getString(key))!!;val pixels=IntArray(bitmap.width*bitmap.height);bitmap.getPixels(pixels,0,bitmap.width,0,0,bitmap.width,bitmap.height)
            val source=com.google.zxing.RGBLuminanceSource(bitmap.width,bitmap.height,pixels)
            val result=com.google.zxing.MultiFormatReader().decode(com.google.zxing.BinaryBitmap(com.google.zxing.common.HybridBinarizer(source)),mapOf(com.google.zxing.DecodeHintType.TRY_HARDER to true))
            Assert.assertTrue(result.text.isNotBlank());bitmap.recycle()
        }
        enqueue();show();ready()
        compose.onNodeWithText("个人微信",substring=false).performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("个人微信 二维码，点击放大").performScrollTo().performClick()
        compose.onNodeWithContentDescription("个人微信 二维码大图").assertIsDisplayed();shot("contacts-personal-large")
        compose.onNodeWithText("关闭大图").performClick()
        compose.onAllNodesWithText("保存到相册")[0].performScrollTo().performClick()
        compose.waitUntil(10000){compose.onAllNodesWithTag("contact-message").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithTag("contact-message").performScrollTo().assertTextEquals("二维码已保存到相册，可在微信扫一扫中从相册选择。")
        compose.onNodeWithText("微信用户群",substring=false).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("contact-expiry").performScrollTo().assertTextContains("2026-11-06",substring=true)
        shot("contacts-group-light");compose.runOnUiThread{dark=true};shot("contacts-group-dark")
        Assert.assertNull(server.takeRequest(2,TimeUnit.SECONDS)!!.getHeader("Authorization"))
    }
    @Test fun emptyConfigDoesNotLeakBundledContactsOnAnotherServer(){
        val empty=JSONObject(defaults.toString()).put("personalQr","").put("groupQr","").put("wechatId","")
        enqueue(empty);show();ready();compose.onNodeWithText("管理员尚未设置联系方式。").assertIsDisplayed()
        compose.onNodeWithContentDescription("个人微信 二维码，点击放大").assertDoesNotExist()
    }
    @Test fun oldServerDisablesAdminSaveAndCanRetry(){
        server.enqueue(MockResponse().setResponseCode(404).setBody("{\"error\":\"Not found\"}"));show(true);ready()
        compose.onNodeWithText("当前服务尚未升级联系方式管理功能。").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("保存联系方式").performScrollTo().assertIsNotEnabled()
        enqueue();compose.onNodeWithText("刷新联系方式").performScrollTo().performClick();ready()
        compose.onNodeWithText("保存联系方式").performScrollTo().assertIsEnabled();shot("contacts-admin")
    }
    @Test fun expiredGroupAndTextOnlyWechatRemainUsableAndCacheSurvivesNewModel(){
        val data=JSONObject(defaults.toString()).put("groupExpiresOn","2020-01-01").put("wechatId","pocket_owner").put("personalQr","")
        enqueue(data);show();ready()
        // Contact text remains available even when the personal image was removed.
        compose.onNodeWithText("微信号：pocket_owner").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("复制微信号").performScrollTo().performClick()
        compose.onNodeWithTag("contact-expiry").performScrollTo().assertTextEquals("群二维码已到期，请添加个人微信获取新群码。")
        val cached=ContactsModel(context,api=api);Assert.assertEquals("pocket_owner",cached.contacts.optString("wechatId"));cached.close()
    }
    @Test fun adminEditsSaveThroughAuthenticatedApiAndAnotherViewerGetsTheChange(){
        var published=defaults
        var savedToken:String?=null
        val token=Encoding.secret()
        val user=JSONObject().put("id","contact-owner").put("username","owner").put("role","admin").put("accessState","active")
        server.dispatcher=object:Dispatcher(){override fun dispatch(request:RecordedRequest):MockResponse {
            val result=when(request.path){
                "/api/account/login"->JSONObject().put("token",token).put("user",user)
                "/api/account/me"->JSONObject().put("user",user)
                "/api/admin/users","/api/admin/events"->JSONObject()
                "/api/account/contacts"->JSONObject().put("contacts",published)
                "/api/admin/contacts"->{savedToken=request.getHeader("Authorization");published=JSONObject(request.body.readUtf8()).put("updatedAt",1234567);JSONObject().put("contacts",published)}
                else->JSONObject()
            };return MockResponse().setBody(result.toString())
        }}
        val account=AccountModel(context,api)
        try{
            compose.runOnUiThread{account.login("owner","contacts-ui-test-password")}
            compose.waitUntil(15000){var done=false;compose.runOnUiThread{done=account.isAdmin&&!account.busy};done}
            compose.runOnUiThread{model.close();model=ContactsModel(context,account)}
            show(true);ready()
            compose.onNodeWithText("页面标题").performScrollTo().performTextReplacement("欢迎来交流")
            androidx.test.espresso.Espresso.closeSoftKeyboard()
            compose.waitForIdle()
            compose.onNodeWithText("保存联系方式").performScrollTo().performClick()
            compose.waitUntil(15000){var done=false;compose.runOnUiThread{done=model.message!=null||model.error!=null};done}
            Assert.assertNull(model.error,model.error)
            compose.onNodeWithText("联系方式已保存，用户重新打开页面即可看到。").performScrollTo().assertIsDisplayed()
            Assert.assertEquals("Bearer $token",savedToken)
            Assert.assertEquals("欢迎来交流",api.post("/api/account/contacts").getJSONObject("contacts").getString("title"))
        }finally{compose.runOnUiThread{account.close()};SecureStore(context,"pocket-account${AccountApi.suffix(context,api.defaults)}.enc").save(JSONObject())}
    }
}
