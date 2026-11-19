package com.fj.wecomloc;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import java.io.File;

/**
 * Fired by AlarmManager inside the punch window.
 *
 * <p>Applies the mock location, stages a punch photo inside WeCom's private
 * dir, then asks the injected WeCom-side module to submit the check-in
 * directly through {@code AttendanceService}. No accessibility, no UI.
 */
public class AlarmReceiver extends BroadcastReceiver {

    private static final String ACTION_PUNCH_DIRECT = "com.fj.wecomloc.PUNCH_DIRECT";
    private static final String ACTION_RESULT = "com.fj.wecomloc.PUNCH_RESULT";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !PunchScheduler.ACTION_PUNCH.equals(intent.getAction())) return;
        final String type = intent.getStringExtra("type");
        final Context app = context.getApplicationContext();

        final PendingResult result = goAsync();
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    applyLocation(app);
                    String photo = null;
                    if (PunchStore.photoEnabled(app)) {
                        RootConfig.pushPhotos(app);
                        photo = nextPhotoPath(app);
                    }
                    sendDirect(app, type, photo);
                    PunchScheduler.scheduleNext(app, type);
                } catch (Throwable ignored) {
                } finally {
                    result.finish();
                }
            }
        }, "fj-punch").start();
    }

    /** Send the saved mock location to WeCom via the same broadcast the UI uses. */
    private static void applyLocation(Context app) {
        SharedPreferences sp = app.getSharedPreferences("ui", Context.MODE_PRIVATE);
        boolean enabled = sp.getBoolean("enabled", true);
        double lat = parseD(sp.getString("lat", "39.908722"), 39.908722d);
        double lng = parseD(sp.getString("lng", "116.397499"), 116.397499d);
        String name = sp.getString("name", "");
        String addr = sp.getString("addr", "");
        Intent i = new Intent("com.fj.wecomloc.SET");
        i.setPackage("com.tencent.wework");
        i.putExtra("enabled", enabled);
        i.putExtra("lat", lat);
        i.putExtra("lng", lng);
        if (name.length() > 0) i.putExtra("name", name);
        if (addr.length() > 0) i.putExtra("addr", addr);
        try {
            app.sendBroadcast(i);
        } catch (Throwable ignored) {
        }
    }

    /**
     * Ask WeCom's injected module to perform the check-in. "morning"/"evening"
     * map to 上下班打卡; anything else maps to 外出打卡.
     */
    private static void sendDirect(Context app, String type, String photoPath) {
        SharedPreferences sp = app.getSharedPreferences("ui", Context.MODE_PRIVATE);
        double lat = parseD(sp.getString("lat", "39.908722"), 39.908722d);
        double lng = parseD(sp.getString("lng", "116.397499"), 116.397499d);
        String name = sp.getString("name", "");
        String addr = sp.getString("addr", "");
        boolean outside = sp.getBoolean("outside", true);
        Intent i = new Intent(ACTION_PUNCH_DIRECT);
        i.setPackage("com.tencent.wework");
        i.putExtra("outside", outside);
        i.putExtra("lat", lat);
        i.putExtra("lng", lng);
        i.putExtra("name", name);
        i.putExtra("addr", addr);
        i.putExtra("type", type == null ? "" : type);
        if (photoPath != null && photoPath.length() > 0) {
            i.putExtra("photo", photoPath);
        }
        try {
            app.sendBroadcast(i);
        } catch (Throwable ignored) {
        }
    }

    /** Pick the next photo in the staged WeCom dir and advance the rotation index. */
    private static String nextPhotoPath(Context app) {
        File dir = PunchStore.photoDir(app);
        File[] fs = PunchStore.photos(app);
        if (fs.length == 0) return null;
        SharedPreferences sp = app.getSharedPreferences("punch", Context.MODE_PRIVATE);
        int idx = sp.getInt("photo_rotate", 0);
        File pick = fs[Math.abs(idx) % fs.length];
        sp.edit().putInt("photo_rotate", idx + 1).apply();
        return "/data/user/0/com.tencent.wework/files/fj_photos/" + pick.getName();
    }

    private static double parseD(String s, double def) {
        try {
            return Double.parseDouble(s.trim());
        } catch (Throwable t) {
            return def;
        }
    }
}
