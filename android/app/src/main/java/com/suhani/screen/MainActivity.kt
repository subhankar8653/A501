package com.suhani.screen

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebChromeClient
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.DefaultTimeBar
import androidx.media3.ui.PlayerView
import com.suhani.videoplayer.PlayerNetwork
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import androidx.webkit.WebViewAssetLoader
import com.suhani.videoplayer.EqualizerAudioProcessor
import com.suhani.videoplayer.FfmpegRenderersFactory
import com.suhani.videoplayer.PlayerActivity
import com.suhani.videoplayer.SharedPlayerHolder
import com.suhani.videoplayer.HistoryStore
import com.suhani.videoplayer.HistoryEntry
import com.suhani.videoplayer.GesturePrefs
import com.suhani.videoplayer.TdlibClient
import com.suhani.videoplayer.AmbientGlowView
import com.google.android.material.bottomsheet.BottomSheetDialog
import org.json.JSONArray
import org.json.JSONObject

/**
 * Web frontend (VideoPlayer.jsx) isi bridge se video ko YouTube-jaisa chhote
 * (inline) native player mein play karta hai, HTML5 <video> tag ke bajaye:
 *
 *   window.AndroidPlayer.mount(uri, title, qualitiesJson)
 *     -> is jagah (jiska rect updateRect se milta hai) ek chhota native
 *        player start ho jaata hai, autoplay ke saath, apne poore controls
 *        (play/pause, seek, lock, audio track, subtitle toggle, speed,
 *        decoder select, PiP, aspect-ratio, fullscreen) ke saath — bas icon
 *        size chhote (is area ke hisaab se), fullscreen jaisi hi feature parity.
 *   window.AndroidPlayer.updateRect(left, top, width, height)  // CSS px
 *     -> jab bhi video-container ka size/position badle (scroll/resize),
 *        chhote player ko wahi exact jagah par move/resize karo.
 *   window.AndroidPlayer.unmount()
 *     -> naya video / page chhodne par chhota player hata do.
 *
 * Chhote player ke "more" (3-dot) button aur fullscreen-expand button dono
 * poore-screen wale PlayerActivity ko khol dete hain — equalizer/cast/
 * A-B-repeat/chapters jaisi bahut advanced cheezein abhi bhi wahi hain
 * (dobara chhote screen par nahi banayi), lekin audio-track/decoder-select
 * ab dono jagah available hain. Fullscreen wahi position/playing-state ke
 * saath khulta hai, aur wapas aane par chhota player sync ho jaata hai
 * (onActivityResult se).
 *
 * - Online stream: uri = "https://..." ya "*.m3u8"/"*.mpd" link
 * - Offline/downloaded: uri = local file ka "file://" ya "content://" path
 * - qualitiesJson = website ke jaisa hi quality list: '[{"url":"...","label":"480p"},...]'
 */
class WebAppInterface(private val activity: MainActivity) {
    // Crash fix: WebView JS-thread se yeh @JavascriptInterface calls activity ke
    // finish()/destroy ho chuke hone ke baad bhi aa sakti hain (WebView apni JS
    // thread par chalta rehta hai jab tak destroy() poora na ho). Us case mein
    // runOnUiThread post to safe hai, lekin andar mountInlinePlayer() jaisa view
    // inflate/attach karne wala kaam ek dead window par crash karta — isliye
    // isFinishing/isDestroyed check karke silently drop karte hain.
    private inline fun runOnUiThreadSafely(crossinline action: () -> Unit) {
        if (activity.isFinishing || activity.isDestroyed) return
        activity.runOnUiThread { if (!activity.isFinishing && !activity.isDestroyed) action() }
    }

    // PERMANENT FIX (user report + screenshot: pehla hi fix "PiP → Home par
    // player" wala regression laaya — "video khulte hi bilkul black screen,
    // koi control nahi, sirf background mein audio chalta hai"): pichla fix
    // (`isCurrentlyOnWatchPage()`) overlay dikhane se pehle NATIVE
    // `webView.url` se current path confirm karta tha. Root cause of THIS
    // naye regression: `webView.getUrl()` Chromium ke renderer<->browser
    // process IPC ke through async update hota hai — jab bhi user pehli baar
    // video par tap karta (Detail -> navigate('/watch/...') -> Player.jsx
    // turant mount -> VideoPlayer ka effect turant `mount()` bridge call
    // karta), yeh sab EK hi JS tick mein back-to-back hota hai. Us waqt
    // native `webView.url` abhi tak purane path (jaha se navigate hua) par
    // hi ho sakta hai — IPC ko catch-up karne ka waqt hi nahi mila. Result:
    // `isCurrentlyOnWatchPage()` galti se false nikalta, overlay kabhi
    // VISIBLE hi nahi hota (chahe player khud bilkul sahi chal/play ho raha
    // ho — isiliye audio sunayi deta tha, sirf visual overlay stuck-hidden
    // reh jaata).
    // Fix: WebView ke apne (racy) `url` par bharosa karne ke bajaye, JS
    // khud apna `window.location.pathname` — jo us EXACT waqt already
    // sahi/updated hota hai (history.pushState turant, synchronously hota
    // hai) — seedha isi bridge call ke saath bhej deta hai. Ab koi IPC-lag
    // wali race hi nahi bachti; native side ko kabhi native `webView.url`
    // par guess karne ki zaroorat nahi padti jab JS khud sach bata sakta hai.
    @JavascriptInterface
    fun mount(uri: String, title: String, qualitiesJson: String, currentPath: String) {
        runOnUiThreadSafely { activity.mountInlinePlayer(uri, title, qualitiesJson, currentPath) }
    }

    @JavascriptInterface
    fun updateRect(left: Double, top: Double, width: Double, height: Double, currentPath: String) {
        runOnUiThreadSafely { activity.updateInlinePlayerRect(left, top, width, height, currentPath) }
    }

    @JavascriptInterface
    fun unmount() {
        runOnUiThreadSafely { activity.unmountInlinePlayer() }
    }

    // Web page (Player.jsx) yeh call karta hai jab bhi pata chale ki agla/pichla
    // episode maujood hai ya nahi (series ki Up Next list se) — isse chhote
    // player ke prev/next button ka enable/dim state sahi rehta hai. Native
    // khud episode URL nahi jaanta — bas JS ko wapas "next/prev dabaya" bata
    // deta hai, aur JS apni normal navigate() se episode badal deta hai
    // (isliye title/Up-Next-list/comments waghera bhi sync rehte hain).
    @JavascriptInterface
    fun setAdjacentEpisodes(hasNext: Boolean, hasPrev: Boolean) {
        runOnUiThreadSafely { activity.updateInlineAdjacentEpisodes(hasNext, hasPrev) }
    }

    // See `inlineAccentColor` field comment above for the full "why" — this
    // is the JS-side entry point ThemeContext.jsx calls whenever the color
    // theme changes, so the native inline player's accent-colored controls
    // (play button/ring, quality text, progress bar) stay in sync with
    // whatever theme the web page is currently showing.
    @JavascriptInterface
    fun setThemeColor(hex: String) {
        runOnUiThreadSafely { activity.setInlineThemeColor(hex) }
    }

}

/**
 * ROOT CAUSE FIX (user ask: "offline video player simple hai, online jaisa
 * poora-feature player chahiye — bas jo internet maange woh feature off kar
 * do"): downloadsStore.js (web frontend) pehle download ko fetch() se JS
 * memory mein le kar seedha IndexedDB Blob mein save karta tha, aur playback
 * ek `blob:` object URL se hoti thi — jo `window.AndroidPlayer` (rich native
 * player) kabhi mount nahi kar paata (blob: sirf WebView JS-process ke andar
 * hi valid hai), isliye offline downloads ek simple web `<video>` fallback
 * par gir jaate the.
 *
 * Is bridge se ab download hi native side par (asli file ke roop mein) hota
 * hai — completion par ek `content://` URI milta hai jo `window.AndroidPlayer`
 * seedha mount kar sakta hai, bilkul online stream jaisa hi. Progress/done/
 * error sab JS ko `window.__nativeDownload*` callbacks (main.jsx/downloadsStore.js
 * mein registered) ke through evaluateJavascript se wapas bheja jaata hai.
 */
class WebDownloadInterface(private val activity: MainActivity) {
    // BUG FIX (user report: "download pe lagata hu, kisi aur app mein
    // chala jaata hu to yahan download cancel ho jaata hai, notification bar
    // mein bhi progress nahi dikhta"): pehle yahin seedha
    // `NativeDownloadManager.start()` ek plain background Thread ke saath
    // call hota tha — koi foreground service nahi, isliye app background
    // jaate hi (khaaskar aggressive battery-saver OEM phones par) poora
    // process hi kill ho jaata aur download ke saath-saath ruk jaata, aur
    // kabhi koi notification bhi nahi thi. Ab download `DownloadService`
    // (foreground service + ongoing progress notification) ke andar chalta
    // hai — dekho uska doc comment. `title` naya param hai (video/episode ka
    // naam) — notification mein dikhane ke liye; JS side (downloadsStore.js)
    // ab isko bhejta hai.
    @JavascriptInterface
    fun startDownload(id: String, url: String, title: String) {
        DownloadService.start(activity, id, url, title)
    }

    @JavascriptInterface
    fun cancelDownload(id: String) {
        DownloadService.cancel(activity, id)
    }

    /** FEATURE (user ask: "watch shuru hote hi chal raha download ruk
     *  jaega, watch band hote hi apne aap wahin se aage badhega"): JS side
     *  (downloadsStore.js) isko tab bulata hai jab watching shuru hoti hai
     *  aur koi download abhi active tha — network activity turant ruk
     *  jaati hai (bandwidth streaming ko poora milta hai), lekin jitna ho
     *  chuka woh disk/TDLib cache par bana rehta hai. Resume seedha
     *  startDownload() dobara call karke hota hai (same id/url). */
    @JavascriptInterface
    fun pauseDownload(id: String) {
        DownloadService.pause(activity, id)
    }

    @JavascriptInterface
    fun deleteDownload(id: String) {
        // Agar yeh abhi download ho hi raha ho, pehle DownloadService ko cancel
        // karo (taaki notification/foreground state bhi sahi se saaf ho), fir
        // file delete karo. Already-complete downloads ke liye cancel() no-op
        // hai (active map mein hai hi nahi).
        DownloadService.cancel(activity, id)
        NativeDownloadManager.delete(activity, id)
    }

    /** Purane/existing downloads (app restart ke baad) ke liye — playback shuru karne se
     *  pehle web side confirm kar leta hai ki file abhi bhi disk par maujood hai. */
    @JavascriptInterface
    fun getDownloadUri(id: String): String {
        return NativeDownloadManager.contentUriFor(activity, id) ?: ""
    }
}

class MainActivity : AppCompatActivity(), DownloadService.ProgressListener {

    // ARCHITECTURE CHANGE (user demand: "app offline mein bhi chalna chahiye,
    // sirf website fix karne se kaam nahi chalega"): pehle yeh Activity sirf
    // ek WebView tha jo har cold-start par https://a501.vercel.app/ (live
    // hosted frontend) fetch karta tha — offline hone par sirf WebView ke apne
    // fragile HTTP-cache fallback (offlineAwareCacheMode() neeche) par bharosa
    // tha, jo agar cache miss/evict ho jaaye to poora app hi nahi khulta tha.
    // Ab poora built frontend (frontend/dist/*) CI build ke waqt seedha APK ke
    // andar (app/src/main/assets/) bundle hota hai — is Activity ko ab kisi
    // bhi network call ki zaroorat NAHI hai app-shell dikhane ke liye, chahe
    // device kabhi online hua hi na ho. Sirf live search/streaming (API calls,
    // Player.jsx ke andar) ke liye ab bhi internet chahiye — woh alag se JS ke
    // apne connectivity/error handling se handle hota hai.
    //
    // Raw file:// URL load nahi kar rahe (bhale hi WebSettings.allowFileAccess
    // se possible hota) kyunki file:// origin par WebView by default JS ko
    // remote XHR/fetch (yaani backend API calls) karne se rok deta hai
    // (universal file-access restriction) — isse Search/Streams poori tarah
    // tootT jaate. Google ka official solution WebViewAssetLoader hai: yeh
    // bundled assets ko ek proper https-jaisi virtual origin
    // (https://appassets.androidplatform.net/) se serve karta hai, isliye
    // fetch()/CORS bilkul normal hosted-website jaisa hi behave karta hai.
    private val APP_URL = "https://appassets.androidplatform.net/index.html"
    private lateinit var assetLoader: WebViewAssetLoader
    private val REQUEST_FULLSCREEN_PLAYER = 9001
    private val resizeModes = intArrayOf(
        AspectRatioFrameLayout.RESIZE_MODE_FIT,
        AspectRatioFrameLayout.RESIZE_MODE_FIXED_WIDTH,
        AspectRatioFrameLayout.RESIZE_MODE_FIXED_HEIGHT,
        AspectRatioFrameLayout.RESIZE_MODE_FILL,
        AspectRatioFrameLayout.RESIZE_MODE_ZOOM
    )

    private lateinit var webView: WebView
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var initialLoadingView: View
    private lateinit var loadErrorView: View
    private lateinit var loadErrorTitle: TextView
    private lateinit var loadErrorMessage: TextView
    private var hasLoadedOnce = false
    // Premium polish: loading label ab static 0.6-alpha text nahi, halka
    // "breathing" pulse leta hai (0.4 <-> 0.85 alpha loop) jab tak page load
    // ho rahi hai — static spinner+text ki jagah thoda zyada "alive"/premium
    // feel deta hai. fadeOutLoadingView() isko cancel kar deta hai.
    private var loadingLabelPulse: android.animation.ObjectAnimator? = null

    // targetSdk 36 par Android forcibly edge-to-edge draw karta hai, isliye status
    // bar ke peeche WebView (aur usi ke upar rakha chhota native player) dono status
    // bar ke "andar" ghus jaate the. Ab is inset ko capture karke: (1) WebView ko
    // status bar ke neeche se shuru karte hain, (2) inline player ka rect isi offset
    // ko add karke calculate karte hain, taaki dono screen ke sahi jagah par rahen.
    private var statusBarInsetPx = 0

    // Chhota (inline) native player aur uske controls
    private var inlinePlayer: ExoPlayer? = null
    private var inlineOverlay: FrameLayout? = null
    private var inlinePlayerView: PlayerView? = null
    private var inlineAmbientGlowView: AmbientGlowView? = null
    // FEATURE (user ask: "video mein subtitle hamesha off rahe by default, user chahe
    // to on kare"): pehle yeh "manually disabled" tha (default false = auto-ON jaise
    // hi koi text track milti), ab ulta hai — "manually enabled" (default false = OFF),
    // user Subtitle menu se khud koi track chune tabhi true hota hai.
    private var subtitleManuallyEnabled = false
    private var audioManuallyDisabled = false
    private var resizeModeIndex = 0
    private var decoderMode = 1 // 0 = HW, 1 = HW+, 2 = SW — same default as fullscreen
    private var inlineLongPressSpeedActive = false
    private var inlineSpeedBeforeLongPress = 1f

    // BUG FIX (user report): "chhote player mein ek baar double-tap karne par
    // 10s dikhta hai, but usi time ke aaspaas dobara double-tap karo to bhi
    // sirf 10s hi dikhta hai (20s nahi)" — Android ka GestureDetector sirf
    // pehle do taps ko "double tap" maanta hai, uske baad turant kiye gaye
    // taps ko dobara double-tap event nahi deta. Bade (fullscreen) player
    // mein isiliye ACTION_DOWN par khud check kiya jaata hai ki pichla seek
    // tap abhi-abhi (continuation window ke andar) usi side par hua tha ya
    // nahi — agar haan, to GestureDetector ko bypass karke seedha seconds
    // accumulate kar dete hain (10s -> 20s -> 30s ...). Yahi exact logic ab
    // chhote player mein bhi hai.
    private var inlineLastSeekSide = 0 // 0 = koi active session nahi, -1 = left, 1 = right
    private var inlineSeekAccumulatedUnits = 0
    private var inlineLastSeekTapTime = 0L
    private var inlineConsumingSeekContinuation = false
    private val INLINE_SEEK_CONTINUATION_WINDOW_MS = 700L

    // BUG FIX (user report): bade (fullscreen) player mein continuation window
    // khatm hote hi session khud reset ho jaata hai (seekSessionResetRunnable,
    // dekho PlayerActivity.kt) — taaki ek naya, baad mein kabhi bhi kiya gaya
    // double-tap dobara "10s" se shuru ho, "30s/40s..." se nahi. Chhote player
    // mein yeh reset missing tha: inlineSeekAccumulatedUnits/inlineLastSeekSide
    // sirf agle mount par (ya kabhi nahi) reset hote the, isliye kaafi der baad
    // kiya gaya bilkul naya double-tap bhi purani session ke units par hi
    // accumulate hoke galat (bada) total dikhata tha. Ab bade player jaisa hi
    // ek Handler-based session-reset hai.
    private val inlineSeekSessionHandler = Handler(Looper.getMainLooper())
    private val inlineSeekSessionResetRunnable = Runnable {
        inlineLastSeekSide = 0
        inlineSeekAccumulatedUnits = 0
    }

    // Swipe-down-to-PiP on the inline player itself — video ko hold karke
    // neeche push karne se bhi chevron jaisa hi minimize hota hai.
    private var inlineSwipeStartX = 0f
    private var inlineSwipeStartY = 0f
    private var inlineSwipeDragging = false
    private val INLINE_DRAG_PIP_THRESHOLD = 90f

    // User ki request: jab chhote inline player se PiP shuru ki jaaye, to WebView
    // ko is (watch) page se PEECHE (info/Detail page) navigate kar do — taaki PiP
    // floating window ke peeche wahi "clean" pichli page dikhe, is page ka khaali/
    // black video-hole nahi. Yeh flag track karta hai ki aisi ek back-navigation
    // abhi pending hai, taaki PlayerActivity se wapas aane par sahi se decide ho
    // sake: expand/normal-return par WebView ko usi watch page par FORWARD wapas
    // le jaana hai (aur video resume karna hai), lekin genuine PiP "X" close par
    // kuchh bhi nahi karna — na forward navigate, na koi playback background mein.
    private var didNavigateBackForPip = false
    // BUG FIX (user report: "PiP expand karne par Home page par khul jaata hai,
    // asli watch page par nahi"): `didNavigateBackForPip` + `webView.goForward()`
    // is assumption par tika tha ki PiP floating rehte waqt WebView ki forward-
    // history bilkul waisi hi rahegi jaisi goBack() karte waqt thi. Lekin PiP ke
    // dauraan user WebView mein kahin bhi normally ghoom sakta hai (Home tab,
    // koi doosra title) — koi bhi naya client-side navigate() us forward-entry
    // ko turant discard kar deta hai, isliye goForward() expand par ya to kuch
    // nahi karta ya galat page par le jaata — result: "Home par hi khula reh
    // jaana", bilkul jaisa report hua. Fix: exact watch-page path yahan yaad
    // rakho (goBack() se theek pehle capture karke) aur expand par isi ko
    // seedha (window.__suhaniPipReturnTo bridge se) navigate karo — koi
    // browser history state par bharosa nahi.
    private var pipReturnPath: String? = null
    // PERMANENT FIX (user ask: "PiP kaunse episode/video ki hai yeh yaad
    // rakhne wala ek pakka system chahiye, taaki expand karte waqt seedha
    // wahi episode khule"): `pipReturnPath` (upar) ab tak `currentWebViewPathOrNull()`
    // — ek LIVE, us hi waqt kiya gaya native `webView.url` read — se bharosa
    // karta tha. Poori is file mein hum baar-baar dekh chuke hain ki yeh
    // getter Chromium ke IPC ki wajah se kabhi-kabhi thoda "lag" kar sakta
    // hai (dekho `WebAppInterface.mount()` ka bada comment). Agar PiP shuru
    // hote hi (pipChevron tap karte hi) yeh galat/purana path capture ho
    // jaaye, poora "expand par wapas jaana" mechanism shuru se hi ek galat
    // target par based ho jaata — result: expand par kahin bhi nahi (ya
    // seedha default Home) khulta, bilkul jaisa report hua.
    // Fix: ab isi bharose ki zaroorat hi nahi — JS khud har `mount()` aur
    // `updateRect()` call mein apna already-accurate `window.location.pathname`
    // bhejta hai (dekho VideoPlayer.jsx). Yahan bas sabse aakhri baar JS ne
    // jo bhi genuine "/watch/" path report kiya tha, use yaad rakh lo — yeh
    // hamesha 100% JS-confirmed hota hai, kabhi kisi native IPC-lag ka
    // shikar nahi hota. PiP shuru hote hi isi ko priority do.
    private var lastKnownWatchPath: String? = null
    // BUG FIX (dekho `attemptPipReturnNavigate()` ka comment): jab tak PiP-
    // expand ka navigate() genuinely safal na ho jaaye, target path yahan
    // yaad rakha jaata hai — taaki `onPageFinished()` bhi (agar is beech
    // WebView reload ho jaaye) isi target par retry kar sake, is Handler-based
    // retry loop se poori tarah independent ek dusra safety-net ban kar.
    private var pendingPipReturnPath: String? = null
    private val pipReturnRetryHandler = Handler(Looper.getMainLooper())
    // BUG FIX (user report + screenshot: "expand ke baad video Home page ke
    // upar hi atka reh jaata hai, apni jagah par nahi"): neeche wale
    // onActivityResult ka "normal return" branch pehle HAMESHA turant
    // `inlineOverlay.visibility = VISIBLE` kar deta tha — apne ANTIM/purane
    // (PiP-se-pehle wale) rect par, chahe WebView us waqt genuinely wapas
    // watch page par pahunchi ho ya nahi. `mountInlinePlayer()` ka apna
    // same-URI dedupe guard (upar dekho) is se koi rishta nahi rakhta — wo
    // sirf overlay CREATE/reuse control karta hai, iski VISIBILITY yahin se
    // aati hai. Result: agar `pipReturnPath` wali navigation abhi poori nahi
    // hui (ya kisi wajah se fail ho gayi), video turant dikh jaata — lekin
    // apni PURANI jagah (jahan Player.jsx pehle tha) par, jo ab galat page
    // (jaise Home) ke upar overlap kar rahi hoti.
    // Fix: jab yeh return ek real-PiP-se-navigate-away session ke baad ho
    // (`didNavigateBackForPip` true tha), overlay ko turant show mat karo —
    // iske bajaye wait karo JS ke agle genuine `updateInlinePlayerRect()`
    // bridge call ka (jo sirf Player.jsx ke sahi route par dobara mount hone
    // ke baad hi aata hai — dekho VideoPlayer.jsx ka rect-effect). Wahi call
    // ab overlay ko sahi, FRESH rect par show karega. Agar navigation kisi
    // wajah se poori kabhi nahi hoti, ek chhota safety-net timeout (neeche)
    // fir bhi ise show kar dega — taaki video hamesha ke liye chhupa na reh
    // jaaye, sirf thoda late dikhe (galat jagah par turant dikhne se behtar).
    private var awaitingRectAfterPipReturn = false
    private val awaitingRectHandler = Handler(Looper.getMainLooper())
    private var awaitingRectTimeoutRunnable: Runnable? = null

    // BUG FIX (user report, bilkul wahi symptom dobara: "PiP karke back button
    // dabaya, Home par aa gaye, expand karne par abhi bhi Home hi khulta hai"):
    // pichla fix (`pipReturnPath` + unconditional navigate-on-expand) sahi
    // disha mein tha, lekin fir bhi race/lag ka shikaar ho sakta tha — asli,
    // pakka fix yeh hai ki PiP floating rehte waqt is WATCH PAGE ko WebView
    // mein badalne hi mat do, taaki "wapas kahan jaana hai" wala sawaal hi na
    // uthe. Yeh flag track karta hai ki abhi ek real PiP session live hai
    // (`openFullscreenFromInline(enterPipImmediately=true)` se leke jab tak
    // PlayerActivity se result wapas na aa jaaye) — is dauraan hardware back
    // button ko yahin (neeche `onKeyDown` mein) absorb kar lete hain, WebView
    // ko bilkul chhedte nahi. Comments/likes/title wala player page bilkul
    // waisa hi "catch" rehta hai jaisa PiP shuru hote waqt tha, isliye expand
    // hamesha wahi khulta hai — koi navigate-back-and-forth ki zaroorat hi
    // nahi padti.
    private var pipSessionActive = false
    // PERMANENT FIX (dekho `isCurrentlyOnWatchPage()` ka bada comment):
    // `pipSessionActive` ab tak kabhi bhi khud-ba-khud reset nahi hoti thi —
    // sirf `onActivityResult()` (REQUEST_FULLSCREEN_PLAYER) ise false karta
    // tha. Is file mein pehle bhi bilkul yahi "flag hamesha ke liye stuck true
    // reh sakta hai" pattern ek baar mil chuka hai (dekho
    // `suppressNextInlineUnmount` ka comment) — agar PlayerActivity kabhi
    // (rare OS-level kill/crash/edge-case) `onActivityResult` deliver kiye
    // baghair hi khatam ho jaaye, yeh flag hamesha ke liye true reh jaata,
    // aur hardware back-button hamesha ke liye `moveTaskToBack()` mein
    // absorb hota rehta (kabhi normal WebView-back kaam na kare) — bilkul
    // waisa hi ek "chhota" leftover symptom jaisa is file mein pehle dikh
    // chuka hai. Fix: ek generous (10-minute) watchdog — real PiP session
    // itni der floating rehna normal hai, isliye yeh kabhi legitimate use
    // mein fire nahi hoga, sirf ek genuine stuck-flag ke liye safety-net hai.
    private val pipSessionWatchdogHandler = Handler(Looper.getMainLooper())
    private val pipSessionWatchdogReset = Runnable { pipSessionActive = false }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
    private val inlineEqProcessor = EqualizerAudioProcessor()
    private var inlineUri: String = ""
    private var inlineTitle: String = ""
    private var inlineQualitiesJson: String = "[]"
    // ROOT-CAUSE BUG FIX (user report: "chhote/inline player mein quality
    // change karo, sirf number/label badalta hai, actual video kabhi nahi
    // badalta — chahe kitni baar try karo"): `mountInlinePlayer()` (neeche)
    // reuse-path mein bhi UNCONDITIONALLY `inlineUri = uri` kar deta tha aur
    // player ko wahi (React-side) URL se reset kar deta tha — chahe overlay
    // pehle se hi zinda ho. Website (React) ko user ke native qualityButton
    // se switch karne ka pata hi nahi chalta (yeh switch purely Kotlin-side
    // hai), toh React ke paas hamesha wahi PURANA/original src hi save
    // rehta hai. Jab bhi `mount()` bridge dobara call hoti (naya render,
    // resize/rotate se re-run hone wala effect, waghera), yeh purana src
    // dobara native ko bhej deta — aur mountInlinePlayer() use blindly load
    // kar leta, turant ussi second manual quality switch ko silently
    // overwrite karke wapas original par le aata. Isiliye switch "kabhi
    // hua hi nahi" jaisa lagta. Fix: yaad rakho React ne AAKHRI baar kaunsa
    // URI bheja tha — agar naya mount() call EXACT wahi URI hai (matlab
    // genuinely duplicate/spurious call, koi naya episode nahi), to
    // playback/inlineUri ko chhedo hi mat, sirf qualitiesJson refresh kar
    // do. Sach mein NAYA episode aane par (React ka src badalne par) yeh
    // hamesha alag hoga, isliye woh case normal reload hi karega.
    private var lastReactRequestedUri: String = ""
    // ROOT-CAUSE BUG FIX (user report: "chhote player mein quality change karne ke
    // baad video/caption update nahi hote — website par sahi kaam karta hai, app
    // mein nahi"): asli wajah in dono fixes (upar wala aur switchInlineQuality())
    // ke BAAWJOOD bhi bug bane rehne ki — switchInlineQuality() end mein React ko
    // `window.__suhaniOnNativeQualityChange(newUrl)` se sync karta hai (dekho neeche),
    // aur Player.jsx us URL se apna `active` state update karta hai. Lekin VideoPlayer
    // ko `key={active.url}` diya gaya hai (React-side, taaki normal/website quality-
    // switch par instance fresh reset ho) — matlab `active` badalte hi poora
    // VideoPlayer COMPONENT hi unmount+remount ho jaata hai, chahe switch kahin se
    // bhi (native ho ya web) trigger hua ho. Iska side-effect: unmount hote hi
    // `window.AndroidPlayer.unmount()` call hoti hai (`unmountInlinePlayer()` — pause
    // + overlay fade-hide), phir turant remount par `window.AndroidPlayer.mount()`
    // (same, ab-switched URL ke saath) — lekin `mountInlinePlayer()` ka upar wala
    // "duplicate URI" guard isko spurious samajh kar SEEDHA return kar deta hai
    // (bilkul sahi, kyunki genuinely koi naya video nahi hai) — magar isse pehle wale
    // unmount() ka pause+hide kabhi undo nahi hota! Result: overlay GONE reh jaata
    // hai aur player paused reh jaata hai — user ko lagta hai "kuch nahi hua/atak
    // gaya", jabki neeche chhupa hua plain HTML5 <video> (jo embedded MKV subtitle
    // tracks decode hi nahi kar sakta — isliye caption bhi gayab) hi ab dikh raha
    // hota hai. Fix: switchInlineQuality() JS ko sync karne se THEEK PEHLE is flag ko
    // set karta hai; unmountInlinePlayer() ise dekh kar is EK spurious call ko
    // completely ignore kar deta hai (na pause, na hide) — genuine "page chhodo"
    // unmount (jahan yeh flag false hi hoga) pehle jaisa hi normal kaam karta hai.
    private var suppressNextInlineUnmount = false
    // BUG FIX (user report: "PiP ki vajah se video player Home page par khula
    // reh jaata hai"): `suppressNextInlineUnmount` pehle indefinitely true reh
    // sakta tha agar expected spurious unmount() (quality-switch remount se)
    // kisi wajah se kabhi na aaye — us case mein agli baar jab user GENUINELY
    // page/video chhodta (jaise Home par navigate karta), wahi asli unmount()
    // call is stale flag se silently nigal li jaati, aur overlay HAMESHA ke
    // liye stuck/visible reh jaata (jahan bhi last dikha tha, jaise Home ke
    // oopar). Fix: is flag ko ab ek chhoti time-window (600ms) ke baad khud hi
    // auto-reset kar dete hain — asli spurious round-trip is se kahin pehle hi
    // (usually next JS tick par) aa jaata hai, isliye normal quality-switch flow
    // par koi asar nahi, lekin flag ab kabhi bhi permanently "stuck true" nahi
    // reh sakta.
    private val suppressNextInlineUnmountHandler = Handler(Looper.getMainLooper())
    private val suppressNextInlineUnmountReset = Runnable { suppressNextInlineUnmount = false }
    private var inlineHasNextEpisode = false
    private var inlineHasPrevEpisode = false
    private var inlinePrevButtonRef: ImageButton? = null
    private var inlineNextButtonRef: ImageButton? = null
    private var inlinePlayPauseButtonRef: ImageButton? = null
    private var inlineQualityButtonRef: TextView? = null
    private var inlineTimeBarRef: DefaultTimeBar? = null
    private var inlineBufferingIndicatorRef: View? = null
    // FEATURE (user ask, with screenshot: "video player mein play button/
    // ring, '480p' quality text, aur progress bar ka color hamesha yellow/
    // gold hi rehta hai, jabki web side pe maine ek alag (jaise blue) theme
    // select kar rakhi hai — in sabka bhi theme ke hisab se color badalna
    // chahiye"): these controls are native Android views (ExoPlayer/Media3
    // inline_player_control_view.xml), a COMPLETELY separate rendering layer
    // from the WebView's CSS — so the web app's theme picker (ThemeContext.jsx)
    // has no way to reach them on its own. `setThemeColor()` below is the
    // new bridge: ThemeContext now calls `window.AndroidPlayer.setThemeColor(hex)`
    // any time the color theme changes (see ThemeContext.jsx), passing the
    // exact same accent hex every other themed element on the page already
    // uses. Deliberately scoped to just the ACCENT elements (play button/
    // ring, quality text, progress played-portion + scrubber dot) — prev/
    // next/fullscreen icons stay a neutral off-white on purpose: they sit
    // directly on top of arbitrary video frames (not app UI), so a fixed
    // light neutral stays legible against any video content regardless of
    // theme, the same way YouTube's own overlay icons never reflect app
    // dark/light mode.
    private var inlineAccentColor: Int = Color.parseColor("#FFD700")

    private var inlineIsBuffering = false
    // BUG FIX (user report: "pehle video par 'Processing' dikha, doosre
    // video par nahin dikha"): pehle ek manually-reset Boolean flag
    // (`inlineIsInitialLoadForCurrentItem`) istemal hoti thi jo sirf ek
    // specific mount() code-path par reset hoti thi — agar koi bhi doosra
    // rasta (retry, reattach, alag mount-branch) usi flag ko update kiye
    // bina video badalta, agla video ka STATE_BUFFERING purani (false)
    // value hi padh leta aur label kabhi nahi dikhta. Ab isके bajaye seedha
    // "is URI ko hum pehle kabhi READY dekh chuke hain?" — jo hamesha,
    // kisi bhi code-path se, khud player se hi live check hota hai, koi
    // manual reset-placement par depend nahi karta.
    private var inlineLastReadyUri: String? = null
    private var inlineProcessingLabelRef: View? = null
    // FEATURE (user ask: "video load nahi hota, fail ho jata hai — fail
    // hone ke baad sara cache clear karo aur wapas try karo"): chhote
    // (inline) player mein pehle koi retry hi nahi thi (dekho neeche
    // inlineErrorListener). Ab fullscreen (PlayerActivity) jaisa hi bounded
    // retry, saath mein TDLib cache clear bhi.
    private var inlineIoRetryCount = 0
    private val maxInlineIoRetries = 2

    // Named (not anonymous) taaki decoder-switch rebuild ke time isi listener ko
    // purane player se hata kar naye player par dobara laga sakein. Bug fix: pehle
    // do overlapping ImageButtons (exo_play/exo_pause) the jinki visibility Media3
    // khud toggle karta tha, lekin dono ek saath dikh rahe the — ab sirf ek button
    // hai aur uska icon yahi listener seedha player ki state se badalta hai.
    private val inlinePlayPauseListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            inlinePlayPauseButtonRef?.setImageResource(
                if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play_arrow
            )
            // FEATURE (user ask: "single download ya watch, dono ek saath
            // kabhi nahi — jo bhi chal raha ho use full power mile"): yahi
            // ek listener [SharedPlayerHolder]'s ExoPlayer par lagा rehta
            // hai chahe abhi MainActivity ka inline overlay dikh raha ho ya
            // fullscreen PlayerActivity (dono same player instance reuse
            // karte hain) — isliye dono cases yahin se cover ho jaate hain.
            notifyWatchingChanged(isPlaying)
        }

        // BUG FIX (user report: "skip/seek karte hi video pause aur buffering
        // ka icon ek saath dikhta hai, bekar lagta hai"): pehle Media3 ka apna
        // default buffering spinner (show_buffering="when_playing") seedha
        // hamare play/pause hero button ke UPAR draw hota tha, dono ek saath
        // dikhte the. Ab woh band hai (dekho inline_player_view.xml) — is
        // listener se STATE_BUFFERING par khud ek smooth cross-fade karte
        // hain: play/pause button fade+scale-out, apna themed spinner fade+
        // scale-in usi jagah (aur wapas jaate hi ulta) — ek waqt mein sirf
        // ek hi cheez dikhti hai, jhatka-free premium feel.
        override fun onPlaybackStateChanged(playbackState: Int) {
            setInlineBuffering(playbackState == Player.STATE_BUFFERING)
            if (playbackState == Player.STATE_READY) {
                // Pehli baar READY ban gaya — is URI ke liye ab "initial
                // load" khatam. Aage koi bhi buffering isi URI par sirf
                // normal mid-playback rebuffer hai (koi "Processing…" label
                // nahi) — dekho inlineLastReadyUri ka field comment.
                inlineLastReadyUri = inlinePlayer?.currentMediaItem?.localConfiguration?.uri?.toString()
                inlineIoRetryCount = 0
            }
        }
    }

    private fun setInlineBuffering(buffering: Boolean) {
        if (inlineIsBuffering == buffering) return
        inlineIsBuffering = buffering
        val spinner = inlineBufferingIndicatorRef ?: return
        val playPause = inlinePlayPauseButtonRef
        val processingLabel = inlineProcessingLabelRef
        val currentUri = inlinePlayer?.currentMediaItem?.localConfiguration?.uri?.toString()
        val showProcessingLabel = buffering && currentUri != null && currentUri != inlineLastReadyUri
        if (buffering) {
            // BUG FIX (user report: "processing ke saath jo circle dikhaya
            // hai vo circle hata do"): initial-load ("Processing…") state
            // mein ab sirf TEXT dikhta hai — spinner circle nahi. Baaki
            // (mid-playback) rebuffering mein pehle jaisa sirf spinner hai,
            // koi text nahi — dono kabhi ek saath nahi dikhte.
            if (showProcessingLabel) {
                spinner.animate().cancel()
                spinner.alpha = 0f
                spinner.visibility = View.GONE
                if (processingLabel != null) {
                    processingLabel.visibility = View.VISIBLE
                    processingLabel.animate().cancel()
                    processingLabel.alpha = 0f
                    processingLabel.scaleX = 0.92f
                    processingLabel.scaleY = 0.92f
                    processingLabel.animate()
                        .alpha(1f).scaleX(1f).scaleY(1f)
                        .setDuration(180)
                        .setInterpolator(android.view.animation.DecelerateInterpolator())
                        .start()
                }
            } else {
                processingLabel?.animate()?.cancel()
                processingLabel?.alpha = 0f
                processingLabel?.visibility = View.GONE
                spinner.visibility = View.VISIBLE
                spinner.animate().cancel()
                spinner.animate()
                    .alpha(1f).scaleX(1f).scaleY(1f)
                    .setDuration(180)
                    .setInterpolator(android.view.animation.DecelerateInterpolator())
                    .start()
            }
            playPause?.animate()?.cancel()
            playPause?.animate()
                ?.alpha(0f)?.scaleX(0.7f)?.scaleY(0.7f)
                ?.setDuration(140)
                ?.start()
        } else {
            spinner.animate().cancel()
            spinner.animate()
                .alpha(0f).scaleX(0.7f).scaleY(0.7f)
                .setDuration(140)
                .withEndAction { spinner.visibility = View.GONE }
                .start()
            processingLabel?.animate()?.cancel()
            processingLabel?.animate()
                ?.alpha(0f)?.setDuration(140)
                ?.withEndAction { processingLabel.visibility = View.GONE }
                ?.start()
            playPause?.animate()?.cancel()
            playPause?.animate()
                ?.alpha(1f)?.scaleX(1f)?.scaleY(1f)
                ?.setDuration(200)
                ?.setInterpolator(android.view.animation.OvershootInterpolator(2f))
                ?.start()
        }
    }

    // Bug fix: chhote (inline) player mein pehle koi onPlayerError handling hi nahi
    // thi — fullscreen (PlayerActivity) network/decoder/format errors par proper
    // fallback + message deta hai, lekin yahan stream fail hone par player bas
    // silently ruk jaata (black/frozen frame), user ko pata hi nahi chalta kyun.
    // Poora fallback-logic (decoder switch, quality retry waghera) yahan dobara
    // banana overkill hai — us robust handling ke liye already fullscreen hai.
    //
    // FEATURE (user ask: "video load nahi hota, kaafi baar fail ho jaata
    // hai — fail hone ke baad sara cache clear karo aur wapas try karo"):
    // ab pehle seedha "fullscreen mein try karein" toast se pehle, IO-type
    // errors par khud yahin bounded retry karta hai (fullscreen jaisa hi
    // pattern) — is stream ka TDLib cache clear karke, phir dobara prepare.
    // Sirf tab toast dikhata hai jab retries khatam ho jaayein.
    private val inlineErrorListener = object : Player.Listener {
        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
            val isIoError = when (error.errorCode) {
                androidx.media3.common.PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
                androidx.media3.common.PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                androidx.media3.common.PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
                androidx.media3.common.PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE -> true
                else -> false
            }
            if (isIoError && inlineIoRetryCount < maxInlineIoRetries) {
                inlineIoRetryCount += 1
                val player = inlinePlayer ?: return
                val resumePos = player.currentPosition
                val streamUrl = player.currentMediaItem?.localConfiguration?.uri?.toString()
                if (streamUrl != null) {
                    // Blocking TDLib round-trip — background thread, UI thread
                    // par nahi (isi tarah NativeDownloadManager bhi apna TDLib
                    // kaam background Thread par karta hai).
                    Thread {
                        com.suhani.videoplayer.TdlibClient.resetCacheForStreamUrl(streamUrl)
                        runOnUiThread {
                            if (!isFinishing && !isDestroyed) {
                                player.prepare()
                                player.seekTo(resumePos)
                                player.playWhenReady = true
                            }
                        }
                    }.apply { isDaemon = true }.start()
                } else {
                    player.prepare()
                    player.seekTo(resumePos)
                    player.playWhenReady = true
                }
                return
            }
            android.widget.Toast.makeText(
                this@MainActivity,
                "Video play nahi ho paya — fullscreen mein try karein",
                android.widget.Toast.LENGTH_SHORT
            ).show()
        }
    }

    // Naya text/subtitle track available hote hi user ne khud manually ON na kiya ho
    // to kabhi khud-ba-khud select NAHI karta — subtitle hamesha OFF start hota hai,
    // sirf user Subtitle menu se ek baar khud koi track chune to hi ON hota hai
    // (dekho showInlineSubtitleDialog()). Yeh listener isi liye khaali/no-op hai —
    // naam se lagta hai kuch karega, par jaan-bujh kar kuch nahi karta; naye video
    // par bhi subtitleManuallyEnabled flag (jo naye mount/episode par reset hoti hai)
    // is default-off ko yakeeni banati hai.
    private val inlineTracksListener = object : Player.Listener {
        override fun onTracksChanged(tracks: Tracks) {
            // Intentionally no-op — subtitle track kabhi auto-select nahi hota.
        }
    }

    // BUG FIX (user report + screenshot: app offline reopen par WebView's own
    // generic "Webpage not available / net::ERR_INTERNET_DISCONNECTED" page
    // dikhata tha, hamara branded loadErrorView kabhi nahi dikhta tha).
    // Root cause: WebViewClient.onReceivedError(view, errorCode, description,
    // failingUrl) — jo neeche override kiya hua tha — woh 4-argument overload
    // hai jo API 23 mein hi deprecate ho gaya tha. Is app ka minSdk 24 hai,
    // isliye HAR real device par (koi bhi API 23 se neeche nahi chalta) woh
    // purana overload cabhi call hi nahi hota — sirf naya
    // onReceivedError(view, WebResourceRequest, WebResourceError) overload
    // call hota hai (dekho neeche). Us naye overload ko kabhi override hi
    // nahi kiya gaya tha, isliye default WebViewClient behavior chalta
    // raha — jo khud Chromium ka apna built-in "Webpage not available"
    // interstitial WebView ke andar load kar deta hai. Humara custom
    // loadErrorView isliye kabhi dikh hi nahi paata tha, chahe uska code
    // sahi tha.
    // Shell ab bundled assets se load hota hai, isliye yeh screen ab
    // "no internet" ka matlab kabhi nahi hoga — sirf ek genuine local-load
    // anomaly (jaise corrupt/incomplete install) ka signal hai. Internet-
    // dependent errors (search/streaming) apni jagah React side
    // (lib/connectivity.js / ConnectionOverlay.jsx) handle karta hai.
    private fun showLoadError() {
        initialLoadingView.visibility = View.GONE
        loadErrorTitle.text = "Couldn't load the app"
        loadErrorMessage.text = "Something went wrong opening the app. Please try again, or reinstall if this keeps happening."
        loadErrorView.visibility = View.VISIBLE
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // DownloadService ke saath registration — dekho onDownloadProgress/
        // Done/Error overrides ka comment aur DownloadService.kt.
        DownloadService.listener = this

        // ROOT CAUSE FIX (user report: "download ke waqt notification bar mein
        // progress hi nahi dikhta"): DownloadService pehle se hi
        // startForeground() + ongoing progress notification post kar raha tha,
        // lekin sirf manifest mein `POST_NOTIFICATIONS` permission declare
        // karna Android 13 (API 33)+ par kaafi nahi hai — us permission ko
        // runtime par explicitly maangna padta hai, warna OS chup-chaap us
        // notification ko drop kar deta hai (service khud foreground mein
        // chalta rehta hai, bas uski notification kabhi dikhti hi nahi). Yahan
        // koi request hi nahi thi, isliye zyada tar naye Android phones par
        // yeh notification hamesha invisible rehti thi. Fix: app open hote hi
        // ek baar yeh permission maango (already granted ho to no-op).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                4501
            )
        }

        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.webview)
        // CRASH FIX (root cause of "online video ko fullscreen/PiP karte hi
        // app crash" — bugreport se confirm hua: `TransactionTooLargeException:
        // data parcel size ~2.9MB`, jisme Bundle stats ne "WEBVIEW_CHROMIUM_STATE"
        // key ko sabse bada contributor dikhaya): Android by default har view
        // jiska ek valid id ho (yeh WebView bhi, R.id.webview se) use apna
        // onSaveInstanceState() dispatch karke Activity ke saved-instance Bundle
        // mein "freeze" kar deta hai. Chromium WebView ke liye iska matlab hai
        // poori navigation history + page state ek Parcelable blob ban jaata
        // hai — jitni zyada der/zyada pages is single-page (React) app mein
        // browse kiya jaaye, utna hi bada yeh blob hota jaata hai. Jab bhi
        // MainActivity background jaati hai (fullscreen/PiP ke liye PlayerActivity
        // khulne par bhi yehi hota hai), Android is poore Bundle ko Binder IPC
        // (activityStopped()) se system_server ko bhejta hai — aur Binder
        // transactions ~1MB tak hi limited hote hain. Blob jab is limit se
        // bada ho jaata (online browsing ke baad hota hi hai), poora transaction
        // hi fail ho jaata hai aur seedha app crash. Offline turant-download-
        // karke-dekhne wale case mein crash na hone ki wajah yehi thi ki fresh
        // process mein WebView ne abhi zyada navigate nahi kiya hota — blob
        // chhota hota, limit ke andar reh jaata.
        // Fix: WebView ka apna view-state save hi disable kar do — hum vaise
        // bhi ise kabhi restore nahi karte (neeche onCreate() ka comment dekho:
        // restoreState() jaan-bujh kar hata diya gaya tha, is app mein page
        // hamesha fresh index.html se load hoti hai), isliye is state ko
        // save karna sirf risk hai, koi fayda nahi.
        webView.isSaveEnabled = false
        swipeRefresh = findViewById(R.id.swipe_refresh)
        initialLoadingView = findViewById(R.id.initialLoadingView)
        loadErrorView = findViewById(R.id.loadErrorView)
        loadErrorTitle = findViewById(R.id.loadErrorTitle)
        loadErrorMessage = findViewById(R.id.loadErrorMessage)
        findViewById<View>(R.id.loadErrorRetryButton).setOnClickListener {
            // Shell ab kabhi network-fail nahi hota (bundled assets se aata
            // hai) — yeh screen ab sirf tabhi dikhega agar koi asli anomaly ho
            // (jaise APK corrupt install). Simple reload hi kaafi hai.
            loadErrorView.visibility = View.GONE
            initialLoadingView.visibility = View.VISIBLE
            startLoadingLabelPulse()
            webView.reload()
        }
        startLoadingLabelPulse()

        // Edge-to-edge fix: WebView (page content) ko status bar/notch ke neeche se
        // shuru karo, aur wahi status-bar height baad mein inline player ke rect
        // calculation mein bhi add karenge (nahi to chhota player status bar ke
        // andar ghusa dikhta).
        WindowCompat.setDecorFitsSystemWindows(window, false)
        ViewCompat.setOnApplyWindowInsetsListener(swipeRefresh) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            statusBarInsetPx = bars.top
            view.setPadding(bars.left, bars.top, bars.right, 0)
            insets
        }

        // "/" -> app/src/main/assets/ ke poore contents ko domain-root se serve
        // karta hai (index.html, /assets/*.js, /favicon.png waghera) — exactly
        // wahi absolute-path structure jo Vite build normally banata hai,
        // isliye frontend code mein koi path change nahi karna pada.
        assetLoader = WebViewAssetLoader.Builder()
            .addPathHandler("/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.databaseEnabled = true
        webView.settings.mediaPlaybackRequiresUserGesture = false
        webView.settings.allowFileAccess = false
        webView.settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
        // PERF FIX (user ask: "poora app fast/smooth banao"): pehle koi
        // explicit render/cache tuning nahi thi — WebView apne defaults par
        // chal raha tha jo har rendering situation ke liye optimal nahi
        // hote.
        //  - LAYER_TYPE_HARDWARE: WebView ka poora content GPU-composited
        //    surface par draw hota hai instead of software rasterizing —
        //    scroll aur CSS transition/animation dono zyada smooth (60fps
        //    ke kaafi kareeb) ho jaate hain, khaaskar poster-grid scroll
        //    aur mini-player ke saath ek hi screen par.
        //  - cacheMode = LOAD_DEFAULT: HTTP cache-control headers follow
        //    karta hai, taaki poster images/API responses jo already fetch
        //    ho chuke hain unhe baar-baar re-download na karna pade (jaise
        //    Home pe wapas aane par ya tabs switch karne par).
        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        webView.settings.cacheMode = android.webkit.WebSettings.LOAD_DEFAULT
        // UX FIX ("clean/fast" audit): agar user ne apne phone ki system font
        // size accessibility setting badhaayi/ghataayi hui hai, WebView
        // default mein us hi scale ko poore web content ke text par bhi laagu
        // kar deta hai (Android ka "textZoom" via system font scale). Is
        // app ka poora UI fixed, carefully-tuned CSS sizing/spacing par bana
        // hai (jaise MediaCard ke poster grid, chhote pill buttons) — is
        // scaling ki wajah se un devices par text overflow, overlapping
        // labels, ya tuti hui grid layout dikh sakti thi, bina kisi bug ke
        // bhi. Native Android UI (status bar, settings, waghera) apni jagah
        // sahi scale hoti rahegi — sirf is WebView ka apna content isse
        // affected nahi hona chahiye, exactly jaise ek normal native app
        // (jo apna text size khud control karta hai) mein hota.
        webView.settings.textZoom = 100
        // User report: red-marked vertical bar on screen's right edge — this is
        // the WebView's own native fading scroll indicator (an Android View
        // property), not a browser/CSS scrollbar, so index.css alone can't
        // remove it. XML sets android:scrollbars="none" already; setting it
        // here too so it stays off even if the view is ever recreated in code.
        webView.isVerticalScrollBarEnabled = false
        webView.isHorizontalScrollBarEnabled = false

        webView.webViewClient = object : WebViewClient() {
            // Har request (index.html, hashed JS/CSS, favicon, ...) yahan se
            // guzarti hai — assetLoader wahi cheez APK ke bundled assets se
            // seedha serve kar deta hai, koi network round-trip nahi. Sirf
            // asset-loader ke apne "/" prefix se match na hone waali requests
            // (jaise API backend ki https:// calls) yahan se pass-through ho
            // kar normal network path lengi.
            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest
            ): WebResourceResponse? {
                return assetLoader.shouldInterceptRequest(request.url)
            }

            // BUG FIX (user report: "Sign up with Telegram" -> "Open Telegram"
            // par click karne par "Webpage not available / net::ERR_UNKNOWN_
            // URL_SCHEME (tg:resolve?...)" dikhta tha): koi shouldOverrideUrlLoading
            // hi nahi tha, isliye WebView har link (chahe https://t.me/... ho
            // ya Telegram ka apna JS-redirect kiya hua tg: deep-link) khud
            // apne andar hi load karne ki koshish karta tha. tg: scheme WebView
            // samajhta hi nahi (sirf http/https handle karta hai) — isliye
            // seedha error screen. Fix: t.me links aur kisi bhi non-http(s)
            // scheme (tg:, intent:, mailto:, waghera) ko yahin pakad kar
            // asli Android OS ko de do (Intent.ACTION_VIEW) — woh khud sahi
            // app choose karega (installed Telegram app, ya agar install
            // nahi hai to Play Store / chooser). Baaki normal http(s) page
            // navigation (apna hi bundled frontend) bilkul pehle jaisa hi
            // WebView ke andar rehta hai.
            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest
            ): Boolean {
                val url = request.url
                val scheme = url.scheme?.lowercase()
                val host = url.host?.lowercase()
                val isTelegramLink = host == "t.me" || host == "telegram.me" || scheme == "tg"
                if (isTelegramLink || (scheme != "http" && scheme != "https")) {
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW, url))
                    } catch (e: android.content.ActivityNotFoundException) {
                        // Telegram app hi installed nahi hai — Play Store par
                        // uska listing khol do taaki user install kar sake.
                        try {
                            startActivity(
                                Intent(
                                    Intent.ACTION_VIEW,
                                    Uri.parse("market://details?id=org.telegram.messenger")
                                )
                            )
                        } catch (e2: android.content.ActivityNotFoundException) {
                            // Play Store bhi nahi — chup-chaap ignore, at least
                            // crash ya broken error page nahi dikhega.
                        }
                    }
                    return true
                }
                return false
            }

            override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                super.onPageStarted(view, url, favicon)
                // Reload (retry/pull-to-refresh) par bhi purani error screen turant
                // hata do, warna spinner ke peeche stale error text dikhta rehta.
                loadErrorView.visibility = View.GONE
            }

            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                swipeRefresh.isRefreshing = false
                hasLoadedOnce = true
                fadeOutLoadingView()
                // BUG FIX (dekho `attemptPipReturnNavigate()` ka comment): agar
                // ek PiP-expand restore abhi bhi pending hai (target set hai)
                // JAB WebView genuinely ek naya page load poora karti hai (jaise
                // background renderer-restart ke baad ka fresh reload), yahan
                // se bhi ek turant fresh attempt kar do — is Handler-based retry
                // loop ke upar/alawa ek dusra, independent safety-net, taaki
                // "React abhi load hi ho raha tha" wali exact timing miss na ho.
                pendingPipReturnPath?.let { attemptPipReturnNavigate(it, attemptsLeft = 13) }
            }

            // BUG FIX (user report: "app ke Home mein PiP ki wajah se video
            // player home page par khula/open reh jaata hai"): ab tak inline
            // overlay ko hide/unmount karne ka poora zimma sirf website (React
            // side ke VideoPlayer.jsx unmount-cleanup effect, ya native-close
            // bridge calls jaise __suhaniOnNativeClosed) par tha. Agar kisi
            // race/edge-case mein woh JS-side unmount() call kabhi miss ho
            // jaaye (jaise upar wale `suppressNextInlineUnmount` ke purane
            // stuck-flag bug mein), overlay apni AAKHRI jagah (jahan wo watch
            // page ka video-slot tha) par hi fixed reh jaata — aur WebView ke
            // kisi bhi doosre route (jaise Home) par navigate karte hi, overlay
            // wahi dikhta rehta, us naye page ke bilkul oopar chipka hua,
            // bilkul jaisa report hua.
            //
            // Fix: yeh ek dusra, poori tarah independent safety-net hai — SPA
            // (React Router pushState/replaceState) navigation yahin, native
            // WebViewClient level par bhi track karo, aur jab bhi current path
            // "/watch/" se shuru na ho, overlay ko definitively/forcefully hide
            // kar do — chahe website side se koi unmount() call aaya ho ya
            // nahi. Real PiP session (`pipSessionActive`) ke dauraan is check
            // ko jaan-bujh kar skip karte hain, kyunki us waqt WebView khud
            // isi watch page par jaan-bujh kar rakha jaata hai (real PiP
            // window floating rehte waqt bhi) — dekho
            // `openFullscreenFromInline()` ka comment.
            override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
                super.doUpdateVisitedHistory(view, url, isReload)
                // PERMANENT FIX (dekho `isCurrentlyOnWatchPage()` ka bada
                // comment): pehle `if (pipSessionActive) return` yahan poore
                // is safety-net ko HI skip kar deta tha — is galat assumption
                // par ki "real PiP session ke dauraan WebView hamesha isi
                // watch page par rahegi." Lekin overlay ab (upar
                // `mountInlinePlayer()`/`showInlineOverlayWithFade()` mein)
                // khud apne current-path ka ground-truth check karta hai,
                // isliye yeh purana skip ab sirf ek EXTRA cheez karta tha:
                // agar overlay kabhi (kisi bhi wajah se) galat page par
                // VISIBLE ho jaata, is definitive safety-net ko hi nishkriya
                // kar deta — exact wahi jo use theek karna chahiye tha. Ab
                // yeh hamesha chalta hai, PiP session ke dauraan bhi — agar
                // overlay genuinely sahi (/watch/) page par hai (jo normal
                // flow mein hamesha hona chahiye), `forceHideInlineOverlay()`
                // khud kuch nahi karega (already-hidden ya sahi-page overlay
                // par no-op hai, dekho uska comment) — koi regression nahi,
                // sirf ek aur hamesha-active defense layer.
                val path = try { url?.let { Uri.parse(it).path } } catch (_: Exception) { null }
                if (path == null || !path.startsWith("/watch/")) {
                    forceHideInlineOverlay()
                }
            }

            // Naya overload (WebResourceRequest/WebResourceError) — yehi hai jo
            // API 24+ (yaani is app ke minSdk se upar har device) par main-frame
            // network errors (DNS fail, connection refused, offline, timeout) ke
            // liye asal mein call hota hai. Purana 4-arg onReceivedError overload
            // (jo pehle yahan tha) API 23 mein deprecate ho chuka tha aur modern
            // devices par kabhi trigger hi nahi hota — isi wajah se pehle branded
            // error screen ki jagah WebView ka apna default "Webpage not
            // available" interstitial dikhta tha (screenshot wahi tha).
            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                super.onReceivedError(view, request, error)
                if (request?.isForMainFrame != true) return // sub-resource (image/analytics) failure, page ko na todo
                swipeRefresh.isRefreshing = false
                if (!hasLoadedOnce) showLoadError()
            }

            // HTTP-level failures (backend crash → Vercel/Railway 5xx, gateway
            // timeout waghera) onReceivedError se nahi, isse aate hain — pehle
            // yeh bhi handle hi nahi hota tha, to server-down case mein bhi
            // WebView blank/default page dikhata tha.
            override fun onReceivedHttpError(
                view: WebView,
                request: WebResourceRequest?,
                errorResponse: WebResourceResponse?
            ) {
                super.onReceivedHttpError(view, request, errorResponse)
                if (request?.isForMainFrame != true) return
                if (!hasLoadedOnce) showLoadError()
            }

            // BUG FIX — THE actual root cause of the reopen-while-offline
            // black screen (user screenshots: app reopened after being fully
            // closed/backgrounded, nothing at all shows — no loading spinner,
            // no branded error screen, not even React ever getting a chance
            // to run its own offline redirect. Just flat black, forever).
            //
            // What's happening: on a low-memory/low-battery moment (both
            // screenshots show the device around 12-20% battery, a classic
            // trigger for Android's memory pressure killing background
            // work), the OS can kill just the WebView's separate renderer
            // process while this Activity's process itself survives in the
            // background. That renderer process is where ALL of our page —
            // HTML, CSS, and every line of the React app — actually lives
            // and runs. When the user reopens the app, Android brings this
            // Activity back via onResume() (not onCreate() — the Activity
            // itself was never killed, so none of our loadDataWithBaseURL /
            // offline-redirect / error-screen logic in onCreate() re-runs
            // at all), but the WebView is now just an empty shell pointed at
            // a renderer that no longer exists. Nothing was ever "loading"
            // to fail or error out — there's simply nobody left to paint
            // anything, so the WebView shows nothing and the plain black
            // root-layout background (#0B0B0F, activity_main.xml) is all
            // that's ever visible. This is exactly why the React-side
            // offline/downloads fix alone can never fix this particular
            // case: JS never gets to run again until something notices the
            // renderer is gone and explicitly reloads it.
            //
            // WebViewClient has a dedicated callback for exactly this,
            // onRenderProcessGone() (API 26+) — it was never overridden
            // here before, so the system fell back to its own default
            // handling. For a foreground/important WebView, that default is
            // usually to silently leave the dead view on screen (what we
            // saw) or, on some OEM builds, kill the whole app outright.
            //
            // Fix: detect it, tear down the now-unusable WebView instance
            // (an app is NOT allowed to keep using a WebView after its
            // renderer has gone — must remove + destroy + rebuild), and
            // recreate() the Activity so the exact same safe boot path from
            // onCreate() (bundled-assets load, offline-safe by construction)
            // runs fresh. Returning true tells Android WE handled the crash,
            // so it must not additionally kill the app process itself.
            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail?): Boolean {
                if (view !== webView) return false
                (webView.parent as? ViewGroup)?.removeView(webView)
                webView.destroy()
                recreate()
                return true
            }
        }
        webView.webChromeClient = WebChromeClient()

        // Native player bridge — frontend isse pehchan kar HTML5 <video> ki jagah
        // seedha native Sisisisi (chhota inline, fullscreen-expandable) player khol sakta hai.
        webView.addJavascriptInterface(WebAppInterface(this), "AndroidPlayer")
        // Offline downloads ke liye native file-download bridge — dekho WebDownloadInterface
        // aur NativeDownloadManager ke doc comments (blob: URL vs content:// URI ka issue).
        webView.addJavascriptInterface(WebDownloadInterface(this), "AndroidDownloader")

        // BUG FIX (user report): pull-to-refresh (neeche-swipe-se-reload) hi
        // "upar scroll nahi ho raha" issue ki asli wajah nikla — SwipeRefreshLayout
        // page ke top par touch ko intercept kar leta tha, chahe kitna bhi fine-tune
        // karo (scrollY check, JS bridge, debounce — kuch bhi permanently fix nahi
        // kar paaya kyunki root cause hi yeh feature tha). Isliye pull-to-refresh
        // ko poori tarah disable kar diya — ab SwipeRefreshLayout sirf ek plain
        // container hai, kabhi bhi touch intercept nahi karega, aur WebView ka
        // apna scroll (upar ho ya neeche) hamesha bina rukawat ke chalega.
        swipeRefresh.isEnabled = false

        // BUG FIX #2 (root cause of "fix #1 ke baad bhi offline black screen aa
        // raha hai" — user ne screenshots se confirm kiya): fix #1 ne sirf COLD
        // START (savedInstanceState == null, jaise APK ka pehla launch) ko
        // loadDataWithBaseURL() se navigation-free bana diya tha — lekin
        // savedInstanceState != null wala branch bilkul chhua hi nahi tha, aur
        // WASTAV mein REAL-WORLD offline-reopen scenario yehi branch hai: user
        // app use karta hai, kuch der ke liye chhodta hai, Android low-memory
        // mein background process ko kill kar deta hai (task/back-stack bana
        // rehta hai) — phir jab user icon se wapas kholta hai, Android
        // onCreate() ko EK SAVED savedInstanceState ke saath dobara call karta
        // hai. Us purane code mein yahan `webView.restoreState(savedInstanceState)`
        // chalta tha — jo apni history ke last URL ko phir se ek REAL NAVIGATION
        // ke through reload karta hai (bilkul loadUrl() jaisa hi), isliye wahi
        // connectivity pre-check hang/error dubara laga deta — is baar cold-start
        // ke fix se bilkul bachte hue.
        // Fix: dono cases (fresh launch ho ya process-death ke baad restore) mein
        // ab hamesha wahi safe loadDataWithBaseURL() path chalta hai — WebView ke
        // native restoreState() par ab kabhi bharosa nahi karte. Trade-off: agar
        // process genuinely beech mein kill hua ho, user exact usi in-app page
        // (jaise kisi movie ke detail page) par restore nahi hoga, seedha Home se
        // khulega — lekin app HAMESHA khulega, offline ho ya online, yeahi
        // asli maang thi.
        try {
            val html = assets.open("index.html").bufferedReader().use { it.readText() }
            webView.loadDataWithBaseURL(
                "https://appassets.androidplatform.net/",
                html,
                "text/html",
                "UTF-8",
                null
            )
        } catch (e: Exception) {
            // Extremely unlikely (would mean assets/index.html khud missing/
            // corrupt build) — is genuine case mein hi ab purana loadUrl()
            // fallback ke roop mein istemal hota hai.
            webView.loadUrl(APP_URL)
        }
    }

    private fun startLoadingLabelPulse() {
        loadingLabelPulse?.cancel()
        val label = findViewById<View>(R.id.initialLoadingLabel) ?: return
        loadingLabelPulse = android.animation.ObjectAnimator.ofFloat(label, View.ALPHA, 0.4f, 0.85f).apply {
            duration = 900L
            repeatMode = android.animation.ValueAnimator.REVERSE
            repeatCount = android.animation.ValueAnimator.INFINITE
            interpolator = android.view.animation.AccelerateDecelerateInterpolator()
            start()
        }
    }

    private fun fadeOutLoadingView() {
        if (initialLoadingView.visibility != View.VISIBLE) return
        loadingLabelPulse?.cancel()
        loadingLabelPulse = null
        initialLoadingView.animate()
            .alpha(0f)
            .setDuration(220)
            .withEndAction {
                initialLoadingView.visibility = View.GONE
                initialLoadingView.alpha = 1f
            }
            .start()
    }

    /** Watch progress (position + history) — dono players ab isi ek jagah save
     *  karte hain, taaki fullscreen aur inline ke beech resume seamlessly kaam kare
     *  chahe user ne video kisi bhi tareeke se dekhi ho. */
    private fun saveInlineWatchProgress() {
        val player = inlinePlayer ?: return
        if (inlineUri.isEmpty()) return
        val position = player.currentPosition
        val duration = player.duration.takeIf { it > 0 } ?: 0L
        getSharedPreferences("playback_positions", MODE_PRIVATE).edit()
            .putLong(inlineUri, position)
            .apply()
        if (inlineTitle.isNotBlank()) {
            HistoryStore.addOrUpdate(
                this,
                HistoryEntry(
                    uri = inlineUri,
                    title = inlineTitle,
                    isVideo = true,
                    positionMs = position,
                    durationMs = duration,
                    playedAt = System.currentTimeMillis()
                )
            )
        }
    }

    /** Chhota inline player (overlay) create/reuse karke naya video load karta hai,
     *  aur uske saare (scaled-down) controls wire karta hai. */
    fun mountInlinePlayer(uri: String, title: String, qualitiesJson: String, currentPath: String? = null, trustedNoCheck: Boolean = false) {
        // PERMANENT FIX (dekho `lastKnownWatchPath` field ka comment): JS ne
        // agar ek accurate "/watch/" path bheja hai, use turant yaad rakh lo
        // — yeh future kisi bhi PiP session ke liye "sahi jagah wapas jaane"
        // ka sabse bharosemand source banega.
        if (currentPath?.startsWith("/watch/") == true) lastKnownWatchPath = currentPath
        // SPEED FIX (buffering still 10s+ dikh raha tha): TdlibClient.prewarm()
        // already exist karta tha, lekin sirf PlayerActivity (fullscreen open)
        // ise bulata tha — jabki user ka SABSE PEHLA tap (video card dabana)
        // yahin mountInlinePlayer() se hota hai, aur wahi actual ExoPlayer
        // banaata/khol ta hai jo TDLib login ka poora ~10s cost synchronously
        // pay karta hai. Matlab jo user sabse pehle dekhta hai wahi hamesha
        // full delay leta tha, chahe baad mein fullscreen kholne par fast lage.
        // Fix: yahan bhi, sabse pehli line par hi (dedupe check se bhi pehle,
        // taaki spurious duplicate mount calls par bhi harmless rahe — dono
        // functions idempotent hain), same prewarm shuru kar do — login ab
        // inline player ke UI/view-setup ke saath parallel chalta hai.
        TdlibClient.init(applicationContext)
        TdlibClient.prewarm(uri)

        // Overlay pehle se zinda hai AUR React ya to (a) bilkul wahi URI dobara
        // bhej raha hai jo pichli baar bheja tha (spurious/duplicate mount call —
        // React ko native-side quality-switch pehle pata nahi chalta tha), YA (b)
        // ab (window.__suhaniOnNativeQualityChange sync hone ke baad) genuinely
        // wahi URL bhej raha hai jo native abhi already khud switch kar chuka hai
        // (`inlineUri`) — dono cases mein yeh NAYA episode nahi hai, sirf
        // bookkeeping/re-render hai. Isse playback ko bilkul mat chhedo — warna
        // abhi-abhi kiya gaya manual quality switch turant overwrite/reload ho
        // jaayega (dobara buffering/stutter dikhega, chahe end mein sahi hi
        // quality par wapas aa jaaye).
        if (inlineOverlay != null && (uri == lastReactRequestedUri || uri == inlineUri)) {
            inlineQualitiesJson = qualitiesJson
            lastReactRequestedUri = uri
            // PERMANENT FIX (user report: "PiP expand karne par video
            // automatically cancel ho jaata hai"): pehle yahan sirf metadata
            // update karke seedha return ho jaata tha — is ghalat assumption
            // ke saath ki overlay ki visibility already sahi hogi. Lekin ek
            // genuine PiP-cross-page-return ke baad, overlay jaan-bujh kar
            // GONE kiya gaya hota hai (asli video us waqt PlayerActivity
            // mein tha) — aur JS ka yeh EXACT `mount()` call (SAME URI ke
            // saath, isiliye is dedupe-branch mein aana) hi wahi signal hai
            // jo batata hai "PiP se genuinely wapas aa chuke hain, isi video
            // ko dobara dikhao." Pehle yeh signal yahin silently discard ho
            // jaata tha, aur overlay wapas dikhane ke liye SIRF ek alag,
            // resize-observer-driven `updateInlinePlayerRect()` call par hi
            // (poora) depend karte the — agar kisi bhi wajah se woh call
            // miss/delay ho jaaye, overlay hamesha ke liye chhupa reh jaata,
            // bilkul jaisa "video cancel ho gaya" report hua. Fix: yahan bhi,
            // isi JS-supplied (accurate) `currentPath` ke saath, ek dusra
            // independent trigger try karo.
            val onWatchPageDedupe = trustedNoCheck || (currentPath?.startsWith("/watch/") ?: isCurrentlyOnWatchPage())
            if (onWatchPageDedupe) {
                inlineOverlay?.let { overlay ->
                    if (overlay.visibility != View.VISIBLE) {
                        overlay.animate().cancel()
                        overlay.alpha = 0f
                        overlay.scaleX = 0.96f
                        overlay.scaleY = 0.96f
                        overlay.visibility = View.VISIBLE
                        overlay.animate()
                            .alpha(1f).scaleX(1f).scaleY(1f)
                            .setDuration(180)
                            .setInterpolator(android.view.animation.DecelerateInterpolator())
                            .start()
                    }
                }
            }
            return
        }
        val previousUri = inlineUri
        lastReactRequestedUri = uri
        inlineUri = uri
        inlineTitle = title
        inlineQualitiesJson = qualitiesJson

        if (inlineOverlay == null) {
            val root = LayoutInflater.from(this).inflate(R.layout.inline_player_view, null) as FrameLayout
            val playerView = root.findViewById<PlayerView>(R.id.inlinePlayerView)
            val ambientGlowView = root.findViewById<AmbientGlowView>(R.id.inlineAmbientGlowView)
            inlineAmbientGlowView = ambientGlowView
            // User request: Ambient Glow default-ON hai (fullscreen player jaisa hi
            // — dono ek hi "feature_prefs"/"ambient_glow_on" setting share karte hain,
            // isliye ek jagah toggle karne se dono players mein consistent rehta hai).
            val ambientOn = getSharedPreferences("feature_prefs", MODE_PRIVATE)
                .getBoolean("ambient_glow_on", true)
            ambientGlowView.setGlowEnabled(ambientOn)
            startInlineAmbientGlowLoop(root)
            // Bug fix (PiP/fullscreen se wapas aane par ek pal ke liye black frame
            // flash hota tha): default behavior mein PlayerView player badalte/
            // reattach hote hi apna last-drawn frame turant clear kar deta hai, chahe
            // wahi (ya ek naya, sahi position wala) player turant dobara attach ho
            // raha ho. Isse setKeepContentOnPlayerReset(true) karne se woh last frame
            // tab tak dikhta rehta hai jab tak naya frame render nahi ho jaata —
            // koi visible black-flash nahi, seedha smooth continuation.
            playerView.setKeepContentOnPlayerReset(true)
            val speedButton = root.findViewById<TextView>(R.id.inlineSpeedButton)
            val subtitleButton = root.findViewById<ImageView>(R.id.inlineSubtitleButton)
            val audioTrackButton = root.findViewById<ImageView>(R.id.inlineAudioTrackButton)
            val decoderButton = root.findViewById<TextView>(R.id.inlineDecoderButton)
            val settingsButton = root.findViewById<ImageView>(R.id.inlineSettingsButton)
            val pipChevron = root.findViewById<ImageView>(R.id.inlinePipChevron)
            val pipButton = playerView.findViewById<ImageView>(R.id.inlinePipButton)
            val aspectButton = playerView.findViewById<ImageView>(R.id.inlineAspectButton)
            val fullscreenButton = playerView.findViewById<ImageView>(R.id.inlineFullscreenButton)
            val qualityButton = playerView.findViewById<TextView>(R.id.inlineQualityButton)
            val timeBar = playerView.findViewById<DefaultTimeBar>(R.id.exo_progress)
            val bufferingIndicator = root.findViewById<View>(R.id.inlineBufferingIndicator)
            val processingLabel = root.findViewById<View>(R.id.inlineProcessingLabel)
            inlineQualityButtonRef = qualityButton
            inlineTimeBarRef = timeBar
            inlineBufferingIndicatorRef = bufferingIndicator
            inlineProcessingLabelRef = processingLabel

            // Back-arrow aur title text hata diye gaye hain (page ka apna back
            // navigation already hai, redundant tha) — isliye ab yahan backButton
            // ka koi reference nahi hai.

            // YouTube jaisa asli bottom-sheet (dekho showInlineSettingsSheet()) —
            // audio/subtitle ab apne dedicated icon se seedhe kaam karte hain,
            // isliye settings mein nahi hain.
            settingsButton.setOnClickListener {
                showInlineSettingsSheet(speedButton, decoderButton, pipButton, aspectButton)
            }
            fullscreenButton.setOnClickListener { openFullscreenFromInline() }

            qualityButton.text = currentInlineQualityLabel()
            qualityButton.setOnClickListener { showInlineQualityDialog(qualityButton) }

            // Pehle sirf ek silent on/off toggle tha — ab fullscreen jaisa hi ek
            // asli track-picker popup khulta hai (available subtitle tracks + Off).
            // Subtitle ab default OFF start hoti hai, isliye icon bhi shuru mein
            // dimmed (0.5 alpha) dikhta hai — user ON kare to hi full-opacity ho.
            subtitleButton.alpha = 0.5f
            subtitleButton.setOnClickListener { showInlineSubtitleDialog(subtitleButton) }

            speedButton.setOnClickListener {
                showInlineSpeedSheet(speedButton)
            }

            audioTrackButton.setOnClickListener { showInlineAudioTrackDialog() }
            decoderButton.setOnClickListener { showInlineDecoderDialog(decoderButton) }

            aspectButton.setOnClickListener {
                resizeModeIndex = (resizeModeIndex + 1) % resizeModes.size
                playerView.resizeMode = resizeModes[resizeModeIndex]
            }

            // Bug fix: is chhoti overlay window par real Android PiP lagane se poora
            // WebView+video ek saath tiny frame mein simat jaata tha (bilkul tootा hua
            // dikhta tha) — kyunki PiP poori Activity ki window shrink karta hai, sirf
            // is div ko nahi. Ab PiP tap karne par pehle fullscreen khulta hai (jahan
            // poori window sirf video hi hai), aur wahi turant real PiP mein chala
            // jaata hai — yahi sahi/kaam karne wala tareeka hai.
            pipButton.setOnClickListener { openFullscreenFromInline(enterPipImmediately = true) }
            pipChevron.setOnClickListener { openFullscreenFromInline(enterPipImmediately = true) }

            val prevButton = root.findViewById<ImageButton>(R.id.inlinePrevButton)
            val nextButton = root.findViewById<ImageButton>(R.id.inlineNextButton)
            val playPauseButton = root.findViewById<ImageButton>(R.id.inlinePlayPauseButton)
            inlinePrevButtonRef = prevButton
            inlineNextButtonRef = nextButton
            inlinePlayPauseButtonRef = playPauseButton
            applyInlineAdjacentButtonState()
            // Fresh views just got inflated from XML (hardcoded gold) — put
            // back whatever accent color the web theme last told us about,
            // so a re-mount (new episode, quality switch, etc.) doesn't
            // silently reset the player back to gold. See inlineAccentColor
            // field comment for the full picture.
            applyInlineThemeColor()

            playPauseButton.setOnClickListener {
                val p = inlinePlayer ?: return@setOnClickListener
                if (p.isPlaying) p.pause() else p.play()
            }

            // Native ko khud episode URL pata nahi hai — bas web page (Player.jsx)
            // ko bata dete hain "next/prev dabaya", aur wahi apni normal navigate()
            // se episode badal deta hai. Isse title/comments/Up-Next list sab sync
            // rehte hain (agar native khud seedha URL switch karta to yeh sab
            // purane episode ka hi dikhta rehta).
            prevButton.setOnClickListener {
                if (inlineHasPrevEpisode) {
                    webView.evaluateJavascript(
                        "if (window.__suhaniOnNativePrev) window.__suhaniOnNativePrev();", null
                    )
                }
            }
            nextButton.setOnClickListener {
                if (inlineHasNextEpisode) {
                    webView.evaluateJavascript(
                        "if (window.__suhaniOnNativeNext) window.__suhaniOnNativeNext();", null
                    )
                }
            }

            // Bug fix (user report: "runtime bar aur baaki icons ek saath
            // nahi chhupte/aate, achha transition animation chahiye"): top
            // bar ab inline_player_control_view.xml ke andar hi hai (see
            // that file's comment), isliye Media3 use baaki controls ke
            // saath khud hi ek single fade animation mein sync rakhta hai —
            // ab yahan alag se setControllerVisibilityListener se
            // topBarRoot.visibility manually set karne ki zaroorat nahi
            // (yehi manual/instant toggle stagger ki asli wajah tha).

            // --- Gestures: double-tap ±10s seek, hold-anywhere-for-2x-speed ---
            // Fullscreen ke gestureDetector jaisa hi — bas chhote area ke hisaab
            // se feedback (inlineSeekIndicatorLeft/Right / inlineSpeedBadge).
            // BUG FIX (user report): purana single "inlineSeekFeedback" pill
            // white/black text ke saath mushkil se dikhta tha aur bade player
            // se alag dikhta tha (koi icon nahi tha). Ab exact bade player
            // (seekIndicatorLeft/Right) jaisa hi icon-in-circle + white text.
            val seekIndicatorLeft = root.findViewById<View>(R.id.inlineSeekIndicatorLeft)
            val seekIndicatorRight = root.findViewById<View>(R.id.inlineSeekIndicatorRight)
            val seekTextLeft = root.findViewById<TextView>(R.id.inlineSeekTextLeft)
            val seekTextRight = root.findViewById<TextView>(R.id.inlineSeekTextRight)
            val speedBadge = root.findViewById<TextView>(R.id.inlineSpeedBadge)
            val hideInlineSeekLeft = Runnable { seekIndicatorLeft.visibility = View.GONE }
            val hideInlineSeekRight = Runnable { seekIndicatorRight.visibility = View.GONE }

            // Har naye mount par purana seek-session baggage carry na ho.
            inlineSeekSessionHandler.removeCallbacks(inlineSeekSessionResetRunnable)
            inlineLastSeekSide = 0
            inlineSeekAccumulatedUnits = 0
            inlineConsumingSeekContinuation = false

            // BUG FIX (user report): "ek baar double-tap se 10s dikhta hai,
            // usi time ke aaspaas dobara double-tap karo to bhi sirf 10s hi
            // dikhta hai, 20s nahi" — bade player jaisa hi ab yahan bhi kaam
            // karta hai: har seek tap par ek "unit" accumulate hota hai, aur
            // feedback total (units * seekSeconds) dikhata hai (10s -> 20s ->
            // 30s ...) jab tak taps ek chhoti continuation-window ke andar
            // hote rahein, usi side par.
            fun handleInlineSeekTap(forward: Boolean) {
                val p = inlinePlayer ?: return
                val max = p.duration.takeIf { it > 0 } ?: Long.MAX_VALUE
                // Bug fix: yeh hamesha hardcoded 10s istemal karta tha, chahe user ne
                // fullscreen player ke settings mein apna custom seek-duration (5-60s)
                // set kiya ho — matlab dono players ka double-tap-seek alag-alag behave
                // karta tha. Ab wahi shared GesturePrefs preference yahan bhi use hoti hai.
                val seekSecondsPref = GesturePrefs.getSeekSeconds(this@MainActivity)
                val seekMs = seekSecondsPref * 1000L
                val target = if (forward) (p.currentPosition + seekMs).coerceAtMost(max)
                             else (p.currentPosition - seekMs).coerceAtLeast(0)
                p.seekTo(target)

                val side = if (forward) 1 else -1
                inlineSeekAccumulatedUnits += 1
                inlineLastSeekSide = side
                inlineLastSeekTapTime = SystemClock.elapsedRealtime()
                val totalSeconds = inlineSeekAccumulatedUnits * seekSecondsPref

                if (forward) {
                    seekTextRight.text = "${totalSeconds}s"
                    seekIndicatorRight.removeCallbacks(hideInlineSeekRight)
                    seekIndicatorRight.visibility = View.VISIBLE
                    seekIndicatorRight.postDelayed(hideInlineSeekRight, 600)
                } else {
                    seekTextLeft.text = "${totalSeconds}s"
                    seekIndicatorLeft.removeCallbacks(hideInlineSeekLeft)
                    seekIndicatorLeft.visibility = View.VISIBLE
                    seekIndicatorLeft.postDelayed(hideInlineSeekLeft, 600)
                }

                // Bade player jaisa: har tap ke baad session-reset ko fresh se
                // schedule karo, taaki continuation window ke baad accumulated
                // units khud-ba-khud 0 par wapas aa jayein.
                inlineSeekSessionHandler.removeCallbacks(inlineSeekSessionResetRunnable)
                inlineSeekSessionHandler.postDelayed(inlineSeekSessionResetRunnable, INLINE_SEEK_CONTINUATION_WINDOW_MS)
            }

            val gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
                override fun onDoubleTap(e: MotionEvent): Boolean {
                    handleInlineSeekTap(forward = e.x >= playerView.width / 2)
                    return true
                }

                override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                    if (playerView.isControllerFullyVisible) playerView.hideController()
                    else playerView.showController()
                    return true
                }

                override fun onLongPress(e: MotionEvent) {
                    val p = inlinePlayer ?: return
                    inlineLongPressSpeedActive = true
                    inlineSpeedBeforeLongPress = try { p.playbackParameters.speed } catch (_: Exception) { 1f }
                    p.playbackParameters = PlaybackParameters(2f)
                    speedBadge.visibility = View.VISIBLE
                }
            })

            playerView.setOnTouchListener { view, event ->
                // Continuous seek: agar pichhle seek-tap ke turant baad (continuation
                // window ke andar) usi side par dobara tap hua hai, to GestureDetector
                // ko yeh event bilkul na dikhao — seedha continuation maan kar seconds
                // accumulate karo. Android ka GestureDetector khud teesre/chauthe rapid
                // tap ko dobara "double tap" event nahi deta, isliye yeh manual check
                // zaroori hai (bade fullscreen player mein bhi yehi tarika hai).
                if (event.action == MotionEvent.ACTION_DOWN) {
                    val now = SystemClock.elapsedRealtime()
                    if (inlineLastSeekSide != 0 && (now - inlineLastSeekTapTime) < INLINE_SEEK_CONTINUATION_WINDOW_MS) {
                        val side = if (event.x < view.width / 2) -1 else 1
                        if (side == inlineLastSeekSide) {
                            inlineConsumingSeekContinuation = true
                            handleInlineSeekTap(forward = side == 1)
                            inlineSwipeStartX = event.rawX
                            inlineSwipeStartY = event.rawY
                            inlineSwipeDragging = false
                            return@setOnTouchListener true
                        }
                    }
                    inlineConsumingSeekContinuation = false
                }

                if (inlineConsumingSeekContinuation) {
                    if (event.action == MotionEvent.ACTION_UP || event.action == MotionEvent.ACTION_CANCEL) {
                        inlineConsumingSeekContinuation = false
                    }
                    return@setOnTouchListener true
                }

                gestureDetector.onTouchEvent(event)
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        inlineSwipeStartX = event.rawX
                        inlineSwipeStartY = event.rawY
                        inlineSwipeDragging = false
                    }
                    MotionEvent.ACTION_MOVE -> {
                        if (!inlineLongPressSpeedActive) {
                            val dy = event.rawY - inlineSwipeStartY
                            val dx = Math.abs(event.rawX - inlineSwipeStartX)
                            if (dy > 20 && dy > dx) {
                                inlineSwipeDragging = true
                                val progress = (dy / 220f).coerceIn(0f, 1f)
                                root.translationY = dy.coerceAtMost(220f)
                                root.alpha = 1f - (progress * 0.35f)
                                root.scaleX = 1f - (progress * 0.06f)
                                root.scaleY = 1f - (progress * 0.06f)
                            }
                        }
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        val wasDragging = inlineSwipeDragging
                        val dy = event.rawY - inlineSwipeStartY
                        inlineSwipeDragging = false
                        if (inlineLongPressSpeedActive) {
                            inlineLongPressSpeedActive = false
                            inlinePlayer?.playbackParameters = PlaybackParameters(inlineSpeedBeforeLongPress)
                            speedBadge.visibility = View.GONE
                        }
                        if (wasDragging && dy > INLINE_DRAG_PIP_THRESHOLD) {
                            // Instant reset (not animated) right before handing off to
                            // the real PiP transition — same reasoning as the fullscreen
                            // player's swipe fix: animating back to full size at the same
                            // moment the fullscreen-open + system-PiP shrink kicks in
                            // caused a visible snap/flash.
                            root.translationY = 0f
                            root.alpha = 1f
                            root.scaleX = 1f
                            root.scaleY = 1f
                            openFullscreenFromInline(enterPipImmediately = true)
                        } else {
                            root.animate().translationY(0f).alpha(1f).scaleX(1f).scaleY(1f).setDuration(180).start()
                        }
                    }
                }
                true
            }

            val overlay = FrameLayout(this).apply {
                addView(root, FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
                ))
                visibility = View.GONE
            }
            val content = findViewById<ViewGroup>(android.R.id.content)
            content.addView(overlay, FrameLayout.LayoutParams(0, 0))
            inlinePlayerView = playerView
            inlineOverlay = overlay
        }

        val player = inlinePlayer ?: buildInlineExoPlayer().also {
            inlinePlayer = it
            inlinePlayerView?.player = it
            // FEATURE (user ask: subtitle default OFF): fresh player par explicitly
            // text tracks disable karo, taaki container ke andar "default"-flagged
            // koi bhi embedded (ESub) subtitle track khud-ba-khud select na ho jaaye.
            // subtitleManuallyEnabled bhi false rehti hai (naya mount = naya session).
            it.trackSelectionParameters = it.trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                .build()
            subtitleManuallyEnabled = false
            it.addListener(inlineTracksListener)
            it.addListener(inlinePlayPauseListener)
            it.addListener(inlineErrorListener)
        }
        // BUG FIX (user report: "episode 2 pe click karne se pehle screen par
        // episode 1 ka purana/cached last frame dikhta hai — koi bhi episode
        // switch hone par purana wala sath hi sath (turant) end hona chahiye,
        // koi cached frame piche nahi chhutna chahiye"): `setKeepContentOnPlayerReset(true)`
        // (upar, view create hote waqt set hota hai) jaan-bujh kar hai taaki
        // PiP/fullscreen se wapas reattach hote waqt ek pal ka black-flash na
        // dikhe — lekin bilkul wahi setting genuine episode-switch (Up Next
        // list se naya episode click karna) par bhi purani video ka aakhri
        // frame naya episode load/buffer hone tak screen par chipkae rakhti
        // thi, jisse lagta tha purana episode abhi khatam hi nahi hua. Fix:
        // sirf tab surface ko explicitly clear karo jab yeh sach mein ek NAYA
        // episode hai (previousUri maujood tha aur naye uri se alag) —
        // reattach/quality-switch cases (jahan previousUri null hai ya same
        // hi hai) yahan tak pahunchte hi nahi (upar wala early-return unhe
        // pehle hi handle kar chuka), unka black-flash-prevention waisa hi
        // bana rehta hai.
        val isGenuineEpisodeSwitch = previousUri != null && previousUri != uri
        if (isGenuineEpisodeSwitch) {
            inlinePlayerView?.setKeepContentOnPlayerReset(false)
            player.clearMediaItems()
            inlinePlayerView?.setKeepContentOnPlayerReset(true)
        }
        // BUG FIX (user report: "fullscreen mein quality badalne ke baad title
        // gayab ho ke sirf 'Video' likha aata hai"): yeh chhota/inline player kabhi
        // apne MediaItem par title metadata set hi nahi karta tha (isko khud kabhi
        // title dikhaana nahi hota, isliye zaroorat mehsoos nahi hui thi). Lekin
        // fullscreen (PlayerActivity) SharedPlayerHolder ke through isi player
        // instance/MediaItem ko reuse karta hai, aur quality-switch ke waqt title
        // ko purane MediaItem ki metadata se hi copy karta hai — jo yahan hamesha
        // khaali hoti thi. Ab yahan bhi hamesha sahi title metadata set karte hain,
        // taaki fullscreen mein switch karne par asli filename hi carry ho, "Video"
        // generic fallback kabhi na dikhe.
        player.setMediaItem(
            MediaItem.Builder()
                .setUri(Uri.parse(uri))
                .setMediaMetadata(MediaMetadata.Builder().setTitle(inlineTitle).build())
                .build()
        )
        player.prepare()
        // FEATURE (user ask: "jab koi video shuru karen tab 'Processing...'
        // dikhna chahiye jab tak load na ho jaaye"): "Processing…" label ab
        // seedha inlineLastReadyUri se driven hai (dekho uska field
        // comment) — yahan sirf is naye mount ka apna fresh IO-retry budget
        // set karna kaafi hai.
        inlineIoRetryCount = 0
        // Bug fix: chhota player pehle kabhi resume position check hi nahi karta tha
        // — na apni (kyunki khud kabhi save hi nahi karta tha, neeche dekho) na
        // fullscreen wali. Matlab agar fullscreen mein aadhi dekhi video ko dubara
        // isi (inline) tarike se khola jaaye, wo shuru se chalti thi. Ab dono players
        // wahi ek "playback_positions" store share karte hain — fullscreen jaisa poora
        // "Resume?" dialog dikhane ki jagah (chhoti jagah mein jachta nahi), yahan
        // seedha silently us position se resume kar dete hain.
        val savedPosition = getSharedPreferences("playback_positions", MODE_PRIVATE).getLong(uri, 0L)
        if (savedPosition > 5000) {
            player.seekTo(savedPosition)
        }
        player.playWhenReady = true
        // Bug fix / polish: pehle overlay ek jhatke se seedha VISIBLE ho jaata tha
        // (koi entrance feel nahi thi). Sirf naya mount hone par (already visible
        // ho to yeh episode-switch hai, wahan animate karne ki zaroorat nahi) ek
        // chhota scale+fade pop dete hain — same treatment jo fullscreen se wapas
        // aane par onActivityResult mein already istemal hoti hai, taaki dono
        // jagah ka feel consistent rahe.
        // PERMANENT FIX (dekho `isCurrentlyOnWatchPage()` ka bada comment
        // upar `currentWebViewPathOrNull()` ke neeche): pehle yahan sirf
        // `overlay.visibility != View.VISIBLE` check hota tha — matlab agar
        // React kisi bhi wajah se `window.AndroidPlayer.mount()` call kar de
        // (naya src, kisi race mein duplicate-guard bypass, waghera) us waqt
        // jab WebView asal mein "/watch/" page par hai hi nahi (jaise ek real
        // PiP session floating rehte waqt background mein Home par navigate
        // ho chuka ho), overlay seedha WAHIN (Home ke oopar) VISIBLE ho
        // jaata — bilkul jaisa report hua. Ab yahan bhi WebView ke ACTUAL
        // current URL se ground-truth confirm karte hain pehle.
        // PERMANENT FIX (dekho `WebAppInterface.mount()` ka bada comment
        // upar — "video khulte hi black screen" regression): pehle sirf
        // native `webView.url` (racy, IPC-lag-prone) par bharosa karte the.
        // Ab JS khud ka (us waqt already-accurate) `currentPath` bhejta hai —
        // usi ko priority do. `currentPath` sirf tabhi null hota hai jab yeh
        // call kisi purane Kotlin-internal caller se aayi ho (JS bridge se
        // nahi) — sirf us case mein purana native-url-based check fallback
        // ke roop mein istemal karo.
        val onWatchPage = trustedNoCheck || (currentPath?.startsWith("/watch/") ?: isCurrentlyOnWatchPage())
        if (onWatchPage) {
            inlineOverlay?.let { overlay ->
                if (overlay.visibility != View.VISIBLE) {
                    overlay.animate().cancel()
                    overlay.alpha = 0f
                    overlay.scaleX = 0.92f
                    overlay.scaleY = 0.92f
                    overlay.visibility = View.VISIBLE
                    overlay.animate()
                        .alpha(1f).scaleX(1f).scaleY(1f)
                        .setDuration(200)
                        .setInterpolator(android.view.animation.DecelerateInterpolator())
                        .start()
                }
            }
        }

        // PlayerActivity ko bata do ki fullscreen jaate waqt yahi instance
        // reuse karna hai — naya player mat banao, buffering dobara nahi hogi.
        SharedPlayerHolder.player = player
        SharedPlayerHolder.uri = uri
        inlineQualityButtonRef?.text = currentInlineQualityLabel()
    }

    // Ek hi baar shuru hoti hai (mountInlinePlayer ke pehli-baar-create wale
    // block se), phir khud ko har ~800ms mein reschedule karti rehti hai —
    // isliye ek dusri baar mountInlinePlayer call hone par ise dobara start
    // karne ki zaroorat nahi.
    private var inlineAmbientGlowLoopStarted = false
    private fun startInlineAmbientGlowLoop(anchor: View) {
        if (inlineAmbientGlowLoopStarted) return
        inlineAmbientGlowLoopStarted = true
        val runnable = object : Runnable {
            override fun run() {
                if (inlineOverlay?.visibility == View.VISIBLE && inlinePlayer?.isPlaying == true) {
                    sampleInlineAmbientGlowColors()
                }
                anchor.postDelayed(this, 800)
            }
        }
        anchor.postDelayed(runnable, 800)
    }

    /** sampleAmbientGlowColors() (PlayerActivity.kt) jaisa hi — bas inline
     *  chhote player ke TextureView par. */
    private fun sampleInlineAmbientGlowColors() {
        try {
            val glowView = inlineAmbientGlowView ?: return
            val textureView = inlinePlayerView?.videoSurfaceView as? android.view.TextureView ?: return
            if (!textureView.isAvailable) return
            val sample = textureView.getBitmap(32, 32) ?: return

            var topR = 0L; var topG = 0L; var topB = 0L
            var botR = 0L; var botG = 0L; var botB = 0L
            var leftR = 0L; var leftG = 0L; var leftB = 0L
            var rightR = 0L; var rightG = 0L; var rightB = 0L
            val w = sample.width
            val h = sample.height
            if (w == 0 || h == 0) { sample.recycle(); return }

            for (x in 0 until w) {
                val pTop = sample.getPixel(x, 0)
                topR += android.graphics.Color.red(pTop); topG += android.graphics.Color.green(pTop); topB += android.graphics.Color.blue(pTop)
                val pBot = sample.getPixel(x, h - 1)
                botR += android.graphics.Color.red(pBot); botG += android.graphics.Color.green(pBot); botB += android.graphics.Color.blue(pBot)
            }
            for (y in 0 until h) {
                val pLeft = sample.getPixel(0, y)
                leftR += android.graphics.Color.red(pLeft); leftG += android.graphics.Color.green(pLeft); leftB += android.graphics.Color.blue(pLeft)
                val pRight = sample.getPixel(w - 1, y)
                rightR += android.graphics.Color.red(pRight); rightG += android.graphics.Color.green(pRight); rightB += android.graphics.Color.blue(pRight)
            }
            sample.recycle()

            fun avgColor(r: Long, g: Long, b: Long, n: Int) = android.graphics.Color.rgb(
                (r / n).toInt().coerceIn(0, 255),
                (g / n).toInt().coerceIn(0, 255),
                (b / n).toInt().coerceIn(0, 255)
            )

            glowView.updateEdgeColors(
                avgColor(topR, topG, topB, w),
                avgColor(botR, botG, botB, w),
                avgColor(leftR, leftG, leftB, h),
                avgColor(rightR, rightG, rightB, h)
            )
        } catch (_: Exception) {
            // Surface resize/transition ke beech mein sample fail ho sakta hai —
            // safe ignore, agla tick (800ms baad) phir try karega.
        }
    }

    /** Web page (Player.jsx) se aata hai jab bhi current episode ke agal-bagal
     *  (Up Next list ke hisaab se) koi episode maujood hai ya nahi — usi se
     *  chhote player ke prev/next button enable/dim hote hain. */
    fun updateInlineAdjacentEpisodes(hasNext: Boolean, hasPrev: Boolean) {
        inlineHasNextEpisode = hasNext
        inlineHasPrevEpisode = hasPrev
        applyInlineAdjacentButtonState()
    }

    // WebDownloadInterface (NativeDownloadManager) se aane wale progress/done/error
    // events ko web frontend (downloadsStore.js) ke registered callbacks tak pahunchate
    // hain. Optional-chaining (`?.`) isliye taaki agar web page abhi tak load hi na hui
    // ho (ya reload ho rahi ho) to evaluateJavascript silently no-op ho, crash na kare.
    // Crash fix: NativeDownloadManager download background thread par independently
    // chalta hai — user download shuru karke app band/finish() kar sakta hai us se
    // pehle hi download poora ho jaaye. onDestroy() mein webView.destroy() ho chuka
    // hota hai, aur ek destroyed WebView par evaluateJavascript() call karne se
    // crash ("WebView is destroyed"/IllegalStateException) hota — isliye ab yahan
    // isFinishing/isDestroyed check karke us callback ko silently drop kar dete hain.
    // BUG FIX (dekho DownloadService.kt ka doc comment — "background jaate hi
    // download cancel, notification bar mein progress nahi"): downloads ab
    // DownloadService (foreground service) ke andar chalte hain, na ki seedha
    // WebDownloadInterface se. Yeh teeno `DownloadService.ProgressListener`
    // interface overrides hain — jab bhi app foreground mein ho (MainActivity
    // zinda ho aur listener registered ho, dekho onCreate/onDestroy), service
    // inhi ke through JS ko bhi progress forward karta hai. App background mein
    // ho to bhi service khud chalta rehta hai aur notification update karta
    // rehta hai — sirf yeh JS-forwarding skip hoti hai (kyunki WebView tab tak
    // paused/inactive hota hai), UI wapas foreground aane par (listener
    // dobara register hoke) turant sync ho jaata hai (downloadsStore.js apna
    // saved state already IndexedDB/localStorage se rakhta hai).
    override fun onDownloadProgress(id: String, pct: Int, bytes: Long) = notifyDownloadProgress(id, pct, bytes)
    override fun onDownloadDone(id: String, contentUri: String) = notifyDownloadDone(id, contentUri)
    override fun onDownloadPaused(id: String) = notifyDownloadPaused(id)
    override fun onDownloadError(id: String, message: String) = notifyDownloadError(id, message)

    fun notifyDownloadProgress(id: String, progressPct: Int, sizeBytes: Long) {
        if (isFinishing || isDestroyed) return
        val idLit = JSONObject.quote(id)
        webView.evaluateJavascript(
            "window.__nativeDownloadProgress?.($idLit, $progressPct, $sizeBytes)", null
        )
    }

    fun notifyDownloadDone(id: String, contentUri: String) {
        if (isFinishing || isDestroyed) return
        val idLit = JSONObject.quote(id)
        val uriLit = JSONObject.quote(contentUri)
        webView.evaluateJavascript(
            "window.__nativeDownloadDone?.($idLit, $uriLit)", null
        )
    }

    /** FEATURE (user ask: "watch shuru hote hi download queue/pause ho
     *  jaega"): native side (NativeDownloadManager) ne is id ka download
     *  genuinely rok diya hai (watching shuru hone ki wajah se) — JS ko
     *  batao taaki woh ismein 'queued' status dikhaye aur watching band
     *  hote hi apne aap resume kare (dekho downloadsStore.js ka
     *  `window.__nativeDownloadPaused`). */
    fun notifyDownloadPaused(id: String) {
        if (isFinishing || isDestroyed) return
        val idLit = JSONObject.quote(id)
        webView.evaluateJavascript(
            "window.__nativeDownloadPaused?.($idLit)", null
        )
    }

    fun notifyDownloadError(id: String, message: String) {
        if (isFinishing || isDestroyed) return
        val idLit = JSONObject.quote(id)
        val msgLit = JSONObject.quote(message)
        webView.evaluateJavascript(
            "window.__nativeDownloadError?.($idLit, $msgLit)", null
        )
    }

    /** FEATURE (user ask: "stream hote waqt download queue mein chala
     *  jaega, watch band karte hi apne aap shuru ho jaega — single download
     *  ya watch hoga, dono ek saath kabhi nahi"): native rich player
     *  (inline overlay YA fullscreen PlayerActivity — dono [SharedPlayerHolder]
     *  ka wahi ek ExoPlayer instance use karte hain, is listener ke through
     *  hi wired hai, isliye yeh dono cases cover karta hai bina PlayerActivity
     *  chhue) jab bhi actually play/pause hota hai, JS ko batao — downloadsStore.js
     *  isi se decide karta hai ki koi active download turant pause/resume
     *  karna hai ya nahi. */
    fun notifyWatchingChanged(isPlaying: Boolean) {
        if (isFinishing || isDestroyed) return
        webView.evaluateJavascript(
            "window.__nativeWatchingChanged?.($isPlaying)", null
        )
    }

    private fun applyInlineAdjacentButtonState() {
        inlineNextButtonRef?.apply {
            isEnabled = inlineHasNextEpisode
            alpha = if (inlineHasNextEpisode) 1f else 0.35f
        }
        inlinePrevButtonRef?.apply {
            isEnabled = inlineHasPrevEpisode
            alpha = if (inlineHasPrevEpisode) 1f else 0.35f
        }
    }

    /** Bridge entry point for `window.AndroidPlayer.setThemeColor(hex)` — stores
     *  the color and re-paints the currently-mounted inline player (if any).
     *  Safe to call before any player has mounted (just updates the stored
     *  value for whenever mountInlinePlayer() next inflates fresh views). */
    fun setInlineThemeColor(hex: String) {
        val parsed = try {
            Color.parseColor(hex)
        } catch (_: IllegalArgumentException) {
            return // malformed hex from JS — ignore, keep last-known-good color
        }
        if (parsed == inlineAccentColor) return
        inlineAccentColor = parsed
        applyInlineThemeColor()
    }

    /** Re-paints every accent-colored inline-player view with `inlineAccentColor`.
     *  No-ops per-view if that view isn't currently inflated (refs are null
     *  before first mount / after unmount) — safe to call any time. See the
     *  `inlineAccentColor` field comment (above the field declaration) for why
     *  only these specific views are touched and not prev/next/fullscreen. */
    private fun applyInlineThemeColor() {
        val color = inlineAccentColor

        inlinePlayPauseButtonRef?.let { btn ->
            btn.imageTintList = android.content.res.ColorStateList.valueOf(color)
            // bg_inline_hero_button.xml's ring stroke can't be tinted via
            // setTint (that only recolors fills, not strokes), so rebuild
            // the same dark-glass-circle-with-hairline-ring shape in code
            // with the new color at ~60% alpha, matching the original
            // #99FFD700 stroke's opacity.
            val ring = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#66000000"))
                setStroke(dpToPx(1f), applyAlpha(color, 0x99))
            }
            btn.background = ring
        }

        inlineQualityButtonRef?.setTextColor(color)

        inlineTimeBarRef?.let { bar ->
            bar.setPlayedColor(color)
            bar.setScrubberColor(color)
            // scrubber_handle_premium.xml (outer 40%-alpha glow dot + solid
            // inner dot) rebuilt the same way — layer-list drawables also
            // can't be retinted with a single setTint() call.
            val outer = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(applyAlpha(color, 0x40))
                setSize(dpToPx(18f), dpToPx(18f))
            }
            val inner = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(color)
            }
            val innerSize = dpToPx(10f)
            val layered = LayerDrawable(arrayOf(outer, inner)).apply {
                setLayerSize(1, innerSize, innerSize)
                setLayerGravity(1, android.view.Gravity.CENTER)
            }
            bar.setScrubberDrawable(layered)
        }
    }

    private fun dpToPx(dp: Float): Int =
        (dp * resources.displayMetrics.density).toInt()

    /** "#RRGGBB" string for a color int — used to hand `inlineAccentColor`
     *  to PlayerActivity as a plain intent extra (see openFullscreenFromInline). */
    private fun colorToHex(color: Int): String =
        String.format("#%06X", 0xFFFFFF and color)

    /** Replaces just the alpha channel of an RGB color (0-255) — used for the
     *  translucent ring/glow shapes above, which mirror the original XML's
     *  fixed alpha (e.g. #99FFD700, #40FFD700) but now with the live theme's
     *  RGB underneath it. */
    private fun applyAlpha(color: Int, alpha: Int): Int =
        (alpha shl 24) or (color and 0x00FFFFFF)

    /** inlineQualitiesJson (website jaisa hi '[{"url":...,"label":"1080p"},...]') ko
     *  ek simple label→url list mein parse karta hai. Kuch bhi galat/khali ho to
     *  khali list deta hai (crash nahi karta). */
    private fun parseInlineQualities(): List<Pair<String, String>> {
        return try {
            val arr = JSONArray(inlineQualitiesJson)
            (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                (obj.optString("label", "Auto")) to obj.getString("url")
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** Abhi jo url chal raha hai (inlineUri) usi se match karke uska label dhoondta
     *  hai — jaise "1080p". Match na mile to "Auto" dikhata hai. */
    private fun currentInlineQualityLabel(): String {
        return parseInlineQualities().firstOrNull { it.second == inlineUri }?.first ?: "Auto"
    }

    /** Quality / Subtitles / Audio track — teeno ab isi ek premium dark
     *  bottom-sheet se khulte hain (plain grey AlertDialog ki jagah), bilkul
     *  playback-speed sheet jaisa hi look: solid near-black gradient, rounded
     *  top corners, faint gold hairline, aur selected row par gold checkmark. */
    private fun showInlineChoiceSheet(title: String, options: List<String>, selectedIndex: Int, onSelect: (Int) -> Unit) {
        val sheetView = LayoutInflater.from(this).inflate(R.layout.inline_choice_sheet, null)
        sheetView.findViewById<TextView>(R.id.choiceSheetTitle).text = title
        val container = sheetView.findViewById<ViewGroup>(R.id.inlineChoiceRowsContainer)
        val dialog = BottomSheetDialog(this)
        dialog.setContentView(sheetView)

        options.forEachIndexed { index, label ->
            val row = LayoutInflater.from(this).inflate(R.layout.inline_choice_row, container, false)
            val isSelected = index == selectedIndex
            row.findViewById<TextView>(R.id.choiceRowLabel).apply {
                text = label
                setTextColor(if (isSelected) android.graphics.Color.parseColor("#FFD700") else android.graphics.Color.WHITE)
            }
            row.findViewById<TextView>(R.id.choiceRowCheck).visibility = if (isSelected) View.VISIBLE else View.INVISIBLE
            row.setOnClickListener {
                onSelect(index)
                dialog.dismiss()
            }
            container.addView(row)
        }

        dialog.setOnShowListener {
            // Same fix as the settings/speed sheets — clear Material's default
            // white bottom-sheet background so only our dark drawable shows.
            val bottomSheet = dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            bottomSheet?.setBackgroundColor(android.graphics.Color.TRANSPARENT)
        }
        dialog.show()
    }

    private fun showInlineQualityDialog(qualityButton: TextView) {
        val qualities = parseInlineQualities()
        if (qualities.isEmpty()) return
        val labels = qualities.map { it.first }
        val currentIndex = qualities.indexOfFirst { it.second == inlineUri }.coerceAtLeast(0)
        showInlineChoiceSheet("Quality", labels, currentIndex) { which ->
            switchInlineQuality(qualities[which].second)
            qualityButton.text = qualities[which].first
        }
    }

    /** Quality (resolution) badalte waqt ExoPlayer instance wahi rehta hai — sirf
     *  MediaItem badalta hai, aur purani position/playing-state wapas apply hoti
     *  hai, taaki switch karne par video shuru se na chale.
     *  BUG FIX (user report: "480p ya koi aur quality select karta hun, par
     *  1080p hi chalta rehta hai — sirf app mein, website par sahi kaam karta
     *  hai"): pehle sirf `setMediaItem()` + `prepare()` call hota tha — same
     *  ExoPlayer instance par bina `stop()`/`clearMediaItems()` ke naya item
     *  daalne se kabhi-kabhi player ka internal renderer state (especially
     *  jab dono URLs same content-type/container ho, jaisa yahan MKV files
     *  hain) purane item ka format/track selection carry kar leta tha, aur
     *  naya URL fetch hone ke bawajood ExoPlayer purani hi decoded resolution
     *  dikhata rehta tha jab tak koi bada gap (seek/pause) na ho. Ab pehle
     *  poori tarah `stop()` + `clearMediaItems()` karke ek clean slate se naya
     *  MediaItem load karte hain — taaki naya URL/quality genuinely fresh
     *  prepare ho, na ki purane render-state par overlay ho.
     */
    private fun switchInlineQuality(newUrl: String) {
        val player = inlinePlayer ?: return
        // Same quality dobara tap ho to kuch mat karo — na koi reload/flicker,
        // na hi koi galat "switch hua" impression.
        if (newUrl == inlineUri) return
        val pos = player.currentPosition
        val wasPlaying = player.playWhenReady
        inlineUri = newUrl
        SharedPlayerHolder.uri = newUrl
        player.stop()
        player.clearMediaItems()
        // BUG FIX (title-loss follow-up — dekho mountInlinePlayer() ka comment):
        // yahan bhi title metadata zaroor set karo, warna isi switch ke baad
        // fullscreen kholne par title "Video" dikhega.
        player.setMediaItem(
            MediaItem.Builder()
                .setUri(Uri.parse(newUrl))
                .setMediaMetadata(MediaMetadata.Builder().setTitle(inlineTitle).build())
                .build()
        )
        player.prepare()
        player.seekTo(pos)
        player.playWhenReady = wasPlaying

        // BUG FIX (user report: "quality button 480p dikha raha hai lekin niche
        // title/filename abhi bhi 1080p wala hi hai"): pehle website ko is
        // manual (Kotlin-only) switch ka bilkul pata hi nahi chalta tha, isliye
        // `active` stream (aur usse judi har cheez — filename heading, download
        // button quality) hamesha original/pehli quality par atki rehti thi.
        // Ab yahan website ko turant bata dete hain — matching handler
        // Player.jsx mein `window.__suhaniOnNativeQualityChange` hai, jo apna
        // `active` state isi URL wale stream se sync kar leta hai.
        // Is JS call ke jawab mein React `active` badal kar VideoPlayer ko
        // key={active.url} se remount karega, jo turant ek spurious
        // unmount()+mount() round-trip bhejega — usse pehle flag laga do
        // (dekho `suppressNextInlineUnmount` ka comment).
        suppressNextInlineUnmount = true
        suppressNextInlineUnmountHandler.removeCallbacks(suppressNextInlineUnmountReset)
        suppressNextInlineUnmountHandler.postDelayed(suppressNextInlineUnmountReset, 600L)
        val escapedUrl = JSONObject.quote(newUrl)
        webView.evaluateJavascript(
            "if (window.__suhaniOnNativeQualityChange) window.__suhaniOnNativeQualityChange($escapedUrl);",
            null
        )
    }

    /** Reference (YouTube) jaisa settings bottom-sheet — gear tap karne par khulta
     *  hai. Audio track/subtitle ab apne dedicated icon se seedhe kaam karte hain
     *  isliye yahan nahi hain; quality bhi apna alag button hai (fullscreen ke
     *  paas), isliye yahan bhi nahi hai. */
    private fun showInlineSettingsSheet(
        speedButton: TextView,
        decoderButton: TextView,
        pipButton: ImageView,
        aspectButton: ImageView
    ) {
        val sheetView = LayoutInflater.from(this).inflate(R.layout.inline_settings_sheet, null)
        val container = sheetView.findViewById<ViewGroup>(R.id.inlineSettingsRowsContainer)
        val dialog = BottomSheetDialog(this)
        dialog.setContentView(sheetView)

        fun addRow(iconRes: Int, label: String, value: String, onClick: () -> Unit) {
            val row = LayoutInflater.from(this).inflate(R.layout.inline_settings_row, container, false)
            row.findViewById<ImageView>(R.id.settingsRowIcon).setImageResource(iconRes)
            row.findViewById<TextView>(R.id.settingsRowLabel).text = label
            row.findViewById<TextView>(R.id.settingsRowValue).text = value
            row.setOnClickListener {
                onClick()
                dialog.dismiss()
            }
            container.addView(row)
        }

        addRow(R.drawable.ic_speed_ramp, "Playback speed", speedButton.text.toString()) {
            speedButton.performClick()
        }
        addRow(R.drawable.ic_settings, "Decoder", decoderButton.text.toString()) {
            decoderButton.performClick()
        }
        addRow(R.drawable.ic_pip, "Picture-in-picture", "") {
            pipButton.performClick()
        }
        addRow(R.drawable.ic_aspect_ratio, "Aspect ratio", "") {
            aspectButton.performClick()
        }

        dialog.setOnShowListener {
            // Material's BottomSheetDialog wraps our view in its own white
            // sheet background by default — clear it so only our solid dark
            // bg_speed_sheet drawable shows (no white edges/corners). This was
            // the actual reason the panel looked washed-out/whitish before.
            val bottomSheet = dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            bottomSheet?.setBackgroundColor(android.graphics.Color.TRANSPARENT)
        }
        dialog.show()
    }

    // ---------------------------------------------------------------------
    // Playback speed — same premium dark bottom sheet as the fullscreen
    // player (current speed badge, -/+ buttons + fine slider, quick preset
    // pills). Replaces the old "tap the label to cycle" behavior; reached
    // both directly (tapping the top-bar label) and via the settings sheet's
    // "Playback speed" row.
    // ---------------------------------------------------------------------
    private val inlineSpeedPresets = floatArrayOf(1.0f, 1.25f, 1.5f, 2.0f, 3.0f)
    private val INLINE_SPEED_MIN = 0.25f
    private val INLINE_SPEED_MAX = 3.0f
    private val INLINE_SPEED_STEP = 0.05f

    private fun formatInlineSpeedLabel(speed: Float): String {
        val rounded = Math.round(speed * 100) / 100.0
        return if (rounded == rounded.toLong().toDouble()) "${rounded.toLong()}.00x"
        else String.format("%.2fx", rounded)
    }

    private fun showInlineSpeedSheet(speedButton: TextView) {
        val player = inlinePlayer ?: return
        val currentSpeed = try { player.playbackParameters.speed } catch (_: Exception) { 1f }
        val sheet = BottomSheetDialog(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = androidx.core.content.ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_speed_sheet)
            setPadding(dp(22), dp(14), dp(22), dp(26))
        }

        root.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(4)).apply {
                gravity = android.view.Gravity.CENTER_HORIZONTAL
                bottomMargin = dp(18)
            }
            background = androidx.core.content.ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_icon_circle)
            alpha = 0.5f
        })

        val speedLabel = TextView(this).apply {
            text = formatInlineSpeedLabel(currentSpeed)
            textSize = 20f
            setTextColor(android.graphics.Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
            gravity = android.view.Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(18) }
        }
        root.addView(speedLabel)

        val steps = Math.round((INLINE_SPEED_MAX - INLINE_SPEED_MIN) / INLINE_SPEED_STEP)

        val sliderRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(20) }
        }

        val minusBtn = TextView(this).apply {
            text = "−"
            textSize = 20f
            setTextColor(android.graphics.Color.WHITE)
            gravity = android.view.Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(dp(36), dp(36))
            background = androidx.core.content.ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_icon_circle)
        }
        val plusBtn = TextView(this).apply {
            text = "+"
            textSize = 20f
            setTextColor(android.graphics.Color.WHITE)
            gravity = android.view.Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(dp(36), dp(36))
            background = androidx.core.content.ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_icon_circle)
        }
        val seek = SeekBar(this).apply {
            max = steps
            progress = Math.round((currentSpeed.coerceIn(INLINE_SPEED_MIN, INLINE_SPEED_MAX) - INLINE_SPEED_MIN) / INLINE_SPEED_STEP)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(10)
                marginEnd = dp(10)
            }
            progressTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#FFD700"))
            thumbTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#FFD700"))
        }
        sliderRow.addView(minusBtn)
        sliderRow.addView(seek)
        sliderRow.addView(plusBtn)
        root.addView(sliderRow)

        val pillsRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val pillViews = mutableListOf<Pair<TextView, Float>>()

        fun refreshPills(selected: Float) {
            pillViews.forEach { (tv, value) ->
                val isSel = Math.abs(value - selected) < 0.01f
                tv.background = androidx.core.content.ContextCompat.getDrawable(
                    this@MainActivity,
                    if (isSel) R.drawable.bg_speed_pill_selected else R.drawable.bg_speed_pill
                )
                tv.setTextColor(if (isSel) android.graphics.Color.parseColor("#0B0B12") else android.graphics.Color.WHITE)
            }
        }

        fun applySpeed(raw: Float, syncSlider: Boolean) {
            val clamped = raw.coerceIn(INLINE_SPEED_MIN, INLINE_SPEED_MAX)
            val rounded = Math.round(clamped / INLINE_SPEED_STEP) * INLINE_SPEED_STEP
            player.playbackParameters = PlaybackParameters(rounded)
            speedLabel.text = formatInlineSpeedLabel(rounded)
            speedButton.text = formatInlineSpeedLabel(rounded).let {
                val f = rounded
                if (f == f.toLong().toFloat()) "${f.toLong()}x" else String.format("%.2fx", f)
            }
            if (syncSlider) seek.progress = Math.round((rounded - INLINE_SPEED_MIN) / INLINE_SPEED_STEP)
            refreshPills(rounded)
        }

        inlineSpeedPresets.forEach { s ->
            val tv = TextView(this).apply {
                text = if (s == 1f) "Normal" else formatInlineSpeedLabel(s).removeSuffix("0x") + "x"
                textSize = 12f
                setTypeface(typeface, Typeface.BOLD)
                gravity = android.view.Gravity.CENTER
                setPadding(0, dp(11), 0, dp(11))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    if (s != inlineSpeedPresets.last()) marginEnd = dp(6)
                }
                setOnClickListener { applySpeed(s, syncSlider = true) }
            }
            pillViews.add(tv to s)
            pillsRow.addView(tv)
        }
        root.addView(pillsRow)
        refreshPills(currentSpeed)

        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                applySpeed(INLINE_SPEED_MIN + progress * INLINE_SPEED_STEP, syncSlider = false)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        minusBtn.setOnClickListener {
            applySpeed((player.playbackParameters.speed - INLINE_SPEED_STEP * 5), syncSlider = true)
        }
        plusBtn.setOnClickListener {
            applySpeed((player.playbackParameters.speed + INLINE_SPEED_STEP * 5), syncSlider = true)
        }

        sheet.setContentView(root)
        sheet.setOnShowListener {
            val bottomSheet = sheet.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            bottomSheet?.setBackgroundColor(android.graphics.Color.TRANSPARENT)
        }
        sheet.show()
    }

    // Dekho `awaitingRectAfterPipReturn` field ka comment upar — is ek chhoti
    // fade-in animation ko ek helper mein nikaal diya hai taaki normal-return
    // path AUR updateInlinePlayerRect() (real-PiP-return path) dono ise
    // istemal kar sakein.
    private fun showInlineOverlayWithFade(currentPath: String? = null, trustedNoCheck: Boolean = false) {
        // PERMANENT FIX (dekho `isCurrentlyOnWatchPage()` ka bada comment
        // upar): yeh sabse aakhri "gate" hai. Chahe caller (onActivityResult
        // ka PiP-return path, updateInlinePlayerRect() ka
        // awaitingRectAfterPipReturn resolve, ya koi bhi future caller) kitna
        // bhi sochta ho ki WebView abhi /watch/ page par hai — yahan dobara,
        // definitively confirm karo. JS-supplied `currentPath` (jab
        // available ho — accurate, IPC-lag-free) ko priority do; sirf jab
        // yeh purane, purely-native-triggered call-site se aaya ho (JS
        // pairing ke bina — jaise onActivityResult ka safety-net timeout),
        // native `webView.url`-based check par fallback karo. Agar nahi, to
        // bilkul show hi mat karo — overlay VISIBLE karke use kisi galat
        // page (jaise Home) ke oopar dikhne dena is poore bug-class ka asli
        // symptom hai. Retry-mechanisms (attemptPipReturnNavigate() ki 13x
        // retry, onPageFinished() ka safety-net) khud hi WebView ko sahi
        // page par le aayenge — us fresh "/watch/" mount se aane wala
        // genuine updateInlinePlayerRect() call hi tab is function ko
        // dobara, successfully call karega.
        val onWatchPage = trustedNoCheck || (currentPath?.startsWith("/watch/") ?: isCurrentlyOnWatchPage())
        if (!onWatchPage) return
        inlineOverlay?.let { overlay ->
            overlay.animate().cancel()
            overlay.alpha = 0f
            overlay.scaleX = 0.96f
            overlay.scaleY = 0.96f
            overlay.visibility = View.VISIBLE
            overlay.animate()
                .alpha(1f).scaleX(1f).scaleY(1f)
                .setDuration(180)
                .setInterpolator(android.view.animation.DecelerateInterpolator())
                .start()
        }
    }

    /** JS se aayi CSS px (viewport-relative) rect ko real device px mein convert karke
     *  chhote player ko us video-container ki exact jagah par rakhta/resize karta hai. */
    fun updateInlinePlayerRect(left: Double, top: Double, width: Double, height: Double, currentPath: String? = null) {
        // PERMANENT FIX (dekho `lastKnownWatchPath` field ka comment): yeh
        // call bhi (ResizeObserver/scroll-driven, isliye sabse zyada baar
        // fire hone wala) accurate JS-supplied path deta hai — isko bhi
        // yaad rakhne mein use karo.
        if (currentPath?.startsWith("/watch/") == true) lastKnownWatchPath = currentPath
        val overlay = inlineOverlay ?: return
        val density = resources.displayMetrics.density
        val params = (overlay.layoutParams as? FrameLayout.LayoutParams)
            ?: FrameLayout.LayoutParams(0, 0)
        params.width = (width * density).toInt()
        params.height = (height * density).toInt()
        params.leftMargin = (left * density).toInt()
        params.topMargin = statusBarInsetPx + (top * density).toInt()
        overlay.layoutParams = params
        // BUG FIX (dekho `awaitingRectAfterPipReturn` field ka comment): yeh
        // pakka signal hai ki JS ab WAAKI (sahi) page par dobara mount ho
        // chuki hai aur is video ke container ka asli, current rect bhej
        // chuki hai — ab safely (aur sahi jagah par) overlay dikhao.
        if (awaitingRectAfterPipReturn) {
            awaitingRectAfterPipReturn = false
            awaitingRectTimeoutRunnable?.let { awaitingRectHandler.removeCallbacks(it) }
            awaitingRectTimeoutRunnable = null
            // PERMANENT FIX (dekho `WebAppInterface.mount()` ka bada comment
            // — "black screen" regression): yahan bhi JS-supplied
            // `currentPath` ko priority do (agar bridge caller ne bheja ho),
            // native racy `webView.url` fallback sirf tab jab JS side se
            // path na aaya ho.
            showInlineOverlayWithFade(currentPath)
        }
    }

    /** `unmountInlinePlayer()` jaisa hi hard-hide (pause + fade-out), lekin
     *  `suppressNextInlineUnmount` flag ko jaan-bujh kar BYPASS karta hai — sirf
     *  `doUpdateVisitedHistory()` wale safety-net se bulaya jaata hai jab
     *  WebView genuinely watch page se hat chuki ho. Isse koi bhi stale/stuck
     *  suppress-flag is definitive cleanup ko kabhi rok nahi paata — dekho
     *  `suppressNextInlineUnmount` field aur `doUpdateVisitedHistory()` ke
     *  comments. Already-hidden overlay par no-op hai (bewajah re-animate
     *  nahi karta). */
    private fun forceHideInlineOverlay() {
        val overlay = inlineOverlay ?: return
        if (overlay.visibility != View.VISIBLE) return
        inlineSeekSessionHandler.removeCallbacks(inlineSeekSessionResetRunnable)
        saveInlineWatchProgress()
        inlinePlayer?.pause()
        overlay.animate().cancel()
        overlay.animate()
            .alpha(0f).scaleX(0.92f).scaleY(0.92f)
            .setDuration(150)
            .withEndAction {
                overlay.visibility = View.GONE
                overlay.alpha = 1f
                overlay.scaleX = 1f
                overlay.scaleY = 1f
            }
            .start()
    }

    fun unmountInlinePlayer() {
        // Dekho `suppressNextInlineUnmount` ki declaration ke paas ka comment —
        // yeh call genuinely "page/video chhodi" ki wajah se nahi, balki humaari
        // apni switchInlineQuality() ki React-sync se aayi ek spurious round-trip
        // hai. Ignore karo — playback jaisi hai waisi hi rehne do, overlay bhi
        // visible rehne do; is ke turant baad aane wala mount() call bhi khud hi
        // no-op ho jaayega (same URI guard).
        if (suppressNextInlineUnmount) {
            suppressNextInlineUnmount = false
            suppressNextInlineUnmountHandler.removeCallbacks(suppressNextInlineUnmountReset)
            return
        }
        inlineSeekSessionHandler.removeCallbacks(inlineSeekSessionResetRunnable)
        saveInlineWatchProgress()
        // Hygiene: video genuinely band ho rahi hai — koi bhi PURANI "yaad
        // rakhi hui" watch-page yahan se aage kisi galat/stale PiP-return
        // target ke roop mein istemal na ho.
        lastKnownWatchPath = null
        inlinePlayer?.pause()
        inlineOverlay?.let { overlay ->
            overlay.animate().cancel()
            overlay.animate()
                .alpha(0f).scaleX(0.92f).scaleY(0.92f)
                .setDuration(150)
                .withEndAction {
                    overlay.visibility = View.GONE
                    overlay.alpha = 1f
                    overlay.scaleX = 1f
                    overlay.scaleY = 1f
                }
                .start()
        }
    }

    /** Fullscreen wale buildPlayer() jaisa hi — FfmpegRenderersFactory (HW/HW+/SW
     *  decoder switch, LGPL audio codecs) ke saath ExoPlayer banata hai, taaki
     *  chhota inline player bhi wahi decoder options support kare. */
    private fun buildInlineExoPlayer(): ExoPlayer {
        val extensionMode = when (decoderMode) {
            0 -> DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF
            1 -> DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON
            else -> DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER
        }
        val renderersFactory = FfmpegRenderersFactory(this, inlineEqProcessor)
            .setExtensionRendererMode(extensionMode)
            .setEnableDecoderFallback(decoderMode != 0)

        // Bug fix (buffering slow): fullscreen PlayerActivity jaisa hi tuning yahan bhi —
        // pehle yeh chhota inline/mini player bilkul default LoadControl aur bina kisi
        // custom DataSource ke chal raha tha (8s HTTP timeout, cross-protocol redirect
        // band, zero disk cache). Ab dono players PlayerNetwork ka shared tuned +
        // cache-backed DataSource use karte hain, aur mini<->fullscreen switch karne par
        // bhi already-buffered data dobara download nahi hota.
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(15_000, 50_000, 1_500, 2_500)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()
        val mediaSourceFactory = DefaultMediaSourceFactory(this)
            .setDataSourceFactory(PlayerNetwork.dataSourceFactory(this))

        return ExoPlayer.Builder(this, renderersFactory)
            .setLoadControl(loadControl)
            .setMediaSourceFactory(mediaSourceFactory)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                /* handleAudioFocus= */ true
            )
            // Bug fix: fullscreen player jaisa hi — pehle set nahi tha, isliye
            // headphone/Bluetooth unplug hone par chhota player bhi turant loud
            // speaker par bajne lagta tha (koi auto-pause nahi).
            .setHandleAudioBecomingNoisy(true)
            .build()
    }

    /** Decoder badalne ke liye poora ExoPlayer instance dobara banana padta hai
     *  (renderersFactory sirf construction time par set hoti hai) — isliye position,
     *  playing-state aur track selection (audio/subtitle choice) capture karke naye
     *  player par wapas apply karte hain, taaki switch ke baad bhi wahi cheez chale. */
    private fun rebuildInlinePlayer() {
        val old = inlinePlayer ?: return
        val pos = old.currentPosition
        val wasPlaying = old.playWhenReady
        val previousParams = old.trackSelectionParameters
        old.removeListener(inlineTracksListener)
        old.removeListener(inlinePlayPauseListener)
        old.removeListener(inlineErrorListener)
        old.release()
        if (SharedPlayerHolder.player === old) SharedPlayerHolder.clear()

        val fresh = buildInlineExoPlayer()
        fresh.trackSelectionParameters = previousParams
        fresh.addListener(inlineTracksListener)
        fresh.addListener(inlinePlayPauseListener)
        fresh.addListener(inlineErrorListener)
        inlinePlayer = fresh
        inlinePlayerView?.player = fresh
        SharedPlayerHolder.player = fresh
        SharedPlayerHolder.uri = inlineUri
        // BUG FIX (title-loss follow-up): yahan bhi title metadata set karo — dekho
        // mountInlinePlayer() ka comment.
        fresh.setMediaItem(
            MediaItem.Builder()
                .setUri(Uri.parse(inlineUri))
                .setMediaMetadata(MediaMetadata.Builder().setTitle(inlineTitle).build())
                .build()
        )
        fresh.prepare()
        fresh.seekTo(pos)
        fresh.playWhenReady = wasPlaying
    }

    /** Fullscreen ke showDecoderDialog() jaisa hi (HW/HW+/SW choice), bas chhote
     *  player par rebuildInlinePlayer() se apply hota hai. */
    private fun showInlineDecoderDialog(decoderButton: TextView) {
        val options = arrayOf("HW decoder", "HW+ decoder", "SW decoder")
        AlertDialog.Builder(this)
            .setTitle("Select decoder")
            .setSingleChoiceItems(options, decoderMode) { dialog, which ->
                decoderMode = which
                decoderButton.text = when (which) {
                    0 -> "HW"
                    1 -> "HW+"
                    else -> "SW"
                }
                rebuildInlinePlayer()
                dialog.dismiss()
            }
            .show()
    }

    /** Fullscreen ke showSubtitleMenu() ka compact version — track list + Off,
     *  is se pehle bas ek silent on/off toggle tha jisme kuch dikhta hi nahi tha
     *  ki kaunsi track chal rahi hai. */
    private fun showInlineSubtitleDialog(subtitleButton: ImageView) {
        val player = inlinePlayer ?: return
        val textGroups = player.currentTracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }
        val labels = mutableListOf<String>()
        val trackRefs = mutableListOf<Pair<Tracks.Group, Int>>()

        textGroups.forEach { group ->
            for (i in 0 until group.length) {
                val format = group.getTrackFormat(i)
                val langCode = format.language?.trim()?.takeIf { it.isNotEmpty() && !it.equals("und", ignoreCase = true) }
                val label = format.label?.trim()?.takeIf { it.isNotEmpty() }
                val name = when {
                    langCode != null -> try {
                        java.util.Locale(langCode).getDisplayLanguage(java.util.Locale.ENGLISH)
                    } catch (_: Exception) { langCode }
                    label != null -> label
                    else -> "Subtitle #${labels.size + 1}"
                }
                labels.add(name)
                trackRefs.add(Pair(group, i))
            }
        }

        if (labels.isEmpty()) {
            android.widget.Toast.makeText(this, "Is video mein koi subtitle track nahi mili", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        labels.add("Off")

        // Selected index: agar user ne khud koi track ON nahi ki (default state),
        // dialog "Off" ko highlighted dikhaye — track "isSelected" honi (container
        // ke andar) subtitle ON hone ka matlab nahi rakhti ab, kyunki hum text
        // tracks default se hi disabled rakhte hain.
        val selectedIndex = if (!subtitleManuallyEnabled) labels.size - 1
            else trackRefs.indexOfFirst { (group, i) -> group.isTrackSelected(i) }.let { if (it < 0) 0 else it }

        showInlineChoiceSheet("Subtitles", labels, selectedIndex) { which ->
            if (which == labels.size - 1) {
                subtitleManuallyEnabled = false
                player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                    .build()
                subtitleButton.alpha = 0.5f
            } else {
                subtitleManuallyEnabled = true
                val (group, index) = trackRefs[which]
                player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                    .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, index))
                    .build()
                subtitleButton.alpha = 1f
            }
        }
    }

    /** Fullscreen ke showAudioTrackDialog() ka compact version — track list + Disable,
     *  koi settings sub-screen nahi (wo dialog fullscreen mein already khula milta
     *  hai agar zyada advanced audio options chahiye ho).
     *
     *  Robustness fix: kabhi-kabhi ek group ka reported `.type` audio nahi dikhta
     *  (renderer-mapping quirk) jabki uska format `audio/...` mime hi hai — isliye
     *  ab type-check ke saath-saath mimeType se bhi audio tracks dhoondte hain,
     *  taaki genuinely single-audio-track file par bhi dialog khaali na dikhe. */
    private fun showInlineAudioTrackDialog() {
        val player = inlinePlayer ?: return
        val allGroups = player.currentTracks.groups
        val audioGroups = allGroups.filter { group ->
            group.type == C.TRACK_TYPE_AUDIO ||
                (0 until group.length).any { i -> group.getTrackFormat(i).sampleMimeType?.startsWith("audio/") == true }
        }
        val labels = mutableListOf<String>()
        val trackRefs = mutableListOf<Pair<Tracks.Group, Int>>()

        audioGroups.forEach { group ->
            for (i in 0 until group.length) {
                val format = group.getTrackFormat(i)
                val langCode = format.language?.trim()?.takeIf { it.isNotEmpty() && !it.equals("und", ignoreCase = true) }
                val label = format.label?.trim()?.takeIf { it.isNotEmpty() }
                val name = when {
                    langCode != null -> try {
                        java.util.Locale(langCode).getDisplayLanguage(java.util.Locale.ENGLISH)
                    } catch (_: Exception) { langCode }
                    label != null -> label
                    else -> "Track #${labels.size + 1}"
                }
                labels.add(name)
                trackRefs.add(Pair(group, i))
            }
        }

        if (labels.isEmpty()) {
            android.widget.Toast.makeText(this, "Audio track ki info abhi load ho rahi hai, thodi der mein try karein", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        labels.add("Disable")

        var selectedIndex = trackRefs.indexOfFirst { (group, i) -> group.isTrackSelected(i) }
        if (selectedIndex < 0) selectedIndex = if (audioManuallyDisabled) labels.size - 1 else 0

        showInlineChoiceSheet("Audio track", labels, selectedIndex) { which ->
            if (which == labels.size - 1) {
                audioManuallyDisabled = true
                player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
                    .build()
            } else {
                audioManuallyDisabled = false
                val (group, index) = trackRefs[which]
                player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
                    .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, index))
                    .build()
            }
        }
    }

    /** Chhote player ke fullscreen/more/PiP button se poora native PlayerActivity khulta hai,
     *  wahi position/playing-state ke saath; wapas aane par onActivityResult se sync hota hai.
     *  enterPipImmediately=true ho to PlayerActivity khulte hi turant real PiP mein chala jaata hai
     *  (isi Activity ka apna already-working PiP implementation use karke). */
    private fun openFullscreenFromInline(enterPipImmediately: Boolean = false) {
        val pos = inlinePlayer?.currentPosition ?: 0L
        val wasPlaying = inlinePlayer?.playWhenReady ?: true
        // Bug fix (premium smoothness / "buffering" feel): pehle yahan turant
        // inlinePlayer?.pause() ho jaata tha, aur PlayerActivity turant hi
        // resume_playing extra se wapas playWhenReady=true kar deta (usi live
        // shared decoder par) — ek fauri pause-fir-resume cycle, jo audio/video
        // sync ko ek pal ke liye todta tha, bilkul "buffer/lag" jaisa mehsoos
        // hota. Ab pause bilkul nahi karte — same-instance handoff ke dauraan
        // player continuously chalta rehta hai (bas Surface thodi der ke liye
        // kisi bhi PlayerView se attach nahi hota), YouTube jaisa gapless feel.
        // ExoPlayer.setForegroundMode(true) yahan decoder ko is chhoti
        // surface-less gap ke dauraan zinda/ready rakhta hai, taaki naye
        // (fullscreen) Surface par attach hote hi bina kisi re-init/rebuffer ke
        // turant continue ho jaaye.
        inlinePlayer?.setForegroundMode(true)
        // Bug fix (PiP button/swipe par "badi wali screen khulti hai fir PiP
        // hoti hai" jhatka): yahi rect ab do jagah kaam aata hai — (1) niche
        // activity-launch ko poori tarah invisible/instant banane ke liye, aur
        // (2) intent extras (pip_src_*) ke through PlayerActivity ko bhejte hain
        // taaki wahan ka system PiP-shrink animation seedha isi chhote inline-
        // player ki jagah se shuru ho, poori screen se nahi — dekho niche wala
        // startActivityForResult() block aur PlayerActivity.buildPipParams().
        val launchRect = Rect()
        if (enterPipImmediately) {
            try { inlinePlayerView?.getGlobalVisibleRect(launchRect) } catch (_: Exception) {}
        }
        // Bug fix (black screen with audio-only playing): pehle sirf overlay ko GONE
        // karte the, lekin inlinePlayerView ka `.player` reference wahi rehta tha —
        // matlab ExoPlayer ka video-output Surface do PlayerView (yeh inline wala,
        // aur PlayerActivity ka fullscreen wala) ke beech simultaneously "fight" karta
        // tha. Jab GONE hua PlayerView apna Surface release karta hai, ExoPlayer ka
        // render target null ho jaata — result: naya (fullscreen) PlayerView bhi kabhi
        // kabhi black rehta, audio chalta rehta kyunki wo Surface se independent hai.
        // Fix: yahin explicitly detach kar do, taaki sirf ek hi PlayerView kisi bhi
        // waqt is player se juda ho.
        inlinePlayerView?.player = null
        inlineOverlay?.visibility = View.GONE
        // User ki request: PiP shuru hote hi is watch page ko poora hata kar
        // WebView ko peechli (info/Detail) page par le jao, taaki PiP window ke
        // peeche wahi saaf page dikhe — is page ka khaali video-hole nahi. Sirf
        // real PiP wale trigger (pip button/chevron/swipe/home-button) ke liye,
        // plain "fullscreen" button (enterPipImmediately=false) page ko chhedta
        // nahi — wahan user seedha isi page par wapas aana chahta hai.
        // Bug fix (dekho `pipReturnPath` field ka comment): browser forward-
        // history par bharosa karne se pehle, is watch page ka exact path
        // (pathname + query) yahin, goBack() se theek pehle, capture kar lo —
        // taaki expand par ise history state se independent, direct navigate()
        // se wapas laaya jaa sake.
        // BUG FIX (naya user report: "PiP karne se PiP is player screen par hi
        // hona chahiye, home page par nahi"): pehle yahan (upar wale purane
        // comment mein bataye gaye ek pehle wale request ke hisaab se)
        // `webView.goBack()` call hoti thi jab bhi real PiP shuru hoti — matlab
        // is watch page ko turant chhod kar WebView peechli (Home/Detail) page
        // par chali jaati thi, taaki PiP window ke "peeche" wahi dikhe. Lekin
        // isi wajah se PiP shuru hote hi background mein Home page dikhne
        // lagta tha — bilkul jaisa ab report hua ("home main pip ho jaata
        // hai"). Fix: ab is watch/player page ko chhodte hi nahi — WebView
        // yahin isi page par rehta hai, PiP window bas isi ke oopar chhoti
        // ho kar tairti hai (YouTube jaisa). `pipReturnPath` abhi bhi capture
        // karte hain — agar user PiP ke dauraan khud kahin aur navigate kar
        // le, expand par wapas isi watch page par le aane ke liye (neeche
        // wale onActivityResult ka existing safety-net), lekin ab khud se
        // kabhi goBack() nahi karte.
        pipReturnPath = if (enterPipImmediately) (lastKnownWatchPath ?: currentWebViewPathOrNull()) else null
        didNavigateBackForPip = false
        // Real PiP session yahin se shuru maani jaati hai — onKeyDown() ab
        // is watch page ko badalne se bachaayega jab tak PlayerActivity se
        // result wapas na aa jaaye (dekho `pipSessionActive` field comment).
        if (enterPipImmediately) {
            pipSessionActive = true
            pipSessionWatchdogHandler.removeCallbacks(pipSessionWatchdogReset)
            pipSessionWatchdogHandler.postDelayed(pipSessionWatchdogReset, 10 * 60 * 1000L)
        }
        // Dekho `awaitingRectAfterPipReturn` field ka comment upar — sirf
        // isi (genuinely-navigated-away) case mein overlay ka turant show
        // hona rokna hai.
        awaitingRectAfterPipReturn = didNavigateBackForPip
        val intent = Intent(this, PlayerActivity::class.java).apply {
            putExtra("video_uri", inlineUri)
            putExtra("video_title", inlineTitle)
            putExtra("video_qualities_json", inlineQualitiesJson)
            putExtra("resume_position_ms", pos)
            putExtra("resume_playing", wasPlaying)
            putExtra("enter_pip_immediately", enterPipImmediately)
            // Carries the inline player's CURRENT theme accent color forward
            // so fullscreen opens already matching it instead of resetting
            // to the old hardcoded gold — see PlayerActivity's
            // `themeAccentColor` field comment for the full picture.
            putExtra("theme_color", colorToHex(inlineAccentColor))
            // Bug fix ("bada player khulta hai fir PiP hota hai" jhatka — asli wajah):
            // PlayerActivity ko yeh chhote inline player ka EXACT on-screen rect bhi
            // bhej do, taaki wahan real PiP mein jaate waqt system ka shrink animation
            // seedha ISI chhoti jagah se shuru ho, poori-screen wale playerView rect se
            // nahi — dekho PlayerActivity.buildPipParams().
            if (enterPipImmediately && launchRect.width() > 0 && launchRect.height() > 0) {
                putExtra("pip_src_left", launchRect.left)
                putExtra("pip_src_top", launchRect.top)
                putExtra("pip_src_right", launchRect.right)
                putExtra("pip_src_bottom", launchRect.bottom)
            }
        }
        @Suppress("DEPRECATION")
        if (enterPipImmediately) {
            // Bug fix ("bada player khulta hai fir PiP hota hai" — do jhatkon wala
            // flash): pehle yahan makeScaleUpAnimation() se activity ko chhote inline
            // rect se POORI SCREEN tak "grow" hote dikhate the, aur uske turant baad
            // ek ALAG system PiP-shrink animation shuru hoti thi (enterPipWhenFrameReady()
            // se) — yeh do alag-alag animations back-to-back chalti thi, isliye beech
            // mein ek pal ke liye poori screen "flash" hoti dikhti thi (pehle grow ho
            // kar ruk jaata, fir shrink shuru hota) — bilkul "bada player khula fir PiP
            // hua" jaisa laga, chahe dono animations back-to-back hi kyun na hon.
            //
            // Fix: is activity-launch transition ko ab poori tarah invisible/instant kar
            // do (0ms — koi grow-animation nahi dikhta) — sirf EK hi visible motion honi
            // chahiye: khud system ka PiP-enter shrink animation, jiska sourceRectHint
            // (upar bheje gaye pip_src_* extras se, PlayerActivity.buildPipParams() mein)
            // ab isi launchRect (chhote inline player ki jagah) se seedha shuru hota hai.
            // Result: chhota inline video seedha turant PiP corner tak "shrink" hote
            // dikhta hai — koi beech mein full-screen flash nahi, ek hi continuous
            // motion, YouTube jaisa "direct PiP".
            val options = android.app.ActivityOptions.makeCustomAnimation(this, 0, 0)
            startActivityForResult(intent, REQUEST_FULLSCREEN_PLAYER, options.toBundle())
        } else {
            startActivityForResult(intent, REQUEST_FULLSCREEN_PLAYER)
        }
    }

    /** BUG FIX (user report + screenshot: "PiP expand karne par watch page ki
     *  jagah app ke Home tab par khul jaata hai"): dekho onActivityResult() ke
     *  restore-path wale comment mein poori wajah. Yeh helper
     *  `window.__suhaniPipReturnTo(path)` ko call karta hai aur JS se
     *  confirm leta hai ki bridge function genuinely mila/chala ya nahi
     *  (function-not-yet-registered hone par JS `false` return karta hai).
     *  Agar abhi safal nahi hua (React abhi WebView reload/restart ke baad
     *  poora mount nahi hua), 150ms baad khud ko dobara call karta hai — kul
     *  ~13 attempts (~2 second) tak, jab tak safal na ho jaaye ya `unmount`/
     *  naya PiP session shuru na ho jaaye (dekho `pendingPipReturnPath` check
     *  — agar target beech mein badal/clear ho jaaye to purana retry chup-
     *  chaap ruk jaata hai). */
    private fun attemptPipReturnNavigate(path: String, attemptsLeft: Int) {
        if (pendingPipReturnPath != path) return // koi naya session/target aa chuka, yeh stale retry hai
        webView.evaluateJavascript(
            "(function(){ if (window.__suhaniPipReturnTo) { window.__suhaniPipReturnTo(${JSONObject.quote(path)}); return true; } return false; })()",
        ) { result ->
            val succeeded = result == "true"
            if (succeeded || attemptsLeft <= 0) {
                if (pendingPipReturnPath == path) pendingPipReturnPath = null
            } else {
                pipReturnRetryHandler.postDelayed(
                    { attemptPipReturnNavigate(path, attemptsLeft - 1) },
                    150L
                )
            }
        }
    }

    // Bug fix (dekho `pipReturnPath` field ka comment): WebView ke current
    // loaded URL (jo SPA route ke saath hamesha in-sync rehta hai — React
    // Router BrowserRouter asli address bar URL hi use karta hai) se sirf
    // path + query nikaal deta hai, taaki React Router ke apne navigate() ko
    // seedha wahi diya jaa sake (poora origin/scheme uske liye zaroori nahi).
    private fun currentWebViewPathOrNull(): String? {
        val url = webView.url ?: return null
        return try {
            val uri = Uri.parse(url)
            val path = uri.path?.takeIf { it.isNotBlank() } ?: "/"
            val query = uri.query
            if (!query.isNullOrEmpty()) "$path?$query" else path
        } catch (_: Exception) {
            null
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // PERMANENT ROOT-CAUSE FIX (user report + screenshots: "PiP expand karte
    // waqt video player Home page ke oopar chipka reh jaata hai, asli watch
    // page par nahi").
    //
    // Poori is class mein ab tak jitne bhi PiP-related bug fixes the (upar
    // `pipReturnPath`, `pipSessionActive`, `awaitingRectAfterPipReturn`,
    // `attemptPipReturnNavigate()` waghera), sab isi ek tareeke se kaam karte
    // the: "sahi choreography/timing/flags maintain karo taaki overlay sirf
    // TABHI VISIBLE ho jab WebView genuinely /watch/ page par ho." Yeh saare
    // fixes zaroori aur sahi the, lekin fundamentally FRAGILE approach hai —
    // koi bhi naya race (WebView renderer restart, activity-result miss,
    // memory-pressure, ek naya future PiP-trigger jo kisi flag ko update
    // karna bhool jaaye) isi symptom ko wapas la sakta hai — jaisa ki is file
    // ke comments mein dikh raha hai, yeh EXACT bug baar-baar alag-alag
    // choreography-race ki wajah se dobara report hua hai.
    //
    // ROOT CAUSE jo ab tak kabhi guard nahi hui thi: overlay ko VISIBLE
    // banane wali dono jagah — `mountInlinePlayer()` (naya mount) aur
    // `showInlineOverlayWithFade()` (PiP-return/resume) — kabhi bhi WebView
    // ka CURRENT path check nahi karti thi. Woh dono sirf apne internal
    // flags/state (`awaitingRectAfterPipReturn`, dedupe-URI check, waghera)
    // par bharosa karte the ki "is waqt hum zaroor /watch/ page par honge."
    // Jis pal bhi yeh assumption kisi bhi wajah se galat nikalti (chahe
    // kaunsi bhi race ho), overlay seedha jahan bhi WebView actually hai
    // (jaise Home) wahin VISIBLE ho jaata — bilkul jaisa report hua.
    //
    // PERMANENT FIX: is fragile "sahi choreography maintain karo" approach
    // ko poori tarah replace mat karo (upar wale saare fixes apni jagah
    // zaroori hain, flags ko wrong hone se bachate hain) — balki ek AAKHRI,
    // ground-truth GATE add karo seedha un DO jagah par jahan visibility
    // asal mein VISIBLE set hoti hai: chahe kuch bhi ho jaaye upar, overlay
    // KABHI VISIBLE nahi hoga jab tak WebView ka current URL genuinely
    // "/watch/" se shuru na ho. Isse yeh poori class of bug — "overlay kisi
    // bhi galat page par dikh jaana" — algorithmically hi impossible ho
    // jaata hai, kisi bhi future/unseen race ki parwah kiye bina. Agar
    // dikhana abhi safe nahi (WebView abhi tak sahi page par nahi pahunchi),
    // to bas skip kar do — already-working retry mechanisms (upar
    // `attemptPipReturnNavigate()` ki 13x retry + `onPageFinished()` ka
    // safety-net) khud WebView ko sahi page par le aayenge, aur us naye
    // "/watch/" mount se aane wala genuine `updateInlinePlayerRect()` call
    // hi tab overlay ko sahi jagah/waqt par dikhayega.
    private fun isCurrentlyOnWatchPage(): Boolean =
        currentWebViewPathOrNull()?.startsWith("/watch/") == true

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_FULLSCREEN_PLAYER && resultCode == RESULT_OK) {
            val pos = data?.getLongExtra("resume_position_ms", 0L) ?: 0L
            val wasPlaying = data?.getBooleanExtra("resume_playing", true) ?: true
            val pipGenuinelyClosed = data?.getBooleanExtra("pip_closed", false) ?: false

            // User ki request: agar yeh genuinely PiP window ke "X"/swipe-away se
            // close hua hai (dekho PlayerActivity.finish()'s "pip_closed" extra),
            // to poora cut kar do — na koi inline player wapas dikhao ya resume
            // karo, na WebView ko us watch page par forward navigate karo. User
            // jahan (peeche navigate ki gayi page par) hai, wahi rahe — bilkul
            // khaali/silent, koi background playback nahi.
            //
            // BUG FIX (asli root cause — "PiP cut ho jaata hai lekin audio
            // background mein chalta rehta hai"): pehle yahan `didNavigateBackForPip
            // && pipGenuinelyClosed` check hota tha — matlab yeh "poora cut karo"
            // wala path SIRF tabhi chalta jab PiP turant-inline se bani ho (jahan
            // WebView bhi peeche navigate hui thi). Sabse aam case mein — video
            // fullscreen se dekha gaya, phir home/swipe se PiP mein gaye, phir "X"
            // se band kiya — didNavigateBackForPip false hi rehta (kyunki wo sirf
            // enterPipImmediately wale flow mein set hota hai), isliye yeh poora
            // block skip ho jaata aur code neeche wale "normal wapas aana" branch
            // mein gir jaata, jo inlinePlayer ko FORCIBLY RESUME (playWhenReady =
            // true) kar deta — result: PiP window gayab ho jaati (cut dikhta hai)
            // lekin audio/video background mein chalta rehta. Ab sirf
            // `pipGenuinelyClosed` par hi bharosa karo — PlayerActivity ab yeh flag
            // origin (inline ho ya fullscreen) ki parwah kiye bina hamesha bhejta
            // hai jab bhi yeh ek definitively genuine PiP close ho (dekho
            // PlayerActivity.handlePipGenuineClose()).
            if (pipGenuinelyClosed) {
                pipSessionActive = false
                pipSessionWatchdogHandler.removeCallbacks(pipSessionWatchdogReset)
                didNavigateBackForPip = false
                pipReturnPath = null
                lastKnownWatchPath = null
                awaitingRectAfterPipReturn = false
                awaitingRectTimeoutRunnable?.let { awaitingRectHandler.removeCallbacks(it) }
                awaitingRectTimeoutRunnable = null
                inlinePlayer?.playWhenReady = false
                inlinePlayer?.pause()
                if (SharedPlayerHolder.player === inlinePlayer) SharedPlayerHolder.clear()
                inlinePlayerView?.player = null
                inlinePlayer?.release()
                inlinePlayer = null
                // ROOT CAUSE FIX (user report: "PiP ka X dabao, video wapas usi
                // jagah aa jaata hai lekin play nahi hoti"): upar `inlinePlayer`
                // ko release/null to kar diya jaata tha, LEKIN `inlineOverlay`
                // (aur `inlineUri`/`lastReactRequestedUri`) kabhi null nahi hote
                // the — sirf visibility GONE hoti thi, view khud zinda rehti thi.
                // Neeche wala `webView.evaluateJavascript(...)` JS side ko
                // `mount()` dobara call karne ke liye trigger karta hai (VideoPlayer.jsx
                // ka `nativeCloseTick` effect) — lekin `mountInlinePlayer()` ke
                // andar sabse pehla check hi hai `if (inlineOverlay != null &&
                // (uri == lastReactRequestedUri || uri == inlineUri))` — chunki
                // yeh SAME episode/URI hi dobara mount ho raha hai aur
                // `inlineOverlay`/`inlineUri` abhi bhi purane (stale) the, yeh
                // check galti se TRUE nikalta — code fresh player banane wale
                // `if (inlineOverlay == null)` block mein kabhi jaata hi nahi
                // tha, sirf purani (ab player-less) overlay ko dobara VISIBLE
                // kar deta — result: video apni jagah par dikhti to thi (overlay
                // visible ho gayi) lekin uske andar koi player attached hi nahi
                // hota (abhi-abhi release/null kiya gaya tha), isliye play tap
                // karne par kuch hota hi nahi.
                // Fix: overlay ko view-hierarchy se poori tarah nikaal do aur
                // sab related state null/clear kar do, taaki agla `mount()` call
                // guaranteed fresh rebuild kare (naya ExoPlayer + naya PlayerView
                // attach) — bilkul jaisa pehli baar video khulne par hota hai.
                inlineOverlay?.let { overlay ->
                    (overlay.parent as? ViewGroup)?.removeView(overlay)
                }
                inlineOverlay = null
                inlinePlayerView = null
                inlineAmbientGlowView = null
                inlineQualityButtonRef = null
                inlineTimeBarRef = null
                inlineBufferingIndicatorRef = null
                inlineProcessingLabelRef = null
                inlineUri = ""
                inlineTitle = ""
                inlineQualitiesJson = "[]"
                lastReactRequestedUri = ""
                // BUG FIX (asli gap jo aapne pakda — "website side to add hi
                // nahi kiya"): ab tak yahan sirf NATIVE side (Kotlin) poora cut
                // kar raha tha — inlinePlayer release, SharedPlayerHolder clear.
                // Lekin website (VideoPlayer.jsx) ko iska koi pata hi nahi chalta
                // tha — usne `window.AndroidPlayer.mount(...)` pehle hi call kar
                // diya tha aur usse lagta hai ki chhota native player abhi bhi
                // wahin mounted/zinda hai. Isliye is div ki jagah khaali/dead reh
                // jaati thi — na play-button kaam karta, na scroll-back-in-view
                // par kuch dikhta, jab tak user manually page reload/re-navigate
                // na kare.
                // Fix: yahan website ko explicitly bata do ki native player cut ho
                // gaya hai, taaki JS side apna state reset kar ke chhote player ko
                // dobara fresh mount kar sake (jaise woh already prev/next button
                // ke liye window.__suhaniOnNativePrev/Next se karta hai). Website
                // side ka matching handler VideoPlayer.jsx mein add kiya gaya hai.
                webView.evaluateJavascript(
                    "if (window.__suhaniOnNativeClosed) window.__suhaniOnNativeClosed();", null
                )
                return
            }

            // Genuine PiP ko "bada" karke (ya normal fullscreen se seedha) wapas
            // aana — hamesha check karo ki WebView abhi bhi usi watch page par
            // hai jahan se PiP shuru hui thi; agar nahi, to seedha wahi par
            // navigate kar do, taaki "clear" page dikhe aur video wahi
            // (fullscreen se laayi gayi) position/playing-state ke saath,
            // YouTube jaisa, resume ho.
            //
            // BUG FIX (user report: "PiP jahan se shuru hui udhar hi expand
            // hona chahiye, chahe popup kahin bhi ho"): pehle yeh poora block
            // `if (didNavigateBackForPip)` ke andar tha — matlab restore SIRF
            // tabhi try hota jab PiP shuru karte waqt genuinely ek goBack()
            // hua ho. Lekin `didNavigateBackForPip` sirf `webView.canGoBack()`
            // true hone par hi set hota hai (dekho openFullscreenFromInline).
            // Jab PiP us watch page se shuru hoti jiske PEECHE koi WebView
            // history hi nahi thi (jaise app khulte hi seedha ek video par —
            // koi Detail/Home page pehle load hi nahi hua tha is session
            // mein), `didNavigateBackForPip` false reh jaata, aur is poore
            // if-block ko hi skip kar diya jaata — chahe `pipReturnPath` set
            // ho. Result: agar user PiP ke dauraan WebView mein kahin bhi
            // ghooma (Home tab, koi doosra title), expand par wahi galat/
            // current page hi khuli reh jaati, kabhi wapas asli watch page par
            // nahi jaata — bilkul jaisa report hua.
            //
            // Fix: `didNavigateBackForPip` par bharosa mat karo — seedha
            // compare karo ki WebView abhi kis path par hai vs `pipReturnPath`
            // kya tha. Agar dono match nahi karte (chahe hum kabhi goBack()
            // kiye the ya nahi), tabhi navigate() karo. Agar already sahi page
            // par hain (user kahin gaya hi nahi tha), koi extra
            // navigate/remount na karo — bewajah reload avoid hota hai.
            // BUG FIX (naya user report — same symptom firse: "PiP karke back
            // button se Home aa gaye, expand karne par Home hi khula reh gaya,
            // wapas us player page par nahi gaya jahan se PiP hui thi"): upar
            // wala fix `webView.url` ko `pipTarget` se COMPARE karke faisla
            // leta tha ki navigate() karna hai ya nahi — lekin PiP floating
            // rehte waqt WebView background mein hi user ke back-press/
            // navigation ko process karta hai, aur `webView.getUrl()` ka
            // internal state kabhi-kabhi is comparison ke exact waqt tak
            // React Router ke asli current path ke saath sync nahi hota
            // (thoda lag/race) — result: `needsPipReturnNavigate` galti se
            // `false` nikal jaata, navigate() poora skip ho jaata, aur user
            // jahan bhi (Home) tha wahin khula reh jaata — bilkul jaisa
            // dubara report hua.
            //
            // Fix: is fragile "kya hum already sahi page par hain" check par
            // bharosa karna hi band kar do — agar `pipTarget` (jahan se PiP
            // shuru hui thi) maujood hai, hamesha seedha wahi navigate() kar
            // do, bina kisi comparison ke. React Router khud already-same-path
            // par navigate() ko safe/idempotent tareeke se handle karta hai
            // (koi extra remount/reload nahi), isliye correctness ke liye yeh
            // trade-off theek hai — "kabhi-kabhi ek harmless extra navigate()"
            // "kabhi-kabhi galat page par phasa reh jaana" se kahin behtar hai.
            val pipTarget = pipReturnPath
            val needsPipReturnNavigate = pipTarget != null
            pipSessionActive = false
            pipSessionWatchdogHandler.removeCallbacks(pipSessionWatchdogReset)
            if (needsPipReturnNavigate) {
                // BUG FIX (user report: "PiP expand karne par watch page ki
                // jagah app ke Home tab par khul jaata hai"): pehle yahan
                // sirf ek EK-SHOT evaluateJavascript() call hoti thi, bina
                // yeh confirm kiye ki `window.__suhaniPipReturnTo` us waqt
                // genuinely maujood/ready hai ya nahi. Real PiP floating
                // rehte waqt MainActivity poora paused rehta hai — kai
                // devices par is lambe background period mein Android
                // WebView ka SEPARATE renderer process (jahan poora React
                // app chalta hai) memory-pressure mein reclaim/restart kar
                // deta hai, jabki yeh Activity khud zinda rehti hai (dekho
                // upar `onRenderProcessGone()` ka comment — bilkul isi tarah
                // ka ek pehle se documented case). Us case mein WebView
                // reload hoke seedha default/Home route par khulta hai, aur
                // React ko is bridge function ko dobara register karne mein
                // thoda waqt lagta hai — agar hamara evaluateJavascript() us
                // chhoti window mein hi fire ho jaaye, `window.__suhaniPipReturnTo`
                // abhi undefined hota hai, poora call silently no-op ho
                // jaata hai, aur user Home page par hi phasa reh jaata hai —
                // bilkul jaisa report hua.
                //
                // Fix: injected JS ab batata hai ki call genuinely hua ya
                // nahi (return true/false), aur agar nahi hua to hum khud
                // thodi der baad (150ms) dobara try karte hain — kai baar
                // tak (2 second tak), jab tak ya to safal ho jaaye ya time-out
                // ho jaaye. `onPageFinished()` mein bhi ek matching retry hai
                // (dekho wahan ka comment) — agar is beech WebView genuinely
                // reload ho jaaye, wahi fresh page-load ke turant baad ek
                // aur koshish karta hai, is Handler-based retry loop ke
                // upar/alawa ek dusra independent safety-net.
                pendingPipReturnPath = pipTarget
                attemptPipReturnNavigate(pipTarget!!, attemptsLeft = 13)
            } else if (didNavigateBackForPip && webView.canGoForward()) {
                // Fallback: agar kisi wajah se watch-page path capture nahi ho
                // paya (bahut purana WebView state, ya url null) lekin humne
                // PiP shuru karte waqt genuinely goBack() kiya tha, purana
                // behavior hi try karo, kuch na hone se behtar.
                webView.goForward()
            }
            didNavigateBackForPip = false
            pipReturnPath = null
            // Overlay ko turant show karna hai ya JS ke agle fresh rect call
            // ka wait karna hai — yeh ab isi baat par tika hai ki humne abhi
            // upar genuinely navigate() kiya ya nahi (na ki purani
            // `didNavigateBackForPip` value par, jo entry-time par set hui
            // thi aur is asli decision ko sahi se reflect nahi karti thi).
            awaitingRectAfterPipReturn = needsPipReturnNavigate

            // Fullscreen mein PlayerActivity isi player instance ko istemal kar raha
            // tha (SharedPlayerHolder ke through) — agar wo abhi bhi zinda hai (decoder
            // switch waghera se replace nahi hua), to usi ko wapas pakad lo, taaki
            // koi naya buffer na bane. Sirf tabhi rebuild karo jab wo genuinely
            // release ho chuka ho (ya system ne background mein kill kar diya ho).
            // Bug fix (premium smoothness / "buffering" feel — asli root cause):
            // pehle yahan har case mein (shared instance reuse ho ya fresh build)
            // seekTo(pos) unconditionally chal jaata tha. "pos" PlayerActivity ke
            // finish() ke waqt capture hui thi — us aur is line ke beech, agar
            // shared player continuously chalta raha (jo ab hum karte hain, upar
            // dekho), current position us "pos" se already AAGE nikal chuki hoti
            // — seekTo(pos) use jabardasti PEECHE, ek stale/purane timestamp par
            // ROLLBACK kar deta tha. Yahi asli "jhatka + buffering" ka kaaran
            // tha: live chal rahi video ko har handoff par thodا peeche seek
            // karke ExoPlayer ko dubara us position ke aas-paas resync/rebuffer
            // karna padta.
            // Fix: jab hum SAME live instance reuse kar rahe hon, koi seek hi
            // mat karo — wo already sahi (ya usse bhi aage, sahi) position par
            // hai. Sirf jab genuinely NAYA player banaya gaya ho (mountInlinePlayer
            // — jiske paas khud ka resume-position logic nahi tha yahan) tabhi
            // seekTo(pos) chahiye.
            if (SharedPlayerHolder.player != null && SharedPlayerHolder.uri == inlineUri) {
                inlinePlayer = SharedPlayerHolder.player
                inlinePlayerView?.player = inlinePlayer
                // Naya Surface abhi-abhi attach hua — decoder ko normal
                // (non-foreground-only) mode mein wapas le aao, ab yeh
                // genuinely visible/foreground surface ke saath chal raha hai.
                inlinePlayer?.setForegroundMode(false)
            } else if (inlineUri.isNotEmpty()) {
                // PERMANENT FIX (user report: "fullscreen se wapas chhote
                // player mein aane par poori screen black ho jaati hai"):
                // pehle yahan bhi (koi currentPath diye baghair) native
                // `webView.url`-based `isCurrentlyOnWatchPage()` fallback
                // chalta tha. Yeh call sirf tabhi is "else" (SharedPlayerHolder
                // reuse nahi hua) branch mein aati hai jab `awaitingRectAfterPipReturn`
                // abhi tak `false` hai — matlab yeh ek AISA return hai jisme
                // koi cross-page navigate hi nahi chahiye tha (plain, non-PiP
                // fullscreen->back, ya PiP jiska pipTarget capture hi nahi ho
                // paya). In dono cases mein, structurally/by-construction,
                // MainActivity ki WebView is poore fullscreen-session ke
                // dauraan kabhi bhi apni jagah se hili hi nahi thi — koi
                // ambiguity hai hi nahi jise resolve karne ke liye kisi bhi
                // (racy ho sakne wale) `webView.url` read ki zaroorat pade.
                // Fix: yahan `trustedNoCheck = true` — ground-truth check
                // poori tarah skip karo, hamesha dikhao.
                mountInlinePlayer(inlineUri, inlineTitle, inlineQualitiesJson, trustedNoCheck = !awaitingRectAfterPipReturn)
                inlinePlayer?.seekTo(pos)
            }
            inlinePlayer?.playWhenReady = wasPlaying
            // Bug fix / polish: pehle overlay seedha VISIBLE ho jaata tha — ek chhota
            // fade-in (jaisa swipe-down-to-PiP wapas reset hone par pehle se hai)
            // smoother/premium feel deta hai, aur reattach ke turant baad wale ek-do
            // frame ke "settle" hone ko bhi chhupa deta hai.
            // Premium polish: ab baaki dono jagah (mountInlinePlayer/unmountInlinePlayer)
            // jaisa hi halka scale bhi hai — teeno jagah ka overlay show/hide motion
            // ab consistent hai, poore app mein ek hi "material" feel.
            // BUG FIX (dekho `awaitingRectAfterPipReturn` field ka comment): agar
            // yeh return ek real-PiP-navigate-away session ke baad hai, overlay ko
            // yahan turant mat dikhao — WebView abhi tak sahi (watch) page par
            // wapas pahunchi hi nahi hai (async navigate() abhi chal raha hai).
            // updateInlinePlayerRect() (JS ke fresh mount ke baad) hi ab ise sahi
            // rect ke saath dikhayega. Ek safety-net timeout bhi laga do — agar
            // kisi wajah se woh call kabhi na aaye, video hamesha ke liye chhupa
            // na reh jaaye.
            // BUG FIX (dekho `attemptPipReturnNavigate()` ka comment — "PiP
            // expand karne par Home khul jaata hai"): yeh timeout pehle 1500ms
            // tha, jabki naya retry-with-confirmation mechanism khud hi poore
            // ~2000ms (13 attempts * 150ms) tak try karta rehta hai jab tak
            // WebView/React reload ke baad ready na ho. Agar yeh timeout usse
            // PEHLE hi fire ho jaata, overlay Home page ke oopar hi (rect update
            // aane se pehle) dikha diya jaata — bilkul wahi symptom jo report
            // hua. Fix: is timeout ko retry-window se aaraam se zyada (2600ms)
            // kar diya, taaki genuine retry ko poora waqt mile safal hone ka.
            awaitingRectTimeoutRunnable?.let { awaitingRectHandler.removeCallbacks(it) }
            if (awaitingRectAfterPipReturn) {
                val timeoutRunnable = Runnable {
                    if (awaitingRectAfterPipReturn) {
                        awaitingRectAfterPipReturn = false
                        showInlineOverlayWithFade()
                    }
                }
                awaitingRectTimeoutRunnable = timeoutRunnable
                awaitingRectHandler.postDelayed(timeoutRunnable, 2600L)
            } else {
                // PERMANENT FIX (dekho upar `mountInlinePlayer(...trustedNoCheck)`
                // ka comment — same reasoning): yahan pahunchna hi iska matlab
                // hai ki `awaitingRectAfterPipReturn` false hai, i.e. koi
                // cross-page navigate zaroori nahi tha — structurally-safe
                // case, ground-truth check ki zaroorat nahi.
                showInlineOverlayWithFade(trustedNoCheck = true)
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        // CRASH FIX: yeh explicit webView.saveState(outState) call yahan se
        // hataya gaya hai — dekho onCreate() mein webView.isSaveEnabled = false
        // ke paas laga bada comment (`TransactionTooLargeException` root cause).
        // Yeh call poori Chromium navigation history ko ek separate
        // "WEBVIEW_CHROMIUM_STATE" blob ke roop mein Bundle mein daal deta tha
        // (bugreport mein crash ke waqt yeh akela ~2.95MB tha, jabki poori
        // Binder transaction limit hi ~1MB hai) — aur is app mein iska koi
        // istemal bhi nahi tha, kyunki webView.restoreState() kahin bhi call
        // nahi hota (upar wale ROOT CAUSE FIX comment mein dekho, jaan-bujh kar
        // hataya gaya tha).
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            // BUG FIX (user report: "PiP hote huye back dabane se Home aa
            // jaata hai, expand par Home hi khulta hai — comment/like/title
            // wale player page ka catch rakho"): jab tak real PiP session
            // live hai (dekho `pipSessionActive`), is watch page ko WebView
            // mein bilkul mat badlo — back-press ko yahin absorb kar lo aur
            // app ko background mein bhej do (jaisa Home button dabane par
            // hota, PiP window waisi hi floating rehti hai). Isse jo page
            // (comments/likes/title/Up-Next ke saath) abhi loaded hai wahi
            // "catch" rehta hai — expand hamesha usi par hoga, koi navigate-
            // wapas-lao jugaad ki zaroorat hi nahi.
            if (pipSessionActive) {
                moveTaskToBack(true)
                return true
            }
            if (webView.canGoBack()) {
                webView.goBack()
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    // Bug fix: is Activity mein pehle koi onPause/onResume hi nahi tha. Matlab
    // agar chhota inline player play ho raha ho aur user Home dabaye ya app
    // switcher se kahin aur chala jaaye, video/audio background mein chalta
    // rehta tha (battery/data drain) — aur WebView bhi (JS timers, video decode
    // waghera) foreground jaisi hi speed se chalta rehta tha. Standard fix:
    // onPause par dono ko pause karo, onResume par WebView wapas resume karo.
    override fun onPause() {
        super.onPause()
        webView.onPause()
        saveInlineWatchProgress()
        inlinePlayer?.pause()
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
    }

    // Manifest mein MainActivity ke liye android:supportsPictureInPicture="true"
    // pehle se tha, lekin koi bhi PiP-trigger karne wala code nahi tha — flag
    // effectively dead thi. Ab agar chhota inline player abhi play ho raha ho
    // (genuinely video dekhte waqt) aur user Home button dabaye / app switch
    // kare, to already-built fullscreen+PiP path (jo PiP button/swipe-down se
    // already kaam karta hai) reuse karke seedha real PiP mein chala jaate hain
    // — ExoPlayer instance wahi rehta hai (SharedPlayerHolder ke through), koi
    // naya buffer nahi banta.
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        val overlayVisible = inlineOverlay?.visibility == View.VISIBLE
        val isPlaying = inlinePlayer?.isPlaying == true
        if (overlayVisible && isPlaying) {
            openFullscreenFromInline(enterPipImmediately = true)
        }
    }

    override fun onDestroy() {
        // Sirf apna hi listener registration hataao — agar kisi wajah se ek
        // naya MainActivity instance already dobara registered ho chuka hai
        // (jaise fast recreate), uska registration na chheeno.
        if (DownloadService.listener === this) DownloadService.listener = null
        pipSessionWatchdogHandler.removeCallbacks(pipSessionWatchdogReset)
        loadingLabelPulse?.cancel()
        saveInlineWatchProgress()
        if (SharedPlayerHolder.player === inlinePlayer) SharedPlayerHolder.clear()
        inlinePlayer?.release()
        inlinePlayer = null
        webView.destroy()
        super.onDestroy()
    }
}
