package com.fj.wecomloc;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.File;
import java.util.Arrays;

/** Schedule + photo config for auto punch. */
final class PunchStore {

    private static final String PREFS = "punch";

    private PunchStore() {
    }

    static boolean morningEnabled(Context c) {
        return sp(c).getBoolean("morning_enabled", false);
    }

    static int morningStart(Context c) {
        return sp(c).getInt("morning_start", 8 * 60);
    }

    static int morningEnd(Context c) {
        return sp(c).getInt("morning_end", 8 * 60 + 30);
    }

    static boolean eveningEnabled(Context c) {
        return sp(c).getBoolean("evening_enabled", false);
    }

    static int eveningStart(Context c) {
        return sp(c).getInt("evening_start", 18 * 60);
    }

    static int eveningEnd(Context c) {
        return sp(c).getInt("evening_end", 18 * 60 + 30);
    }

    static String pin(Context c) {
        return sp(c).getString("pin", "");
    }

    static void setPin(Context c, String v) {
        sp(c).edit().putString("pin", v == null ? "" : v.trim()).apply();
    }

    static boolean photoEnabled(Context c) {
        return sp(c).getBoolean("photo_enabled", false);
    }

    /** Rotation cursor for staged punch photos (survives reboots). */
    static int photoRotate(Context c) {
        return sp(c).getInt("photo_rotate", 0);
    }

    static void setPhotoRotate(Context c, int v) {
        sp(c).edit().putInt("photo_rotate", v).apply();
    }

    static void setMorning(Context c, boolean en, int start, int end) {
        sp(c).edit().putBoolean("morning_enabled", en)
                .putInt("morning_start", start).putInt("morning_end", end).apply();
    }

    static void setEvening(Context c, boolean en, int start, int end) {
        sp(c).edit().putBoolean("evening_enabled", en)
                .putInt("evening_start", start).putInt("evening_end", end).apply();
    }

    static void setPhotoEnabled(Context c, boolean en) {
        sp(c).edit().putBoolean("photo_enabled", en).apply();
    }

    /** Last scheduled fire time in minutes-of-day, shown in the UI. */
    static void setLastScheduled(Context c, String type, int minutes) {
        sp(c).edit().putInt("last_" + type, minutes).apply();
    }

    static int lastScheduled(Context c, String type) {
        return sp(c).getInt("last_" + type, -1);
    }

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** Photo files live in getExternalFilesDir("punch") as photo_0.jpg ... */
    static File photoDir(Context c) {
        File d = new File(c.getExternalFilesDir(null), "punch");
        if (!d.exists()) {
            d.mkdirs();
        }
        return d;
    }

    static int photoCount(Context c) {
        File[] fs = photoDir(c).listFiles();
        return fs == null ? 0 : fs.length;
    }

    static File[] photos(Context c) {
        File[] fs = photoDir(c).listFiles();
        if (fs == null) {
            return new File[0];
        }
        Arrays.sort(fs);
        return fs;
    }
}
