package com.chunkytofustudios.play_install_referrer

import android.content.Context
import android.os.Bundle
import android.util.Log
import com.android.installreferrer.api.InstallReferrerClient
import com.android.installreferrer.api.InstallReferrerStateListener
import com.android.installreferrer.api.ReferrerDetails
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import org.mockito.ArgumentCaptor
import org.mockito.MockedStatic
import org.mockito.Mockito

internal class PlayInstallReferrerPluginTest {
    private val client = Mockito.mock(InstallReferrerClient::class.java)
    private val plugin = PlayInstallReferrerPlugin()
    private lateinit var staticMocks: List<MockedStatic<*>>

    @BeforeTest
    fun setUp() {
        val builder = Mockito.mock(InstallReferrerClient.Builder::class.java)
        Mockito.`when`(builder.build()).thenReturn(client)
        val clientStatics = Mockito.mockStatic(InstallReferrerClient::class.java)
        clientStatics.`when`<InstallReferrerClient.Builder> {
            InstallReferrerClient.newBuilder(Mockito.any())
        }.thenReturn(builder)
        // android.util.Log is only a stub in JVM unit tests.
        staticMocks = listOf(clientStatics, Mockito.mockStatic(Log::class.java))

        val binding = Mockito.mock(FlutterPlugin.FlutterPluginBinding::class.java)
        Mockito.`when`(binding.applicationContext).thenReturn(Mockito.mock(Context::class.java))
        Mockito.`when`(binding.binaryMessenger).thenReturn(Mockito.mock(BinaryMessenger::class.java))
        plugin.onAttachedToEngine(binding)
    }

    @AfterTest
    fun tearDown() {
        staticMocks.forEach { it.close() }
    }

    @Test
    fun onMethodCall_getInstallReferrer_returnsReferrerDetails() {
        val bundle = Mockito.mock(Bundle::class.java)
        Mockito.`when`(bundle.getString("install_referrer")).thenReturn("utm_source=test")
        Mockito.`when`(bundle.getLong("referrer_click_timestamp_seconds")).thenReturn(1L)
        Mockito.`when`(bundle.getLong("install_begin_timestamp_seconds")).thenReturn(2L)
        Mockito.`when`(bundle.getLong("referrer_click_timestamp_server_seconds")).thenReturn(3L)
        Mockito.`when`(bundle.getLong("install_begin_timestamp_server_seconds")).thenReturn(4L)
        Mockito.`when`(bundle.getString("install_version")).thenReturn("1.0.0")
        Mockito.`when`(bundle.getBoolean("google_play_instant")).thenReturn(true)
        Mockito.`when`(client.installReferrer).thenReturn(ReferrerDetails(bundle))

        val result = getInstallReferrer()

        Mockito.verify(result).success(
            mapOf(
                "installReferrer" to "utm_source=test",
                "referrerClickTimestampSeconds" to 1L,
                "installBeginTimestampSeconds" to 2L,
                "referrerClickTimestampServerSeconds" to 3L,
                "installBeginTimestampServerSeconds" to 4L,
                "installVersion" to "1.0.0",
                "googlePlayInstantParam" to true
            )
        )
    }

    @Test
    fun onMethodCall_getInstallReferrer_nullBundle_returnsError() {
        // Play can return OK with a ReferrerDetails whose Bundle is null.
        Mockito.`when`(client.installReferrer).thenReturn(ReferrerDetails(null))

        val result = getInstallReferrer()

        Mockito.verify(result).error("UNEXPECTED_ERROR", "An unexpected error occurred.", null)
        Mockito.verify(result, Mockito.never()).success(Mockito.any())
    }

    private fun getInstallReferrer(): MethodChannel.Result {
        val result = Mockito.mock(MethodChannel.Result::class.java)
        plugin.onMethodCall(MethodCall("getInstallReferrer", null), result)

        val listener = ArgumentCaptor.forClass(InstallReferrerStateListener::class.java)
        Mockito.verify(client).startConnection(listener.capture())
        listener.value.onInstallReferrerSetupFinished(InstallReferrerClient.InstallReferrerResponse.OK)
        return result
    }
}
