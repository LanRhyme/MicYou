package com.lanrhyme.micyou

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UpdateCheckerVersionTest {
    @Test
    fun detectsNewerVersion() {
        assertTrue(isNewerVersion("1.1.0", "1.1.1"))
        assertTrue(isNewerVersion("1.1", "1.1.1"))
        assertTrue(isNewerVersion("1.9.9", "2.0.0"))
    }

    @Test
    fun detectsSameOrOlderVersion() {
        assertFalse(isNewerVersion("1.1.0", "1.1.0"))
        assertFalse(isNewerVersion("1.2.0", "1.1.9"))
    }
}
