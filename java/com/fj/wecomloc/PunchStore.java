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

    /** 外出打卡窗口：走 remoteCheckExceptionRamdon_V2815（可带照片/人脸）。 */
    static boolean outsideEnabled(Context c) {
        return sp(c).getBoolean("outside_enabled", false);
    }

    static int outsideStart(Context c) {
        return sp(c).getInt("outside_start", 9 * 60);
    }

    static int outsideEnd(Context c) {
        return sp(c).getInt("outside_end", 18 * 60);
    }

    static void setOutside(Context c, boolean en, int start, int end) {
        sp(c).edit().putBoolean("outside_enabled", en)
                .putInt("outside_start", start).putInt("outside_end", end).apply();
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

    /**
     * Photo pool lives in /sdcard/Pictures/wecom_punch.
     *
     * <p>Not getExternalFilesDir(): Android's scoped storage hides one app's
     * Android/data from every other app, so the WeCom process could not read the
     * pictures to copy them into its own private dir (the check-in then failed
     * with error 901). A shared media dir is readable by everyone.
     */
    static File photoDir(Context c) {
        File d = new File("/sdcard/Pictures/wecom_punch");
        if (!d.exists()) {
            d.mkdirs();
        }
        return d;
    }

    static int photoCount(Context c) {
        return photos(c).length;
    }

    static File[] photos(Context c) {
        return list("photo", photoDir(c));
    }

    // -------------------------------------------------------------- face pool

    /** Real face photos used to answer the server's liveness challenge. */
    static File faceDir(Context c) {
        File d = new File("/sdcard/Pictures/wecom_punch_face");
        if (!d.exists()) {
            d.mkdirs();
        }
        return d;
    }

    static File[] faces(Context c) {
        return list("face", faceDir(c));
    }

    /** Only *.jpg picked up, so leftover thumbnails or .nomedia do not break rotation. */
    private static File[] list(String prefix, File dir) {
        File[] fs = dir.listFiles();
        if (fs == null) {
            return new File[0];
        }
        java.util.ArrayList<File> out = new java.util.ArrayList<File>();
        for (File f : fs) {
            String n = f.getName();
            if (f.isFile() && n.startsWith(prefix) && n.endsWith(".jpg")) {
                out.add(f);
            }
        }
        File[] arr = out.toArray(new File[0]);
        Arrays.sort(arr);
        return arr;
    }

    static int faceCount(Context c) {
        return faces(c).length;
    }

    static boolean faceEnabled(Context c) {
        return sp(c).getBoolean("face_enabled", false);
    }

    static void setFaceEnabled(Context c, boolean en) {
        sp(c).edit().putBoolean("face_enabled", en).apply();
    }

    static int faceRotate(Context c) {
        return sp(c).getInt("face_rotate", 0);
    }

    static void setFaceRotate(Context c, int v) {
        sp(c).edit().putInt("face_rotate", v).apply();
    }
}
