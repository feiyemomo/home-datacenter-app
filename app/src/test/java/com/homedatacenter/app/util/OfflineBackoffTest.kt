package com.homedatacenter.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.14.3: offline re-probe backoff schedule (5s -> 10s -> 20s -> 40s -> 60s cap).
 */
class OfflineBackoffTest {

    @Test
    fun schedule_doublesThenCaps() {
        assertEquals(5_000L, BaseUrlResolver.offlineBackoffDelayMs(1))
        assertEquals(10_000L, BaseUrlResolver.offlineBackoffDelayMs(2))
        assertEquals(20_000L, BaseUrlResolver.offlineBackoffDelayMs(3))
        assertEquals(40_000L, BaseUrlResolver.offlineBackoffDelayMs(4))
        assertEquals(60_000L, BaseUrlResolver.offlineBackoffDelayMs(5))
    }

    @Test
    fun belowOne_clampsToBase() {
        assertEquals(5_000L, BaseUrlResolver.offlineBackoffDelayMs(0))
        assertEquals(5_000L, BaseUrlResolver.offlineBackoffDelayMs(-3))
    }

    @Test
    fun manyFailures_neverExceedCap_noOverflow() {
        for (n in listOf(6, 10, 63, 64, 100, Int.MAX_VALUE)) {
            val d = BaseUrlResolver.offlineBackoffDelayMs(n)
            assertEquals("failures=$n", 60_000L, d)
            assertTrue(d > 0)
        }
    }

    @Test
    fun monotonicNonDecreasing() {
        var prev = 0L
        for (n in 0..20) {
            val d = BaseUrlResolver.offlineBackoffDelayMs(n)
            assertTrue("failures=$n", d >= prev)
            prev = d
        }
    }
}
