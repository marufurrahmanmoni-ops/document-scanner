package com.docscanner.app

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** J3 capture and the camera-interruption condition. Needs a device or emulator with a back camera. */
@RunWith(AndroidJUnit4::class)
class CaptureAndInterruptionTest {
    @get:Rule val camera: GrantPermissionRule = GrantPermissionRule.grant(android.Manifest.permission.CAMERA)

    private val instr = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(instr)
    private val pkg = instr.targetContext.packageName
    private fun s(id: Int) = instr.targetContext.getString(id)

    @Before fun openCamera() {
        val i = instr.targetContext.packageManager.getLaunchIntentForPackage(pkg)!!
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK)
        instr.targetContext.startActivity(i)
        device.wait(Until.findObject(By.text(s(R.string.scan_first))), 10_000)?.click()
        assertNotNull(device.wait(Until.findObject(By.desc(s(R.string.take_photo))), 10_000))
    }

    @Test fun J3_H1_shutterOpensCrop() {
        device.findObject(By.desc(s(R.string.take_photo))).click()
        assertNotNull("crop screen not reached", device.wait(Until.findObject(By.desc(s(R.string.retake))), 15_000))
    }

    @Test fun J3_E1_homeAndBackResumesPreview() {
        device.pressHome()
        device.waitForIdle()
        instr.targetContext.startActivity(
            instr.targetContext.packageManager.getLaunchIntentForPackage(pkg)!!.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        assertNotNull(device.wait(Until.findObject(By.desc(s(R.string.take_photo))), 10_000))
        device.findObject(By.desc(s(R.string.take_photo))).click()
        assertNotNull(device.wait(Until.findObject(By.desc(s(R.string.retake))), 15_000))
    }

    @Test fun J3_E2_rotationDuringPreviewKeepsCamera() {
        device.setOrientationLeft()
        try {
            assertNotNull(device.wait(Until.findObject(By.desc(s(R.string.take_photo))), 10_000))
        } finally {
            device.setOrientationNatural()
        }
    }
}
