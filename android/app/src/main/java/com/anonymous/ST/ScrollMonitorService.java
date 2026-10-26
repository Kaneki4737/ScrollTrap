package com.anonymous.ST;

import android.accessibilityservice.AccessibilityService;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.LinearLayout;
import android.widget.TextView;

public class ScrollMonitorService extends AccessibilityService {

    private String currentPackage = "";
    private boolean isWatchingShorts = false;
    private int screenTimeSeconds = 0;
    private int punishmentsToday = 0;
    
    private final int PUNISHMENT_THRESHOLD = 10; // Change to 300 for 5 mins
    private final int REPEAT_PUNISHMENT_INTERVAL = 5; // Change to 60 for 1 min
    
    private Handler timerHandler = new Handler(Looper.getMainLooper());
    private WindowManager windowManager;
    private View overlayView;
    private boolean isOverlayActive = false;
    private SharedPreferences prefs;

    private Runnable timeTrackerRunnable = new Runnable() {
        @Override
        public void run() {
            if (isTargetActive()) {
                screenTimeSeconds++;
                saveStats();
                checkPunishment();
            }
            timerHandler.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        prefs = getSharedPreferences("DoomScrollPrefs", MODE_PRIVATE);
        
        screenTimeSeconds = prefs.getInt("screenTime", 0);
        punishmentsToday = prefs.getInt("punishments", 0);
        
        timerHandler.post(timeTrackerRunnable);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event.getPackageName() != null) {
            currentPackage = event.getPackageName().toString();
        }

        if ("com.google.android.youtube".equals(currentPackage)) {
            AccessibilityNodeInfo rootNode = getRootInActiveWindow();
            if (rootNode != null) {
                isWatchingShorts = checkShortsUI(rootNode);
            }
        } else {
            isWatchingShorts = false;
        }
    }

    // Recursively scans the screen to confirm we are ACTUALLY in the Shorts feed
    private boolean checkShortsUI(AccessibilityNodeInfo node) {
        if (node == null) return false;
        
        CharSequence contentDesc = node.getContentDescription();
        if (contentDesc != null) {
            String desc = contentDesc.toString();
            // 1. Checks if the "Shorts" tab in the bottom bar is the currently active tab
            if (desc.equals("Shorts") && node.isSelected()) {
                return true;
            }
            // 2. Checks for the camera icon unique to the Shorts scrolling feed
            if (desc.contains("Create a Short")) {
                return true;
            }
        }
        
        for (int i = 0; i < node.getChildCount(); i++) {
            if (checkShortsUI(node.getChild(i))) {
                return true;
            }
        }
        return false;
    }

    private boolean isTargetActive() {
        return "com.instagram.android".equals(currentPackage) || 
              ("com.google.android.youtube".equals(currentPackage) && isWatchingShorts);
    }

    private void checkPunishment() {
        if (isOverlayActive) return;
        int timeOverThreshold = screenTimeSeconds - PUNISHMENT_THRESHOLD;
        if (timeOverThreshold == 0 || (timeOverThreshold > 0 && timeOverThreshold % REPEAT_PUNISHMENT_INTERVAL == 0)) {
            triggerLockOverlay();
        }
    }

    private void triggerLockOverlay() {
        if (isOverlayActive) return;
        isOverlayActive = true;
        punishmentsToday++;
        saveStats();

        // Natively acts exactly as if you pressed the physical Home button
        performGlobalAction(GLOBAL_ACTION_HOME);

        timerHandler.postDelayed(() -> {
            WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL 
                    | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                    | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS // Overrides safe areas
                    | WindowManager.LayoutParams.FLAG_FULLSCREEN,      // Hides status bar space
                    PixelFormat.TRANSLUCENT);

            params.gravity = Gravity.CENTER;

            LinearLayout layout = new LinearLayout(this);
            layout.setBackgroundColor(Color.parseColor("#FF000000"));
            layout.setOrientation(LinearLayout.VERTICAL);
            layout.setGravity(Gravity.CENTER);
            layout.setFocusableInTouchMode(true);

            // Immersive Sticky Mode: Hides Status Bar & Navigation Bar completely
            layout.setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);

            TextView text = new TextView(this);
            text.setText("TIME LIMIT EXCEEDED.\nPHONE LOCKED.");
            text.setTextColor(Color.WHITE);
            text.setTextSize(24);
            text.setGravity(Gravity.CENTER);
            layout.addView(text);

            overlayView = layout;
            windowManager.addView(overlayView, params);

            timerHandler.postDelayed(() -> {
                if (overlayView != null) {
                    windowManager.removeView(overlayView);
                    overlayView = null;
                }
                isOverlayActive = false;
            }, 10000); 
        }, 300); // 300ms delay gives the Home Screen time to open before locking
    }

    private void saveStats() {
        prefs.edit()
            .putInt("screenTime", screenTimeSeconds)
            .putInt("punishments", punishmentsToday)
            .apply();
    }

    @Override
    public void onInterrupt() {}
}