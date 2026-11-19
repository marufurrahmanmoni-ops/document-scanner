package com.docscanner.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * J1 first launch and J2 camera permission, driven through the real launcher intent and the system
 * permission dialog with UI Automator. Run on a fresh install (`adb shell pm clear com.docscanner.app` first).
 */
@RunWith(AndroidJUnit4::class)
class FirstLaunchAndPermissionTest {
    private val instr = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(instr)
    private val pkg = instr.targetContext.packageName
    private val timeout = 10_000L

    @Before fun freshStart() {
        device.executeShellCommand("pm revoke $pkg android.permission.CAMERA")
        device.executeShellCommand("pm clear-permission-flags $pkg android.permission.CAMERA user-set user-fixed")
        device.pressHome()
        val intent = instr.targetContext.packageManager.getLaunchIntentForPackage(pkg)!!
            .addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK or android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        instr.targetContext.startActivity(intent)
        assertNotNull("app did not open", device.wait(Until.hasObject(By.pkg(pkg).depth(0)), timeout))
    }

    private fun text(id: Int) = instr.targetContext.getString(id)

    @Test fun J1_H1_emptyLibraryOffersScan() {
        assertNotNull(device.wait(Until.findObject(By.text(text(R.string.empty_home_title))), timeout))
        assertNotNull(device.findObject(By.text(text(R.string.scan_first))))
    }

    @Test fun J2_N1_denyingCameraShowsExplanationNotACrash() {
        device.wait(Until.findObject(By.text(text(R.string.scan_first))), timeout)!!.click()
        val deny = device.wait(Until.findObject(By.res("com.android.permissioncontroller:id/permission_deny_button")), timeout)
            ?: device.wait(Until.findObject(By.textContains("Don")), timeout)
        assertNotNull("system permission dialog not shown", deny)
        deny!!.click()
        assertNotNull(device.wait(Until.findObject(By.text(text(R.string.camera_permission_title))), timeout))
    }

    @Test fun J2_H1_allowingCameraShowsShutter() {
        device.executeShellCommand("pm grant $pkg android.permission.CAMERA")
        device.wait(Until.findObject(By.text(text(R.string.scan_first))), timeout)!!.click()
        assertNotNull(device.wait(Until.findObject(By.desc(text(R.string.take_photo))), timeout))
    }
}
