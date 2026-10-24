package dev.ravindran.ythub.mobile

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.espresso.Espresso
import org.junit.Rule
import org.junit.Test

class PopupHostDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun outsideTouchAndBackDismissWithoutActivatingTheScreenBehind() {
        compose.waitUntil(30000) { MusicRepository.ready.value }
        compose.runOnUiThread { compose.activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
        compose.waitUntil(15000) {
            compose.onAllNodesWithTag("nav-Home").fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty()
        }
        compose.onNodeWithTag("nav-Home").performClick()
        compose.onNodeWithTag("home-library").performScrollTo()
        val underlyingLibrary = compose.onNodeWithTag("home-library").fetchSemanticsNode().boundsInRoot.center
        compose.onNodeWithContentDescription("App menu").performClick()
        compose.onNodeWithTag("glass-popover").assertIsDisplayed()
        compose.onRoot().performTouchInput { click(underlyingLibrary) }
        compose.onNodeWithTag("glass-popover").assertDoesNotExist()
        compose.onNodeWithText("Download from YouTube").assertIsDisplayed()
        compose.onNodeWithContentDescription("App menu").performClick()
        compose.onNodeWithTag("glass-popover").assertIsDisplayed()
        Espresso.pressBack()
        compose.onNodeWithTag("glass-popover").assertDoesNotExist()
        compose.onNodeWithText("Download from YouTube").assertIsDisplayed()
    }
}
