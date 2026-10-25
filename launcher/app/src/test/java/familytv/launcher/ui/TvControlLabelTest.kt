package familytv.launcher.ui

import familytv.launcher.remote.LocalAdbKeys.Status
import org.junit.Assert.assertEquals
import org.junit.Test

class TvControlLabelTest {
    @Test fun offWhenThePreferenceIsOff() {
        assertEquals("Off", tvControlLabel(false, null))
        assertEquals("Off", tvControlLabel(false, Status.Ready))
    }

    @Test fun onWhenReadyOrNotCheckedYet() {
        assertEquals("On", tvControlLabel(true, Status.Ready))
        assertEquals("On", tvControlLabel(true, null))
    }

    @Test fun waitingWhileThePromptIsUp() = assertEquals("Waiting for approval", tvControlLabel(true, Status.NeedsApproval))

    @Test fun notAvailableWhenItCannotConnect() = assertEquals("Not available", tvControlLabel(true, Status.Unavailable("off")))
}
