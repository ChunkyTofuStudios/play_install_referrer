package com.chunkytofustudios.play_install_referrer

import android.content.Context
import android.os.DeadObjectException
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
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
import kotlin.collections.ArrayList
import kotlin.concurrent.thread


class PlayInstallReferrerPlugin : FlutterPlugin, MethodCallHandler {
    private lateinit var context: Context
    private lateinit var channel: MethodChannel
    // Plugin state is only touched on the main thread. The blocking Binder call runs on a
    // background thread, which posts its outcome back through this handler.
    private val mainHandler = Handler(Looper.getMainLooper())
    private val timeoutToken = Any()
    private val pendingResults = ArrayList<Result>(1)
    private var referrerClient: InstallReferrerClient? = null
    private var referrerDetails: Map<String, Any?>? = null
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
        mainHandler.removeCallbacksAndMessages(null)
        pendingResults.clear()
        referrerClient?.endConnection()
        referrerClient = null
        channel.setMethodCallHandler(null)
    }

    @Synchronized
    private fun getInstallReferrer(@NonNull result: Result) {
        if (isInstallReferrerResolved) {
            resolveInstallReferrerResult(result)
        } else {
            pendingResults.add(result)
            mainHandler.postAtTime(
                { timeOutPendingResult(result) },
                timeoutToken,
                SystemClock.uptimeMillis() + INSTALL_REFERRER_TIMEOUT_MILLIS
            )

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
        try {
            when (responseCode) {
                InstallReferrerClient.InstallReferrerResponse.OK -> {
                    val client = referrerClient
                    if (client != null) {
                        // Pending calls are resolved later by completeInstallReferrerFetch.
                        fetchInstallReferrerInBackground(client)
                        return
                    }
                    referrerError = Pair("BAD_STATE", "Referrer client is null.")
                }
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

        finishInstallReferrer()
    }

    private fun fetchInstallReferrerInBackground(client: InstallReferrerClient) {
        // getInstallReferrer is a synchronous Binder call into the Play Store. Making it on the
        // main thread freezes the UI, and can trigger an ANR, whenever the Play Store is slow.
        thread(name = "PlayInstallReferrer") {
            var details: Map<String, Any?>? = null
            var error: Pair<String, String>? = null

            try {
                // Read every field now, inside the try: Play can return OK with a
                // ReferrerDetails whose internal Bundle is null, and its getters throw.
                details = client.installReferrer.toMap()
            } catch (e: DeadObjectException) {
                Log.e("InstallReferrer", "DeadObjectException while fetching referrer: ${e.message}", e)
                error = Pair("DEAD_OBJECT_EXCEPTION", "Service connection lost while retrieving data.")
            } catch (e: IllegalStateException) {
                // The service disconnected before the call was made.
                Log.e("InstallReferrer", "Service disconnected before fetching referrer: ${e.message}", e)
                error = Pair("SERVICE_DISCONNECTED", "Play Store service is not connected now.")
            } catch (e: Exception) {
                Log.e("InstallReferrer", "Unexpected error while fetching referrer: ${e.message}", e)
                error = Pair("UNEXPECTED_ERROR", "An unexpected error occurred.")
            }

            mainHandler.post { completeInstallReferrerFetch(client, details, error) }
        }
    }

    @Synchronized
    private fun completeInstallReferrerFetch(
        client: InstallReferrerClient,
        details: Map<String, Any?>?,
        error: Pair<String, String>?
    ) {
        if (client !== referrerClient) {
            // A reconnect replaced this client mid-fetch. Its error is moot, since the new
            // connection reports its own outcome, but a successful result is still valid.
            if (details == null || referrerDetails != null) return
        }

        referrerDetails = details
        referrerError = error
        finishInstallReferrer()
    }

    // Resolves pending calls with the outcome and releases the Play Store connection.
    @Synchronized
    private fun finishInstallReferrer() {
        resolvePendingInstallReferrerResults()
        referrerClient?.endConnection()
        referrerClient = null
    }

    @Synchronized
    private fun timeOutPendingResult(@NonNull result: Result) {
        // The fetch carries on; its outcome is cached for later calls when it arrives.
        if (pendingResults.remove(result)) {
            Log.w("InstallReferrer", "Timed out waiting for the Play Store.")
            result.error("TIMEOUT", "Play Store did not respond in time.", null)
        }
    }

    @Synchronized
    private fun resolvePendingInstallReferrerResults() {
        mainHandler.removeCallbacksAndMessages(timeoutToken)
        pendingResults.forEach {
            resolveInstallReferrerResult(it)
        }
        pendingResults.clear()
    }

    @Synchronized
    private fun resolveInstallReferrerResult(@NonNull result: Result) {
        referrerDetails?.let {
            result.success(it)
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

    private fun ReferrerDetails.toMap(): Map<String, Any?> = mapOf(
        "installReferrer" to installReferrer,
        "referrerClickTimestampSeconds" to referrerClickTimestampSeconds,
        "installBeginTimestampSeconds" to installBeginTimestampSeconds,
        "referrerClickTimestampServerSeconds" to referrerClickTimestampServerSeconds,
        "installBeginTimestampServerSeconds" to installBeginTimestampServerSeconds,
        "installVersion" to installVersion,
        "googlePlayInstantParam" to googlePlayInstantParam
    )

    companion object {
        private const val INSTALL_REFERRER_TIMEOUT_MILLIS = 10_000L
    }
}
