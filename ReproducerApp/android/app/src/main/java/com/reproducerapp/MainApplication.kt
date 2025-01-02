package com.reproducerapp

import android.app.Application
import android.util.Log
import android.content.SharedPreferences
import com.facebook.react.PackageList
import com.facebook.react.ReactApplication
import com.facebook.react.ReactInstanceManager
import com.facebook.react.ReactNativeHost
import com.facebook.react.ReactPackage
import com.facebook.react.defaults.DefaultNewArchitectureEntryPoint.load
import com.facebook.react.defaults.DefaultReactHost.getDefaultReactHost
import com.facebook.react.defaults.DefaultReactNativeHost
import com.facebook.react.soloader.OpenSourceMergedSoMapping
import com.facebook.soloader.SoLoader
import com.facebook.react.modules.core.DeviceEventManagerModule
import com.android.installreferrer.api.InstallReferrerClient
import com.android.installreferrer.api.InstallReferrerStateListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import com.facebook.react.bridge.ReactContext

class MainApplication : Application(), ReactApplication {

    companion object {
        private const val PREFS_NAME = "AppPrefs"
        private const val PREF_REFERRER_EMITTED = "ReferrerEmitted"
        private const val TAG = "InstallReferrer"
    }

    private val sharedPreferences: SharedPreferences by lazy {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
    }

    override val reactNativeHost: ReactNativeHost by lazy {
        object : DefaultReactNativeHost(this) {
            override fun getPackages(): List<ReactPackage> =
                PackageList(this).packages.apply {
                    // add(MinimizerPackage())
                }

            override fun getJSMainModuleName(): String = "index"

            override fun getUseDeveloperSupport(): Boolean = BuildConfig.DEBUG

            override val isNewArchEnabled: Boolean = BuildConfig.IS_NEW_ARCHITECTURE_ENABLED
            override val isHermesEnabled: Boolean = BuildConfig.IS_HERMES_ENABLED
        }
    }

    override val reactHost by lazy {
        getDefaultReactHost(applicationContext, reactNativeHost)
    }

    override fun onCreate() {
        super.onCreate()
        try {
            SoLoader.init(this, OpenSourceMergedSoMapping)

            if (BuildConfig.IS_NEW_ARCHITECTURE_ENABLED) {
                load()
            }

            // Start handling the Install Referrer
            handleInstallReferrer()
        } catch (e: Exception) {
            logError("Application initialization failed", e)
        }
    }

    private fun handleInstallReferrer() {
        val isReferrerEmitted = sharedPreferences.getBoolean(PREF_REFERRER_EMITTED, false)
        logInfo("Emitted value: $isReferrerEmitted")

        val referrerClient = InstallReferrerClient.newBuilder(this).build()
        try {
            referrerClient.startConnection(object : InstallReferrerStateListener {
                override fun onInstallReferrerSetupFinished(responseCode: Int) {
                    CoroutineScope(Dispatchers.IO).launch {
                        try {
                            if (responseCode == InstallReferrerClient.InstallReferrerResponse.OK) {
                                val response = referrerClient.installReferrer
                                processReferrer(response.installReferrer, isReferrerEmitted)
                            } else {
                                logError("Setup failed with response code: $responseCode")
                            }
                        } catch (e: Exception) {
                            logError("Error while processing install referrer", e)
                        } finally {
                            referrerClient.endConnection()
                        }
                    }
                }

                override fun onInstallReferrerServiceDisconnected() {
                    logWarning("Install Referrer service disconnected.")
                }
            })
        } catch (e: Exception) {
            logError("Error initializing InstallReferrerClient", e)
        }
    }

    private fun processReferrer(referrer: String, isReferrerEmitted: Boolean) {
        logInfo("Referrer: $referrer")

        val reactContext = getReactContext()
        if (reactContext != null) {
            sendReferrerToReactNative(reactContext, referrer)
        } else {
            logInfo("ReactContext not ready. Queuing referrer.")
            addReactContextListener(referrer)
        }
        markReferrerAsEmitted()
    }

    private fun getReactContext(): ReactContext? {
        val reactApplication = applicationContext as? ReactApplication ?: return null
        return reactApplication.reactNativeHost.reactInstanceManager.currentReactContext
    }

    private fun markReferrerAsEmitted() {
        sharedPreferences.edit().putBoolean(PREF_REFERRER_EMITTED, true).apply()
    }

    private fun sendReferrerToReactNative(reactContext: ReactContext, referrer: String) {
        try {
            val eventEmitter = reactContext.getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java)
            if (eventEmitter != null) {
                eventEmitter.emit("InstallReferrer", referrer)
                logInfo("Referrer sent to React Native: $referrer")
            } else {
                logWarning("EventEmitter is null. Could not send referrer to React Native.")
            }
        } catch (e: Exception) {
            logError("Error sending referrer to React Native", e)
        }
    }

    private fun addReactContextListener(referrer: String) {
        val reactInstanceManager = reactNativeHost.reactInstanceManager

        val listener = object : ReactInstanceManager.ReactInstanceEventListener {
            override fun onReactContextInitialized(reactContext: ReactContext) {
                try {
                    sendReferrerToReactNative(reactContext, referrer)
                    logInfo("Referrer sent successfully")
                } catch (e: Exception) {
                    logError("Error during onReactContextInitialized", e)
                } finally {
                    reactInstanceManager.removeReactInstanceEventListener(this)
                }
            }
        }

        reactInstanceManager.addReactInstanceEventListener(listener)
        logInfo("ReactContextListener added to handle referrer.")
    }

    private fun logDebug(message: String) {
        if (BuildConfig.DEBUG) Log.d(TAG, message)
    }

    private fun logInfo(message: String) {
        Log.i(TAG, message)
    }

    private fun logError(message: String, throwable: Throwable? = null) {
        Log.e(TAG, message, throwable)
    }

    private fun logWarning(message: String) {
        Log.w(TAG, message)
    }
}
