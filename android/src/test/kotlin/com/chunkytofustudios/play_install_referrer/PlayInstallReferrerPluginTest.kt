package com.chunkytofustudios.play_install_referrer

import android.app.Application
import android.content.ComponentName
import android.content.IntentFilter
import android.content.pm.PackageInfo
import android.os.Binder
import android.os.Bundle
import android.os.Looper
import android.os.Parcel
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import java.time.Duration
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/**
 * Runs the plugin against the real Install Referrer library, with a fake Play Store service
 * bound in its place.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlayInstallReferrerPluginTest {
    private val app: Application = RuntimeEnvironment.getApplication()
    private val playStore = ComponentName(
        "com.android.vending",
        "com.google.android.finsky.externalreferrer.GetInstallReferrerService")
    private val service = FakeInstallReferrerService()
    private val plugin = PlayInstallReferrerPlugin()

    @Before
    fun setUp() {
        ShadowLog.stream = System.out
        // Match Android, which doesn't call onServiceDisconnected after unbindService.
        shadowOf(app).setUnbindServiceCallsOnServiceDisconnected(false)
        val binding = mock(FlutterPlugin.FlutterPluginBinding::class.java)
        `when`(binding.applicationContext).thenReturn(app)
        `when`(binding.binaryMessenger).thenReturn(mock(BinaryMessenger::class.java))
        plugin.onAttachedToEngine(binding)
    }

    @After
    fun tearDown() {
        service.releaseAll()
        finishBackgroundFetches()
        plugin.onDetachedFromEngine(mock(FlutterPlugin.FlutterPluginBinding::class.java))
    }

    @Test
    fun fetchesReferrerOffTheMainThread() {
        installFakePlayStore()

        val first = getInstallReferrer()
        val second = getInstallReferrer()
        shadowOf(Looper.getMainLooper()).idle()
        finishBackgroundFetches()

        val expected = mapOf(
            "installReferrer" to "utm_source=test",
            "referrerClickTimestampSeconds" to 1L,
            "installBeginTimestampSeconds" to 2L,
            "referrerClickTimestampServerSeconds" to 3L,
            "installBeginTimestampServerSeconds" to 4L,
            "installVersion" to "1.0",
            "googlePlayInstantParam" to true
        )
        assertEquals(expected, first.value)
        assertEquals(expected, second.value)
        assertEquals(1, service.calls.get())
        assertFalse("Binder call ran on the main thread", service.calledOnMainThread)
        assertTrue(shadowOf(app).boundServiceConnections.isEmpty())

        // The result is cached; later calls don't reconnect.
        assertEquals(expected, getInstallReferrer().value)
        assertEquals(1, service.calls.get())
    }

    @Test
    fun timesOutWhenPlayStoreStalls_andCachesTheLateResult() {
        installFakePlayStore()
        val stall = service.stallNextCall()

        val first = getInstallReferrer()
        shadowOf(Looper.getMainLooper()).idle()
        service.awaitCall()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(10))

        assertEquals("TIMEOUT", first.errorCode)

        stall.countDown()
        finishBackgroundFetches()

        val later = getInstallReferrer()
        assertEquals("utm_source=test", (later.value as Map<*, *>)["installReferrer"])
        assertEquals(1, service.calls.get())
        assertEquals(1, first.replies)
    }

    @Test
    fun keepsResultFetchedBeforeServiceDisconnected() {
        installFakePlayStore()
        val firstFetch = service.stallNextCall()
        service.stallNextCall()

        val result = getInstallReferrer()
        shadowOf(Looper.getMainLooper()).idle()
        service.awaitCall()

        // The Play Store dies while the first fetch is in flight, so the plugin reconnects and
        // starts a second fetch.
        shadowOf(app).boundServiceConnections.single().onServiceDisconnected(playStore)
        shadowOf(Looper.getMainLooper()).idle()
        service.awaitCall()

        // The first fetch still completes, and its result is used instead of being dropped.
        firstFetch.countDown()
        finishBackgroundFetches(except = 1)

        assertNull(result.errorCode)
        assertEquals("utm_source=test", (result.value as Map<*, *>)["installReferrer"])
        assertTrue(shadowOf(app).boundServiceConnections.isEmpty())
    }

    @Test
    fun reportsNullReferrerBundleAsError() {
        installFakePlayStore()
        service.referrer = null

        val result = getInstallReferrer()
        shadowOf(Looper.getMainLooper()).idle()
        finishBackgroundFetches()

        assertEquals("UNEXPECTED_ERROR", result.errorCode)
        assertNull(result.value)
    }

    @Test
    fun reportsFeatureNotSupportedWithoutPlayStore() {
        val result = getInstallReferrer()

        assertEquals("FEATURE_NOT_SUPPORTED", result.errorCode)
        assertEquals(0, service.calls.get())
    }

    private fun installFakePlayStore() {
        val packageManager = shadowOf(app.packageManager)
        packageManager.installPackage(PackageInfo().apply {
            packageName = playStore.packageName
            @Suppress("DEPRECATION")
            versionCode = 80837300
        })
        packageManager.addServiceIfNotPresent(playStore)
        packageManager.addIntentFilterForService(
            playStore, IntentFilter("com.google.android.finsky.BIND_GET_INSTALL_REFERRER_SERVICE"))
        shadowOf(app).setComponentNameAndServiceForBindService(playStore, service)
    }

    private fun getInstallReferrer(): RecordingResult {
        val result = RecordingResult()
        plugin.onMethodCall(MethodCall("getInstallReferrer", null), result)
        return result
    }

    // Waits for the plugin's fetch threads (leaving `except` of them stalled), then delivers
    // what they posted to the main thread.
    private fun finishBackgroundFetches(except: Int = 0) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (fetchThreads().size > except) {
            assertTrue("Fetch threads did not finish", System.nanoTime() < deadline)
            Thread.sleep(10)
        }
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun fetchThreads() =
        Thread.getAllStackTraces().keys.filter { it.name == "PlayInstallReferrer" && it.isAlive }

    private class RecordingResult : MethodChannel.Result {
        var value: Any? = null
        var errorCode: String? = null
        var replies = 0

        override fun success(result: Any?) {
            replies++
            value = result
        }

        override fun error(errorCode: String, errorMessage: String?, errorDetails: Any?) {
            replies++
            this.errorCode = errorCode
        }

        override fun notImplemented() {
            replies++
        }
    }

    /** Speaks the Play Store's IGetInstallReferrerService Binder protocol. */
    private class FakeInstallReferrerService : Binder() {
        @Volatile var referrer: Bundle? = Bundle().apply {
            putString("install_referrer", "utm_source=test")
            putLong("referrer_click_timestamp_seconds", 1)
            putLong("install_begin_timestamp_seconds", 2)
            putLong("referrer_click_timestamp_server_seconds", 3)
            putLong("install_begin_timestamp_server_seconds", 4)
            putString("install_version", "1.0")
            putBoolean("google_play_instant", true)
        }
        @Volatile var calledOnMainThread = false
        val calls = AtomicInteger()
        private val entered = Semaphore(0)
        private val stalls = ConcurrentLinkedQueue<CountDownLatch>()
        private val allStalls = ConcurrentLinkedQueue<CountDownLatch>()

        /** Makes the next call block until the returned latch is released. */
        fun stallNextCall(): CountDownLatch =
            CountDownLatch(1).also {
                stalls.add(it)
                allStalls.add(it)
            }

        fun awaitCall() {
            assertTrue("Play Store was not called", entered.tryAcquire(5, TimeUnit.SECONDS))
        }

        fun releaseAll() = allStalls.forEach { it.countDown() }

        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code != FIRST_CALL_TRANSACTION) return super.onTransact(code, data, reply, flags)
            data.enforceInterface("com.google.android.finsky.externalreferrer.IGetInstallReferrerService")
            calls.incrementAndGet()
            if (Looper.myLooper() == Looper.getMainLooper()) calledOnMainThread = true
            entered.release()
            stalls.poll()?.await(10, TimeUnit.SECONDS)

            val bundle = referrer
            reply!!.writeNoException()
            if (bundle == null) {
                reply.writeInt(0)
            } else {
                reply.writeInt(1)
                bundle.writeToParcel(reply, 0)
            }
            return true
        }
    }
}
