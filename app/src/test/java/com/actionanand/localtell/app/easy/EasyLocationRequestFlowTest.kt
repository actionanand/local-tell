package com.actionanand.localtell.app.easy

import com.actionanand.localtell.app.easy.EasyLocationRequestFlow.Step
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EasyLocationRequestFlowTest {
    private val flow = EasyLocationRequestFlow()

    @Test fun permissionGrantedAndLocationOnStartsImmediately() {
        assertEquals(Step.START_LOCATING, flow.onTap(fineGranted = true, locationEnabled = true, locating = false))
        assertFalse(flow.isPending)
    }

    @Test fun permissionMissingWithLocationOnStartsAfterGrant() {
        assertEquals(Step.REQUEST_PERMISSION, flow.onTap(false, true, false))
        assertEquals(Step.START_LOCATING, flow.onPermissionResult(fineGranted = true, locationEnabled = true))
        assertEquals(Step.NONE, flow.onResume(true, true, false))
    }

    @Test fun locationOffContinuesOnceEnabledWithoutSecondTap() {
        assertEquals(Step.REQUEST_LOCATION_ENABLE, flow.onTap(true, false, false))
        assertEquals(Step.START_LOCATING, flow.onLocationEnabled())
        assertEquals(Step.NONE, flow.onLocationEnabled())
        assertEquals(Step.NONE, flow.onResume(true, true, false))
    }

    @Test fun permissionMissingAndLocationOffCompletesWholeFlowFromOneTap() {
        assertEquals(Step.REQUEST_PERMISSION, flow.onTap(false, false, false))
        assertEquals(Step.REQUEST_LOCATION_ENABLE, flow.onPermissionResult(true, false))
        // Activity resumes after the permission dialog before the enable dialog appears.
        assertEquals(Step.NONE, flow.onResume(true, false, false))
        assertTrue(flow.isPending)
        assertEquals(Step.START_LOCATING, flow.onLocationEnabled())
        assertFalse(flow.isPending)
    }

    @Test fun approximateOnlyOrRejectedPermissionIsReportedOnce() {
        flow.onTap(false, true, false)
        assertEquals(Step.PERMISSION_DENIED, flow.onPermissionResult(fineGranted = false, locationEnabled = true))
        assertEquals(Step.NONE, flow.onPermissionResult(fineGranted = false, locationEnabled = true))
        assertFalse(flow.isPending)
    }

    @Test fun cancellingEnableDialogEndsPendingIntentSafely() {
        flow.onTap(true, false, false)
        assertEquals(Step.LOCATION_DECLINED, flow.onLocationDeclined())
        assertFalse(flow.isPending)
        assertEquals(Step.NONE, flow.onLocationEnabled())
        assertEquals(Step.NONE, flow.onResume(true, true, false))
    }

    @Test fun returningFromSettingsOrRecreationResumesExactlyOnce() {
        flow.onTap(true, false, false)
        // The enablement callback was lost (Activity recreated); Location is now ON.
        assertEquals(Step.START_LOCATING, flow.onResume(true, true, false))
        assertEquals(Step.NONE, flow.onResume(true, true, false))
        assertEquals(Step.NONE, flow.onLocationEnabled())
    }

    @Test fun rapidRepeatedTapsDoNotDuplicateSystemPrompts() {
        assertEquals(Step.REQUEST_PERMISSION, flow.onTap(false, false, false))
        assertEquals(Step.NONE, flow.onTap(false, false, false))
        assertEquals(Step.NONE, flow.onTap(false, false, false))
    }

    @Test fun recreationAllowsOneExplicitRetryForLostEnablePrompt() {
        flow.onTap(true, false, false)
        assertEquals(Step.RECONNECT_LOCATION_ENABLE, flow.onHostAttached())
        repeat(5) { assertEquals(Step.NONE, flow.onResume(true, false, false)) }
        assertEquals(Step.REQUEST_LOCATION_ENABLE, flow.onTap(true, false, false))
        repeat(5) {
            assertEquals(Step.NONE, flow.onResume(true, false, false))
            assertEquals(Step.NONE, flow.onTap(true, false, false))
        }
        assertEquals(Step.START_LOCATING, flow.onLocationEnabled())
        assertEquals(Step.NONE, flow.onLocationEnabled())
    }

    @Test fun resumeDoesNotAllowRepeatedEnablePromptsInTheSameActivity() {
        flow.onTap(true, false, false)
        repeat(5) {
            assertEquals(Step.NONE, flow.onResume(true, false, false))
            assertEquals(Step.NONE, flow.onTap(true, false, false))
        }
    }

    @Test fun recreationAfterGrantContinuesToEnablementWithoutPermissionCallback() {
        flow.onTap(false, false, false)
        assertEquals(Step.NONE, flow.onHostAttached())
        assertEquals(Step.REQUEST_LOCATION_ENABLE, flow.onResume(true, false, false))
        repeat(5) { assertEquals(Step.NONE, flow.onResume(true, false, false)) }
        assertEquals(Step.NONE, flow.onPermissionResult(true, false))
        assertEquals(Step.START_LOCATING, flow.onLocationEnabled())
    }

    @Test fun recreationAfterGrantWithBothPrerequisitesStartsOnlyOnce() {
        flow.onTap(false, false, false)
        flow.onHostAttached()
        assertEquals(Step.START_LOCATING, flow.onResume(true, true, false))
        assertEquals(Step.NONE, flow.onPermissionResult(true, true))
        repeat(5) { assertEquals(Step.NONE, flow.onResume(true, true, false)) }
    }

    @Test fun recreationWhileAwaitingEnablementStartsOnceWhenLocationBecomesAvailable() {
        flow.onTap(true, false, false)
        assertEquals(Step.RECONNECT_LOCATION_ENABLE, flow.onHostAttached())
        assertEquals(Step.NONE, flow.onResume(true, false, false))
        assertEquals(Step.START_LOCATING, flow.onResume(true, true, false))
        assertEquals(Step.NONE, flow.onLocationEnabled())
        assertEquals(Step.NONE, flow.onLocationDeclined())
    }

    @Test fun declineAfterRecreationEndsIntentAndCannotRestartOnResume() {
        flow.onTap(true, false, false)
        flow.onHostAttached()
        assertEquals(Step.LOCATION_DECLINED, flow.onLocationDeclined())
        repeat(5) { assertEquals(Step.NONE, flow.onResume(true, true, false)) }
        assertEquals(Step.NONE, flow.onHostAttached())
        assertFalse(flow.isPending)
    }

    @Test fun noPendingUserIntentNeverRequestsPermissionOrStartsGpsOnResume() {
        repeat(5) {
            assertEquals(Step.NONE, flow.onHostAttached())
            assertEquals(Step.NONE, flow.onResume(false, false, false))
            assertEquals(Step.NONE, flow.onResume(true, true, false))
        }
    }

    @Test fun recreationWhileAwaitingPermissionNeverAutoRequestsButOneTapRecoversLostResult() {
        flow.onTap(false, false, false)
        assertEquals(Step.NONE, flow.onHostAttached())
        repeat(5) { assertEquals(Step.NONE, flow.onResume(false, false, false)) }
        assertEquals(Step.REQUEST_PERMISSION, flow.onTap(false, false, false))
        repeat(5) {
            assertEquals(Step.NONE, flow.onResume(false, false, false))
            assertEquals(Step.NONE, flow.onTap(false, false, false))
        }
        assertEquals(Step.PERMISSION_DENIED, flow.onPermissionResult(false, false))
        assertFalse(flow.isPending)
    }

    @Test fun restoredPermissionResultAfterRecreationStillContinuesWithoutTap() {
        flow.onTap(false, false, false)
        flow.onHostAttached()
        assertEquals(Step.REQUEST_LOCATION_ENABLE, flow.onPermissionResult(true, false))
        assertEquals(Step.NONE, flow.onTap(true, false, false))
        assertEquals(Step.START_LOCATING, flow.onLocationEnabled())
    }

    @Test fun restoredGrantBeforeHostAttachesDoesNotAllowDuplicateFreshPrompt() {
        flow.onTap(false, false, false)
        assertEquals(Step.REQUEST_LOCATION_ENABLE, flow.onPermissionResult(true, false))
        assertEquals(Step.RECONNECT_LOCATION_ENABLE, flow.onHostAttached(enablementRequestActive = true))
        repeat(5) {
            assertEquals(Step.NONE, flow.onResume(true, false, false))
            assertEquals(Step.NONE, flow.onTap(true, false, false))
        }
    }

    @Test fun tapsWhileLocatingAreIgnored() {
        assertEquals(Step.NONE, flow.onTap(true, true, locating = true))
        flow.onTap(true, false, false)
        assertEquals(Step.NONE, flow.onResume(true, true, locating = true))
        assertTrue(flow.isPending)
    }

    @Test fun permissionResultWithoutPendingRequestIsIgnored() {
        assertEquals(Step.NONE, flow.onPermissionResult(true, true))
        assertEquals(Step.NONE, flow.onLocationDeclined())
    }
}
