package com.glazkov.outagewatch

import android.app.Application
import android.util.Log
import com.glazkov.outagewatch.api.ApiConfig
import com.glazkov.outagewatch.push.AndroidPushTokenProvider
import com.glazkov.outagewatch.push.PushTokens
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions

class OutageWatchApp : Application() {

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG && BuildConfig.API_BASE_URL.isNotEmpty()) {
            ApiConfig.baseUrl = BuildConfig.API_BASE_URL
        }
        initFirebase()
        PushTokens.provider = AndroidPushTokenProvider()
    }

    // Manual init instead of the google-services plugin: config values live in
    // local.properties (gitignored) and flow in via BuildConfig. When they're
    // absent (CI, fresh clone) the app runs fine without push.
    private fun initFirebase() {
        if (BuildConfig.FIREBASE_APP_ID.isEmpty()) {
            Log.w("OutageWatch", "Firebase config missing; push disabled for this build")
            return
        }
        val options = FirebaseOptions.Builder()
            .setApplicationId(BuildConfig.FIREBASE_APP_ID)
            .setApiKey(BuildConfig.FIREBASE_API_KEY)
            .setProjectId(BuildConfig.FIREBASE_PROJECT_ID)
            .setGcmSenderId(BuildConfig.FIREBASE_SENDER_ID)
            .build()
        FirebaseApp.initializeApp(this, options)
    }
}
