package com.suhani.screen

import android.content.Intent
import android.graphics.Rect
import android.graphics.Typeface
import android.net.Uri
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
import androidx.media3.ui.PlayerView
import com.suhani.videoplayer.PlayerNetwork
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
    @JavascriptInterface
    fun mount(uri: String, title: String, qualitiesJson: String) {
        activity.runOnUiThread { activity.mountInlinePlayer(uri, title, qualitiesJson) }
    }

    @JavascriptInterface
    fun updateRect(left: Double, top: Double, width: Double, height: Double) {
        activity.runOnUiThread { activity.updateInlinePlayerRect(left, top, width, height) }
    }

    @JavascriptInterface
    fun unmount() {
        activity.runOnUiThread { activity.unmountInlinePlayer() }
    }

    // Web page (Player.jsx) yeh call karta hai jab bhi pata chale ki agla/pichla
    // episode maujood hai ya nahi (series ki Up Next list se) — isse chhote
    // player ke prev/next button ka enable/dim state sahi rehta hai. Native
    // khud episode URL nahi jaanta — bas JS ko wapas "next/prev dabaya" bata
    // deta hai, aur JS apni normal navigate() se episode badal deta hai
    // (isliye title/Up-Next-list/comments waghera bhi sync rehte hain).
    @JavascriptInterface
    fun setAdjacentEpisodes(hasNext: Boolean, hasPrev: Boolean) {
        activity.runOnUiThread { activity.updateInlineAdjacentEpisodes(hasNext, hasPrev) }
    }

}

class MainActivity : AppCompatActivity() {

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
    private var subtitleManuallyDisabled = false
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
    private var inlineHasNextEpisode = false
    private var inlineHasPrevEpisode = false
    private var inlinePrevButtonRef: ImageButton? = null
    private var inlineNextButtonRef: ImageButton? = null
    private var inlinePlayPauseButtonRef: ImageButton? = null
    private var inlineQualityButtonRef: TextView? = null
    private var inlineBufferingIndicatorRef: View? = null
    private var inlineIsBuffering = false

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
        }
    }

    private fun setInlineBuffering(buffering: Boolean) {
        if (inlineIsBuffering == buffering) return
        inlineIsBuffering = buffering
        val spinner = inlineBufferingIndicatorRef ?: return
        val playPause = inlinePlayPauseButtonRef
        if (buffering) {
            spinner.visibility = View.VISIBLE
            spinner.animate().cancel()
            spinner.animate()
                .alpha(1f).scaleX(1f).scaleY(1f)
                .setDuration(180)
                .setInterpolator(android.view.animation.DecelerateInterpolator())
                .start()
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
    // banana overkill hai — us robust handling ke liye already fullscreen hai —
    // isliye yahan minimum zaroori cheez: user ko batao, aur poore-screen mein
    // (jahan asli recovery/fallback hai) khud khulne ka option do.
    private val inlineErrorListener = object : Player.Listener {
        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
            android.widget.Toast.makeText(
                this@MainActivity,
                "Video play nahi ho paya — fullscreen mein try karein",
                android.widget.Toast.LENGTH_SHORT
            ).show()
        }
    }

    // Named (not anonymous) taaki decoder-switch rebuild ke time isi listener ko
    // purane player se hata kar naye player par dobara laga sakein.
    private val inlineTracksListener = object : Player.Listener {
        override fun onTracksChanged(tracks: Tracks) {
            if (subtitleManuallyDisabled) return
            val alreadySelected = tracks.groups.any { g -> g.type == C.TRACK_TYPE_TEXT && g.isSelected }
            if (alreadySelected) return
            val firstTextGroup = tracks.groups.firstOrNull { g -> g.type == C.TRACK_TYPE_TEXT } ?: return
            val p = inlinePlayer ?: return
            p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                .setOverrideForType(TrackSelectionOverride(firstTextGroup.mediaTrackGroup, 0))
                .build()
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
        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.webview)
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
        }
        webView.webChromeClient = WebChromeClient()

        // Native player bridge — frontend isse pehchan kar HTML5 <video> ki jagah
        // seedha native Sisisisi (chhota inline, fullscreen-expandable) player khol sakta hai.
        webView.addJavascriptInterface(WebAppInterface(this), "AndroidPlayer")

        // BUG FIX (user report): pull-to-refresh (neeche-swipe-se-reload) hi
        // "upar scroll nahi ho raha" issue ki asli wajah nikla — SwipeRefreshLayout
        // page ke top par touch ko intercept kar leta tha, chahe kitna bhi fine-tune
        // karo (scrollY check, JS bridge, debounce — kuch bhi permanently fix nahi
        // kar paaya kyunki root cause hi yeh feature tha). Isliye pull-to-refresh
        // ko poori tarah disable kar diya — ab SwipeRefreshLayout sirf ek plain
        // container hai, kabhi bhi touch intercept nahi karega, aur WebView ka
        // apna scroll (upar ho ya neeche) hamesha bina rukawat ke chalega.
        swipeRefresh.isEnabled = false

        if (savedInstanceState != null) {
            webView.restoreState(savedInstanceState)
        } else {
            // BUG FIX (asli root cause of the offline "black screen", user report
            // + confirmed pattern seen with WebViewAssetLoader-based apps): webView
            // .loadUrl(APP_URL) is a real top-level NAVIGATION. Chromium (jo WebView
            // ko power karta hai) kabhi-kabhi is level par ek OS network-state check
            // karta hai UNSE PEHLE hi ki request kabhi shouldInterceptRequest tak
            // pahunche — matlab agar device par bilkul network na ho (Airplane
            // mode / SIM+WiFi dono off), Chromium poori navigation hi seedha
            // net::ERR_INTERNET_DISCONNECTED de kar reject kar sakta hai, chahe
            // target URL 100% locally-intercepted (appassets.androidplatform.net)
            // hi kyun na ho. Isi se onReceivedError() -> showLoadError() trigger
            // hota tha, jiska dark (#0B0B0F) near-black background + chhota
            // warning-icon/text hi effectively user ko "black screen" jaisa
            // dikhta tha — HAR baar jab device genuinely offline hota, na ki sirf
            // kabhi-kabhi.
            //
            // Fix: loadUrl() (top-level navigation) ki jagah index.html ka content
            // seedha loadDataWithBaseURL() se WebView ko "inject" karo. Yeh ek
            // navigation nahi hai (koi network request hi nahi banta is pehle
            // load ke liye), isliye upar wala connectivity pre-check bilkul bypass
            // ho jaata hai — chahe device abhi Airplane mode mein hi kyun na ho.
            // baseURL wahi virtual origin (https://appassets.androidplatform.net/)
            // rakha hai, isliye HTML ke andar ke saare relative paths (/assets/*.js,
            // /assets/*.css, /favicon.png, waghera) bilkul pehle jaisa hi assetLoader
            // ke through resolve/serve hote hain — yeh SUB-resource requests hain,
            // top-level navigation nahi, isliye inke liye woh connectivity
            // pre-check lagta hi nahi (yeh already reliably offline kaam karta tha,
            // isko chheda nahi gaya).
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
    fun mountInlinePlayer(uri: String, title: String, qualitiesJson: String) {
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
            return
        }
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
            val bufferingIndicator = root.findViewById<View>(R.id.inlineBufferingIndicator)
            inlineQualityButtonRef = qualityButton
            inlineBufferingIndicatorRef = bufferingIndicator

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
            // Fullscreen player jaisa hi behavior: koi bhi text/subtitle track available
            // ho aur user ne khud-se OFF na kiya ho, to pehla milte hi khud-ba-khud select
            // kar do — warna subtitle button "on" dikhta lekin kuch bhi nahi dikhta.
            it.addListener(inlineTracksListener)
            it.addListener(inlinePlayPauseListener)
            it.addListener(inlineErrorListener)
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

    /** JS se aayi CSS px (viewport-relative) rect ko real device px mein convert karke
     *  chhote player ko us video-container ki exact jagah par rakhta/resize karta hai. */
    fun updateInlinePlayerRect(left: Double, top: Double, width: Double, height: Double) {
        val overlay = inlineOverlay ?: return
        val density = resources.displayMetrics.density
        val params = (overlay.layoutParams as? FrameLayout.LayoutParams)
            ?: FrameLayout.LayoutParams(0, 0)
        params.width = (width * density).toInt()
        params.height = (height * density).toInt()
        params.leftMargin = (left * density).toInt()
        params.topMargin = statusBarInsetPx + (top * density).toInt()
        overlay.layoutParams = params
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
            return
        }
        inlineSeekSessionHandler.removeCallbacks(inlineSeekSessionResetRunnable)
        saveInlineWatchProgress()
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

        val selectedIndex = if (subtitleManuallyDisabled) labels.size - 1
            else trackRefs.indexOfFirst { (group, i) -> group.isTrackSelected(i) }.let { if (it < 0) 0 else it }

        showInlineChoiceSheet("Subtitles", labels, selectedIndex) { which ->
            if (which == labels.size - 1) {
                subtitleManuallyDisabled = true
                player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                    .build()
                subtitleButton.alpha = 0.5f
            } else {
                subtitleManuallyDisabled = false
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
        didNavigateBackForPip = enterPipImmediately && webView.canGoBack()
        if (didNavigateBackForPip) {
            webView.goBack()
        }
        val intent = Intent(this, PlayerActivity::class.java).apply {
            putExtra("video_uri", inlineUri)
            putExtra("video_title", inlineTitle)
            putExtra("video_qualities_json", inlineQualitiesJson)
            putExtra("resume_position_ms", pos)
            putExtra("resume_playing", wasPlaying)
            putExtra("enter_pip_immediately", enterPipImmediately)
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
                didNavigateBackForPip = false
                inlinePlayer?.playWhenReady = false
                inlinePlayer?.pause()
                if (SharedPlayerHolder.player === inlinePlayer) SharedPlayerHolder.clear()
                inlinePlayerView?.player = null
                inlinePlayer?.release()
                inlinePlayer = null
                inlineOverlay?.visibility = View.GONE
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
            // aana — agar PiP shuru karte waqt WebView ko peeche navigate kiya
            // gaya tha, to ab usi watch page par FORWARD wapas le jao, taaki
            // "clear" page dikhe aur video wahi (fullscreen se laayi gayi)
            // position/playing-state ke saath, YouTube jaisa, resume ho.
            if (didNavigateBackForPip) {
                didNavigateBackForPip = false
                if (webView.canGoForward()) webView.goForward()
            }

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
                mountInlinePlayer(inlineUri, inlineTitle, inlineQualitiesJson)
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
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK && webView.canGoBack()) {
            webView.goBack()
            return true
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
        loadingLabelPulse?.cancel()
        saveInlineWatchProgress()
        if (SharedPlayerHolder.player === inlinePlayer) SharedPlayerHolder.clear()
        inlinePlayer?.release()
        inlinePlayer = null
        webView.destroy()
        super.onDestroy()
    }
}
