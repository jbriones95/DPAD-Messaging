package com.dpad.messaging.receivers

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.TestListenableWorkerBuilder
import com.dpad.messaging.dumber.DumberSubscriptions
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
class SmsReceiveCompatibilityTest {

    @Test
    @Config(sdk = [23])
    fun defaultSubscriptionLookupDoesNotCrashOnApi23() {
        val context = ApplicationProvider.getApplicationContext<Context>()

        // A missing SIM/default subscription must not call an API-24-only method.
        DumberSubscriptions.selected(context, -1)
    }

    @Test
    @Config(sdk = [28])
    fun expeditedMmsWorkerProvidesForegroundInfo() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val worker = TestListenableWorkerBuilder<MmsReceiveWorker>(context).build()

        val info = worker.getForegroundInfo()

        assertTrue(info.notificationId != 0)
    }

    @Test
    @Config(sdk = [28])
    fun expeditedSmsWorkerProvidesForegroundInfo() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val worker = TestListenableWorkerBuilder<SmsReceiveWorker>(context).build()

        val info = worker.getForegroundInfo()

        assertTrue(info.notificationId != 0)
    }
}
