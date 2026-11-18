package com.fj.wecomloc;

import android.app.Activity;
import android.app.TimePickerDialog;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;

/**
 * Configuration UI for scheduled punch: morning/evening windows, punch photos,
 * photo rotation switch and manual test.
 */
public class AutoPunchActivity extends Activity {

    private static final int REQ_PHOTOS = 2001;
    private static final int MAX_PHOTOS = 10;
    private static final int LONG_EDGE = 1280;
    private static final int JPEG_Q = 85;

    private TextView mMorningTime;
    private TextView mEveningTime;
    private TextView mPhotoInfo;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("自动打卡配置");
        title.setTextSize(22);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setPadding(0, (int) (12 * getResources().getDisplayMetrics().density), 0, (int) (12 * getResources().getDisplayMetrics().density));
        root.addView(title);

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
        mMorningTime.setOnClickListener(v -> pickTime(true));
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
        mEveningTime.setOnClickListener(v -> pickTime(false));
        root.addView(mEveningTime);

        Switch photoSw = new Switch(this);
        photoSw.setText("打卡照片轮换（拍照打卡时替换）");
        photoSw.setChecked(PunchStore.photoEnabled(this));
        photoSw.setOnCheckedChangeListener((b, v) -> PunchStore.setPhotoEnabled(this, v));
        root.addView(photoSw);

        final android.widget.EditText pin = new android.widget.EditText(this);
        pin.setHint("锁屏PIN码（有密码锁屏时自动解锁打卡）");
        pin.setText(PunchStore.pin(this));
        pin.setInputType(android.text.InputType.TYPE_CLASS_NUMBER
                | android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        root.addView(pin);

        Button savePin = new Button(this);
        savePin.setText("保存解锁PIN");
        savePin.setOnClickListener(v -> {
            PunchStore.setPin(this, pin.getText().toString());
            Toast.makeText(this, "解锁PIN已保存", Toast.LENGTH_SHORT).show();
        });
        root.addView(savePin);

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

        Button testM = new Button(this);
        testM.setText("立即测试：上班打卡");
        testM.setOnClickListener(v -> testPunch("morning"));
        root.addView(testM);

        Button testE = new Button(this);
        testE.setText("立即测试：下班打卡");
        testE.setOnClickListener(v -> testPunch("evening"));
        root.addView(testE);

        Button acc = new Button(this);
        acc.setText("打开无障碍设置（必须开启本模块服务）");
        acc.setOnClickListener(v ->
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        root.addView(acc);

        ScrollView sc = new ScrollView(this);
        sc.addView(root);
        setContentView(sc);
    }

    private void testPunch(String type) {
        if (PunchService.inst() == null) {
            Toast.makeText(this, "请先在无障碍设置里开启 WeComLoc 服务", Toast.LENGTH_LONG).show();
            return;
        }
        if (PunchStore.photoEnabled(this)) RootConfig.pushPhotos(this);
        PunchService.begin(type, PunchStore.photoEnabled(this));
        Toast.makeText(this, "已开始，请勿操作屏幕直到打卡完成", Toast.LENGTH_LONG).show();
    }

    private void pickTime(boolean morning) {
        int cur = morning ? PunchStore.morningStart(this) : PunchStore.eveningStart(this);
        int curEnd = morning ? PunchStore.morningEnd(this) : PunchStore.eveningEnd(this);
        new TimePickerDialog(this, (tp, h, m) -> {
            int start = h * 60 + m;
            new TimePickerDialog(this, (tp2, h2, m2) -> {
                int end = h2 * 60 + m2;
                if (end <= start) {
                    Toast.makeText(this, "结束时间必须晚于开始时间", Toast.LENGTH_SHORT).show();
                    return;
                }
                if (morning) {
                    PunchStore.setMorning(this, PunchStore.morningEnabled(this), start, end);
                } else {
                    PunchStore.setEvening(this, PunchStore.eveningEnabled(this), start, end);
                }
                PunchScheduler.scheduleNext(this, morning ? "morning" : "evening");
                updateMorningText();
                updateEveningText();
            }, curEnd / 60, curEnd % 60, true).show();
        }, cur / 60, cur % 60, true).show();
    }

    private void updateMorningText() {
        mMorningTime.setText("上班窗口："
                + PunchScheduler.fmt(PunchStore.morningStart(this)) + " ~ "
                + PunchScheduler.fmt(PunchStore.morningEnd(this))
                + "  next=" + PunchScheduler.fmt(PunchStore.lastScheduled(this, "morning")));
    }

    private void updateEveningText() {
        mEveningTime.setText("下班窗口："
                + PunchScheduler.fmt(PunchStore.eveningStart(this)) + " ~ "
                + PunchScheduler.fmt(PunchStore.eveningEnd(this))
                + "  next=" + PunchScheduler.fmt(PunchStore.lastScheduled(this, "evening")));
    }

    private void updatePhotoInfo() {
        File[] fs = PunchStore.photos(this);
        mPhotoInfo.setText("已选照片：" + (fs == null ? 0 : fs.length));
    }

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