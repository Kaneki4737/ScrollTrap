package com.anonymous.ST;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.accessibility.AccessibilityManager;
import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.Promise;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactContextBaseJavaModule;
import com.facebook.react.bridge.ReactMethod;
import com.facebook.react.bridge.WritableMap;
import java.util.List;

public class ScrollMonitorModule extends ReactContextBaseJavaModule {
    ScrollMonitorModule(ReactApplicationContext context) {
        super(context);
    }

    @Override
    public String getName() {
        return "ScrollMonitor";
    }

    @ReactMethod
    public void requestOverlayPermission() {
        if (!Settings.canDrawOverlays(getReactApplicationContext())) {
            Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getReactApplicationContext().getPackageName()));
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            getReactApplicationContext().startActivity(intent);
        }
    }

    @ReactMethod
    public void openAccessibilitySettings() {
        Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        getReactApplicationContext().startActivity(intent);
    }

    @ReactMethod
    public void requestIgnoreBatteryOptimizations() {
        try {
            Context context = getReactApplicationContext();
            PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            if (pm != null && !pm.isIgnoringBatteryOptimizations(context.getPackageName())) {
                Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                intent.setData(Uri.parse("package:" + context.getPackageName()));
                intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(intent);
            }
        } catch (Exception ignored) {}
    }

    @ReactMethod
    public void checkPermissions(Promise promise) {
        Context context = getReactApplicationContext();
        WritableMap map = Arguments.createMap();
        map.putBoolean("hasOverlay", Settings.canDrawOverlays(context));

        boolean hasAccessibility = false;
        AccessibilityManager am = (AccessibilityManager) context.getSystemService(Context.ACCESSIBILITY_SERVICE);
        if (am != null) {
            List<AccessibilityServiceInfo> enabledServices = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK);
            for (AccessibilityServiceInfo service : enabledServices) {
                if (service.getId().contains(context.getPackageName())) {
                    hasAccessibility = true;
                    break;
                }
            }
        }
        map.putBoolean("hasAccessibility", hasAccessibility);

        PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        boolean hasBatteryExemption = pm != null && pm.isIgnoringBatteryOptimizations(context.getPackageName());
        map.putBoolean("hasBatteryExemption", hasBatteryExemption);

        promise.resolve(map);
    }

    @ReactMethod
    public void getStats(Promise promise) {
        SharedPreferences prefs = getReactApplicationContext().getSharedPreferences("DoomScrollPrefs", Context.MODE_PRIVATE);
        WritableMap map = Arguments.createMap();
        map.putInt("screenTime", prefs.getInt("screenTime", 0));
        map.putInt("punishments", prefs.getInt("punishments", 0));
        promise.resolve(map);
    }
}