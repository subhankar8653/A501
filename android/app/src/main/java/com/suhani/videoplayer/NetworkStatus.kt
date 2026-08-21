package com.suhani.videoplayer

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/**
 * User ask: "offline mein wahi player chahiye jo online use hota hai, bas jo
 * feature internet maangte hain woh off kar do" — is decision ke liye ek
 * single, saari activities/bridges mein reuse hone wali connectivity check.
 *
 * Sirf "device ka radio connected hai ya nahi" batata hai (WiFi/mobile/ethernet
 * transport ke saath NET_CAPABILITY_VALIDATED) — asli internet reachability
 * (jaise backend up hai ya nahi) yeh nahi batata, bas cast/online-subtitle-search
 * jaisi cheezein turant disable karne ke liye kaafi hai.
 */
object NetworkStatus {
    fun isAvailable(context: Context): Boolean {
        return try {
            val cm = context.applicationContext
                .getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
            val network = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(network) ?: return false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        } catch (_: Exception) {
            false
        }
    }
}
