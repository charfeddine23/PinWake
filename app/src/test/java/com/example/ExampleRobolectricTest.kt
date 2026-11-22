package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.repository.PollingTier
import com.example.util.ApkUpdateManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ExampleRobolectricTest {

    @Test
    fun `read string from context`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("Pin Wake", appName)
    }

    @Test
    fun `adaptive polling tier scales with distance`() {
        assertEquals(PollingTier.ECO_30S, PollingTier.forDistance(6500f))
        assertEquals(PollingTier.BALANCED_10S, PollingTier.forDistance(2500f))
        assertEquals(PollingTier.PRECISION_2S, PollingTier.forDistance(800f))
    }

    @Test
    fun `semantic version comparison detects newer github release`() {
        assertTrue(ApkUpdateManager.isNewerVersion("v1.0.1", "1.0.0"))
        assertTrue(ApkUpdateManager.isNewerVersion("v1.1.0", "1.0.9"))
        assertFalse(ApkUpdateManager.isNewerVersion("v1.0.1", "1.0.1"))
        assertFalse(ApkUpdateManager.isNewerVersion("1.0.0", "1.0.1"))
    }
}
