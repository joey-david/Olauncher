package app.olauncher

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.olauncher.helper.FocusSession
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.ZoneId

@RunWith(AndroidJUnit4::class)
class DailyBudgetTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val prefs get() = context.getSharedPreferences("focus", Context.MODE_PRIVATE)
    private var wall = 0L
    private var elapsed = 1_000_000L
    private val focus get() = FocusSession(context, { wall }, { elapsed }, 12)
    @Before fun setup() {
        prefs.edit().clear().commit()
        wall = LocalDate.of(2026, 11, 9).atTime(7, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        assertTrue(focus.configure(setOf("chosen.app"), setOf("notify.app")))
    }
    @After fun cleanup() { prefs.edit().clear().commit() }
    private fun awake() { focus.noteUnlock(); elapsed += FocusSession.MORNING_MS }

    @Test fun draftChoicesSurviveReopeningBeforeAccessIsGranted() {
        prefs.edit().clear().commit()
        focus.saveChoices(setOf("chosen.app"), setOf("notify.app"))
        assertEquals(setOf("chosen.app"), FocusSession(context).allowedApps)
        assertEquals(setOf("notify.app"), FocusSession(context).notificationApps)
        assertTrue(focus.locks("org.mozilla.firefox", focus.essentialApps()))
        assertTrue(focus.needsSetup)
    }

    @Test fun savedChoicesBlockImmediatelyAndMorningHasItsOwnAllowlist() {
        prefs.edit().clear().commit()
        focus.saveChoices(setOf("day.app"), emptySet())
        focus.noteUnlock()
        focus.morningApps = setOf("morning.app")
        val essentials = focus.essentialApps()
        assertTrue(focus.locks("day.app", essentials))
        assertFalse(focus.locks("morning.app", essentials))
        assertTrue(focus.locks("blocked.app", essentials))
        elapsed += FocusSession.MORNING_MS
        assertFalse(focus.locks("day.app", essentials))
        assertTrue(focus.locks("morning.app", essentials))
        assertTrue(focus.needsSetup)
    }

    @Test fun remindersCountOnlyContinuousUnlockedTimeOutsideSlots() {
        var clock = 0L
        var reminders = 0
        val usage = app.olauncher.helper.DailyPhoneUsage(context, { clock }, { reminders++ })
        repeat(60) { clock += 2000; usage.sample(true, context.packageName, false) }
        assertEquals(1, reminders)
        repeat(60) { clock += 2000; usage.sample(true, context.packageName, true) }
        assertEquals(1, reminders)
        repeat(60) { clock += 2000; usage.sample(false, context.packageName, false) }
        assertEquals(1, reminders)
        repeat(60) { clock += 2000; usage.sample(true, context.packageName, false) }
        assertEquals(2, reminders)
    }

    @Test fun waitsFullThirtyMinutesAndTaskTicksCannotSkipIt() {
        focus.noteUnlock()
        repeat(3) { focus.toggleMorningStep(it) }
        assertEquals(7, focus.morningSteps)
        elapsed += FocusSession.MORNING_MS - 1
        wall += 7 * 86_400_000L // Wall-clock changes cannot skip the wait.
        assertFalse(focus.start())
        elapsed++
        assertTrue(focus.start())
        assertEquals(2, focus.slotsLeft)
    }

    @Test fun exactlyThreeSlotsWithNoRefundOrExtension() {
        awake()
        repeat(3) { index ->
            assertTrue(focus.start())
            assertFalse("Double taps cannot extend a slot", focus.start())
            assertEquals(FocusSession.SLOT_MS, focus.remaining)
            elapsed += 5_000
            assertEquals(FocusSession.SLOT_MS - 5_000, focus.remaining)
            focus.finish()
            assertEquals(2 - index, focus.slotsLeft)
        }
        assertFalse(focus.start())
        assertEquals(0, focus.slotsLeft)
    }

    @Test fun resetsAtLocalMidnightWithoutStackingOrClockRollbackRefills() {
        awake()
        assertTrue(focus.start())
        wall = LocalDate.of(2026, 11, 10).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        assertEquals(3, focus.slotsLeft)
        assertFalse("A slot crossing midnight must not stack", focus.start())
        focus.finish()
        assertTrue(focus.start())
        assertEquals(2, focus.slotsLeft)
        wall -= 86_400_000L
        assertEquals(2, focus.slotsLeft)
    }

    @Test fun rebootEndsSlotButKeepsItsCostAndRestartsWakeWait() {
        awake()
        assertTrue(focus.start())
        val rebooted = FocusSession(context, { wall }, { 1000 }, 13)
        assertEquals(0L, rebooted.remaining)
        assertEquals(2, rebooted.slotsLeft)
        rebooted.noteUnlock()
        assertEquals(FocusSession.MORNING_MS, rebooted.morningRemaining)
        assertFalse(rebooted.start())
    }

    @Test fun choicesLockUntilAnUpdateAndUpdatingKeepsTheBudget() {
        awake()
        assertTrue(focus.start())
        focus.finish()
        assertFalse(focus.configure(setOf("other.app"), emptySet()))
        assertEquals(setOf("chosen.app"), focus.allowedApps)
        prefs.edit().putInt("configuredVersion", BuildConfig.VERSION_CODE - 1).commit()
        assertFalse("An update needs new choices before a slot", focus.start())
        assertTrue(focus.configure(setOf("other.app"), emptySet()))
        assertEquals(2, focus.slotsLeft)
    }

    @Test fun newAppsAreBlockedAndOldFocusTimersCannotGrantAccess() {
        prefs.edit().putLong("end", Long.MAX_VALUE).putInt("boot", 12).commit()
        val essentials = focus.essentialApps()
        awake()
        assertFalse(focus.locks("chosen.app", essentials))
        assertTrue(focus.locks("newly.installed.app", essentials))
        assertFalse(focus.locks("com.android.settings", essentials))
        awake()
        assertTrue(focus.start())
        assertFalse(focus.locks("newly.installed.app", essentials))
        elapsed += FocusSession.SLOT_MS
        assertTrue(focus.locks("newly.installed.app", essentials))
    }

    @Test fun longSleepRestartsMorningWaitButShortScreenLocksDoNot() {
        awake()
        focus.noteScreenOff()
        elapsed += 60_000
        focus.noteUnlock()
        assertEquals(0L, focus.morningRemaining)
        focus.noteScreenOff()
        elapsed += FocusSession.SLEEP_MS
        focus.noteUnlock()
        assertEquals(FocusSession.MORNING_MS, focus.morningRemaining)
    }

    @Test fun firstMorningUnlockWorksEvenAfterAnAfterMidnightVisit() {
        wall = LocalDate.of(2026, 11, 9).atTime(0, 5).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        awake()
        assertEquals(0L, focus.morningRemaining)
        wall += 7 * 3_600_000L
        focus.noteUnlock()
        assertEquals(FocusSession.MORNING_MS, focus.morningRemaining)
    }
}
