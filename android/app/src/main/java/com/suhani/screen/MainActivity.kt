package com.suhani.screen

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebChromeClient
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.suhani.videoplayer.PlayerActivity

/**
 * Web frontend (VideoPlayer.jsx) isi bridge se video ko YouTube-jaisa chhote
 * (inline) native player mein play karta hai, HTML5 <video> tag ke bajaye:
 *
 *   window.AndroidPlayer.mount(uri, title, qualitiesJson)
 *     -> is jagah (jiska rect updateRect se milta hai) ek chhota native
 *        player start ho jaata hai, autoplay ke saath, apne poore controls
 *        (play/pause, seek, lock, subtitle toggle, speed, PiP, aspect-ratio,
 *        fullscreen) ke saath — bas icon size chhote (is area ke hisaab se).
 *   window.AndroidPlayer.updateRect(left, top, width, height)  // CSS px
 *     -> jab bhi video-container ka size/position badle (scroll/resize),
 *        chhote player ko wahi exact jagah par move/resize karo.
 *   window.AndroidPlayer.unmount()
 *     -> naya video / page chhodne par chhota player hata do.
 *
 * Chhote player ke "more" (3-dot) button aur fullscreen-expand button dono
 * poore-screen wale PlayerActivity ko khol dete hain — audio-track/decoder-
 * switch/equalizer/cast/A-B-repeat/chapters jaisi advanced cheezein wahi
 * already banी hui hain, dobara chhote screen par nahi banayi — wahi
 * position/playing-state ke saath khulta hai, aur wapas aane par chhota
 * player sync ho jaata hai (onActivityResult se).
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
}

class MainActivity : AppCompatActivity() {

    private val SITE_URL = "https://a501.vercel.app/"
    private val REQUEST_FULLSCREEN_PLAYER = 9001
    private val SPEEDS = floatArrayOf(0.5f, 1f, 1.25f, 1.5f, 2f)
    private val resizeModes = intArrayOf(
        AspectRatioFrameLayout.RESIZE_MODE_FIT,
        AspectRatioFrameLayout.RESIZE_MODE_FIXED_WIDTH,
        AspectRatioFrameLayout.RESIZE_MODE_FIXED_HEIGHT,
        AspectRatioFrameLayout.RESIZE_MODE_FILL,
        AspectRatioFrameLayout.RESIZE_MODE_ZOOM
    )

    private lateinit var webView: WebView
    private lateinit var swipeRefresh: SwipeRefreshLayout

    // Chhota (inline) native player aur uske controls
    private var inlinePlayer: ExoPlayer? = null
    private var inlineOverlay: FrameLayout? = null
    private var inlinePlayerView: PlayerView? = null
    private var inlineLocked = false
    private var subtitleManuallyDisabled = false
    private var speedIndex = 1 // 1.0x default
    private var resizeModeIndex = 0
    private var inlineUri: String = ""
    private var inlineTitle: String = ""
    private var inlineQualitiesJson: String = "[]"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.webview)
        swipeRefresh = findViewById(R.id.swipe_refresh)

        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.databaseEnabled = true
        webView.settings.mediaPlaybackRequiresUserGesture = false
        webView.settings.allowFileAccess = false
        webView.settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                swipeRefresh.isRefreshing = false
            }

            override fun onReceivedError(view: WebView, errorCode: Int, description: String?, failingUrl: String?) {
                super.onReceivedError(view, errorCode, description, failingUrl)
                swipeRefresh.isRefreshing = false
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
            val backButton = root.findViewById<ImageView>(R.id.inlineBackButton)
            val moreButton = root.findViewById<ImageView>(R.id.inlineMoreButton)
            val lockButton = playerView.findViewById<ImageView>(R.id.inlineLockButton)
            val pipButton = playerView.findViewById<ImageView>(R.id.inlinePipButton)
            val aspectButton = playerView.findViewById<ImageView>(R.id.inlineAspectButton)
            val fullscreenButton = playerView.findViewById<ImageView>(R.id.inlineFullscreenButton)
            val topBarRoot = root.findViewById<View>(R.id.inlineTopBarRoot)
            val unlockButton = root.findViewById<ImageView>(R.id.inlineUnlockButton)

            backButton.setOnClickListener { unmountInlinePlayer() }
            moreButton.setOnClickListener { openFullscreenFromInline() }
            fullscreenButton.setOnClickListener { openFullscreenFromInline() }

            // Bug fix: sirf visibility toggle karne se kuch nahi hota tha kyunki by
            // default koi text track select hi nahi hoti thi (subtitleView hamesha
            // khaali rehta) — ab track type ko explicitly enable/disable karte hain,
            // aur pehli baar milte hi (onTracksChanged) khud-ba-khud pehla text track
            // select kar dete hain, jaise fullscreen player karta hai.
            subtitleButton.setOnClickListener {
                val p = inlinePlayer ?: return@setOnClickListener
                subtitleManuallyDisabled = !subtitleManuallyDisabled
                p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, subtitleManuallyDisabled)
                    .build()
                subtitleButton.alpha = if (subtitleManuallyDisabled) 0.5f else 1f
            }

            speedButton.setOnClickListener {
                speedIndex = (speedIndex + 1) % SPEEDS.size
                val rate = SPEEDS[speedIndex]
                inlinePlayer?.playbackParameters = PlaybackParameters(rate)
                speedButton.text = "${rate}x"
            }

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

            lockButton.setOnClickListener {
                inlineLocked = true
                playerView.useController = false
                topBarRoot.visibility = View.GONE
                unlockButton.visibility = View.VISIBLE
            }
            unlockButton.setOnClickListener {
                inlineLocked = false
                playerView.useController = true
                topBarRoot.visibility = View.VISIBLE
                unlockButton.visibility = View.GONE
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

        inlineOverlay?.findViewById<TextView>(R.id.inlineTitleText)?.text = title

        val player = inlinePlayer ?: ExoPlayer.Builder(this).build().also {
            inlinePlayer = it
            inlinePlayerView?.player = it
            // Fullscreen player jaisa hi behavior: koi bhi text/subtitle track available
            // ho aur user ne khud-se OFF na kiya ho, to pehla milte hi khud-ba-khud select
            // kar do — warna subtitle button "on" dikhta lekin kuch bhi nahi dikhta.
            it.addListener(object : Player.Listener {
                override fun onTracksChanged(tracks: Tracks) {
                    if (subtitleManuallyDisabled) return
                    val alreadySelected = tracks.groups.any { g -> g.type == C.TRACK_TYPE_TEXT && g.isSelected }
                    if (alreadySelected) return
                    val firstTextGroup = tracks.groups.firstOrNull { g -> g.type == C.TRACK_TYPE_TEXT }
                        ?: return
                    it.trackSelectionParameters = it.trackSelectionParameters.buildUpon()
                        .setOverrideForType(TrackSelectionOverride(firstTextGroup.mediaTrackGroup, 0))
                        .build()
                }
            })
        }
        player.setMediaItem(MediaItem.fromUri(Uri.parse(uri)))
        player.prepare()
        player.playWhenReady = true
        inlineOverlay?.visibility = View.VISIBLE
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
        params.topMargin = (top * density).toInt()
        overlay.layoutParams = params
    }

    fun unmountInlinePlayer() {
        inlineOverlay?.visibility = View.GONE
        inlinePlayer?.pause()
    }

    /** Chhote player ke fullscreen/more/PiP button se poora native PlayerActivity khulta hai,
     *  wahi position/playing-state ke saath; wapas aane par onActivityResult se sync hota hai.
     *  enterPipImmediately=true ho to PlayerActivity khulte hi turant real PiP mein chala jaata hai
     *  (isi Activity ka apna already-working PiP implementation use karke). */
    private fun openFullscreenFromInline(enterPipImmediately: Boolean = false) {
        val pos = inlinePlayer?.currentPosition ?: 0L
        val wasPlaying = inlinePlayer?.playWhenReady ?: true
        inlinePlayer?.pause()
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
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_FULLSCREEN_PLAYER && resultCode == RESULT_OK) {
            val pos = data?.getLongExtra("resume_position_ms", 0L) ?: 0L
            val wasPlaying = data?.getBooleanExtra("resume_playing", true) ?: true
            // Agar background mein rehte hue inline player release ho gaya ho (system ne
            // memory ke liye kill kiya), use wahi uri/qualities se dobara mount karke resume karo.
            if (inlinePlayer == null && inlineUri.isNotEmpty()) {
                mountInlinePlayer(inlineUri, inlineTitle, inlineQualitiesJson)
            }
            inlinePlayer?.seekTo(pos)
            inlinePlayer?.playWhenReady = wasPlaying
            inlineOverlay?.visibility = View.VISIBLE
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

    override fun onDestroy() {
        inlinePlayer?.release()
        inlinePlayer = null
        super.onDestroy()
    }
}
