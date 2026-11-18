package com.fj.wecomloc;

import android.app.KeyguardManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

/**
 * Fired by AlarmManager inside the punch window: applies the mock location,
 * pushes photos into WeCom's files dir, then hands off to the accessibility
 * service which drives the UI. Reschedules itself for the next day.
 */
public class AlarmReceiver extends BroadcastReceiver {

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
                    if (PunchStore.photoEnabled(app)) {
                        RootConfig.pushPhotos(app);
                    }
                    wakeScreen();
                    unlockIfNeeded(app);
                    PunchService.begin(type, PunchStore.photoEnabled(app));
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

    private static double parseD(String s, double def) {
        try {
            return Double.parseDouble(s.trim());
        } catch (Throwable t) {
            return def;
        }
    }

    private static void wakeScreen() {
        try {
            Runtime.getRuntime().exec(new String[]{"su", "-c",
                    "input keyevent KEYCODE_WAKEUP; "
                            + "wm dismiss-keyguard; "
                            + "input keyevent 82"}).waitFor();
        } catch (Throwable ignored) {
        }
    }

    /** If a secure lock is still up, swipe up and type the PIN configured in the app. */
    private static void unlockIfNeeded(Context app) {
        try {
            KeyguardManager km = (KeyguardManager) app.getSystemService(Context.KEYGUARD_SERVICE);
            if (km == null || !km.isKeyguardLocked()) return;
            String pin = PunchStore.pin(app);
            if (pin.length() == 0) return;
            String[] cmd = new String[]{"su", "-c",
                    "input keyevent 82; sleep 1; "
                            + "input swipe 640 2200 640 800 300; sleep 1; "
                            + "input text " + pin + "; sleep 1; "
                            + "input keyevent 66"};
            Process p = Runtime.getRuntime().exec(cmd);
            p.waitFor();
            Thread.sleep(1500);
        } catch (Throwable ignored) {
        }
    }
}