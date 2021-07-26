package android.content.res;

import android.annotation.Nullable;
import android.graphics.Color;
import android.os.SystemProperties;
import android.util.Log;
import android.provider.Settings;
import android.content.Context;
import android.app.ActivityThread;

import java.util.ArrayList;
import java.util.Arrays;

public final class AccentUtils {
    private AccentUtils() {}

    private static final String TAG = "AccentUtils";

    private static final String ACCENT_DARK_SETTING = "accent_dark";
    private static final String ACCENT_LIGHT_SETTING = "accent_light";

    public static boolean isResourceDarkAccent(@Nullable String resName) {
        return resName == null
                ? false
                :  resName.contains("accent_device_default_dark")
                || resName.contains("colorAccent")
                || resName.contains("lockscreen_clock_accent_color")
                || resName.contains("oneplus_accent_color")
                || resName.contains("settings_accent_color")
                || resName.contains("settingsHeaderColor")
                || resName.contains("dismiss_all_icon_color")
                || resName.contains("avatar_bg_red")
                || resName.contains("folder_indicator_color")
                || resName.contains("accent_color_red")
                || resName.contains("alert_dialog_color_accent_dark")
                || resName.contains("oneplus_accent_text_color")
                || resName.contains("accent_device_default")
                || resName.contains("accent_device_default_dark");
    }

    public static boolean isResourceLightAccent(@Nullable String resName) {
        return resName == null
                ? false
                : resName.contains("accent_device_default_light")
                || resName.contains("colorAccent")
                || resName.contains("lockscreen_clock_accent_color")
                || resName.contains("oneplus_accent_color")
                || resName.contains("settings_accent_color")
                || resName.contains("dismiss_all_icon_color")
                || resName.contains("settingsHeaderColor")
                || resName.contains("avatar_bg_red")
                || resName.contains("folder_indicator_color")
                || resName.contains("accent_color_red")
                || resName.contains("alert_dialog_color_accent_light")
                || resName.contains("oneplus_accent_text_color")
                || resName.contains("accent_device_default")
                || resName.contains("accent_device_default_light");
    }

    public static int getDarkAccentColor(int defaultColor) {
        return getAccentColor(defaultColor, ACCENT_DARK_SETTING);
    }

    public static int getLightAccentColor(int defaultColor) {
        return getAccentColor(defaultColor, ACCENT_LIGHT_SETTING);
    }

    private static int getAccentColor(int defaultColor, String setting) {
        final Context context = ActivityThread.currentApplication();
        try {
            String colorValue = Settings.Secure.getString(context.getContentResolver(), setting);
            return (colorValue == null || "-1".equals(colorValue))
                    ? defaultColor
                    : Color.parseColor("#" + colorValue);
        } catch (Exception e) {
            Log.e(TAG, "Failed to set accent: " + e.getMessage() +
                    "\nSetting default: " + defaultColor);
            return defaultColor;
        }
    }
}
