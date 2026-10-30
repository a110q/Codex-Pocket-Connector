package dev.codexpocket.app

import android.content.Intent
import android.graphics.Bitmap
import android.util.Base64
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import okio.Buffer
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

class AppUpdatesTest{
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var server:MockWebServer
    private lateinit var model:AppUpdateModel
    private lateinit var config:UpdateConfig
    private lateinit var signing:Ed25519PrivateKeyParameters
    private var dark=androidx.compose.runtime.mutableStateOf(false)
    private fun ByteArray.hash()=MessageDigest.getInstance("SHA-256").digest(this).joinToString(""){"%02x".format(it)}
    private val apk get()=File(context.applicationInfo.sourceDir).readBytes()
    @Before fun setup(){
        val cert=HeldCertificate.Builder().addSubjectAlternativeName("localhost").addSubjectAlternativeName("127.0.0.1").build()
        val tls=HandshakeCertificates.Builder().heldCertificate(cert).build()
        server=MockWebServer();server.useHttps(tls.sslSocketFactory(),false);server.start()
        signing=Ed25519PrivateKeyParameters(SecureRandom())
        val prefix=byteArrayOf(0x30,0x2a,0x30,0x05,0x06,0x03,0x2b,0x65,0x70,0x03,0x21,0x00)
        val spki=prefix+signing.generatePublicKey().encoded
        config=UpdateConfig.parse(JSONObject().put("manifestUrl",server.url("/updates/stable.json").toString()).put("pin",cert.certificate.encoded.hash()).put("keyId",spki.hash().take(16)).put("publicKey","-----BEGIN PUBLIC KEY-----\n"+Base64.encodeToString(spki,Base64.NO_WRAP)+"\n-----END PUBLIC KEY-----"))
        model=AppUpdateModel(context,UpdateClient(config),"0.9.9.3",35)
        compose.setContent{PocketTheme(if(dark.value)"dark"else"light"){UpdateScreen(model){}}}
    }
    @After fun cleanup(){compose.runOnUiThread{model.close()};server.shutdown();File(context.cacheDir,"updates/0.9.10").deleteRecursively()}
    private fun payload(version:String="0.9.10",bytes:ByteArray=byteArrayOf(1,2,3),build:Int=36)=JSONObject().put("schema",1).put("channel","stable").put("version",version).put("build",build).put("publishedAt","2026-10-30T00:00:00Z").put("notes","新增检测更新。\n下载后保留原账号和配对信息。").put("assets",JSONArray().put(JSONObject().put("platform","android").put("kind","apk").put("name","Codex-Pocket-$version.apk").put("url",server.url("/packages/$version/Codex-Pocket-$version.apk").toString()).put("size",bytes.size).put("sha256",bytes.hash())))
    private fun envelope(payload:JSONObject):JSONObject{
        val data=payload.toString().toByteArray();val signer=Ed25519Signer();signer.init(true,signing);signer.update(data,0,data.size)
        return JSONObject().put("schema",1).put("algorithm","Ed25519").put("keyId",config.keyId).put("payload",Base64.encodeToString(data,Base64.NO_WRAP)).put("signature",Base64.encodeToString(signer.generateSignature(),Base64.NO_WRAP))
    }
    private fun enqueue(payload:JSONObject=payload()){server.enqueue(MockResponse().setHeader("Content-Type","application/json").setBody(envelope(payload).toString()))}
    private fun until(timeout:Long=15000,condition:()->Boolean){
        try{compose.waitUntil(timeout){var done=false;compose.runOnUiThread{done=condition()};done}}
        catch(e:Exception){throw AssertionError("Update state: status=${model.status}, download=${model.download}, received=${model.received}, error=${model.error}",e)}
    }
    private fun click(label:String){compose.onNodeWithText(label,useUnmergedTree=true).performScrollTo().performClick()}
    private fun screenshot(name:String){compose.waitForIdle();Thread.sleep(350);File(compose.activity.getExternalFilesDir(null),"$name.png").outputStream().use{InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG,100,it)}}

    @Test fun availableVersionShowsNotesAndDoesNotSendAccountCredentials(){
        enqueue();click("检测更新");until{model.status=="available"}
        compose.onNodeWithTag("update-current").assertTextEquals("0.9.9.3");compose.onNodeWithTag("update-latest").assertTextEquals("0.9.10")
        compose.onNodeWithText("发现新版本 0.9.10").assertIsDisplayed();compose.onNodeWithText("新增检测更新。\n下载后保留原账号和配对信息。").assertIsDisplayed()
        val request=server.takeRequest(2,TimeUnit.SECONDS)!!;Assert.assertNull(request.getHeader("Authorization"));Assert.assertEquals("/updates/stable.json",request.path)
        screenshot("update-android-available-light");compose.runOnUiThread{dark.value=true};compose.waitForIdle();screenshot("update-android-available-dark")
    }
    @Test fun latestVersionIsExplicitAndHasNoDownloadAction(){
        enqueue(payload("0.9.9.3",build=35));click("检测更新");until{model.status=="current"}
        compose.onNodeWithText("当前已是最新版本。").assertIsDisplayed();compose.onNodeWithText("下载安卓更新").assertDoesNotExist();screenshot("update-android-current")
    }
    @Test fun failedServiceAndTamperedSignatureCanRetryWithoutOfferingAnUnverifiedDownload(){
        server.enqueue(MockResponse().setResponseCode(503));click("检测更新");until{model.status=="error"}
        compose.onNodeWithTag("update-error").assertExists();screenshot("update-android-failure")
        val tampered=envelope(payload()).put("signature",Base64.encodeToString(ByteArray(64),Base64.NO_WRAP))
        server.enqueue(MockResponse().setBody(tampered.toString()));click("检测更新");until{model.status=="error"&&model.error?.contains("验签失败")==true}
        compose.onNodeWithText("下载安卓更新").assertDoesNotExist()
        enqueue();click("检测更新");until{model.status=="available"};Assert.assertNull(model.error)
    }
    @Test fun verifiedApkDownloadProducesARestrictedContentUriForTheSystemInstaller(){
        val bytes=apk;enqueue(payload(bytes=bytes));server.enqueue(MockResponse().setBody(Buffer().write(bytes)))
        click("检测更新");until{model.status=="available"};click("下载安卓更新");until(60000){model.download=="ready"||model.download=="error"}
        Assert.assertEquals(model.error,"ready",model.download);compose.onNodeWithText("安装更新").performScrollTo().assertIsDisplayed()
        val intent=model.installIntent();Assert.assertEquals(Intent.ACTION_VIEW,intent.action);Assert.assertEquals("content",intent.data!!.scheme);Assert.assertEquals(context.packageName+".updates",intent.data!!.authority);Assert.assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION!=0)
        Assert.assertEquals(bytes.size,context.contentResolver.openInputStream(intent.data!!)!!.use{it.readBytes().size})
        screenshot("update-android-install-ready")
    }
    @Test fun corruptDownloadIsRemovedAndAValidRetryCanSucceed(){
        val bytes=apk;enqueue(payload(bytes=bytes));server.enqueue(MockResponse().setBody("broken"))
        click("检测更新");until{model.status=="available"};click("下载安卓更新");until{model.download=="error"}
        Assert.assertFalse(File(context.cacheDir,"updates/0.9.10/Codex-Pocket-0.9.10.apk.partial").exists());compose.onNodeWithText("安装更新").assertDoesNotExist()
        server.enqueue(MockResponse().setBody(Buffer().write(bytes)));click("重新下载");until(60000){model.download=="ready"||model.download=="error"};Assert.assertEquals(model.error,"ready",model.download)
    }
    @Test fun foreignUrlOrWrongApkMetadataAreRejected(){
        val invalid=payload();invalid.getJSONArray("assets").getJSONObject(0).put("url","https://example.com/package.apk")
        Assert.assertThrows(IllegalStateException::class.java){UpdateClient(config).parse(envelope(invalid).toString().toByteArray())}
        val currentFile=File(context.applicationInfo.sourceDir)
        Assert.assertThrows(IllegalStateException::class.java){verifyApk(context,currentFile,AppRelease("9.0.0",999,"","",UpdateAsset("","",1,"")))}
    }
    @Test fun networkFailureNeverLooksLikeAlreadyLatest(){
        server.shutdown();click("检测更新");until{model.status=="error"}
        compose.onNodeWithText("当前已是最新版本。").assertDoesNotExist();compose.onNodeWithTag("update-error").assertExists();compose.onNodeWithText("检测更新").assertIsEnabled()
    }
}
