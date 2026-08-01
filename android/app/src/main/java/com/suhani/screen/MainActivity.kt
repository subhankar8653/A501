package com.suhani.screen

import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
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
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.suhani.videoplayer.EqualizerAudioProcessor
import com.suhani.videoplayer.FfmpegRenderersFactory
import com.suhani.videoplayer.PlayerActivity
import com.suhani.videoplayer.SharedPlayerHolder
import com.google.android.material.bottomsheet.BottomSheetDialog
import org.json.JSONArray

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

    private val SITE_URL = "https://a501.vercel.app/"
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
    private var hasLoadedOnce = false

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
    private var inlineLocked = false
    private var subtitleManuallyDisabled = false
    private var audioManuallyDisabled = false
    private var resizeModeIndex = 0
    private var decoderMode = 1 // 0 = HW, 1 = HW+, 2 = SW — same default as fullscreen
    private var inlineLongPressSpeedActive = false
    private var inlineSpeedBeforeLongPress = 1f

    // Swipe-down-to-PiP on the inline player itself — video ko hold karke
    // neeche push karne se bhi chevron jaisa hi minimize hota hai.
    private var inlineSwipeStartX = 0f
    private var inlineSwipeStartY = 0f
    private var inlineSwipeDragging = false
    private val INLINE_DRAG_PIP_THRESHOLD = 90f

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
    private val inlineEqProcessor = EqualizerAudioProcessor()
    private var inlineUri: String = ""
    private var inlineTitle: String = ""
    private var inlineQualitiesJson: String = "[]"
    private var inlineHasNextEpisode = false
    private var inlineHasPrevEpisode = false
    private var inlinePrevButtonRef: ImageButton? = null
    private var inlineNextButtonRef: ImageButton? = null
    private var inlinePlayPauseButtonRef: ImageButton? = null
    private var inlineQualityButtonRef: TextView? = null

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.webview)
        swipeRefresh = findViewById(R.id.swipe_refresh)
        initialLoadingView = findViewById(R.id.initialLoadingView)
        loadErrorView = findViewById(R.id.loadErrorView)
        findViewById<View>(R.id.loadErrorRetryButton).setOnClickListener {
            loadErrorView.visibility = View.GONE
            initialLoadingView.visibility = View.VISIBLE
            webView.reload()
        }

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

        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.databaseEnabled = true
        webView.settings.mediaPlaybackRequiresUserGesture = false
        webView.settings.allowFileAccess = false
        webView.settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW

        webView.webViewClient = object : WebViewClient() {
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

            override fun onReceivedError(view: WebView, errorCode: Int, description: String?, failingUrl: String?) {
                super.onReceivedError(view, errorCode, description, failingUrl)
                swipeRefresh.isRefreshing = false
                // Pull-to-refresh se aaya error chhota/silent rehne dete hain (user
                // already jaanta hai wo kya kar raha hai) — sirf pehli/cold load
                // fail hone par poori-screen branded error+retry dikhate hain.
                if (!hasLoadedOnce) {
                    initialLoadingView.visibility = View.GONE
                    loadErrorView.visibility = View.VISIBLE
                }
            }
        }
        webView.webChromeClient = WebChromeClient()

        // Native player bridge — frontend isse pehchan kar HTML5 <video> ki jagah
        // seedha native Sisisisi (chhota inline, fullscreen-expandable) player khol sakta hai.
        webView.addJavascriptInterface(WebAppInterface(this), "AndroidPlayer")

        swipeRefresh.setOnRefreshListener {
            webView.reload()
            // Safety net: if the page load somehow never fires onPageFinished
            // (slow network, hung request), don't leave the spinner stuck forever.
            swipeRefresh.postDelayed({ swipeRefresh.isRefreshing = false }, 8000)
        }
        webView.setOnScrollChangeListener { _, scrollY, _, _, _ ->
            swipeRefresh.isEnabled = scrollY == 0
        }

        if (savedInstanceState != null) {
            webView.restoreState(savedInstanceState)
        } else {
            webView.loadUrl(SITE_URL)
        }
    }

    private fun fadeOutLoadingView() {
        if (initialLoadingView.visibility != View.VISIBLE) return
        initialLoadingView.animate()
            .alpha(0f)
            .setDuration(220)
            .withEndAction {
                initialLoadingView.visibility = View.GONE
                initialLoadingView.alpha = 1f
            }
            .start()
    }

    /** Chhota inline player (overlay) create/reuse karke naya video load karta hai,
     *  aur uske saare (scaled-down) controls wire karta hai. */
    fun mountInlinePlayer(uri: String, title: String, qualitiesJson: String) {
        inlineUri = uri
        inlineTitle = title
        inlineQualitiesJson = qualitiesJson

        if (inlineOverlay == null) {
            val root = LayoutInflater.from(this).inflate(R.layout.inline_player_view, null) as FrameLayout
            val playerView = root.findViewById<PlayerView>(R.id.inlinePlayerView)
            val speedButton = root.findViewById<TextView>(R.id.inlineSpeedButton)
            val subtitleButton = root.findViewById<ImageView>(R.id.inlineSubtitleButton)
            val audioTrackButton = root.findViewById<ImageView>(R.id.inlineAudioTrackButton)
            val decoderButton = root.findViewById<TextView>(R.id.inlineDecoderButton)
            val settingsButton = root.findViewById<ImageView>(R.id.inlineSettingsButton)
            val pipChevron = root.findViewById<ImageView>(R.id.inlinePipChevron)
            val lockButton = playerView.findViewById<ImageView>(R.id.inlineLockButton)
            val pipButton = playerView.findViewById<ImageView>(R.id.inlinePipButton)
            val aspectButton = playerView.findViewById<ImageView>(R.id.inlineAspectButton)
            val fullscreenButton = playerView.findViewById<ImageView>(R.id.inlineFullscreenButton)
            val qualityButton = playerView.findViewById<TextView>(R.id.inlineQualityButton)
            val topBarRoot = root.findViewById<View>(R.id.inlineTopBarRoot)
            val unlockButton = root.findViewById<ImageView>(R.id.inlineUnlockButton)
            inlineQualityButtonRef = qualityButton

            // Back-arrow aur title text hata diye gaye hain (page ka apna back
            // navigation already hai, redundant tha) — isliye ab yahan backButton
            // ka koi reference nahi hai.

            // YouTube jaisa asli bottom-sheet (dekho showInlineSettingsSheet()) —
            // audio/subtitle ab apne dedicated icon se seedhe kaam karte hain,
            // isliye settings mein nahi hain.
            settingsButton.setOnClickListener {
                showInlineSettingsSheet(speedButton, decoderButton, pipButton, aspectButton, lockButton)
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

            lockButton.setOnClickListener {
                inlineLocked = true
                playerView.useController = false
                playerView.hideController()
                topBarRoot.visibility = View.GONE
                // Bug fix: sirf useController=false karne se yeh (khud controller
                // ke andar wala) lock icon reliably hide nahi ho raha tha — ab
                // explicitly GONE karte hain, bade wale (fullscreen) player jaisa.
                lockButton.visibility = View.GONE
                unlockButton.visibility = View.VISIBLE
            }
            unlockButton.setOnClickListener {
                inlineLocked = false
                playerView.useController = true
                lockButton.visibility = View.VISIBLE
                topBarRoot.visibility = View.VISIBLE
                unlockButton.visibility = View.GONE
            }

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

            // Bottom controls (center row + progress bar) Media3 khud show/hide karta
            // hai; ab top bar (back/title/CC/gear) ko bhi usi ke saath sync kar dete
            // hain — pehle yeh alag/independent rehta tha, isliye control-bar hide
            // hone par bhi upar wali line screen par chipki rehti thi.
            playerView.setControllerVisibilityListener(
                PlayerView.ControllerVisibilityListener { visibility ->
                    if (!inlineLocked) topBarRoot.visibility = visibility
                }
            )

            // --- Gestures: double-tap ±10s seek, hold-anywhere-for-2x-speed ---
            // Fullscreen ke gestureDetector jaisa hi — bas chhote area ke hisaab
            // se feedback (inlineSeekFeedback pill / inlineSpeedBadge).
            val seekFeedback = root.findViewById<TextView>(R.id.inlineSeekFeedback)
            val speedBadge = root.findViewById<TextView>(R.id.inlineSpeedBadge)
            val hideInlineSeekFeedback = Runnable { seekFeedback.visibility = View.GONE }

            val gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
                override fun onDoubleTap(e: MotionEvent): Boolean {
                    if (inlineLocked) return true
                    val forward = e.x >= playerView.width / 2
                    val p = inlinePlayer ?: return true
                    val max = p.duration.takeIf { it > 0 } ?: Long.MAX_VALUE
                    val target = if (forward) (p.currentPosition + 10_000).coerceAtMost(max)
                                 else (p.currentPosition - 10_000).coerceAtLeast(0)
                    p.seekTo(target)
                    seekFeedback.text = if (forward) "⏩ 10s" else "⏪ 10s"
                    seekFeedback.removeCallbacks(hideInlineSeekFeedback)
                    seekFeedback.visibility = View.VISIBLE
                    seekFeedback.postDelayed(hideInlineSeekFeedback, 600)
                    return true
                }

                override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                    if (inlineLocked) return true
                    if (playerView.isControllerFullyVisible) playerView.hideController()
                    else playerView.showController()
                    return true
                }

                override fun onLongPress(e: MotionEvent) {
                    // Lock mein bhi hold-to-2x allow karte hain, fullscreen jaisa hi.
                    val p = inlinePlayer ?: return
                    inlineLongPressSpeedActive = true
                    inlineSpeedBeforeLongPress = try { p.playbackParameters.speed } catch (_: Exception) { 1f }
                    p.playbackParameters = PlaybackParameters(2f)
                    speedBadge.visibility = View.VISIBLE
                }
            })

            playerView.setOnTouchListener { _, event ->
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
        }
        player.setMediaItem(MediaItem.fromUri(Uri.parse(uri)))
        player.prepare()
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

    private fun showInlineQualityDialog(qualityButton: TextView) {
        val qualities = parseInlineQualities()
        if (qualities.isEmpty()) return
        val labels = qualities.map { it.first }.toTypedArray()
        val currentIndex = qualities.indexOfFirst { it.second == inlineUri }.coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle("Quality")
            .setSingleChoiceItems(labels, currentIndex) { dialog, which ->
                switchInlineQuality(qualities[which].second)
                qualityButton.text = qualities[which].first
                dialog.dismiss()
            }
            .show()
    }

    /** Quality (resolution) badalte waqt ExoPlayer instance wahi rehta hai — sirf
     *  MediaItem badalta hai, aur purani position/playing-state wapas apply hoti
     *  hai, taaki switch karne par video shuru se na chale. */
    private fun switchInlineQuality(newUrl: String) {
        val player = inlinePlayer ?: return
        val pos = player.currentPosition
        val wasPlaying = player.playWhenReady
        inlineUri = newUrl
        SharedPlayerHolder.uri = newUrl
        player.setMediaItem(MediaItem.fromUri(Uri.parse(newUrl)))
        player.prepare()
        player.seekTo(pos)
        player.playWhenReady = wasPlaying
    }

    /** Reference (YouTube) jaisa settings bottom-sheet — gear tap karne par khulta
     *  hai. Audio track/subtitle ab apne dedicated icon se seedhe kaam karte hain
     *  isliye yahan nahi hain; quality bhi apna alag button hai (fullscreen ke
     *  paas), isliye yahan bhi nahi hai. */
    private fun showInlineSettingsSheet(
        speedButton: TextView,
        decoderButton: TextView,
        pipButton: ImageView,
        aspectButton: ImageView,
        lockButton: ImageView
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
        addRow(R.drawable.ic_lock, "Lock screen", "") {
            lockButton.performClick()
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
        return ExoPlayer.Builder(this, renderersFactory)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                /* handleAudioFocus= */ true
            )
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
        old.release()
        if (SharedPlayerHolder.player === old) SharedPlayerHolder.clear()

        val fresh = buildInlineExoPlayer()
        fresh.trackSelectionParameters = previousParams
        fresh.addListener(inlineTracksListener)
        fresh.addListener(inlinePlayPauseListener)
        inlinePlayer = fresh
        inlinePlayerView?.player = fresh
        SharedPlayerHolder.player = fresh
        SharedPlayerHolder.uri = inlineUri
        fresh.setMediaItem(MediaItem.fromUri(Uri.parse(inlineUri)))
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

        var selectedIndex = if (subtitleManuallyDisabled) labels.size - 1
            else trackRefs.indexOfFirst { (group, i) -> group.isTrackSelected(i) }.let { if (it < 0) 0 else it }

        AlertDialog.Builder(this)
            .setTitle("Subtitles")
            .setSingleChoiceItems(labels.toTypedArray(), selectedIndex) { dialog, which ->
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
                dialog.dismiss()
            }
            .show()
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

        AlertDialog.Builder(this)
            .setTitle("Audio track")
            .setSingleChoiceItems(labels.toTypedArray(), selectedIndex) { dialog, which ->
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
                dialog.dismiss()
            }
            .show()
    }

    /** Chhote player ke fullscreen/more/PiP button se poora native PlayerActivity khulta hai,
     *  wahi position/playing-state ke saath; wapas aane par onActivityResult se sync hota hai.
     *  enterPipImmediately=true ho to PlayerActivity khulte hi turant real PiP mein chala jaata hai
     *  (isi Activity ka apna already-working PiP implementation use karke). */
    private fun openFullscreenFromInline(enterPipImmediately: Boolean = false) {
        val pos = inlinePlayer?.currentPosition ?: 0L
        val wasPlaying = inlinePlayer?.playWhenReady ?: true
        inlinePlayer?.pause()
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
        val intent = Intent(this, PlayerActivity::class.java).apply {
            putExtra("video_uri", inlineUri)
            putExtra("video_title", inlineTitle)
            putExtra("video_qualities_json", inlineQualitiesJson)
            putExtra("resume_position_ms", pos)
            putExtra("resume_playing", wasPlaying)
            putExtra("enter_pip_immediately", enterPipImmediately)
        }
        @Suppress("DEPRECATION")
        startActivityForResult(intent, REQUEST_FULLSCREEN_PLAYER)
        if (enterPipImmediately) {
            // Bug fix (jhatke wala/gadbad transformation): normal fullscreen-open ke
            // liye OS ka default open-animation theek lagta hai, lekin jab hum turant
            // (isi frame ke baad) PiP mein bhi chale jaate hain, to "activity grow
            // animation" + "PiP shrink animation" dono ek saath overlap ho kar ek
            // jhatkedaar/double transform dikhte the. Is specific case (PiP button /
            // swipe-down-to-PiP se aaya ho) mein open-animation hata do — activity
            // turant (bina apne animation ke) render hoti hai, aur sirf ek hi smooth
            // shrink-to-PiP animation dikhti hai (PlayerActivity khud enter karta hai,
            // sourceRectHint ke saath — dekho buildPipParams()).
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_FULLSCREEN_PLAYER && resultCode == RESULT_OK) {
            val pos = data?.getLongExtra("resume_position_ms", 0L) ?: 0L
            val wasPlaying = data?.getBooleanExtra("resume_playing", true) ?: true
            // Fullscreen mein PlayerActivity isi player instance ko istemal kar raha
            // tha (SharedPlayerHolder ke through) — agar wo abhi bhi zinda hai (decoder
            // switch waghera se replace nahi hua), to usi ko wapas pakad lo, taaki
            // koi naya buffer na bane. Sirf tabhi rebuild karo jab wo genuinely
            // release ho chuka ho (ya system ne background mein kill kar diya ho).
            if (SharedPlayerHolder.player != null && SharedPlayerHolder.uri == inlineUri) {
                inlinePlayer = SharedPlayerHolder.player
                inlinePlayerView?.player = inlinePlayer
            } else if (inlineUri.isNotEmpty()) {
                mountInlinePlayer(inlineUri, inlineTitle, inlineQualitiesJson)
            }
            inlinePlayer?.seekTo(pos)
            inlinePlayer?.playWhenReady = wasPlaying
            // Bug fix / polish: pehle overlay seedha VISIBLE ho jaata tha — ek chhota
            // fade-in (jaisa swipe-down-to-PiP wapas reset hone par pehle se hai)
            // smoother/premium feel deta hai, aur reattach ke turant baad wale ek-do
            // frame ke "settle" hone ko bhi chhupa deta hai.
            inlineOverlay?.let { overlay ->
                overlay.animate().cancel()
                overlay.alpha = 0f
                overlay.visibility = View.VISIBLE
                overlay.animate().alpha(1f).setDuration(180).start()
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
        if (overlayVisible && isPlaying && !inlineLocked) {
            openFullscreenFromInline(enterPipImmediately = true)
        }
    }

    override fun onDestroy() {
        if (SharedPlayerHolder.player === inlinePlayer) SharedPlayerHolder.clear()
        inlinePlayer?.release()
        inlinePlayer = null
        webView.destroy()
        super.onDestroy()
    }
}
