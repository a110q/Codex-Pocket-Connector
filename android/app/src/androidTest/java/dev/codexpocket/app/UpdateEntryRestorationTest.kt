package dev.codexpocket.app

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test

class UpdateEntryRestorationTest{
    @get:org.junit.Rule val compose=createAndroidComposeRule<ComponentActivity>()
    @Test fun manualUpdatePageRemainsOpenAfterSavedStateRestoration(){
        val model=AppUpdateModel(InstrumentationRegistry.getInstrumentation().targetContext,preferencesName="update-entry-test")
        try{
            val restoration=StateRestorationTester(compose)
            restoration.setContent{PocketTheme("light"){CompositionLocalProvider(LocalAppUpdateModel provides model){UpdateEntry()}}}
            compose.onNodeWithText("检测更新").performClick()
            compose.onNodeWithText("关于与更新").assertIsDisplayed()
            restoration.emulateSavedInstanceStateRestore()
            compose.onNodeWithText("关于与更新").assertIsDisplayed()
        }finally{compose.runOnUiThread{model.close()}}
    }
}
