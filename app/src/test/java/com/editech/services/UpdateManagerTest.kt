package com.editech.services

import com.editech.services.updater.UpdateManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateManagerTest {

    @Test
    fun testIsNewerVersion_NewerMinorOrPatch() {
        assertTrue(UpdateManager.isNewerVersion("2.0.3", "2.0.2"))
        assertTrue(UpdateManager.isNewerVersion("v2.1.0", "2.0.2"))
        assertTrue(UpdateManager.isNewerVersion("3.0.0", "2.0.2"))
        assertTrue(UpdateManager.isNewerVersion("v2.0.10", "2.0.2"))
    }

    @Test
    fun testIsNewerVersion_SameOrOlder() {
        assertFalse(UpdateManager.isNewerVersion("2.0.2", "2.0.2"))
        assertFalse(UpdateManager.isNewerVersion("v2.0.2", "2.0.2"))
        assertFalse(UpdateManager.isNewerVersion("2.0.1", "2.0.2"))
        assertFalse(UpdateManager.isNewerVersion("v1.9.9", "2.0.2"))
    }

    @Test
    fun testIsNewerVersion_EdgeCases() {
        assertFalse(UpdateManager.isNewerVersion("", "2.0.2"))
        assertFalse(UpdateManager.isNewerVersion("v2.0.2", ""))
        assertTrue(UpdateManager.isNewerVersion("v2.0.2.1", "2.0.2"))
    }
}
