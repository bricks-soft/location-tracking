package com.brickssoft.locationtracking.permission

import android.app.Activity
import android.app.AlertDialog
import android.os.Looper
import com.brickssoft.locationtracking.R
import com.brickssoft.locationtracking.config.BackgroundPermissionRationale
import com.brickssoft.locationtracking.core.Logger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowAlertDialog

@RunWith(RobolectricTestRunner::class)
class BackgroundRationaleDialogTest {
    private val activity: Activity = Robolectric.buildActivity(Activity::class.java).setup().get()
    private val answers = mutableListOf<Boolean>()

    @After
    fun tearDown() {
        Logger.sink = null
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    @Test
    fun `positive answers true exactly once even though the dialog is dismissed afterwards`() {
        assertTrue(BackgroundRationaleDialog.show(activity, BackgroundPermissionRationale()) { answers += it })
        val dialog = ShadowAlertDialog.getLatestAlertDialog()

        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        idle()
        dialog.dismiss()
        idle()

        assertEquals(listOf(true), answers)
    }

    @Test
    fun `negative answers false exactly once`() {
        BackgroundRationaleDialog.show(activity, BackgroundPermissionRationale()) { answers += it }
        val dialog = ShadowAlertDialog.getLatestAlertDialog()

        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        idle()
        dialog.cancel()
        idle()

        assertEquals(listOf(false), answers)
    }

    @Test
    fun `dismissing without a button answers false`() {
        BackgroundRationaleDialog.show(activity, BackgroundPermissionRationale()) { answers += it }

        ShadowAlertDialog.getLatestAlertDialog().dismiss()
        idle()

        assertEquals(listOf(false), answers)
    }

    @Test
    fun `a destroyed activity answers false once and closes the dialog`() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        BackgroundRationaleDialog.show(controller.get(), BackgroundPermissionRationale()) { answers += it }
        val dialog = ShadowAlertDialog.getLatestAlertDialog()

        controller.pause().stop().destroy()
        idle()

        assertEquals(listOf(false), answers)
        assertFalse(dialog.isShowing)
    }

    @Test
    fun `destroying another activity does not answer`() {
        BackgroundRationaleDialog.show(activity, BackgroundPermissionRationale()) { answers += it }

        Robolectric.buildActivity(Activity::class.java).setup().pause().stop().destroy()
        idle()

        assertTrue(answers.isEmpty())
        assertTrue(ShadowAlertDialog.getLatestAlertDialog().isShowing)
    }

    @Test
    fun `texts mix config values and resource defaults`() {
        val texts = BackgroundRationaleDialog.texts(
            activity,
            BackgroundPermissionRationale(title = "T", message = "", positiveAction = null, negativeAction = "N"),
        )

        assertEquals(
            BackgroundRationaleDialog.Texts(
                title = "T",
                message = activity.getString(R.string.lt_bg_rationale_message),
                positive = activity.getString(R.string.lt_bg_rationale_positive),
                negative = "N",
            ),
            texts,
        )
    }
}
