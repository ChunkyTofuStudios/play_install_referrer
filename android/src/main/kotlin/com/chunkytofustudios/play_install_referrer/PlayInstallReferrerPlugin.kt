package com.chunkytofustudios.play_install_referrer

import android.content.Context
import android.os.DeadObjectException
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.NonNull
import com.android.installreferrer.api.InstallReferrerClient
import com.android.installreferrer.api.InstallReferrerStateListener
import com.android.installreferrer.api.ReferrerDetails
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import kotlin.collections.ArrayList


class PlayInstallReferrerPlugin : FlutterPlugin, MethodCallHandler {
    private lateinit var context: Context
    private lateinit var channel: MethodChannel
    private val mainHandler = Handler(Looper.getMainLooper())
    private var installReferrerExecutor: ExecutorService? = null
    private val pendingResults = ArrayList<Result>(1)
    private var referrerClient: InstallReferrerClient? = null
    private var referrerDetails: ReferrerDetails? = null
    private var referrerError: Pair<String, String>? = null

    private val isInstallReferrerPending: Boolean
        @Synchronized
        get() {
            return referrerClient != null && !isInstallReferrerResolved
        }

    private val isInstallReferrerResolved: Boolean
        @Synchronized
        get() {
            return referrerDetails != null || referrerError != null
        }

    override fun onAttachedToEngine(@NonNull flutterPluginBinding: FlutterPlugin.FlutterPluginBinding) {
        this.context = flutterPluginBinding.applicationContext
        installReferrerExecutor = Executors.newSingleThreadExecutor()
        channel = MethodChannel(
            flutterPluginBinding.binaryMessenger,
            "com.chunkytofustudios.play_install_referrer")
        channel.setMethodCallHandler(this)
    }

    override fun onMethodCall(@NonNull call: MethodCall, @NonNull result: Result) {
        if (call.method == "getInstallReferrer") {
            getInstallReferrer(result)
        } else {
            result.notImplemented()
        }

    }

    @Synchronized
    override fun onDetachedFromEngine(@NonNull binding: FlutterPlugin.FlutterPluginBinding) {
        pendingResults.clear()
        referrerClient?.endConnection()
        referrerClient = null
        installReferrerExecutor?.shutdownNow()
        installReferrerExecutor = null
        channel.setMethodCallHandler(null)
    }

    @Synchronized
    private fun getInstallReferrer(@NonNull result: Result) {
        if (isInstallReferrerResolved) {
            resolveInstallReferrerResult(result)
        } else {
            pendingResults.add(result)

            if (!isInstallReferrerPending) {
                referrerClient = InstallReferrerClient.newBuilder(context).build()
                try {
                    referrerClient?.startConnection(object : InstallReferrerStateListener {
                        override fun onInstallReferrerSetupFinished(responseCode: Int) {
                            handleOnInstallReferrerSetupFinished(responseCode)
                        }

                        override fun onInstallReferrerServiceDisconnected() {
                            Log.w("InstallReferrer", "Service disconnected, retrying...")
                            retryConnection()
                        }
                    })
                } catch (e: DeadObjectException) {
                    Log.e("InstallReferrer", "DeadObjectException caught: ${e.message}", e)
                    referrerError = Pair("DEAD_OBJECT_EXCEPTION", "Service connection lost unexpectedly.")
                    resolvePendingInstallReferrerResults()
                }
            }
        }
    }

    @Synchronized
    private fun handleOnInstallReferrerSetupFinished(responseCode: Int) {
        if (responseCode == InstallReferrerClient.InstallReferrerResponse.OK) {
            fetchInstallReferrerInBackground()
            return
        }

        try {
            when (responseCode) {
                InstallReferrerClient.InstallReferrerResponse.SERVICE_DISCONNECTED -> {
                    referrerError = Pair("SERVICE_DISCONNECTED", "Play Store service is not connected now.")
                }
                InstallReferrerClient.InstallReferrerResponse.SERVICE_UNAVAILABLE -> {
                    referrerError = Pair("SERVICE_UNAVAILABLE", "Connection couldn't be established.")
                }
                InstallReferrerClient.InstallReferrerResponse.FEATURE_NOT_SUPPORTED -> {
                    referrerError = Pair("FEATURE_NOT_SUPPORTED", "API not available on the current Play Store app.")
                }
                InstallReferrerClient.InstallReferrerResponse.DEVELOPER_ERROR -> {
                    referrerError = Pair("DEVELOPER_ERROR", "General errors caused by incorrect usage.")
                }
                InstallReferrerClient.InstallReferrerResponse.PERMISSION_ERROR -> {
                    referrerError = Pair("PERMISSION_ERROR", "App is not allowed to bind to the Service.")
                }
                else -> {
                    referrerError = Pair("UNKNOWN_ERROR", "InstallReferrerClient returned unknown response code.")
                }
            }
        } catch (e: Exception) {
            Log.e("InstallReferrer", "Unexpected error: ${e.message}", e)
            referrerError = Pair("UNEXPECTED_ERROR", "An unexpected error occurred.")
        }

        resolvePendingInstallReferrerResults()
        referrerClient?.endConnection()
        referrerClient = null
    }

    @Synchronized
    private fun fetchInstallReferrerInBackground() {
        val client = referrerClient
        if (client == null) {
            referrerError = Pair("BAD_STATE", "Referrer client is null.")
            resolvePendingInstallReferrerResults()
            return
        }

        val executor = installReferrerExecutor
        if (executor == null) {
            referrerError = Pair("BAD_STATE", "Install Referrer worker is not available.")
            resolvePendingInstallReferrerResults()
            client.endConnection()
            referrerClient = null
            return
        }

        try {
            executor.execute {
                var details: ReferrerDetails? = null
                var error: Pair<String, String>? = null

                try {
                    // getInstallReferrer performs a synchronous Binder transaction. Running it
                    // on the platform thread can freeze the UI if the Play Store is slow.
                    details = client.installReferrer
                } catch (e: DeadObjectException) {
                    Log.e("InstallReferrer", "DeadObjectException while fetching referrer: ${e.message}", e)
                    error = Pair("DEAD_OBJECT_EXCEPTION", "Service connection lost while retrieving data.")
                } catch (e: Exception) {
                    Log.e("InstallReferrer", "Unexpected error while fetching referrer: ${e.message}", e)
                    error = Pair("UNEXPECTED_ERROR", "An unexpected error occurred while retrieving data.")
                }

                mainHandler.post {
                    completeInstallReferrerFetch(client, details, error)
                }
            }
        } catch (e: RejectedExecutionException) {
            Log.e("InstallReferrer", "Install Referrer worker rejected the request: ${e.message}", e)
            referrerError = Pair("BAD_STATE", "Install Referrer worker is not available.")
            resolvePendingInstallReferrerResults()
            client.endConnection()
            referrerClient = null
        }
    }

    @Synchronized
    private fun completeInstallReferrerFetch(
        client: InstallReferrerClient,
        details: ReferrerDetails?,
        error: Pair<String, String>?
    ) {
        // Ignore a stale result after detachment or reconnection.
        if (referrerClient !== client) {
            client.endConnection()
            return
        }

        referrerDetails = details
        referrerError = error ?: if (details == null) {
            Pair("UNEXPECTED_ERROR", "Install Referrer returned no data.")
        } else {
            null
        }

        resolvePendingInstallReferrerResults()
        client.endConnection()
        referrerClient = null
    }

    @Synchronized
    private fun resolvePendingInstallReferrerResults() {
        pendingResults.forEach {
            resolveInstallReferrerResult(it)
        }
        pendingResults.clear()
    }

    @Synchronized
    private fun resolveInstallReferrerResult(@NonNull result: Result) {
        referrerDetails?.let {
            result.success(
                mapOf(
                    "installReferrer" to it.installReferrer,
                    "referrerClickTimestampSeconds" to it.referrerClickTimestampSeconds,
                    "installBeginTimestampSeconds" to it.installBeginTimestampSeconds,
                    "referrerClickTimestampServerSeconds" to it.referrerClickTimestampServerSeconds,
                    "installBeginTimestampServerSeconds" to it.installBeginTimestampServerSeconds,
                    "installVersion" to it.installVersion,
                    "googlePlayInstantParam" to it.googlePlayInstantParam
                )
            )
            return
        }
        referrerError?.let {
            result.error(it.first, it.second, null)
            return
        }
    }

    private fun retryConnection() {
        try {
            referrerClient?.endConnection()
            referrerClient = InstallReferrerClient.newBuilder(context).build()
            referrerClient?.startConnection(object : InstallReferrerStateListener {
                override fun onInstallReferrerSetupFinished(responseCode: Int) {
                    handleOnInstallReferrerSetupFinished(responseCode)
                }

                override fun onInstallReferrerServiceDisconnected() {
                    Log.w("InstallReferrer", "Retry failed, service disconnected again.")
                }
            })
        } catch (e: Exception) {
            Log.e("InstallReferrer", "Failed to retry connection: ${e.message}", e)
        }
    }
}
