package com.anonymous.ST;

import android.accessibilityservice.AccessibilityService;
import android.app.ActivityManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.WallpaperManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.content.res.AssetFileDescriptor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Telephony;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ScrollMonitorService extends AccessibilityService {

    // =========================================================================
    // ⚙️ TWEAK YOUR TIMERS HERE
    // =========================================================================
    private final int PUNISHMENT_THRESHOLD = 10;          // 10s test | 300s (5m) real
    private final int REPEAT_PUNISHMENT_INTERVAL = 10;     // 5s test  | 60s (1m) real
    private final long LOCK_DURATION_MS = 30000;          // 30s test | 300000ms (5m) real
    private final long SOUND_DURATION_MS = 10000;         // 15s max speaker blast
    // =========================================================================

    private static final int PUNISHMENT_OVERLAY = 0;
    private static final int PUNISHMENT_WALLPAPER = 1;
    private static final int PUNISHMENT_SPEAKER = 2;

    private final List<Integer> punishmentQueue = new ArrayList<>();
    private final List<String> wallpaperQueue = new ArrayList<>();
    private final List<String> soundQueue = new ArrayList<>();

    private int lastPunishment = -1;
    private String lastWallpaper = "";
    private String lastSound = "";

    private final Random random = new Random();
    private final ExecutorService backgroundExecutor = Executors.newSingleThreadExecutor();

    private String currentPackage = "";
    private boolean isWatchingShorts = false;
    private int screenTimeSeconds = 0;
    private int punishmentsToday = 0;

    private final Handler timerHandler = new Handler(Looper.getMainLooper());
    private WindowManager windowManager;
    private View overlayView;
    private TextView countdownText;
    private boolean isOverlayActive = false;
    private long lockEndTimeMs = 0;

    private long emergencyLaunchGraceUntilMs = 0;
    private boolean hasEnteredEmergencyApp = false;
    private long closingAppGraceUntilMs = 0;

    private MediaPlayer activeMediaPlayer;
    private boolean isPlayingPunishmentSound = false;

    private SharedPreferences prefs;

    // 1-second main tracker
    private final Runnable timeTrackerRunnable = new Runnable() {
        @Override
        public void run() {
            try {
                if (isOverlayActive) {
                    long remainingMs = lockEndTimeMs - System.currentTimeMillis();
                    if (remainingMs <= 0) {
                        removeLockOverlay();
                    } else {
                        updateCountdownUI(remainingMs);
                        enforceLockScreen();
                    }
                } else {
                    refreshYouTubeShortsState();
                    if (isTargetActive()) {
                        screenTimeSeconds++;
                        saveStats();
                        checkPunishment();
                    }
                }
            } catch (Exception ignored) {}
            timerHandler.postDelayed(this, 1000);
        }
    };

    // 100ms aggressive volume lock so user cannot drag sliders or lower volume during Speaker Sabotage
    private final Runnable volumeLockRunnable = new Runnable() {
        @Override
        public void run() {
            if (isPlayingPunishmentSound) {
                forceMaxVolume();
                timerHandler.postDelayed(this, 100);
            }
        }
    };

    // 150ms fast watcher for instant re-lock when leaving Phone/Messages
    private final Runnable fastEmergencyWatcher = new Runnable() {
        @Override
        public void run() {
            try {
                if (isOverlayActive && overlayView != null && overlayView.getVisibility() != View.VISIBLE) {
                    refreshActiveForegroundPackage();
                    enforceLockScreen();
                    timerHandler.postDelayed(this, 150);
                }
            } catch (Exception ignored) {}
        }
    };

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startPersistentForegroundNotification();
        return START_STICKY;
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        try {
            windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
            prefs = getSharedPreferences("DoomScrollPrefs", MODE_PRIVATE);

            screenTimeSeconds = prefs.getInt("screenTime", 0);
            punishmentsToday = prefs.getInt("punishments", 0);
            loadQueuesState();

            startPersistentForegroundNotification();

            timerHandler.removeCallbacks(timeTrackerRunnable);
            timerHandler.post(timeTrackerRunnable);
        } catch (Exception ignored) {}
    }

    private void startPersistentForegroundNotification() {
        try {
            String channelId = "scroll_trap_monitor_channel";
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm != null) {
                NotificationChannel channel = new NotificationChannel(
                        channelId,
                        "ScrollTrap Active Monitor",
                        NotificationManager.IMPORTANCE_LOW);
                channel.setDescription("Keeps DoomScroll Trap active when the app is closed");
                nm.createNotificationChannel(channel);
            }

            Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                    ? new Notification.Builder(this, channelId)
                    : new Notification.Builder(this);

            Notification notification = builder
                    .setContentTitle("DoomScroll Trap Active")
                    .setContentText("Monitoring Shorts & Instagram usage")
                    .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
                    .setOngoing(true)
                    .build();

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(1001, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            } else {
                startForeground(1001, notification);
            }
        } catch (Exception ignored) {}
    }

    // Intercepts physical Volume Up / Down / Mute buttons while Speaker Sabotage is active
    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        if (isPlayingPunishmentSound && event != null) {
            int keyCode = event.getKeyCode();
            if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN ||
                keyCode == KeyEvent.KEYCODE_VOLUME_UP ||
                keyCode == KeyEvent.KEYCODE_VOLUME_MUTE) {
                forceMaxVolume();
                return true; // Blocks the volume button press completely!
            }
        }
        return super.onKeyEvent(event);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        try {
            if (event == null) return;

            if (event.getPackageName() != null) {
                String pkg = event.getPackageName().toString();

                // Block status bar pull-down during overlay lock OR during speaker sabotage
                if ((isOverlayActive || isPlayingPunishmentSound) &&
                    "com.android.systemui".equals(pkg) &&
                    !isEmergencyPackage(currentPackage)) {
                    collapseStatusBar();
                    return;
                }

                // Ignore transient events from YouTube/Instagram while stopPlaybackAndCloseApp() is minimizing them
                if (System.currentTimeMillis() < closingAppGraceUntilMs &&
                    ("com.google.android.youtube".equals(pkg) || "com.instagram.android".equals(pkg))) {
                    isWatchingShorts = false;
                    return;
                }

                if (!pkg.equals(getPackageName()) && !"com.android.systemui".equals(pkg)) {
                    currentPackage = pkg;
                }
            }

            if (isOverlayActive) {
                enforceLockScreen();
                return;
            }

            if ("com.google.android.youtube".equals(currentPackage)) {
                AccessibilityNodeInfo rootNode = getRootInActiveWindow();
                if (rootNode != null) {
                    isWatchingShorts = checkShortsUI(rootNode);
                } else {
                    isWatchingShorts = false;
                }
            } else {
                isWatchingShorts = false;
            }
        } catch (Exception ignored) {}
    }

    private void refreshActiveForegroundPackage() {
        try {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root != null && root.getPackageName() != null) {
                String rootPkg = root.getPackageName().toString();
                if (!rootPkg.equals(getPackageName()) && !"com.android.systemui".equals(rootPkg)) {
                    currentPackage = rootPkg;
                }
            }
        } catch (Exception ignored) {}
    }

    private void refreshYouTubeShortsState() {
        if (System.currentTimeMillis() < closingAppGraceUntilMs) {
            isWatchingShorts = false;
            return;
        }
        try {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root != null && root.getPackageName() != null) {
                String rootPkg = root.getPackageName().toString();
                if (!rootPkg.equals(getPackageName()) && !"com.android.systemui".equals(rootPkg)) {
                    currentPackage = rootPkg;
                }
                if ("com.google.android.youtube".equals(currentPackage)) {
                    isWatchingShorts = checkShortsUI(root);
                } else {
                    isWatchingShorts = false;
                }
            }
        } catch (Exception ignored) {}
    }

    private static class ShortsScanState {
        boolean hasSelectedShortsTab = false;
        boolean hasSelectedNonShortsTab = false;
        boolean hasVisibleHomeFeed = false;
        boolean hasVisibleShortsActiveElement = false;
        boolean hasVisibleShortsContainer = false;
    }

    private boolean checkShortsUI(AccessibilityNodeInfo rootNode) {
        if (rootNode == null) return false;
        try {
            DisplayMetrics metrics = new DisplayMetrics();
            if (windowManager != null && windowManager.getDefaultDisplay() != null) {
                windowManager.getDefaultDisplay().getRealMetrics(metrics);
            } else {
                metrics = getResources().getDisplayMetrics();
            }
            int screenWidth = metrics.widthPixels > 0 ? metrics.widthPixels : 1080;
            int screenHeight = metrics.heightPixels > 0 ? metrics.heightPixels : 1920;

            ShortsScanState state = new ShortsScanState();
            scanShortsNodes(rootNode, state, screenWidth, screenHeight);

            // 1. "Shorts" bottom navigation tab is actively selected and visible
            if (state.hasSelectedShortsTab) {
                return true;
            }

            // 2. Active Shorts player controls or full-screen scrollable reel_recycler are visibly on screen
            //    (handles opening a Short directly from the Homepage Shorts shelf)
            if (state.hasVisibleShortsActiveElement) {
                return true;
            }

            // 3. Fallback container check only if not on the visible Home/Subscriptions/You feed
            if (state.hasVisibleShortsContainer && !state.hasVisibleHomeFeed && !state.hasSelectedNonShortsTab) {
                return true;
            }
        } catch (Exception ignored) {}
        return false;
    }

    private void scanShortsNodes(AccessibilityNodeInfo node, ShortsScanState state, int screenWidth, int screenHeight) {
        if (node == null) return;
        try {
            // Skip nodes that are hidden/cached in background fragments (fixes post-punishment homepage false positive)
            if (node.isVisibleToUser()) {
                Rect bounds = new Rect();
                node.getBoundsInScreen(bounds);

                int visibleLeft = Math.max(0, bounds.left);
                int visibleTop = Math.max(0, bounds.top);
                int visibleRight = Math.min(screenWidth, bounds.right);
                int visibleBottom = Math.min(screenHeight, bounds.bottom);
                int visibleWidth = Math.max(0, visibleRight - visibleLeft);
                int visibleHeight = Math.max(0, visibleBottom - visibleTop);

                if (visibleWidth > 0 && visibleHeight > 0) {
                    boolean isFullscreenNode = visibleWidth >= (int) (screenWidth * 0.65f)
                            && visibleHeight >= (int) (screenHeight * 0.55f);

                    String viewId = node.getViewIdResourceName();
                    if (viewId != null) {
                        // Home feed filter chips bar ("All", "Gaming", etc.) only appears on Homepage
                        if (viewId.contains("chip_cloud")) {
                            state.hasVisibleHomeFeed = true;
                        }

                        // Active full-screen Shorts vertical pager
                        if (viewId.contains("reel_recycler") || viewId.contains("shorts_player")) {
                            if (isFullscreenNode && (node.isScrollable() || node.getChildCount() > 0)) {
                                state.hasVisibleShortsActiveElement = true;
                            }
                        }

                        // Active Shorts player overlay controls (hidden when on Homepage)
                        if (viewId.contains("reel_time_bar") ||
                            viewId.contains("reel_progress_bar") ||
                            viewId.contains("reel_rhs_container") ||
                            viewId.contains("reel_dyn_like") ||
                            viewId.contains("reel_dyn_dislike") ||
                            viewId.contains("reel_dyn_comment") ||
                            viewId.contains("reel_dyn_share") ||
                            viewId.contains("reel_player_header")) {
                            state.hasVisibleShortsActiveElement = true;
                        }

                        // Outer Shorts player containers (only valid when full-screen)
                        if (viewId.contains("reel_watch_player") ||
                            viewId.contains("reel_player_page_container")) {
                            if (isFullscreenNode) {
                                state.hasVisibleShortsContainer = true;
                            }
                        }
                    }

                    CharSequence contentDesc = node.getContentDescription();
                    if (contentDesc != null) {
                        String desc = contentDesc.toString();
                        if (desc.equals("Shorts") && node.isSelected()) {
                            state.hasSelectedShortsTab = true;
                        } else if ((desc.equals("Home") || desc.equals("Subscriptions") ||
                                    desc.equals("You") || desc.equals("Library")) && node.isSelected()) {
                            state.hasSelectedNonShortsTab = true;
                        } else if (desc.contains("Create a Short")) {
                            state.hasVisibleShortsActiveElement = true;
                        }
                    }
                }
            }

            int count = node.getChildCount();
            for (int i = 0; i < count; i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child != null) {
                    scanShortsNodes(child, state, screenWidth, screenHeight);
                }
            }
        } catch (Exception ignored) {}
    }

    private boolean isTargetActive() {
        return "com.instagram.android".equals(currentPackage) ||
              ("com.google.android.youtube".equals(currentPackage) && isWatchingShorts);
    }

    private void checkPunishment() {
        if (isOverlayActive) return;
        int timeOverThreshold = screenTimeSeconds - PUNISHMENT_THRESHOLD;
        if (timeOverThreshold == 0 || (timeOverThreshold > 0 && timeOverThreshold % REPEAT_PUNISHMENT_INTERVAL == 0)) {
            triggerRandomPunishment();
        }
    }

    // =========================================================================
    // 🎲 NON-REPEATING SHUFFLE QUEUE HELPERS (Punishments, Wallpapers, Sounds)
    // =========================================================================
    private synchronized int getNextPunishment() {
        if (punishmentQueue.isEmpty()) {
            punishmentQueue.add(PUNISHMENT_OVERLAY);
            punishmentQueue.add(PUNISHMENT_WALLPAPER);
            punishmentQueue.add(PUNISHMENT_SPEAKER);
            Collections.shuffle(punishmentQueue, random);

            // Ensure the first punishment of the new cycle is never the same as the last punishment of the previous cycle
            if (punishmentQueue.size() > 1 && punishmentQueue.get(0) == lastPunishment) {
                int swapIndex = 1 + random.nextInt(punishmentQueue.size() - 1);
                Collections.swap(punishmentQueue, 0, swapIndex);
            }
        }

        int chosen = punishmentQueue.remove(0);
        lastPunishment = chosen;
        saveQueuesState();
        return chosen;
    }

    private synchronized String getNextWallpaper(List<String> validImages) {
        if (validImages == null || validImages.isEmpty()) return null;

        // Keep only files that still exist in assets
        wallpaperQueue.retainAll(validImages);

        if (wallpaperQueue.isEmpty()) {
            wallpaperQueue.addAll(validImages);
            Collections.shuffle(wallpaperQueue, random);

            // Prevent the first wallpaper of a new cycle from repeating the last wallpaper of the previous cycle
            if (wallpaperQueue.size() > 1 && wallpaperQueue.get(0).equals(lastWallpaper)) {
                int swapIndex = 1 + random.nextInt(wallpaperQueue.size() - 1);
                Collections.swap(wallpaperQueue, 0, swapIndex);
            }
        }

        String chosen = wallpaperQueue.remove(0);
        lastWallpaper = chosen;
        saveQueuesState();
        return chosen;
    }

    private synchronized String getNextSound(List<String> validSounds) {
        if (validSounds == null || validSounds.isEmpty()) return null;

        // Keep only files that still exist in assets
        soundQueue.retainAll(validSounds);

        if (soundQueue.isEmpty()) {
            soundQueue.addAll(validSounds);
            Collections.shuffle(soundQueue, random);

            // Prevent the first sound of a new cycle from repeating the last sound of the previous cycle
            if (soundQueue.size() > 1 && soundQueue.get(0).equals(lastSound)) {
                int swapIndex = 1 + random.nextInt(soundQueue.size() - 1);
                Collections.swap(soundQueue, 0, swapIndex);
            }
        }

        String chosen = soundQueue.remove(0);
        lastSound = chosen;
        saveQueuesState();
        return chosen;
    }

    private synchronized void saveQueuesState() {
        if (prefs == null) return;
        List<String> pStrings = new ArrayList<>();
        for (Integer p : punishmentQueue) {
            pStrings.add(String.valueOf(p));
        }
        prefs.edit()
            .putString("punishmentQueue", TextUtils.join("\n", pStrings))
            .putInt("lastPunishment", lastPunishment)
            .putString("wallpaperQueue", TextUtils.join("\n", wallpaperQueue))
            .putString("lastWallpaper", lastWallpaper)
            .putString("soundQueue", TextUtils.join("\n", soundQueue))
            .putString("lastSound", lastSound)
            .apply();
    }

    private synchronized void loadQueuesState() {
        if (prefs == null) return;

        lastPunishment = prefs.getInt("lastPunishment", -1);
        lastWallpaper = prefs.getString("lastWallpaper", "");
        lastSound = prefs.getString("lastSound", "");

        punishmentQueue.clear();
        String pRaw = prefs.getString("punishmentQueue", "");
        if (!pRaw.isEmpty()) {
            for (String part : pRaw.split("\n")) {
                try {
                    punishmentQueue.add(Integer.parseInt(part.trim()));
                } catch (Exception ignored) {}
            }
        }

        wallpaperQueue.clear();
        String wRaw = prefs.getString("wallpaperQueue", "");
        if (!wRaw.isEmpty()) {
            wallpaperQueue.addAll(Arrays.asList(wRaw.split("\n")));
        }

        soundQueue.clear();
        String sRaw = prefs.getString("soundQueue", "");
        if (!sRaw.isEmpty()) {
            soundQueue.addAll(Arrays.asList(sRaw.split("\n")));
        }
    }

    // =========================================================================
    // 🎲 RANDOMIZED PUNISHMENT ENGINE
    // =========================================================================
    private void triggerRandomPunishment() {
        punishmentsToday++;
        saveStats();

        int chosenPunishment = getNextPunishment();

        switch (chosenPunishment) {
            case PUNISHMENT_OVERLAY:
                triggerLockOverlay();
                break;
            case PUNISHMENT_WALLPAPER:
                triggerWallpaperSabotage();
                break;
            case PUNISHMENT_SPEAKER:
                triggerSpeakerSabotage();
                break;
        }
    }

    // =========================================================================
    // 🖼️ PUNISHMENT 1: WALLPAPER SABOTAGE (Closes Shorts & Minimizes First)
    // =========================================================================
    private void triggerWallpaperSabotage() {
        // 1. Close YT Shorts player & minimize app to Home screen so PiP doesn't play
        stopPlaybackAndCloseApp();

        // 2. Change wallpaper right as user lands on the Home screen
        timerHandler.postDelayed(() -> {
            Toast.makeText(this, "⚠️ PUNISHMENT: Wallpaper Sabotaged!", Toast.LENGTH_LONG).show();

            backgroundExecutor.execute(() -> {
                try {
                    WallpaperManager wm = WallpaperManager.getInstance(getApplicationContext());
                    String[] files = getAssets().list("wallpapers");
                    List<String> validImages = new ArrayList<>();

                    if (files != null) {
                        for (String file : files) {
                            String lower = file.toLowerCase();
                            if (lower.endsWith(".png") || lower.endsWith(".jpg") ||
                                lower.endsWith(".jpeg") || lower.endsWith(".webp")) {
                                validImages.add(file);
                            }
                        }
                    }

                    Bitmap bitmapToSet;
                    String nextImage = getNextWallpaper(validImages);
                    if (nextImage != null) {
                        try (InputStream is = getAssets().open("wallpapers/" + nextImage)) {
                            bitmapToSet = BitmapFactory.decodeStream(is);
                        }
                    } else {
                        bitmapToSet = createFallbackWallpaper();
                    }

                    if (bitmapToSet != null) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                            wm.setBitmap(bitmapToSet, null, true,
                                    WallpaperManager.FLAG_SYSTEM | WallpaperManager.FLAG_LOCK);
                        } else {
                            wm.setBitmap(bitmapToSet);
                        }
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            });
        }, 300);
    }

    private Bitmap createFallbackWallpaper() {
        Bitmap bmp = Bitmap.createBitmap(1080, 1920, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bmp);
        canvas.drawColor(Color.parseColor("#8B0000"));

        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(Color.WHITE);
        paint.setTextSize(70f);
        paint.setTextAlign(Paint.Align.CENTER);
        canvas.drawText("STOP DOOMSCROLLING", 540, 900, paint);
        canvas.drawText("GET BACK TO WORK", 540, 1020, paint);
        return bmp;
    }

    // =========================================================================
    // 🔊 PUNISHMENT 2: SPEAKER SABOTAGE (Closes Shorts, Minimizes & Locks Volume)
    // =========================================================================
    private void triggerSpeakerSabotage() {
        // 1. Close YT Shorts player & minimize app first
        stopPlaybackAndCloseApp();
        stopActiveSound();

        // 2. Start loud sound after app is minimized & lock volume controls
        timerHandler.postDelayed(() -> {
            Toast.makeText(this, "⚠️ PUNISHMENT: Speaker Sabotage! (Volume Locked)", Toast.LENGTH_LONG).show();

            try {
                isPlayingPunishmentSound = true;
                forceMaxVolume();
                timerHandler.removeCallbacks(volumeLockRunnable);
                timerHandler.post(volumeLockRunnable);

                String[] files = getAssets().list("sounds");
                List<String> validSounds = new ArrayList<>();
                if (files != null) {
                    for (String file : files) {
                        String lower = file.toLowerCase();
                        if (lower.endsWith(".mp3") || lower.endsWith(".wav") ||
                            lower.endsWith(".ogg") || lower.endsWith(".m4a") || lower.endsWith(".aac")) {
                            validSounds.add(file);
                        }
                    }
                }

                activeMediaPlayer = new MediaPlayer();
                activeMediaPlayer.setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build());

                String nextSound = getNextSound(validSounds);
                if (nextSound != null) {
                    AssetFileDescriptor afd = getAssets().openFd("sounds/" + nextSound);
                    activeMediaPlayer.setDataSource(afd.getFileDescriptor(), afd.getStartOffset(), afd.getLength());
                    afd.close();
                } else {
                    Uri alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
                    if (alarmUri == null) {
                        alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
                    }
                    activeMediaPlayer.setDataSource(getApplicationContext(), alarmUri);
                }

                activeMediaPlayer.setLooping(false);
                activeMediaPlayer.prepare();
                activeMediaPlayer.start();

                activeMediaPlayer.setOnCompletionListener(mp -> stopActiveSound());
                timerHandler.postDelayed(this::stopActiveSound, SOUND_DURATION_MS);
            } catch (Exception e) {
                stopActiveSound();
            }
        }, 350);
    }

    private void forceMaxVolume() {
        try {
            AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
            if (am != null) {
                int maxMusic = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
                int maxAlarm = am.getStreamMaxVolume(AudioManager.STREAM_ALARM);
                am.setStreamVolume(AudioManager.STREAM_MUSIC, maxMusic, 0);
                am.setStreamVolume(AudioManager.STREAM_ALARM, maxAlarm, 0);
            }
        } catch (Exception ignored) {}
    }

    private void stopActiveSound() {
        isPlayingPunishmentSound = false;
        timerHandler.removeCallbacks(volumeLockRunnable);
        if (activeMediaPlayer != null) {
            try {
                if (activeMediaPlayer.isPlaying()) {
                    activeMediaPlayer.stop();
                }
                activeMediaPlayer.release();
            } catch (Exception ignored) {}
            activeMediaPlayer = null;
        }
    }

    // =========================================================================
    // 🔒 PUNISHMENT 3: FULLSCREEN LOCK OVERLAY
    // =========================================================================
    private void stopPlaybackAndCloseApp() {
        try {
            AudioManager audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
            if (audioManager != null) {
                audioManager.requestAudioFocus(null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT);
                audioManager.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PAUSE));
                audioManager.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PAUSE));
                audioManager.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_STOP));
                audioManager.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_STOP));
            }
        } catch (Exception ignored) {}

        // Exits YT Shorts player first so Picture-in-Picture (PiP) will not trigger
        closingAppGraceUntilMs = System.currentTimeMillis() + 600;
        isWatchingShorts = false;
        currentPackage = "";
        performGlobalAction(GLOBAL_ACTION_BACK);

        timerHandler.postDelayed(() -> {
            isWatchingShorts = false;
            currentPackage = "";
            performGlobalAction(GLOBAL_ACTION_HOME);
            try {
                ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
                if (am != null) {
                    am.killBackgroundProcesses("com.google.android.youtube");
                    am.killBackgroundProcesses("com.instagram.android");
                }
            } catch (Exception ignored) {}
        }, 150);
    }

    private void triggerLockOverlay() {
        if (isOverlayActive) return;
        isOverlayActive = true;
        lockEndTimeMs = System.currentTimeMillis() + LOCK_DURATION_MS;

        stopPlaybackAndCloseApp();

        timerHandler.postDelayed(() -> {
            try {
                if (overlayView != null) {
                    windowManager.removeView(overlayView);
                    overlayView = null;
                }

                DisplayMetrics metrics = new DisplayMetrics();
                windowManager.getDefaultDisplay().getRealMetrics(metrics);

                WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                        metrics.widthPixels,
                        metrics.heightPixels + 300,
                        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_FULLSCREEN,
                        PixelFormat.OPAQUE);

                params.gravity = Gravity.TOP | Gravity.START;
                params.x = 0;
                params.y = -150;

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    params.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    params.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
                }

                LinearLayout layout = new LinearLayout(this);
                layout.setBackgroundColor(Color.BLACK);
                layout.setOrientation(LinearLayout.VERTICAL);
                layout.setGravity(Gravity.CENTER);
                layout.setPadding(60, 0, 60, 0);

                TextView title = new TextView(this);
                title.setText("TIME LIMIT EXCEEDED.\nPHONE LOCKED.");
                title.setTextColor(Color.WHITE);
                title.setTextSize(24);
                title.setGravity(Gravity.CENTER);
                layout.addView(title);

                countdownText = new TextView(this);
                countdownText.setTextColor(Color.parseColor("#FF4444"));
                countdownText.setTextSize(18);
                countdownText.setGravity(Gravity.CENTER);
                countdownText.setPadding(0, 30, 0, 80);
                updateCountdownUI(LOCK_DURATION_MS);
                layout.addView(countdownText);

                Button phoneBtn = createEmergencyButton("📞 Emergency Phone / Calls", "#2E7D32");
                phoneBtn.setOnClickListener(v -> openEmergencyApp(true));
                layout.addView(phoneBtn);

                View spacer = new View(this);
                spacer.setLayoutParams(new LinearLayout.LayoutParams(1, 30));
                layout.addView(spacer);

                Button smsBtn = createEmergencyButton("💬 Emergency Messages", "#1565C0");
                smsBtn.setOnClickListener(v -> openEmergencyApp(false));
                layout.addView(smsBtn);

                overlayView = layout;
                windowManager.addView(overlayView, params);
            } catch (Exception ignored) {
                isOverlayActive = false;
            }
        }, 350);
    }

    private Button createEmergencyButton(String label, String hexColor) {
        Button btn = new Button(this);
        btn.setText(label);
        btn.setTextColor(Color.WHITE);
        btn.setTextSize(15);
        btn.setAllCaps(false);

        GradientDrawable shape = new GradientDrawable();
        shape.setCornerRadius(24f);
        shape.setColor(Color.parseColor(hexColor));
        btn.setBackground(shape);

        LinearLayout.LayoutParams btnParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 140);
        btn.setLayoutParams(btnParams);
        return btn;
    }

    private void openEmergencyApp(boolean isPhone) {
        if (overlayView == null) return;

        hasEnteredEmergencyApp = false;
        emergencyLaunchGraceUntilMs = System.currentTimeMillis() + 1500;
        overlayView.setVisibility(View.GONE);

        try {
            Intent intent;
            if (isPhone) {
                intent = new Intent(Intent.ACTION_DIAL);
            } else {
                String defaultSmsPkg = Telephony.Sms.getDefaultSmsPackage(this);
                if (defaultSmsPkg != null) {
                    intent = getPackageManager().getLaunchIntentForPackage(defaultSmsPkg);
                } else {
                    intent = new Intent(Intent.ACTION_MAIN);
                    intent.addCategory(Intent.CATEGORY_APP_MESSAGING);
                }
            }
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(intent);
            }
        } catch (Exception ignored) {}

        timerHandler.removeCallbacks(fastEmergencyWatcher);
        timerHandler.postDelayed(fastEmergencyWatcher, 150);
    }

    private boolean isEmergencyPackage(String pkg) {
        if (pkg == null) return false;
        String lower = pkg.toLowerCase();
        String defaultSms = Telephony.Sms.getDefaultSmsPackage(this);
        if (defaultSms != null && defaultSms.equals(pkg)) return true;

        return lower.contains("dialer") ||
               lower.contains("incallui") ||
               lower.contains("telecom") ||
               lower.contains("contacts") ||
               lower.contains("mms") ||
               lower.contains("messaging");
    }

    private void enforceLockScreen() {
        if (!isOverlayActive || overlayView == null) return;

        boolean currentlyInEmergency = isEmergencyPackage(currentPackage);

        if (currentlyInEmergency) {
            hasEnteredEmergencyApp = true;
            emergencyLaunchGraceUntilMs = 0;
        }

        boolean inInitialLaunchWindow = !hasEnteredEmergencyApp && (System.currentTimeMillis() < emergencyLaunchGraceUntilMs);

        if (currentlyInEmergency || inInitialLaunchWindow) {
            if (overlayView.getVisibility() != View.GONE) {
                overlayView.setVisibility(View.GONE);
            }
        } else {
            if (overlayView.getVisibility() != View.VISIBLE) {
                timerHandler.removeCallbacks(fastEmergencyWatcher);
                if (isTargetActive()) {
                    stopPlaybackAndCloseApp();
                }
                overlayView.setVisibility(View.VISIBLE);
            }
        }
    }

    private void collapseStatusBar() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                performGlobalAction(GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE);
            } else {
                sendBroadcast(new Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS));
            }
        } catch (Exception ignored) {}
    }

    private void updateCountdownUI(long remainingMs) {
        if (countdownText == null) return;
        int totalSecs = (int) Math.ceil(remainingMs / 1000.0);
        int mins = totalSecs / 60;
        int secs = totalSecs % 60;
        countdownText.setText(String.format("Unlocks in %02d:%02d", mins, secs));
    }

    private void removeLockOverlay() {
        timerHandler.removeCallbacks(fastEmergencyWatcher);
        if (overlayView != null) {
            try {
                windowManager.removeView(overlayView);
            } catch (Exception ignored) {}
            overlayView = null;
        }
        isOverlayActive = false;
    }

    private void saveStats() {
        if (prefs != null) {
            prefs.edit()
                .putInt("screenTime", screenTimeSeconds)
                .putInt("punishments", punishmentsToday)
                .apply();
        }
    }

    @Override
    public void onInterrupt() {
        stopActiveSound();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        timerHandler.removeCallbacks(timeTrackerRunnable);
        timerHandler.removeCallbacks(fastEmergencyWatcher);
        timerHandler.removeCallbacks(volumeLockRunnable);
        stopActiveSound();
    }
}