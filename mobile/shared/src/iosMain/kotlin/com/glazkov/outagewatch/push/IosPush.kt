package com.glazkov.outagewatch.push

/**
 * The FCM registration token, written by the Swift AppDelegate once Firebase
 * hands it over and read by shared code when it registers a subscription.
 *
 * Firebase's iOS SDK is Swift-native, so the split is deliberate: Swift owns
 * Firebase and only deposits a string here, which keeps Swift from having to
 * implement a Kotlin `suspend` function.
 */
object IosPush {
    @kotlin.concurrent.Volatile
    var token: String? = null
}

class IosPushTokenProvider : PushTokenProvider {
    override suspend fun getToken(): String? = IosPush.token
}
