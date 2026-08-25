package com.suhani.videoplayer

import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import java.io.File

/**
 * BUFFERING SLOW ki asli root-cause: poore app mein (fullscreen PlayerActivity ho ya
 * MainActivity ka inline/mini player) kahin bhi koi custom DataSource.Factory nahi tha —
 * seedha ExoPlayer ka bilkul default HTTP data source use ho raha tha, jiska matlab:
 *
 *  1) Connect/read timeout sirf 8 second — thoda bhi slow/flaky mobile network mile to
 *     baar-baar timeout+retry, jo "buffering atak gayi" jaisa mehsoos hota hai.
 *  2) Cross-protocol redirect OFF by default — kayi CDN/Telegram stream links http->https
 *     ya ek host se doosre host par redirect karte hain; is wajah se woh links silently
 *     fail ho jaate the ya loop mein retry hote rehte the.
 *  3) DISK CACHE bilkul nahi tha — same video dobara kholna, seek peeche karna, ya
 *     mini-player se fullscreen (aur wapas) switch karna, har baar POORA data network
 *     se dubara download karta tha. Yehi sabse zyada "slow/laggy" feel deta hai.
 *
 * Yeh singleton dono players ke liye ek hi tuned, cache-backed DataSource.Factory deta
 * hai — cache app ke andar hi rehta hai (kisi extra permission ki zaroorat nahi) aur
 * LRU evictor se apne aap purana/kam-use hua data hata deta hai taaki disk space bhar na jaaye.
 */
@UnstableApi
object PlayerNetwork {

    private const val CACHE_DIR_NAME = "media_cache"
    private const val CACHE_MAX_BYTES = 500L * 1024 * 1024 // rolling ~500 MB, LRU evict

    @Volatile private var cacheInstance: SimpleCache? = null

    private fun cache(context: Context): SimpleCache {
        return cacheInstance ?: synchronized(this) {
            cacheInstance ?: SimpleCache(
                File(context.applicationContext.cacheDir, CACHE_DIR_NAME),
                LeastRecentlyUsedCacheEvictor(CACHE_MAX_BYTES),
                StandaloneDatabaseProvider(context.applicationContext)
            ).also { cacheInstance = it }
        }
    }

    private fun httpFactory(): DefaultHttpDataSource.Factory =
        DefaultHttpDataSource.Factory()
            .setUserAgent("A501-Player/1.0 (Android)")
            // Bug fix: default 8000ms bahut kam tha slow/mobile-data connections ke liye.
            .setConnectTimeoutMs(20_000)
            .setReadTimeoutMs(20_000)
            // Bug fix: kayi stream links redirect karte hain (http->https ya doosre CDN
            // host par) — pehle yeh band tha isliye aisi links play hi nahi hoti thi.
            .setAllowCrossProtocolRedirects(true)

    /** Cache-backed DataSource.Factory — ek baar jo hissa download ho chuka hai wo
     *  agli baar (seek peeche, replay, mini<->fullscreen switch) disk se turant milta
     *  hai, network par dubara nahi jaana padta. Agar cache mein koi dikkat aaye to
     *  seedha network par fallback ho jaata hai (IGNORE_CACHE_ON_ERROR). */
    private fun cacheBackedHttpFactory(context: Context): DataSource.Factory {
        val upstream = DefaultDataSource.Factory(context.applicationContext, httpFactory())
        return CacheDataSource.Factory()
            .setCache(cache(context))
            .setUpstreamDataSourceFactory(upstream)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
    }

    // A501 — direct phone<->Telegram migration (see
    // a501-direct-streaming-migration-prompt.md). This is the single call
    // site PlayerActivity uses to build its DataSource.Factory, so wrapping
    // it here (instead of touching PlayerActivity.kt) is enough to put the
    // on-device TDLib path in front of the existing Railway-proxy path for
    // the WHOLE app, native-only, with zero risk to the proxy path itself.
    //
    // When TdlibConfig.ENABLED is false (default), FallbackDataSource always
    // falls straight through to the exact same cache-backed HTTP factory
    // this returned before this migration — behavior is unchanged until the
    // flag is deliberately turned on with a real TDLib SDK wired in.
    fun dataSourceFactory(context: Context): DataSource.Factory {
        val proxyFallback = cacheBackedHttpFactory(context)
        return FallbackDataSource.Factory(
            primaryFactory = TdlibDataSource.Factory(),
            secondaryFactory = proxyFallback,
        )
    }
}
