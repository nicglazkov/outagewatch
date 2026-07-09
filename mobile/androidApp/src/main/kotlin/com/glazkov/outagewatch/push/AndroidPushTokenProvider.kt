package com.glazkov.outagewatch.push

import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.tasks.await

class AndroidPushTokenProvider : PushTokenProvider {
    override suspend fun getToken(): String? = try {
        FirebaseApp.getInstance() // throws if Firebase config was absent
        FirebaseMessaging.getInstance().token.await()
    } catch (_: Exception) {
        null
    }
}
