package com.wyrix.guard.core

import org.junit.Assert.*
import org.junit.Test

class PhotoTest {
    private val now = 1_800_000_000_000L
    @Test fun requiresOptInPermissionAndEnrolment() {
        assertTrue(PhotoPolicy.canCapture(true, true, true, 0, now, false))
        assertFalse(PhotoPolicy.canCapture(false, true, true, 0, now, false))
        assertFalse(PhotoPolicy.canCapture(true, false, true, 0, now, false))
        assertFalse(PhotoPolicy.canCapture(true, true, false, 0, now, false))
    }
    @Test fun rateLimitedUnlessForcedByTheOwner() {
        assertFalse(PhotoPolicy.canCapture(true, true, true, now - 60_000, now, false))
        assertTrue(PhotoPolicy.canCapture(true, true, true, now - 60_000, now, true))
    }
    @Test fun downscalesLargeImages() {
        assertEquals(1, PhotoPolicy.sampleSize(1280, 720))
        assertEquals(2, PhotoPolicy.sampleSize(3000, 2000))
        assertEquals(4, PhotoPolicy.sampleSize(5200, 3000))
    }
}
