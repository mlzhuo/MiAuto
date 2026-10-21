package com.miauto.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Base64;
import android.util.Log;
import android.view.KeyEvent;
import android.view.Surface;

import com.andrerinas.openheadunit.aap.NativeGroupFrequencyPolicy;
import com.share.connect.WifiOwnerConfig;
import com.ucar.vehiclesdk.ICarConnectListener;
import com.ucar.vehiclesdk.ICarInitCallback;
import com.ucar.vehiclesdk.IPhoneDataListener;
import com.ucar.vehiclesdk.UCarAdapter;
import com.ucar.vehiclesdk.UCarCommon;
import com.ucar.vehiclesdk.UCarConfig;

import java.lang.reflect.Constructor;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

/**
 * MiAuto 车机端 ICCOA CarLink（小米 CarWith）SDK 生命周期管理器。
 *
 * <p>本类只管 SDK：初始化 / 广播 / 投屏 Surface / 回调转发。UI 与 BYD 输出通过
 * {@link Listener} 解耦（由 Activity 或 {@code IccoaNavigationBridge} 实现）。
 *
 * <p><b>★ init-once 状态机</b>：{@link UCarAdapter#init} 在 SDK 内部是「进程一次性」
 * （{@code sIsServiceInitialized} 守门）。重启只 {@code startAdvertise()}，绝不重复 init，
 * 否则 BLE 广播状态残留，手机扫不到、{@code onPinCode} 不再触发。
 * {@link #stop()} 断开但保留初始化标志。
 */
public class IccoaManager {

    private static final String TAG = "MiAuto/ICCOA";

    /** 事件回调。由 UI / 导航桥接实现。 */
    public interface Listener {
        void onLog(String msg);
        /** 已连接状态变化：0 断开 / 1 连接中 / 2 已连接 / 3 已建立 / 4 已连接(SDK 实测) / -1 瞬时未知 */
        void onConnectionState(int state);
        /**
         * 协商进度 0..100。
         *
         * <p>100 = 链路已建立，这是 SDK 最可靠的「已连上」信号：部分手机只把状态打成 {@code -1}
         * 这类过渡值，若只看状态码，投屏页永远打不开，而 Surface 只有投屏页才提供。
         */
        void onConnectProgress(int progress);
        void onPinCode(String pinCode, String carDeviceName);
        void onCastStarted();
        /** 手机导航信息（POC-2 / BYD 仪表与 HUD 的数据源）。 */
        void onNavigation(UCarCommon.NavigationInfo info);
        /** 手机侧曲目/播放状态变化（车机媒体会话据此判断当前是否在放音）。 */
        void onMusicInfo(UCarCommon.MusicInfo info);
    }

    private final Context appCtx;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());

    private UCarAdapter adapter = null;
    private Surface surface = null;

    /**
     * 投屏期间持有的 Wi-Fi 高性能锁。
     *
     * <p>Android 默认为省电会在 P2P 空闲窗口里降低射频占空比，视频帧因此成串到达、
     * 再成串卡住 —— 表现就是地图周期性掉帧。AA 链路在自己的服务里一直持有同类锁，
     * ICCOA 链路此前漏了这一步。锁只在会话期间持有，断开即释放。
     */
    private WifiManager.WifiLock wifiLock = null;

    /**
     * 已经交给 SDK 投屏的那块 Surface（{@code startCast()} 的入参）。
     *
     * <p>用来判断「新来的 Surface 是不是换了人」：换了就必须先 {@code pauseCast()}，
     * 否则 SDK 会继续往已废弃的 BufferQueue 写帧，视频通道会永久失效（黑屏但有声音）。
     */
    private volatile Surface boundSurface = null;

    /** {@link #tryStartCast()} 的重试预算（×500ms = 最长 10 秒）。 */
    private static final int CAST_RETRY_MAX = 20;
    private int castRetry = 0;

    // ---- 音频焦点 ----

    /** SDK 的 GAIN 焦点请求每个播放器只发一次；失败后不重试，PCM 会被永久丢弃。 */
    private static final int AUDIO_FOCUS_RETRY_MAX = 20;
    private static final long AUDIO_FOCUS_RETRY_DELAY_MS = 1500L;
    private int audioFocusRetry = 0;

    // 不用 UCarCommon.AudioAttributes 的常量：stubs 里值全是 0，防止编译期被当成常量内联。
    private static final int FOCUS_PRE_REQUEST = 0;
    private static final int FOCUS_GAIN = 1;
    private static final int FOCUS_LOSS = -1;
    private static final int FOCUS_REQUEST_FAILED = -4;
    private static final int FOCUS_ABANDONED = -5;

    /**
     * 焦点申请失败后的再授权。
     *
     * <p>{@link UCarAdapter#allowGainAudioFocus()} 只置一个「下一帧 PCM 可以再申请」的一次性
     * 开关，真正的申请发生在 SDK 下一次写音频时，所以必须延时到失败流程退出后再置。
     */
    private final Runnable regainAudioFocusRunnable = () -> {
        if (!IccoaManager.this.sdkReady || IccoaManager.this.adapter == null) return;
        log("↻ 媒体音频焦点未拿到，重新授权 SDK 申请（" + audioFocusRetry + "/" + AUDIO_FOCUS_RETRY_MAX + "）");
        try {
            IccoaManager.this.adapter.allowGainAudioFocus();
        } catch (Throwable t) {
            log("allowGainAudioFocus 异常: " + t);
        }
    };

    /**
     * 音乐焦点看门狗。
     *
     * <p>SDK 只在创建播放器时置一次全局的「下一帧可申请焦点」开关，多个 AudioType 交替建
     * 播放器/写帧时这个开关可能被别的类型消费掉，音乐就会一次焦点申请都不发，PCM 被静默
     * 丢弃 —— 表现即画面和进度正常、没有声音。这里在音乐属性被请求后盯一段时间：始终等不到
     * 任何 STREAM_CAST_MUSIC 焦点回调，就再授权一次，让 SDK 在下一帧补发申请。
     */
    private static final long MUSIC_FOCUS_WATCHDOG_DELAY_MS = 3000L;
    private static final int MUSIC_FOCUS_WATCHDOG_MAX = 3;
    private int musicFocusWatchdogCount = 0;
    private volatile boolean musicFocusSeen = false;

    private final Runnable musicFocusWatchdogRunnable = () -> {
        if (!IccoaManager.this.sdkReady || IccoaManager.this.adapter == null || musicFocusSeen) return;
        if (musicFocusWatchdogCount >= MUSIC_FOCUS_WATCHDOG_MAX) {
            log("✗ 音乐焦点看门狗：连续 " + MUSIC_FOCUS_WATCHDOG_MAX + " 次重授权后仍无焦点回调");
            return;
        }
        musicFocusWatchdogCount++;
        log("↻ 音乐播放器已建但无焦点回调，重新授权 SDK 申请（"
                + musicFocusWatchdogCount + "/" + MUSIC_FOCUS_WATCHDOG_MAX + "）");
        try {
            IccoaManager.this.adapter.allowGainAudioFocus();
        } catch (Throwable t) {
            log("allowGainAudioFocus 异常: " + t);
        }
        scheduleMusicFocusWatchdog();
    };

    private void scheduleMusicFocusWatchdog() {
        main.removeCallbacks(musicFocusWatchdogRunnable);
        main.postDelayed(musicFocusWatchdogRunnable, MUSIC_FOCUS_WATCHDOG_DELAY_MS);
    }

    private void armMusicFocusWatchdog() {
        if (musicFocusSeen) return;
        musicFocusWatchdogCount = 0;
        scheduleMusicFocusWatchdog();
    }

    private void cancelMusicFocusWatchdog() {
        main.removeCallbacks(musicFocusWatchdogRunnable);
        musicFocusWatchdogCount = 0;
    }

    private void resetMusicFocusTracking() {
        cancelMusicFocusWatchdog();
        musicFocusSeen = false;
    }

    /**
     * 按 AudioType 构造独立焦点属性。
     *
     * <p>SDK 用 {@code focusGain_usage_contentType} 作焦点状态的 key；监听器返回 null 时所有
     * 类型都拿到同一组默认媒体属性，于是共用一个 AudioFocusState：通知音先拿到焦点后，随后
     * 创建的音乐播放器只蹭这个状态、不再自己申请，通知一结束音乐就被静默丢帧。分开 key 后
     * 各类型独立持有/释放焦点。
     *
     * <p>播放属性一律保持默认媒体属性（声音路由不变），只让焦点属性和焦点类型按语义区分。
     */
    private UCarCommon.AudioAttributes buildAudioAttributes(UCarCommon.AudioType type) {
        if (type == null) return null;
        int focusUsage;
        int focusContentType;
        int focusGain;
        int streamType;
        if (type == UCarCommon.AudioType.STREAM_CAST_MUSIC) {
            focusUsage = AudioAttributes.USAGE_MEDIA;
            focusContentType = AudioAttributes.CONTENT_TYPE_MUSIC;
            focusGain = AudioManager.AUDIOFOCUS_GAIN;
            streamType = AudioManager.STREAM_MUSIC;
        } else if (type == UCarCommon.AudioType.STREAM_NOTIFICATION) {
            focusUsage = AudioAttributes.USAGE_NOTIFICATION;
            focusContentType = AudioAttributes.CONTENT_TYPE_SONIFICATION;
            focusGain = AudioManager.AUDIOFOCUS_GAIN_TRANSIENT;
            streamType = AudioManager.STREAM_NOTIFICATION;
        } else if (type == UCarCommon.AudioType.STREAM_SYSTEM) {
            focusUsage = AudioAttributes.USAGE_ASSISTANCE_SONIFICATION;
            focusContentType = AudioAttributes.CONTENT_TYPE_SONIFICATION;
            focusGain = AudioManager.AUDIOFOCUS_GAIN_TRANSIENT;
            streamType = AudioManager.STREAM_SYSTEM;
        } else if (type == UCarCommon.AudioType.STREAM_TTS) {
            focusUsage = AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE;
            focusContentType = AudioAttributes.CONTENT_TYPE_SPEECH;
            focusGain = AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK;
            streamType = AudioManager.STREAM_MUSIC;
        } else if (type == UCarCommon.AudioType.STREAM_AI_ASSISTANT) {
            focusUsage = AudioAttributes.USAGE_ASSISTANT;
            focusContentType = AudioAttributes.CONTENT_TYPE_SPEECH;
            focusGain = AudioManager.AUDIOFOCUS_GAIN_TRANSIENT;
            streamType = AudioManager.STREAM_MUSIC;
        } else if (type == UCarCommon.AudioType.STREAM_RING) {
            focusUsage = AudioAttributes.USAGE_NOTIFICATION_RINGTONE;
            focusContentType = AudioAttributes.CONTENT_TYPE_SONIFICATION;
            focusGain = AudioManager.AUDIOFOCUS_GAIN;
            streamType = AudioManager.STREAM_RING;
        } else if (type == UCarCommon.AudioType.STREAM_IP_CALL
                || type == UCarCommon.AudioType.STREAM_MODEM_CALL) {
            focusUsage = AudioAttributes.USAGE_VOICE_COMMUNICATION;
            focusContentType = AudioAttributes.CONTENT_TYPE_SPEECH;
            focusGain = AudioManager.AUDIOFOCUS_GAIN;
            streamType = AudioManager.STREAM_VOICE_CALL;
        } else {
            return null;
        }
        AudioAttributes play = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build();
        AudioAttributes focus = new AudioAttributes.Builder()
                .setUsage(focusUsage)
                .setContentType(focusContentType)
                .build();
        UCarCommon.AudioAttributes attrs = newSdkAudioAttributes(play, focus, focusGain, streamType);
        if (attrs != null) {
            log("音频属性 " + audioTypeName(type) + " → "
                    + audioAttributesKey(focusGain, focusUsage, focusContentType)
                    + "（stream=" + streamType + "）");
        }
        return attrs;
    }

    private static Constructor<?> sdkAudioAttributesCtor;
    private static boolean sdkAudioAttributesCtorResolved;

    /**
     * 真实 SDK 类有 {@code (AudioAttributes, AudioAttributes, int, int)} 构造器，但 compileOnly 的
     * stub 里只有一个无参构造器，编译期不可见，因此走反射；失败则回退 null（SDK 默认属性）。
     */
    private UCarCommon.AudioAttributes newSdkAudioAttributes(
            AudioAttributes play, AudioAttributes focus, int focusGain, int streamType) {
        try {
            if (!sdkAudioAttributesCtorResolved) {
                sdkAudioAttributesCtorResolved = true;
                sdkAudioAttributesCtor = Class
                        .forName("com.ucar.vehiclesdk.UCarCommon$AudioAttributes")
                        .getConstructor(AudioAttributes.class, AudioAttributes.class,
                                int.class, int.class);
            }
            if (sdkAudioAttributesCtor == null) return null;
            return (UCarCommon.AudioAttributes) sdkAudioAttributesCtor
                    .newInstance(play, focus, focusGain, streamType);
        } catch (Throwable t) {
            log("! 构造 AudioAttributes 失败，回退默认媒体属性: " + t);
            return null;
        }
    }

    private static String audioAttributesKey(int focusGain, int usage, int contentType) {
        return focusGain + "_" + usage + "_" + contentType;
    }

    // ---- 状态机 ----
    private volatile boolean sdkInitialized = false;
    private volatile boolean sdkReady = false;
    private volatile boolean castStarted = false;
    private volatile String lastPinCode = null;

    /** 会话结束后重新广播的延时：留出 SDK 内部收尾时间，避免与断连流程竞争。 */
    private static final long RE_ADVERTISE_DELAY_MS = 1200L;

    /**
     * 配对码自动刷新周期。
     *
     * <p>车机一直在广播并不足以把手机叫回来：BLE 广播帧内容不变时，手机端 CarWith 不会重新
     * 弹出配对界面。用户一旦关掉那个弹窗，之后就必须手动去 CarWith 里找车机才能重连。
     * 荣耀车联 / HiCar 的车机端做法是周期性重播，本常量即那个周期。
     */
    private static final long AUTO_REFRESH_INTERVAL_MS = 30_000L;

    /** 会话是否正在进行（手机连接中或已连接）。由连接回调维护，看门狗据此避让。 */
    private volatile boolean sessionActive = false;

    /** 最近一次下发配对码的时刻；用于给用户留出确认时间。 */
    private volatile long lastPinAtMs = 0L;

    /** 最近一次协商有进展的时刻。进度还在动就不重播，僵住超过保护期才救。 */
    private volatile long lastProgressAtMs = 0L;

    /**
     * 车机是否向手机宣称自己支持 SoftAP。
     *
     * <p>侧载 APK 拿不到 TETHER_PRIVILEGED / OVERRIDE_WIFI_CONFIG / NETWORK_SETTINGS（三者都是
     * signature|privileged），SDK 的 WifiApService 起不来；而配置里只要写了「支持 SoftAP」，
     * 手机就优先选 AP 通道，协商会停在 10% 不再前进。只宣称 P2P 才能走通。
     */
    private static final boolean SUPPORT_SOFT_AP = false;

    /** 配对码下发后的保护期：期间看门狗不重播，避免把用户正在确认的配对顶掉。 */
    private static final long PIN_GRACE_MS = 45_000L;

    /** Android 6 起非系统应用读到的蓝牙 MAC 恒为该占位值，拿它当车机身份会破坏重连。 */
    private static final String PLACEHOLDER_BT_MAC = "02:00:00:00:00:00";

    /**
     * 会话结束后的重新广播。
     *
     * <p>车机端必须一直可被发现：手机断开后若不再广播，用户就得手动重启 App 才能重连。
     * 重新广播时会清掉旧配对码，手机因此走一次全新配对并下发【新的】配对码 —— 这就是
     * 「断开后自动刷新链接码」。
     */
    private final Runnable reAdvertiseRunnable = () -> {
        if (sdkReady && adapter != null) {
            log("会话结束 → 重新广播（等待手机再次连接，届时下发新的配对码）");
            reAdvertise();
        }
    };

    private void scheduleReAdvertise() {
        main.removeCallbacks(reAdvertiseRunnable);
        main.postDelayed(reAdvertiseRunnable, RE_ADVERTISE_DELAY_MS);
    }

    /**
     * 配对码看门狗：每隔 {@link #AUTO_REFRESH_INTERVAL_MS} 检查一次，仍未连上就重播一次。
     *
     * <p>这是「刷新出来的新码没人来连，之后就再也不刷新」的解法 —— 那种情况下手机端弹窗
     * 已经关掉，车机只能靠重播把它重新叫出来，否则用户点开 CarWith 也发现不了车机。
     *
     * <p>避让规则：手机正在连接或已连上（{@link #sessionActive}）、或画面已经投出去
     * （{@code castStarted}）时一律跳过，绝不打断进行中的会话。新码下发时计时会重置，
     * 保证用户拿到完整的 30 秒去输入。
     */
    private final Runnable autoRefreshRunnable = () -> {
        if (!sdkReady || adapter == null) return;
        if (sessionActive || castStarted) {
            log("自动刷新：会话进行中，跳过本轮");
            scheduleAutoRefresh();
            return;
        }
        long anchor = Math.max(lastPinAtMs, lastProgressAtMs);
        if (anchor != 0 && SystemClock.elapsedRealtime() - anchor < PIN_GRACE_MS) {
            log("自动刷新：配对仍在进行（距最近进展 "
                    + (SystemClock.elapsedRealtime() - anchor) / 1000 + " 秒），本轮让位");
            scheduleAutoRefresh();
            return;
        }
        log("★ 自动刷新：超过 " + (AUTO_REFRESH_INTERVAL_MS / 1000) + " 秒未连接 → 重播以重新触发手机弹窗");
        reAdvertise();
        scheduleAutoRefresh();
    };

    private void scheduleAutoRefresh() {
        main.removeCallbacks(autoRefreshRunnable);
        main.postDelayed(autoRefreshRunnable, AUTO_REFRESH_INTERVAL_MS);
    }

    private void cancelAutoRefresh() {
        main.removeCallbacks(autoRefreshRunnable);
    }

    // ---- 屏幕尺寸 ----
    private int screenW = 1920, screenH = 720, videoW = 1920, videoH = 720;

    public IccoaManager(Context ctx, Listener listener) {
        this.appCtx = ctx.getApplicationContext();
        this.listener = listener;
    }

    public void setScreenSize(int w, int h) {
        screenW = w; screenH = h; videoW = w; videoH = h;
    }

    /** 投屏显示区宽度，与 {@code setVideoDisplayWidth()} 声明的一致。 */
    public int getDisplayWidth() { return videoW; }

    /** 投屏显示区高度，与 {@code setVideoDisplayHeight()} 声明的一致。 */
    public int getDisplayHeight() { return videoH; }

    /**
     * 车机侧触摸回传给手机（SDK 的 UIBC 通道）。
     *
     * <p>{@code action} 用 {@link android.view.MotionEvent#getAction()} 的原始值，不要
     * {@code actionMasked}；{@code x[]/y[]} 是 display 空间坐标，SDK 内部再按
     * {@code x / displayWidth * sourceWidth} 换算成手机屏幕坐标。
     */
    public boolean sendTouchEvent(int action, int count, int[] trackId, int[] x, int[] y) {
        if (adapter == null) return false;
        try {
            return adapter.sendTouchEvent(action, count, trackId, x, y);
        } catch (Throwable t) {
            log("sendTouchEvent 异常: " + t);
            return false;
        }
    }

    /**
     * 方向盘媒体键透传给手机。
     *
     * <p>SDK 只搬运手机的音频/视频，按键必须由车机侧显式经控制通道回传。不传的话按键会被
     * 车机自带的音乐/收音机吃掉：它们抢到音频焦点后 SDK 会给手机发暂停，下一代音乐键再按
     * 就是「切歌变暂停」。返回 false 表示该键不归我们管（此时调用方应交还系统处理）。
     */
    public boolean sendMediaKey(int keyCode, boolean down) {
        UCarCommon.KeyCodeType code;
        switch (keyCode) {
            case KeyEvent.KEYCODE_MEDIA_NEXT:
                code = UCarCommon.KeyCodeType.KEY_CODE_MEDIA_NEXT;
                break;
            case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
                code = UCarCommon.KeyCodeType.KEY_CODE_MEDIA_PREVIOUS;
                break;
            case KeyEvent.KEYCODE_MEDIA_PLAY:
                code = UCarCommon.KeyCodeType.KEY_CODE_MEDIA_PLAY;
                break;
            case KeyEvent.KEYCODE_MEDIA_PAUSE:
            case KeyEvent.KEYCODE_MEDIA_STOP:
                code = UCarCommon.KeyCodeType.KEY_CODE_MEDIA_PAUSE;
                break;
            default:
                // 播放/暂停切换键由上层按当前播放状态解析成显式 PLAY/PAUSE 再进来。
                return false;
        }
        if (adapter == null) return false;
        UCarCommon.KeyEventActionType action = down
                ? UCarCommon.KeyEventActionType.KEY_EVENT_ACTION_DOWN
                : UCarCommon.KeyEventActionType.KEY_EVENT_ACTION_UP;
        try {
            boolean sent = adapter.sendKeyEvent(action, code, 0);
            if (!sent) log("✗ 媒体键下发失败（控制通道未就绪）: " + code);
            return sent;
        } catch (Throwable t) {
            log("sendKeyEvent 异常: " + t);
            return false;
        }
    }

    public String getLastPinCode() { return lastPinCode; }

    /** Surface 就绪 / 变化（Activity 的 surfaceChanged 回调里调用）。 */
    public void setSurface(Surface s) {
        boolean replaced = castStarted && s != boundSurface;
        this.surface = s;
        castRetry = 0;          // 新 Surface 到位，重试预算重置
        if (replaced) {
            // Surface 被换掉了：SDK 的解码器绑在具体那块 BufferQueue 上，旧 Surface 销毁后
            // 继续写帧会报 queueBuffer -19（isRecoverable=false），视频永久黑屏但声音还在。
            pauseCastInternal("Surface 被替换");
        }
        tryStartCast();
    }

    /** Activity 的 surfaceDestroyed：让 SDK 停手，别往已废弃的 BufferQueue 再写一帧。 */
    public void onSurfaceDestroyed() {
        surface = null;
        if (castStarted) {
            pauseCastInternal("Surface 已销毁");
        }
    }

    /** 只停投屏、保留会话，并清掉「已绑定 Surface」的记账。 */
    private void pauseCastInternal(String why) {
        log(why + " → pauseCast()");
        if (adapter != null) {
            try { adapter.pauseCast(); } catch (Throwable t) { log("pauseCast 异常: " + t); }
        }
        castStarted = false;
        boundSurface = null;
    }

    private synchronized void acquireWifiLock() {
        try {
            if (wifiLock == null) {
                WifiManager wm = (WifiManager) appCtx.getSystemService(Context.WIFI_SERVICE);
                if (wm == null) return;
                // Android 10 起 HIGH_PERF 已弃用且可能被系统忽略，低延迟模式才会真正避免
                // 射频省电切槽；老系统没有该常量，仍用 HIGH_PERF。
                int mode = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                        ? WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                        : WifiManager.WIFI_MODE_FULL_HIGH_PERF;
                wifiLock = wm.createWifiLock(mode, "MiAuto:ICCOA");
            }
            if (!wifiLock.isHeld()) {
                wifiLock.acquire();
                log("WifiLock acquired (" + (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ? "LOW_LATENCY" : "HIGH_PERF") + ")");
            }
        } catch (Throwable t) {
            log("WifiLock acquire 异常: " + t);
        }
    }

    private synchronized void releaseWifiLock() {
        try {
            if (wifiLock != null && wifiLock.isHeld()) {
                wifiLock.release();
                log("WifiLock released");
            }
        } catch (Throwable t) {
            log("WifiLock release 异常: " + t);
        }
    }

    // ==================================================================
    // 主入口
    // ==================================================================

    /** 启动（或重启）投屏链路。权限由调用方（Activity）负责。 */
    public void start() {
        log("=== 启动（ICCOA CarWith）===");
        log("构建标记 2026-10-21b：P2P-only / progress100 / touch / surface-rebind / audio-focus-per-type / media-key-forward / focus-loss-rearm / wifi-channel-align / wifi-lock / fps58");
        if (sdkInitialized && adapter != null) {
            log("重启：SDK 已初始化，仅重新广播");
            reAdvertise();
            return;
        }
        UCarConfig config;
        try {
            config = buildConfig();
        } catch (Throwable t) {
            log("✗ 配置构造失败: " + t);
            return;
        }
        try {
            adapter = UCarAdapter.getInstance();
            adapter.registerCarConnectListener(connectListener);
            adapter.registerPhoneDataListener(phoneDataListener);
        } catch (Throwable t) {
            log("✗ getInstance/register 异常: " + t);
            return;
        }
        initSdk(config);
    }

    private void initSdk(UCarConfig config) {
        boolean posted = adapter.init(appCtx, config, new ICarInitCallback() {
            @Override
            public void onInitSuccess() {
                log("★ onInitSuccess()");
                sdkReady = true;
                sdkInitialized = true;   // ★ init-once 守门
                main.post(() -> safe("doAdvertise", IccoaManager.this::doAdvertise));
                main.post(IccoaManager.this::scheduleAutoRefresh);   // ★ 启动配对码看门狗
            }

            @Override
            public void onInitFailed(int errCode) {
                log("✗ onInitFailed(" + errCode + ")"
                        + (errCode == 10001 ? " ← HandlerThread 启动失败" : ""));
            }
        });
        if (!posted) log("✗ init() 返回 false —— 任务未提交");
    }

    /**
     * 重播：先停旧广播，再启新广播。
     *
     * <p><b>必须先 {@code stopAdvertise()}</b> —— 对一条已经在广播中的链路重复调用
     * {@code startAdvertise()} 会被 SDK 直接忽略，广播帧内容不变，手机端因此不会重新发现
     * 车机、也就不会重新弹窗。只有「停-启」才是一次真正的重播。
     */
    private void reAdvertise() {
        if (adapter == null) return;
        try {
            adapter.registerCarConnectListener(connectListener);
            adapter.registerPhoneDataListener(phoneDataListener);
        } catch (Throwable t) {
            log("reAdvertise 重新注册异常: " + t);
        }
        try { adapter.stopAdvertise(); } catch (Throwable t) { log("stopAdvertise(重播前) 异常: " + t); }
        sdkReady = true;
        castStarted = false;
        boundSurface = null;
        castRetry = 0;
        lastPinCode = null;
        lastPinAtMs = 0L;
        lastProgressAtMs = 0L;
        doAdvertise();
    }

    /**
     * 用户主动「刷新配对码」：立刻重播一次，不必等看门狗的 30 秒周期。
     *
     * <p>与自动刷新走同一条路径，重播后看门狗重新计时。
     */
    public void refreshPairing() {
        log("=== 手动刷新配对码 ===");
        if (!sdkReady || adapter == null) {
            log("刷新失败：SDK 尚未就绪");
            return;
        }
        reAdvertise();
        scheduleAutoRefresh();
    }

    public void stop() {
        log("=== 停止 ===");
        if (adapter != null) {
            try { adapter.pauseCast(); } catch (Throwable t) { log("pauseCast 异常: " + t); }
            try { adapter.disconnect(); } catch (Throwable t) { log("disconnect 异常: " + t); }
            try { adapter.stopAdvertise(); } catch (Throwable t) { log("stopAdvertise 异常: " + t); }
            try {
                adapter.unregisterCarConnectListener(connectListener);
                adapter.unregisterPhoneDataListener(phoneDataListener);
            } catch (Throwable ignored) { }
        }
        main.removeCallbacks(reAdvertiseRunnable);
        cancelAutoRefresh();
        resetAudioFocusRetry();
        resetMusicFocusTracking();
        releaseWifiLock();
        sessionActive = false;
        castStarted = false;
        boundSurface = null;
        sdkReady = false;
        lastPinCode = null;
        lastPinAtMs = 0L;
        lastProgressAtMs = 0L;
        // 保留 sdkInitialized —— init() 进程一次性，重启只走 reAdvertise()。
    }

    /**
     * 仅断开当前会话（用户主动点「断开」），保持广播 —— 手机可立即重新连接。
     *
     * <p>与 {@link #stop()} 的区别：{@code stop()} 会停掉广播（相当于彻底关机位），
     * 本方法只拆会话，车机仍可被发现。
     */
    public void disconnectSession() {
        log("=== 断开当前会话（保持广播）===");
        if (adapter != null) {
            try { adapter.pauseCast(); } catch (Throwable t) { log("pauseCast 异常: " + t); }
            try { adapter.disconnect(); } catch (Throwable t) { log("disconnect 异常: " + t); }
        }
        resetAudioFocusRetry();
        resetMusicFocusTracking();
        releaseWifiLock();
        sessionActive = false;
        castStarted = false;
        boundSurface = null;
        lastPinCode = null;
        lastPinAtMs = 0L;
        lastProgressAtMs = 0L;
        scheduleReAdvertise();
        scheduleAutoRefresh();
    }

    // ==================================================================
    // 广播 / 投屏
    // ==================================================================

    private void doAdvertise() {
        if (adapter == null) return;
        boolean ok = adapter.startAdvertise();
        log((ok ? "✓ " : "✗ ") + "startAdvertise() = " + ok);
        tryStartCast();
    }

    /**
     * 尝试起投。
     *
     * <p>重试有上限：停车待机页没有 Surface，无上限重试会变成一个永不停止的 500ms 定时器
     * （待机页常驻，等于白耗电）。Surface 稍后就绪的常见情形由 {@link #setSurface} 兜底，
     * 这里的十秒预算只用于「Surface 已设但尚未 valid」的短暂窗口。
     */
    private void tryStartCast() {
        if (!sdkReady || castStarted || adapter == null) return;
        Surface s = surface;
        if (s == null || !s.isValid()) {
            if (castRetry < CAST_RETRY_MAX) {
                castRetry++;
                main.postDelayed(this::tryStartCast, 500);
            }
            return;
        }
        boolean ok = adapter.startCast(s, videoW, videoH);
        log((ok ? "✓ " : "✗ ") + "startCast(" + videoW + "x" + videoH + ") = " + ok);
        if (ok) {
            castRetry = 0;
            castStarted = true;
            boundSurface = s;
            main.post(listener::onCastStarted);
        } else {
            // 会话可能还没建立起来：每 500ms 再试，上限 10 秒；连接建立时也会重置预算再触发一次。
            castStarted = false;
            boundSurface = null;
            if (castRetry < CAST_RETRY_MAX) {
                castRetry++;
                main.postDelayed(this::tryStartCast, 500);
            }
        }
    }

    // ==================================================================
    // SDK 回调
    // ==================================================================

    private final ICarConnectListener connectListener = new ICarConnectListener() {
        @Override
        public void onPinCode(String pinCode, String carDeviceName) {
            lastPinCode = pinCode;
            lastPinAtMs = SystemClock.elapsedRealtime();
            log("★ onPinCode pinCode=" + pinCode + " carDeviceName=" + carDeviceName);
            // 配对意味着手机即将建立 P2P 链路，从这一刻起把射频固定在性能模式。
            acquireWifiLock();
            // 新码下发 → 看门狗重新计时，保证用户拿到完整的有效期去确认，不会刚出码就被重播顶掉。
            scheduleAutoRefresh();
            main.post(() -> listener.onPinCode(pinCode, carDeviceName));
        }

        @Override
        public void onConnectStateChanged(String deviceID, int oldState, int newState) {
            log("onConnectStateChanged " + stateName(oldState) + " -> " + stateName(newState));
            if (newState == 0 && oldState != 0) {
                // 会话真正结束（不是启动时的初始 DISCONNECTED）。清掉已过期的配对码并延时重新
                // 广播：手机端 CarWith 会重新发现车机并触发一次全新配对（新的 onPinCode）。
                sessionActive = false;
                lastPinCode = null;
                lastPinAtMs = 0L;
                lastProgressAtMs = 0L;
                castStarted = false;
                boundSurface = null;
                resetAudioFocusRetry();
                resetMusicFocusTracking();
                releaseWifiLock();
                scheduleReAdvertise();
                scheduleAutoRefresh();
            } else if (newState == 2 || newState == 3 || newState == 4) {
                // 已连上：撤销任何待执行的重新广播，免得刚连上就被重启广播打断；看门狗也会因此空转。
                sessionActive = true;
                acquireWifiLock();
                lastPinAtMs = 0L;
                lastProgressAtMs = 0L;
                resetMusicFocusTracking();
                main.removeCallbacks(reAdvertiseRunnable);
                // 会话已建立：投屏页可能早就把 Surface 交过来了，重新给一次 startCast 的机会。
                castRetry = 0;
                main.post(IccoaManager.this::tryStartCast);
            } else if (newState >= 10000) {
                // SDK 的协商失败错误码（例如 10003 = Wi-Fi 通道未建立）。会话没起来，
                // 看门狗需要继续工作，30 秒后再重播一次让手机重新发起。
                sessionActive = false;
                releaseWifiLock();
                log("✗ 协商失败（错误码 " + newState + "），等待下一轮重播");
                scheduleAutoRefresh();
            } else if (newState < 0) {
                // 协商刚成功时 SDK 会先发一个 -1，随后才是正式状态；这里只记日志。
                log("协商过渡态 " + newState);
            }
            // newState == 1（CONNECTING）刻意不置 sessionActive：若协商卡死超过一个刷新周期，
            // 看门狗需要能重播把它救回来。正常协商只需数秒，30 秒的余量足够。
            final int st = newState;
            main.post(() -> listener.onConnectionState(st));
        }

        @Override
        public void onConnectingProgress(String phoneId, int progress) {
            lastProgressAtMs = SystemClock.elapsedRealtime();
            log("onConnectingProgress " + progress);
            if (progress > 0) acquireWifiLock();
            final int p = progress;
            main.post(() -> listener.onConnectProgress(p));
            if (progress >= 100) {
                // 链路已建立；若投屏页已经给了 Surface，立刻再试一次 startCast。
                castRetry = 0;
                main.post(IccoaManager.this::tryStartCast);
            }
        }

        @Override
        public void onReconfigureWifiInfo(WifiOwnerConfig config) {
            if (config == null) {
                log("onReconfigureWifiInfo(null)");
                return;
            }
            String pass = config.getPassphrase();
            log("onReconfigureWifiInfo type=" + config.getType()
                    + " ssid=" + config.getSsid()
                    + " frequency=" + config.getFrequency()
                    + " mac=" + maskMac(config.getMac())
                    + " passphrase=" + (pass == null || pass.isEmpty() ? "空" : pass.length() + " 位"));
        }

        @Override
        public void onRethinkBluetoothConnect(boolean rethink) {
            log("onRethinkBluetoothConnect=" + rethink);
        }

        @Override
        public UCarCommon.WorkMode onSelectWorkMode(String deviceID, UCarCommon.WorkMode supported) {
            UCarCommon.WorkMode chosen;
            if (supported == null) {
                chosen = UCarCommon.WorkMode.NONE;
            } else if (supported.isSupportNormalCast()) {
                // 配置里只声明了 NORMAL_CAST，选它才与声明一致。
                chosen = UCarCommon.WorkMode.NORMAL_CAST;
            } else if (supported.isSupportAppFullScreenCast()) {
                chosen = UCarCommon.WorkMode.APP_FULLSCREEN_CAST;
            } else if (supported.isSupportAppWindowCast()) {
                chosen = UCarCommon.WorkMode.APP_WINDOW_CAST;
            } else if (supported.isSupportDataTransfer()) {
                chosen = UCarCommon.WorkMode.DATA_TRANSFER;
            } else {
                chosen = UCarCommon.WorkMode.NONE;
            }
            log("onSelectWorkMode → " + chosen);
            return chosen;
        }
    };

    private final IPhoneDataListener phoneDataListener = new IPhoneDataListener() {
        @Override
        public void onNavigationInfoReceived(String phoneId, UCarCommon.NavigationInfo info) {
            listener.onNavigation(info);
        }

        @Override public void onMusicInfoReceived(String phoneId, UCarCommon.MusicInfo info) {
            listener.onMusicInfo(info);
        }
        @Override public void onCallInfoReceived(String phoneId, UCarCommon.CallInfo info) { }
        @Override public void onPhoneStateInfoReceived(String phoneId, UCarCommon.PhoneStateInfo info) { }
        @Override public void onPOIReceived(String phoneId, UCarCommon.POIAddress poi) { }

        @Override
        public void onAudioFocusChanged(UCarCommon.AudioType type, int focus) {
            log("onAudioFocusChanged " + audioTypeName(type) + " → " + audioFocusName(focus));
            if (type == UCarCommon.AudioType.STREAM_CAST_MUSIC) {
                cancelMusicFocusWatchdog();
                if (focus == FOCUS_PRE_REQUEST || focus == FOCUS_GAIN) {
                    musicFocusSeen = true;
                }
            }
            if (focus == FOCUS_GAIN) {
                resetAudioFocusRetry();
            } else if (focus == FOCUS_LOSS) {
                resetAudioFocusRetry(); // 丢焦是新的打断，之前未跑完的重试作废
                rearmAudioFocusAfterLoss();
            } else if (focus == FOCUS_REQUEST_FAILED) {
                scheduleRegainAudioFocus();
            } else if (focus == FOCUS_ABANDONED) {
                resetAudioFocusRetry();
            }
        }

        @Override
        @SuppressWarnings("rawtypes")
        public void onAppListStateChanged(String phoneId, List list, UCarCommon.AppListState state) { }

        @Override
        public void onAppStateChanged(String phoneId, int state, boolean flag,
                                      int a, int b, UCarCommon.AppState appState) { }

        @Override
        public UCarCommon.AudioAttributes getAudioAttributesByType(UCarCommon.AudioType type) {
            UCarCommon.AudioAttributes attrs = buildAudioAttributes(type);
            if (attrs == null) {
                // 回退：所有类型共用 SDK 默认媒体属性（key = 1_1_2），仅用于反射失败等兜底。
                log("音频属性请求 " + audioTypeName(type) + " → 默认媒体属性");
            } else {
                log("音频属性请求 " + audioTypeName(type) + " → 独立属性");
            }
            resetAudioFocusRetry();
            if (type == UCarCommon.AudioType.STREAM_CAST_MUSIC) {
                armMusicFocusWatchdog();
            }
            return attrs;
        }
    };

    // ==================================================================
    // 配置构造（SDK 硬校验，越界即抛）
    // ==================================================================

    private UCarConfig buildConfig() {
        byte[] carId = generateCarId();
        if (carId == null || carId.length != 6) {
            carId = new byte[6];
            new Random().nextBytes(carId);
            log("! carId 长度异常，强制随机 6 字节");
        }
        android.util.DisplayMetrics dm = appCtx.getResources().getDisplayMetrics();
        int densityDpi = dm.densityDpi;
        int dpi = clamp(320, 120, 640);
        int realDpi = clamp(densityDpi, 120, 640);
        // 车机面板实测 57.999996 Hz；请求 58 帧可避免 60 vs 58 的周期性丢帧。
        int fps = clamp(58, 15, 60);
        int sw = Math.max(1, screenW), sh = Math.max(1, screenH);
        int vw = Math.max(1, videoW), vh = Math.max(1, videoH);
        int stationFreq = stationWifiFrequency();
        int[] allowed5g = NativeGroupFrequencyPolicy.INSTANCE.allowed5gChannels(stationFreq);

        UCarCommon.WorkMode workMode = new UCarCommon.WorkMode.Builder()
                .setSupportNormalCast(true)
                .setSupportAppFullScreenCast(false)
                .setSupportAppWindowCast(false)
                .build();

        UCarConfig config = new UCarConfig.Builder()
                .setCarBrMac(carId)                  // 必须恰好 6 字节
                .setDpi(dpi)                         // 120..640
                .setRealDpi(realDpi)                 // 120..640
                .setFps(fps)                         // 15..60
                .setAllowed5gChannels(allowed5g)     // 非空；[0] 会作为 default5GChannel 下发给手机
                .setScreenWidth(sw)
                .setScreenHeight(sh)
                .setVideoDisplayWidth(vw)
                .setVideoDisplayHeight(vh)
                .setSupportP2P(true)
                .setSupportSoftAP(SUPPORT_SOFT_AP)
                .setSupportCamera(false)
                .setSupportHevc(false)
                .setSupportMic(true)
                .setUseCustomAudioRecord(false)
                .setSupportStereoRecord(false)
                .setSupportLowLatencyDecodingMode(false)
                .setSupportPOINav(false)
                .setCcdFilePath("ccd.data")
                .setSupportedWorkModes(workMode)
                .build();
        log("UCarConfig: p2p=" + config.isSupportP2P()
                + " softAp=" + config.isSupportSoftAP()
                + " reconfigureWifi=" + config.isReconfigureWifi()
                + " fps=" + config.getFps()
                + " allowed5gChannels=" + Arrays.toString(allowed5g)
                + " stationFreq=" + (stationFreq > 0 ? stationFreq + "MHz" : "unknown")
                + " carBrMac=" + bytesToMac(carId));
        return config;
    }

    /**
     * 车机当前 Wi-Fi（STA）的连接频率；未连接、无权限或读不到时返回 0。
     *
     * <p>P2P 组与 STA 用不同信道时，Wi-Fi 芯片只能分时切换，视频流会出现周期性停顿。
     */
    @android.annotation.SuppressLint("MissingPermission")
    private int stationWifiFrequency() {
        try {
            android.net.wifi.WifiManager wm = (android.net.wifi.WifiManager)
                    appCtx.getSystemService(Context.WIFI_SERVICE);
            android.net.wifi.WifiInfo info = wm == null ? null : wm.getConnectionInfo();
            if (android.os.Build.VERSION.SDK_INT >= 21
                    && info != null
                    && info.getSupplicantState() == android.net.wifi.SupplicantState.COMPLETED) {
                return info.getFrequency();
            }
        } catch (Throwable t) {
            log("读取车机 Wi-Fi 信道失败: " + t);
        }
        return 0;
    }

    private static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    private byte[] generateCarId() {
        SharedPreferences prefs = appCtx.getSharedPreferences("car_id_prefs", Context.MODE_PRIVATE);
        byte[] override = parseMac(prefs.getString("car_id_override_mac", ""));
        if (override != null) {
            log("carId 使用 car_id_override_mac");
            return override;
        }
        String saved = prefs.getString("car_id_key", "");
        if (saved != null && !saved.isEmpty()) {
            try {
                byte[] cached = Base64.decode(saved, Base64.NO_WRAP);
                if (cached != null && cached.length == 6 && !isPlaceholder(cached)) return cached;
                log("carId 缓存无效（长度≠6 或占位 MAC），重新生成");
            } catch (Throwable t) {
                log("carId 缓存解码失败: " + t);
            }
        }
        byte[] raw = getBluetoothMac();
        if (raw == null || raw.length != 6 || isPlaceholder(raw)) {
            raw = new byte[6];
            new Random().nextBytes(raw);
            log("carId 改用随机值（蓝牙 MAC 不可用）");
        }
        prefs.edit().putString("car_id_key", Base64.encodeToString(raw, Base64.NO_WRAP)).apply();
        return raw;
    }

    @android.annotation.SuppressLint("MissingPermission")
    private byte[] getBluetoothMac() {
        byte[] macBytes = new byte[6];
        try {
            android.bluetooth.BluetoothManager bm =
                    (android.bluetooth.BluetoothManager) appCtx.getSystemService(Context.BLUETOOTH_SERVICE);
            android.bluetooth.BluetoothAdapter a = (bm == null) ? null : bm.getAdapter();
            String mac = (a == null) ? null : a.getAddress();
            if (mac != null) {
                byte[] parsed = parseMac(mac);
                if (parsed != null) return parsed;
                log("蓝牙 MAC 不可用或为占位值: " + mac);
            }
        } catch (Throwable t) {
            log("读蓝牙 MAC 被拒（缺 BLUETOOTH_CONNECT？）: " + t);
        }
        new Random().nextBytes(macBytes);
        return macBytes;
    }

    /** "AA:BB:CC:DD:EE:FF" → 6 字节；格式不对或为占位值返回 null。 */
    private static byte[] parseMac(String mac) {
        if (mac == null) return null;
        String[] seg = mac.trim().split(":");
        if (seg.length != 6) return null;
        byte[] out = new byte[6];
        try {
            for (int i = 0; i < 6; i++) out[i] = (byte) Integer.parseInt(seg[i], 16);
        } catch (Throwable t) {
            return null;
        }
        return isPlaceholder(out) ? null : out;
    }

    private static boolean isPlaceholder(byte[] mac) {
        return mac != null && PLACEHOLDER_BT_MAC.equalsIgnoreCase(bytesToMac(mac));
    }

    private static String bytesToMac(byte[] mac) {
        if (mac == null || mac.length != 6) return "?";
        StringBuilder sb = new StringBuilder(17);
        for (int i = 0; i < 6; i++) {
            if (i > 0) sb.append(':');
            sb.append(String.format(java.util.Locale.US, "%02X", mac[i]));
        }
        return sb.toString();
    }

    private static String maskMac(String mac) {
        if (mac == null || mac.length() < 5) return String.valueOf(mac);
        return "**:**:**:**:" + mac.substring(mac.length() - 5);
    }

    private void scheduleRegainAudioFocus() {
        if (!sdkReady || adapter == null) return;
        if (audioFocusRetry >= AUDIO_FOCUS_RETRY_MAX) {
            if (audioFocusRetry == AUDIO_FOCUS_RETRY_MAX) {
                audioFocusRetry++;
                log("✗ 媒体音频焦点连续失败 " + AUDIO_FOCUS_RETRY_MAX + " 次，停止重试；"
                        + "手机下次开始播放/播报时 SDK 还会再申请一次");
            }
            return;
        }
        audioFocusRetry++;
        main.removeCallbacks(regainAudioFocusRunnable);
        main.postDelayed(regainAudioFocusRunnable, AUDIO_FOCUS_RETRY_DELAY_MS);
    }

    private void resetAudioFocusRetry() {
        audioFocusRetry = 0;
        main.removeCallbacks(regainAudioFocusRunnable);
    }

    /**
     * 永久丢焦后重新武装 SDK 的焦点申请。
     *
     * <p>SDK 的 GAIN 类型焦点每个播放器只申请一次（一次性开关在首帧就消耗掉）；永久丢焦后它
     * 只把 {@code mHasFocus} 置 false 并给手机发暂停，之后再也不会申请。手机重新播放时每一帧
     * PCM 都被「no focus, drop data」静默丢弃，只有断开重连、重建播放器才恢复。这里只把开关
     * 重新武装，真正的申请仍由 SDK 在下一帧音频到达时发出——手机没在播放就不会抢别的音源。
     */
    private void rearmAudioFocusAfterLoss() {
        main.post(() -> {
            if (!sdkReady || adapter == null) return;
            log("↻ 音频焦点已丢失，重新武装 SDK 的焦点申请（下次收流时生效）");
            try {
                adapter.allowGainAudioFocus();
            } catch (Throwable t) {
                log("allowGainAudioFocus 异常: " + t);
            }
        });
    }

    private static String audioTypeName(UCarCommon.AudioType type) {
        return type == null ? "null" : type.toString();
    }

    private static String audioFocusName(int focus) {
        if (focus == 0) return "PRE_REQUEST(0)";
        if (focus == 1) return "GAIN(1)";
        if (focus == -1) return "LOSS(-1)";
        if (focus == -2) return "LOSS_TRANSIENT(-2)";
        if (focus == -3) return "CAN_DUCK(-3)";
        if (focus == -4) return "REQUEST_FAILED(-4)";
        if (focus == -5) return "ABANDONED(-5)";
        return "FOCUS_" + focus;
    }

    private void log(String msg) {
        Log.i(TAG, msg);
        try { listener.onLog(msg); } catch (Throwable ignored) { }
    }

    private void safe(String where, Runnable r) {
        try { r.run(); } catch (Throwable t) { log("!!! " + where + " 异常: " + t); }
    }

    private static String stateName(int s) {
        switch (s) {
            case 0: return "DISCONNECTED";
            case 1: return "CONNECTING";
            case 2: return "CONNECTED";
            case 3: return "ESTABLISHED";
            default: return "STATE_" + s;
        }
    }
}
