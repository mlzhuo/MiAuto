package com.andrerinas.openheadunit.connection

import android.content.Context
import android.content.Intent
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.view.KeyEvent
import android.view.Surface
import androidx.core.content.IntentCompat
import com.andrerinas.openheadunit.utils.AppLog
import com.miauto.app.IccoaManager
import com.ucar.vehiclesdk.UCarCommon
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Bridges the MiAuto ICCOA CarLink SDK (a plain Java facade, [IccoaManager]) into DiAuto's
 * reactive architecture.
 *
 * DiAuto drives every consumer from a single `StateFlow` (see `CommManager.connectionState`);
 * the ICCOA SDK instead pushes callbacks on worker threads. This class is the adapter between
 * the two: it implements [IccoaManager.Listener], folds the raw callbacks into one observable
 * [state], and exposes the navigation stream the BYD cluster / HUD outputs consume.
 *
 * MiAuto is ICCOA-only — there is no Android Auto path at runtime — so this bridge (not
 * `CommManager`) is the source of truth for both the home screen and the projection activity.
 */
class IcocaBridge(context: Context) : IccoaManager.Listener {

    /** Coarse connection phase, mapped from the SDK's numeric state. */
    enum class Phase {
        /** Not started, or stopped by the user. */
        Idle,

        /** BLE advertising; waiting for a phone to discover us. */
        Advertising,

        /** Phone found us and is pairing / negotiating (PIN exchanged, Wi-Fi or USB negotiated). */
        Pairing,

        /** Session established; the phone may now push video. */
        Connected,
    }

    data class State(
        val phase: Phase = Phase.Idle,
        /** Pairing code to show on screen, if the SDK handed one out. */
        val pinCode: String? = null,
        val carDeviceName: String? = null,
        /** Last raw SDK log line, kept for the diagnostics screen. */
        val lastLog: String? = null,
        /** Negotiation error code from the SDK (10000+), kept so the car screen can show why it failed. */
        val lastError: Int? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val _navigation = MutableStateFlow<UCarCommon.NavigationInfo?>(null)

    /** Latest navigation snapshot from the phone (ICCOA `NotifyNavigationInfo`). */
    val navigation: StateFlow<UCarCommon.NavigationInfo?> = _navigation.asStateFlow()

    /** The underlying SDK facade. Public so projection / navigation code can reach raw APIs. */
    val manager = IccoaManager(context.applicationContext, this)

    /** 手机当前是否在放音（来自 ICCOA 的 MusicInfo），用于把播放/暂停切换键解析成显式键。 */
    @Volatile
    private var phoneIsPlaying = false

    /**
     * 会话存续期间保持 active 的 MediaSession，用来接管方向盘媒体键。
     *
     * <p>没有它，车机自带的音乐/收音机会作为系统里唯一「活跃的媒体会话」拿到按键，抢走音频
     * 焦点；SDK 随即给手机发 MEDIA_PAUSE，用户看到的就是切歌变成暂停。按键收到后经 ICCOA
     * 控制通道转给手机（见 [sendMediaKey]）。未连接时保持 inactive，按键仍归车机。
     */
    private val mediaSession = MediaSessionCompat(context.applicationContext, "MiAutoIcoca").apply {
        setFlags(
            MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or
                MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS
        )
        setCallback(object : MediaSessionCompat.Callback() {
            override fun onMediaButtonEvent(mediaButtonEvent: Intent?): Boolean {
                val event = mediaButtonEvent?.let {
                    IntentCompat.getParcelableExtra(it, Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
                } ?: return false
                if (event.action != KeyEvent.ACTION_DOWN && event.action != KeyEvent.ACTION_UP) return false
                if (event.repeatCount > 0) return true // 长按不重复切歌，但也别漏给车机
                val down = event.action == KeyEvent.ACTION_DOWN
                if (!this@IcocaBridge.sendMediaKey(event.keyCode, down)) return false
                AppLog.i("ICCOA: media key ${event.keyCode} ${if (down) "DOWN" else "UP"} → phone")
                return true
            }
        })
    }

    @Volatile
    private var started = false

    /** Starts (or restarts) the ICCOA link: SDK init once, then BLE advertising. */
    fun start() {
        if (started) {
            AppLog.i("ICCOA: start() ignored — already started; restarting advertise")
            manager.start() // init-once inside; falls through to reAdvertise()
            return
        }
        started = true
        AppLog.i("ICCOA: start()")
        updatePhase(Phase.Advertising)
        manager.start()
    }

    /** Stops advertising and tears the session down, keeping the SDK initialised for a restart. */
    fun stop() {
        if (!started) return
        started = false
        AppLog.i("ICCOA: stop()")
        manager.stop()
        updatePhase(Phase.Idle)
    }

    val isStarted: Boolean get() = started

    /**
     * Drops the current session but keeps the link alive and advertising, so the phone can come
     * straight back without the user restarting the app. Backs the projection screen's disconnect
     * affordance, which must not behave like a power-off.
     */
    fun disconnectSession() {
        AppLog.i("ICCOA: disconnectSession()")
        manager.disconnectSession()
        updatePhase(Phase.Advertising)
    }

    /**
     * User-triggered "refresh pairing code": re-broadcasts immediately so the phone re-prompts,
     * instead of waiting for the 30-second watchdog. The stale code is cleared at once so the
     * screen never shows a code that is already dead.
     */
    fun refreshPairing() {
        AppLog.i("ICCOA: refreshPairing()")
        manager.refreshPairing()
        _state.update { it.copy(pinCode = null) }
    }

    /** Native screen size handed to the SDK; drives the negotiated stream resolution. */
    fun setScreenSize(width: Int, height: Int) = manager.setScreenSize(width, height)

    /**
     * Size of the projection area declared to the SDK (`videoDisplayWidth/Height`). Touch
     * coordinates must be expressed in this space; the SDK scales them to the phone's screen.
     */
    fun displaySize(): Pair<Int, Int> = manager.displayWidth to manager.displayHeight

    /** Sends one touch event over the SDK's UIBC back-channel. Raw action, per-pointer id, display x/y. */
    fun sendTouch(action: Int, count: Int, trackIds: IntArray, xs: IntArray, ys: IntArray): Boolean =
        manager.sendTouchEvent(action, count, trackIds, xs, ys)

    /**
     * 媒体键（按下/抬起）经控制通道发给手机。false = 该键不归手机管，调用方交还系统。
     *
     * <p>SDK 没有 PLAY_PAUSE 键码，切换键按手机当前是否在放音解析成显式 PLAY/PAUSE，并在
     * 按下的瞬间整组下发（按下+抬起），避免按下到抬起之间播放状态翻转导致同一个手势发两次。
     */
    fun sendMediaKey(keyCode: Int, down: Boolean): Boolean {
        if (keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE || keyCode == KeyEvent.KEYCODE_HEADSETHOOK) {
            if (!down) return true // 整组已在按下时发完
            val resolved = if (phoneIsPlaying) KeyEvent.KEYCODE_MEDIA_PAUSE else KeyEvent.KEYCODE_MEDIA_PLAY
            if (!manager.sendMediaKey(resolved, true)) return false
            manager.sendMediaKey(resolved, false)
            return true
        }
        return manager.sendMediaKey(keyCode, down)
    }

    /**
     * Hands the projection [surface] to the SDK. Idempotent for the same surface; a *different*
     * surface means the old one is gone, so the cast is torn down and re-established on the new one.
     */
    fun setSurface(surface: Surface) = manager.setSurface(surface)

    /**
     * The projection surface went away. Stopping the cast here is what keeps the video pipeline
     * alive across a surface change: letting the decoder write into a destroyed BufferQueue ends
     * video permanently (black screen, audio still playing, phone still reporting connected).
     */
    fun onSurfaceDestroyed() = manager.onSurfaceDestroyed()

    /** Last PIN the SDK issued, or null. Useful when the projection activity opens late. */
    fun lastPinCode(): String? = manager.lastPinCode

    // ------------------------------------------------------------------
    // IccoaManager.Listener — called on SDK worker threads, hence the StateFlow.
    // ------------------------------------------------------------------

    override fun onLog(msg: String?) {
        AppLog.i("ICCOA: $msg")
        _state.update { it.copy(lastLog = msg) }
    }

    override fun onConnectionState(stateCode: Int) {
        val phase = when (stateCode) {
            // 2 CONNECTED / 3 ESTABLISHED / 4 会话已建立：只有这些才代表会话真的起来了。
            2, 3, 4 -> Phase.Connected
            1 -> Phase.Pairing
            0 -> Phase.Advertising
            else -> null
        }
        if (phase == null) {
            // -1 是协商成功瞬间的过渡态：把它当 Advertising 会把刚建立的会话丢掉，
            // 投屏页因此永远不会被打开。>=10000 则是 SDK 的协商失败错误码。
            val note = if (stateCode >= 10000) "negotiation failed" else "transient"
            AppLog.i("ICCOA: connection state $stateCode ($note), phase stays ${_state.value.phase}")
            if (stateCode >= 10000) _state.update { it.copy(lastError = stateCode) }
            return
        }
        AppLog.i("ICCOA: connection state $stateCode -> $phase")
        updatePhase(phase)
    }

    override fun onConnectProgress(progress: Int) {
        // 100 = the link is up. This is the signal the numeric state codes cannot be trusted for:
        // some phones only emit the transient -1, and without this the projection screen — the only
        // thing that provides a Surface — would never open.
        AppLog.i("ICCOA: connect progress $progress")
        if (progress >= 100) updatePhase(Phase.Connected)
    }

    override fun onPinCode(pinCode: String?, carDeviceName: String?) {
        AppLog.i("ICCOA: pinCode=$pinCode carDeviceName=$carDeviceName")
        updatePhase(Phase.Pairing)
        _state.update {
            it.copy(pinCode = pinCode, carDeviceName = carDeviceName, lastError = null)
        }
    }

    override fun onCastStarted() {
        AppLog.i("ICCOA: cast started")
        updatePhase(Phase.Connected)
    }

    override fun onNavigation(info: UCarCommon.NavigationInfo?) {
        _navigation.value = info
        // Navigation data only travels over a live session. If the state codes never announced the
        // connection, the first payload is still proof that it exists — and without a Connected
        // phase the projection screen (and therefore any video) never opens.
        if (info != null && _state.value.phase != Phase.Connected) {
            AppLog.i("ICCOA: navigation payload received — treating session as connected")
            updatePhase(Phase.Connected)
        }
    }

    /**
     * 手机在放音时把会话打成 PLAYING：车机（MediaSessionManager）会优先把媒体键交给「正在
     * 播放」的会话；手机暂停则落回 PAUSED，按键自然还给车机自带的播放器。
     */
    override fun onMusicInfo(info: UCarCommon.MusicInfo?) {
        if (info == null) return
        phoneIsPlaying = info.isPlaying
        mediaSession.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setState(
                    if (info.isPlaying) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED,
                    PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN,
                    1f,
                )
                .build()
        )
    }

    /**
     * Moves the phase and keeps the stored PIN honest: a code only means something while the SDK is
     * still pairing.
     *
     * Leaving it up after the session opens would show a code that no longer matches anything, and
     * leaving it up after a drop is worse — the phone is about to be issued a *different* one, so
     * the screen would invite the user to type a code that is already dead. Clearing on every
     * non-pairing phase is what makes the code "refresh itself" across reconnects.
     */
    private fun updatePhase(phase: Phase) {
        // 只有会话真的建立时才接管媒体键；断开会自动把按键还给车机。
        mediaSession.isActive = phase == Phase.Connected
        if (phase != Phase.Connected) {
            mediaSession.setPlaybackState(null)
            phoneIsPlaying = false
        }
        _state.update { current ->
            current.copy(
                phase = phase,
                pinCode = if (phase == Phase.Pairing) current.pinCode else null,
                lastError = if (phase == Phase.Connected) null else current.lastError,
            )
        }
    }
}
