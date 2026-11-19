package com.fj.wecomloc;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import java.util.Calendar;
import java.util.Random;

/**
 * Schedules the next fire time inside each punch window via AlarmManager.
 *
 * <p>The fired alarm only wakes our own process; it sends the direct-punch
 * broadcast into WeCom, which performs the submission from inside its own
 * process without any UI.
 */
final class PunchScheduler {

    static final String ACTION_PUNCH = "com.fj.wecomloc.PUNCH";

    private PunchScheduler() {
    }

    /** Schedule the next occurrence for "morning" / "evening" / "outside". */
    static int scheduleNext(Context c, String type) {
        boolean en;
        int start;
        int end;
        if ("morning".equals(type)) {
            en = PunchStore.morningEnabled(c);
            start = PunchStore.morningStart(c);
            end = PunchStore.morningEnd(c);
        } else if ("outside".equals(type)) {
            en = PunchStore.outsideEnabled(c);
            start = PunchStore.outsideStart(c);
            end = PunchStore.outsideEnd(c);
        } else {
            en = PunchStore.eveningEnabled(c);
            start = PunchStore.eveningStart(c);
            end = PunchStore.eveningEnd(c);
        }
        if (!en || end <= start) {
            cancel(c, type);
            return -1;
        }
        int fire = start + new Random().nextInt(end - start);
        Calendar cal = Calendar.getInstance();
        cal.set(Calendar.HOUR_OF_DAY, fire / 60);
        cal.set(Calendar.MINUTE, fire % 60);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        if (cal.getTimeInMillis() <= System.currentTimeMillis()) {
            cal.add(Calendar.DAY_OF_YEAR, 1);
        }
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        PendingIntent pi = pending(c, type);
        if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, cal.getTimeInMillis(), pi);
        } else {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, cal.getTimeInMillis(), pi);
        }
        PunchStore.setLastScheduled(c, type, fire);
        return fire;
    }

    static void scheduleAll(Context c) {
        if (PunchStore.morningEnabled(c)) scheduleNext(c, "morning");
        if (PunchStore.eveningEnabled(c)) scheduleNext(c, "evening");
        if (PunchStore.outsideEnabled(c)) scheduleNext(c, "outside");
    }

    static void cancel(Context c, String type) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        am.cancel(pending(c, type));
        PunchStore.setLastScheduled(c, type, -1);
    }

    private static PendingIntent pending(Context c, String type) {
        Intent i = new Intent(c, AlarmReceiver.class);
        i.setAction(ACTION_PUNCH);
        i.putExtra("type", type);
        int req = "morning".equals(type) ? 9001 : ("outside".equals(type) ? 9003 : 9002);
        return PendingIntent.getBroadcast(c, req, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    static String fmt(int minutes) {
        if (minutes < 0) return "未设置";
        return String.format("%02d:%02d", minutes / 60, minutes % 60);
    }
}
