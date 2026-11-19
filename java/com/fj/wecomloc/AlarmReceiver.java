package com.fj.wecomloc;

import android.app.AlarmManager;
import android.app.PendingIntent;
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

    /** Test hook: schedule a direct punch {@code delay} seconds from now. */
    static final String ACTION_TEST_SCHEDULE = "com.fj.wecomloc.SCHEDULE_TEST";

    /** Re-arm every enabled window right away (used after editing prefs / reinstalling). */
    static final String ACTION_RESCHEDULE = "com.fj.wecomloc.RESCHEDULE";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        final Context app = context.getApplicationContext();
        final String action = intent.getAction();

        if (ACTION_TEST_SCHEDULE.equals(action)) {
            final int delay = intent.getIntExtra("delay", 60);
            final boolean outside = intent.getBooleanExtra("outside", true);
            final boolean dry = intent.getBooleanExtra("dry", false);
            AlarmManager am = (AlarmManager) app.getSystemService(Context.ALARM_SERVICE);
            Intent i = new Intent(app, AlarmReceiver.class);
            i.setAction(PunchScheduler.ACTION_PUNCH);
            i.putExtra("type", "test:" + (outside ? "out" : "in"));
            i.putExtra("dry", dry);
            PendingIntent pi = PendingIntent.getBroadcast(app, 9100, i,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            long at = System.currentTimeMillis() + Math.max(5, delay) * 1000L;
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
            result(app, "测试定时已排定: " + Math.max(5, delay) + " 秒后触发"
                    + (dry ? "（dry：只验证链路不提交）" : ""));
            return;
        }

        if (ACTION_RESCHEDULE.equals(action)) {
            PunchScheduler.scheduleAll(app);
            result(app, "已重排: 上班=" + PunchScheduler.fmt(PunchStore.lastScheduled(app, "morning"))
                    + " 下班=" + PunchScheduler.fmt(PunchStore.lastScheduled(app, "evening"))
                    + " 外出=" + PunchScheduler.fmt(PunchStore.lastScheduled(app, "outside")));
            return;
        }

        if (!PunchScheduler.ACTION_PUNCH.equals(action)) return;
        final String type = intent.getStringExtra("type");
        final boolean dry = intent.getBooleanExtra("dry", false);
        // morning/evening are 上下班打卡 (binary); "outside" and manual/test are 外出打卡.
        final boolean forcedOutside = "test:out".equals(type) ? true
                : "test:in".equals(type) ? false
                : !("morning".equals(type) || "evening".equals(type));
        final boolean wantPhoto = "outside".equals(type) || (type != null && type.startsWith("test:out"));

        final PendingResult result = goAsync();
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    ensureWeCom(app);
                    applyLocation(app);
                    // Hand over the *source* paths only. The copy into WeCom's
                    // private dir must happen inside the WeCom process (see
                    // PunchDirect.stageImage), otherwise the file ends up owned
                    // by another uid and the server answers 901.
                    String photo = null;
                    if (wantPhoto && PunchStore.photoEnabled(app)) {
                        photo = pickNext(app, PunchStore.photos(app), "photo_rotate");
                    }
                    // 上下班打卡也会要人脸，所以 face 对所有类型都准备
                    String face = null;
                    if (PunchStore.faceEnabled(app)) {
                        face = pickNext(app, PunchStore.faces(app), "face_rotate");
                    }
                    sendDirect(app, type, photo, face, forcedOutside, dry);
                    if (type == null || !type.startsWith("test:")) {
                        PunchScheduler.scheduleNext(app, type);
                    }
                } catch (Throwable ignored) {
                } finally {
                    result.finish();
                }
            }
        }, "fj-punch").start();
    }

    /**
     * The injected module only exists inside a live WeCom process; after a
     * reboot or a long idle period the app may be gone, so the direct-punch
     * broadcast would be dropped. Pull it up first when needed.
     */
    private static void ensureWeCom(Context app) {
        try {
            if (RootConfig.run("pidof com.tencent.wework > /dev/null 2>&1", 8) == 0) return;
            RootConfig.run("am start -n com.tencent.wework/com.tencent.wework.launch.WwMainActivity", 15);
            Thread.sleep(9000L);
        } catch (Throwable ignored) {
        }
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

    /** Ask WeCom's injected module to perform the check-in. */
    private static void sendDirect(Context app, String type, String photoPath,
                                   String facePath, boolean outside, boolean dry) {
        SharedPreferences sp = app.getSharedPreferences("ui", Context.MODE_PRIVATE);
        double lat = parseD(sp.getString("lat", "39.908722"), 39.908722d);
        double lng = parseD(sp.getString("lng", "116.397499"), 116.397499d);
        String name = sp.getString("name", "");
        String addr = sp.getString("addr", "");
        Intent i = new Intent(ACTION_PUNCH_DIRECT);
        i.setPackage("com.tencent.wework");
        i.putExtra("outside", outside);
        i.putExtra("lat", lat);
        i.putExtra("lng", lng);
        i.putExtra("name", name);
        i.putExtra("addr", addr);
        i.putExtra("type", type == null ? "" : type);
        if (dry) i.putExtra("dry", true);
        if (photoPath != null && photoPath.length() > 0) {
            i.putExtra("photo", photoPath);
        }
        if (facePath != null && facePath.length() > 0) {
            i.putExtra("facephoto", facePath);
        }
        try {
            app.sendBroadcast(i);
            result(app, "直投广播已发出: type=" + type + " outside=" + outside
                    + (photoPath == null ? "" : " photo=" + photoPath)
                    + (facePath == null ? "" : " face=" + facePath));
        } catch (Throwable t) {
            result(app, "直投广播失败: " + t);
        }
    }

    private static boolean isOutside(Context app) {
        return PunchStore.outsideEnabled(app);
    }

    private static void result(Context app, String msg) {
        try {
            Intent r = new Intent("com.fj.wecomloc.PUNCH_RESULT");
            r.setPackage("com.fj.wecomloc");
            r.putExtra("msg", msg);
            r.putExtra("ts", System.currentTimeMillis());
            app.sendBroadcast(r);
        } catch (Throwable ignored) {
        }
    }

    /** Advance a rotation cursor and pick one of the local (source) paths. */
    private static String pickNext(Context app, java.io.File[] files, String cursorKey) {
        if (files == null || files.length == 0) return null;
        SharedPreferences sp = app.getSharedPreferences("punch", Context.MODE_PRIVATE);
        int idx = sp.getInt(cursorKey, 0);
        String pick = files[Math.abs(idx) % files.length].getAbsolutePath();
        sp.edit().putInt(cursorKey, idx + 1).apply();
        return pick;
    }

    private static double parseD(String s, double def) {
        try {
            return Double.parseDouble(s.trim());
        } catch (Throwable t) {
            return def;
        }
    }
}
