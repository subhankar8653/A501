package com.suhani.videoplayer

// A501 app ka namespace "com.suhani.screen" hai (is package se alag), isliye R class
// yahan bare "R.xxx" se auto-resolve nahi hoga jaise Sisisisi mein hota tha - explicit
// import zaroori hai.
import com.suhani.screen.R

import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.Icon
import android.media.MediaScannerConnection
import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.media.audiofx.LoudnessEnhancer
import android.media.audiofx.PresetReverb
import android.media.audiofx.Virtualizer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Rational
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import android.widget.CheckBox
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.AudioAttributes
import androidx.mediarouter.app.MediaRouteButton
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.MediaMetadata as CastMediaMetadata
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.SessionManagerListener
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import org.json.JSONArray

/**
 * MX Player jaisa playback screen.
 *
 * Batch 1: Aspect Ratio, Screenshot, A-B Repeat, Mute, Screen Rotation
 * Batch 2 (naya): Equalizer + Audio Effect (Bass Boost / Virtualizer), Audio Track select,
 * Decoder select (HW / HW+ / SW), External Subtitle, Sleep Timer, Night Mode,
 * Shuffle / Loop (poori playlist ke saath), Background Play (best-effort),
 * More menu -> Bookmark, Favourite, Information, Share, Network Stream.
 *
 * Bug fixes is batch mein:
 * 1) Double-tap seek ab video duration se aage nahi jaata (crash/glitch fix).
 * 2) Gesture feedback overlay ab turant hide/dubara show hone par flicker nahi karta.
 * 3) A-B repeat point ab agli video (next/previous) par carry-over nahi hota.
 * 4) Playback error par ab app silently atakti nahi - Toast dikhta hai.
 */
class PlayerActivity : AppCompatActivity() {

    // Bug fix: onNewIntent() ke baar-baar same video ke liye call hone par poora
    // reload skip karne ke liye (dekho loadVideoFromIntent()) — taaki PiP button
    // dobara dabane ya singleTask re-entry par "different player khul gaya" jaisa
    // flash aur duplicate listener registration na ho.
    private var currentLoadedUri: String? = null

    companion object {
        // YouTube jaisa "background/floating mini player" feature: back dabate
        // hi Android ke native Picture-in-Picture (PiP) mode mein video chota
        // hoke floating window mein chalta rehta hai. Ye actions PiP window ke
        // apne buttons (system overlay, RemoteAction) ke liye hain — PiP ke
        // andar app ko normal touch/gesture events nahi milte (Android system
        // hi us chhote floating window ka touch handle karta hai: tap se expand,
        // drag se move), isliye "hold to 2x" / "double-tap 10s skip" jaisे real
        // gestures PiP ke andar possible nahi hain. In RemoteAction buttons se
        // wahi kaam ho jaata hai: Rewind10 / 2x toggle / Forward10 / Play-Pause.
        private const val ACTION_PIP_PLAY_PAUSE = "com.suhani.videoplayer.ACTION_PIP_PLAY_PAUSE"
        private const val ACTION_PIP_REWIND = "com.suhani.videoplayer.ACTION_PIP_REWIND"
        private const val ACTION_PIP_FORWARD = "com.suhani.videoplayer.ACTION_PIP_FORWARD"
        private const val ACTION_PIP_SPEED_TOGGLE = "com.suhani.videoplayer.ACTION_PIP_SPEED_TOGGLE"
    }

    private var pipSpeedBoosted = false
    // Bug fix (PiP shrink animation ke shuru mein black frame ka flash): dekho
    // enterPipWhenFrameReady() ka comment neeche.
    private var pipEntryFrameListener: Player.Listener? = null

    // Bug fix (PiP band karne ke baad black screen + audio chalte rehna): pehle
    // "isFinishing" akela hi signal tha ye decide karne ke liye ki player ko
    // pause karna hai ya nahi — lekin wahi signal do bilkul alag cases mein
    // aata hai: (1) normal back-navigation jab usingSharedPlayer hai (yahan
    // MainActivity turant onActivityResult() mein player wapas le leta hai,
    // isliye yahan chhedna nahi chahiye), aur (2) system PiP window ka "X"/
    // swipe-away close (yahan bhi MainActivity turant player wapas le lega,
    // BAS agar app ke bahar / kisi doosre app mein PiP band ki gayi hai to
    // audio background mein leak na ho, isliye pause zaroor karo).
    // Fix: track karo ki hum abhi-abhi real PiP mode mein the ya nahi — agar
    // haan, to "false + isFinishing" callback ka matlab hai PiP genuinely band
    // hui hai (X ya swipe se): player ko turant pause karo (audio-leak na ho),
    // LEKIN release/SharedPlayerHolder-clear MAT karo (dekho niche wala
    // onPictureInPictureModeChanged() ka comment — release karne se MainActivity
    // ko naya ExoPlayer banana padta, jisse black-screen/buffering dikhti thi).
    private var wasInRealPipMode = false
    // finish() ke time par read hone wala stable snapshot — dekho
    // onPictureInPictureModeChanged() ka comment ("X dabane par cut nahi hota").
    private var pipCloseFlagForResult = false
    // PiP mein jaate waqt playback state ka snapshot — PiP-se-fullscreen
    // restore par forcibly re-assert karne ke liye (audio-focus-pause bug fix).
    private var pipEntryWasPlaying = false
    // Bug fix ("bada player khulta hai fir PiP hota hai" jhatka): MainActivity se
    // enter_pip_immediately=true ke saath aane par, chhote inline player ka EXACT
    // on-screen rect (pip_src_* extras) yahan store hota hai — pehli hi real PiP
    // entry ke liye buildPipParams() isi rect ko sourceRectHint banata hai (poori-
    // screen wale playerView rect ki jagah), taaki system ka shrink animation
    // seedha usi chhoti jagah se shuru ho jahan video pehle se dikh raha tha. Ek
    // hi baar consume hota hai — tryEnterPipOnBack() call ke turant baad null kar
    // diya jaata hai, taaki baad ki (back-button/swipe se) PiP entries hamesha
    // live fullscreen rect hi use karein.
    private var pendingImmediatePipSourceRect: Rect? = null
    // Bug fix (user report): jab "normal" (inline) player se turant-PiP banaya
    // gaya ho (enter_pip_immediately), aur user us PiP window ko tap karke
    // expand kare, to Android bas is Activity ko fullscreen mein wapas la deta
    // hai — matlab user ko galti se poora FULLSCREEN player dikhta tha, jabki
    // yeh PiP kabhi genuinely "fullscreen dekhi gayi" video thi hi nahi, sirf
    // normal/inline player se seedhi PiP mein gayi thi. Yeh flag track karta hai
    // ki iss session ki PiP inline se aayi thi — expand hote hi (onPictureIn-
    // PictureModeChanged, isFinishing=false wala case) hum seedha finish() kar
    // dete hain taaki control MainActivity ko wapas mile aur wahi NORMAL
    // (inline) player dobara dikhe — bilkul waisi hi jaisi PiP se pehle thi.
    private var pipOriginFromInline = false
    // onPictureInPictureModeChanged() mein wasInRealPipMode already false ho
    // chuka hota hai jab tak onDestroy() chalta hai (dono alag callbacks hain,
    // life-cycle mein pehla dusre se pehle fire hota hai) — isliye onDestroy()
    // tak "yeh genuine PiP close tha" wala fact yaad rakhne ke liye alag,
    // longer-lived flag chahiye.
    private var releasePlayerFullyOnDestroy = false
    // Jab genuine PiP close (X/swipe) par upar wala fix player ko force-pause
    // karta hai (background audio-leak rokne ke liye), wahi "playWhenReady"
    // value niche finish() ke setResult() mein bhi chali jaati thi — matlab
    // MainActivity ko galat sandesh milta "video pause thi", aur PiP band
    // karne ke baad wapas app kholne par inline player play hi nahi hota tha
    // (user maang raha hai: usi duration se play hona chahiye, na ki paused).
    // Yeh field asli "PiP band hone se pehle video chal rahi thi ya nahi"
    // yaad rakhta hai, taaki finish() sahi resume_playing value bhej sake.
    private var resumePlayingIntentOverride: Boolean? = null

    // BUG FIX (major rewrite — user report: "PiP ka X dabane par bhi cut nahi
    // hota, video kahin na kahin chalta reh jaata hai"): saari pichli koshishein
    // system ke `isFinishing` flag par depend karti thi, jo humare control mein
    // nahi hai aur kai devices/OEM builds par turant (ya kabhi bhi) reliably
    // settle nahi hota — isliye ek chhoti race window mein galat decision
    // ("close" ki jagah "expand") le liya jaata tha, aur ek baar galat decision
    // lene ke baad (controls wapas dikha diye / playback resume kar diya /
    // galat result extras ke saath finish() bhi ho chuka) koi baad ka watchdog
    // usko theek nahi kar sakta tha.
    //
    // Naya approach: `isFinishing` per bharosa hi mat karo. Iski jagah Android
    // khud jo DO GUARANTEED, ordering-safe lifecycle signal deta hai unhi par
    // bharosa karo:
    //   - Genuine "tap to expand": Activity hamesha wapas RESUMED state mein
    //     aati hai -> onResume() zaroor call hota hai.
    //   - Genuine "X"/swipe close: Activity kabhi RESUMED nahi hoti, seedha
    //     onStop() (phir onDestroy()) par chali jaati hai -> onResume() KABHI
    //     call nahi hota.
    // `pendingPipExitDecision` PiP se bahar aate hi true set hota hai, aur
    // jo bhi lifecycle callback PEHLE genuinely fire ho (onResume ya onStop),
    // wahi is flag ko consume karke sahi faisla leta hai — koi timer/race nahi.
    private var pendingPipExitDecision = false

    private var pipReceiverRegistered = false
    private val pipActionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (!::player.isInitialized) return
            when (intent?.action) {
                ACTION_PIP_PLAY_PAUSE -> {
                    player.playWhenReady = !player.playWhenReady
                }
                ACTION_PIP_REWIND -> {
                    player.seekTo((player.currentPosition - 10_000).coerceAtLeast(0))
                }
                ACTION_PIP_FORWARD -> {
                    val dur = player.duration
                    val target = player.currentPosition + 10_000
                    player.seekTo(if (dur > 0) target.coerceAtMost(dur) else target)
                }
                ACTION_PIP_SPEED_TOGGLE -> {
                    pipSpeedBoosted = !pipSpeedBoosted
                    player.playbackParameters = PlaybackParameters(if (pipSpeedBoosted) 2f else 1f)
                }
                // Bug fix: "Background Play" notification ka "Stop" button pehle sirf
                // notification/service hata deta tha, ExoPlayer chalta rehta tha. Ab
                // BackgroundPlaybackService yahan yeh broadcast bhejta hai jise sunkar
                // hum player ko genuinely pause karte hain.
                BackgroundPlaybackService.ACTION_PAUSE_PLAYBACK -> {
                    player.playWhenReady = false
                }
                else -> return
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                try {
                    setPictureInPictureParams(buildPipParams().build())
                } catch (_: Exception) {}
            }
        }
    }

    private lateinit var player: ExoPlayer
    // Chhote inline player (MainActivity) se handoff hote waqt agar usi ExoPlayer
    // instance ko reuse kar rahe hain (naya buffer banane se bachne ke liye), to
    // yeh true hota hai — isse pata chalta hai ki is player ko release() karna hai
    // ya nahi (shared instance MainActivity ka hai, hum sirf "borrow" kar rahe hain).
    private var usingSharedPlayer = false
    private lateinit var playerView: PlayerView
    private lateinit var playerContainer: FrameLayout
    // Web frontend se aayi quality list (label -> url) — More menu ke "Quality"
    // option se yahi list dikhti hai, jugaad-style: bas mediaItem swap + resume.
    private var availableQualities: List<Pair<String, String>> = emptyList()
    // Audio Track / Subtitle jaisa koi bhi overlay panel khula hai to count yahan track hota hai
    // (nested panels — jaise Subtitle -> Settings — ke liye), taaki sirf pehla panel khulne par
    // shrink ho aur sirf aakhri panel band hone par hi wapas normal size mein aaye.
    private var openOverlayPanelCount = 0
    private var playerContainerWidthAnimator: android.animation.ValueAnimator? = null
    private lateinit var gestureIndicator: LinearLayout
    private lateinit var gestureText: TextView

    // Player-wide themed toast/snackbar (see showPlayerSnackbar()) — replaces
    // scattered plain Toast.makeText(...) calls across the file so every
    // status/error message looks consistent with the rest of the player UI.
    private lateinit var playerSnackbar: LinearLayout
    private lateinit var playerSnackbarIcon: ImageView
    private lateinit var playerSnackbarText: TextView
    private val hidePlayerSnackbarRunnable = Runnable { hidePlayerSnackbar() }

    // Dedicated chhota "2x" badge, upar beech mein (center of screen wale
    // gestureIndicator se alag) — sirf hold-to-2x-speed ke liye.
    private lateinit var speedIndicatorBadge: TextView
    private lateinit var resetZoomChip: View
    private lateinit var frameBackButton: ImageView
    private lateinit var frameForwardButton: ImageView
    private val hideSpeedIndicatorRunnable = Runnable {
        if (::speedIndicatorBadge.isInitialized) speedIndicatorBadge.visibility = View.GONE
    }
    private lateinit var seekIndicatorLeft: LinearLayout
    private lateinit var seekTextLeft: TextView
    private lateinit var seekIndicatorRight: LinearLayout
    private lateinit var seekTextRight: TextView
    private lateinit var volumeSliderContainer: LinearLayout
    private lateinit var volumePercentText: TextView
    private lateinit var volumeFill: View
    private lateinit var volumeBoostFill: View
    private lateinit var brightnessSliderContainer: LinearLayout
    private lateinit var brightnessPercentText: TextView
    private lateinit var brightnessFill: View
    private lateinit var topBar: LinearLayout
    // More (three-dot) menu ab AlertDialog nahi, balki playerContainer ke
    // andar ek real positioned overlay panel hai — inhi 2 refs se open/close
    // track hota hai (toggle + outside-tap-to-dismiss + back-press-to-dismiss).
    private var moreMenuPanel: View? = null
    private var moreMenuScrim: View? = null
    private lateinit var quickActionsScroll: View
    private lateinit var expandActionsButton: ImageView
    private lateinit var extraQuickActions: LinearLayout
    private lateinit var audioTrackButton: ImageView
    private lateinit var speedButton: TextView
    private lateinit var decoderButton: TextView
    private lateinit var moreButton: ImageView
    private lateinit var lockButton: ImageView
    private lateinit var unlockButton: ImageView
    private lateinit var bottomPipButton: ImageView
    private lateinit var bottomAspectButton: ImageView
    private lateinit var backButton: ImageView

    private lateinit var topContainer: LinearLayout
    private lateinit var playerTitleText: TextView
    // Swipe-down-to-PiP: video ko hold karke neeche push karne se bhi mini
    // player khulta hai — sirf topContainer (title/icon strip) se track karte
    // hain taaki poori video-surface par pehle se chal rahe volume/brightness
    // swipe gestures se koi conflict na ho.
    private var pipSwipeStartY = 0f
    private var pipSwipeStartX = 0f
    private var pipSwipeDragging = false
    private lateinit var gestureDetector: GestureDetector
    private lateinit var videoInfoBadge: TextView
    private lateinit var scrubPreviewContainer: LinearLayout
    private lateinit var scrubPreviewImage: ImageView
    private lateinit var scrubPreviewTime: TextView
    private var scrubPreviewRetriever: android.media.MediaMetadataRetriever? = null
    private var scrubPreviewRetrieverUri: String? = null
    private val scrubPreviewExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()
    // Online subtitle search/download (OpenSubtitles.com) background thread ke liye —
    // baaki executors jaisa hi single-thread pattern, taaki network call UI ko block na kare.
    private val onlineSubtitleExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()

    // --- Smart Chapters (naya premium feature): frame-sampling se scene-cut
    // detect karke timeline par chapter markers dikhata hai — background
    // thread par (scrubPreviewExecutor jaisa hi single-thread pattern).
    private val chapterAnalysisExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()
    private var chapterMarkersMs: MutableList<Long> = mutableListOf()
    private var chapterAnalyzedUri: String? = null
    private var isAnalyzingChapters = false

    // --- Speed Ramp (naya premium feature): current speed se target speed
    // tak N second mein smoothly interpolate karta hai (jaise ek "fade" lekin
    // playback speed ke liye) — MX Player ke fixed speed-selector se alag,
    // yahan transition khud gradual hoti hai.
    private val speedRampHandler = Handler(Looper.getMainLooper())
    private var speedRampRunnable: Runnable? = null

    // Cast (Chromecast): session listener sirf ek baar register hota hai (lazy), taaki
    // baar-baar cast button dabane par duplicate listeners na jud jaayein.
    private var castSessionManagerListener: SessionManagerListener<CastSession>? = null
    // Har naye scrub-frame request ka ek stamp — purane background thread se late aaya
    // result agar naya scrub already aage badh chuka hai to discard ho jaata hai (warna
    // fast-drag karte waqt purana/galat frame flash ho sakta tha).
    private var scrubPreviewRequestId = 0

    // Dual Subtitle: doosri, independent subtitle file (screen ke upar) — dekho
    // DualSubtitleController.kt. secondarySubtitleText view onCreate() mein findViewById hoti hai.
    private lateinit var secondarySubtitleText: TextView
    private var dualSubtitleController: DualSubtitleController? = null

    // Gesture Settings (double-tap seek seconds + swipe sensitivity) — GesturePrefs.kt
    // se load hote hain, More > Gesture Settings se change ho sakte hain.
    private var seekSecondsPref = GesturePrefs.SEEK_SECONDS_DEFAULT
    private var swipeSensitivityMultiplier = 1f

    // Auto Subtitle: video khulte hi (agar setting ON hai aur koi subtitle already
    // nahi hai) chup-chaap OpenSubtitles.com search + top result attach karta hai.
    // Har media item ke liye sirf ek baar try hota hai (naya video/replay par flag reset).
    private var autoSubtitleAttemptedForCurrentItem = false

    // More menu -> Display Settings / Video Display toggle state
    private var videoDisplayInfoOn = false
    // Audio Only mode: video decoding/rendering band, sirf audio chalta rehta hai
    // (battery/data bachane ke liye) — screen par ek dark placeholder dikhta hai.
    private var audioOnlyModeOn = false
    private var keepScreenOnPref = true

    // More menu -> Cut (trim) feature
    private var cutPointA = -1L
    private var cutPointB = -1L

    private lateinit var aspectRatioButton: ImageView
    private lateinit var screenshotButton: ImageView
    private lateinit var abRepeatButton: ImageView
    private lateinit var abRepeatLabel: TextView
    private lateinit var muteButton: ImageView
    private lateinit var muteLabel: TextView
    private lateinit var rotateButton: ImageView

    // Batch 2 views
    private lateinit var nightModeOverlay: View
    private lateinit var audioOnlyOverlay: View
    private lateinit var nightModeButton: ImageView
    private lateinit var shuffleButton: ImageView
    private lateinit var loopButton: ImageView
    private lateinit var sleepTimerButton: ImageView
    private lateinit var sleepTimerLabel: TextView

    // ---------------------------------------------------------------------
    // Naye "sabse alag" features: Ambient Glow, Stats for Nerds, One-Handed
    // Reach Mode, Ambient Sleep Timer dim, Auto Intro/Recap Skip
    // ---------------------------------------------------------------------
    private lateinit var ambientGlowView: AmbientGlowView
    private lateinit var ambientGlowButton: ImageView
    private lateinit var statsForNerdsOverlay: TextView
    private lateinit var statsForNerdsButton: ImageView
    private lateinit var reachModeButton: ImageView
    private lateinit var markIntroButton: ImageView
    private lateinit var markIntroLabel: TextView
    private lateinit var skipIntroButton: LinearLayout
    private lateinit var sleepDimOverlay: View

    private var ambientGlowOn = false
    private var statsForNerdsOn = false
    // Reach mode: 0 = off, 1 = compact bottom-right, 2 = compact bottom-left
    private var reachModeState = 0
    private val featuresHandler = Handler(Looper.getMainLooper())
    private var totalDroppedFrames = 0

    private var introMarkStartMs = -1L
    private var currentFolderKey = ""
    private var introRangeForCurrent: Pair<Long, Long>? = null
    private var introSkipShown = false

    private var sleepDimRunnable: Runnable? = null
    private var sleepDimAnimator: android.animation.ValueAnimator? = null

    /** Har ~800ms mein current video frame ke edges se average color nikaal kar
     *  Ambient Glow ko update karta hai. */
    private val ambientGlowRunnable = object : Runnable {
        override fun run() {
            if (ambientGlowOn && ::player.isInitialized && player.isPlaying) {
                sampleAmbientGlowColors()
            }
            featuresHandler.postDelayed(this, 800)
        }
    }

    /** Har second stats overlay refresh karta hai jab wo visible ho. */
    private val statsForNerdsRunnable = object : Runnable {
        override fun run() {
            if (statsForNerdsOn) {
                updateStatsForNerdsOverlay()
            }
            featuresHandler.postDelayed(this, 1000)
        }
    }

    /** Har 400ms current position check karta hai — agar marked intro-range ke andar
     *  hai to "Skip Intro" pill dikhao, warna chhupao. */
    private val introSkipCheckRunnable = object : Runnable {
        override fun run() {
            checkIntroSkipVisibility()
            featuresHandler.postDelayed(this, 400)
        }
    }
    private lateinit var subtitleButton: ImageView
    private lateinit var equalizerButton: ImageView
    private lateinit var castButton: ImageView
    private lateinit var backgroundPlayButton: ImageView
    private lateinit var backgroundPlayLabel: TextView

    private var startX = 0f
    private var startY = 0f
    private var isSwipingVolume = false
    private var isSwipingBrightness = false

    // Swipe-down-to-PiP seedha video (playerView) par se bhi: pehle sirf
    // topContainer (top icon strip) se hi PiP trigger hota tha, video ko
    // hold karke kahin bhi bich mein se niche khinchne se kuch nahi hota
    // tha. Ab beech ka ek "middle zone" (left/right volume-brightness
    // swipe zones ke beech, taaki un dono se conflict na ho) isi kaam ke
    // liye reserved hai.
    private var isSwipingToPipFromVideo = false
    private var currentVolume = 0f
    private var currentBrightness = 0.5f
    private var isLocked = false

    // Volume gesture ab 0%-200% tak jaata hai: 0-100% device ka normal stream
    // volume hai, 100-200% LoudnessEnhancer se extra software boost hai (isi
    // enhancer ko Equalizer dialog ka "Volume Boost" slider bhi use karta hai,
    // dono jagah se ek hi state control/sync hoti hai).
    private var volumeGestureStartPercent = 100f

    // Bug fix: pehle agla swipe gesture shuru hote hi actual hardware se volume
    // % dobara nikala jaata tha (getCurrentVolumePercent). Kai devices par
    // OEM ka "hearing safety" / auto volume-limiter feature background mein
    // stream volume ko chhupke se thoda kam kar deta hai (jaise 156% set kiya
    // tha to kuch der baad 120-130% jaisa dikhne lagta tha), jiski wajah se
    // agli baar swipe start karte hi wo already-kam-hui value se shuru ho jaata
    // tha aur user ko lagta tha volume "apne aap kam ho gaya". Ab hum apni khud
    // ki app-level target value yaad rakhte hain (lastAppliedVolumePercent) aur
    // usi se agla gesture start karte hain, taaki OS/OEM ke background
    // adjustment se app ka apna set kiya hua level disturb na ho.
    private var lastAppliedVolumePercent = 100f

    // Continuous double-tap seek: baar baar tap karte rehne se seconds
    // accumulate hote hain (10s, 20s, 30s ... jab tak tap karte raho, usi
    // side par). Ek chhote window ke andar agla tap "continuation" maana
    // jaata hai, warna naya session start ho jaata hai.
    private var lastSeekSide = 0 // 0 = koi active session nahi, -1 = left, 1 = right
    private var seekAccumulatedUnits = 0
    private var consumingSeekContinuationGesture = false
    private val seekSessionHandler = Handler(Looper.getMainLooper())
    private val seekSessionResetRunnable = Runnable {
        lastSeekSide = 0
        seekAccumulatedUnits = 0
    }

    // YouTube jaisa: screen ke kisi bhi side ko hold (long press) karne par
    // video 2x speed par chalta hai, finger uthate hi wapas normal speed par.
    private var isLongPressSpeedActive = false
    private var speedBeforeLongPress = 1f

    private val speedOptions = floatArrayOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f)
    private var speedIndex = 2 // 1.0x default

    // Premium feature: Pinch-to-Zoom & Pan — do ungliyon se video ko zoom
    // (1x-4x) aur zoom hone par ek ungli se pan/drag kiya ja sakta hai
    // (VLC/MX Player Pro jaisa). Sirf video surface par apply hota hai,
    // controls (seekbar, buttons) is scale/translation se untouched rehte hain.
    private lateinit var scaleGestureDetector: android.view.ScaleGestureDetector
    private var videoZoomScale = 1f
    private var videoPanX = 0f
    private var videoPanY = 0f
    private var isPinchZooming = false
    private var isPanningZoomedVideo = false
    private var panStartX = 0f
    private var panStartY = 0f
    private var panStartTranslationX = 0f
    private var panStartTranslationY = 0f
    private var lastPinchFocusX = 0f
    private var lastPinchFocusY = 0f
    private val MIN_ZOOM = 0.5f
    private val MAX_ZOOM = 4f

    private lateinit var audioManager: android.media.AudioManager
    private var maxVolume = 15

    private val resizeModes = intArrayOf(
        AspectRatioFrameLayout.RESIZE_MODE_FIT,
        AspectRatioFrameLayout.RESIZE_MODE_FIXED_WIDTH,
        AspectRatioFrameLayout.RESIZE_MODE_FIXED_HEIGHT,
        AspectRatioFrameLayout.RESIZE_MODE_FILL,
        AspectRatioFrameLayout.RESIZE_MODE_ZOOM
    )
    private var resizeModeIndex = 0

    private var isMuted = false
    private var volumeBeforeMute = 1f

    private var pointA = -1L
    private var pointB = -1L
    private var abState = 0 // 0 = none set, 1 = A set, 2 = A-B looping
    private val abHandler = Handler(Looper.getMainLooper())

    // Controls (top bar + quick actions + bottom seekbar/buttons) ab isi ek Handler
    // se sync me show/hide hote hain — MX Player jaisa: sab ek saath 3 second baad
    // gayab, tap karne par sab ek saath wapas.
    private val controlsHandler = Handler(Looper.getMainLooper())
    private val hideControlsRunnable = Runnable { hideAllControls() }
    private val abRunnable = object : Runnable {
        override fun run() {
            if (abState == 2 && ::player.isInitialized) {
                if (player.currentPosition >= pointB) {
                    player.seekTo(pointA)
                }
            }
            abHandler.postDelayed(this, 300)
        }
    }

    // Playlist (same folder ke videos)
    private var queue: List<VideoItem> = emptyList()
    private var queueStartIndex: Int = 0

    // Decoder mode
    // Bug fix: default pehle "HW" (extension OFF) tha — iska matlab kisi bhi track (jaise
    // Hindi dub ka AC3/DTS audio) ke liye agar hardware decoder support nahi karta, to seedha
    // error aata tha, koi automatic fallback nahi hota. Ab default "HW+" hai (hardware
    // preferred, per-track automatically FFmpeg software decoder par fallback) — isliye video
    // open hote hi sahi decoder khud-ba-khud select ho jaata hai, bina kisi error/rebuild ke.
    private var decoderMode = 1 // 0 = HW, 1 = HW+, 2 = SW

    // Bug fix: pehle decoder-init / IO errors seedha ek Toast dikha kar chhod dete the — user
    // ko manually "Decoder" menu khol kar HW+ / SW try karna padta tha. Ab errors ko auto-handle
    // karte hain: decoder na chal paaye to khud-ba-khud agla (zyada compatible) decoder mode try
    // karta hai usi position se resume karke; IO glitch ho to pehle chup-chaap 1-2 baar retry
    // karta hai, tabhi user ko error dikhata hai jab sab kuch fail ho jaaye.
    private var ioRetryCount = 0
    private val maxIoRetries = 2

    // Night Mode
    private var nightModeOn = false

    // Background Play
    private var backgroundPlayEnabled = false

    // Sleep Timer
    private val sleepHandler = Handler(Looper.getMainLooper())
    private var sleepRunnable: Runnable? = null
    private var sleepTimerActive = false

    // Equalizer / Audio Effects (system AudioFx, koi extra library ki zaroorat nahi)
    // PERMANENT EQUALIZER FIX: apna khud ka in-app PCM equalizer, jo har device par
    // reliably kaam karta hai (system Equalizer ki tarah vendor HAL par depend nahi karta).
    // Activity ke jeete-ji ek hi instance reuse hoti hai taaki gain values buildPlayer()
    // rebuild (decoder switch) ke baad bhi persist rahein.
    private val eqProcessor = EqualizerAudioProcessor()

    private var equalizer: Equalizer? = null
    private var bassBoost: BassBoost? = null
    private var virtualizer: Virtualizer? = null
    private var presetReverb: PresetReverb? = null
    private var loudnessEnhancer: LoudnessEnhancer? = null
    private var audioFxAttached = false
    private var attachedSessionId: Int = C.AUDIO_SESSION_ID_UNSET

    // 30dB tak extra loudness (device ki max capacity). Equalizer dialog ka
    // "Volume Boost" slider AUR swipe-to-200%-volume gesture, dono isi ek
    // range ko share karte hain (100%-200% = 0dB-30dB).
    private val MAX_BOOST_MILLIBEL = 3000

    // Player rebuild hone par (jaise decoder mode switch) purane effects release ho jaate
    // hain, isliye unki settings yahan persist karte hain taaki reattach hone par restore
    // ho sakein — warna decoder mode badalte hi EQ/Bass/Virtualizer/Reverb/Volume Boost
    // chup-chaap reset ho jaate the (real bug tha).
    private var savedEqEnabled: Boolean? = null
    private var savedBandLevels: List<Short>? = null
    private var savedBassEnabled: Boolean? = null
    private var savedBassStrength: Short? = null
    private var savedVirtEnabled: Boolean? = null
    private var savedVirtStrength: Short? = null
    private var savedReverbPreset: Short? = null
    private var savedLoudnessGain: Float? = null
    private var savedLoudnessEnabled: Boolean? = null

    // Dialog band aur effect ka current selection persist karne ke liye (real bug fix):
    // pehle popup band karke reopen karne par UI selector hamesha "Custom"/"Original"
    // pe hardcode reset ho jata tha, chahe user ne koi bhi preset/effect select kiya ho.
    // Ab actual last-selected naam yahan persist hota hai taaki reopen par sahi
    // preset/effect highlight ho, reset jaisa mehsoos na ho.
    private var savedEqPresetName: String = "Custom"
    private var savedEffectName: String = "Original"

    // ---------------------------------------------------------------------
    // Phase 3: Subtitle full-screen menu + Subtitles Customization + Audio
    // Track full dialog ke liye state (screenshots jaisa)
    // ---------------------------------------------------------------------
    private var subtitleLoaded = false
    private var subPanelOn = false

    // Layout
    private val subAlignments = arrayOf("Center", "Left", "Right")
    private var subAlignmentIndex = 0
    private var subBottomMargin = 8 // 0-100
    private var subLayoutBgEnabled = false
    private var subLayoutBgColor = android.graphics.Color.parseColor("#80000000")
    private var subFitToVideoSize = true

    // Text
    private val subFonts = arrayOf("Default", "Sans Serif", "Serif", "Monospace")
    private var subFontIndex = 0
    private var subSizeSp = 22 // 8-40
    private var subScalePercent = 100 // 50-150
    private var subTextColor = android.graphics.Color.WHITE
    private var subBold = true
    private var subTextBgEnabled = false
    private var subTextBgColor = android.graphics.Color.parseColor("#80000000")
    private var subBorderEnabled = false
    private var subBorderColor = android.graphics.Color.BLACK
    private var subBorderSize = 80 // 0-100
    private var subAdvancedExpanded = false
    private var subShadowEnabled = false

    private val colorSwatches = intArrayOf(
        android.graphics.Color.WHITE,
        android.graphics.Color.YELLOW,
        android.graphics.Color.parseColor("#FF3B30"),
        android.graphics.Color.parseColor("#34C759"),
        android.graphics.Color.parseColor("#00C7FF"),
        android.graphics.Color.BLACK
    )

    // Audio Track dialog
    private var useSwAudioDecoder = false

    // Subtitle picker (Storage Access Framework)
    private val openSubtitleLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) attachSubtitle(uri) }

    // Dual Subtitle picker — is se select hui file DualSubtitleController mein
    // (ExoPlayer se bilkul alag) parse+render hoti hai, primary subtitle ke saath saath.
    private val openSecondarySubtitleLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            try {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: Exception) { /* kuch providers persistable permission support nahi karte */ }
            val ok = dualSubtitleController?.load(uri) ?: false
            showGestureFeedback(if (ok) "Dual subtitle loaded" else "Dual subtitle load nahi ho payi (SRT/VTT try karein)")
        }
    }

    // Background Play reliable banane ke liye foreground-service notification permission (API 33+)
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* granted ho ya na ho, background play foreground service ke bina bhi kaam karega,
           bas notification dikhegi ya nahi wo depend karega */ }

    private fun ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // Bug fix: app background se wapas aane par ya focus milne par Android
        // system bars ko dobara dikha deta hai — agar controls abhi hidden hain
        // (ya screen locked hai) to bars ko phir se immersive mode mein le aao.
        if (hasFocus && ::playerView.isInitialized) {
            if (isLocked || !playerView.isControllerFullyVisible) {
                hideSystemBars()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_player)
        hideSystemBars()

        val pipFilter = IntentFilter().apply {
            addAction(ACTION_PIP_PLAY_PAUSE)
            addAction(ACTION_PIP_REWIND)
            addAction(ACTION_PIP_FORWARD)
            addAction(ACTION_PIP_SPEED_TOGGLE)
            addAction(BackgroundPlaybackService.ACTION_PAUSE_PLAYBACK)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(pipActionReceiver, pipFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(pipActionReceiver, pipFilter)
        }
        pipReceiverRegistered = true

        playerView = findViewById(R.id.playerView)
        playerContainer = findViewById(R.id.playerContainer)
        gestureIndicator = findViewById(R.id.gestureIndicator)
        gestureText = findViewById(R.id.gestureText)
        speedIndicatorBadge = findViewById(R.id.speedIndicatorBadge)
        resetZoomChip = findViewById(R.id.resetZoomChip)
        resetZoomChip.setOnClickListener { resetVideoZoom() }
        playerSnackbar = findViewById(R.id.playerSnackbar)
        playerSnackbarIcon = findViewById(R.id.playerSnackbarIcon)
        playerSnackbarText = findViewById(R.id.playerSnackbarText)

        // Premium: Frame-by-Frame stepping — pause karke precise frame-by-frame
        // navigation, editing/analysis dekhne walo ke liye kaafi useful.
        frameBackButton = findViewById(R.id.frameBackButton)
        frameForwardButton = findViewById(R.id.frameForwardButton)
        frameBackButton.setOnClickListener { stepFrame(forward = false) }
        frameForwardButton.setOnClickListener { stepFrame(forward = true) }
        seekIndicatorLeft = findViewById(R.id.seekIndicatorLeft)
        seekTextLeft = findViewById(R.id.seekTextLeft)
        seekIndicatorRight = findViewById(R.id.seekIndicatorRight)
        seekTextRight = findViewById(R.id.seekTextRight)
        volumeSliderContainer = findViewById(R.id.volumeSliderContainer)
        volumePercentText = findViewById(R.id.volumePercentText)
        volumeFill = findViewById(R.id.volumeFill)
        volumeBoostFill = findViewById(R.id.volumeBoostFill)
        brightnessSliderContainer = findViewById(R.id.brightnessSliderContainer)
        brightnessPercentText = findViewById(R.id.brightnessPercentText)
        brightnessFill = findViewById(R.id.brightnessFill)
        topBar = findViewById(R.id.topBar)
        quickActionsScroll = findViewById(R.id.quickActionsScroll)
        expandActionsButton = findViewById(R.id.expandActionsButton)
        extraQuickActions = findViewById(R.id.extraQuickActions)
        audioTrackButton = findViewById(R.id.audioTrackButton)
        speedButton = findViewById(R.id.speedButton)
        decoderButton = findViewById(R.id.decoderButton)
        moreButton = findViewById(R.id.moreButton)
        lockButton = findViewById(R.id.lockButton)
        unlockButton = findViewById(R.id.unlockButton)
        bottomPipButton = findViewById(R.id.bottomPipButton)
        topContainer = findViewById(R.id.topContainer)
        bottomAspectButton = findViewById(R.id.bottomAspectButton)
        backButton = findViewById(R.id.backButton)
        playerTitleText = findViewById(R.id.playerTitleText)
        // Premium touch: lambe/long file names ab truncate ho kar "..." nahi dikhte,
        // balki YouTube/MX Player jaisa continuous marquee scroll karte hain. isSelected=true
        // zaroori hai — Android mein marquee tabhi start hota hai jab TextView "selected" ho.
        playerTitleText.isSelected = true
        videoInfoBadge = findViewById(R.id.videoInfoBadge)
        scrubPreviewContainer = findViewById(R.id.scrubPreviewContainer)
        scrubPreviewImage = findViewById(R.id.scrubPreviewImage)
        scrubPreviewTime = findViewById(R.id.scrubPreviewTime)

        secondarySubtitleText = findViewById(R.id.secondarySubtitleText)
        dualSubtitleController = DualSubtitleController(
            context = this,
            targetView = secondarySubtitleText,
            currentPositionMs = { if (::player.isInitialized) player.currentPosition else 0L }
        )

        seekSecondsPref = GesturePrefs.getSeekSeconds(this)
        swipeSensitivityMultiplier = GesturePrefs.swipeMultiplier(this)

        aspectRatioButton = findViewById(R.id.aspectRatioButton)
        screenshotButton = findViewById(R.id.screenshotButton)
        abRepeatButton = findViewById(R.id.abRepeatButton)
        abRepeatLabel = findViewById(R.id.abRepeatLabel)
        muteButton = findViewById(R.id.muteButton)
        muteLabel = findViewById(R.id.muteLabel)
        rotateButton = findViewById(R.id.rotateButton)

        nightModeOverlay = findViewById(R.id.nightModeOverlay)
        audioOnlyOverlay = findViewById(R.id.audioOnlyOverlay)
        nightModeButton = findViewById(R.id.nightModeButton)
        shuffleButton = findViewById(R.id.shuffleButton)
        loopButton = findViewById(R.id.loopButton)
        sleepTimerButton = findViewById(R.id.sleepTimerButton)
        sleepTimerLabel = findViewById(R.id.sleepTimerLabel)
        subtitleButton = findViewById(R.id.subtitleButton)
        equalizerButton = findViewById(R.id.equalizerButton)
        castButton = findViewById(R.id.castButton)
        applyPressScale(castButton)
        backgroundPlayButton = findViewById(R.id.backgroundPlayButton)
        backgroundPlayLabel = findViewById(R.id.backgroundPlayLabel)

        ambientGlowView = findViewById(R.id.ambientGlowView)
        ambientGlowButton = findViewById(R.id.ambientGlowButton)
        statsForNerdsOverlay = findViewById(R.id.statsForNerdsOverlay)
        statsForNerdsButton = findViewById(R.id.statsForNerdsButton)
        reachModeButton = findViewById(R.id.reachModeButton)
        markIntroButton = findViewById(R.id.markIntroButton)
        markIntroLabel = findViewById(R.id.markIntroLabel)
        skipIntroButton = findViewById(R.id.skipIntroButton)
        sleepDimOverlay = findViewById(R.id.sleepDimOverlay)

        audioManager = getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        maxVolume = audioManager.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)
        currentVolume = audioManager.getStreamVolume(android.media.AudioManager.STREAM_MUSIC).toFloat()
        lastAppliedVolumePercent = if (maxVolume > 0) (currentVolume / maxVolume) * 100f else 100f
        currentBrightness = window.attributes.screenBrightness.let { if (it < 0) 0.5f else it }

        loadVideoFromIntent()

        // Bug fix: top-left corner mein pehle 2 icons the (back arrow +
        // dedicated chevron), dono hi PiP khol dete the — duplicate tha, ek
        // hata diya gaya hai (dekho activity_player.xml, pipChevronButton
        // hata diya). Bacha hua back button ab seedha peeche/wapas jaata hai
        // (jahan se fullscreen open kiya tha), PiP nahi kholta — PiP ke liye
        // bottomPipButton ya neeche-swipe gesture use karo.
        backButton.setOnClickListener { finish() }

        speedButton.setOnClickListener { showSpeedSheet() }

        lockButton.setOnClickListener { setLocked(true) }
        unlockButton.setOnClickListener { setLocked(false) }
        applyPressScale(lockButton)
        applyPressScale(unlockButton)

        setupBatch1Features()
        setupBatch2Features()
        setupBatch3Features()
        setupGestures()
        setupPlayPauseButton()
        setupScrubPreview()

        // Premium touch (round 2): pehle sirf cast/lock/play-pause ko
        // applyPressScale() milta tha — ab baaki saare frequently-tapped
        // control icons ko bhi wahi tactile press-bounce feedback milta hai,
        // taaki poora control-bar ek hi consistent "alive" feel de.
        listOf(
            backButton, frameBackButton, frameForwardButton, moreButton,
            aspectRatioButton, screenshotButton, abRepeatButton, muteButton,
            rotateButton, nightModeButton, shuffleButton, loopButton,
            sleepTimerButton, equalizerButton, backgroundPlayButton,
            ambientGlowButton, statsForNerdsButton, expandActionsButton,
            audioTrackButton, subtitleButton, speedButton,
            bottomPipButton, bottomAspectButton, resetZoomChip
        ).forEach { applyPressScale(it) }

        abHandler.post(abRunnable)
        // Bug fix ("badi wali screen khulti hai fir PiP hoti hai" jhatka, part 2):
        // pehle yahan hamesha showAllControls() call hota tha — chahe hum turant
        // (kuch hi ms mein) PiP mein shrink hone hi wale ho (enter_pip_immediately).
        // Matlab top bar/title/icons ek pal ke liye fade-in hote dikhte the phir
        // turant PiP shrink ho jaata — isi flash se "pehle bada player khula"
        // jaisa mehsoos hota tha. Ab is specific case mein controls kabhi dikhaye
        // hi nahi jaate — sirf raw video frame render hota hai jab tak
        // enterPipWhenFrameReady() (loadVideoFromIntent() mein) PiP mein le nahi
        // jaata, taaki koi bhi button/text kabhi ek frame ke liye bhi na dikhe.
        if (!intent.getBooleanExtra("enter_pip_immediately", false)) {
            showAllControls()
        } else {
            playerView.useController = false
            topBar.visibility = View.GONE
            quickActionsScroll.visibility = View.GONE
        }

        featuresHandler.post(ambientGlowRunnable)
        featuresHandler.post(statsForNerdsRunnable)
        featuresHandler.post(introSkipCheckRunnable)
    }

    /**
     * Intent (aur PlaybackQueue) se video/playlist load karke player (re)build karta hai.
     * onCreate() aur onNewIntent() dono isi ko call karte hain — isse "naya video play karo"
     * hamesha isi single Activity instance/player mein hota hai, kabhi ek alag duplicate
     * PlayerActivity nahi khulta.
     */
    private fun loadVideoFromIntent() {
        // Bug fix (seekbar idhar-udhar jump + "galat player khul gaya" flash): jab inline
        // player ke PiP button ko dobara dabaya jaata (ya kabhi Android khud singleTask
        // reuse ki wajah se onNewIntent() dubara call karta) usi video ke liye jo already
        // isi Activity mein chal rahi hai, to pehle yeh function unconditionally poora
        // dobara chalta tha — zoom reset, queue rebuild, aur (sab se bada issue) neeche
        // wala shared-player branch bina purana listener hataye player.addListener() phir
        // se call kar deta tha, matlab wahi listener ek hi player par 2-3 baar registered
        // ho jaata — har position/track/media-item event double-triple fire hota, isse
        // seekbar/UI thoda "jump" karti dikhti thi. Fix: agar yeh genuinely wahi video hai
        // jo abhi already load/playing hai, to kuch bhi rebuild na karo — bas turant return.
        val incomingUri = intent.getStringExtra("video_uri")
        if (::player.isInitialized && incomingUri != null && incomingUri == currentLoadedUri) {
            // Reload skip ho raha hai, lekin agar isi tap ne PiP maangi thi to wo
            // honour zaroor honi chahiye (warna dobara PiP button dabana silently
            // kuch na kare, aisa nahi hona chahiye).
            if (intent.getBooleanExtra("enter_pip_immediately", false)) {
                pipOriginFromInline = true
                pendingImmediatePipSourceRect = readPipSourceRectExtra()
                enterPipWhenFrameReady()
            }
            return
        }
        currentLoadedUri = incomingUri
        pipOriginFromInline = intent.getBooleanExtra("enter_pip_immediately", false)

        // Bug fix: is Activity ke singleTask hone ki wajah se yahi instance baar baar reuse
        // hoti hai (Home se koi bhi naya/alag video khulne par bhi) — isliye pichle video ka
        // pinch-zoom/pan state yahan explicitly reset karna zaroori hai, warna agar kabhi
        // zoom-out (0.5x) kiya ho aur pan drag hua ho, wahi "half screen" state naye,
        // bilkul alag video par bhi carry ho jaati thi.
        resetVideoZoom()

        val videoUri = intent.getStringExtra("video_uri")
        val videoTitle = intent.getStringExtra("video_title") ?: "Video"
        playerTitleText.text = videoTitle

        // Web se bheji quality list parse karo (agar hai) — [{"url":"...","label":"480p"}, ...]
        availableQualities = try {
            val arr = JSONArray(intent.getStringExtra("video_qualities_json") ?: "[]")
            (0 until arr.length()).mapNotNull { i ->
                val obj = arr.optJSONObject(i) ?: return@mapNotNull null
                val url = obj.optString("url").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val label = obj.optString("label").takeIf { it.isNotBlank() } ?: url
                label to url
            }
        } catch (e: Exception) {
            emptyList()
        }

        // Playlist load karo (agar MainActivity se aayi hai), warna sirf current video ka
        // single-item fallback banao taaki app crash na ho (bug-fix: pehle koi fallback nahi tha)
        queue = if (PlaybackQueue.items.isNotEmpty()) PlaybackQueue.items else {
            if (videoUri != null) {
                listOf(VideoItem(0, videoTitle, videoUri, 0, 0, ""))
            } else emptyList()
        }
        queueStartIndex = PlaybackQueue.startIndex.coerceIn(0, (queue.size - 1).coerceAtLeast(0))

        // Video jaisa shoot hua hai (vertical/portrait ya horizontal/landscape) usi orientation
        // mein khulna chahiye — manifest se ab fixed "sensorLandscape" hata diya gaya hai. Yahan
        // stored width/height se turant ek guess laga dete hain (taaki launch par flash na ho),
        // aur ExoPlayer ka onVideoSizeChanged actual decoded size se ise confirm/correct karega.
        queue.getOrNull(queueStartIndex)?.let { applyOrientationForVideo(it.width, it.height) }

        // Chhote inline player se fullscreen mein handoff hote waqt exact position/playing
        // state bhi aati hai (MainActivity se) — taaki fullscreen mein wahi se resume ho,
        // shuru se dobara na chale.
        val resumePositionMs = intent.getLongExtra("resume_position_ms", -1L)
        val resumePlaying = intent.getBooleanExtra("resume_playing", true)

        val sharedPlayer = SharedPlayerHolder.player
        if (sharedPlayer != null && SharedPlayerHolder.uri == videoUri && resumePositionMs >= 0) {
            // Bug fix: pehle yahan hamesha ek NAYA ExoPlayer banta tha aur usi resume
            // position par seekTo() karta tha — matlab network se buffer scratch se
            // banta tha, isliye position match hone ke bawajood buffering dikhti thi.
            // Ab agar MainActivity ka player abhi bhi zinda hai aur wahi video chala
            // raha hai, to usi instance ko seedha yahan le lete hain — kuch bhi rebuild
            // nahi hota, playback bilkul seamlessly continue hoti hai.
            usingSharedPlayer = true
            player = sharedPlayer
            playerView.player = player
            playerView.setKeepContentOnPlayerReset(true)
            // Bug fix (duplicate listener -> seekbar jump): agar kisi wajah se yeh
            // branch dobara isi player instance ke liye hit ho (currentLoadedUri guard
            // ke bawajood), pehle purana listener hata do taaki wahi listener kabhi
            // 2x registered na ho — buildPlayer() mein bhi yahi pattern hai.
            player.removeListener(playerListener)
            player.removeAnalyticsListener(statsAnalyticsListener)
            player.addListener(playerListener)
            player.addAnalyticsListener(statsAnalyticsListener)
            player.setSeekParameters(SeekParameters.CLOSEST_SYNC)
            player.playWhenReady = resumePlaying
            // MainActivity ne handoff se pehle setForegroundMode(true) kiya tha
            // (surface-less gap ke dauraan decoder ko zinda/ready rakhne ke
            // liye) — ab genuinely visible Surface mil chuka hai, normal mode
            // mein wapas le aao.
            player.setForegroundMode(false)
        } else {
            buildPlayer(
                DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON,
                enableFallback = true,
                resumePositionMs = resumePositionMs,
                forcePlayWhenReady = if (resumePositionMs >= 0) resumePlaying else null
            )
        }

        // Chhote inline player ke PiP button se aaye the to yahan aate hi turant
        // real PiP mein chale jaao — isi Activity ka apna, already-working PiP.
        // enterPipWhenFrameReady() pehle frame render hone tak wait karta hai
        // (dekho uska comment) taaki shrink animation shuru hote hi video already
        // visible ho, black-frame flash na dikhe.
        if (intent.getBooleanExtra("enter_pip_immediately", false)) {
            pendingImmediatePipSourceRect = readPipSourceRectExtra()
            enterPipWhenFrameReady()
        }
    }

    // MainActivity se aaya chhote inline player ka on-screen rect (agar hai) parse
    // karta hai — dekho pendingImmediatePipSourceRect field ka comment.
    private fun readPipSourceRectExtra(): Rect? {
        val left = intent.getIntExtra("pip_src_left", Int.MIN_VALUE)
        if (left == Int.MIN_VALUE) return null
        val top = intent.getIntExtra("pip_src_top", Int.MIN_VALUE)
        val right = intent.getIntExtra("pip_src_right", Int.MIN_VALUE)
        val bottom = intent.getIntExtra("pip_src_bottom", Int.MIN_VALUE)
        if (top == Int.MIN_VALUE || right == Int.MIN_VALUE || bottom == Int.MIN_VALUE) return null
        if (right <= left || bottom <= top) return null
        return Rect(left, top, right, bottom)
    }

    // Bug fix: PlayerActivity ab manifest mein "singleTask" hai (see AndroidManifest), isliye
    // agar user PiP mein ek video chhod kar Home-screen se koi doosra video play karta hai,
    // Android nayi Activity banane ki jagah isi purani instance ko onNewIntent() ke through
    // wapas la kar deta hai. Pehle (standard launch mode ke saath) ek NAYI PlayerActivity ban
    // jaati thi — purani wali PiP mein zinda rehti (apna player bajaate hue) aur nayi bhi apna
    // alag player bajaane lagti, isliye dono ka audio/video ek saath chalta tha. Ab naya video
    // isi single player instance mein load hota hai, purana turant replace ho jaata hai.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        loadVideoFromIntent()
        // Agar chhote PiP window mein tha to wapas normal fullscreen UI dikhao — Android
        // task ko foreground mein laate hi khud PiP se bahar nikal deta hai, ye sirf UI
        // chrome ko turant sync rakhne ke liye extra safety hai.
        playerView.useController = true
        if (::topBar.isInitialized) showAllControls()
    }

    // Bug fix: pehle play/pause button sirf media3 ke automatic exo_play/exo_pause
    // id-binding par depend karta tha, jo custom controller_layout ke saath kabhi
    // kabhi reliably click register nahi karta tha - button dikhta tha lekin tap
    // karne par kuch hota nahi tha. Ab yahan explicit click listener + icon-sync
    // laga diya hai taaki play/pause hamesha kaam kare, chahe automatic binding
    // fail ho ya na ho.
    private var exoPlayButton: ImageButton? = null
    private var exoPauseButton: ImageButton? = null

    // Premium touch: play/pause aur lock/unlock jaise primary buttons ab tap
    // karte hi halka "press down" scale animation dete hain (iOS/premium apps
    // jaisa tactile micro-interaction) — sirf static icon tap na lagkar
    // button responsive/alive feel deta hai.
    private fun applyPressScale(view: View) {
        // Premium polish: pehle sirf scale-bounce tha, koi ripple nahi — flat
        // "custom" feel deta tha. Ab har button par ek halka golden Material
        // ripple bhi lagaya (naya @drawable/ripple_icon_button resource,
        // foreground ke through), taaki tap karte hi bilkul native Android/
        // YouTube jaisa ripple-glow + hamara scale-bounce dono ek saath
        // dikhein. Button ke apne background shape (bg_icon_circle waghera)
        // ko bilkul bhi chhedte nahi — sirf upar ek transparent ripple
        // foreground layer add hota hai.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP && view.foreground == null) {
            try {
                view.foreground = ContextCompat.getDrawable(this, R.drawable.ripple_icon_button)
            } catch (_: Exception) {}
        }
        view.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    v.animate().scaleX(0.85f).scaleY(0.85f).setDuration(100).start()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.animate().scaleX(1f).scaleY(1f).setDuration(120)
                        .setInterpolator(android.view.animation.OvershootInterpolator(2f)).start()
                }
            }
            false // event ko aage bhi propagate hone do taaki click listener normally chale
        }
    }

    private fun setupPlayPauseButton() {
        // Bug fix (build error): "nonTransitiveRClass=true" is gradle.properties mein
        // set hai, isliye exo_play/exo_pause jaise IDs jo media3-ui library ke andar
        // define hain, app ke apne R class (com.suhani.videoplayer.R) mein nahi aate -
        // isse "Unresolved reference: exo_play" build error aata tha. Fix: media3-ui
        // library ke apne R class se hi in ids ko refer karo.
        exoPlayButton = playerView.findViewById(androidx.media3.ui.R.id.exo_play)
        exoPauseButton = playerView.findViewById(androidx.media3.ui.R.id.exo_pause)
        exoPlayButton?.let { applyPressScale(it) }
        exoPauseButton?.let { applyPressScale(it) }

        exoPlayButton?.setOnClickListener {
            if (player.playbackState == Player.STATE_ENDED) {
                player.seekTo(0)
            }
            player.playWhenReady = true
            syncPlayPauseIcon()
        }
        exoPauseButton?.setOnClickListener {
            player.playWhenReady = false
            syncPlayPauseIcon()
        }

        syncPlayPauseIcon()
    }

    // Note: decoder-switch jaise features player ko rebuild karte hain (naya ExoPlayer
    // instance), isliye sync ka call yahan alag se player.addListener() na karke shared
    // playerListener ke andar hota hai (buildPlayer() har rebuild par usse dobara attach
    // karta hai) - warna icon-sync sirf pehle player instance ke liye kaam karta.
    private fun syncPlayPauseIcon() {
        if (!::player.isInitialized) return
        val showPause = player.playWhenReady &&
            player.playbackState != Player.STATE_ENDED &&
            player.playbackState != Player.STATE_IDLE
        animatePlayPauseSwap(showPause)
    }

    /**
     * Premium touch: timeline (exo_progress / DefaultTimeBar) ko drag karte waqt ab ek
     * chhota popup card dikhta hai jisme us exact position ka asli video frame + timestamp
     * hota hai — YouTube/MX Player Pro jaisa "seek karne se pehle preview dekh lo" feature.
     * Frame nikalna heavy operation hai isliye MediaMetadataRetriever background thread par
     * chalta hai (scrubPreviewExecutor), aur fast-drag ke dauraan sirf sabse latest request
     * ka result UI par apply hota hai (purane in-flight results discard).
     */
    private fun setupScrubPreview() {
        val timeBar = playerView.findViewById<androidx.media3.ui.DefaultTimeBar>(
            androidx.media3.ui.R.id.exo_progress
        ) ?: return

        timeBar.addListener(object : androidx.media3.ui.TimeBar.OnScrubListener {
            override fun onScrubStart(timeBar: androidx.media3.ui.TimeBar, position: Long) {
                scrubPreviewContainer.visibility = View.VISIBLE
                scrubPreviewContainer.alpha = 0f
                scrubPreviewContainer.animate().alpha(1f).setDuration(120).start()
                // Har naye scrub session ki shuruaat mein image dobara VISIBLE karo —
                // pichhli baar agar frame extraction fail hua tha (GONE ho gaya tha),
                // is baar naye position par phir se try karna chahiye.
                scrubPreviewImage.visibility = View.VISIBLE
                updateScrubPreview(position)
            }

            override fun onScrubMove(timeBar: androidx.media3.ui.TimeBar, position: Long) {
                updateScrubPreview(position)
            }

            override fun onScrubStop(timeBar: androidx.media3.ui.TimeBar, position: Long, canceled: Boolean) {
                scrubPreviewContainer.animate().alpha(0f).setDuration(120)
                    .withEndAction { scrubPreviewContainer.visibility = View.GONE }
                    .start()
            }
        })
    }

    private fun updateScrubPreview(positionMs: Long) {
        // Timestamp text turant update hota hai (cheap), sirf frame image background
        // thread se async aati hai.
        val m = positionMs / 60000
        val s = (positionMs / 1000) % 60
        scrubPreviewTime.text = String.format("%d:%02d", m, s)

        // Preview card ko timeline ke us fraction ke upar horizontally position karo,
        // taaki wo asli drag position ke saath align rahe (screen se bahar na jaaye).
        val duration = player.duration.takeIf { it > 0 } ?: 1L
        val fraction = (positionMs.toFloat() / duration.toFloat()).coerceIn(0f, 1f)
        val parentWidth = (scrubPreviewContainer.parent as? View)?.width ?: resources.displayMetrics.widthPixels
        val cardWidth = dp(128) + dp(12)
        val targetX = (parentWidth * fraction - cardWidth / 2f).coerceIn(dp(8).toFloat(), (parentWidth - cardWidth - dp(8)).toFloat())
        scrubPreviewContainer.translationX = targetX

        val uri = player.currentMediaItem?.mediaId ?: return
        val requestId = ++scrubPreviewRequestId
        scrubPreviewExecutor.execute {
            try {
                val retriever = if (scrubPreviewRetrieverUri == uri && scrubPreviewRetriever != null) {
                    scrubPreviewRetriever!!
                } else {
                    scrubPreviewRetriever?.release()
                    android.media.MediaMetadataRetriever().also {
                        it.setDataSource(this, Uri.parse(uri))
                        scrubPreviewRetriever = it
                        scrubPreviewRetrieverUri = uri
                    }
                }
                // BUG FIX (scrub preview = wrong/non-real-time frame): OPTION_CLOSEST_SYNC
                // sirf sabse nazdeeki KEYFRAME uthata hai, exact requested timestamp nahi —
                // ek GOP mein keyframes aksar 2-10s ke gap par hote hain, isliye preview
                // hamesha ek stale/nearby frame dikhata tha, jahan user asal mein drag kar
                // raha tha wahan ka nahi (e.g. 5:32 par drag karo to 5:28 wale keyframe ka
                // frame dikhta). Pehle sirf tabhi accurate OPTION_CLOSEST try hota jab SYNC
                // null return kare — jo valid videos par bahut kam hota hai, isliye yeh
                // fallback practically kabhi chalta hi nahi tha.
                // Fix: seedha OPTION_CLOSEST use karo — yeh exact requested frame ko decode
                // karta hai (thoda slower hai SYNC ke muqable, lekin single background
                // thread + sirf latest scrub request apply hone ki wajah se yeh kaafi
                // smooth rehta hai), taaki preview hamesha us exact position ka real frame
                // dikhaye jahan user drag kar raha hai.
                val frame = retriever.getFrameAtTime(
                    positionMs * 1000,
                    android.media.MediaMetadataRetriever.OPTION_CLOSEST
                )
                if (requestId == scrubPreviewRequestId) {
                    runOnUiThread {
                        if (requestId == scrubPreviewRequestId) {
                            if (frame != null) {
                                scrubPreviewImage.setImageBitmap(frame)
                            } else if (scrubPreviewImage.drawable == null) {
                                // Dono options fail hue aur abhi tak koi frame nahi
                                // dikha — ab plain black box dikhaane ke bajaye image
                                // hi chhupa do, sirf timestamp pill dikhta rahe. Ek
                                // black square se behtar hai ki kam dikhe par saaf ho.
                                scrubPreviewImage.visibility = View.GONE
                            }
                        }
                    }
                }
            } catch (_: Exception) {
                // Corrupt/unsupported stream par frame extraction fail ho sakta hai —
                // is case mein bas purana frame (ya blank) dikhta rehta hai, crash nahi hota.
            }
        }
    }

    // ---------------------------------------------------------------------
    // Smart Chapters: video ko background thread par sample karke scene-cuts
    // detect karta hai (frame-difference se), fir unhe DefaultTimeBar par
    // chhote markers ki tarah aur ek "Chapters" list mein dikhata hai — poori
    // tarah apna logic hai, kisi player se copy nahi.
    // ---------------------------------------------------------------------

    /** Current video ke liye chapters ready karta hai — pehli baar scan karta hai,
     *  agli baar cached result seedha dialog mein dikha deta hai. */
    private fun analyzeSceneChapters(showDialogWhenDone: Boolean) {
        if (!::player.isInitialized) return
        val uri = queue.getOrNull(player.currentMediaItemIndex)?.uriString ?: return
        val duration = player.duration
        if (duration <= 0) {
            showGestureFeedback("Thodi der ruk kar phir try karo")
            return
        }
        if (chapterAnalyzedUri == uri) {
            // Isi video ke liye pehle se scan ho chuka hai.
            if (showDialogWhenDone) showChaptersDialog()
            return
        }
        if (isAnalyzingChapters) {
            showGestureFeedback("Chapters scan ho rahe hain...")
            return
        }
        isAnalyzingChapters = true
        showGestureFeedback("Smart Chapters scan ho raha hai...")

        chapterAnalysisExecutor.execute {
            val markers = detectSceneChapters(uri, duration)
            runOnUiThread {
                isAnalyzingChapters = false
                chapterAnalyzedUri = uri
                chapterMarkersMs = markers.toMutableList()
                applyChapterMarkersToTimeBar()
                showGestureFeedback(
                    if (markers.isEmpty()) "Koi clear scene-cut nahi mila"
                    else "${markers.size} chapters mil gaye"
                )
                if (showDialogWhenDone) showChaptersDialog()
            }
        }
    }

    /** Background thread par chalta hai: video ko chhote-chhote intervals par sample
     *  karke consecutive frames ke beech "kitna badla" (avg grayscale diff) check karta
     *  hai — badi jump = scene cut = chapter marker. */
    private fun detectSceneChapters(uri: String, durationMs: Long): List<Long> {
        val markers = mutableListOf<Long>()
        var retriever: android.media.MediaMetadataRetriever? = null
        try {
            retriever = android.media.MediaMetadataRetriever()
            retriever.setDataSource(this, Uri.parse(uri))

            // Roughly har ~12s par ek sample, lekin bahut chhoti/lambi videos ke
            // liye sensible min/max range mein clamp kiya hua.
            val numSamples = (durationMs / 12_000L).toInt().coerceIn(6, 40)
            val intervalMs = durationMs / numSamples
            val sampleSide = 10 // 10x10 = 100 "pixels" ka chhota signature, kaafi hai scene-cut ke liye

            var prevSignature: FloatArray? = null
            for (i in 1 until numSamples) {
                val t = i * intervalMs
                val rawFrame = try {
                    retriever.getFrameAtTime(t * 1000, android.media.MediaMetadataRetriever.OPTION_CLOSEST)
                } catch (_: Exception) {
                    null
                } ?: continue

                val small = Bitmap.createScaledBitmap(rawFrame, sampleSide, sampleSide, true)
                val signature = FloatArray(sampleSide * sampleSide)
                var idx = 0
                for (y in 0 until sampleSide) {
                    for (x in 0 until sampleSide) {
                        val pixel = small.getPixel(x, y)
                        val r = (pixel shr 16) and 0xFF
                        val g = (pixel shr 8) and 0xFF
                        val b = pixel and 0xFF
                        signature[idx++] = (r + g + b) / 3f
                    }
                }
                if (small !== rawFrame) rawFrame.recycle()
                small.recycle()

                val prev = prevSignature
                if (prev != null) {
                    var diffSum = 0f
                    for (k in signature.indices) diffSum += Math.abs(signature[k] - prev[k])
                    val avgDiff = diffSum / signature.size
                    // Threshold experiment se tuned — normal pan/motion isse kam
                    // diff deta hai, ek asli scene-change isse zyada.
                    if (avgDiff > 26f) markers.add(t)
                }
                prevSignature = signature
            }
        } catch (_: Exception) {
            // Corrupt/unsupported stream — jo bhi markers mile hain wahi return ho jaayenge.
        } finally {
            try { retriever?.release() } catch (_: Exception) {}
        }
        return markers
    }

    /** DefaultTimeBar ke ad-marker API ko (re)use karke chapter cut-points ko chhoti
     *  lines ki tarah seekbar par dikhata hai. */
    private fun applyChapterMarkersToTimeBar() {
        val timeBar = playerView.findViewById<androidx.media3.ui.DefaultTimeBar>(
            androidx.media3.ui.R.id.exo_progress
        ) ?: return
        if (chapterMarkersMs.isEmpty()) {
            timeBar.setAdGroupTimesMs(null, null, 0)
            return
        }
        val times = chapterMarkersMs.toLongArray()
        val played = BooleanArray(times.size)
        timeBar.setAdGroupTimesMs(times, played, times.size)
    }

    /** Detected chapters ki list dikhata hai — tap karke seedha us scene par jump. */
    private fun showChaptersDialog() {
        if (chapterMarkersMs.isEmpty()) {
            showGestureFeedback("Koi chapter mark nahi hai")
            return
        }
        val labels = chapterMarkersMs.mapIndexed { index, ms ->
            val m = ms / 60000
            val s = (ms / 1000) % 60
            "Chapter ${index + 1}  •  ${String.format("%d:%02d", m, s)}"
        }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("Smart Chapters")
            .setItems(labels) { _, which ->
                player.seekTo(chapterMarkersMs[which])
                showGestureFeedback("Chapter ${which + 1} par gaye")
            }
            .setNegativeButton("Re-scan") { _, _ ->
                chapterAnalyzedUri = null
                analyzeSceneChapters(true)
            }
            .setPositiveButton("Band karo", null)
            .show()
    }

    // ---------------------------------------------------------------------
    // Speed Ramp: current speed se ek target speed tak N second mein
    // smoothly interpolate karta hai (gradual "fade", instant jump nahi).
    // ---------------------------------------------------------------------

    /** User se target speed + ramp duration poochta hai, fir shuru karta hai. */
    private fun showSpeedRampDialog() {
        val speedOptions = arrayOf("0.5x (Slow-mo)", "0.75x", "1.5x", "2.0x (Fast)")
        val speedValues = floatArrayOf(0.5f, 0.75f, 1.5f, 2.0f)
        val durationOptions = arrayOf("3 seconds", "5 seconds", "10 seconds", "20 seconds")
        val durationValuesMs = longArrayOf(3000L, 5000L, 10_000L, 20_000L)

        var chosenSpeedIdx = 1 // default 0.75x
        var chosenDurationIdx = 1 // default 5s

        AlertDialog.Builder(this)
            .setTitle("Speed Ramp — target speed")
            .setSingleChoiceItems(speedOptions, chosenSpeedIdx) { _, which -> chosenSpeedIdx = which }
            .setPositiveButton("Aage") { _, _ ->
                AlertDialog.Builder(this)
                    .setTitle("Speed Ramp — kitne second mein")
                    .setSingleChoiceItems(durationOptions, chosenDurationIdx) { _, which -> chosenDurationIdx = which }
                    .setPositiveButton("Start") { _, _ ->
                        startSpeedRamp(speedValues[chosenSpeedIdx], durationValuesMs[chosenDurationIdx])
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Current playback speed se targetSpeed tak durationMs mein linear ramp karta hai. */
    private fun startSpeedRamp(targetSpeed: Float, durationMs: Long) {
        if (!::player.isInitialized) return
        cancelSpeedRamp()
        val startSpeed = player.playbackParameters.speed.takeIf { it > 0f } ?: 1f
        val startTime = SystemClock.elapsedRealtime()
        val tickMs = 80L

        val runnable = object : Runnable {
            override fun run() {
                val elapsed = SystemClock.elapsedRealtime() - startTime
                val fraction = (elapsed.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
                val current = startSpeed + (targetSpeed - startSpeed) * fraction
                player.playbackParameters = PlaybackParameters(current)
                if (fraction < 1f) {
                    speedRampHandler.postDelayed(this, tickMs)
                } else {
                    speedRampRunnable = null
                    showGestureFeedback("Speed Ramp complete: ${String.format("%.2f", targetSpeed)}x")
                }
            }
        }
        speedRampRunnable = runnable
        showGestureFeedback("Speed Ramp: ${String.format("%.2f", startSpeed)}x -> ${String.format("%.2f", targetSpeed)}x")
        speedRampHandler.post(runnable)
    }

    /** Chal rahi speed-ramp ko rok deta hai (naya video load hone par ya user cancel kare to). */
    private fun cancelSpeedRamp() {
        speedRampRunnable?.let { speedRampHandler.removeCallbacks(it) }
        speedRampRunnable = null
    }


    // shrink+fade ho kar gayab hota hai, incoming icon chhota shuru ho kar
    // halka overshoot ke saath apni asli size par settle hota hai.
    private fun animatePlayPauseSwap(showPause: Boolean) {
        val incoming = if (showPause) exoPauseButton else exoPlayButton
        val outgoing = if (showPause) exoPlayButton else exoPauseButton
        if (incoming == null || outgoing == null) {
            exoPlayButton?.visibility = if (showPause) View.GONE else View.VISIBLE
            exoPauseButton?.visibility = if (showPause) View.VISIBLE else View.GONE
            return
        }
        outgoing.animate().cancel()
        incoming.animate().cancel()

        if (outgoing.visibility == View.VISIBLE) {
            outgoing.animate()
                .alpha(0f).scaleX(0.6f).scaleY(0.6f)
                .setDuration(130)
                .withEndAction {
                    outgoing.visibility = View.GONE
                    outgoing.alpha = 1f
                    outgoing.scaleX = 1f
                    outgoing.scaleY = 1f
                }
                .start()
        }

        incoming.visibility = View.VISIBLE
        incoming.alpha = 0f
        incoming.scaleX = 0.6f
        incoming.scaleY = 0.6f
        incoming.animate()
            .alpha(1f).scaleX(1f).scaleY(1f)
            .setDuration(210)
            .setInterpolator(android.view.animation.OvershootInterpolator(2.2f))
            .start()
    }

    // ---------------------------------------------------------------------
    // Player build / rebuild (decoder mode change ke liye player rebuild hota hai
    // kyunki DefaultRenderersFactory sirf construction time par set hoti hai)
    // ---------------------------------------------------------------------
    private fun buildPlayer(extensionMode: Int, enableFallback: Boolean, resumePositionMs: Long = -1L, resumeIndex: Int = -1, forcePlayWhenReady: Boolean? = null) {
        val wasPlaying = forcePlayWhenReady ?: (if (::player.isInitialized) player.playWhenReady else true)
        // Bug fix: pehle rebuild (jaise decoder-error se auto-recovery, ya manual decoder
        // switch) ek NAYA ExoPlayer banata tha jiski apni fresh/default trackSelectionParameters
        // hoti thi — isliye user ki chuni hui "Hindi" audio track (ya koi bhi manual audio/
        // subtitle selection) silently reset ho jaati thi. Upar se dikhta tha "error chala gaya"
        // lekin asal mein wo track ab select hi nahi thi. Ab purani selection ko capture karke
        // naye player par dobara apply karte hain, taaki rebuild ke baad bhi wahi track chalti rahe.
        val previousTrackSelectionParameters = if (::player.isInitialized) player.trackSelectionParameters else null
        if (::player.isInitialized) {
            player.removeListener(playerListener)
            player.removeAnalyticsListener(statsAnalyticsListener)
            if (usingSharedPlayer) {
                // Yeh instance abhi tak MainActivity ke chhote (inline) player ke
                // saath shared thi — release NAHI karni, warna wapas jaate hi wahan
                // bhi crash/black screen ho jaata. Bas detach karo; is point ke aage
                // (decoder switch) PlayerActivity apna exclusive naya player banata hai.
                if (SharedPlayerHolder.player === player) SharedPlayerHolder.clear()
                usingSharedPlayer = false
            } else {
                player.release()
            }
            captureAudioFxState()
            releaseAudioFx()
        }

        // FfmpegRenderersFactory: humara apna (Apache-2.0, LGPL-only FFmpeg codecs wala) drop-in
        // replacement — AC3/EAC3/DTS/TrueHD jaisi audio tracks ab bina GPL risk ke wapas chalengi.
        // Isko use karne se pehle FfmpegRenderersFactory.kt add karo AUR self-built
        // media3-ffmpeg-decoder.aar ko app/libs/ mein daalo (workflow instructions dekho).
        val renderersFactory = FfmpegRenderersFactory(this, eqProcessor)
            .setExtensionRendererMode(extensionMode)
            .setEnableDecoderFallback(enableFallback)

        // MX Player jaisa smooth playback ke liye buffering tune ki:
        // - kam "min buffer" taaki video jaldi start ho (fast-start)
        // - zyada "max buffer" taaki network hiccup mein bhi rebuffer kam ho
        // - rebuffer ke baad dubara chalne ke liye chhota buffer (turant resume)
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                15_000,   // minBufferMs
                50_000,   // maxBufferMs
                // Bug fix: 500ms itna kam tha ki bade/high-bitrate videos mein playback shuru
                // hote hi turant dobara buffering mein chala jaata tha (read/decode itni jaldi
                // itna data jama nahi kar paate the). Thoda badhaya taaki bade files bhi
                // smoothly start hon, chhote files par fark mushkil se noticeable hoga.
                1_500,    // bufferForPlaybackMs
                2_500     // bufferForPlaybackAfterRebufferMs
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        // Bug fix (buffering slow): pehle koi custom DataSource nahi tha, matlab bilkul
        // default 8s HTTP timeout, cross-protocol redirect band, aur koi disk cache nahi —
        // seek peeche karo ya video dubara kholo, har baar poora dubara download hota tha.
        // PlayerNetwork ab tuned timeouts + cache-backed DataSource deta hai (dekho
        // PlayerNetwork.kt), jo fullscreen aur inline/mini player dono share karte hain.
        val mediaSourceFactory = DefaultMediaSourceFactory(this)
            .setDataSourceFactory(PlayerNetwork.dataSourceFactory(this))

        player = ExoPlayer.Builder(this, renderersFactory)
            .setLoadControl(loadControl)
            .setMediaSourceFactory(mediaSourceFactory)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                /* handleAudioFocus= */ true
            )
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(10_000)
            // Bug fix: yeh set hi nahi tha, isliye headphone/Bluetooth unplug/disconnect
            // hone par video/audio turant loud speaker par bajne lagta tha (koi auto-pause
            // nahi). Ab OS "becoming noisy" broadcast par ExoPlayer khud playWhenReady=false
            // kar dega.
            .setHandleAudioBecomingNoisy(true)
            .build()

        if (previousTrackSelectionParameters != null) {
            player.trackSelectionParameters = previousTrackSelectionParameters
        }

        // Fast/approximate seek (double-tap ±10s) taaki seek bar/gesture turant respond kare,
        // exact-frame seek ka thoda extra wait na lage.
        player.setSeekParameters(SeekParameters.CLOSEST_SYNC)

        playerView.player = player
        // Video switch (next/previous) hone par black-flash na aaye, purana frame tab tak
        // dikhta rahe jab tak agla frame ready nahi ho jaata.
        playerView.setKeepContentOnPlayerReset(true)
        player.addListener(playerListener)
        // Stats for Nerds: dropped-frame count ka source. AnalyticsListener rebuild ke
        // baad bhi dobara attach hota hai (playerListener ki tarah), warna decoder-switch
        // ke baad stats stale/zero reh jaate.
        player.addAnalyticsListener(statsAnalyticsListener)

        if (queue.isNotEmpty()) {
            val mediaItems = queue.map { video ->
                MediaItem.Builder()
                    .setUri(Uri.parse(video.uriString))
                    .setMediaId(video.uriString)
                    .setMediaMetadata(MediaMetadata.Builder().setTitle(video.title).build())
                    .build()
            }
            val startIdx = if (resumeIndex >= 0) resumeIndex else queueStartIndex
            player.setMediaItems(mediaItems, startIdx.coerceIn(0, mediaItems.size - 1), 0L)
            player.prepare()

            if (resumePositionMs >= 0) {
                player.seekTo(startIdx, resumePositionMs)
                player.playWhenReady = wasPlaying
            } else {
                val currentUri = queue[startIdx].uriString
                val prefs = getSharedPreferences("playback_positions", MODE_PRIVATE)
                val savedPosition = prefs.getLong(currentUri, 0L)
                if (savedPosition > 5000) {
                    showResumeDialog(savedPosition)
                } else {
                    player.playWhenReady = true
                }
            }
        }
    }

    /** Stats for Nerds: sirf dropped-frame count accumulate karta hai, baaki stats
     *  (resolution/codec/bitrate) directly player.videoFormat/audioFormat se milte hain. */
    private val statsAnalyticsListener = object : androidx.media3.exoplayer.analytics.AnalyticsListener {
        override fun onDroppedVideoFrames(
            eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime,
            droppedFrames: Int,
            elapsedMs: Long
        ) {
            totalDroppedFrames += droppedFrames
        }
    }

    private val playerListener = object : Player.Listener {
        override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
            updateVideoInfoBadge()
            applyOrientationForVideo(videoSize.width, videoSize.height, videoSize.unappliedRotationDegrees)
            refreshPipParams()
        }

        override fun onAudioSessionIdChanged(audioSessionId: Int) {
            // Effects ko turant attach karo jaise hi actual audio session mil jaaye —
            // dialog khulne tak wait mat karo. Ye fix karta hai woh case jahan switch
            // ON karne par bhi sound nahi badalta tha, kyunki effect kabhi live audio
            // session se juda hi nahi tha (ya purane/dead session se juda reh gaya tha).
            if (audioSessionId != C.AUDIO_SESSION_ID_UNSET) {
                setupAudioFx(audioSessionId)
            }
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            playerTitleText.text = mediaItem?.mediaMetadata?.title ?: "Video"
            // Bug fix: naye video par purane A-B repeat points carry-over nahi hone chahiye
            abState = 0
            pointA = -1L
            pointB = -1L
            abRepeatLabel.text = "A-B Repeat"
            abRepeatButton.setImageResource(R.drawable.ic_ab_repeat)
            setToggleActive(abRepeatButton, false)
            // Naye video par purana pinch-zoom/pan bhi carry-over nahi hona chahiye.
            resetVideoZoom()
            // Naye video par purani auto-subtitle attempt, dual-subtitle (purani file
            // ki), aur purani subtitle-loaded state — teeno carry-over nahi honi chahiye
            // (subtitleLoaded pehle yahan reset hi nahi hota tha, isliye playlist mein
            // agli video par bhi "subtitle already loaded" maan liya jaata tha).
            autoSubtitleAttemptedForCurrentItem = false
            dualSubtitleController?.clear()
            subtitleLoaded = false

            // Naya episode/video: is series (folder) ke liye pehle se koi marked
            // intro-range hai to load karo, aur dropped-frame counter reset karo.
            resetIntroSkipForCurrentItem()
            totalDroppedFrames = 0

            // Naye video par purane Smart Chapters markers aur Speed Ramp
            // carry-over nahi hone chahiye — har video ka apna scan hota hai.
            chapterMarkersMs = mutableListOf()
            chapterAnalyzedUri = null
            applyChapterMarkersToTimeBar()
            cancelSpeedRamp()
        }

        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
            val resumePos = player.currentPosition
            val resumeIdx = player.currentMediaItemIndex

            when (error.errorCode) {
                // HW decoder us track (video codec ya audio codec jaisे AC3/DTS/TrueHD) ko
                // handle nahi kar paaya. Agla zyada-compatible decoder mode try karo (HW+, phir
                // SW/FFmpeg) — usi video ko usi position se dobara start karo, poora restart
                // ya crash nahi.
                androidx.media3.common.PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
                androidx.media3.common.PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
                androidx.media3.common.PlaybackException.ERROR_CODE_DECODING_FAILED,
                androidx.media3.common.PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
                androidx.media3.common.PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES -> {
                    if (decoderMode < 2) {
                        decoderMode += 1
                        decoderButton.text = when (decoderMode) {
                            1 -> "HW+"
                            else -> "SW"
                        }
                        val mode = when (decoderMode) {
                            1 -> DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON
                            else -> DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER
                        }
                        showPlayerSnackbar(
                            "Is track ke liye HW decoder support nahi tha, software decoder try kiya jaa raha hai\u2026"
                        )
                        buildPlayer(mode, enableFallback = true, resumePositionMs = resumePos, resumeIndex = resumeIdx)
                    } else {
                        // HW, HW+, aur SW (FFmpeg) — teeno decoder mode try ho chuke aur
                        // fir bhi fail — ab is file ko "corrupt" tag kar do taaki Home
                        // grid mein dobara khole bina hi pata chal jaaye.
                        markCurrentFileCorrupt(error.errorCodeName)
                        showPlayerSnackbar(
                            "Ye file is device par play nahi ho paa rahi: ${error.errorCodeName}",
                            isError = true
                        )
                    }
                }

                // Container/manifest hi malformed hai (file truncated/damaged download,
                // bit-rot, waghera) — decoder switch karne se koi fayda nahi, seedha corrupt tag.
                androidx.media3.common.PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
                androidx.media3.common.PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED -> {
                    markCurrentFileCorrupt(error.errorCodeName)
                    showPlayerSnackbar(
                        "Ye file corrupt/damaged lag rahi hai: ${error.errorCodeName}",
                        isError = true
                    )
                }

                // Network/storage se data padhte waqt aata hai — kabhi kabhi ek chhota glitch
                // (jaise slow storage, momentary I/O hiccup) hota hai jo dobara try karne par
                // theek ho jaata hai. Isliye seedha user ko error dikhaane se pehle chup-chaap
                // 1-2 baar retry karte hain.
                androidx.media3.common.PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
                androidx.media3.common.PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                androidx.media3.common.PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
                androidx.media3.common.PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE -> {
                    if (ioRetryCount < maxIoRetries) {
                        ioRetryCount += 1
                        controlsHandler.postDelayed({
                            if (::player.isInitialized) {
                                player.prepare()
                                player.seekTo(resumeIdx, resumePos)
                                player.playWhenReady = true
                            }
                        }, 800L)
                    } else {
                        val causeDetail = generateSequence(error.cause) { it.cause }
                            .firstOrNull { !it.message.isNullOrBlank() }
                            ?.message
                        val detailSuffix = if (causeDetail != null) "\n($causeDetail)" else ""
                        showPlayerSnackbar(
                            "Ye file load nahi ho paa rahi (I/O error). File move/delete to nahi ho gayi?$detailSuffix",
                            isError = true
                        )
                    }
                }

                else -> {
                    showPlayerSnackbar("Playback error: ${error.errorCodeName}", isError = true)
                }
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            syncPlayPauseIcon()
            rescheduleControlsHideIfVisible()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            syncPlayPauseIcon()
            if (playbackState == Player.STATE_READY) {
                ioRetryCount = 0
                maybeAutoSearchSubtitle()
            }
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            syncPlayPauseIcon()
            rescheduleControlsHideIfVisible()
        }
    }

    /** Current media item ko CorruptStore mein tag karta hai — Home grid isko agli baar
     *  scan par "Corrupt" badge ke saath dikhayega. */
    private fun markCurrentFileCorrupt(reason: String) {
        if (!::player.isInitialized) return
        val currentItem = queue.getOrNull(player.currentMediaItemIndex) ?: return
        CorruptStore.markCorrupt(this, currentItem.uriString, reason)
    }

    // Bug fix: agar controls already visible hain aur user pause/play karta hai,
    // to purane 3s/10s timer ko turant naye sahi timeout (pause=10s, play=3s) se
    // dobara start karo — warna pause karte hi bhi purana 3s timer chalta rehta.
    private fun rescheduleControlsHideIfVisible() {
        if (isLocked || !::topBar.isInitialized) return
        if (areControlsVisible()) {
            controlsHandler.removeCallbacks(hideControlsRunnable)
            controlsHandler.postDelayed(hideControlsRunnable, currentHideTimeoutMs())
        }
    }

    // ---------------------------------------------------------------------
    // Batch 1 (existing) features
    // ---------------------------------------------------------------------
    private fun setupBatch1Features() {
        aspectRatioButton.setOnClickListener { cycleAspectRatio() }
        bottomAspectButton.setOnClickListener { cycleAspectRatio() }

        screenshotButton.setOnClickListener { takeScreenshot() }

        abRepeatButton.setOnClickListener {
            when (abState) {
                0 -> {
                    pointA = player.currentPosition
                    abState = 1
                    abRepeatLabel.text = "Set B"
                    abRepeatButton.setImageResource(R.drawable.ic_ab_point_a)
                    setToggleActive(abRepeatButton, true)
                    showGestureFeedback("Point A set")
                }
                1 -> {
                    val posB = player.currentPosition
                    if (posB > pointA) {
                        pointB = posB
                        abState = 2
                        abRepeatLabel.text = "Repeat ON"
                        abRepeatButton.setImageResource(R.drawable.ic_ab_repeat_on)
                        setToggleActive(abRepeatButton, true)
                        showGestureFeedback("A-B Repeat ON")
                    } else {
                        showGestureFeedback("B, A ke baad hona chahiye")
                    }
                }
                else -> {
                    abState = 0
                    pointA = -1L
                    pointB = -1L
                    abRepeatLabel.text = "A-B Repeat"
                    abRepeatButton.setImageResource(R.drawable.ic_ab_repeat)
                    setToggleActive(abRepeatButton, false)
                    showGestureFeedback("A-B Repeat OFF")
                }
            }
        }

        muteButton.setOnClickListener {
            isMuted = !isMuted
            if (isMuted) {
                volumeBeforeMute = if (player.volume > 0f) player.volume else 1f
                player.volume = 0f
                muteButton.setImageResource(R.drawable.ic_volume_off)
                muteLabel.text = "Unmute"
                setToggleActive(muteButton, true)
                showGestureFeedback("Muted")
            } else {
                player.volume = volumeBeforeMute
                muteButton.setImageResource(R.drawable.ic_volume_up)
                muteLabel.text = "Mute"
                setToggleActive(muteButton, false)
                showGestureFeedback("Unmuted")
            }
        }

        rotateButton.setOnClickListener {
            requestedOrientation = if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
                ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            } else {
                ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            }
        }
    }

    /**
     * Video jis shape mein hai (vertical/portrait ya horizontal/landscape) usi mein screen ko
     * lock karta hai — "unappliedRotationDegrees" ka use karke un videos ko bhi sahi handle
     * karta hai jinke frame data landscape store hote hain lekin 90/270 rotation metadata ke
     * saath actually portrait dikhte hain (common jab phone se vertical shoot kiya gaya ho).
     * SENSOR_* variants use kiye hain taaki us orientation family ke andar device tilt karne par
     * bhi (upside-down waghera) normal sensor-rotation kaam karta rahe.
     */
    private fun applyOrientationForVideo(width: Int, height: Int, unappliedRotationDegrees: Int = 0) {
        if (width <= 0 || height <= 0) return
        // Bug fix (user report: "PiP se pehle full screen player dikhta hai"):
        // agar yeh session seedha inline player se PiP mein jaane wala hai, to
        // yahan requestedOrientation badalna (portrait se landscape, video ke
        // hisaab se) khud apna ek poora device-rotation animation trigger karta
        // — jo activity-launch ki 0ms/invisible transition se bilkul alag,
        // ROKA nahi ja sakta — result: user ko ek pal ke liye poori screen
        // ghoomti/full-size dikhti, TABHI PiP shrink shuru hoti. PiP window
        // apna aspect ratio khud (buildPipParams) handle karta hai, isko
        // Activity ke screen-orientation lock ki zaroorat hi nahi — isliye is
        // case mein orientation-force poora skip kar do.
        if (pipOriginFromInline) return
        val rotated = unappliedRotationDegrees == 90 || unappliedRotationDegrees == 270
        val displayWidth = if (rotated) height else width
        val displayHeight = if (rotated) width else height
        requestedOrientation = if (displayHeight > displayWidth) {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        } else {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
    }

    /** Har aspect ratio mode ka apna icon return karta hai. */
    private fun iconForResizeMode(mode: Int): Int = when (mode) {
        AspectRatioFrameLayout.RESIZE_MODE_FIXED_WIDTH -> R.drawable.ic_aspect_fixed_width
        AspectRatioFrameLayout.RESIZE_MODE_FIXED_HEIGHT -> R.drawable.ic_aspect_fixed_height
        AspectRatioFrameLayout.RESIZE_MODE_FILL -> R.drawable.ic_aspect_fill
        AspectRatioFrameLayout.RESIZE_MODE_ZOOM -> R.drawable.ic_aspect_zoom
        else -> R.drawable.ic_aspect_ratio
    }

    /** Aspect ratio cycle karta hai; quick-action button aur More menu dono se call hota hai. */
    private fun cycleAspectRatio() {
        resizeModeIndex = (resizeModeIndex + 1) % resizeModes.size
        playerView.resizeMode = resizeModes[resizeModeIndex]
        aspectRatioButton.setImageResource(iconForResizeMode(resizeModes[resizeModeIndex]))
        val label = when (resizeModes[resizeModeIndex]) {
            AspectRatioFrameLayout.RESIZE_MODE_FIT -> "Fit"
            AspectRatioFrameLayout.RESIZE_MODE_FIXED_WIDTH -> "Fixed Width"
            AspectRatioFrameLayout.RESIZE_MODE_FIXED_HEIGHT -> "Fixed Height"
            AspectRatioFrameLayout.RESIZE_MODE_FILL -> "Fill"
            AspectRatioFrameLayout.RESIZE_MODE_ZOOM -> "Zoom"
            else -> "Fit"
        }
        showGestureFeedback("Aspect: $label")
    }

    /** Toggle button ka background badalta hai: ON hone par gold accent circle, OFF par normal circle. */
    private fun setToggleActive(view: ImageView, active: Boolean) {
        view.background = androidx.core.content.ContextCompat.getDrawable(
            this,
            if (active) R.drawable.bg_icon_circle_accent else R.drawable.bg_icon_circle
        )
    }

    /** Repeat mode ke hisab se sahi icon return karta hai (Off / One / All ke liye alag icon). */
    private fun iconForRepeatMode(mode: Int): Int = when (mode) {
        Player.REPEAT_MODE_ONE -> R.drawable.ic_repeat_one
        Player.REPEAT_MODE_ALL -> R.drawable.ic_loop
        else -> R.drawable.ic_repeat_off
    }

    // ---------------------------------------------------------------------
    // Batch 2 (naye) features
    // ---------------------------------------------------------------------
    private fun setupBatch2Features() {

        // Night Mode: warm/dim overlay taaki late-night dekhne mein aankhon par kam pade
        // Icon mode ke hisab se badalta hai: ON par chand, OFF par sun
        nightModeButton.setImageResource(if (nightModeOn) R.drawable.ic_night_mode else R.drawable.ic_day_mode)
        setToggleActive(nightModeButton, nightModeOn)
        nightModeButton.setOnClickListener {
            nightModeOn = !nightModeOn
            nightModeOverlay.visibility = if (nightModeOn) View.VISIBLE else View.GONE
            nightModeButton.setImageResource(if (nightModeOn) R.drawable.ic_night_mode else R.drawable.ic_day_mode)
            setToggleActive(nightModeButton, nightModeOn)
            showGestureFeedback(if (nightModeOn) "Night Mode ON" else "Night Mode OFF")
        }

        // Shuffle: poori playlist (same folder) shuffle ho jayegi
        // Icon state ke hisab se badalta hai: ON par crossed shuffle arrows, OFF par sequential arrows
        shuffleButton.setImageResource(if (player.shuffleModeEnabled) R.drawable.ic_shuffle else R.drawable.ic_shuffle_off)
        setToggleActive(shuffleButton, player.shuffleModeEnabled)
        shuffleButton.setOnClickListener {
            player.shuffleModeEnabled = !player.shuffleModeEnabled
            shuffleButton.setImageResource(if (player.shuffleModeEnabled) R.drawable.ic_shuffle else R.drawable.ic_shuffle_off)
            setToggleActive(shuffleButton, player.shuffleModeEnabled)
            showGestureFeedback(if (player.shuffleModeEnabled) "Shuffle ON" else "Shuffle OFF")
        }

        // Loop: Off -> Repeat One -> Repeat All -> Off
        // Har state ka apna icon: Off = slashed loop, One = loop with "1", All = normal loop
        loopButton.setImageResource(iconForRepeatMode(player.repeatMode))
        setToggleActive(loopButton, player.repeatMode != Player.REPEAT_MODE_OFF)
        loopButton.setOnClickListener {
            player.repeatMode = when (player.repeatMode) {
                Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ONE
                Player.REPEAT_MODE_ONE -> Player.REPEAT_MODE_ALL
                else -> Player.REPEAT_MODE_OFF
            }
            loopButton.setImageResource(iconForRepeatMode(player.repeatMode))
            setToggleActive(loopButton, player.repeatMode != Player.REPEAT_MODE_OFF)
            val label = when (player.repeatMode) {
                Player.REPEAT_MODE_ONE -> "Repeat One"
                Player.REPEAT_MODE_ALL -> "Repeat All"
                else -> "Repeat Off"
            }
            showGestureFeedback(label)
        }

        sleepTimerButton.setOnClickListener { showSleepTimerDialog() }

        subtitleButton.setOnClickListener { showSubtitleMenu() }

        equalizerButton.setOnClickListener { showEqualizerDialog() }

        // Bug fix: pehle ye button sirf "jald aa raha hai" Toast dikhata tha. Ab real Cast SDK
        // (CastOptionsProvider + MediaRouteButton) se device chooser khulta hai aur network
        // stream URL (Streams tab wale http/https links) ko Chromecast par actually load karta
        // hai. Local storage wali files cast nahi ho sakti — Chromecast unhe direct fetch nahi
        // kar sakta (iske liye phone par local HTTP server chahiye, jo is app mein nahi hai);
        // us case mein button clear message dikhata hai, silently fail nahi hota.
        castButton.setOnClickListener { openCastChooser() }

        decoderButton.setOnClickListener { showDecoderDialog() }

        moreButton.setOnClickListener { showMoreMenu() }

        // Bug fix: More menu ka "BG Play" button pehle purane audio-only
        // Background Play (screen off par sirf sound chalta tha) ko toggle
        // karta tha. Ab yeh bhi corner wale PiP button jaisa hi — click karte
        // hi asli Picture-in-Picture floating window khulega.
        backgroundPlayLabel.text = "PiP"
        backgroundPlayButton.setImageResource(R.drawable.ic_pip)
        backgroundPlayButton.setOnClickListener {
            if (!tryEnterPipOnBack()) {
                showGestureFeedback("PiP is not supported on this device")
            }
        }

        // Corner wala PiP button bhi wahi floating window kholta hai jo back
        // dabane par khulta hai.
        bottomPipButton.setImageResource(R.drawable.ic_pip)
        bottomPipButton.setOnClickListener {
            if (!tryEnterPipOnBack()) {
                showGestureFeedback("PiP is not supported on this device")
            }
        }

        // Swipe-down gesture -> PiP. Sirf topContainer (title/icon strip)
        // se — video-surface (playerView) ka apna touch listener pehle se
        // volume/brightness/seek gestures handle karta hai, wahan add karne
        // se un sabse conflict hota, isliye yeh alag, safe zone mein hai.
        topContainer.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    pipSwipeStartY = event.rawY
                    pipSwipeStartX = event.rawX
                    pipSwipeDragging = false
                    false // let child buttons (back/pip/audio/subtitle/etc.) still get their taps
                }
                MotionEvent.ACTION_MOVE -> {
                    val dy = event.rawY - pipSwipeStartY
                    val dx = Math.abs(event.rawX - pipSwipeStartX)
                    if (dy > 24 && dy > dx) {
                        pipSwipeDragging = true
                        // Live shrink+fade feedback while dragging down.
                        val progress = (dy / 260f).coerceIn(0f, 1f)
                        v.translationY = dy.coerceAtMost(260f)
                        v.alpha = 1f - (progress * 0.4f)
                        v.scaleX = 1f - (progress * 0.06f)
                        v.scaleY = 1f - (progress * 0.06f)
                    }
                    pipSwipeDragging
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val wasDragging = pipSwipeDragging
                    val dy = event.rawY - pipSwipeStartY
                    pipSwipeDragging = false
                    if (wasDragging && dy > 90) {
                        // Instant reset (not animated) right before the real system
                        // PiP shrink kicks in — animating back to full size at the
                        // same time as the system's own shrink animation caused a
                        // visible snap/flash. See same fix on the video-surface
                        // swipe handler below for the reasoning.
                        v.translationY = 0f
                        v.alpha = 1f
                        v.scaleX = 1f
                        v.scaleY = 1f
                        if (!tryEnterPipOnBack()) {
                            showGestureFeedback("PiP is not supported on this device")
                        }
                    } else {
                        v.animate().translationY(0f).alpha(1f).scaleX(1f).scaleY(1f).setDuration(180).start()
                    }
                    wasDragging
                }
                else -> false
            }
        }

        audioTrackButton.setOnClickListener { showAudioTrackDialog() }

        // Feature dock ab right-edge par vertical floating hai: sirf 4 icons
        // (Cast/EQ/Screenshot/Rotate) hamesha dikhte hain, baaki (A-B Repeat,
        // Aspect, BG Play, Night Mode, Shuffle, Loop, Mute, Sleep Timer, Frame
        // Step) is chevron par tap karne ke baad 2-column grid mein neeche
        // expand hote hain — MX ke top horizontal-row wale pattern se alag.
        expandActionsButton.setOnClickListener {
            val expand = extraQuickActions.visibility != View.VISIBLE
            extraQuickActions.visibility = if (expand) View.VISIBLE else View.GONE
            // rotation: 270deg = "v" (neeche, expand karo) jab collapsed,
            // 90deg = "^" (upar, collapse karo) jab expanded.
            expandActionsButton.animate().rotation(if (expand) 90f else 270f).setDuration(150).start()
        }
    }

    // ---------------------------------------------------------------------
    // Batch 3 (naye) features: Ambient Glow, Stats for Nerds, Reach Mode,
    // Auto Intro/Recap Skip
    // ---------------------------------------------------------------------
    private fun setupBatch3Features() {

        // --- Ambient Glow Mode ---
        val featurePrefs = getSharedPreferences("feature_prefs", MODE_PRIVATE)
        // User request: Ambient Glow ab default-ON hai (YouTube jaisa) — user
        // jab chaahe usi toggle button se band kar sakta hai, waisa hi jaisa
        // pehle tha, sirf starting default badla hai.
        ambientGlowOn = featurePrefs.getBoolean("ambient_glow_on", true)
        ambientGlowView.setGlowEnabled(ambientGlowOn)
        setToggleActive(ambientGlowButton, ambientGlowOn)
        ambientGlowButton.setOnClickListener {
            ambientGlowOn = !ambientGlowOn
            ambientGlowView.setGlowEnabled(ambientGlowOn)
            setToggleActive(ambientGlowButton, ambientGlowOn)
            featurePrefs.edit().putBoolean("ambient_glow_on", ambientGlowOn).apply()
            showGestureFeedback(if (ambientGlowOn) "Ambient Glow ON" else "Ambient Glow OFF")
        }

        // --- Stats for Nerds ---
        statsForNerdsButton.setOnClickListener {
            statsForNerdsOn = !statsForNerdsOn
            statsForNerdsOverlay.visibility = if (statsForNerdsOn) View.VISIBLE else View.GONE
            setToggleActive(statsForNerdsButton, statsForNerdsOn)
            if (statsForNerdsOn) updateStatsForNerdsOverlay()
            showGestureFeedback(if (statsForNerdsOn) "Stats for nerds ON" else "Stats for nerds OFF")
        }

        // --- One-Handed Reach Mode ---
        // Tap-tap-tap cycle: Off -> compact bottom-right -> compact bottom-left -> Off.
        // Dono haathon (left/right) ke liye kaam karta hai, bade phones/tablets par
        // seekbar+play/pause/lock jaisa main control-row thumb ki reach mein le aata hai.
        reachModeButton.setOnClickListener {
            reachModeState = (reachModeState + 1) % 3
            applyReachMode(reachModeState)
            setToggleActive(reachModeButton, reachModeState != 0)
            val label = when (reachModeState) {
                1 -> "One-Handed Reach: Right"
                2 -> "One-Handed Reach: Left"
                else -> "One-Handed Reach: Off"
            }
            showGestureFeedback(label)
        }

        // --- Auto Intro/Recap Skip: mark intro start/end for the current series ---
        markIntroButton.setOnClickListener { onMarkIntroClicked() }
        skipIntroButton.setOnClickListener {
            val range = introRangeForCurrent
            if (range != null && ::player.isInitialized) {
                player.seekTo(range.second)
                skipIntroButton.visibility = View.GONE
                introSkipShown = false
                showGestureFeedback("Intro skipped")
            }
        }
    }

    /**
     * Ambient Glow: TextureView (surface_type="texture_view" activity_player.xml mein
     * already set hai) se ek chhota downsampled bitmap nikaal kar uske 4 edges (top row/
     * bottom row/left col/right col) ka average color compute karta hai, phir Ambient
     * Glow view ko naye colors bhejta hai (jo khud smoothly crossfade karta hai).
     *
     * TextureView.getBitmap() DRM-protected content par blank/black de sakta hai — us
     * case mein bhi crash nahi hota, bas glow static/dim reh jaata hai (safe fallback).
     */
    private fun sampleAmbientGlowColors() {
        try {
            val textureView = playerView.videoSurfaceView as? android.view.TextureView ?: return
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

            val topColor = avgColor(topR, topG, topB, w)
            val bottomColor = avgColor(botR, botG, botB, w)
            val leftColor = avgColor(leftR, leftG, leftB, h)
            val rightColor = avgColor(rightR, rightG, rightB, h)

            ambientGlowView.updateEdgeColors(topColor, bottomColor, leftColor, rightColor)
        } catch (_: Exception) {
            // Kabhi kabhi surface abhi resize/transition ho raha hota hai — safe ignore,
            // agla sample 800ms baad phir try karega.
        }
    }

    /** "Stats for nerds": current codec/resolution/bitrate/dropped-frames text banata hai. */
    private fun updateStatsForNerdsOverlay() {
        if (!::player.isInitialized) return
        val vf = player.videoFormat
        val af = player.audioFormat
        val sb = StringBuilder()
        if (vf != null) {
            sb.append("Video: ${vf.width}x${vf.height} ${vf.sampleMimeType?.substringAfter('/') ?: "?"}\n")
            if (vf.bitrate > 0) sb.append("Bitrate: ${vf.bitrate / 1000} kbps\n")
            if (vf.frameRate > 0) sb.append("FPS: ${"%.1f".format(vf.frameRate)}\n")
        } else {
            sb.append("Video: -\n")
        }
        sb.append("Dropped frames: $totalDroppedFrames\n")
        if (af != null) {
            sb.append("Audio: ${af.sampleMimeType?.substringAfter('/') ?: "?"}")
            if (af.bitrate > 0) sb.append(" ${af.bitrate / 1000} kbps")
            sb.append(" ${af.channelCount}ch/${af.sampleRate}Hz\n")
        } else {
            sb.append("Audio: -\n")
        }
        sb.append("Buffered: ${(player.bufferedPosition - player.currentPosition).coerceAtLeast(0) / 1000}s")
        statsForNerdsOverlay.text = sb.toString()
    }

    /** One-Handed Reach Mode: control-bar (controlsCard) ko thumb ki reach mein
     *  scale+translate karta hai. state: 0=off, 1=bottom-right compact, 2=bottom-left compact. */
    private fun applyReachMode(state: Int) {
        val controlsCard = playerView.findViewById<View>(R.id.controlsCard) ?: return
        controlsCard.animate().cancel()
        when (state) {
            0 -> {
                controlsCard.pivotX = controlsCard.width / 2f
                controlsCard.pivotY = controlsCard.height.toFloat()
                controlsCard.animate()
                    .scaleX(1f).scaleY(1f).translationX(0f)
                    .setDuration(220)
                    .start()
            }
            1, 2 -> {
                val scale = 0.8f
                controlsCard.pivotY = controlsCard.height.toFloat()
                controlsCard.pivotX = if (state == 1) controlsCard.width.toFloat() else 0f
                val shiftX = (controlsCard.width * 0.12f) * (if (state == 1) 1f else -1f)
                controlsCard.animate()
                    .scaleX(scale).scaleY(scale)
                    .translationX(shiftX)
                    .setDuration(220)
                    .start()
            }
        }
    }

    /** Mark Intro tap handler: pehla tap = intro start, dusra tap (thodi der baad,
     *  jab intro khatam ho jaaye) = intro end. Range us poore series/folder ke liye save hoti hai. */
    private fun onMarkIntroClicked() {
        if (!::player.isInitialized) return
        if (introMarkStartMs < 0) {
            introMarkStartMs = player.currentPosition
            markIntroLabel.text = "Mark Intro End"
            showGestureFeedback("Intro start marked — intro khatam hote hi phir tap karo")
        } else {
            val start = introMarkStartMs
            val end = player.currentPosition
            introMarkStartMs = -1L
            markIntroLabel.text = "Mark Intro"
            if (end > start + 1000) {
                IntroSkipStore.saveRange(this, currentFolderKey, start, end)
                introRangeForCurrent = start to end
                showGestureFeedback("Intro range saved — agli episode mein auto-skip milega")
            } else {
                showGestureFeedback("Intro end, start ke baad hona chahiye")
            }
        }
    }

    /** Naye episode/video load hone par is series ke liye pehle se koi intro range hai
     *  kya check karta hai, aur mark-in-progress state reset karta hai. */
    private fun resetIntroSkipForCurrentItem() {
        val currentIndex = if (::player.isInitialized) player.currentMediaItemIndex else -1
        currentFolderKey = queue.getOrNull(currentIndex)?.relativePath ?: ""
        introRangeForCurrent = IntroSkipStore.getRange(this, currentFolderKey)
        introMarkStartMs = -1L
        markIntroLabel.text = "Mark Intro"
        introSkipShown = false
        skipIntroButton.visibility = View.GONE
    }

    private fun checkIntroSkipVisibility() {
        if (!::player.isInitialized) return
        val range = introRangeForCurrent ?: return
        val pos = player.currentPosition
        val inRange = pos in range.first until range.second
        if (inRange && !introSkipShown) {
            introSkipShown = true
            skipIntroButton.visibility = View.VISIBLE
            skipIntroButton.alpha = 0f
            skipIntroButton.animate().alpha(1f).setDuration(150).start()
        } else if (!inRange && introSkipShown) {
            introSkipShown = false
            skipIntroButton.animate().alpha(0f).setDuration(150)
                .withEndAction { skipIntroButton.visibility = View.GONE }
                .start()
        }
    }

    // ---------------------------------------------------------------------
    // Sleep Timer
    // ---------------------------------------------------------------------
    private fun showSleepTimerDialog() {
        val options = arrayOf("Off", "10 minutes", "20 minutes", "30 minutes", "45 minutes", "60 minutes")
        val minutesValues = intArrayOf(0, 10, 20, 30, 45, 60)

        AlertDialog.Builder(this)
            .setTitle("Sleep Timer")
            .setItems(options) { _, which ->
                sleepRunnable?.let { sleepHandler.removeCallbacks(it) }
                sleepRunnable = null
                cancelSleepDim()
                sleepTimerLabel.text = "Sleep Timer"

                val minutes = minutesValues[which]
                if (minutes > 0) {
                    val runnable = Runnable {
                        if (::player.isInitialized) player.playWhenReady = false
                        showGestureFeedback("Sleep Timer: video paused")
                        sleepTimerLabel.text = "Sleep Timer"
                        sleepTimerActive = false
                        sleepTimerButton.setImageResource(R.drawable.ic_timer)
                        setToggleActive(sleepTimerButton, false)
                        cancelSleepDim()
                    }
                    sleepRunnable = runnable
                    sleepHandler.postDelayed(runnable, minutes * 60_000L)
                    sleepTimerLabel.text = "$minutes min"
                    sleepTimerActive = true
                    sleepTimerButton.setImageResource(R.drawable.ic_timer_active)
                    setToggleActive(sleepTimerButton, true)
                    showGestureFeedback("Sleep Timer: $minutes min")

                    // Ambient touch: agar timer 2 min se lamba hai, to aakhri 2 minute mein
                    // screen dheere-dheere warm/amber tone mein dim hone lagegi — video
                    // pause hone se pehle ek soft "wind-down" cue, jhatke se pause hone
                    // jaisa nahi lagta.
                    val dimLeadMs = 120_000L
                    if (minutes * 60_000L > dimLeadMs) {
                        val dimRunnable = Runnable { startSleepDimFade(dimLeadMs) }
                        sleepDimRunnable = dimRunnable
                        sleepHandler.postDelayed(dimRunnable, minutes * 60_000L - dimLeadMs)
                    }
                } else {
                    sleepTimerActive = false
                    sleepTimerButton.setImageResource(R.drawable.ic_timer)
                    setToggleActive(sleepTimerButton, false)
                    showGestureFeedback("Sleep Timer OFF")
                }
            }
            .show()
    }

    /** Sleep timer fire hone se `durationMs` pehle call hota hai — overlay ko alpha 0
     *  se 0.55 tak `durationMs` mein smoothly animate karta hai (warm amber tone). */
    private fun startSleepDimFade(durationMs: Long) {
        sleepDimOverlay.visibility = View.VISIBLE
        sleepDimAnimator?.cancel()
        sleepDimAnimator = android.animation.ValueAnimator.ofFloat(0f, 0.55f).apply {
            duration = durationMs
            addUpdateListener { anim -> sleepDimOverlay.alpha = anim.animatedValue as Float }
            start()
        }
    }

    /** Sleep timer cancel/reschedule hone par pending dim-fade hata kar overlay reset karta hai. */
    private fun cancelSleepDim() {
        sleepDimRunnable?.let { sleepHandler.removeCallbacks(it) }
        sleepDimRunnable = null
        sleepDimAnimator?.cancel()
        sleepDimAnimator = null
        sleepDimOverlay.alpha = 0f
        sleepDimOverlay.visibility = View.GONE
    }

    // ---------------------------------------------------------------------
    // Subtitle: full-screen menu (screenshot jaisa) -> Open / Settings /
    // Synchronization / Speed / Panel / Customization
    // ---------------------------------------------------------------------
    private fun showSubtitleMenu() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
        }
        root.addView(buildSubtitleHeader("Subtitle", showBack = false, onBack = null))

        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(20))
        }

        body.addView(subtitleMenuRow("Open", R.drawable.ic_folder, true) {
            openSubtitleLauncher.launch(arrayOf("*/*"))
        })
        addEmbeddedSubtitleTrackList(body)
        body.addView(subtitleMenuRow(
            if (dualSubtitleController?.isLoaded == true) "Dual Subtitle: ON (tap to change)" else "Dual Subtitle (add 2nd subtitle)",
            R.drawable.ic_folder,
            true
        ) {
            if (dualSubtitleController?.isLoaded == true) {
                AlertDialog.Builder(this)
                    .setTitle("Dual Subtitle")
                    .setItems(arrayOf("Change file", "Turn off")) { _, which ->
                        if (which == 0) {
                            openSecondarySubtitleLauncher.launch(arrayOf("*/*"))
                        } else {
                            dualSubtitleController?.clear()
                            showGestureFeedback("Dual subtitle off")
                        }
                    }
                    .show()
            } else {
                openSecondarySubtitleLauncher.launch(arrayOf("*/*"))
            }
        })
        body.addView(subtitleMenuRow("Settings", R.drawable.ic_settings, true) {
            showSubtitleCustomizationScreen()
        })
        body.addView(spacer(dp(18)))
        body.addView(subtitleMenuRow("Synchronization", null, subtitleLoaded) {
            showSubtitleSyncDialog()
        })
        body.addView(subtitleMenuRow("Speed", null, subtitleLoaded) {
            showSubtitleSpeedDialog()
        })
        body.addView(spacer(dp(18)))
        body.addView(checkboxRow(
            label = "Panel",
            checked = subPanelOn,
            colorHex = null
        ) { checked ->
            subPanelOn = checked
            showGestureFeedback(if (checked) "Subtitle panel ON" else "Subtitle panel OFF")
        })
        body.addView(spacer(dp(24)))
        body.addView(TextView(this).apply {
            text = "Customization"
            textSize = 20f
            setTextColor(android.graphics.Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
            isClickable = true
            setOnClickListener { showSubtitleCustomizationScreen() }
        })

        root.addView(ScrollView(this).apply {
            addView(body)
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        showOverlayPanel(root)
    }

    /** "Open"/"Settings" jaisi icon+label row (Synchronization/Speed ke liye icon null aur disabled ho sakta hai). */
    private fun subtitleMenuRow(label: String, iconRes: Int?, enabled: Boolean, onClick: () -> Unit): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, dp(14), 0, dp(14))
            isClickable = enabled
            alpha = if (enabled) 1f else 0.4f

            if (iconRes != null) {
                addView(ImageView(this@PlayerActivity).apply {
                    layoutParams = LinearLayout.LayoutParams(dp(26), dp(26)).apply { marginEnd = dp(24) }
                    setImageResource(iconRes)
                })
            } else {
                addView(View(this@PlayerActivity).apply {
                    layoutParams = LinearLayout.LayoutParams(dp(26) + dp(24), dp(1))
                })
            }

            addView(TextView(this@PlayerActivity).apply {
                text = label
                textSize = 18f
                setTextColor(android.graphics.Color.WHITE)
            })

            if (enabled) setOnClickListener { onClick() }
        }
    }

    private fun showSubtitleSyncDialog() {
        val prefs = getSharedPreferences("subtitle_prefs", MODE_PRIVATE)
        var delayMs = prefs.getInt("sub_delay_ms", 0)
        val seekBar = SeekBar(this).apply {
            max = 100 // -5000ms..+5000ms, 100 steps of 100ms
            progress = ((delayMs + 5000) / 100).coerceIn(0, 100)
        }
        val label = TextView(this).apply {
            text = "Delay: ${delayMs}ms"
            setTextColor(android.graphics.Color.WHITE)
            setPadding(dp(24), dp(16), dp(24), 0)
        }
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                delayMs = progress * 100 - 5000
                label.text = "Delay: ${delayMs}ms"
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(label)
            addView(seekBar)
        }
        AlertDialog.Builder(this)
            .setTitle("Subtitle Synchronization")
            .setView(container)
            .setPositiveButton("OK") { _, _ ->
                prefs.edit().putInt("sub_delay_ms", delayMs).apply()
                showGestureFeedback("Subtitle delay: ${delayMs}ms")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showSubtitleSpeedDialog() {
        val options = arrayOf("0.5x", "0.75x", "1.0x", "1.25x", "1.5x")
        AlertDialog.Builder(this)
            .setTitle("Subtitle Speed")
            .setItems(options) { _, which ->
                showGestureFeedback("Subtitle speed: ${options[which]}")
            }
            .show()
    }

    private fun attachSubtitle(uri: Uri) {
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: Exception) {
            // Kuch providers persistable permission support nahi karte, ignore kar sakte hain
        }

        val fileName = uri.lastPathSegment?.lowercase() ?: ""
        val mimeType = when {
            fileName.endsWith(".srt") -> MimeTypes.APPLICATION_SUBRIP
            fileName.endsWith(".vtt") -> MimeTypes.TEXT_VTT
            fileName.endsWith(".ssa") || fileName.endsWith(".ass") -> MimeTypes.TEXT_SSA
            else -> MimeTypes.APPLICATION_SUBRIP
        }

        val subtitleConfig = MediaItem.SubtitleConfiguration.Builder(uri)
            .setMimeType(mimeType)
            .setLanguage("und")
            .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
            .build()

        val currentItem = player.currentMediaItem ?: return
        val newItem = currentItem.buildUpon()
            .setSubtitleConfigurations(listOf(subtitleConfig))
            .build()

        val resumePos = player.currentPosition
        val wasPlaying = player.playWhenReady
        player.replaceMediaItem(player.currentMediaItemIndex, newItem)
        player.seekTo(player.currentMediaItemIndex, resumePos)
        player.playWhenReady = wasPlaying
        subtitleLoaded = true
        applySubtitleStyle()
        showGestureFeedback("Subtitle loaded")
    }

    /**
     * "Online subtitles" link — OpenSubtitles.com par current video ke naam se search karta hai,
     * result list dikhata hai, aur selected subtitle download karke turant apply kar deta hai.
     * Bug fix: pehle ye button sirf "coming soon" bolta tha, kuch karta nahi tha.
     */
    private fun showOnlineSubtitleSearch() {
        if (!OpenSubtitlesClient.isConfigured()) {
            AlertDialog.Builder(this)
                .setTitle("Online subtitles")
                .setMessage(
                    "Ye feature use karne ke liye free OpenSubtitles.com API key chahiye.\n\n" +
                        "1. opensubtitles.com par free account banao\n" +
                        "2. \"API Consumers\" section se app register karke key lo\n" +
                        "3. OpenSubtitlesClient.kt file mein API_KEY variable mein paste kar do"
                )
                .setPositiveButton("OK", null)
                .show()
            return
        }

        val defaultQuery = playerTitleText.text
            ?.toString()
            ?.substringBeforeLast('.')
            ?.replace('.', ' ')
            ?.replace('_', ' ')
            ?.trim()
            .orEmpty()

        val input = EditText(this).apply {
            setText(defaultQuery)
            setSelectAllOnFocus(true)
            setTextColor(android.graphics.Color.WHITE)
            setHint("Movie/video ka naam")
            setHintTextColor(android.graphics.Color.parseColor("#888888"))
            setPadding(dp(20), dp(16), dp(20), dp(16))
        }

        AlertDialog.Builder(this)
            .setTitle("Online subtitles search karein")
            .setView(input)
            .setPositiveButton("Search") { _, _ ->
                val query = input.text?.toString()?.trim().orEmpty()
                if (query.isEmpty()) {
                    showGestureFeedback("Pehle video/movie ka naam likhein")
                } else {
                    runOnlineSubtitleSearch(query)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun runOnlineSubtitleSearch(query: String) {
        val progress = AlertDialog.Builder(this)
            .setTitle("Searching...")
            .setMessage("\"$query\" ke liye subtitles dhoonda ja raha hai")
            .setCancelable(false)
            .show()

        onlineSubtitleExecutor.execute {
            try {
                val results = OpenSubtitlesClient.search(query)
                runOnUiThread {
                    progress.dismiss()
                    if (results.isEmpty()) {
                        showGestureFeedback("Koi subtitle nahi mila")
                    } else {
                        showOnlineSubtitleResults(results)
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    progress.dismiss()
                    showGestureFeedback(e.message ?: "Subtitle search fail ho gayi")
                }
            }
        }
    }

    private fun showOnlineSubtitleResults(results: List<OpenSubtitlesClient.Result>) {
        val labels = results.map { "[${it.language.uppercase()}] ${it.releaseName}" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Subtitle select karein")
            .setItems(labels) { _, which ->
                downloadAndAttachOnlineSubtitle(results[which])
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun downloadAndAttachOnlineSubtitle(result: OpenSubtitlesClient.Result) {
        val progress = AlertDialog.Builder(this)
            .setTitle("Downloading...")
            .setMessage(result.releaseName)
            .setCancelable(false)
            .show()

        onlineSubtitleExecutor.execute {
            try {
                val dir = File(cacheDir, "online_subtitles").apply { if (!exists()) mkdirs() }
                val destFile = File(dir, "sub_${result.fileId}.srt")
                OpenSubtitlesClient.download(result.fileId, destFile)
                runOnUiThread {
                    progress.dismiss()
                    attachSubtitle(Uri.fromFile(destFile))
                }
            } catch (e: Exception) {
                runOnUiThread {
                    progress.dismiss()
                    showGestureFeedback(e.message ?: "Subtitle download fail ho gaya")
                }
            }
        }
    }

    // ---------------------------------------------------------------------
    // Cast (Chromecast) — network stream URLs ke liye real device casting
    // ---------------------------------------------------------------------

    /** Cast icon tap hone par device chooser dialog kholta hai (network stream par hi kaam karta hai). */
    private fun openCastChooser() {
        val currentUri = queue.getOrNull(player.currentMediaItemIndex)?.uriString
        val isNetworkStream = currentUri != null &&
            (currentUri.startsWith("http://", ignoreCase = true) || currentUri.startsWith("https://", ignoreCase = true))

        if (!isNetworkStream) {
            AlertDialog.Builder(this)
                .setTitle("Cast")
                .setMessage(
                    "Cast abhi sirf network stream URLs (More > Streams se add kiye http/https links) " +
                        "ke liye kaam karta hai.\n\nPhone ki local storage wali file ko seedha Chromecast " +
                        "tak nahi bheja ja sakta — iske liye phone par ek local HTTP server chahiye hota, " +
                        "jo is app mein abhi nahi hai."
                )
                .setPositiveButton("OK", null)
                .show()
            return
        }

        val castContext = try {
            CastContext.getSharedInstance(this)
        } catch (e: Exception) {
            showGestureFeedback("Is device par Cast available nahi hai")
            return
        }

        val selector = castContext.mergedSelector
        if (selector == null) {
            showGestureFeedback("Is device par Cast available nahi hai")
            return
        }

        registerCastSessionListener(castContext, currentUri, playerTitleText.text?.toString() ?: "Video")

        MediaRouteButton(this).apply {
            routeSelector = selector
        }.showDialog()
    }

    private fun registerCastSessionListener(castContext: CastContext, mediaUri: String, title: String) {
        castSessionManagerListener?.let {
            castContext.sessionManager.removeSessionManagerListener(it, CastSession::class.java)
        }
        val listener = object : SessionManagerListener<CastSession> {
            override fun onSessionStarted(session: CastSession, sessionId: String) = loadMediaOnCast(session, mediaUri, title)
            override fun onSessionResumed(session: CastSession, wasSuspended: Boolean) = loadMediaOnCast(session, mediaUri, title)
            override fun onSessionStartFailed(session: CastSession, error: Int) {
                showGestureFeedback("Cast shuru nahi ho paaya")
            }
            override fun onSessionResumeFailed(session: CastSession, error: Int) {}
            override fun onSessionEnded(session: CastSession, error: Int) {}
            override fun onSessionStarting(session: CastSession) {}
            override fun onSessionEnding(session: CastSession) {}
            override fun onSessionResuming(session: CastSession, sessionId: String) {}
            override fun onSessionSuspended(session: CastSession, reason: Int) {}
        }
        castSessionManagerListener = listener
        castContext.sessionManager.addSessionManagerListener(listener, CastSession::class.java)
    }

    private fun loadMediaOnCast(session: CastSession, mediaUri: String, title: String) {
        val remoteMediaClient = session.remoteMediaClient ?: return
        val mimeType = when {
            mediaUri.endsWith(".m3u8", ignoreCase = true) -> "application/x-mpegURL"
            mediaUri.endsWith(".mpd", ignoreCase = true) -> "application/dash+xml"
            mediaUri.endsWith(".mkv", ignoreCase = true) -> "video/x-matroska"
            else -> "video/mp4"
        }
        val castMetadata = CastMediaMetadata(CastMediaMetadata.MEDIA_TYPE_MOVIE).apply {
            putString(CastMediaMetadata.KEY_TITLE, title)
        }
        val mediaInfo = MediaInfo.Builder(mediaUri)
            .setContentUrl(mediaUri)
            .setStreamType(MediaInfo.STREAM_TYPE_BUFFERED)
            .setContentType(mimeType)
            .setMetadata(castMetadata)
            .build()
        val loadRequest = MediaLoadRequestData.Builder()
            .setMediaInfo(mediaInfo)
            .setCurrentTime(player.currentPosition)
            .setAutoplay(true)
            .build()
        remoteMediaClient.load(loadRequest)
        player.pause()
        showGestureFeedback("Casting: $title")
    }

    /**
     * File ke andar jitne bhi subtitle tracks embedded hain (jaisa MX Player dikhata hai), unko
     * asli naam/language ke saath checkbox list mein dikhata hai — select karne par turant apply
     * ho jata hai. Agar koi embedded subtitle track hi nahi hai (sirf external load kiya ja sakta
     * hai) to kuch nahi dikhata.
     */
    private fun addEmbeddedSubtitleTrackList(body: LinearLayout) {
        val subGroups = player.currentTracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }
        if (subGroups.isEmpty()) return

        val subLabels = mutableListOf<String>()
        val subRefs = mutableListOf<Pair<androidx.media3.common.Tracks.Group, Int>>()
        subGroups.forEach { group ->
            for (i in 0 until group.length) {
                val format = group.getTrackFormat(i)
                subLabels.add(trackDisplayLabel(format, subLabels.size + 1, "Subtitle track"))
                subRefs.add(Pair(group, i))
            }
        }
        subLabels.add("Disable")

        val currentSelected = subRefs.indexOfFirst { (group, i) -> group.isTrackSelected(i) }
            .let { if (player.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT)) subLabels.size - 1 else it }

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(10))
            background = GradientDrawable().apply {
                setColor(android.graphics.Color.parseColor("#14FFFFFF"))
                setStroke(dp(1), android.graphics.Color.parseColor("#26FFFFFF"))
                cornerRadius = dp(14).toFloat()
            }
        }

        val checkBoxes = mutableListOf<CheckBox>()
        var applyingProgrammatically = false

        fun applySelection(which: Int) {
            if (which == subLabels.size - 1) {
                player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                    .build()
                subtitleLoaded = false
                showGestureFeedback("Subtitle disabled")
            } else {
                val (group, idx) = subRefs[which]
                player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                    .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, idx))
                    .build()
                subtitleLoaded = true
                showGestureFeedback(subLabels[which])
            }
        }

        // Look-and-feel fix: pehle yahan MX Player ka hi signature purple
        // (#8C6FFF) hardcoded tha — ab app ke apne gold accent (#FFD700) se
        // match karta hai, baaki poore player jaisa hi.
        val accentColor = android.graphics.Color.parseColor("#FFD700")
        fun subRowBg(selected: Boolean) = GradientDrawable().apply {
            cornerRadius = dp(10).toFloat()
            setColor(if (selected) android.graphics.Color.parseColor("#26FFD700") else android.graphics.Color.TRANSPARENT)
        }
        subLabels.forEachIndexed { idx, label ->
            val cb = CheckBox(this).apply {
                text = label
                textSize = 13.5f
                setLineSpacing(dp(2).toFloat(), 1f)
                setTextColor(android.graphics.Color.WHITE)
                isChecked = idx == currentSelected
                setPadding(dp(8), dp(10), dp(8), dp(10))
                buttonTintList = android.content.res.ColorStateList.valueOf(accentColor)
                background = subRowBg(idx == currentSelected)
                // Width MATCH_PARENT zaroori hai warna lamba text box se bahar chala jaata hai.
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            }
            checkBoxes.add(cb)
            box.addView(cb)
        }

        checkBoxes.forEachIndexed { idx, cb ->
            cb.setOnCheckedChangeListener { _, isChecked ->
                if (applyingProgrammatically) return@setOnCheckedChangeListener
                if (isChecked) {
                    applyingProgrammatically = true
                    checkBoxes.forEachIndexed { j, other ->
                        if (j != idx) other.isChecked = false
                        other.background = subRowBg(j == idx)
                    }
                    applyingProgrammatically = false
                    applySelection(idx)
                } else if (checkBoxes.none { it.isChecked }) {
                    applyingProgrammatically = true
                    cb.isChecked = true
                    applyingProgrammatically = false
                }
            }
        }

        body.addView(spacer(dp(12)))
        body.addView(box)
        body.addView(spacer(dp(8)))
    }

    private fun disableSubtitle() {
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            .build()
        subtitleLoaded = false
        showGestureFeedback("Subtitle disabled")
    }

    // ---------------------------------------------------------------------
    // Subtitles Customization: Layout + Text sections (screenshot jaisa)
    // ---------------------------------------------------------------------
    private fun showSubtitleCustomizationScreen() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(android.graphics.Color.BLACK)
        }

        lateinit var dialogRef: AlertDialog
        root.addView(buildSubtitleHeader("Subtitles Customization", showBack = true) {
            dialogRef.dismiss()
        })

        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(24))
        }

        // ---- Layout section ----
        body.addView(sectionHeader("Layout"))
        body.addView(dropdownRow("Alignment", subAlignments[subAlignmentIndex]) {
            AlertDialog.Builder(this)
                .setTitle("Alignment")
                .setSingleChoiceItems(subAlignments, subAlignmentIndex) { dialog, which ->
                    subAlignmentIndex = which
                    applySubtitleStyle()
                    dialog.dismiss()
                    dialogRef.dismiss()
                    showSubtitleCustomizationScreen()
                }
                .show()
        })
        body.addView(sliderRow("Bottom margins", 0, 100, subBottomMargin) { v ->
            subBottomMargin = v
            applySubtitleStyle()
        })
        body.addView(checkboxRow("Background", subLayoutBgEnabled, subLayoutBgColor, onColorClick = {
            showColorPickerDialog(subLayoutBgColor) { color ->
                subLayoutBgColor = color
                applySubtitleStyle()
                dialogRef.dismiss()
                showSubtitleCustomizationScreen()
            }
        }) { checked ->
            subLayoutBgEnabled = checked
            applySubtitleStyle()
        })
        body.addView(checkboxRow("Fit subtitles into video size", subFitToVideoSize, null) { checked ->
            subFitToVideoSize = checked
            applySubtitleStyle()
        })

        body.addView(spacer(dp(20)))

        // ---- Text section ----
        body.addView(sectionHeader("Text"))
        body.addView(dropdownRow("Font", subFonts[subFontIndex]) {
            AlertDialog.Builder(this)
                .setTitle("Font")
                .setSingleChoiceItems(subFonts, subFontIndex) { dialog, which ->
                    subFontIndex = which
                    applySubtitleStyle()
                    dialog.dismiss()
                    dialogRef.dismiss()
                    showSubtitleCustomizationScreen()
                }
                .show()
        })
        body.addView(sliderRow("Size", 8, 40, subSizeSp) { v ->
            subSizeSp = v
            applySubtitleStyle()
        })
        body.addView(sliderRow("Scale", 50, 150, subScalePercent, suffix = "%") { v ->
            subScalePercent = v
            applySubtitleStyle()
        })
        body.addView(colorAndBoldRow(dialogRefProvider = { dialogRef }))
        body.addView(checkboxRow("Background Color", subTextBgEnabled, subTextBgColor, onColorClick = {
            showColorPickerDialog(subTextBgColor) { color ->
                subTextBgColor = color
                applySubtitleStyle()
                dialogRef.dismiss()
                showSubtitleCustomizationScreen()
            }
        }) { checked ->
            subTextBgEnabled = checked
            applySubtitleStyle()
        })
        body.addView(borderRow(dialogRefProvider = { dialogRef }))
        body.addView(dropdownRow("Advanced", if (subAdvancedExpanded) "▲" else "▼") {
            subAdvancedExpanded = !subAdvancedExpanded
            dialogRef.dismiss()
            showSubtitleCustomizationScreen()
        })
        if (subAdvancedExpanded) {
            body.addView(checkboxRow("Shadow", subShadowEnabled, null) { checked ->
                subShadowEnabled = checked
                applySubtitleStyle()
            })
        }

        root.addView(ScrollView(this).apply {
            addView(body)
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        dialogRef = AlertDialog.Builder(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
            .setView(root)
            .create()
        dialogRef.window?.setBackgroundDrawableResource(android.R.color.black)
        dialogRef.show()
    }

    /** "Color [swatch]  [x] Bold" wali row (screenshot 2 jaisi). */
    private fun colorAndBoldRow(dialogRefProvider: () -> AlertDialog): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, dp(14), 0, dp(14))

            addView(TextView(this@PlayerActivity).apply {
                text = "Color"
                textSize = 16f
                setTextColor(android.graphics.Color.WHITE)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })

            addView(colorSwatchView(subTextColor).apply {
                setOnClickListener {
                    showColorPickerDialog(subTextColor) { color ->
                        subTextColor = color
                        applySubtitleStyle()
                        dialogRefProvider().dismiss()
                        showSubtitleCustomizationScreen()
                    }
                }
            })

            addView(CheckBox(this@PlayerActivity).apply {
                text = "Bold"
                setTextColor(android.graphics.Color.WHITE)
                isChecked = subBold
                setPadding(dp(16), 0, 0, 0)
                setOnCheckedChangeListener { _, checked ->
                    subBold = checked
                    applySubtitleStyle()
                }
            })
        }
    }

    /** "Border [swatch] [-----•----] 80%" wali row. */
    private fun borderRow(dialogRefProvider: () -> AlertDialog): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, dp(14), 0, dp(14))

            addView(CheckBox(this@PlayerActivity).apply {
                isChecked = subBorderEnabled
                setOnCheckedChangeListener { _, checked ->
                    subBorderEnabled = checked
                    applySubtitleStyle()
                }
            })
            addView(TextView(this@PlayerActivity).apply {
                text = "Border"
                textSize = 16f
                setTextColor(android.graphics.Color.WHITE)
                setPadding(dp(8), 0, dp(16), 0)
            })
            addView(colorSwatchView(subBorderColor).apply {
                setOnClickListener {
                    showColorPickerDialog(subBorderColor) { color ->
                        subBorderColor = color
                        applySubtitleStyle()
                        dialogRefProvider().dismiss()
                        showSubtitleCustomizationScreen()
                    }
                }
            })
            addView(SeekBar(this@PlayerActivity).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = dp(12)
                }
                max = 100
                progress = subBorderSize
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                        subBorderSize = progress
                        applySubtitleStyle()
                    }
                    override fun onStartTrackingTouch(sb: SeekBar?) {}
                    override fun onStopTrackingTouch(sb: SeekBar?) {}
                })
            })
        }
    }

    /** Small colored square jo current color dikhata hai (tap -> color picker). */
    private fun colorSwatchView(color: Int): View {
        return View(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(28), dp(28))
            background = GradientDrawable().apply {
                setColor(color)
                setStroke(dp(1), android.graphics.Color.parseColor("#66FFFFFF"))
                cornerRadius = dp(3).toFloat()
            }
        }
    }

    private fun showColorPickerDialog(current: Int, onPick: (Int) -> Unit) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        colorSwatches.forEach { c ->
            row.addView(View(this).apply {
                layoutParams = LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginEnd = dp(10) }
                background = GradientDrawable().apply {
                    setColor(c)
                    setStroke(if (c == current) dp(3) else dp(1), android.graphics.Color.parseColor("#AAFFFFFF"))
                    cornerRadius = dp(20).toFloat()
                }
                setOnClickListener { onPick(c) }
            })
        }
        AlertDialog.Builder(this)
            .setTitle("Choose color")
            .setView(row)
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Bina subtitle track ke bhi style state save rehti hai; track load hote hi apply ho jaati hai. */
    private fun applySubtitleStyle() {
        val subtitleView = playerView.subtitleView ?: return

        val fraction = (subSizeSp / 20f) * (subScalePercent / 100f) * 0.0533f
        subtitleView.setFractionalTextSize(fraction.coerceIn(0.01f, 0.2f))

        val typeface = when (subFontIndex) {
            1 -> Typeface.SANS_SERIF
            2 -> Typeface.SERIF
            3 -> Typeface.MONOSPACE
            else -> Typeface.DEFAULT
        }.let { if (subBold) Typeface.create(it, Typeface.BOLD) else it }

        val edgeType = if (subBorderEnabled) {
            CaptionStyleCompat.EDGE_TYPE_OUTLINE
        } else if (subShadowEnabled) {
            CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW
        } else {
            CaptionStyleCompat.EDGE_TYPE_NONE
        }
        val edgeAlpha = ((subBorderSize / 100f) * 255).toInt().coerceIn(0, 255)
        val edgeColor = (subBorderColor and 0x00FFFFFF) or (edgeAlpha shl 24)

        val windowColor = if (subLayoutBgEnabled) subLayoutBgColor else android.graphics.Color.TRANSPARENT
        val backgroundColor = if (subTextBgEnabled) subTextBgColor else android.graphics.Color.TRANSPARENT

        subtitleView.setStyle(
            CaptionStyleCompat(
                subTextColor,
                backgroundColor,
                windowColor,
                edgeType,
                edgeColor,
                typeface
            )
        )
        subtitleView.setApplyEmbeddedStyles(!subFitToVideoSize)

        val marginPx = ((subBottomMargin / 100f) * dp(120))
        val params = subtitleView.layoutParams
        if (params is FrameLayout.LayoutParams) {
            params.bottomMargin = marginPx.toInt()
            params.gravity = when (subAlignmentIndex) {
                1 -> android.view.Gravity.BOTTOM or android.view.Gravity.START
                2 -> android.view.Gravity.BOTTOM or android.view.Gravity.END
                else -> android.view.Gravity.BOTTOM or android.view.Gravity.CENTER_HORIZONTAL
            }
            subtitleView.layoutParams = params
        }
    }

    // ---------------------------------------------------------------------
    // Decoder select: HW / HW+ / SW
    // ---------------------------------------------------------------------
    private fun showDecoderDialog() {
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
                val mode = when (which) {
                    0 -> DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF
                    1 -> DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON
                    else -> DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER
                }
                val fallback = which != 0
                val resumePos = player.currentPosition
                val resumeIdx = player.currentMediaItemIndex
                buildPlayer(mode, fallback, resumePositionMs = resumePos, resumeIndex = resumeIdx)

                if (which == 2) {
                    showPlayerSnackbar(
                        "SW decoder ON — FFmpeg software decoding use hoga (thoda zyada battery/CPU lag sakta hai)."
                    )
                }
                dialog.dismiss()
            }
            .show()
    }

    // ---------------------------------------------------------------------
    // Playback speed — premium dark bottom sheet (YouTube-style): current
    // speed badge, -/+ buttons + slider (0.25x steps of granularity via a
    // fine-grained SeekBar), aur quick preset pills (Normal/1.25x/1.5x/2x/3x).
    // Purana speedButton "tap to cycle" behavior isi se replace hua hai.
    // ---------------------------------------------------------------------
    private val speedSheetPresets = floatArrayOf(1.0f, 1.25f, 1.5f, 2.0f, 3.0f)
    private val SPEED_MIN = 0.25f
    private val SPEED_MAX = 3.0f
    private val SPEED_STEP = 0.05f

    private fun formatSpeedLabel(speed: Float): String {
        val rounded = Math.round(speed * 100) / 100.0
        return if (rounded == rounded.toLong().toDouble()) "${rounded.toLong()}.00x"
        else String.format("%.2fx", rounded)
    }

    private fun showSpeedSheet() {
        val currentSpeed = try { player.playbackParameters.speed } catch (_: Exception) { 1f }
        val sheet = com.google.android.material.bottomsheet.BottomSheetDialog(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = ContextCompat.getDrawable(this@PlayerActivity, R.drawable.bg_speed_sheet)
            setPadding(dp(22), dp(14), dp(22), dp(26))
        }

        // Drag handle
        root.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(4)).apply {
                gravity = android.view.Gravity.CENTER_HORIZONTAL
                bottomMargin = dp(18)
            }
            background = ContextCompat.getDrawable(this@PlayerActivity, R.drawable.bg_icon_circle)
            alpha = 0.5f
        })

        val speedLabel = TextView(this).apply {
            text = formatSpeedLabel(currentSpeed)
            textSize = 20f
            setTextColor(android.graphics.Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
            gravity = android.view.Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(18) }
        }
        root.addView(speedLabel)

        val steps = Math.round((SPEED_MAX - SPEED_MIN) / SPEED_STEP)

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
            background = ContextCompat.getDrawable(this@PlayerActivity, R.drawable.bg_icon_circle)
        }
        val plusBtn = TextView(this).apply {
            text = "+"
            textSize = 20f
            setTextColor(android.graphics.Color.WHITE)
            gravity = android.view.Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(dp(36), dp(36))
            background = ContextCompat.getDrawable(this@PlayerActivity, R.drawable.bg_icon_circle)
        }
        val seek = SeekBar(this).apply {
            max = steps
            progress = Math.round((currentSpeed.coerceIn(SPEED_MIN, SPEED_MAX) - SPEED_MIN) / SPEED_STEP)
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

        val pillsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val pillViews = mutableListOf<Pair<TextView, Float>>()

        fun refreshPills(selected: Float) {
            pillViews.forEach { (tv, value) ->
                val isSel = Math.abs(value - selected) < 0.01f
                tv.background = ContextCompat.getDrawable(
                    this@PlayerActivity,
                    if (isSel) R.drawable.bg_speed_pill_selected else R.drawable.bg_speed_pill
                )
                tv.setTextColor(if (isSel) android.graphics.Color.parseColor("#0B0B12") else android.graphics.Color.WHITE)
            }
        }

        fun applySpeed(raw: Float, syncSlider: Boolean) {
            val clamped = raw.coerceIn(SPEED_MIN, SPEED_MAX)
            val rounded = Math.round(clamped / SPEED_STEP) * SPEED_STEP
            player.playbackParameters = PlaybackParameters(rounded)
            speedLabel.text = formatSpeedLabel(rounded)
            speedButton.text = formatSpeedLabel(rounded).let {
                // Top-bar chip stays short (e.g. "1.5x" instead of "1.50x")
                val f = rounded
                if (f == f.toLong().toFloat()) "${f.toLong()}x" else String.format("%.2fx", f)
            }
            if (syncSlider) seek.progress = Math.round((rounded - SPEED_MIN) / SPEED_STEP)
            refreshPills(rounded)
        }

        speedSheetPresets.forEach { s ->
            val tv = TextView(this).apply {
                text = if (s == 1f) "Normal" else formatSpeedLabel(s).removeSuffix("0x") + "x"
                textSize = 12f
                setTypeface(typeface, Typeface.BOLD)
                gravity = android.view.Gravity.CENTER
                setPadding(0, dp(11), 0, dp(11))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    if (s != speedSheetPresets.last()) marginEnd = dp(6)
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
                applySpeed(SPEED_MIN + progress * SPEED_STEP, syncSlider = false)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        minusBtn.setOnClickListener {
            applySpeed((player.playbackParameters.speed - SPEED_STEP * 5), syncSlider = true)
        }
        plusBtn.setOnClickListener {
            applySpeed((player.playbackParameters.speed + SPEED_STEP * 5), syncSlider = true)
        }

        sheet.setContentView(root)
        sheet.setOnShowListener {
            // Material's BottomSheetDialog wraps our view in its own white
            // sheet background by default — clear it so only our solid dark
            // bg_speed_sheet drawable shows (no white edges/corners).
            val bottomSheet = sheet.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            bottomSheet?.setBackgroundColor(android.graphics.Color.TRANSPARENT)
        }
        sheet.show()
    }

    // ---------------------------------------------------------------------
    // Audio Track select + Disable + SW audio decoder checkbox
    // ---------------------------------------------------------------------
    private fun showAudioTrackDialog() {
        val tracksGroup = player.currentTracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
        val labels = mutableListOf<String>()
        val trackRefs = mutableListOf<Pair<androidx.media3.common.Tracks.Group, Int>>()

        tracksGroup.forEach { group ->
            for (i in 0 until group.length) {
                val format = group.getTrackFormat(i)
                labels.add(trackDisplayLabel(format, labels.size + 1, "Audio track"))
                trackRefs.add(Pair(group, i))
            }
        }
        labels.add("Disable")

        // Abhi kaunsa track actually selected/playing hai, wo dhoondte hain (radio pre-select ke liye)
        var selectedIndex = trackRefs.indexOfFirst { (group, i) -> group.isTrackSelected(i) }
        if (selectedIndex < 0) selectedIndex = 0

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            setPadding(dp(20), dp(20), dp(20), dp(20))
        }

        // Title row: "Audio Track" + right side "more" (gear) button jo settings (SW decoder,
        // Stereo mode, Synchronization, AV sync) alag screen mein kholta hai.
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(16))

            addView(TextView(this@PlayerActivity).apply {
                text = "Audio Track"
                textSize = 22f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(android.graphics.Color.WHITE)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })

            addView(ImageView(this@PlayerActivity).apply {
                layoutParams = LinearLayout.LayoutParams(dp(28), dp(28))
                setImageResource(R.drawable.ic_settings)
                isClickable = true
                isFocusable = true
                foreground = androidx.core.content.ContextCompat.getDrawable(
                    this@PlayerActivity, android.R.drawable.list_selector_background
                )
                setOnClickListener { showAudioSettingsScreen() }
            })
        })

        // Look-and-feel fix: pehle yahan MX Player ka hi signature purple
        // (#8C6FFF) hardcoded tha — ab app ke apne gold accent (#FFD700) se
        // match karta hai, baaki poore player jaisa hi.
        val accentColor = android.graphics.Color.parseColor("#FFD700")
        fun audioRowBg(selected: Boolean) = GradientDrawable().apply {
            cornerRadius = dp(12).toFloat()
            setColor(if (selected) android.graphics.Color.parseColor("#26FFD700") else android.graphics.Color.TRANSPARENT)
        }
        val radioGroup = RadioGroup(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        labels.forEachIndexed { idx, label ->
            radioGroup.addView(RadioButton(this).apply {
                text = label
                textSize = 15f
                setLineSpacing(dp(2).toFloat(), 1f)
                setTextColor(android.graphics.Color.WHITE)
                isChecked = idx == selectedIndex
                setPadding(dp(10), dp(10), dp(10), dp(10))
                id = View.generateViewId()
                buttonTintList = android.content.res.ColorStateList.valueOf(accentColor)
                background = audioRowBg(idx == selectedIndex)
                // Width MATCH_PARENT zaroori hai warna lamba text (jaise "[HindiAnimeZone.com]")
                // panel se bahar chala jaata hai. Isse text panel ke andar hi wrap hoga.
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            })
        }
        root.addView(radioGroup)

        radioGroup.setOnCheckedChangeListener { group, checkedId ->
            val which = (0 until group.childCount).firstOrNull { group.getChildAt(it).id == checkedId } ?: return@setOnCheckedChangeListener
            for (i in 0 until group.childCount) {
                val rb = group.getChildAt(i) as RadioButton
                rb.background = audioRowBg(rb.id == checkedId)
            }
            if (which == labels.size - 1) {
                player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
                    .build()
                showGestureFeedback("Audio disabled")
            } else {
                val (trackGroup, index) = trackRefs[which]
                player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
                    .setOverrideForType(
                        TrackSelectionOverride(trackGroup.mediaTrackGroup, index)
                    )
                    .build()
                showGestureFeedback(labels[which])
            }
        }

        showOverlayPanel(ScrollView(this).apply { addView(root) })
    }

    /**
     * Audio ka "more" (gear) button dabane par khulne wali settings screen — SW audio decoder
     * + Stereo mode / Synchronization / AV sync (placeholder rows). Back arrow se wapas
     * Audio Track list par aa jaate hain (jo neeche already open hai, isliye sirf dismiss karna hai).
     */
    private fun showAudioSettingsScreen() {
        lateinit var dialogRef: AlertDialog
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
        }
        root.addView(buildSubtitleHeaderSimple("Audio Settings") { dialogRef.dismiss() })

        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(20))
        }

        body.addView(checkboxRow("Use SW audio decoder", useSwAudioDecoder, null) { checked ->
            useSwAudioDecoder = checked
            showPlayerSnackbar(
                if (checked) "SW audio decoder ON (agla playback se apply hoga)" else "SW audio decoder OFF"
            )
        })

        body.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)).apply {
                topMargin = dp(12); bottomMargin = dp(12)
            }
            setBackgroundColor(android.graphics.Color.parseColor("#33FFFFFF"))
        })

        // Ye 3 rows abhi is build mein sirf placeholder hain (track/device support ke bina
        // real switch karna galat info dega), isliye greyed-out + informative tap rakha hai
        body.addView(disabledInfoRow("Stereo mode"))
        body.addView(disabledInfoRow("Synchronization"))
        body.addView(disabledInfoRow("AV sync", showCheckbox = true))

        root.addView(ScrollView(this).apply { addView(body) }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        dialogRef = showOverlayPanel(root)
    }

    /** Chhota header: back arrow + bold title (buildSubtitleHeader jaisa, par "Online subtitles" link ke bina). */
    private fun buildSubtitleHeaderSimple(title: String, onBack: () -> Unit): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(16))

            addView(ImageView(this@PlayerActivity).apply {
                layoutParams = LinearLayout.LayoutParams(dp(32), dp(32)).apply { marginEnd = dp(16) }
                setImageResource(R.drawable.ic_back)
                isClickable = true
                foreground = androidx.core.content.ContextCompat.getDrawable(
                    this@PlayerActivity, android.R.drawable.list_selector_background
                )
                setOnClickListener { onBack() }
            })

            addView(TextView(this@PlayerActivity).apply {
                text = title
                textSize = 20f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(android.graphics.Color.WHITE)
            })
        }
    }

    /** "Stereo mode" / "Synchronization" / "AV sync" jaisi grayed-out row (abhi track support nahi hai). */
    private fun disabledInfoRow(label: String, showCheckbox: Boolean = false): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, dp(14), 0, dp(14))
            alpha = 0.4f
            isClickable = true
            setOnClickListener {
                showPlayerSnackbar("$label: is track ke liye available nahi hai")
            }

            addView(TextView(this@PlayerActivity).apply {
                text = label
                textSize = 17f
                setTextColor(android.graphics.Color.WHITE)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })

            if (showCheckbox) {
                addView(CheckBox(this@PlayerActivity).apply {
                    isEnabled = false
                })
            }
        }
    }

    // ---------------------------------------------------------------------
    // Equalizer + Audio Effect dialog
    // ---------------------------------------------------------------------
    private fun showEqualizerDialog() {
        val sessionId = player.audioSessionId
        if (sessionId == C.AUDIO_SESSION_ID_UNSET) {
            showPlayerSnackbar("Audio abhi ready nahi hai, thoda wait karke dobara try karein")
            return
        }
        setupAudioFx(sessionId)

        val view = layoutInflater.inflate(R.layout.dialog_equalizer, null)
        val dialog = AlertDialog.Builder(this).setView(view).create()

        val tabAudioEffect = view.findViewById<TextView>(R.id.tabAudioEffect)
        val tabEqualizer = view.findViewById<TextView>(R.id.tabEqualizer)
        val tabRow = view.findViewById<LinearLayout>(R.id.tabRow)
        val tabIndicator = view.findViewById<View>(R.id.tabIndicator)
        val pageAudioEffect = view.findViewById<LinearLayout>(R.id.pageAudioEffect)
        val pageEqualizer = view.findViewById<LinearLayout>(R.id.pageEqualizer)

        fun showTab(effectTab: Boolean) {
            pageAudioEffect.visibility = if (effectTab) View.VISIBLE else View.GONE
            pageEqualizer.visibility = if (effectTab) View.GONE else View.VISIBLE
            // Theme fix: pehle selected tab blue (#3399FF) ho jaata tha, jabki poore
            // player mein (timebar, tab underline, EQ switch waghera) app ka apna gold
            // accent (#FFD700) use hota hai — ab dono consistent hain.
            tabAudioEffect.setTextColor(if (effectTab) 0xFFFFD700.toInt() else 0xFFAAAAAA.toInt())
            tabEqualizer.setTextColor(if (!effectTab) 0xFFFFD700.toInt() else 0xFFAAAAAA.toInt())
            tabAudioEffect.setTypeface(null, if (effectTab) Typeface.BOLD else Typeface.NORMAL)
            tabEqualizer.setTypeface(null, if (!effectTab) Typeface.BOLD else Typeface.NORMAL)
            // Gold underline ko dusre tab ke neeche slide karo (MX Player jaisa)
            val half = tabRow.width / 2
            if (half > 0) {
                tabIndicator.animate().translationX(if (effectTab) 0f else half.toFloat())
                    .setDuration(200).start()
            }
        }
        tabRow.post {
            val half = tabRow.width / 2
            val lp = tabIndicator.layoutParams
            lp.width = half
            tabIndicator.layoutParams = lp
            // Default active tab Equalizer hai (right side)
            tabIndicator.translationX = half.toFloat()
        }
        tabAudioEffect.setOnClickListener { showTab(true) }
        tabEqualizer.setOnClickListener { showTab(false) }
        showTab(false)


        // --- Equalizer tab: on/off + presets + 5 bands ---
        val eqSwitch = view.findViewById<Switch>(R.id.equalizerSwitch)
        val bandSeeks = listOf<SeekBar>(
            view.findViewById(R.id.bandSeek0), view.findViewById(R.id.bandSeek1),
            view.findViewById(R.id.bandSeek2), view.findViewById(R.id.bandSeek3),
            view.findViewById(R.id.bandSeek4)
        )
        val bandLabels = listOf<TextView>(
            view.findViewById(R.id.bandLabel0), view.findViewById(R.id.bandLabel1),
            view.findViewById(R.id.bandLabel2), view.findViewById(R.id.bandLabel3),
            view.findViewById(R.id.bandLabel4)
        )

        val eqOnOffLabel = view.findViewById<TextView>(R.id.equalizerOnOffLabel)

        // Preset row (Custom + 10 MX Player jaise presets), highlight logic pehle define
        // taaki band-slider drag listener bhi ise call kar sake.
        val presetViews = linkedMapOf(
            "Custom" to view.findViewById<TextView>(R.id.presetCustom),
            "Normal" to view.findViewById<TextView>(R.id.presetNormal),
            "Classical" to view.findViewById<TextView>(R.id.presetClassical),
            "Dance" to view.findViewById<TextView>(R.id.presetDance),
            "Flat" to view.findViewById<TextView>(R.id.presetFlat),
            "Folk" to view.findViewById<TextView>(R.id.presetFolk),
            "Heavy Metal" to view.findViewById<TextView>(R.id.presetHeavyMetal),
            "Hip Hop" to view.findViewById<TextView>(R.id.presetHipHop),
            "Jazz" to view.findViewById<TextView>(R.id.presetJazz),
            "Pop" to view.findViewById<TextView>(R.id.presetPop),
            "Rock" to view.findViewById<TextView>(R.id.presetRock)
        )
        fun highlightPreset(selected: String) {
            // Selection ko persist karo taaki dialog dobara khulne par yahi
            // preset highlight ho, hardcoded "Custom" pe reset na ho.
            savedEqPresetName = selected
            presetViews.forEach { (name, tv) ->
                val isSelected = name.equals(selected, ignoreCase = true)
                tv.setTextColor(if (isSelected) 0xFFFFD700.toInt() else 0xFFFFFFFF.toInt())
                tv.setTypeface(null, if (isSelected) Typeface.BOLD else Typeface.NORMAL)
            }
        }
        highlightPreset(savedEqPresetName)

        // NOTE: "eq" sirf dialog-open ke waqt UI ko turant populate karne ke liye ek
        // snapshot hai (range/band-count padhne ke liye). Actual value-changing calls
        // (setBandLevel waghera) hamesha live "equalizer" member field par hone chahiye,
        // kyunki agar audio session change ho (jaise quality/decoder switch) toh
        // "equalizer" field naye object se replace ho jaata hai — is stale "eq" par
        // likhte rehna asal audio par kabhi apply hi nahi hota (real bug: "value set
        // karne ke hisab se kaam nahi karta").
        val eq = equalizer
        // BUG FIX: pehle eqSwitch ki state aur poora band-slider setup system "eq" (jo
        // kai HW-decoder audio sessions par silently attach hi nahi hota — setupAudioFx()
        // mein exception aane par null reh jaata hai) par depend karta tha. Ab humara
        // asal-reliable in-app processor "eqProcessor" hi primary source-of-truth hai —
        // system Equalizer sirf tab tak bonus hai jab tak vo device par support ho.
        eqSwitch.isChecked = eqProcessor.enabled
        eqOnOffLabel.text = if (eqSwitch.isChecked) "On" else "Off"
        // Double-EQ fix: system Equalizer ko yahan se enable nahi karte — sirf
        // eqProcessor hi actual gain apply karta hai (dekho setupAudioFx() comment).

        // PERMANENT FIX ("slider hilta hai par sound nahi badalta"): pehle neeche wala
        // poora setup (max/progress/listener) sirf `if (eq != null)` ke andar hota tha.
        // Jab system Equalizer attach hi nahi hoti (jaisa "HW" decoder mode ke audio
        // session par hota hai), listener kabhi lagta hi nahi — slider sirf visually
        // move hota, 10 ho ya 100, eqProcessor ko koi naya gain milta hi nahi tha, isliye
        // audio bilkul same rehta tha. HW+ mode mein system Equalizer usually attach ho
        // jaati thi isliye sirf udhar "kaam karta" dikhta tha — ye galat dependency thi.
        // Fix: band range/listener hamesha eqProcessor ke apne fixed ±15dB range se banao
        // (jo guaranteed kaam karta hai); system eq ka range sirf milne par bonus-sync
        // ke liye use karo.
        val sysRange = try { eq?.bandLevelRange } catch (_: Exception) { null }

        for (i in 0 until minOf(bandSeeks.size, EqualizerAudioProcessor.BAND_COUNT)) {
            val seekBar = bandSeeks[i]
            val label = bandLabels[i]

            if (sysRange != null) {
                val minLevel = sysRange[0]
                val span = (sysRange[1] - sysRange[0]).toInt().coerceAtLeast(1)
                seekBar.max = span
                val level = try { eq?.getBandLevel(i.toShort()) } catch (_: Exception) { null }
                    ?: (eqProcessor.getBandGainDb(i) * 100).toInt().toShort()
                seekBar.progress = (level - minLevel).toInt()
                label.text = "${level / 100} dB"
                eqProcessor.setBandGainDb(i, level / 100f)
            } else {
                // System Equalizer available nahi — apna fixed ±15dB range use karo,
                // taaki slider phir bhi 100% kaam kare.
                seekBar.max = 3000
                val gainDb = eqProcessor.getBandGainDb(i)
                seekBar.progress = ((gainDb + 15f) * 100).toInt().coerceIn(0, 3000)
                label.text = "${gainDb.toInt()} dB"
            }

            seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) {
                        val gainDb: Float = if (sysRange != null) {
                            val newLevel = (progress + sysRange[0]).toShort()
                            // Double-EQ fix: system Equalizer par ab band level set
                            // nahi karte — sirf range scaling ke liye use ho raha hai,
                            // gain sirf eqProcessor se aata hai (neeche).
                            newLevel / 100f
                        } else {
                            (progress / 100f) - 15f
                        }
                        // Asal audio badlaav yahi se aata hai (system Equalizer ke
                        // upar depend nahi karta, isliye har device/decoder-mode par kaam karta hai):
                        eqProcessor.setBandGainDb(i, gainDb)
                        label.text = "${gainDb.toInt()} dB"
                        // User ne slider manually hilaya -> preset selector "Custom" pe chala jaaye
                        highlightPreset("Custom")
                    }
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }

        val eqCheckedChangeListener = CompoundButton.OnCheckedChangeListener { _, checked ->
            eqOnOffLabel.text = if (checked) "On" else "Off"
            eqProcessor.enabled = checked
        }
        eqSwitch.setOnCheckedChangeListener(eqCheckedChangeListener)


        // PERMANENT PRESET FIX: pehle yahan liveEq.usePreset() + naam-se-match karke system
        // Equalizer ke presets dhoonde jaate the. Do wajah se ye unreliable tha: (1) kai
        // devices/custom ROMs par system Equalizer.numberOfPresets 0 hi return karta hai
        // (koi exception nahi aati, bas preset milta hi nahi) — isliye button click karne
        // par UI/sound mein kuch badalta hi nahi tha; (2) jahan preset mil bhi jaaye, wahi
        // vendor-DSP silent no-op limitation lagu hoti hai jo EqualizerAudioProcessor.kt ke
        // top comment mein detail se samjhayi gayi hai. Ab har preset ka apna fixed 5-band
        // dB curve hai jo seedha apne in-app processor (eqProcessor) par jaata hai — 100%
        // guaranteed audible change, har device par, system Equalizer support ke bina bhi.
        val presetCurvesDb = linkedMapOf(
            "Normal" to floatArrayOf(0f, 0f, 0f, 0f, 0f),
            "Classical" to floatArrayOf(3f, 2f, 6f, 4f, 3f),
            "Dance" to floatArrayOf(6f, 4f, -2f, 2f, 4f),
            "Flat" to floatArrayOf(0f, 0f, 0f, 0f, 0f),
            "Folk" to floatArrayOf(2f, 1f, 0f, 1f, 2f),
            "Heavy Metal" to floatArrayOf(5f, 3f, -3f, 4f, 5f),
            "Hip Hop" to floatArrayOf(7f, 4f, 0f, 2f, 3f),
            "Jazz" to floatArrayOf(3f, 2f, 0f, 2f, 3f),
            "Pop" to floatArrayOf(1f, 3f, 4f, 2f, 0f),
            "Rock" to floatArrayOf(6f, 3f, -2f, 3f, 5f)
        )

        fun applyBandCurve(curveDb: FloatArray) {
            val liveEq = equalizer
            val range = try { liveEq?.bandLevelRange } catch (_: Exception) { null }
            for (i in bandSeeks.indices) {
                val gainDb = curveDb.getOrElse(i) { 0f }
                eqProcessor.setBandGainDb(i, gainDb)
                if (range != null) {
                    val levelMilliBel = (gainDb * 100).toInt().coerceIn(range[0].toInt(), range[1].toInt())
                    // Double-EQ fix: range sirf slider scaling ke liye, band-level
                    // system Equalizer par set nahi karte (gain sirf eqProcessor se).
                    bandSeeks[i].max = (range[1] - range[0]).toInt().coerceAtLeast(1)
                    bandSeeks[i].progress = (levelMilliBel - range[0]).toInt()
                } else {
                    bandSeeks[i].max = 3000
                    bandSeeks[i].progress = (((gainDb + 15f) * 100)).toInt().coerceIn(0, 3000)
                }
                bandLabels[i].text = "${gainDb.toInt()} dB"
            }
            eqProcessor.enabled = true
            if (!eqSwitch.isChecked) {
                eqSwitch.setOnCheckedChangeListener(null)
                eqSwitch.isChecked = true
                eqOnOffLabel.text = "On"
                eqSwitch.setOnCheckedChangeListener(eqCheckedChangeListener)
            }
        }

        fun applyPreset(name: String) {
            val curve = presetCurvesDb[name] ?: return
            applyBandCurve(curve)
        }


        presetViews.forEach { (name, tv) ->
            tv.setOnClickListener {
                if (name == "Custom") {
                    highlightPreset("Custom")
                    showGestureFeedback("Custom: sliders manually adjust karein")
                } else {
                    applyPreset(name)
                    highlightPreset(name)
                }
            }
        }

        // --- Volume Boost: EQ ki apni ±dB range khatam hone ke baad bhi loudness badhane
        //     ke liye LoudnessEnhancer use karta hai (0% se 100% = 0dB se +30dB extra gain) ---
        val volumeBoostSeek = view.findViewById<SeekBar>(R.id.volumeBoostSeekBar)
        val volumeBoostValue = view.findViewById<TextView>(R.id.volumeBoostValue)

        val currentGain = try { loudnessEnhancer?.targetGain?.toInt() } catch (_: Exception) { null } ?: 0
        val currentPercent = ((currentGain * 100) / MAX_BOOST_MILLIBEL).coerceIn(0, 100)
        volumeBoostSeek.progress = currentPercent
        volumeBoostValue.text = "$currentPercent%"

        volumeBoostSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                volumeBoostValue.text = "$progress%"
                if (fromUser) {
                    val gainMilliBel = (progress * MAX_BOOST_MILLIBEL) / 100
                    try {
                        loudnessEnhancer?.setTargetGain(gainMilliBel)
                        loudnessEnhancer?.enabled = gainMilliBel > 0
                    } catch (_: Exception) {}
                    // Bug fix: is slider se boost seedha loudnessEnhancer par set
                    // hota tha lekin lastAppliedVolumePercent (jo agla volume swipe
                    // gesture apna start point maanta hai) kabhi update nahi hota
                    // tha. Isse yeh hota tha: Equalizer dialog se boost badlo, phir
                    // volume swipe karo — swipe purani (stale) % se shuru hoke turant
                    // is naye boost ko apni applyVolumePercent() call se overwrite kar
                    // deta tha, aur user ko volume achanak "jump" karta hua lagta tha.
                    // Ab dono jagah ki state (dialog + swipe gesture) hamesha sync rahegi.
                    lastAppliedVolumePercent = getCurrentVolumePercent()
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        // --- Reverb: None / Small Room / Medium Room / Large Room / Medium Hall / Large Hall ---
        val reverbRow = view.findViewById<LinearLayout>(R.id.reverbRow)
        val reverbValue = view.findViewById<TextView>(R.id.reverbValue)
        val reverbOptions = linkedMapOf(
            "None" to PresetReverb.PRESET_NONE,
            "Small Room" to PresetReverb.PRESET_SMALLROOM,
            "Medium Room" to PresetReverb.PRESET_MEDIUMROOM,
            "Large Room" to PresetReverb.PRESET_LARGEROOM,
            "Medium Hall" to PresetReverb.PRESET_MEDIUMHALL,
            "Large Hall" to PresetReverb.PRESET_LARGEHALL
        )

        val currentReverbPreset = try { presetReverb?.preset } catch (_: Exception) { null }
        reverbValue.text = reverbOptions.entries.firstOrNull { it.value == currentReverbPreset }?.key ?: "None"

        reverbRow.setOnClickListener {
            val popup = PopupMenu(this, reverbValue)
            reverbOptions.keys.forEach { label -> popup.menu.add(label) }
            popup.setOnMenuItemClickListener { item ->
                val label = item.title.toString()
                val presetVal = reverbOptions[label] ?: PresetReverb.PRESET_NONE
                try {
                    presetReverb?.enabled = presetVal != PresetReverb.PRESET_NONE
                    presetReverb?.preset = presetVal
                } catch (_: Exception) {}
                reverbValue.text = label
                true
            }
            popup.show()
        }

        // --- Audio Effect tab: MX Player jaisa preset button grid ---
        val effectBoxes = linkedMapOf(
            "Original" to view.findViewById<LinearLayout>(R.id.effectOriginal),
            "Clarity" to view.findViewById<LinearLayout>(R.id.effectClarity),
            "Bass Boost" to view.findViewById<LinearLayout>(R.id.effectBassBoost),
            "Treble Boost" to view.findViewById<LinearLayout>(R.id.effectTrebleBoost),
            "Movie" to view.findViewById<LinearLayout>(R.id.effectMovie),
            "Music" to view.findViewById<LinearLayout>(R.id.effectMusic),
            "Vocal Boost" to view.findViewById<LinearLayout>(R.id.effectVocalBoost),
            "Rock" to view.findViewById<LinearLayout>(R.id.effectRock),
            "Pop" to view.findViewById<LinearLayout>(R.id.effectPop),
            "Party" to view.findViewById<LinearLayout>(R.id.effectParty),
            "3D Surround" to view.findViewById<LinearLayout>(R.id.effect3DSurround),
            "Podcast" to view.findViewById<LinearLayout>(R.id.effectPodcast)
        )

        fun highlightEffect(selected: String) {
            // Selection persist karo taaki dialog dobara khulne par yahi effect
            // highlight ho, hardcoded "Original" pe reset na ho.
            savedEffectName = selected
            effectBoxes.forEach { (name, box) ->
                box.setBackgroundResource(
                    if (name == selected) R.drawable.bg_effect_box_selected
                    else R.drawable.bg_effect_box_unselected
                )
            }
        }

        fun applyAudioEffect(name: String) {
            try {
                when (name) {
                    "Original" -> {
                        bassBoost?.enabled = false
                        virtualizer?.enabled = false
                        presetReverb?.enabled = false
                        try { presetReverb?.preset = PresetReverb.PRESET_NONE } catch (_: Exception) {}
                        applyBandCurve(floatArrayOf(0f, 0f, 0f, 0f, 0f))
                        highlightPreset("Flat")
                    }
                    "Clarity" -> {
                        bassBoost?.enabled = false
                        presetReverb?.enabled = false
                        virtualizer?.apply {
                            try { setStrength(500) } catch (_: Exception) {}
                            enabled = true
                        }
                        applyBandCurve(floatArrayOf(0f, 0f, 2f, 5f, 6f))
                        highlightPreset("Custom")
                    }
                    "Bass Boost" -> {
                        virtualizer?.enabled = false
                        presetReverb?.enabled = false
                        bassBoost?.apply {
                            try { setStrength(900) } catch (_: Exception) {}
                            enabled = true
                        }
                        applyBandCurve(floatArrayOf(9f, 5f, 0f, 0f, 0f))
                        highlightPreset("Custom")
                    }
                    "Treble Boost" -> {
                        bassBoost?.enabled = false
                        virtualizer?.enabled = false
                        presetReverb?.enabled = false
                        applyBandCurve(floatArrayOf(0f, 0f, 0f, 9f, 9f))
                        highlightPreset("Custom")
                    }
                    "Movie" -> {
                        bassBoost?.enabled = false
                        virtualizer?.apply {
                            try { setStrength(1000) } catch (_: Exception) {}
                            enabled = true
                        }
                        presetReverb?.apply {
                            try { preset = PresetReverb.PRESET_MEDIUMHALL } catch (_: Exception) {}
                            enabled = true
                        }
                        applyBandCurve(floatArrayOf(4f, 2f, 0f, 3f, 5f))
                        highlightPreset("Custom")
                    }
                    "Music" -> {
                        virtualizer?.enabled = false
                        bassBoost?.apply {
                            try { setStrength(400) } catch (_: Exception) {}
                            enabled = true
                        }
                        presetReverb?.apply {
                            try { preset = PresetReverb.PRESET_SMALLROOM } catch (_: Exception) {}
                            enabled = true
                        }
                        applyBandCurve(floatArrayOf(3f, 1f, 0f, 2f, 3f))
                        highlightPreset("Custom")
                    }
                    "Vocal Boost" -> {
                        // Vocals mostly ghumte hain 1kHz-4kHz ke aas paas — is range ko
                        // boost karo aur bass thoda kam karo taaki vocals crisp/clear lagein.
                        bassBoost?.enabled = false
                        presetReverb?.enabled = false
                        virtualizer?.apply {
                            try { setStrength(300) } catch (_: Exception) {}
                            enabled = true
                        }
                        applyBandCurve(floatArrayOf(-2f, -1f, 6f, 7f, 3f))
                        highlightPreset("Custom")
                    }
                    "Rock" -> {
                        // Classic rock curve: strong bass + strong treble, mids slightly
                        // scooped — guitars aur drums punchy lagte hain.
                        presetReverb?.enabled = false
                        bassBoost?.apply {
                            try { setStrength(700) } catch (_: Exception) {}
                            enabled = true
                        }
                        virtualizer?.apply {
                            try { setStrength(600) } catch (_: Exception) {}
                            enabled = true
                        }
                        applyBandCurve(floatArrayOf(6f, 3f, -2f, 3f, 6f))
                        highlightPreset("Custom")
                    }
                    "Pop" -> {
                        // Vocals + mids thoda upfront, bass/treble halka sa lift — radio-
                        // friendly balanced sound.
                        presetReverb?.apply {
                            try { preset = PresetReverb.PRESET_SMALLROOM } catch (_: Exception) {}
                            enabled = true
                        }
                        bassBoost?.apply {
                            try { setStrength(300) } catch (_: Exception) {}
                            enabled = true
                        }
                        virtualizer?.enabled = false
                        applyBandCurve(floatArrayOf(2f, 3f, 3f, 1f, 2f))
                        highlightPreset("Custom")
                    }
                    "Party" -> {
                        // Loud, bass-heavy, wide stereo image — dance/club jaisa energetic
                        // sound, thoda room reverb ke saath.
                        bassBoost?.apply {
                            try { setStrength(1000) } catch (_: Exception) {}
                            enabled = true
                        }
                        virtualizer?.apply {
                            try { setStrength(900) } catch (_: Exception) {}
                            enabled = true
                        }
                        presetReverb?.apply {
                            try { preset = PresetReverb.PRESET_MEDIUMROOM } catch (_: Exception) {}
                            enabled = true
                        }
                        applyBandCurve(floatArrayOf(8f, 4f, -1f, 3f, 7f))
                        highlightPreset("Custom")
                    }
                    "3D Surround" -> {
                        // Virtualizer ko max strength par le jao taaki stereo widening/
                        // spatial effect sabse zyada noticeable ho — EQ curve neutral rakho
                        // taaki sirf spatial widening ka effect suna jaaye.
                        bassBoost?.enabled = false
                        virtualizer?.apply {
                            try { setStrength(1000) } catch (_: Exception) {}
                            enabled = true
                        }
                        presetReverb?.apply {
                            try { preset = PresetReverb.PRESET_LARGEHALL } catch (_: Exception) {}
                            enabled = true
                        }
                        applyBandCurve(floatArrayOf(1f, 0f, 0f, 1f, 2f))
                        highlightPreset("Custom")
                    }
                    "Podcast" -> {
                        // Speech clarity ke liye: low-end rumble kam karo, mid-range (jahan
                        // human voice sabse zyada energy rakhti hai) ko boost karo, koi
                        // reverb/virtualizer nahi taaki voice dry aur clear sune.
                        bassBoost?.enabled = false
                        virtualizer?.enabled = false
                        presetReverb?.enabled = false
                        applyBandCurve(floatArrayOf(-4f, -1f, 5f, 5f, 1f))
                        highlightPreset("Custom")
                    }
                }
            } catch (_: Exception) {}
        }

        effectBoxes.forEach { (name, box) ->
            box.setOnClickListener {
                applyAudioEffect(name)
                highlightEffect(name)
            }
        }
        // Pehle yahan hardcoded "Original" hota tha, isliye dialog dobara khulte hi
        // aisa lagta tha jaise Audio Effect selection reset ho gayi — chahe user ne
        // pehle Bass Boost/Movie/Music waghera select kiya ho. Ab last-selected
        // effect hi highlight hoga.
        highlightEffect(savedEffectName)

        dialog.show()
    }

    // Current effect settings ko instance-level fields mein save karta hai, taaki
    // player rebuild (decoder switch) ke baad bhi user ki EQ/Bass/Virtualizer/
    // Reverb/Volume Boost settings restore ho sakein.
    private fun captureAudioFxState() {
        equalizer?.let { eq ->
            savedEqEnabled = eq.enabled
            try { savedBandLevels = (0 until eq.numberOfBands).map { eq.getBandLevel(it.toShort()) } } catch (_: Exception) {}
        }
        bassBoost?.let {
            savedBassEnabled = it.enabled
            try { savedBassStrength = it.roundedStrength } catch (_: Exception) {}
        }
        virtualizer?.let {
            savedVirtEnabled = it.enabled
            try { savedVirtStrength = it.roundedStrength } catch (_: Exception) {}
        }
        presetReverb?.let {
            try { savedReverbPreset = it.preset } catch (_: Exception) {}
        }
        loudnessEnhancer?.let {
            savedLoudnessEnabled = it.enabled
            try { savedLoudnessGain = it.targetGain } catch (_: Exception) {}
        }
    }

    private fun setupAudioFx(sessionId: Int) {
        if (audioFxAttached && attachedSessionId == sessionId) return

        // Reattach ho raha hai to purani settings (agar thi) yaad rakho, taaki user
        // ka EQ / bass / virtualizer setup dobara se flat pe reset na ho jaaye.
        // Pehle live effect object se try karo, agar wo already release ho chuka hai
        // (jaise decoder-mode switch ke baad) to saved fields se fallback karo.
        val prevBassEnabled = bassBoost?.enabled ?: savedBassEnabled
        val prevBassStrength = (try { bassBoost?.roundedStrength } catch (_: Exception) { null }) ?: savedBassStrength
        val prevVirtEnabled = virtualizer?.enabled ?: savedVirtEnabled
        val prevVirtStrength = (try { virtualizer?.roundedStrength } catch (_: Exception) { null }) ?: savedVirtStrength
        val prevReverbPreset = (try { presetReverb?.preset } catch (_: Exception) { null }) ?: savedReverbPreset
        val prevLoudnessGain = (try { loudnessEnhancer?.targetGain } catch (_: Exception) { null }) ?: savedLoudnessGain
        val prevLoudnessEnabled = loudnessEnhancer?.enabled ?: savedLoudnessEnabled

        releaseAudioFx()

        // BUG FIX: pehle ye sab 5 effects (Equalizer, BassBoost, Virtualizer, PresetReverb,
        // LoudnessEnhancer) EK hi try/catch mein bante the — matlab agar in mein se koi EK
        // bhi effect us device par unsupported hota (jaisa kai OEM ROMs — vivo/Realme/Oppo
        // waghera — mein BassBoost/Virtualizer/PresetReverb ka apna vendor audio-effect stack
        // hone ki wajah se hota hai), to poora block turant exception fenk deta tha aur BAAKI
        // sab effects (jinme Equalizer bhi included, jo har device par almost hamesha available
        // hota hai) bhi silently attach hi nahi hote the — sirf ek generic "not supported"
        // toast dikhta tha, chahe equalizer khud kaam kar sakta ho. Ab har effect apne alag
        // try/catch mein banta hai, taaki jo effect us device par supported hai wo zaroor
        // kaam kare, aur sirf wahi effect skip ho jo genuinely unsupported hai.
        var anySucceeded = false

        try {
            // BUG FIX (double-EQ / distortion): pehle yahan system Equalizer ko bhi
            // prevEqEnabled + prevBandLevels ke saath enable kiya jaata tha — jin
            // devices par vendor HAL isse genuinely process karta hai (sab nahi karte,
            // EqualizerAudioProcessor.kt ka top comment dekho), wahan gain DO baar lagta
            // tha: ek baar system Equalizer se, aur ek baar hamare apne PCM-level
            // eqProcessor se (jo primary source-of-truth hai, neeche dialog code mein).
            // +6dB ka band asal mein ~+12dB ban jaata tha -> loud distortion/clipping.
            // Fix: system Equalizer object sirf bandLevelRange padhne (UI slider scaling)
            // ke liye rakho, kabhi khud enable ya band-level set mat karo — actual gain
            // hamesha sirf eqProcessor se aana chahiye, har device par consistent.
            equalizer = Equalizer(0, sessionId).apply {
                enabled = false
            }
            anySucceeded = true
        } catch (_: Exception) {
            equalizer = null
        }

        try {
            bassBoost = BassBoost(0, sessionId).apply {
                enabled = prevBassEnabled ?: false
                prevBassStrength?.let { try { setStrength(it) } catch (_: Exception) {} }
            }
            anySucceeded = true
        } catch (_: Exception) {
            bassBoost = null
        }

        try {
            virtualizer = Virtualizer(0, sessionId).apply {
                enabled = prevVirtEnabled ?: false
                prevVirtStrength?.let { try { setStrength(it) } catch (_: Exception) {} }
            }
            anySucceeded = true
        } catch (_: Exception) {
            virtualizer = null
        }

        try {
            presetReverb = PresetReverb(0, sessionId).apply {
                enabled = (prevReverbPreset ?: PresetReverb.PRESET_NONE) != PresetReverb.PRESET_NONE
                try { preset = prevReverbPreset ?: PresetReverb.PRESET_NONE } catch (_: Exception) {}
            }
            anySucceeded = true
        } catch (_: Exception) {
            presetReverb = null
        }

        try {
            loudnessEnhancer = LoudnessEnhancer(sessionId).apply {
                try { setTargetGain((prevLoudnessGain ?: 0f).toInt()) } catch (_: Exception) {}
                enabled = prevLoudnessEnabled ?: false
            }
            anySucceeded = true
        } catch (_: Exception) {
            loudnessEnhancer = null
        }

        if (anySucceeded) {
            audioFxAttached = true
            attachedSessionId = sessionId
        } else {
            audioFxAttached = false
            showPlayerSnackbar("Audio effects is device par supported nahi hain", isError = true)
        }
    }

    private fun releaseAudioFx() {
        try { equalizer?.release() } catch (_: Exception) {}
        try { bassBoost?.release() } catch (_: Exception) {}
        try { virtualizer?.release() } catch (_: Exception) {}
        try { presetReverb?.release() } catch (_: Exception) {}
        try { loudnessEnhancer?.release() } catch (_: Exception) {}
        equalizer = null
        bassBoost = null
        virtualizer = null
        presetReverb = null
        loudnessEnhancer = null
        audioFxAttached = false
        attachedSessionId = C.AUDIO_SESSION_ID_UNSET
    }

    // ---------------------------------------------------------------------
    // More menu: poora grid (screenshot jaisa) + Video Display / Shortcuts switches
    // ---------------------------------------------------------------------
    private fun showMoreMenu() {
        // Teen dot dobara dabaya jab panel pehle se khula hai -> band kar do
        // (toggle behavior).
        if (moreMenuPanel != null) {
            hideMoreMenu()
            return
        }

        // User ne maanga tha: panel khulte hi video apne aap pause ho jaaye.
        if (::player.isInitialized && player.isPlaying) {
            player.pause()
        }

        // Logical grouping (jo cheez zyada use hoti hai wo pehle): Playback &
        // Queue -> Display & Appearance -> Tools -> Library & Info. User apna
        // custom order drag-reorder se already bana chuka ho to wahi
        // applySavedMoreMenuOrder() mein priority leta hai — ye sirf naya/
        // reset order hai.
        val items = listOf(
            // -- Playback & Queue --
            Triple("Playing Queue", R.drawable.ic_playlist_play, false),
            // Settings-icon -> "Playback Speed" -> speed panel, YouTube jaisa
            // flow — badge/topbar wale speedButton se seedha bhi khulta hai,
            // yeh sirf ussi showSpeedSheet() ka doosra entry-point hai.
            Triple("Playback Speed", R.drawable.ic_speed_ramp, false),
            Triple("A-B Repeat", R.drawable.ic_ab_repeat, false),
            Triple("Prev Frame", R.drawable.ic_frame_back, false),
            Triple("Next Frame", R.drawable.ic_frame_forward, false),
            Triple("Sleep Timer", R.drawable.ic_timer, false),
            Triple("Shuffle", R.drawable.ic_shuffle, false),
            Triple("Loop", R.drawable.ic_loop, false),
            Triple("Mute", R.drawable.ic_volume_up, false),
            // -- Display & Appearance --
            // "Aspect Ratio" hata diya — bottom control bar mein already
            // hamesha visible hai (bottomAspectButton), yahan duplicate tha.
            Triple("Rotate", R.drawable.ic_screen_rotation, false),
            Triple("Display Settings", R.drawable.ic_equalizer, false),
            Triple("Night Mode", R.drawable.ic_night_mode, false),
            // -- Tools --
            Triple("Gesture Settings", R.drawable.ic_settings, false),
            Triple("Bookmark", R.drawable.ic_bookmark, false),
            Triple("Cut", R.drawable.ic_ab_repeat, false),
            Triple("Screenshot", R.drawable.ic_camera, false),
            Triple("Cast", R.drawable.ic_cast, false),
            Triple("Audio Effect", R.drawable.ic_equalizer, false),
            // "Background Play" hata diya — yeh asal mein bottom control bar
            // ke PiP button jaisa hi hai (dono tryEnterPipOnBack() call karte
            // the), sirf naam alag tha. Duplicate tha isliye hata diya.
            Triple("Playback Stats", R.drawable.ic_stats_nerds, false),
            Triple("Smart Chapters", R.drawable.ic_chapters, false),
            Triple("Speed Ramp", R.drawable.ic_speed_ramp, false),
            // -- Library & Info --
            Triple("Favourite", R.drawable.ic_favorite, true),
            Triple("Add To Playlist", R.drawable.ic_playlist_play, true),
            Triple("Information", R.drawable.ic_info, false),
            Triple("Share", R.drawable.ic_share, false),
            Triple("Quality", R.drawable.ic_quality, false),
            Triple("Network Stream", R.drawable.ic_network_stream, false),
            Triple("Tutorial", R.drawable.ic_info, false)
        )

        // UNIQUE LOOK v5 — pehle grid manually LinearLayout rows se bana tha
        // aur vertical scroll ho sakta tha (jisse content kabhi-kabhi neeche
        // se cut dikhta tha). User ne maanga: kabhi vertical scroll na ho,
        // poora panel hamesha exactly 2 rows mein poora complete dikhe, aur
        // agar future mein aur icons add hon to sirf horizontal (left/right)
        // scroll se dikhein. Isliye ab ye ek RecyclerView hai jiska
        // GridLayoutManager HORIZONTAL orientation + spanCount=2 use karta
        // hai — matlab hamesha exactly 2 fixed rows, aur extra items horizontal
        // direction mein scroll hote hain, kabhi neeche nahi.
        // Bonus feature (jaisa maanga gaya): ItemTouchHelper se long-press
        // karke kisi bhi icon ko drag karke doosri jagah drop kar sakte ho
        // (mobile home-screen apps jaisa) — naya order SharedPreferences mein
        // save ho jaata hai, agli baar bhi wahi order yaad rehta hai.
        val topMarginPx = dp(86)
        // FIX: pehle sirf 10dp side margin thi, jisse panel edge-to-edge ke
        // bahut kareeb tha (ek taraf navigation-bar/edge controls se takra
        // sakta tha). Ab dono side thodi zyada margin hai.
        val sideMarginPx = dp(20)
        val closeButtonSizePx = dp(28)
        val gridPaddingPx = dp(8)
        // FIX: pehle close button ke liye ek alag "header strip" banayi thi,
        // jo khud hi ek bada khaali band ban gayi thi (upar bahut zyada
        // khaali jagah). Ab koi alag header nahi — close button seedha grid
        // ke corner par overlay hai, aur top/bottom padding dono EXACTLY
        // barabar hain (close button ke liye jitni jagah upar chahiye, utni
        // hi neeche bhi rakhi hai) — isliye upar-neeche gap ab sach mein
        // symmetric hai.
        val gridVerticalPaddingPx = closeButtonSizePx + dp(10)
        val cellHeightPx = dp(76)
        val cellWidthPx = dp(70)
        val recyclerHeightPx = (cellHeightPx * 2) + (gridVerticalPaddingPx * 2)

        // Panel ka apna background halka hai (bg_more_menu_panel: ~55%
        // opacity). FrameLayout isliye taaki close (X) button grid ke upar
        // corner mein overlay ho sake, bina alag jagah liye.
        val panel = FrameLayout(this).apply {
            background = ContextCompat.getDrawable(this@PlayerActivity, R.drawable.bg_more_menu_panel)
            elevation = dp(10).toFloat()
            isClickable = true
            setOnClickListener { /* no-op: panel ke andar tap se peeche scrim tak na pahunche */ }
        }

        fun handleItemClick(label: String) {
            hideMoreMenu()
            when (label) {
                "Playing Queue" -> showPlayingQueueDialog()
                "Playback Speed" -> showSpeedSheet()
                "Display Settings" -> showDisplaySettingsDialog()
                "Gesture Settings" -> showGestureSettingsDialog()
                "Bookmark" -> showBookmarkDialog()
                "Cut" -> showCutDialog()
                "Favourite" -> toggleFavourite()
                "Add To Playlist" -> showAddToPlaylistDialog()
                "Information" -> showInformationDialog()
                "Share" -> shareCurrentVideo()
                "Quality" -> showQualityDialog()
                "Network Stream" -> showNetworkStreamDialog()
                "Tutorial" -> showTutorialDialog()
                // In sabki apni click-logic/toggle-state pehle se hidden
                // dock buttons par attached hai — performClick() se wahi
                // reuse ho jaati hai, dobara likhne ki zaroorat nahi.
                "Cast" -> castButton.performClick()
                "Audio Effect" -> equalizerButton.performClick()
                "Screenshot" -> screenshotButton.performClick()
                "Rotate" -> rotateButton.performClick()
                "A-B Repeat" -> abRepeatButton.performClick()
                "Playback Stats" -> statsForNerdsButton.performClick()
                "Smart Chapters" -> analyzeSceneChapters(showDialogWhenDone = true)
                "Speed Ramp" -> showSpeedRampDialog()
                "Night Mode" -> nightModeButton.performClick()
                "Shuffle" -> shuffleButton.performClick()
                "Loop" -> loopButton.performClick()
                "Mute" -> muteButton.performClick()
                "Sleep Timer" -> sleepTimerButton.performClick()
                "Prev Frame" -> frameBackButton.performClick()
                "Next Frame" -> frameForwardButton.performClick()
            }
        }

        val orderedItems = applySavedMoreMenuOrder(items)
        val adapter = MoreMenuAdapter(orderedItems, cellWidthPx, cellHeightPx) { label -> handleItemClick(label) }

        val recyclerView = RecyclerView(this).apply {
            layoutManager = GridLayoutManager(this@PlayerActivity, 2, GridLayoutManager.HORIZONTAL, false)
            clipToPadding = false
            setPadding(gridPaddingPx, gridVerticalPaddingPx, gridPaddingPx, gridVerticalPaddingPx)
            overScrollMode = View.OVER_SCROLL_NEVER
            this.adapter = adapter
        }

        // Drag-to-reorder: long-press karke kisi bhi icon ko pakad ke kahin
        // aur drop kar sakte ho, jaisa mobile home-screen apps mein hota hai.
        ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT or ItemTouchHelper.UP or ItemTouchHelper.DOWN,
            0
        ) {
            override fun onMove(
                rv: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                val from = viewHolder.bindingAdapterPosition
                val to = target.bindingAdapterPosition
                if (from == RecyclerView.NO_POSITION || to == RecyclerView.NO_POSITION) return false
                adapter.moveItem(from, to)
                return true
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                // Swipe-to-dismiss disabled — icons sirf reorder hote hain, delete nahi.
            }

            override fun clearView(rv: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                super.clearView(rv, viewHolder)
                // Drag khatam hote hi naya order save kar do taaki agli baar bhi yaad rahe.
                saveMoreMenuOrder(adapter.currentLabelOrder())
            }
        }).attachToRecyclerView(recyclerView)

        panel.addView(
            recyclerView,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, recyclerHeightPx)
        )

        // Close (X) button — grid ke top-right corner par overlay (uske
        // upar-neeche jitni padding chahiye thi wo pehle se hi grid ki
        // gridVerticalPaddingPx mein shaamil hai, isliye ye kisi icon se
        // takrata nahi aur top/bottom symmetry bhi nahi todta).
        val closeButton = FrameLayoutCircle(this, closeButtonSizePx, R.drawable.ic_close, false).apply {
            setOnClickListener { hideMoreMenu() }
        }
        panel.addView(
            closeButton,
            FrameLayout.LayoutParams(closeButtonSizePx, closeButtonSizePx).apply {
                gravity = android.view.Gravity.TOP or android.view.Gravity.END
                topMargin = dp(6)
                rightMargin = dp(8)
            }
        )

        // Scrim: poori tarah transparent (koi dark dim nahi — screen jaisa
        // hai waisa hi dikhega), sirf outside-tap capture karne ke liye taaki
        // panel ke bahar tap karne par menu band ho jaaye.
        val scrim = View(this).apply {
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            isClickable = true
            setOnClickListener { hideMoreMenu() }
        }
        playerContainer.addView(
            scrim,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        )
        playerContainer.addView(
            panel,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
                gravity = android.view.Gravity.TOP
                topMargin = topMarginPx
                leftMargin = sideMarginPx
                rightMargin = sideMarginPx
            }
        )
        moreMenuScrim = scrim
        moreMenuPanel = panel

        // Panel khud bhi ek premium pop-in ke saath aaye (sirf icons hi
        // stagger nahi, poora card bhi halka scale-up + fade-in ke saath
        // upar se "settle" hota hai) — top-center se pivot taaki natural lage.
        panel.alpha = 0f
        panel.scaleX = 0.94f
        panel.scaleY = 0.94f
        panel.translationY = dp(-10).toFloat()
        // width abhi tak 0 hai (layout pass baaki hai), isliye pivot ko
        // ek frame baad set karte hain taaki scale sach mein center se ho.
        panel.post {
            panel.pivotX = panel.width.toFloat() / 2f
            panel.pivotY = 0f
            panel.animate()
                .alpha(1f)
                .scaleX(1f).scaleY(1f)
                .translationY(0f)
                .setDuration(200)
                .setInterpolator(android.view.animation.DecelerateInterpolator())
                .start()
        }
    }

    /** More menu panel + uska scrim dono ko ek chhoti exit animation ke saath screen se hata deta hai. */
    private fun hideMoreMenu() {
        val panel = moreMenuPanel
        val scrim = moreMenuScrim
        if (panel == null && scrim == null) return
        moreMenuPanel = null
        moreMenuScrim = null

        if (panel != null) {
            panel.animate()
                .alpha(0f)
                .scaleX(0.94f).scaleY(0.94f)
                .translationY(dp(-8).toFloat())
                .setDuration(140)
                .setInterpolator(android.view.animation.AccelerateInterpolator())
                .withEndAction { (panel.parent as? android.view.ViewGroup)?.removeView(panel) }
                .start()
        }
        scrim?.let { (it.parent as? android.view.ViewGroup)?.removeView(it) }
    }

    /** User ka saved custom icon-order load karta hai; naye (future) items jo saved order mein nahi hain woh end mein appear ho jaate hain. */
    private fun applySavedMoreMenuOrder(defaultItems: List<Triple<String, Int, Boolean>>): MutableList<Triple<String, Int, Boolean>> {
        val prefs = getSharedPreferences("more_menu_prefs", MODE_PRIVATE)
        val savedOrderStr = prefs.getString("item_order", null) ?: return defaultItems.toMutableList()
        val savedLabels = savedOrderStr.split("|")
        val byLabel = defaultItems.associateBy { it.first }
        val ordered = mutableListOf<Triple<String, Int, Boolean>>()
        savedLabels.forEach { label -> byLabel[label]?.let { ordered.add(it) } }
        defaultItems.forEach { item -> if (item.first !in savedLabels) ordered.add(item) }
        return ordered
    }

    /** More menu ka current icon-order save karta hai (drag-reorder ke baad). */
    private fun saveMoreMenuOrder(order: List<String>) {
        val prefs = getSharedPreferences("more_menu_prefs", MODE_PRIVATE)
        prefs.edit().putString("item_order", order.joinToString("|")).apply()
    }

    /** More menu grid ka RecyclerView adapter — icon + label cells banata hai aur drag-reorder ke liye moveItem() expose karta hai. */
    private inner class MoreMenuAdapter(
        private val menuItems: MutableList<Triple<String, Int, Boolean>>,
        private val cellWidthPx: Int,
        private val cellHeightPx: Int,
        private val onClick: (String) -> Unit
    ) : RecyclerView.Adapter<MoreMenuAdapter.VH>() {

        // Panel jab bhi khulta hai tabhi ek baar staggered entrance animation
        // chale — drag-reorder (notifyItemMoved) par dobara na chale, isliye
        // position ko yahan track karte hain.
        private val entranceAnimatedPositions = mutableSetOf<Int>()

        inner class VH(
            val cell: LinearLayout,
            val iconView: ImageView,
            val dot: View,
            val text: TextView
        ) : RecyclerView.ViewHolder(cell)

        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): VH {
            val iconSizePx = dp(34)
            val cell = LinearLayout(parent.context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = android.view.Gravity.CENTER
                setPadding(dp(4), dp(6), dp(4), dp(6))
                layoutParams = android.view.ViewGroup.LayoutParams(cellWidthPx, cellHeightPx)
                isClickable = true
                foreground = ContextCompat.getDrawable(this@PlayerActivity, android.R.drawable.list_selector_background)
            }
            val iconFrame = FrameLayout(parent.context).apply {
                layoutParams = LinearLayout.LayoutParams(iconSizePx, iconSizePx)
                background = ContextCompat.getDrawable(this@PlayerActivity, R.drawable.bg_icon_circle)
            }
            val iconView = ImageView(parent.context).apply {
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    android.view.Gravity.CENTER
                )
            }
            iconFrame.addView(iconView)
            val dotSizePx = dp(6)
            val dot = View(parent.context).apply {
                layoutParams = FrameLayout.LayoutParams(dotSizePx, dotSizePx, android.view.Gravity.TOP or android.view.Gravity.END)
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setColor(android.graphics.Color.parseColor("#FF3B30"))
                }
                visibility = View.GONE
            }
            iconFrame.addView(dot)
            val text = TextView(parent.context).apply {
                textSize = 9f
                maxLines = 2
                setTextColor(android.graphics.Color.WHITE)
                gravity = android.view.Gravity.CENTER
                setPadding(0, dp(4), 0, 0)
            }
            cell.addView(iconFrame)
            cell.addView(text)
            val holder = VH(cell, iconView, dot, text)
            cell.setOnClickListener {
                val pos = holder.bindingAdapterPosition
                if (pos == RecyclerView.NO_POSITION) return@setOnClickListener
                // Premium tap feedback: icon thoda "squeeze" hoke wapas pop
                // hota hai (overshoot bounce), fir action chalti hai — koi
                // dusre player ka clone nahi, apna hi chhota micro-interaction.
                iconFrame.animate().cancel()
                iconFrame.animate()
                    .scaleX(0.82f).scaleY(0.82f)
                    .setDuration(90)
                    .setInterpolator(android.view.animation.DecelerateInterpolator())
                    .withEndAction {
                        iconFrame.animate()
                            .scaleX(1f).scaleY(1f)
                            .setDuration(180)
                            .setInterpolator(android.view.animation.OvershootInterpolator(3.5f))
                            .start()
                    }
                    .start()
                onClick(menuItems[pos].first)
            }
            return holder
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val (label, iconRes, showDot) = menuItems[position]
            holder.iconView.setImageResource(iconRes)
            holder.dot.visibility = if (showDot) View.VISIBLE else View.GONE
            holder.text.text = label

            // Staggered fade + slide-up + scale-in jab panel pehli baar khulta
            // hai — har icon thodi der baad ek-ek karke "pop" hota hai
            // (column-wise stagger, kyunki grid HORIZONTAL hai).
            if (entranceAnimatedPositions.add(position)) {
                holder.cell.alpha = 0f
                holder.cell.translationY = dp(14).toFloat()
                holder.cell.scaleX = 0.85f
                holder.cell.scaleY = 0.85f
                holder.cell.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .scaleX(1f).scaleY(1f)
                    .setStartDelay((position / 2) * 28L)
                    .setDuration(220)
                    .setInterpolator(android.view.animation.DecelerateInterpolator())
                    .start()
            }
        }

        override fun getItemCount(): Int = menuItems.size

        /** Drag ke dauraan do items ki position swap karta hai (ItemTouchHelper.onMove se call hota hai). */
        fun moveItem(from: Int, to: Int) {
            val moved = menuItems.removeAt(from)
            menuItems.add(to, moved)
            notifyItemMoved(from, to)
        }

        fun currentLabelOrder(): List<String> = menuItems.map { it.first }
    }

    /** Video renderer/track ko band-chalu karta hai — audio playback continue rehta hai. */
    private fun setAudioOnlyMode(enabled: Boolean) {
        audioOnlyModeOn = enabled
        if (::player.isInitialized) {
            player.trackSelectionParameters = player.trackSelectionParameters
                .buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, enabled)
                .build()
        }
        audioOnlyOverlay.visibility = if (enabled) View.VISIBLE else View.GONE
        showGestureFeedback(if (enabled) "Audio Only ON" else "Audio Only OFF")
    }

    /** "Video Display" / "Shortcuts" jaisi label + Switch wali row banata hai (screenshot jaisi). */
    private fun buildToggleRow(
        label: String,
        checked: Boolean,
        dp: (Int) -> Int,
        iconRes: Int? = null,
        onChange: (Boolean) -> Unit
    ): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(14), dp(20), dp(14))

            // Baaki grid items jaisa hi chhota circular icon — "simple jaise
            // dusre ka hai" (user ne yehi maanga tha).
            if (iconRes != null) {
                val icon = FrameLayoutCircle(this@PlayerActivity, dp(36), iconRes, false)
                (icon.layoutParams as LinearLayout.LayoutParams).marginEnd = dp(14)
                addView(icon)
            }

            addView(TextView(this@PlayerActivity).apply {
                text = label
                textSize = 16f
                setTextColor(android.graphics.Color.WHITE)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })

            addView(Switch(this@PlayerActivity).apply {
                isChecked = checked
                setOnCheckedChangeListener { _, isChecked -> onChange(isChecked) }
            })
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    /**
     * MX Player jaisa right-side floating overlay panel banata hai, lekin latest/rounded card
     * styling ke saath — rounded left corners, subtle border + elevation shadow, aur top-right
     * mein ek close (✕) button jisse panel ko turant band kiya ja sake. Video peeche hamesha
     * visible rehta hai (halka dim ke saath), screen fully dhakta nahi.
     *
     * Width hamesha screen ke CHHOTE dimension (portrait width) ke hisaab se nikalte hain, taaki
     * landscape mein panel bahut chauda na ho jaye aur portrait mein bahut sankra na ho — dono
     * orientation mein ek jaisa, compact size milta hai.
     */
    private fun showOverlayPanel(contentView: View, widthFraction: Double = 0.56): AlertDialog {
        val cardRadius = dp(20).toFloat()
        val cardBg = GradientDrawable().apply {
            // Pehle almost-solid black tha (#ED = 93% opacity); ab dim/translucent kiya hai
            // taaki peeche chal raha video halka sa dikhta rahe (glass jaisa effect). Jahan
            // real window-blur available hai (API 31+), card thoda aur transparent rakha hai
            // taaki blurred video peeche se saaf dikhe — asli "frosted glass" jaisa.
            val glassColor = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                "#99131318" else "#D9131318"
            setColor(android.graphics.Color.parseColor(glassColor))
            cornerRadii = floatArrayOf(
                cardRadius, cardRadius,   // top-left
                0f, 0f,                  // top-right (screen edge se milta hai)
                0f, 0f,                  // bottom-right
                cardRadius, cardRadius   // bottom-left
            )
            setStroke(dp(1), android.graphics.Color.parseColor("#26FFFFFF"))
        }

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = cardBg
            elevation = dp(16).toFloat()
            outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
            clipToOutline = true
            // Explicit MATCH_PARENT zaroori hai warna weight-based content orientation ke hisaab
            // se alag-alag height le leta hai aur top bar (close button) kabhi kabhi screen ke
            // bahar chala jaata hai (landscape mein yehi bug tha).
            layoutParams = android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        // Slim top strip: sirf close (X) button, taaki neeche ke title/content se overlap na ho
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(8), dp(12), dp(4))
        }
        topBar.addView(View(this), LinearLayout.LayoutParams(0, dp(1), 1f))
        val closeBtn = TextView(this).apply {
            text = "\u2715"
            textSize = 14f
            setTextColor(android.graphics.Color.WHITE)
            gravity = android.view.Gravity.CENTER
            isClickable = true
            isFocusable = true
            val circleBg = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(android.graphics.Color.parseColor("#26FFFFFF"))
            }
            background = android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#40FFFFFF")),
                circleBg, circleBg
            )
            layoutParams = LinearLayout.LayoutParams(dp(28), dp(28))
        }
        topBar.addView(closeBtn)
        card.addView(topBar)

        card.addView(contentView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        val dialog = AlertDialog.Builder(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
            .setView(card)
            .create()
        dialog.show()
        closeBtn.setOnClickListener { dialog.dismiss() }
        dialog.window?.let { win ->
            win.setBackgroundDrawableResource(android.R.color.transparent)
            // Premium frosted-glass touch: Android 12+ (API 31) par peeche chal raha video
            // asli blur ke saath dikhta hai (RenderEffect-backed window blur) — glass jaisa
            // depth milta hai, panel content clearly separate lagta hai. Purane devices par
            // ye API available nahi hai, isliye wahan thoda zyada dim amount use karte hain
            // taaki panel still "elevated/focused" feel de, bina asli blur ke bhi.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                try {
                    win.setBackgroundBlurRadius(dp(28))
                    win.setDimAmount(0.1f)
                } catch (_: Exception) {
                    win.setDimAmount(0.35f)
                }
            } else {
                win.setDimAmount(0.35f)
            }
            // Ye dialog apni alag Window hai (Activity ki window se different), isliye usko bhi
            // Activity jaisa hi edge-to-edge + system bars hidden banana zaroori hai — warna status
            // bar/gesture nav bar ke liye jagah reserve ho jaati hai aur panel ka top/bottom hissa
            // screen se bahar/cut hua dikhta hai (yehi "pura panel dikh nahi raha" wala bug tha).
            WindowCompat.setDecorFitsSystemWindows(win, false)
            val insetsController = WindowInsetsControllerCompat(win, card)
            insetsController.hide(WindowInsetsCompat.Type.systemBars())
            insetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            win.addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
            win.setGravity(android.view.Gravity.END)
            val dm = resources.displayMetrics
            val shorterSide = minOf(dm.widthPixels, dm.heightPixels)
            // Ab labels chhote (single line) hain, isliye width wapas compact rakhi hai.
            // Extra safety: chahe kuch bhi ho, panel kabhi bhi screen width se zyada nahi hoga
            // (warna close/gear button screen ke bahar chala jaata hai jaisa pehle ho raha tha).
            val targetWidth = (shorterSide * widthFraction).toInt()
                .coerceIn(dp(240), dp(320))
                .coerceAtMost(dm.widthPixels - dp(24))
            win.setLayout(targetWidth, WindowManager.LayoutParams.MATCH_PARENT)

            // Video ko panel jitni jagah shrink kar do (side-by-side), taaki poora video bhi
            // dikhta rahe aur panel bhi — sirf pehla panel khulne par shrink karna hai
            // (Subtitle -> Settings jaisa nested panel dubara shrink na kare).
            openOverlayPanelCount++
            if (openOverlayPanelCount == 1) {
                animatePlayerContainerWidth(dm.widthPixels - targetWidth)
            }
        }
        dialog.setOnDismissListener {
            openOverlayPanelCount = (openOverlayPanelCount - 1).coerceAtLeast(0)
            if (openOverlayPanelCount == 0) {
                animatePlayerContainerWidth(resources.displayMetrics.widthPixels)
            }
        }
        return dialog
    }

    /** playerContainer ki width ko smoothly animate karta hai (panel open -> shrink, close -> restore). */
    private fun animatePlayerContainerWidth(toWidthPx: Int) {
        if (!::playerContainer.isInitialized) return
        val fromWidthPx = playerContainer.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
        if (fromWidthPx == toWidthPx) return
        playerContainerWidthAnimator?.cancel()
        playerContainerWidthAnimator = android.animation.ValueAnimator.ofInt(fromWidthPx, toWidthPx).apply {
            duration = 220
            interpolator = android.view.animation.DecelerateInterpolator()
            addUpdateListener { anim ->
                val lp = playerContainer.layoutParams
                lp.width = anim.animatedValue as Int
                playerContainer.layoutParams = lp
            }
            start()
        }
    }

    /**
     * File ke andar jo asli track name/language metadata hota hai (jaisa MX Player dikhata hai),
     * usse ek readable label banata hai, e.g. "[HindiAnimeZone.com] - Hindi".
     * Agar format mein kuch nahi milta to generic "Track #n" fallback deta hai.
     */
    private fun trackDisplayLabel(format: androidx.media3.common.Format, fallbackNumber: Int, fallbackPrefix: String = "Track"): String {
        val label = format.label?.trim()?.takeIf { it.isNotEmpty() }
        val langCode = format.language?.trim()?.takeIf { it.isNotEmpty() && !it.equals("und", ignoreCase = true) }
        val langName = langCode?.let {
            try {
                val name = java.util.Locale(it).getDisplayLanguage(java.util.Locale.ENGLISH)
                if (name.isNotBlank() && !name.equals(it, ignoreCase = true)) name else it
            } catch (_: Exception) {
                it
            }
        }
        // Sirf plain language naam — bracket ya credit line nahi, taaki row chhoti aur
        // ek-line mein hi fit ho jaaye.
        return when {
            langName != null -> langName
            label != null -> label
            else -> "$fallbackPrefix #$fallbackNumber"
        }
    }

    // ---------------------------------------------------------------------
    // Phase 3 shared UI helpers: Subtitle menu / Subtitles Customization /
    // Audio Track full-screen dialog in sabhi mein use hote hain
    // ---------------------------------------------------------------------

    /** "Subtitle" / "Subtitles Customization" title bar, "Online subtitles" link ke saath. */
    private fun buildSubtitleHeader(title: String, showBack: Boolean, onBack: (() -> Unit)?): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(16))

            if (showBack) {
                addView(ImageView(this@PlayerActivity).apply {
                    layoutParams = LinearLayout.LayoutParams(dp(32), dp(32)).apply { marginEnd = dp(16) }
                    setImageResource(R.drawable.ic_back)
                    isClickable = true
                    foreground = androidx.core.content.ContextCompat.getDrawable(
                        this@PlayerActivity, android.R.drawable.list_selector_background
                    )
                    setOnClickListener { onBack?.invoke() }
                })
            }

            addView(TextView(this@PlayerActivity).apply {
                text = title
                textSize = 22f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(android.graphics.Color.WHITE)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })

            addView(TextView(this@PlayerActivity).apply {
                text = "Online subtitles"
                textSize = 15f
                setTextColor(android.graphics.Color.parseColor("#CCCCCC"))
                isClickable = true
                setOnClickListener { showOnlineSubtitleSearch() }
            })
        }
    }

    /** "Layout" / "Text" jaisa bold section label. */
    private fun sectionHeader(text: String): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(android.graphics.Color.WHITE)
            setPadding(0, dp(10), 0, dp(14))
        }
    }

    /** Sirf visual gap. */
    private fun spacer(heightPx: Int): View {
        return View(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, heightPx)
        }
    }

    /** "Font   Default ▾" jaisi dropdown row (label left, value + arrow right, poori row clickable). */
    private fun dropdownRow(label: String, valueText: String, onClick: () -> Unit): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, dp(14), 0, dp(14))
            isClickable = true
            setOnClickListener { onClick() }

            addView(TextView(this@PlayerActivity).apply {
                text = label
                textSize = 16f
                setTextColor(android.graphics.Color.parseColor("#BBBBBB"))
                layoutParams = LinearLayout.LayoutParams(dp(110), LinearLayout.LayoutParams.WRAP_CONTENT)
            })

            addView(TextView(this@PlayerActivity).apply {
                text = valueText
                textSize = 17f
                setTextColor(android.graphics.Color.WHITE)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
        }
    }

    /** "Size ----•---- 22" jaisi slider row (label left, seekbar middle, value right). */
    private fun sliderRow(
        label: String,
        min: Int,
        max: Int,
        value: Int,
        suffix: String = "",
        onChange: (Int) -> Unit
    ): LinearLayout {
        lateinit var valueLabel: TextView
        val range = max - min
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, dp(14), 0, dp(14))

            addView(TextView(this@PlayerActivity).apply {
                text = label
                textSize = 16f
                setTextColor(android.graphics.Color.parseColor("#BBBBBB"))
                layoutParams = LinearLayout.LayoutParams(dp(110), LinearLayout.LayoutParams.WRAP_CONTENT)
            })

            addView(SeekBar(this@PlayerActivity).apply {
                this.max = range
                progress = (value - min).coerceIn(0, range)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                        if (fromUser) {
                            val newValue = min + progress
                            valueLabel.text = "$newValue$suffix"
                            onChange(newValue)
                        }
                    }
                    override fun onStartTrackingTouch(sb: SeekBar?) {}
                    override fun onStopTrackingTouch(sb: SeekBar?) {}
                })
            })

            valueLabel = TextView(this@PlayerActivity).apply {
                text = "$value$suffix"
                textSize = 16f
                setTextColor(android.graphics.Color.WHITE)
                layoutParams = LinearLayout.LayoutParams(dp(50), LinearLayout.LayoutParams.WRAP_CONTENT)
                gravity = android.view.Gravity.END
            }
            addView(valueLabel)
        }
    }
    private fun checkboxRow(
        label: String,
        checked: Boolean,
        colorHex: Int? = null,
        onColorClick: (() -> Unit)? = null,
        onCheck: (Boolean) -> Unit
    ): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, dp(14), 0, dp(14))

            addView(CheckBox(this@PlayerActivity).apply {
                isChecked = checked
                setOnCheckedChangeListener { _, isChecked -> onCheck(isChecked) }
            })

            addView(TextView(this@PlayerActivity).apply {
                text = label
                textSize = 16f
                setTextColor(android.graphics.Color.WHITE)
                setPadding(dp(8), 0, 0, 0)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })

            if (colorHex != null) {
                addView(colorSwatchView(colorHex).apply {
                    setOnClickListener { onColorClick?.invoke() }
                })
            }
        }
    }

    /** Video Display switch ON hone par resolution/codec/decoder badge screen par dikhata hai. */
    private fun updateVideoInfoBadge() {
        if (!videoDisplayInfoOn) {
            videoInfoBadge.visibility = View.GONE
            return
        }
        val vf = player.videoFormat
        val decoderLabel = when (decoderMode) {
            1 -> "HW+"
            2 -> "SW"
            else -> "HW"
        }
        val text = if (vf != null) {
            "${vf.width}x${vf.height} • ${vf.sampleMimeType?.substringAfterLast('/') ?: "?"} • $decoderLabel"
        } else {
            "Loading… • $decoderLabel"
        }
        videoInfoBadge.text = text
        videoInfoBadge.visibility = View.VISIBLE
    }

    /** Circular icon badge used in the More menu grid, mirrors the quick-action icon style. */
    private fun FrameLayoutCircle(
        context: Context,
        sizePx: Int,
        iconRes: Int,
        showDot: Boolean
    ): FrameLayout {
        val frame = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(sizePx, sizePx)
            background = androidx.core.content.ContextCompat.getDrawable(context, R.drawable.bg_icon_circle)
        }
        val icon = ImageView(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                android.view.Gravity.CENTER
            )
            setImageResource(iconRes)
        }
        frame.addView(icon)
        if (showDot) {
            val dot = View(context).apply {
                val dotSize = (6 * context.resources.displayMetrics.density).toInt()
                layoutParams = FrameLayout.LayoutParams(dotSize, dotSize, android.view.Gravity.TOP or android.view.Gravity.END)
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setColor(android.graphics.Color.parseColor("#FF3B30"))
                }
            }
            frame.addView(dot)
        }
        return frame
    }

    private fun showPlayingQueueDialog() {
        if (queue.isEmpty()) {
            showGestureFeedback("Queue khaali hai")
            return
        }
        val titles = queue.map { it.title }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Playing Queue")
            .setSingleChoiceItems(titles, player.currentMediaItemIndex) { dialog, which ->
                player.seekTo(which, 0)
                player.playWhenReady = true
                dialog.dismiss()
            }
            .show()
    }

    private fun showBookmarkDialog() {
        val uri = queue.getOrNull(player.currentMediaItemIndex)?.uriString ?: return
        val prefs = getSharedPreferences("bookmarks", MODE_PRIVATE)
        val raw = prefs.getString(uri, "") ?: ""
        val entries = raw.split(";;").filter { it.isNotBlank() }

        val labels = entries.map { entry ->
            val parts = entry.split("|", limit = 2)
            val pos = parts.getOrNull(0)?.toLongOrNull() ?: 0L
            val name = parts.getOrNull(1) ?: "Bookmark"
            val m = pos / 60000
            val s = (pos / 1000) % 60
            String.format("%s (%d:%02d)", name, m, s)
        }.toMutableList()
        labels.add(0, "+ Add bookmark at current position")

        AlertDialog.Builder(this)
            .setTitle("Bookmarks")
            .setItems(labels.toTypedArray()) { _, which ->
                if (which == 0) {
                    val pos = player.currentPosition
                    val newEntry = "$pos|Bookmark"
                    val updated = if (raw.isBlank()) newEntry else "$raw;;$newEntry"
                    prefs.edit().putString(uri, updated).apply()
                    showGestureFeedback("Bookmark added")
                } else {
                    val entry = entries[which - 1]
                    val pos = entry.split("|").firstOrNull()?.toLongOrNull() ?: 0L
                    player.seekTo(pos)
                    showGestureFeedback("Jumped to bookmark")
                }
            }
            .show()
    }

    private fun toggleFavourite() {
        val uri = queue.getOrNull(player.currentMediaItemIndex)?.uriString ?: return
        val prefs = getSharedPreferences("favourites", MODE_PRIVATE)
        val set = HashSet(prefs.getStringSet("uris", emptySet()) ?: emptySet())
        if (set.contains(uri)) {
            set.remove(uri)
            showGestureFeedback("Favourite se hataya")
        } else {
            set.add(uri)
            showGestureFeedback("Favourite mein add kiya")
        }
        prefs.edit().putStringSet("uris", set).apply()
    }

    private fun showInformationDialog() {
        val videoFormat = player.videoFormat
        val audioFormat = player.audioFormat
        val sb = StringBuilder()
        sb.append("File: ${playerTitleText.text}\n\n")
        if (videoFormat != null) {
            sb.append("Video: ${videoFormat.width}x${videoFormat.height}\n")
            sb.append("Codec: ${videoFormat.sampleMimeType}\n")
            if (videoFormat.bitrate > 0) sb.append("Bitrate: ${videoFormat.bitrate / 1000} kbps\n")
            sb.append("\n")
        }
        if (audioFormat != null) {
            sb.append("Audio codec: ${audioFormat.sampleMimeType}\n")
            sb.append("Sample rate: ${audioFormat.sampleRate} Hz\n")
            sb.append("Channels: ${audioFormat.channelCount}\n")
        }
        AlertDialog.Builder(this)
            .setTitle("Information")
            .setMessage(sb.toString())
            .setPositiveButton("OK", null)
            .show()
    }

    private fun shareCurrentVideo() {
        val uriString = queue.getOrNull(player.currentMediaItemIndex)?.uriString ?: return
        try {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "video/*"
                putExtra(Intent.EXTRA_STREAM, Uri.parse(uriString))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "Share video"))
        } catch (e: Exception) {
            showPlayerSnackbar("Share fail ho gaya: ${e.message}", isError = true)
        }
    }

    // Jugaad-style quality switch: koi adaptive HLS/DASH switching nahi — bas
    // current position save karo, naye quality URL ka MediaItem load karo, aur
    // wahi position se resume karo. Website ke quality button jaisa hi result.
    private fun showQualityDialog() {
        if (availableQualities.size < 2) {
            showPlayerSnackbar("Is video ke liye aur koi quality available nahi hai")
            return
        }
        val labels = availableQualities.map { it.first }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Quality")
            .setItems(labels) { _, which ->
                val (label, url) = availableQualities[which]
                val resumePos = player.currentPosition
                val wasPlaying = player.isPlaying || player.playWhenReady
                val item = MediaItem.Builder().setUri(Uri.parse(url)).build()
                player.setMediaItem(item, resumePos)
                player.prepare()
                player.playWhenReady = wasPlaying
                showPlayerSnackbar("Quality: $label")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showNetworkStreamDialog() {
        val input = EditText(this).apply {
            hint = "http:// ya https:// URL yahan paste karein"
        }
        AlertDialog.Builder(this)
            .setTitle("Network Stream")
            .setView(input)
            .setPositiveButton("Play") { _, _ ->
                val url = input.text.toString().trim()
                if (url.isNotEmpty()) {
                    val item = MediaItem.Builder().setUri(Uri.parse(url)).build()
                    player.setMediaItem(item)
                    player.prepare()
                    player.playWhenReady = true
                    playerTitleText.text = url
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** More menu -> Display Settings: keep-screen-on + on-screen video info toggle ek hi jagah. */
    private fun showDisplaySettingsDialog() {
        val options = arrayOf("Keep Screen On", "Show Video Info Overlay", "Auto-fetch Subtitle Online")
        val checked = booleanArrayOf(keepScreenOnPref, videoDisplayInfoOn, GesturePrefs.isAutoSubtitleEnabled(this))
        AlertDialog.Builder(this)
            .setTitle("Display Settings")
            .setMultiChoiceItems(options, checked) { _, which, isChecked ->
                checked[which] = isChecked
            }
            .setPositiveButton("Apply") { _, _ ->
                keepScreenOnPref = checked[0]
                if (keepScreenOnPref) {
                    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
                videoDisplayInfoOn = checked[1]
                updateVideoInfoBadge()
                GesturePrefs.setAutoSubtitleEnabled(this, checked[2])
                showGestureFeedback("Display Settings updated")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * Video ready hone par (agar "Auto-fetch Subtitle Online" ON hai aur is video ke
     * paas na embedded na hi attach ki hui subtitle hai) chup-chaap OpenSubtitles.com
     * par title se search karke sabse pehla result auto-download + attach kar deta hai.
     * Har media item ke liye sirf ek baar try hota hai (onMediaItemTransition mein flag reset).
     */
    private fun maybeAutoSearchSubtitle() {
        if (autoSubtitleAttemptedForCurrentItem) return
        if (!GesturePrefs.isAutoSubtitleEnabled(this)) return
        if (!OpenSubtitlesClient.isConfigured()) return
        if (subtitleLoaded) return
        if (!::player.isInitialized) return
        val hasEmbeddedText = player.currentTracks.groups.any { it.type == C.TRACK_TYPE_TEXT }
        if (hasEmbeddedText) return

        autoSubtitleAttemptedForCurrentItem = true
        val query = playerTitleText.text
            ?.toString()
            ?.substringBeforeLast('.')
            ?.replace('.', ' ')
            ?.replace('_', ' ')
            ?.trim()
            .orEmpty()
        if (query.isBlank()) return

        onlineSubtitleExecutor.execute {
            try {
                val results = OpenSubtitlesClient.search(query)
                val top = results.firstOrNull() ?: return@execute
                val dir = File(cacheDir, "online_subtitles").apply { if (!exists()) mkdirs() }
                val destFile = File(dir, "auto_${top.fileId}.srt")
                OpenSubtitlesClient.download(top.fileId, destFile)
                runOnUiThread {
                    if (!subtitleLoaded) {
                        attachSubtitle(Uri.fromFile(destFile))
                        showGestureFeedback("Subtitle auto-added: ${top.releaseName}")
                    }
                }
            } catch (_: Exception) {
                // Chup-chaap fail — auto-fetch hai, user ko error dikhaana zaroori nahi,
                // wo hamesha manually "Online Subtitle" se try kar sakta hai.
            }
        }
    }

    /** More menu -> Gesture Settings: double-tap seek seconds aur swipe (volume/brightness) sensitivity. */
    private fun showGestureSettingsDialog() {
        var newSeekSeconds = seekSecondsPref
        var newSensitivityPercent = GesturePrefs.getSwipeSensitivity(this)

        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), dp(0))
        }
        body.addView(sectionHeader("Double-tap Seek"))
        body.addView(sliderRow(
            "Seconds",
            GesturePrefs.SEEK_SECONDS_MIN,
            GesturePrefs.SEEK_SECONDS_MAX,
            newSeekSeconds,
            suffix = "s"
        ) { v -> newSeekSeconds = v })

        body.addView(spacer(dp(12)))
        body.addView(sectionHeader("Swipe Sensitivity"))
        body.addView(sliderRow(
            "Volume / Brightness",
            GesturePrefs.SWIPE_SENSITIVITY_MIN,
            GesturePrefs.SWIPE_SENSITIVITY_MAX,
            newSensitivityPercent,
            suffix = "%"
        ) { v -> newSensitivityPercent = v })

        AlertDialog.Builder(this)
            .setTitle("Gesture Settings")
            .setView(body)
            .setPositiveButton("Save") { _, _ ->
                seekSecondsPref = newSeekSeconds
                swipeSensitivityMultiplier = newSensitivityPercent / 100f
                GesturePrefs.setSeekSeconds(this, newSeekSeconds)
                GesturePrefs.setSwipeSensitivity(this, newSensitivityPercent)
                showGestureFeedback("Gesture settings saved")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** More menu -> Cut: current position ko A/B mark karke video trim karta hai (MediaMuxer copy). */
    private fun showCutDialog() {
        val posLabel = fun(ms: Long): String {
            if (ms < 0) return "Not set"
            val m = ms / 60000
            val s = (ms / 1000) % 60
            return String.format("%d:%02d", m, s)
        }
        val message = "Start: ${posLabel(cutPointA)}\nEnd: ${posLabel(cutPointB)}\n\n" +
                "Pehle 'Set Start' aur video aage badhaake 'Set End' dabayein, phir 'Trim & Save' karein."
        AlertDialog.Builder(this)
            .setTitle("Cut")
            .setMessage(message)
            .setPositiveButton("Set Start") { _, _ ->
                cutPointA = player.currentPosition
                showGestureFeedback("Start point set: ${posLabel(cutPointA)}")
            }
            .setNeutralButton("Set End") { _, _ ->
                cutPointB = player.currentPosition
                showGestureFeedback("End point set: ${posLabel(cutPointB)}")
                if (cutPointA >= 0 && cutPointB > cutPointA) {
                    trimCurrentVideo(cutPointA, cutPointB)
                }
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun trimCurrentVideo(startMs: Long, endMs: Long) {
        val uriString = queue.getOrNull(player.currentMediaItemIndex)?.uriString ?: return
        showGestureFeedback("Trimming shuru...")
        Thread {
            try {
                val extractor = android.media.MediaExtractor()
                extractor.setDataSource(this, Uri.parse(uriString), null)

                val outDir = getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: filesDir
                if (!outDir.exists()) outDir.mkdirs()
                val outFile = File(outDir, "trim_${System.currentTimeMillis()}.mp4")
                val muxer = android.media.MediaMuxer(
                    outFile.absolutePath,
                    android.media.MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
                )

                val indexMap = HashMap<Int, Int>()
                for (i in 0 until extractor.trackCount) {
                    val format = extractor.getTrackFormat(i)
                    val mime = format.getString(android.media.MediaFormat.KEY_MIME) ?: ""
                    if (mime.startsWith("video/") || mime.startsWith("audio/")) {
                        val dstIndex = muxer.addTrack(format)
                        indexMap[i] = dstIndex
                        extractor.selectTrack(i)
                    }
                }
                muxer.start()

                val buffer = java.nio.ByteBuffer.allocate(2 * 1024 * 1024)
                val bufferInfo = android.media.MediaCodec.BufferInfo()
                extractor.seekTo(startMs * 1000, android.media.MediaExtractor.SEEK_TO_PREVIOUS_SYNC)

                while (true) {
                    val sampleTrack = extractor.sampleTrackIndex
                    if (sampleTrack < 0) break
                    val sampleTime = extractor.sampleTime
                    if (sampleTime > endMs * 1000) break

                    buffer.clear()
                    val size = extractor.readSampleData(buffer, 0)
                    if (size < 0) break

                    bufferInfo.offset = 0
                    bufferInfo.size = size
                    bufferInfo.presentationTimeUs = (sampleTime - startMs * 1000).coerceAtLeast(0)
                    bufferInfo.flags = extractor.sampleFlags

                    val dst = indexMap[sampleTrack]
                    if (dst != null) {
                        muxer.writeSampleData(dst, buffer, bufferInfo)
                    }
                    extractor.advance()
                }

                muxer.stop()
                muxer.release()
                extractor.release()

                MediaScannerConnection.scanFile(this, arrayOf(outFile.absolutePath), null, null)

                runOnUiThread {
                    showGestureFeedback("Trimmed video saved: ${outFile.name}")
                    cutPointA = -1L
                    cutPointB = -1L
                }
            } catch (e: Exception) {
                runOnUiThread {
                    showPlayerSnackbar(
                        "Cut fail ho gaya (format shayad support nahi karta): ${e.message}",
                        isError = true
                    )
                }
            }
        }.start()
    }

    /** More menu -> Add To Playlist: naam poochke current video ko named playlist mein save karta hai. */
    private fun showAddToPlaylistDialog() {
        val uriString = queue.getOrNull(player.currentMediaItemIndex)?.uriString ?: return
        val prefs = getSharedPreferences("playlists", MODE_PRIVATE)
        val playlistNames = (prefs.getStringSet("names", emptySet()) ?: emptySet()).toMutableList()

        val options = (playlistNames + "+ Naya playlist banayein").toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Add To Playlist")
            .setItems(options) { _, which ->
                if (which == options.lastIndex) {
                    val input = EditText(this).apply { hint = "Playlist ka naam" }
                    AlertDialog.Builder(this)
                        .setTitle("Naya Playlist")
                        .setView(input)
                        .setPositiveButton("Save") { _, _ ->
                            val name = input.text.toString().trim()
                            if (name.isNotEmpty()) {
                                addToNamedPlaylist(prefs, name, uriString, playlistNames)
                            }
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                } else {
                    addToNamedPlaylist(prefs, playlistNames[which], uriString, playlistNames)
                }
            }
            .show()
    }

    private fun addToNamedPlaylist(
        prefs: android.content.SharedPreferences,
        name: String,
        uriString: String,
        existingNames: List<String>
    ) {
        val editor = prefs.edit()
        val updatedNames = HashSet(existingNames)
        updatedNames.add(name)
        editor.putStringSet("names", updatedNames)

        val itemsKey = "items_$name"
        val items = HashSet(prefs.getStringSet(itemsKey, emptySet()) ?: emptySet())
        items.add(uriString)
        editor.putStringSet(itemsKey, items)
        editor.apply()

        showGestureFeedback("\"$name\" playlist mein add ho gaya")
    }

    /** More menu -> Tutorial: gestures aur shortcuts ka quick reference. */
    private fun showTutorialDialog() {
        val message = """
            • Left/Right swipe: seek aage/peeche
            • Left side vertical swipe: brightness control
            • Right side vertical swipe: volume control
            • Double tap left/right: 10 sec seek
            • Lock icon: screen ko touch se lock karein
            • More (⋮): playlist, aspect ratio, cut, bookmark, share, waghera
            • Aspect Ratio icon: Fit / Fill / Zoom cycle karta hai
        """.trimIndent()
        AlertDialog.Builder(this)
            .setTitle("Tutorial")
            .setMessage(message)
            .setPositiveButton("Got it", null)
            .show()
    }

    // ---------------------------------------------------------------------
    // Screenshot (existing)
    // ---------------------------------------------------------------------
    private fun takeScreenshot() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            showPlayerSnackbar("Screenshot ke liye Android 7.0+ chahiye", isError = true)
            return
        }
        // Bug fix (PiP black screen): playerView ab surface_type="texture_view" use karta
        // hai (SurfaceView ka hardware layer PiP ke fast resize animation ke saath sync
        // nahi ho pata tha, isliye ek black frame dikhta tha). Isliye screenshot bhi ab
        // TextureView se lena hai — PixelCopy ki zaroorat nahi, TextureView khud hi
        // getBitmap() deta hai.
        val textureView = playerView.videoSurfaceView as? android.view.TextureView
        if (textureView == null || textureView.width == 0 || textureView.height == 0) {
            showPlayerSnackbar("Abhi screenshot possible nahi hai", isError = true)
            return
        }
        try {
            val bitmap = textureView.getBitmap(
                Bitmap.createBitmap(textureView.width, textureView.height, Bitmap.Config.ARGB_8888)
            )
            saveScreenshot(bitmap)
        } catch (e: Exception) {
            showPlayerSnackbar("Screenshot error: ${e.message}", isError = true)
        }
    }

    private fun saveScreenshot(bitmap: Bitmap) {
        try {
            val dir = getExternalFilesDir(Environment.DIRECTORY_PICTURES)
            if (dir != null && !dir.exists()) dir.mkdirs()
            val fileName = "Screenshot_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())}.jpg"
            val file = File(dir, fileName)
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
            }
            MediaScannerConnection.scanFile(this, arrayOf(file.absolutePath), arrayOf("image/jpeg"), null)
            runOnUiThread {
                hapticTick()
                showGestureFeedback("Screenshot saved")
            }
        } catch (e: Exception) {
            runOnUiThread { showGestureFeedback("Save fail: ${e.message}") }
        }
    }

    // MX Player jaisa immersive fullscreen: status bar + navigation bar dono hide,
    // controller ke saath sync — controls hide honge to bars bhi hide, show honge to bars bhi show.
    private fun hideSystemBars() {
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
    }

    private fun showSystemBars() {
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.show(WindowInsetsCompat.Type.systemBars())
    }

    // Premium touch feedback: MX Pro/Netflix jaise apps mein key gestures
    // (seek tap, lock/unlock, hold-to-2x, screenshot) par ek halka tactile
    // "tick" milta hai — sirf visual feedback se zyada premium feel deta hai.
    // Safe no-op agar device haptics support/allow nahi karta.
    private fun hapticTick() {
        try {
            playerView.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        } catch (_: Exception) {}
    }

    private fun setLocked(locked: Boolean) {
        isLocked = locked
        hapticTick()
        if (locked) {
            controlsHandler.removeCallbacks(hideControlsRunnable)
            // Bug fix: pehle sirf topBar (back/title row) hide hoti thi, quick-actions
            // wali icon-row (Night Mode/Shuffle/... row) lock ke baad bhi dikhti reh jaati thi.
            // Ab poora feature bar (dono rows) lock hote hi hide hoga — ab dono ek
            // smooth fade ke saath gayab hote hain, abrupt "pop" nahi hota.
            fadeOutView(topBar)
            fadeOutView(quickActionsScroll)
            playerView.useController = false
            // Lock hote hi unlock icon turant nahi dikhega — hidden rahega.
            // User jab screen tap karega tabhi yeh 10 second ke liye dikhega
            // (dekho showLockIconTemporarily()).
            controlsHandler.removeCallbacks(hideLockIconRunnable)
            unlockButton.animate().cancel()
            unlockButton.visibility = View.GONE
            unlockButton.alpha = 1f
            unlockButton.scaleX = 1f
            unlockButton.scaleY = 1f
            hideSystemBars()
        } else {
            controlsHandler.removeCallbacks(hideLockIconRunnable)
            playerView.useController = true
            dismissUnlockButton()
            showAllControls()
        }
    }

    // Lock screen par tap karne par unlock icon ko 10 second ke liye dikhaata
    // hai, phir wapas apne aap gayab ho jaata hai. Dobara tap karne par yeh
    // 10-second timer fresh se shuru ho jaata hai.
    private val hideLockIconRunnable = Runnable { dismissUnlockButton() }

    private fun showLockIconTemporarily() {
        controlsHandler.removeCallbacks(hideLockIconRunnable)
        revealUnlockButton()
        controlsHandler.postDelayed(hideLockIconRunnable, 10000L)
    }

    // Premium touch: unlock icon plain VISIBLE/GONE flip ki jagah ab chhota
    // shuru ho kar overshoot-bounce ke saath apni size par aata hai (lock)
    // aur shrink+fade ho kar gayab hota hai (unlock) — dono transitions ek
    // premium "materialize" feel dete hain.
    private fun revealUnlockButton() {
        unlockButton.animate().cancel()
        unlockButton.visibility = View.VISIBLE
        unlockButton.alpha = 0f
        unlockButton.scaleX = 0.5f
        unlockButton.scaleY = 0.5f
        unlockButton.animate()
            .alpha(1f).scaleX(1f).scaleY(1f)
            .setDuration(240)
            .setInterpolator(android.view.animation.OvershootInterpolator(2f))
            .start()
    }

    private fun dismissUnlockButton() {
        if (unlockButton.visibility != View.VISIBLE) return
        unlockButton.animate().cancel()
        unlockButton.animate()
            .alpha(0f).scaleX(0.5f).scaleY(0.5f)
            .setDuration(160)
            .withEndAction {
                unlockButton.visibility = View.GONE
                unlockButton.alpha = 1f
                unlockButton.scaleX = 1f
                unlockButton.scaleY = 1f
            }
            .start()
    }

    private fun showResumeDialog(position: Long) {
        val minutes = position / 60000
        val seconds = (position / 1000) % 60
        AlertDialog.Builder(this)
            .setTitle("Resume playback?")
            .setMessage(String.format("Wahin se continue karein jahan chhoda tha: %d:%02d", minutes, seconds))
            .setPositiveButton("Resume") { _, _ ->
                player.seekTo(position)
                player.playWhenReady = true
            }
            .setNegativeButton("Start Over") { _, _ ->
                player.playWhenReady = true
            }
            .setCancelable(false)
            .show()
    }

    // Bug fix: pause hone par controls sirf 3s mein hi hide ho jaate the, jitna
    // play mode mein hota hai — ab pause hone par kam se kam 10s tak visible
    // rahenge, play mode mein pehle jaisa 3s hi rahega.
    private fun currentHideTimeoutMs(): Long =
        if (::player.isInitialized && player.isPlaying) 3000L else 10000L

    // Top bar, quick-actions row aur neeche ka control bar (seekbar + buttons)
    // teeno ko hamesha EK SAATH dikhao/chhupao — pehle inka timing PlayerView ke
    // apne internal auto-hide timer par depend karta tha jiski wajah se seekbar
    // kabhi-kabhi baaki icons se pehle hi gayab ho jaata tha. Ab sirf yahi 2
    // functions in sabki visibility control karte hain, ek hi timer se.
    private fun showAllControls() {
        if (isLocked) return
        playerView.showController()
        fadeInView(topBar)
        // Bug fix: side dock (equalizer/screenshot/cast/rotate/aspect icons ki
        // vertical strip) yahan pehle galti se fadeInView(quickActionsScroll) se
        // wapas VISIBLE ho jaata tha. Ye poora dock jaan-bujh kar hata diya gaya
        // tha kyunki iske saare icons ab three-dot (More) menu ke andar duplicate
        // maujood hain — isliye ise yahan kabhi wapas visible nahi karna,
        // permanently hidden rehta hai (quickActionsScroll layout mein bhi
        // visibility="gone" hai).
        hideSystemBars()
        controlsHandler.removeCallbacks(hideControlsRunnable)
        controlsHandler.postDelayed(hideControlsRunnable, currentHideTimeoutMs())
    }

    private fun hideAllControls() {
        controlsHandler.removeCallbacks(hideControlsRunnable)
        if (isLocked) return
        playerView.hideController()
        // Bug fix: topBar/quickActionsScroll pehle turant (View.GONE) gayab ho
        // jaate the jabki playerView ka apna control bar (seekbar+buttons) fade
        // ho ke hide hota tha — dono ka timing match nahi karta tha isliye kuch
        // icons pehle aur kuch der se gayab hote dikhte the. Ab teeno EK SAATH,
        // same fade duration ke saath hide honge.
        fadeOutView(topBar)
        fadeOutView(quickActionsScroll)
        hideSystemBars()
    }

    // Playback controller (seekbar+buttons) ka apna default fade animation bhi
    // isi duration ke aas-paas hai, isliye topBar/quickActionsScroll ko bhi
    // wahi 250ms fade dete hain taaki sab EK SAATH, EK HI speed se hide/show ho.
    private val controlsFadeDurationMs = 250L

    // Premium polish: pehle yeh sirf flat alpha-fade tha — ab YouTube jaisa
    // halka "settle" motion bhi saath mein (chhota scale + upward slide),
    // taaki controls sirf "gayab/pragat" na ho balki thoda organically
    // ubharte/simatte hue dikhein — zyada premium/tactile feel.
    private fun fadeInView(view: View) {
        view.animate().cancel()
        view.alpha = 0f
        view.scaleY = 0.94f
        view.translationY = -dpToPx(6f)
        view.visibility = View.VISIBLE
        view.animate()
            .alpha(1f)
            .scaleY(1f)
            .translationY(0f)
            .setDuration(controlsFadeDurationMs)
            .setInterpolator(android.view.animation.DecelerateInterpolator(1.5f))
            .start()
    }

    private fun fadeOutView(view: View) {
        if (view.visibility != View.VISIBLE) return
        view.animate().cancel()
        view.animate()
            .alpha(0f)
            .scaleY(0.94f)
            .translationY(-dpToPx(6f))
            .setDuration(controlsFadeDurationMs)
            .setInterpolator(android.view.animation.AccelerateInterpolator())
            .withEndAction {
                view.visibility = View.GONE
                view.alpha = 1f
                view.scaleY = 1f
                view.translationY = 0f
            }
            .start()
    }

    private fun dpToPx(dp: Float): Float = dp * resources.displayMetrics.density

    private fun areControlsVisible(): Boolean = topBar.visibility == View.VISIBLE

    private fun setupGestures() {
        gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {

            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (isLocked) return true
                val screenWidth = playerView.width
                handleSeekTap(forward = e.x >= screenWidth / 2)
                return true
            }

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                // Bug fix: pehle performClick() call hota tha jiska koi listener hi nahi tha,
                // isliye tap karne par controls kabhi show/hide hi nahi hote the.
                // Ab MX Player jaisa: tap karo to top bar + bottom bar + seekbar
                // sab ek saath toggle honge (showAllControls/hideAllControls).
                if (!isLocked) {
                    if (areControlsVisible()) {
                        hideAllControls()
                    } else {
                        showAllControls()
                    }
                }
                return true
            }

            // YouTube jaisa: screen ko kahin bhi (left ya right side) dabaake
            // rakhne (hold) par video 2x speed par chalne lagta hai. Finger
            // uthate hi (ACTION_UP/CANCEL, touch listener mein neeche) speed
            // wapas pehle wali value par chali jaati hai.
            override fun onLongPress(e: MotionEvent) {
                // Bug fix: pehle "areControlsVisible()" hone par yeh gesture kaam
                // hi nahi karta tha — matlab jab top bar/seekbar/feature icons
                // dikh rahe hote the, tab hold karke 2x speed nahi milti thi.
                // Ab controls visible hone par bhi hold-to-2x kaam karega.
                //
                // Bug fix: yahan pehle "if (isLocked) return" tha, jo lock-screen
                // mein hold-to-2x ko poori tarah block kar deta tha — jabki neeche
                // setOnTouchListener ke isLocked block mein khaas isi gesture ko
                // lock mein bhi allow karne ka logic likha hai ("Lock screen mein
                // bhi hold-to-2x kaam kare"). Dono jagah ka intent contradict kar
                // raha tha, isliye yahan se lock check hata diya — ab lock mein
                // bhi hold-to-2x sahi se activate hota hai.
                isLongPressSpeedActive = true
                hapticTick()
                speedBeforeLongPress = try { player.playbackParameters.speed } catch (_: Exception) { 1f }
                player.playbackParameters = PlaybackParameters(2f)
                speedIndicatorBadge.removeCallbacks(hideSpeedIndicatorRunnable)
                speedIndicatorBadge.visibility = View.VISIBLE
            }
        })

        // Premium feature: Pinch-to-Zoom. Do ungli se pinch karke video ko 1x
        // se 4x tak zoom kiya ja sakta hai — sirf video surface scale hota
        // hai, controls waise hi rehte hain.
        scaleGestureDetector = android.view.ScaleGestureDetector(
            this,
            object : android.view.ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScaleBegin(detector: android.view.ScaleGestureDetector): Boolean {
                    if (isLocked) return false
                    isPinchZooming = true
                    lastPinchFocusX = detector.focusX
                    lastPinchFocusY = detector.focusY
                    return true
                }

                override fun onScale(detector: android.view.ScaleGestureDetector): Boolean {
                    val newScale = (videoZoomScale * detector.scaleFactor).coerceIn(MIN_ZOOM, MAX_ZOOM)
                    videoZoomScale = newScale

                    // Do-ungli ka focus point move hone par usi hisaab se pan bhi
                    // update karo — taaki pinch karte waqt ungliyon ke saath video
                    // bhi drag ho (jaisa gallery/photo apps mein hota hai). Yeh
                    // zoom-in (1x se badha) aur zoom-out (video ko chhota karna,
                    // MX Player jaisa) dono ke liye kaam karta hai.
                    if (Math.abs(videoZoomScale - 1f) > 0.02f) {
                        videoPanX += detector.focusX - lastPinchFocusX
                        videoPanY += detector.focusY - lastPinchFocusY
                    } else {
                        videoPanX = 0f
                        videoPanY = 0f
                    }
                    lastPinchFocusX = detector.focusX
                    lastPinchFocusY = detector.focusY

                    applyVideoZoom()
                    val label = if (videoZoomScale < 1f) "Chhota" else "Zoom"
                    showGestureFeedback("$label ${String.format("%.1f", videoZoomScale)}x")
                    return true
                }

                override fun onScaleEnd(detector: android.view.ScaleGestureDetector) {
                    isPinchZooming = false
                }
            }
        )

        // Bug fix: top bar aur quick-actions row ab bottom control bar (seekbar +
        // buttons) ke saath EK HI Handler (showAllControls/hideAllControls) se
        // sync rahenge — MX Player jaisa: 3 second mein sab ek saath auto-hide,
        // tap karne par sab ek saath wapas.
        //
        // IMPORTANT bug fix: pehle yahan controller visible hote hi showSystemBars()
        // call ho jaata tha — matlab jab bhi user player ke controls (play/pause,
        // feature icons, seekbar) dikhane ke liye screen touch karta tha, status bar
        // aur navigation bar bhi wapas pop-up ho jaate the (screenshot mein exactly
        // yahi problem dikh rahi thi). MX Player mein aisa nahi hota — status/nav bar
        // sirf screen edge se swipe karne par hi transient dikhte hain, player controls
        // dikhane/chhupane se unka koi lena dena nahi hota. Isliye ab system bars ko
        // controller visibility ke saath link nahi kar rahe — wo hamesha hidden hi
        // rahenge (BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE ki wajah se edge-swipe par
        // khud hi thodi der ke liye dikh kar wapas chhup jaayenge).
        //
        // controllerShowTimeoutMs = 0 + controllerHideOnTouch = false: PlayerView
        // ka apna internal auto-hide timer poori tarah band, kyunki wahi alag-alag
        // waqt par elements ko hide kar raha tha. Ab sirf humara controlsHandler
        // (showAllControls/hideAllControls) hi visibility decide karta hai.
        playerView.controllerShowTimeoutMs = 0
        playerView.controllerHideOnTouch = false
        playerView.setControllerVisibilityListener(
            PlayerView.ControllerVisibilityListener {
                // Status/navigation bar ab controller ke saath toggle nahi hoti — hamesha hidden.
                hideSystemBars()
            }
        )

        playerView.setOnTouchListener { view, event ->
            if (isLocked) {
                // Lock screen mein bhi hold-to-2x kaam kare — sirf yeh ek gesture
                // allow karte hain, baaki sab (seek/volume/brightness swipe, tap)
                // disabled hi rehte hain jab tak unlock na ho.
                if (event.action == MotionEvent.ACTION_DOWN) {
                    // Tap karte hi unlock icon 10 second ke liye dikhao — dobara
                    // tap karne par yeh timer fresh se restart ho jaata hai.
                    showLockIconTemporarily()
                }
                gestureDetector.onTouchEvent(event)
                if (event.action == MotionEvent.ACTION_UP || event.action == MotionEvent.ACTION_CANCEL) {
                    if (isLongPressSpeedActive) {
                        isLongPressSpeedActive = false
                        player.playbackParameters = PlaybackParameters(speedBeforeLongPress)
                        speedIndicatorBadge.visibility = View.GONE
                    }
                }
                return@setOnTouchListener true
            }

            // Premium: pinch-to-zoom ko sabse pehle event dikhao. Jab do
            // ungliyon se pinch chal raha ho, to neeche ka single-finger
            // seek/volume/brightness/tap logic bilkul skip kar dete hain,
            // taaki dono gesture aapas mein conflict na karein.
            scaleGestureDetector.onTouchEvent(event)
            if (event.pointerCount >= 2 || isPinchZooming) {
                if (event.actionMasked == MotionEvent.ACTION_UP ||
                    event.actionMasked == MotionEvent.ACTION_CANCEL ||
                    event.actionMasked == MotionEvent.ACTION_POINTER_UP
                ) {
                    if (event.pointerCount <= 2) {
                        isPinchZooming = false
                    }
                }
                return@setOnTouchListener true
            }

            if (event.action == MotionEvent.ACTION_DOWN) {
                // Continuous seek: agar pichhle tap ke turant baad (SEEK_CONTINUATION_WINDOW_MS
                // ke andar) usi side par dobara tap hua hai, to use naya double-tap gesture
                // maanne ke bajaye seedha continuation maan kar seconds accumulate karo —
                // GestureDetector ko yeh event bilkul nahi dikhaya jaata, warna wo isse
                // single-tap (controls toggle) ya nayi double-tap detection samajh sakta hai.
                val now = SystemClock.elapsedRealtime()
                if (lastSeekSide != 0 && (now - lastSeekTapTime) < SEEK_CONTINUATION_WINDOW_MS) {
                    val screenWidth = view.width
                    val side = if (event.x < screenWidth / 2) -1 else 1
                    if (side == lastSeekSide) {
                        consumingSeekContinuationGesture = true
                        handleSeekTap(forward = side == 1)
                        startX = event.x
                        startY = event.y
                        isSwipingVolume = false
                        isSwipingBrightness = false
                        return@setOnTouchListener true
                    }
                }
                consumingSeekContinuationGesture = false
            }

            if (consumingSeekContinuationGesture) {
                if (event.action == MotionEvent.ACTION_UP || event.action == MotionEvent.ACTION_CANCEL) {
                    consumingSeekContinuationGesture = false
                }
                return@setOnTouchListener true
            }

            gestureDetector.onTouchEvent(event)

            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = event.x
                    startY = event.y
                    isSwipingVolume = false
                    isSwipingBrightness = false
                }

                MotionEvent.ACTION_MOVE -> {
                    // Bug fix: hold-to-2x active hote hue bhi finger thoda idhar-udhar
                    // move hone par volume/brightness swipe bhi trigger ho jaata tha.
                    // Ab jab tak 2x hold active hai, sirf yehi chalega — volume/brightness
                    // bilkul touch nahi honge.
                    if (isLongPressSpeedActive) return@setOnTouchListener true

                    val deltaY = startY - event.y
                    val deltaX = event.x - startX

                    // Middle-zone downward swipe = shrink-to-PiP, exactly like
                    // topContainer's gesture but usable from the video itself.
                    // Sirf beech ke ~40% width mein — left/right zones brightness/
                    // volume ke liye reserved hi rehte hain, koi conflict nahi.
                    if (!isSwipingVolume && !isSwipingBrightness) {
                        val screenWidth = view.width
                        val inMiddleZone = startX > screenWidth * 0.30f && startX < screenWidth * 0.70f
                        val downAmount = -deltaY // positive jab neeche ki taraf swipe ho raha ho
                        if (inMiddleZone && (isSwipingToPipFromVideo || (downAmount > 24 && downAmount > abs(deltaX)))) {
                            isSwipingToPipFromVideo = true
                            val progress = (downAmount / 260f).coerceIn(0f, 1f)
                            playerContainer.translationY = downAmount.coerceIn(0f, 260f)
                            playerContainer.alpha = 1f - (progress * 0.35f)
                            playerContainer.scaleX = 1f - (progress * 0.08f)
                            playerContainer.scaleY = 1f - (progress * 0.08f)
                            return@setOnTouchListener true
                        }
                    }

                    if (abs(deltaY) > abs(deltaX) && abs(deltaY) > 20) {
                        val screenWidth = view.width

                        if (startX > screenWidth / 2) {
                            if (!isSwipingVolume) {
                                // Swipe shuru hote hi apna app-level target % use karo (hardware se
                                // dobara nahi padhte) — dekhiye lastAppliedVolumePercent field ka comment.
                                isSwipingVolume = true
                                volumeGestureStartPercent = lastAppliedVolumePercent
                            }
                            val change = (deltaY / view.height) * 200f * swipeSensitivityMultiplier
                            val newPercent = (volumeGestureStartPercent + change).coerceIn(0f, 200f)
                            applyVolumePercent(newPercent)
                            showVolumeSlider(newPercent.toInt())
                        } else {
                            isSwipingBrightness = true
                            val change = (deltaY / view.height) * swipeSensitivityMultiplier
                            val newBrightness = (currentBrightness + change).coerceIn(0.02f, 1f)
                            val params = window.attributes
                            params.screenBrightness = newBrightness
                            window.attributes = params
                            val percent = (newBrightness * 100).toInt()
                            showBrightnessSlider(percent)
                        }
                    }
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (isSwipingVolume) {
                        hideSliderDelayed(volumeSliderContainer)
                    }
                    if (isSwipingBrightness) {
                        currentBrightness = window.attributes.screenBrightness
                        hideSliderDelayed(brightnessSliderContainer)
                    }
                    if (isLongPressSpeedActive) {
                        isLongPressSpeedActive = false
                        player.playbackParameters = PlaybackParameters(speedBeforeLongPress)
                        speedIndicatorBadge.visibility = View.GONE
                    }
                    if (isSwipingToPipFromVideo) {
                        val wasDragging = isSwipingToPipFromVideo
                        val downAmount = event.y - startY
                        isSwipingToPipFromVideo = false
                        if (wasDragging && downAmount > 90) {
                            // Real system PiP animation le raha hai ab — apna local
                            // shrink-transform ko ANIMATE karke wapas normal size
                            // laane ki koshish nahi karte (wo system ke transition
                            // ke saath collide karke jerky/double-jump dikhta tha).
                            // Seedha instantly reset karke phir PiP request karo,
                            // taaki handoff ek hi smooth motion jaisa lage.
                            playerContainer.translationY = 0f
                            playerContainer.alpha = 1f
                            playerContainer.scaleX = 1f
                            playerContainer.scaleY = 1f
                            if (!tryEnterPipOnBack()) {
                                showGestureFeedback("PiP is not supported on this device")
                            }
                        } else {
                            playerContainer.animate()
                                .translationY(0f).alpha(1f).scaleX(1f).scaleY(1f)
                                .setDuration(180).start()
                        }
                    }
                }
            }
            true
        }
    }

    // Premium feature: Pinch-to-Zoom & Pan lagu karta hai. videoSurfaceView
    // (TextureView/SurfaceView) ko hi scale + translate karte hain — controls
    // (top bar, seekbar, buttons) is transform se bilkul untouched rehte hain
    // kyunki wo playerView ke andar alag layer par hain.
    private fun applyVideoZoom() {
        val surface = playerView.videoSurfaceView ?: return
        val maxPanX = (surface.width * Math.abs(videoZoomScale - 1f)) / 2f
        val maxPanY = (surface.height * Math.abs(videoZoomScale - 1f)) / 2f
        videoPanX = videoPanX.coerceIn(-maxPanX, maxPanX)
        videoPanY = videoPanY.coerceIn(-maxPanY, maxPanY)
        surface.scaleX = videoZoomScale
        surface.scaleY = videoZoomScale
        surface.translationX = videoPanX
        surface.translationY = videoPanY
        resetZoomChip.visibility = if (Math.abs(videoZoomScale - 1f) > 0.02f) View.VISIBLE else View.GONE
    }

    // Zoom reset — hamesha 1x (original size) par wapas aata hai, chahe video
    // zoom-in kiya ho ya zoom-out karke chhota kiya ho.
    private fun resetVideoZoom() {
        if (videoZoomScale == 1f && videoPanX == 0f && videoPanY == 0f) return
        videoZoomScale = 1f
        videoPanX = 0f
        videoPanY = 0f
        val surface = playerView.videoSurfaceView
        surface?.animate()
            ?.scaleX(1f)?.scaleY(1f)
            ?.translationX(0f)?.translationY(0f)
            ?.setDuration(150)
            ?.start()
        resetZoomChip.visibility = View.GONE
    }

    // Premium feature: Frame-by-Frame stepping. Video ko pause karke, video ke
    // asli frame-rate ke hisaab se ek frame jitna hi precisely aage/peeche
    // seek karta hai (fallback 30fps agar format se frame-rate na mile).
    private fun stepFrame(forward: Boolean) {
        if (player.isPlaying) {
            player.pause()
        }
        val frameRate = player.videoFormat?.frameRate
            ?.takeIf { it > 0f && !it.isNaN() } ?: 30f
        val frameDurationMs = (1000f / frameRate).toLong().coerceAtLeast(1L)
        val newPosition = if (forward) {
            (player.currentPosition + frameDurationMs).coerceAtMost(player.duration.coerceAtLeast(0L))
        } else {
            (player.currentPosition - frameDurationMs).coerceAtLeast(0L)
        }
        player.seekTo(newPosition)
        showGestureFeedback(if (forward) "Next Frame ▶" else "◀ Prev Frame")
    }

    // Ek "seek session" ke andar har agle tap par yeh window jitni der tak
    // agla tap "continuation" mana jaata hai (10s -> 20s -> 30s ...).
    private val SEEK_CONTINUATION_WINDOW_MS = 700L
    private var lastSeekTapTime = 0L

    // Double-tap (aur uske baad continuous single-tap) seek: har call se 10s
    // aur seek hota hai, aur session ke andar total accumulated seconds
    // dikhaye jaate hain (5 baar tap = 50s, 2 baar = 20s, waghera).
    private fun handleSeekTap(forward: Boolean) {
        if (isLocked) return
        hapticTick()
        val side = if (forward) 1 else -1
        seekAccumulatedUnits += 1
        lastSeekSide = side
        lastSeekTapTime = SystemClock.elapsedRealtime()

        val seekAmount = seekSecondsPref * 1000L
        if (forward) {
            val duration = player.duration
            val target = player.currentPosition + seekAmount
            val safeTarget = if (duration != C.TIME_UNSET) target.coerceAtMost(duration) else target
            player.seekTo(safeTarget)
        } else {
            player.seekTo((player.currentPosition - seekAmount).coerceAtLeast(0))
        }
        showSeekFeedback(forward, seekAccumulatedUnits * seekSecondsPref)

        seekSessionHandler.removeCallbacks(seekSessionResetRunnable)
        seekSessionHandler.postDelayed(seekSessionResetRunnable, SEEK_CONTINUATION_WINDOW_MS)
    }

    // Current combined volume % (0-200) actual device state se nikalta hai —
    // stream volume (0-100%) + LoudnessEnhancer boost (100-200%), taaki
    // Equalizer dialog se boost badla ho tab bhi swipe gesture sync rahe.
    private fun getCurrentVolumePercent(): Float {
        val streamVol = audioManager.getStreamVolume(android.media.AudioManager.STREAM_MUSIC).toFloat()
        val streamPercent = (streamVol / maxVolume) * 100f
        val gain = try {
            loudnessEnhancer?.takeIf { it.enabled }?.targetGain?.toInt()
        } catch (_: Exception) { null } ?: 0
        val boostPercent = (gain.toFloat() / MAX_BOOST_MILLIBEL) * 100f
        return (streamPercent + boostPercent).coerceIn(0f, 200f)
    }

    // 0-100%: sirf device stream volume. 100-200%: stream volume max par lock
    // karke LoudnessEnhancer se extra software boost.
    private fun applyVolumePercent(percent: Float) {
        val clamped = percent.coerceIn(0f, 200f)
        if (clamped <= 100f) {
            val newStreamVol = ((clamped / 100f) * maxVolume).toInt()
            audioManager.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, newStreamVol, 0)
            try {
                loudnessEnhancer?.setTargetGain(0)
                loudnessEnhancer?.enabled = false
            } catch (_: Exception) {}
        } else {
            audioManager.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, maxVolume, 0)
            val boostFraction = (clamped - 100f) / 100f
            val gainMilliBel = (boostFraction * MAX_BOOST_MILLIBEL).toInt()
            try {
                loudnessEnhancer?.setTargetGain(gainMilliBel)
                loudnessEnhancer?.enabled = gainMilliBel > 0
            } catch (_: Exception) {}
        }
        // App ka apna "source of truth" target yaad rakho (dekhiye field
        // comment) taaki agli baar gesture start karte waqt hardware ke
        // kisi silent drift se galat/kam value se shuru na ho.
        lastAppliedVolumePercent = clamped
    }

    // Bug fix: pehle sirf ACTION_UP par ek hi postDelayed lagta tha jo baar baar
    // overlap ho sakta tha; ab hamesha pending hide cancel karke fresh schedule karte hain
    private val hideIndicatorRunnable = Runnable { gestureIndicator.visibility = View.GONE }
    private val hideSeekLeftRunnable = Runnable { seekIndicatorLeft.visibility = View.GONE }
    private val hideSeekRightRunnable = Runnable { seekIndicatorRight.visibility = View.GONE }

    private val sliderHandler = Handler(Looper.getMainLooper())
    private val hideVolumeRunnable = Runnable { volumeSliderContainer.visibility = View.GONE }
    private val hideBrightnessRunnable = Runnable { brightnessSliderContainer.visibility = View.GONE }

    // Chhota/compact pill jaisa track (pehle 180dp tha, ab MX/other apps jaise
    // chhote indicator ke liye kam kiya gaya).
    private val VOLUME_TRACK_HEIGHT_DP = 130

    private fun showVolumeSlider(percent: Int) {
        sliderHandler.removeCallbacks(hideVolumeRunnable)
        volumeSliderContainer.visibility = View.VISIBLE
        val clamped = percent.coerceIn(0, 200)
        volumePercentText.text = "$clamped%"
        val trackHeight = VOLUME_TRACK_HEIGHT_DP * resources.displayMetrics.density

        // Track ab 0%-200% represent karta hai: neeche wala (blue) hissa 0-100%
        // dikhata hai, upar wala (red) hissa sirf 100%-200% boost zone mein
        // dikhta hai — bilkul us reference jaisa jahan boost hote hi upar laal
        // rang shuru hota hai.
        val basePercent = clamped.coerceIn(0, 100)
        val boostPercent = (clamped - 100).coerceIn(0, 100)
        val baseHeight = ((basePercent / 200f) * trackHeight).toInt().coerceAtLeast(4)
        val boostHeight = ((boostPercent / 200f) * trackHeight).toInt()

        val baseParams = volumeFill.layoutParams
        baseParams.height = baseHeight
        volumeFill.layoutParams = baseParams

        val boostParams = volumeBoostFill.layoutParams as FrameLayout.LayoutParams
        boostParams.height = boostHeight
        boostParams.bottomMargin = baseHeight
        volumeBoostFill.layoutParams = boostParams
        volumeBoostFill.visibility = if (boostHeight > 0) View.VISIBLE else View.GONE
    }

    private fun showBrightnessSlider(percent: Int) {
        sliderHandler.removeCallbacks(hideBrightnessRunnable)
        brightnessSliderContainer.visibility = View.VISIBLE
        brightnessPercentText.text = "$percent%"
        val trackHeight = VOLUME_TRACK_HEIGHT_DP * resources.displayMetrics.density
        val params = brightnessFill.layoutParams
        params.height = ((percent / 100f) * trackHeight).toInt().coerceAtLeast(4)
        brightnessFill.layoutParams = params
    }

    private fun hideSliderDelayed(container: LinearLayout) {
        val runnable = if (container == volumeSliderContainer) hideVolumeRunnable else hideBrightnessRunnable
        sliderHandler.removeCallbacks(runnable)
        sliderHandler.postDelayed(runnable, 700)
    }

    // Premium touch: badge instantly VISIBLE hone ke bajaye halka scale+fade
    // "pop" ke saath aata hai (small → normal size, 0 → full alpha) — MX Pro/
    // premium OTT apps jaisa micro-animation, feels zyada polished/native.
    private fun popIn(view: View) {
        view.animate().cancel()
        view.alpha = 0f
        view.scaleX = 0.82f
        view.scaleY = 0.82f
        view.visibility = View.VISIBLE
        view.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(140)
            .setInterpolator(android.view.animation.OvershootInterpolator(1.6f))
            .start()
    }

    private fun showGestureFeedback(text: String) {
        gestureIndicator.removeCallbacks(hideIndicatorRunnable)
        gestureText.text = text
        popIn(gestureIndicator)
        hideGestureIndicatorDelayed()
    }

    // ---------------------------------------------------------------------
    // Player-wide snackbar — status/error messages ke liye (jaise decoder
    // fallback, corrupt file, quality change, screenshot/share result waghera)
    // jo pehle bare system Toast.makeText(...) se dikhte the. Woh center-screen
    // gestureIndicator (short taps ke liye tuned, 700ms, single-line) se
    // jaanbujh kar alag rakha hai kyunki ye messages kabhi lambe/multi-line
    // hote hain aur inhe padhne ke liye zyada time chahiye — isliye duration
    // message length ke hisaab se scale hota hai, aur bottom mein controls ke
    // upar dikhta hai taaki kabhi center content ko block na kare.
    private fun showPlayerSnackbar(message: String, isError: Boolean = false) {
        if (!::playerSnackbar.isInitialized) return
        playerSnackbar.removeCallbacks(hidePlayerSnackbarRunnable)
        playerSnackbarText.text = message
        playerSnackbarIcon.setImageResource(if (isError) R.drawable.ic_warning else R.drawable.ic_info)
        playerSnackbarIcon.setColorFilter(if (isError) android.graphics.Color.parseColor("#FF6B5D") else android.graphics.Color.parseColor("#FFD700"))

        playerSnackbar.animate().cancel()
        playerSnackbar.visibility = View.VISIBLE
        playerSnackbar.alpha = 0f
        playerSnackbar.translationY = dp(16).toFloat()
        playerSnackbar.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(200)
            .setInterpolator(android.view.animation.DecelerateInterpolator())
            .start()

        val autoHideMs = (1800L + message.length * 30L).coerceIn(1800L, 5000L)
        playerSnackbar.postDelayed(hidePlayerSnackbarRunnable, autoHideMs)
    }

    private fun hidePlayerSnackbar() {
        if (!::playerSnackbar.isInitialized) return
        playerSnackbar.animate().cancel()
        playerSnackbar.animate()
            .alpha(0f)
            .translationY(dp(16).toFloat())
            .setDuration(160)
            .withEndAction { playerSnackbar.visibility = View.GONE }
            .start()
    }

    private fun hideGestureIndicatorDelayed() {
        gestureIndicator.removeCallbacks(hideIndicatorRunnable)
        gestureIndicator.postDelayed(hideIndicatorRunnable, 700)
    }

    // Double-tap +Ns / -Ns feedback: MX Player jaisa chhota icon+text indicator
    // screen ke left/right side par, halke dark radial-glow background ke saath
    // (seek_ripple_bg drawable — screenshot mein dikhne wala "halka black gol"
    // effect). seconds = us session mein ab tak accumulate hui total seconds.
    private fun showSeekFeedback(forward: Boolean, seconds: Int = 10) {
        val container = if (forward) seekIndicatorRight else seekIndicatorLeft
        val text = if (forward) seekTextRight else seekTextLeft
        val runnable = if (forward) hideSeekRightRunnable else hideSeekLeftRunnable
        text.text = "${seconds}s"
        container.removeCallbacks(runnable)
        popIn(container)
        container.postDelayed(runnable, 500)
    }

    // ---------------------------------------------------------------------
    // Picture-in-Picture (YouTube jaisa "background floating mini player"):
    // back dabate hi (in-app back arrow ya system back/home) video band nahi
    // hota — chhota hoke ek floating window mein screen ke upar chalta rehta
    // hai. Sirf Android 8.0+ (API 26) par kaam karta hai; purane devices par
    // pehle jaisa hi normal back/finish hota hai.
    // ---------------------------------------------------------------------

    // Bug fix (PiP shrink animation "smooth" nahi lagti thi — shuru mein ek black
    // frame flash hota tha): chhote inline player (MainActivity) se seedha yahan
    // aane par shared ExoPlayer instance is (naye) playerView se abhi-abhi attach
    // hui hoti hai — uska pehla frame is naye Surface par render hone mein ek-do
    // vsync (10-30ms) lag sakte hain. Pehle hum bas `playerView.post { }` ke turant
    // baad hi enterPictureInPictureMode() call kar dete the, jo kabhi-kabhi us
    // pehle frame se PEHLE hi fire ho jaata tha — result: PiP shrink animation ek
    // khaali/black rectangle ko shrink karte hue shuru hoti, video 1-2 frame baad
    // "pop" karta — jhatka-sa/non-premium feel.
    //
    // Fix: agar player abhi tak koi frame render nahi kar chuka (onRenderedFirstFrame
    // fire nahi hua), thoda wait karo (max 150ms timeout safety ke saath, taaki
    // kisi wajah se listener na fire ho to bhi PiP hamesha ke liye atki na rahe)
    // — phir hi enterPictureInPictureMode() call karo. Isse shrink animation
    // shuru hote hi video already visible hota hai, bilkul YouTube jaisa smooth feel.
    private fun enterPipWhenFrameReady() {
        if (!::player.isInitialized || !::playerView.isInitialized) return
        pipEntryFrameListener?.let { player.removeListener(it) }
        pipEntryFrameListener = null

        if (player.isPlaying) {
            // Shared player already actively playing tha (MainActivity mein) — naye
            // Surface par bhi decoder turant hi ek fresh frame push karega, isliye
            // ek chhota fixed delay hi kaafi hai (koi listener overhead nahi chahiye).
            playerView.postDelayed({ tryEnterPipOnBack() }, 32L)
            return
        }

        val listener = object : Player.Listener {
            override fun onRenderedFirstFrame() {
                pipEntryFrameListener?.let { player.removeListener(it) }
                pipEntryFrameListener = null
                playerView.post { tryEnterPipOnBack() }
            }
        }
        pipEntryFrameListener = listener
        player.addListener(listener)
        // Safety timeout — agar kisi wajah se onRenderedFirstFrame fire hi na ho
        // (edge case), PiP request phir bhi honi chahiye, warna button/swipe "kuch
        // nahi hua" jaisa feel dega.
        playerView.postDelayed({
            if (pipEntryFrameListener === listener) {
                player.removeListener(listener)
                pipEntryFrameListener = null
                tryEnterPipOnBack()
            }
        }, 150L)
    }

    private fun tryEnterPipOnBack(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return unsupportedPipFallback()
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) return unsupportedPipFallback()
        if (!::player.isInitialized) return false
        if (isInPictureInPictureMode) return false
        val result = try {
            enterPictureInPictureMode(buildPipParams().build())
        } catch (_: Exception) {
            false
        }
        if (!result) unsupportedPipFallback()
        // Sirf pehli (direct-from-inline) PiP entry ke liye tha — consume ho gaya,
        // ab se hamesha live fullscreen rect hi use hona chahiye (dekho field
        // comment).
        pendingImmediatePipSourceRect = null
        return result
    }

    // Bug fix: agar device PiP support hi nahi karta (purana Android ya
    // FEATURE_PICTURE_IN_PICTURE missing) ya enterPictureInPictureMode() kisi
    // wajah se fail ho jaaye, to inline-origin session hamesha ke liye
    // orientation-force suppress kiye baithi rehti thi (applyOrientationForVideo
    // guard dekho) — matlab video kabhi sahi (landscape) orientation mein
    // dikhta hi nahi, na PiP na hi normal fullscreen. Fix: aisi situation mein
    // "inline-origin" treatment turant hata do taaki yeh normal fullscreen
    // player ki tarah hi (sahi orientation ke saath) kaam kare — expand-to-
    // inline shortcut bhi ab lagu nahi hoga (jo sahi hai, kyunki PiP bani hi
    // nahi thi).
    private fun unsupportedPipFallback(): Boolean {
        if (pipOriginFromInline) {
            pipOriginFromInline = false
            if (::player.isInitialized) {
                val vw = player.videoSize.width
                val vh = player.videoSize.height
                if (vw > 0 && vh > 0) applyOrientationForVideo(vw, vh, player.videoSize.unappliedRotationDegrees)
            }
        }
        return false
    }

    private fun buildPipParams(): PictureInPictureParams.Builder {
        val builder = PictureInPictureParams.Builder()
        // Bug fix (jhatke wala/gadbad shrink animation): sourceRectHint ke bina Android
        // ek generic "poori screen se center/corner tak zoom-out" animation deta hai,
        // jo letterboxed (aspect-fit) video ke saath khaas taur par jhatka-sa/galat
        // dikhta hai. Yeh hint dene se OS video-surface ke asli on-screen rect (playerView
        // ke andar jahan actual video content hai, letterbox bars ke bina) se seedha
        // shrink karta hai — bilkul YouTube jaisa smooth "video hi shrink ho raha hai"
        // feel deta hai.
        //
        // Bug fix ("bada player khulta hai fir PiP hota hai" jhatka): jab yeh chhote
        // inline player se turant PiP (enter_pip_immediately) ke through aaya ho, to
        // is poore-screen wale playerView rect ki jagah ORIGINAL chhote inline player
        // ka on-screen rect (pendingImmediatePipSourceRect, MainActivity se aaya) use
        // karo. Isse system ka shrink animation seedha usi chhoti jagah se shuru hota
        // hai jahan video pehle se dikh raha tha — poori screen kabhi flash nahi hoti,
        // ek hi continuous "chhota video seedha PiP corner tak shrink" motion dikhta hai.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val immediateRect = pendingImmediatePipSourceRect
            if (immediateRect != null && immediateRect.width() > 0 && immediateRect.height() > 0) {
                try { builder.setSourceRectHint(immediateRect) } catch (_: Exception) {}
            } else if (::playerView.isInitialized) {
                try {
                    val videoRect = Rect()
                    playerView.getGlobalVisibleRect(videoRect)
                    if (videoRect.width() > 0 && videoRect.height() > 0) {
                        builder.setSourceRectHint(videoRect)
                    }
                } catch (_: Exception) {}
            }
        }
        if (::player.isInitialized) {
            val vw = player.videoSize.width
            val vh = player.videoSize.height
            if (vw > 0 && vh > 0) {
                // Android PiP sirf 1:2.39 se 2.39:1 tak ke aspect ratio allow
                // karta hai, isse bahar jaane par crash hota hai — isliye clamp.
                var num = vw
                var den = vh
                val maxRatio = 2.39f
                if (num.toFloat() / den.toFloat() > maxRatio) {
                    num = (den * maxRatio).toInt()
                } else if (den.toFloat() / num.toFloat() > maxRatio) {
                    den = (num * maxRatio).toInt()
                }
                try {
                    builder.setAspectRatio(Rational(num, den))
                } catch (_: Exception) {}
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder.setActions(
                listOf(
                    buildRemoteAction(
                        android.R.drawable.ic_media_rew, "Rewind 10s", ACTION_PIP_REWIND
                    ),
                    buildPlayPauseRemoteAction(),
                    buildRemoteAction(
                        android.R.drawable.ic_media_ff, "Forward 10s", ACTION_PIP_FORWARD
                    ),
                    buildRemoteAction(
                        if (pipSpeedBoosted) R.drawable.ic_play_arrow else R.drawable.ic_fast_forward,
                        if (pipSpeedBoosted) "Normal Speed" else "2x Speed",
                        ACTION_PIP_SPEED_TOGGLE
                    )
                )
            )
        }
        // Bug fix ("sab jagah jhatka-sa lagta hai" — asli wajah): ab tak hum
        // Home/back-gesture par khud manually onUserLeaveHint() se
        // enterPictureInPictureMode() bulaate the — yeh hamesha ek REACTION
        // thi (activity pehle se hi background hone lagi hoti, PHIR hum shrink
        // shuru karte), isliye system ka apna leave-gesture/animation aur
        // hamara PiP-shrink do ALAG motions ban jaate — thoda lag/snap jaisa
        // feel deta, chahe sourceRectHint sahi ho.
        //
        // Android 12+ (API 31) par setAutoEnterEnabled(true) ke saath, OS khud
        // hi back-gesture/Home ke EXACT saath synchronized ek single seamless
        // shrink animation karta hai (bilkul YouTube jaisa) — hume manually
        // enterPictureInPictureMode() call karne ki zaroorat hi nahi padti,
        // isliye koi do-motion jhatka nahi banta. (Dekho onUserLeaveHint() ka
        // guard neeche, aur refreshPipParams() jo params ko hamesha up-to-date
        // rakhta hai taaki OS ke paas seamless-enter ke waqt sahi sourceRectHint
        // /aspect-ratio pehle se maujood ho.)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try { builder.setAutoEnterEnabled(true) } catch (_: Exception) {}
        }
        return builder
    }

    // Params ko hamesha fresh rakhne ke liye (onResume, video-size change, orientation
    // change par) — taaki setAutoEnterEnabled(true) wala seamless auto-enter hamesha
    // sahi sourceRectHint/aspect-ratio ke saath fire ho, kabhi stale rect se nahi.
    private fun refreshPipParams() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        if (!::player.isInitialized || isInPictureInPictureMode) return
        try { setPictureInPictureParams(buildPipParams().build()) } catch (_: Exception) {}
    }

    private fun buildRemoteAction(iconRes: Int, title: String, action: String): RemoteAction {
        val icon = Icon.createWithResource(this, iconRes)
        val intent = Intent(action).setPackage(packageName)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        // Har action ka apna unique request code chahiye, warna PendingIntent.getBroadcast()
        // sab ek hi PendingIntent reuse kar leta (extras/action update nahi hote).
        val requestCode = action.hashCode()
        val pendingIntent = PendingIntent.getBroadcast(this, requestCode, intent, flags)
        return RemoteAction(icon, title, title, pendingIntent)
    }

    private fun buildPlayPauseRemoteAction(): RemoteAction {
        val isPlaying = ::player.isInitialized && player.isPlaying
        val iconRes = if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
        val icon = Icon.createWithResource(this, iconRes)
        val title = if (isPlaying) "Pause" else "Play"
        val intent = Intent(ACTION_PIP_PLAY_PAUSE).setPackage(packageName)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val pendingIntent = PendingIntent.getBroadcast(this, 0, intent, flags)
        return RemoteAction(icon, title, title, pendingIntent)
    }

    // Chhote inline player (MainActivity) se yahan aaye the to wapas jaate waqt current
    // position + playing state bhi saath bhej do, taaki chhota player wahi se resume ho
    // jahan tak fullscreen mein dekha tha — stale/purani position par na ruke.
    override fun finish() {
        if (::player.isInitialized) {
            setResult(
                RESULT_OK,
                Intent().apply {
                    putExtra("resume_position_ms", player.currentPosition)
                    // Bug fix: normally player.playWhenReady abhi ka live state
                    // reflect karta hai — lekin genuine PiP-close case mein hum
                    // usse jaan-bujh kar false kar chuke hote hain (background
                    // audio-leak rokne ke liye). Agar wahi live value yahan bhej
                    // dete to MainActivity video ko paused hi resume karta —
                    // isliye agar upar wala override maujood hai (PiP close se
                    // pehle ka asli intent), usi ko priority do.
                    putExtra("resume_playing", resumePlayingIntentOverride ?: player.playWhenReady)
                    // User ki request: MainActivity ko batao ki yeh finish() ek
                    // genuine PiP "X"/swipe-away close ki wajah se ho raha hai (na
                    // ki expand-to-fullscreen ke baad ka normal back-press) — taaki
                    // wahan na background mein kuchh resume ho, na WebView wapas us
                    // watch page par forward jaaye (dekho MainActivity.onActivityResult()).
                    // wasInRealPipMode abhi bhi true hota hai sirf genuine PiP-close
                    // ke waqt hi — expand ke baad onPictureInPictureModeChanged() ise
                    // pehle hi false kar chuka hota hai (dekho uska comment).
                    putExtra("pip_closed", pipCloseFlagForResult)
                }
            )
        }
        super.finish()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // Agar More menu panel khula hai to back usko pehle band kare, poori
        // screen se bahar na nikle.
        if (moreMenuPanel != null) {
            hideMoreMenu()
            return
        }
        // FIX (YouTube jaisa predictable back): pehle yahan system back/gesture
        // par bhi tryEnterPipOnBack() try hota tha — matlab visible back-arrow
        // (upar backButton.setOnClickListener, jo seedha finish() karta hai) aur
        // system back button/gesture do ALAG cheezein karte the: ek seedha wapas
        // MainActivity ke inline mini-player par le jaata, doosra achanak
        // OS-level floating PiP window khol deta. Yahi asymmetry "khichdi" ka
        // asli kaaran thi — PiP window expand karke wapas fullscreen aao, phir
        // back dabao to phir PiP khul jaaye — user kabhi seedhe "normal wapas"
        // nahi nikal paata tha, ek expand/collapse loop mein fasa reh jaata.
        //
        // YouTube ka asli behavior: back = hamesha seedha wapas (in-app
        // mini-player), PiP sirf Home button/app-switch (onUserLeaveHint, neeche
        // dekho) ya explicit PiP icon/swipe-down gesture se. Ab back button aur
        // system back/gesture dono ek hi (consistent) cheez karte hain.
        super.onBackPressed()
        // Opening ke saath match karta hua fade+zoom-out — Home screen par
        // wapas jaate waqt bhi default abrupt slide na ho.
        @Suppress("DEPRECATION")
        overridePendingTransition(R.anim.activity_close_enter, R.anim.activity_close_exit)
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // Android 12+ (API 31) par setAutoEnterEnabled(true) (buildPipParams() dekho)
        // OS ko khud hi is exact moment par seamless PiP shrink karne deta hai — agar
        // hum yahan bhi manually enterPictureInPictureMode() bulaate to do transitions
        // race karke hi jhatka wapas laa dete. Sirf purane devices (< API 31, jahan
        // auto-enter available hi nahi) ke liye manual fallback chahiye.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            tryEnterPipOnBack()
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        if (isInPictureInPictureMode) {
            wasInRealPipMode = true
            // Bug fix ("PiP se fullscreen hote waqt video pause ho jaata hai"):
            // ExoPlayer khud audio-focus handle karta hai (handleAudioFocus=true,
            // dekho initializePlayer()). PiP <-> fullscreen ke beech window-manager
            // transition ke dauraan kai devices par ek chhoti transient audio-focus
            // loss/gain spuriously fire ho jaati hai — ExoPlayer usko genuine focus-
            // loss maan kar khud hi playWhenReady=false kar deta hai, aur focus-regain
            // wapas theek se receive na hone par video paused hi reh jaata hai. Yahan
            // "PiP mein jaate waqt kya chal raha tha" record kar lo, taaki restore ke
            // waqt (neeche) isko forcibly wapas assert kiya jaa sake.
            if (::player.isInitialized) pipEntryWasPlaying = player.playWhenReady
            // Chhote floating window mein hamara bhaara-bharkam custom UI (top
            // bar, quick actions, gesture overlays) dikhana theek nahi — sab
            // chhupa do, sirf video + system ka apna minimal play/pause chrome.
            controlsHandler.removeCallbacks(hideControlsRunnable)
            topBar.visibility = View.GONE
            quickActionsScroll.visibility = View.GONE
            gestureIndicator.visibility = View.GONE
            speedIndicatorBadge.visibility = View.GONE
            seekIndicatorLeft.visibility = View.GONE
            seekIndicatorRight.visibility = View.GONE
            volumeSliderContainer.visibility = View.GONE
            brightnessSliderContainer.visibility = View.GONE
            playerView.useController = false
        } else {
            wasInRealPipMode = false
            // BUG FIX (rewrite — dekho `pendingPipExitDecision` field ka comment
            // upar): yahan koi isFinishing-based faisla NAHI liya jaata ab. Bas
            // itna record karo ki "PiP se abhi-abhi bahar aaye hain, faisla
            // baaki hai" — asli faisla onResume() (= expand) ya onStop()
            // (= genuine close) mein, jo bhi pehle genuinely fire ho, wahan
            // hota hai.
            pendingPipExitDecision = true
        }
    }

    // BUG FIX (major rewrite — user report: "PiP ka X dabane par bhi cut nahi
    // hota"): yeh function sirf tabhi kuch karta hai jab `pendingPipExitDecision`
    // set ho (matlab hum abhi-abhi real PiP se bahar aaye the aur faisla baaki
    // tha). Isko onResume() se bulaya jaata hai — Android guarantee karta hai ki
    // "tap to expand" ONLY yahi path hai jahan Activity dobara RESUMED hoti hai,
    // isliye yahan pahunchna khud hi pakka signal hai ki yeh genuine expand hai,
    // koi isFinishing check ki zaroorat nahi.
    private fun handlePipExpand() {
        if (!pendingPipExitDecision) return
        pendingPipExitDecision = false

        // FIX (user report): yeh PiP normal/inline player se seedhi bani thi
        // (pipOriginFromInline) — isko tap karke "bada" karne par Android bas
        // is Activity ko wapas fullscreen dikha deta hai, jo galat hai: yeh
        // video kabhi genuinely fullscreen dekhi hi nahi gayi thi. User yahan
        // NORMAL (inline) player hi expect karta hai, fullscreen nahi.
        if (pipOriginFromInline) {
            pipCloseFlagForResult = false
            if (usingSharedPlayer && ::player.isInitialized) {
                player.setForegroundMode(true)
                playerView.player = null
            }
            @Suppress("DEPRECATION")
            overridePendingTransition(R.anim.activity_close_enter, R.anim.activity_close_exit)
            finish()
            return
        }

        // Wapas full-size par aane par controls/controller dobara enable karo.
        playerView.useController = true
        if (::topBar.isInitialized) showAllControls()
        // Bug fix ("PiP se fullscreen hote waqt video pause ho jaata hai"):
        // upar record kiya gaya pipEntryWasPlaying ab forcibly assert karo.
        if (::player.isInitialized) {
            val shouldResume = pipEntryWasPlaying
            playerView.postDelayed({
                if (!isFinishing && ::player.isInitialized) player.playWhenReady = shouldResume
            }, 150)
        }
    }

    // BUG FIX (major rewrite — dekho `pendingPipExitDecision` field ka comment
    // upar): agar onStop() fire ho raha hai jabki PiP-exit ka faisla abhi bhi
    // pending hai (matlab beech mein onResume() KABHI nahi aaya), to yeh pakka
    // ek genuine "X"/swipe close hai — Activity kabhi RESUMED hui hi nahi.
    // isFinishing par bharosa kiye bina, yahin definitively cut kar do.
    private fun handlePipGenuineClose() {
        if (!pendingPipExitDecision) return
        pendingPipExitDecision = false
        // BUG FIX (asli root cause — "PiP cut hota hai lekin audio background
        // mein bajta rehta hai, agla video kholne par black screen"): pehle
        // yahan `pipCloseFlagForResult = pipOriginFromInline` tha — matlab
        // MainActivity ko "genuine close" tabhi bataya jaata tha jab PiP seedhe
        // inline (chhote) player se turant-PiP ban kar aayi thi. Lekin
        // handlePipGenuineClose() khud sirf TABHI chalta hai jab yeh definitively
        // ek real "X"/swipe close ho (upar `pendingPipExitDecision` ka comment
        // dekho) — origin (inline ho ya fullscreen se) se iska koi lena-dena
        // nahi. Jab origin fullscreen thi (jo sabse aam case hai), pehle yeh
        // flag false chala jaata tha, MainActivity ise ek NORMAL "wapas aana"
        // samajh kar apna inlinePlayer resume (playWhenReady = true) kar deta
        // tha — isi wajah se PiP window screen se "cut" ho jaati thi lekin audio
        // chalta reh jaata. Ab yeh flag hamesha true jaata hai jab bhi yeh
        // genuinely PiP-close path hai, origin chahe jo bhi ho.
        pipCloseFlagForResult = true
        // BUG FIX (agla video black screen dikhata tha): yeh flag pehle kabhi
        // set hi nahi hota tha (dekho iski declaration ka comment) — isliye
        // onDestroy() mein neeche wala "poora release + SharedPlayerHolder
        // clear" branch genuine PiP-close par bhi kabhi nahi chalta tha. Player
        // sirf pause hota, zinda reh jaata, aur agla video khulne par ek
        // orphaned/stale instance reuse hone ki koshish hoti — surface conflict
        // se black screen, jab tak fullscreen jaakar surfaces force-refresh na
        // ho jaayein.
        releasePlayerFullyOnDestroy = true
        if (::player.isInitialized) {
            // Bug fix ("PiP X se band karne ke baad video wapas nahi chalta,
            // black screen reh jaata hai"): player ko pause zaroor karo (audio
            // background mein leak na ho), lekin release/SharedPlayerHolder-
            // clear yahan MAT karo — woh kaam MainActivity.onActivityResult()
            // "pip_closed" extra dekh kar khud (definitively) karega.
            resumePlayingIntentOverride = player.playWhenReady
            player.playWhenReady = false
        }
        BackgroundPlaybackService.stop(this)
        // System ne khud finish() trigger na kiya ho (kuch OEM par aisa hota
        // hai) to bhi is Activity/PiP window ko screen par kabhi "atka" na
        // chhodo — khud finish() bulao (already-finishing par yeh safe/no-op).
        if (!isFinishing) finish()
    }

    override fun onResume() {
        super.onResume()
        // Genuine "tap to expand" ka pakka signal — dekho handlePipExpand()
        // function ka comment.
        handlePipExpand()
        // Wapas app khulte hi background service band kar do — ab notification
        // aur foreground-priority ki zaroorat nahi, Activity khud foreground me hai.
        BackgroundPlaybackService.stop(this)
        // Params ko fresh rakho — taaki agla seamless auto-enter (setAutoEnterEnabled)
        // hamesha abhi ka sahi playerView rect/aspect use kare.
        refreshPipParams()
    }

    override fun onPause() {
        super.onPause()
        // Bug fix (black screen on return to inline player): Android ka callback order
        // hai — is Activity ka onPause() PEHLE chalta hai, uske baad hi MainActivity ka
        // onActivityResult() (jahan wo apne inline PlayerView ko wapas isi player se
        // attach karta hai). Pehle hum sirf onDestroy() mein playerView ko detach karte
        // the — jo onActivityResult() ke KAAFI baad chalta hai, isliye thodi der ke liye
        // dono PlayerView (fullscreen wala aur inline wala) ek saath same ExoPlayer se
        // juda rehte the — Surface conflict, black frame. Ab agar hum genuinely finish
        // ho kar MainActivity ko wapas control de rahe hain (aur shared player istemal
        // ho raha tha), turant yahin detach kar do.
        if (isFinishing && usingSharedPlayer && ::player.isInitialized) {
            // Bug fix (premium smoothness / "buffering" feel): MainActivity ko
            // is instance ka control wapas milne tak ek chhota surface-less gap
            // hota hai (yeh Activity ka Surface abhi detach ho raha hai, waha
            // ka PlayerView abhi attach nahi hua) — bina foreground-mode ke,
            // decoder is gap mein apne resources release/reconfigure kar sakta
            // hai, jisse naye Surface par turant continue karne ke bajaye ek
            // chhota rebuffer/stutter dikhta. setForegroundMode(true) decoder ko
            // is gap ke dauraan bhi "ready" rakhta hai.
            player.setForegroundMode(true)
            playerView.player = null
        }
        val currentIndex = if (::player.isInitialized) player.currentMediaItemIndex else -1
        val currentItem = queue.getOrNull(currentIndex)
        val currentUri = currentItem?.uriString
        if (currentUri != null && ::player.isInitialized) {
            val prefs = getSharedPreferences("playback_positions", MODE_PRIVATE)
            prefs.edit().putLong(currentUri, player.currentPosition).apply()

            val title = player.currentMediaItem?.mediaMetadata?.title?.toString()
                ?: currentItem.title
            HistoryStore.addOrUpdate(
                this,
                HistoryEntry(
                    uri = currentUri,
                    title = title,
                    isVideo = true,
                    positionMs = player.currentPosition,
                    durationMs = player.duration.coerceAtLeast(0),
                    playedAt = System.currentTimeMillis()
                )
            )
        }
        // Ab background continue sirf PiP floating window ke through hota hai
        // (purana alag "Background Play" audio-only toggle hata diya gaya hai —
        // dono buttons ab PiP hi kholte hain). PiP mein nahi hai to pause karo.
        // BUG FIX (PiP "X" close ke baad bhi audio background mein chalta rehta
        // tha): system ke PiP window ka "X" (close) button dabane par Android
        // seedha isFinishing=true kar deta hai lekin isInPictureInPictureMode
        // abhi bhi true hi report karta hai (mode kabhi "exit" hua hi nahi,
        // activity seedha finish ho rahi hai) — isliye pehle yeh code isse
        // ek genuine ongoing PiP samajh kar foreground service START kar deta
        // tha aur player ko pause hi nahi karta tha. Ab isFinishing bhi check
        // karte hain: agar activity finish ho rahi hai to turant pause + service
        // stop, chahe isInPictureInPictureMode abhi bhi true dikha raha ho.
        //
        // BUG FIX (audio thodi der ke liye "leak" hoti thi + inline player black
        // screen reh jaata tha): agar yeh Activity apna shared/borrowed player
        // MainActivity ko wapas handoff kar rahi hai (usingSharedPlayer &&
        // isFinishing), to playWhenReady ko yahan CHHEDO MAT. MainActivity ka
        // onActivityResult() is Activity ke onPause() ke turant baad hi (onStop()
        // se PEHLE) chalta hai aur khud playWhenReady = wasPlaying set karta hai —
        // agar hum yahan pehle hi false kar dein, to woh sahi resume state ke
        // bawajood bhi kabhi-kabhi galat/stale state pe fight karta reh jaata,
        // aur baad mein aane wala onStop() (isFinishing abhi bhi true) us resume
        // ko dobara undo kar deta — video wahin frozen/black reh jaata jabki
        // audio ek pal ke liye already bajj chuka hota (isi wajah se dono bugs
        // saath dikhte the: "audio aata hai" aur "black screen rehta hai").
        val handingOffToInline = isFinishing && usingSharedPlayer
        val isPip = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && isInPictureInPictureMode && !isFinishing
        if (handingOffToInline) {
            // Control (aur play/pause state) MainActivity ke onActivityResult()
            // ko de diya — foreground service ab bhi band kar do (MainActivity
            // khud foreground hai, isliye notification ki zaroorat nahi).
            BackgroundPlaybackService.stop(this)
        } else if (!isPip) {
            player.playWhenReady = false
            if (isFinishing) BackgroundPlaybackService.stop(this)
        } else if (::player.isInitialized) {
            // Foreground service start karo taaki Android process ko background
            // me kill/suspend na kare — isi ke bina pehle sirf thodi der audio
            // chalta tha ya screen off hote hi ruk jaata tha.
            val title = player.currentMediaItem?.mediaMetadata?.title?.toString()
                ?: queue.getOrNull(player.currentMediaItemIndex)?.title
                ?: "Video"
            BackgroundPlaybackService.start(this, title)
        }
    }

    override fun onStop() {
        super.onStop()
        // BUG FIX (major rewrite — user report: "PiP ka X dabane par bhi cut
        // nahi hota"): agar `pendingPipExitDecision` abhi bhi pending hai yahan
        // tak (matlab PiP se bahar aane ke baad Activity kabhi onResume() tak
        // nahi pahunchi), to yeh 100% pakka ek genuine "X"/swipe close hai —
        // dekho handlePipGenuineClose() ka comment. Ise sabse pehle, kisi bhi
        // aur logic se pehle handle karo.
        handlePipGenuineClose()
        // BUG FIX (PiP black screen): pehle yahan bina kisi shart ke turant
        // pause kar dete the ye soch kar ki "onStop() ka matlab hi hai Activity
        // dikh nahi rahi". Lekin galat nikla — kai devices/launchers (especially
        // gesture-nav wale) PiP mein enter karte hi turant onStop() bhi fire
        // kar dete hain jabki PiP window abhi-abhi screen par visible ho raha
        // hota hai. Result: player turant pause ho jaata, naya frame kabhi
        // render hi nahi hota naye (chhote) PiP surface size par — isliye PiP
        // window sirf ek solid BLACK box dikhta tha, video kabhi dikhta hi
        // nahi tha.
        // Fix: agar hum abhi PiP mein hain to yahan pause mat karo — video
        // chalta rahega jaisa real PiP mein hona chahiye. "X" button se PiP
        // close karne wala case already onPictureInPictureModeChanged() mein
        // (isFinishing check ke saath) alag se handle hota hai, isliye yahan
        // unconditional pause hataane se woh purana fix bhi nahi tootta.
        // Yahan bhi wahi isFinishing check (upar onPause() mein detail comment
        // dekho) — PiP "X" close case mein isInPictureInPictureMode abhi bhi
        // true reh sakta hai, isFinishing hi asli signal hai ki activity band
        // ho rahi hai aur audio ab turant rukna chahiye.
        //
        // BUG FIX: agar shared player hai aur handoff MainActivity ko ho raha hai,
        // to yahan bhi (jaise onPause() mein) playWhenReady ko chhedo mat — onStop()
        // MainActivity ke onActivityResult() ke BAAD chalta hai (jo already resume
        // kar chuka hota hai), isliye yahan force-pause karna seedha uska resume
        // undo kar deta tha, aur inline player black/frozen reh jaata tha.
        val handingOffToInline = isFinishing && usingSharedPlayer
        if (handingOffToInline) {
            if (::player.isInitialized) BackgroundPlaybackService.stop(this)
            return
        }
        val isPip = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && isInPictureInPictureMode && !isFinishing
        if (::player.isInitialized && !isPip) {
            player.playWhenReady = false
            BackgroundPlaybackService.stop(this)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (pipReceiverRegistered) {
            try { unregisterReceiver(pipActionReceiver) } catch (_: Exception) {}
            pipReceiverRegistered = false
        }
        BackgroundPlaybackService.stop(this)
        abHandler.removeCallbacksAndMessages(null)
        controlsHandler.removeCallbacksAndMessages(null)
        sleepRunnable?.let { sleepHandler.removeCallbacks(it) }
        sleepDimRunnable?.let { sleepHandler.removeCallbacks(it) }
        sleepDimAnimator?.cancel()
        featuresHandler.removeCallbacksAndMessages(null)
        gestureIndicator.removeCallbacks(hideIndicatorRunnable)
        seekIndicatorLeft.removeCallbacks(hideSeekLeftRunnable)
        seekIndicatorRight.removeCallbacks(hideSeekRightRunnable)
        sliderHandler.removeCallbacksAndMessages(null)
        // Bug fix (cleanup): yeh do pending callbacks pehle onDestroy mein clear
        // nahi hote the — agar activity multi-tap-seek ke 700ms window ke andar
        // ya speed-badge hide hone se pehle destroy hoti, to unka Runnable baad
        // mein bhi fire hota (halaanki crash nahi karta, but ek stale/unnecessary
        // callback tha jo turant clean hona chahiye tha).
        seekSessionHandler.removeCallbacks(seekSessionResetRunnable)
        speedIndicatorBadge.removeCallbacks(hideSpeedIndicatorRunnable)
        releaseAudioFx()
        dualSubtitleController?.stop()
        scrubPreviewExecutor.execute {
            try { scrubPreviewRetriever?.release() } catch (_: Exception) {}
        }
        scrubPreviewExecutor.shutdown()
        onlineSubtitleExecutor.shutdown()
        chapterAnalysisExecutor.shutdown()
        cancelSpeedRamp()
        castSessionManagerListener?.let {
            try {
                CastContext.getSharedInstance(this).sessionManager.removeSessionManagerListener(it, CastSession::class.java)
            } catch (_: Exception) {
                // Device par Play Services na ho to yahan exception aa sakta hai, safe hai ignore karna
            }
        }
        if (::player.isInitialized) {
            pipEntryFrameListener?.let { player.removeListener(it) }
            pipEntryFrameListener = null
            player.removeListener(playerListener)
            player.removeAnalyticsListener(statsAnalyticsListener)
            if (usingSharedPlayer && !releasePlayerFullyOnDestroy) {
                // MainActivity ka inline player hai — release nahi karna, wahi isi
                // instance ko wapas apne overlay mein istemal karega. Bas playerView
                // se detach kar do taaki is (ab destroy ho rahi) Activity ka koi
                // reference player par bacha na rahe.
                playerView.player = null
            } else {
                // Bug fix (PiP band karne ke baad black screen + audio chalte
                // rehna, phir wahi video dobara khola jaaye to bhi black screen):
                // agar yeh genuine PiP close (X/swipe) hai to shared player hone
                // ke bawajood ise poori tarah release karo aur SharedPlayerHolder
                // (upar wale block mein) pehle hi clear ho chuka hai — taaki agli
                // baar wahi video khulne par ek bilkul fresh player bane, kisi
                // orphaned/broken instance ko reuse na kiya jaaye.
                playerView.player = null
                player.release()
            }
        }
    }
}
