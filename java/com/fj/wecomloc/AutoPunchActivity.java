package com.fj.wecomloc;

import android.app.Activity;
import android.app.TimePickerDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;

/**
 * Configuration UI for scheduled punch.
 *
 * <p>Punching is done by the injected WeCom-side module: this activity only
 * stores the schedule, stages photos, and sends
 * {@code com.fj.wecomloc.PUNCH_DIRECT} into WeCom's process, which builds the
 * protobuf and calls {@code AttendanceService} directly. No accessibility
 * service and no UI automation.
 */
public class AutoPunchActivity extends Activity {

    private static final int REQ_PHOTOS = 2001;
    private static final int MAX_PHOTOS = 10;
    private static final int LONG_EDGE = 1280;
    private static final int JPEG_Q = 85;

    private static final String ACTION_PUNCH_DIRECT = "com.fj.wecomloc.PUNCH_DIRECT";
    private static final String ACTION_RESULT = "com.fj.wecomloc.PUNCH_RESULT";

    private TextView mMorningTime;
    private TextView mEveningTime;
    private TextView mOutsideTime;
    private TextView mPhotoInfo;
    private TextView mLog;
    private final Handler mUi = new Handler(Looper.getMainLooper());
    private BroadcastReceiver mResultRx;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        float d = getResources().getDisplayMetrics().density;
        int pad = (int) (16 * d);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("自动打卡配置（直投模式）");
        title.setTextSize(22);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setPadding(0, (int) (12 * d), 0, (int) (8 * d));
        root.addView(title);

        TextView hint = new TextView(this);
        hint.setText("不走模拟点击：模块在企微进程内直接构造打卡报文并调用 AttendanceService 提交。"
                + "需要 LSPosed 作用域已勾选企业微信。");
        hint.setTextSize(12);
        hint.setPadding(0, 0, 0, (int) (12 * d));
        root.addView(hint);

        Switch outside = new Switch(this);
        outside.setText("外出打卡（自动）");
        outside.setChecked(PunchStore.outsideEnabled(this));
        outside.setOnCheckedChangeListener((b, v) -> {
            PunchStore.setOutside(this, v,
                    PunchStore.outsideStart(this), PunchStore.outsideEnd(this));
            if (v) PunchScheduler.scheduleNext(this, "outside");
            else PunchScheduler.cancel(this, "outside");
            updateOutsideText();
        });
        root.addView(outside);

        mOutsideTime = new TextView(this);
        updateOutsideText();
        mOutsideTime.setOnClickListener(v -> pickTime(2));
        root.addView(mOutsideTime);

        Switch morning = new Switch(this);
        morning.setText("上班打卡（自动）");
        morning.setChecked(PunchStore.morningEnabled(this));
        morning.setOnCheckedChangeListener((b, v) -> {
            PunchStore.setMorning(this, v,
                    PunchStore.morningStart(this), PunchStore.morningEnd(this));
            if (v) PunchScheduler.scheduleNext(this, "morning");
            else PunchScheduler.cancel(this, "morning");
        });
        root.addView(morning);

        mMorningTime = new TextView(this);
        updateMorningText();
        mMorningTime.setOnClickListener(v -> pickTime(0));
        root.addView(mMorningTime);

        Switch evening = new Switch(this);
        evening.setText("下班打卡（自动）");
        evening.setChecked(PunchStore.eveningEnabled(this));
        evening.setOnCheckedChangeListener((b, v) -> {
            PunchStore.setEvening(this, v,
                    PunchStore.eveningStart(this), PunchStore.eveningEnd(this));
            if (v) PunchScheduler.scheduleNext(this, "evening");
            else PunchScheduler.cancel(this, "evening");
        });
        root.addView(evening);

        mEveningTime = new TextView(this);
        updateEveningText();
        mEveningTime.setOnClickListener(v -> pickTime(1));
        root.addView(mEveningTime);

        Switch photoSw = new Switch(this);
        photoSw.setText("打卡照片轮换（外出打卡用）");
        photoSw.setChecked(PunchStore.photoEnabled(this));
        photoSw.setOnCheckedChangeListener((b, v) -> PunchStore.setPhotoEnabled(this, v));
        root.addView(photoSw);

        mPhotoInfo = new TextView(this);
        updatePhotoInfo();
        root.addView(mPhotoInfo);

        Button pick = new Button(this);
        pick.setText("选择打卡照片（最多10张）");
        pick.setOnClickListener(v -> {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("image/*");
            i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
            startActivityForResult(i, REQ_PHOTOS);
        });
        root.addView(pick);

        Button clear = new Button(this);
        clear.setText("清空照片");
        clear.setOnClickListener(v -> {
            File[] old = PunchStore.photos(this);
            if (old != null) for (File f : old) f.delete();
            updatePhotoInfo();
        });
        root.addView(clear);

        Button test = new Button(this);
        test.setText("立即测试：直投打卡");
        test.setOnClickListener(v -> testPunch());
        root.addView(test);

        Button sched = new Button(this);
        sched.setText("测试定时链路（10秒后触发，仅验证不提交）");
        sched.setOnClickListener(v -> {
            Intent i = new Intent(this, AlarmReceiver.class);
            i.setAction(AlarmReceiver.ACTION_TEST_SCHEDULE);
            i.putExtra("delay", 10);
            i.putExtra("outside", outsideEnabled());
            i.putExtra("dry", true);
            sendBroadcast(i);
            Toast.makeText(this, "已排定 10 秒后触发（dry），等 20 秒看日志", Toast.LENGTH_SHORT).show();
            mUi.postDelayed(() -> mLog.setText(readLog()), 20000);
        });
        root.addView(sched);

        Button openWecom = new Button(this);
        openWecom.setText("打开企业微信");
        openWecom.setOnClickListener(v -> {
            Intent i = getPackageManager().getLaunchIntentForPackage("com.tencent.wework");
            if (i != null) startActivity(i);
            else Toast.makeText(this, "未安装企业微信", Toast.LENGTH_SHORT).show();
        });
        root.addView(openWecom);

        TextView logTitle = new TextView(this);
        logTitle.setText("直投日志");
        logTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        logTitle.setPadding(0, (int) (16 * d), 0, (int) (4 * d));
        root.addView(logTitle);

        mLog = new TextView(this);
        mLog.setTextSize(11);
        mLog.setText(readLog());
        root.addView(mLog);

        Button refresh = new Button(this);
        refresh.setText("刷新日志");
        refresh.setOnClickListener(v -> mLog.setText(readLog()));
        root.addView(refresh);

        Button clearLog = new Button(this);
        clearLog.setText("清空日志");
        clearLog.setOnClickListener(v -> {
            logFile().delete();
            mLog.setText("");
        });
        root.addView(clearLog);

        ScrollView sc = new ScrollView(this);
        sc.addView(root);
        setContentView(sc);

        mResultRx = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (intent == null || !ACTION_RESULT.equals(intent.getAction())) return;
                String msg = intent.getStringExtra("msg");
                Toast.makeText(AutoPunchActivity.this,
                        msg == null ? "" : msg, Toast.LENGTH_SHORT).show();
                mLog.setText(readLog());
            }
        };
        IntentFilter f = new IntentFilter(ACTION_RESULT);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(mResultRx, f, Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(mResultRx, f);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (mResultRx != null) {
            try {
                unregisterReceiver(mResultRx);
            } catch (Throwable ignored) {
            }
            mResultRx = null;
        }
    }

    // ---------------------------------------------------------------- actions

    private void testPunch() {
        if (PunchStore.photoEnabled(this)) {
            RootConfig.pushPhotos(this);
        }
        sendDirect("manual");
        Toast.makeText(this, "已下发直投打卡，看下方日志", Toast.LENGTH_SHORT).show();
        mUi.postDelayed(() -> mLog.setText(readLog()), 2500);
        mUi.postDelayed(() -> mLog.setText(readLog()), 6000);
    }

    private void sendDirect(String type) {
        SharedPreferences sp = getSharedPreferences("ui", MODE_PRIVATE);
        boolean enabled = sp.getBoolean("enabled", true);
        double lat = parseD(sp.getString("lat", "39.908722"), 39.908722d);
        double lng = parseD(sp.getString("lng", "116.397499"), 116.397499d);
        String name = sp.getString("name", "");
        String addr = sp.getString("addr", "");

        Intent loc = new Intent("com.fj.wecomloc.SET");
        loc.setPackage("com.tencent.wework");
        loc.putExtra("enabled", enabled);
        loc.putExtra("lat", lat);
        loc.putExtra("lng", lng);
        if (name.length() > 0) loc.putExtra("name", name);
        if (addr.length() > 0) loc.putExtra("addr", addr);
        try {
            sendBroadcast(loc);
        } catch (Throwable ignored) {
        }

        String photo = null;
        if (PunchStore.photoEnabled(this)) {
            File[] fs = PunchStore.photos(this);
            if (fs.length > 0) {
                int idx = PunchStore.photoRotate(this);
                photo = "/data/user/0/com.tencent.wework/files/fj_photos/"
                        + fs[Math.abs(idx) % fs.length].getName();
                PunchStore.setPhotoRotate(this, idx + 1);
            }
        }

        Intent i = new Intent(ACTION_PUNCH_DIRECT);
        i.setPackage("com.tencent.wework");
        // 测试按钮固定打「外出打卡」，不碰上下班卡
        i.putExtra("outside", "manual".equals(type) ? true : outsideEnabled());
        i.putExtra("lat", lat);
        i.putExtra("lng", lng);
        i.putExtra("name", name);
        i.putExtra("addr", addr);
        i.putExtra("type", type);
        if (photo != null) i.putExtra("photo", photo);
        try {
            sendBroadcast(i);
        } catch (Throwable ignored) {
        }
    }

    private boolean outsideEnabled() {
        return PunchStore.outsideEnabled(this);
    }

    private static double parseD(String s, double def) {
        try {
            return Double.parseDouble(s.trim());
        } catch (Throwable t) {
            return def;
        }
    }

    // ------------------------------------------------------------------- log

    private File logFile() {
        return new File("/data/data/com.tencent.wework/files/fj_punch_direct.log");
    }

    private String readLog() {
        File f = logFile();
        if (!f.exists()) {
            return "(暂无日志。企微进程内模块还没写过东西——先确认 LSPosed 作用域已勾选企业微信，"
                    + "再手动打开一次企业微信让模块加载。)";
        }
        BufferedReader r = null;
        StringBuilder sb = new StringBuilder();
        try {
            r = new BufferedReader(new InputStreamReader(
                    new FileInputStream(f), StandardCharsets.UTF_8));
            String line;
            while ((line = r.readLine()) != null) {
                sb.append(line).append('\n');
            }
        } catch (Throwable t) {
            return "读取日志失败: " + t;
        } finally {
            if (r != null) try {
                r.close();
            } catch (Throwable ignored) {
            }
        }
        String all = sb.toString();
        if (all.length() > 4000) {
            all = all.substring(all.length() - 4000);
        }
        return all;
    }

    // ------------------------------------------------------------------ time

    private void pickTime(int which) {
        int cur = which == 0 ? PunchStore.morningStart(this)
                : which == 1 ? PunchStore.eveningStart(this) : PunchStore.outsideStart(this);
        int curEnd = which == 0 ? PunchStore.morningEnd(this)
                : which == 1 ? PunchStore.eveningEnd(this) : PunchStore.outsideEnd(this);
        new TimePickerDialog(this, (tp, h, m) -> {
            final int start = h * 60 + m;
            new TimePickerDialog(this, (tp2, h2, m2) -> {
                int end = h2 * 60 + m2;
                if (end <= start) {
                    Toast.makeText(this, "结束时间必须晚于开始时间", Toast.LENGTH_SHORT).show();
                    return;
                }
                if (which == 0) {
                    PunchStore.setMorning(this, PunchStore.morningEnabled(this), start, end);
                    PunchScheduler.scheduleNext(this, "morning");
                } else if (which == 1) {
                    PunchStore.setEvening(this, PunchStore.eveningEnabled(this), start, end);
                    PunchScheduler.scheduleNext(this, "evening");
                } else {
                    PunchStore.setOutside(this, PunchStore.outsideEnabled(this), start, end);
                    PunchScheduler.scheduleNext(this, "outside");
                }
                updateMorningText();
                updateEveningText();
                updateOutsideText();
            }, curEnd / 60, curEnd % 60, true).show();
        }, cur / 60, cur % 60, true).show();
    }

    private void updateOutsideText() {
        mOutsideTime.setText("外出窗口："
                + PunchScheduler.fmt(PunchStore.outsideStart(this)) + " ~ "
                + PunchScheduler.fmt(PunchStore.outsideEnd(this))
                + "   下次=" + PunchScheduler.fmt(PunchStore.lastScheduled(this, "outside")));
    }

    private void updateMorningText() {
        mMorningTime.setText("上班窗口：" 
                + PunchScheduler.fmt(PunchStore.morningStart(this)) + " ~ "
                + PunchScheduler.fmt(PunchStore.morningEnd(this))
                + "   下次=" + PunchScheduler.fmt(PunchStore.lastScheduled(this, "morning")));
    }

    private void updateEveningText() {
        mEveningTime.setText("下班窗口：" 
                + PunchScheduler.fmt(PunchStore.eveningStart(this)) + " ~ "
                + PunchScheduler.fmt(PunchStore.eveningEnd(this))
                + "   下次=" + PunchScheduler.fmt(PunchStore.lastScheduled(this, "evening")));
    }

    private void updatePhotoInfo() {
        File[] fs = PunchStore.photos(this);
        mPhotoInfo.setText("已选照片：" + (fs == null ? 0 : fs.length));
    }

    // --------------------------------------------------------------- picking

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_PHOTOS || resultCode != RESULT_OK || data == null) return;

        ArrayList<Uri> uris = new ArrayList<>();
        if (data.getClipData() != null) {
            int cnt = data.getClipData().getItemCount();
            for (int i = 0; i < cnt && uris.size() < MAX_PHOTOS; i++) {
                uris.add(data.getClipData().getItemAt(i).getUri());
            }
        } else if (data.getData() != null) {
            uris.add(data.getData());
        }

        File dir = PunchStore.photoDir(this);
        dir.mkdirs();
        File[] old = PunchStore.photos(this);
        if (old != null) for (File f : old) f.delete();

        int n = 0;
        for (Uri u : uris) {
            File out = new File(dir, "photo_" + n + ".jpg");
            if (saveScaled(u, out)) n++;
        }
        Toast.makeText(this, "已保存 " + n, Toast.LENGTH_SHORT).show();
        updatePhotoInfo();
    }

    private boolean saveScaled(Uri u, File out) {
        InputStream in = null;
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            in = getContentResolver().openInputStream(u);
            BitmapFactory.decodeStream(in, null, bounds);
            if (in != null) in.close();

            int sample = 1;
            while (Math.max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= LONG_EDGE) {
                sample *= 2;
            }
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sample;
            in = getContentResolver().openInputStream(u);
            Bitmap bm = BitmapFactory.decodeStream(in, null, opts);
            if (in != null) in.close();
            if (bm == null) return false;

            FileOutputStream fo = new FileOutputStream(out);
            bm.compress(Bitmap.CompressFormat.JPEG, JPEG_Q, fo);
            fo.close();
            return true;
        } catch (Throwable t) {
            return false;
        }
    }
}
