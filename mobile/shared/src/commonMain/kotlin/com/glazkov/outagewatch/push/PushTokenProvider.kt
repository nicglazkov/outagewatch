package com.glazkov.outagewatch.push

/**
 * Platform push-token source. Android wires FCM; iOS wires FCM-over-APNs from
 * the iosApp entry point. Null token means push is unavailable (no config,
 * permission denied, simulator) - the app still works read-only.
 */
interface PushTokenProvider {
    suspend fun getToken(): String?
}

object PushTokens {
    var provider: PushTokenProvider? = null

    suspend fun current(): String? = provider?.getToken()
}
