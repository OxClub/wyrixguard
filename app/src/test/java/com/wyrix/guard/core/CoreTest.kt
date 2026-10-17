package com.wyrix.guard.core

import org.junit.Assert.*
import org.junit.Test

class CoreTest {
    private val now = 1_800_000_000_000L
    private fun cmd(
        device: String = "d1", owner: String = "u1", type: String = "TRIGGER_ALARM", state: String = "requested", exp: Long = now + 60_000,
    ) = CommandInfo("c1", device, owner, type, state, exp)

    @Test fun validCommandIsAccepted() = assertEquals(Validation.Ok, CommandValidator.validate(cmd(), "d1", "u1", now))
    @Test fun wrongDeviceRejected() = assertTrue(CommandValidator.validate(cmd(device = "other"), "d1", "u1", now) is Validation.Rejected)
    @Test fun wrongOwnerRejected() = assertTrue(CommandValidator.validate(cmd(owner = "mallory"), "d1", "u1", now) is Validation.Rejected)
    @Test fun expiredRejected() = assertEquals(Validation.Rejected("expired"), CommandValidator.validate(cmd(exp = now - 1), "d1", "u1", now))
    @Test fun unknownTypeRejected() = assertEquals(Validation.Rejected("unknown_type"), CommandValidator.validate(cmd(type = "WIPE_DEVICE"), "d1", "u1", now))
    @Test fun terminalStateRejected() = assertTrue(CommandValidator.validate(cmd(state = "executed"), "d1", "u1", now) is Validation.Rejected)
    @Test fun notEnrolledRejected() = assertEquals(Validation.Rejected("not_enrolled"), CommandValidator.validate(cmd(), null, "u1", now))

    @Test fun accuracyNeverOverclaims() {
        assertEquals("±20 m", Accuracy.label(20f))
        assertTrue(Accuracy.label(300f).contains("approximate"))
        assertTrue(Accuracy.label(3000f).contains("low precision"))
    }

    @Test fun imeiLuhn() {
        assertTrue(Luhn.validImei("490154203237518"))
        assertFalse(Luhn.validImei("490154203237519"))
        assertFalse(Luhn.validImei("12345"))
    }

    @Test fun csvEscapesAndBlocksFormulaInjection() {
        val csv = Csv.build(listOf("a", "b"), listOf(listOf("=HYPERLINK(\"x\")", "x,y")))
        assertEquals("a,b\n\"'=HYPERLINK(\"\"x\"\")\",\"x,y\"", csv)
    }

    @Test fun backoffGrowsAndIsCapped() {
        assertEquals(30_000L, Backoff.delayMs(0))
        assertTrue(Backoff.delayMs(3) > Backoff.delayMs(2))
        assertEquals(30 * 60_000L, Backoff.delayMs(20))
    }

    @Test fun trackingModesOrderedByFrequency() {
        assertTrue(TrackingMode.RECOVERY.intervalMs < TrackingMode.NORMAL.intervalMs)
        assertTrue(TrackingMode.NORMAL.intervalMs < TrackingMode.BATTERY_SAVING.intervalMs)
        assertEquals(TrackingMode.NORMAL, TrackingMode.fromWire("nonsense"))
    }

    @Test fun isoParsesPostgresTimestamps() {
        assertEquals(1_700_000_000_000L, Iso.toMs("2023-11-14T22:13:20+00:00"))
        assertEquals(1_700_000_000_123L, Iso.toMs("2023-11-14T22:13:20.123+00:00"))
    }
}
