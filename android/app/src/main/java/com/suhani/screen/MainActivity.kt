package com.suhani.screen

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebChromeClient
import androidx.appcompat.app.AppCompatActivity
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.suhani.videoplayer.PlayerActivity

/**
 * Web frontend (VideoPlayer.jsx) isi bridge ke through native Sisisisi PlayerActivity
 * (full ExoPlayer - gestures, equalizer, subtitles, cast, PiP) khol sakta hai, HTML5
 * <video> tag ke bajaye. JS se call: window.AndroidPlayer.playVideo(uri, title)
 *
 * - Online stream: uri = "https://..." ya "*.m3u8"/"*.mpd" link
 * - Offline/downloaded: uri = local file ka "file://" ya "content://" path
 * (dono hi ExoPlayer/PlayerActivity handle karta hai, alag se code nahi likhna padta)
 */
class WebAppInterface(private val activity: MainActivity) {
    @JavascriptInterface
    fun playVideo(uri: String, title: String) {
        activity.runOnUiThread {
            val intent = Intent(activity, PlayerActivity::class.java).apply {
                putExtra("video_uri", uri)
                putExtra("video_title", title)
            }
            activity.startActivity(intent)
        }
    }
}

class MainActivity : AppCompatActivity() {

    private val SITE_URL = "https://a501.vercel.app/"

    private lateinit var webView: WebView
    private lateinit var swipeRefresh: SwipeRefreshLayout

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
        // seedha native Sisisisi player khol sakta hai (window.AndroidPlayer check).
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
}
