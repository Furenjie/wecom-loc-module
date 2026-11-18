package com.fj.wecomloc;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;
import java.util.UUID;

public class MainActivity extends Activity {

    private static final String PKG = "com.tencent.wework";
    private static final String ACTION_SET = "com.fj.wecomloc.SET";
    private static final String ACTION_ACK = "com.fj.wecomloc.ACK";
    private static final int REQ_PICK = 1001;

    private EditText latEdit;
    private EditText lngEdit;
    private EditText nameEdit;
    private EditText addrEdit;
    private Switch enableSwitch;
    private TextView status;
    private Button applyButton;
    private LinearLayout favoriteBar;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private BroadcastReceiver ackReceiver;
    private String pendingToken;
    private volatile boolean ackDone;
    private volatile boolean rootOk;
    private Runnable fallback;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle("WeCom 定位模块");
        registerAckReceiver();
        buildUi();
        loadPrefs();
        refreshFavorites();
    }

    @Override
    protected void onDestroy() {
        ui.removeCallbacksAndMessages(null);
        if (ackReceiver != null) {
            try { unregisterReceiver(ackReceiver); } catch (Throwable ignored) {}
        }
        super.onDestroy();
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.rgb(248, 249, 251));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(20), dp(20), dp(28));
        scroll.addView(root, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView title = new TextView(this);
        title.setText("企业微信打卡定位");
        title.setTextSize(22);
        title.setTextColor(Color.rgb(24, 28, 36));
        title.setGravity(Gravity.START);
        root.addView(title, lp(-2, -2, 0, 4));

        TextView desc = new TextView(this);
        desc.setText("LSPosed 模块入口：修改企业微信打卡/外出打卡使用的位置。\n"
                + "先打开一次企业微信，再回到这里点“写入并应用”。");
        desc.setTextSize(13);
        desc.setTextColor(Color.rgb(92, 100, 112));
        desc.setLineSpacing(dp(3), 1f);
        root.addView(desc, lp(-1, -2, 0, 14));

        enableSwitch = new Switch(this);
        enableSwitch.setText("启用模拟定位");
        enableSwitch.setTextSize(16);
        enableSwitch.setChecked(true);
        root.addView(enableSwitch, lp(-1, -2, 0, 12));

        TextView favTitle = new TextView(this);
        favTitle.setText("常用地点（点一下直接切换，长按删除）");
        favTitle.setTextSize(13);
        favTitle.setTextColor(Color.rgb(70, 76, 88));
        root.addView(favTitle, lp(-1, -2, 0, 4));

        HorizontalScrollView hsv = new HorizontalScrollView(this);
        hsv.setHorizontalScrollBarEnabled(false);
        favoriteBar = new LinearLayout(this);
        favoriteBar.setOrientation(LinearLayout.HORIZONTAL);
        favoriteBar.setGravity(Gravity.CENTER_VERTICAL);
        hsv.addView(favoriteBar, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(48)));
        root.addView(hsv, lp(-1, dp(50), 0, 6));

        LinearLayout favActions = new LinearLayout(this);
        favActions.setOrientation(LinearLayout.HORIZONTAL);
        root.addView(favActions, lp(-1, dp(44), 0, 10));

        Button addFav = new Button(this);
        addFav.setText("收藏当前");
        addFav.setAllCaps(false);
        addFav.setTextSize(13);
        favActions.addView(addFav, new LinearLayout.LayoutParams(0, dp(44), 1f));
        addFav.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { addFavorite(); }
        });

        Button pick = new Button(this);
        pick.setText("腾讯地图选点");
        pick.setAllCaps(false);
        pick.setTextSize(13);
        favActions.addView(pick, new LinearLayout.LayoutParams(0, dp(44), 1f));
        pick.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { pickOnMap(); }
        });

        latEdit = addField(root, "纬度 latitude", "39.908722",
                InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL
                        | InputType.TYPE_NUMBER_FLAG_SIGNED);
        lngEdit = addField(root, "经度 longitude", "116.397499",
                InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL
                        | InputType.TYPE_NUMBER_FLAG_SIGNED);
        nameEdit = addField(root, "POI 名称 name", "东长安街", InputType.TYPE_CLASS_TEXT);
        addrEdit = addField(root, "详细地址 address", "北京市东城区东长安街", InputType.TYPE_CLASS_TEXT);

        applyButton = new Button(this);
        applyButton.setText("写入并应用");
        applyButton.setAllCaps(false);
        applyButton.setTextSize(16);
        root.addView(applyButton, lp(-1, dp(48), 0, 10));
        applyButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { apply(); }
        });

        Button open = new Button(this);
        open.setText("打开企业微信");
        open.setAllCaps(false);
        open.setTextSize(15);
        root.addView(open, lp(-1, dp(44), 0, 12));
        open.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { openWeCom(); }
        });

        status = new TextView(this);
        status.setTextSize(13);
        status.setTextColor(Color.rgb(46, 125, 50));
        status.setText("等待应用。模块需在 LSPosed 中启用并勾选企业微信。");
        status.setLineSpacing(dp(3), 1f);
        root.addView(status, lp(-1, -2, 0, 0));

        TextView tip = new TextView(this);
        tip.setText("\n提示：切换常用地点会立即发送一次 mock；成功时会显示“Mock 成功”。");
        tip.setTextSize(12);
        tip.setTextColor(Color.rgb(120, 126, 136));
        root.addView(tip, lp(-1, -2, 0, 0));

        setContentView(scroll);
    }

    private LinearLayout.LayoutParams lp(int w, int h, int top, int bottom) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h);
        p.topMargin = dp(top);
        p.bottomMargin = dp(bottom);
        return p;
    }

    private EditText addField(LinearLayout root, String label, String hint, int inputType) {
        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setTextSize(13);
        tv.setTextColor(Color.rgb(70, 76, 88));
        root.addView(tv, lp(-1, -2, 8, 4));

        EditText et = new EditText(this);
        et.setHint(hint);
        et.setTextSize(16);
        et.setSingleLine(true);
        et.setInputType(inputType);
        et.setPadding(dp(12), dp(8), dp(12), dp(8));
        et.setBackgroundColor(Color.WHITE);
        root.addView(et, lp(-1, dp(46), 0, 4));
        return et;
    }

    private void loadPrefs() {
        SharedPreferences sp = getSharedPreferences("ui", MODE_PRIVATE);
        latEdit.setText(sp.getString("lat", "39.908722"));
        lngEdit.setText(sp.getString("lng", "116.397499"));
        nameEdit.setText(sp.getString("name", "东长安街"));
        addrEdit.setText(sp.getString("addr", "北京市东城区东长安街"));
        enableSwitch.setChecked(sp.getBoolean("enabled", true));
    }

    private void savePrefs(String lat, String lng, String name, String addr, boolean enabled) {
        getSharedPreferences("ui", MODE_PRIVATE).edit()
                .putString("lat", lat)
                .putString("lng", lng)
                .putString("name", name)
                .putString("addr", addr)
                .putBoolean("enabled", enabled)
                .apply();
    }

    private void refreshFavorites() {
        favoriteBar.removeAllViews();
        List<FavoriteStore.Item> items = FavoriteStore.load(this);
        if (items.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("还没有收藏，点“腾讯地图选点”或填好后“收藏当前”");
            empty.setTextSize(12);
            empty.setTextColor(Color.rgb(130, 136, 146));
            favoriteBar.addView(empty, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            return;
        }
        for (int i = 0; i < items.size(); i++) {
            final FavoriteStore.Item item = items.get(i);
            Button b = new Button(this);
            b.setText(item.name);
            b.setAllCaps(false);
            b.setTextSize(13);
            b.setSingleLine(true);
            b.setPadding(dp(14), 0, dp(14), 0);
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, dp(42));
            p.rightMargin = dp(8);
            favoriteBar.addView(b, p);
            b.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    latEdit.setText(String.valueOf(item.lat));
                    lngEdit.setText(String.valueOf(item.lng));
                    nameEdit.setText(item.name);
                    addrEdit.setText(item.addr);
                    Toast.makeText(MainActivity.this, "切换到：" + item.name, Toast.LENGTH_SHORT).show();
                    apply();
                }
            });
            b.setOnLongClickListener(new View.OnLongClickListener() {
                @Override public boolean onLongClick(View v) {
                    confirmDelete(item);
                    return true;
                }
            });
        }
    }

    private void confirmDelete(final FavoriteStore.Item item) {
        new AlertDialog.Builder(this)
                .setTitle("删除常用地点")
                .setMessage("确定删除“" + item.name + "”吗？")
                .setNegativeButton("取消", null)
                .setPositiveButton("删除", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) {
                        List<FavoriteStore.Item> items = FavoriteStore.load(MainActivity.this);
                        for (int i = items.size() - 1; i >= 0; i--) {
                            FavoriteStore.Item it = items.get(i);
                            if (it.name.equals(item.name)
                                    && Math.abs(it.lat - item.lat) < 0.000001
                                    && Math.abs(it.lng - item.lng) < 0.000001) {
                                items.remove(i);
                            }
                        }
                        FavoriteStore.save(MainActivity.this, items);
                        refreshFavorites();
                    }
                }).show();
    }

    private double[] readPoint() {
        String latS = latEdit.getText().toString().trim();
        String lngS = lngEdit.getText().toString().trim();
        double lat;
        double lng;
        try {
            lat = Double.parseDouble(latS);
            lng = Double.parseDouble(lngS);
        } catch (Throwable t) {
            Toast.makeText(this, "经纬度必须是数字", Toast.LENGTH_SHORT).show();
            return null;
        }
        if (lat < -90 || lat > 90 || lng < -180 || lng > 180) {
            Toast.makeText(this, "经纬度超出范围", Toast.LENGTH_SHORT).show();
            return null;
        }
        return new double[]{lat, lng};
    }

    private void addFavorite() {
        double[] ll = readPoint();
        if (ll == null) return;
        String name = nameEdit.getText().toString().trim();
        String addr = addrEdit.getText().toString().trim();
        if (name.isEmpty()) name = addr;
        if (name.isEmpty()) {
            Toast.makeText(this, "先填名称或地址", Toast.LENGTH_SHORT).show();
            return;
        }
        FavoriteStore.add(this, new FavoriteStore.Item(name, addr, ll[0], ll[1]));
        refreshFavorites();
        Toast.makeText(this, "已收藏：" + name, Toast.LENGTH_SHORT).show();
    }

    private void pickOnMap() {
        Intent i = new Intent(this, PlacePickerActivity.class);
        String q = nameEdit.getText().toString().trim();
        if (!q.isEmpty()) i.putExtra("q", q);
        startActivityForResult(i, REQ_PICK);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_PICK || resultCode != RESULT_OK || data == null) return;
        double lat = data.getDoubleExtra("lat", 0);
        double lng = data.getDoubleExtra("lng", 0);
        String name = data.getStringExtra("name");
        String addr = data.getStringExtra("addr");
        if (lat == 0 || lng == 0) return;
        if (name == null || name.trim().isEmpty()) name = "腾讯地图选点";
        if (addr == null) addr = "";
        latEdit.setText(String.valueOf(lat));
        lngEdit.setText(String.valueOf(lng));
        nameEdit.setText(name);
        addrEdit.setText(addr);
        FavoriteStore.add(this, new FavoriteStore.Item(name, addr, lat, lng));
        refreshFavorites();
        Toast.makeText(this, "已收藏并切换：" + name, Toast.LENGTH_SHORT).show();
        apply();
    }

    private void registerAckReceiver() {
        ackReceiver = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                if (intent == null || !ACTION_ACK.equals(intent.getAction())) return;
                String token = intent.getStringExtra("token");
                if (pendingToken == null || !pendingToken.equals(token)) return;
                onMockAck(intent);
            }
        };
        IntentFilter filter = new IntentFilter(ACTION_ACK);
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(ackReceiver, filter, Context.RECEIVER_EXPORTED);
            } else {
                registerReceiver(ackReceiver, filter);
            }
        } catch (Throwable ignored) {
        }
    }

    private void onMockAck(Intent intent) {
        ackDone = true;
        if (fallback != null) ui.removeCallbacks(fallback);
        applyButton.setEnabled(true);
        double lat = intent.getDoubleExtra("lat", 0);
        double lng = intent.getDoubleExtra("lng", 0);
        String name = intent.getStringExtra("name");
        if (name == null) name = "";
        status.setTextColor(Color.rgb(46, 125, 50));
        status.setText("Mock 成功：企业微信已接收当前定位。\n"
                + lat + ", " + lng + (name.isEmpty() ? "" : "  " + name));
        Toast.makeText(this, "Mock 成功", Toast.LENGTH_SHORT).show();
    }

    private void apply() {
        final double[] ll = readPoint();
        if (ll == null) return;
        final double lat = ll[0];
        final double lng = ll[1];
        final String name = nameEdit.getText().toString().trim();
        final String addr = addrEdit.getText().toString().trim();
        final boolean enabled = enableSwitch.isChecked();
        if (name.isEmpty() && addr.isEmpty()) {
            Toast.makeText(this, "名称和地址至少填一个", Toast.LENGTH_SHORT).show();
            return;
        }

        final String latS = String.valueOf(lat);
        final String lngS = String.valueOf(lng);
        savePrefs(latS, lngS, name, addr, enabled);

        final String cfg = "enabled=" + (enabled ? "1" : "0") + "\n"
                + "lat=" + lat + "\n"
                + "lng=" + lng + "\n"
                + "name=" + name + "\n"
                + "addr=" + addr + "\n";

        pendingToken = UUID.randomUUID().toString();
        ackDone = false;
        rootOk = false;
        applyButton.setEnabled(false);
        status.setTextColor(Color.rgb(120, 90, 20));
        status.setText("正在通知企业微信...");

        fallback = new Runnable() {
            @Override public void run() {
                if (ackDone) return;
                applyButton.setEnabled(true);
                if (rootOk) {
                    status.setTextColor(Color.rgb(180, 120, 20));
                    status.setText("配置已写入，但没收到企业微信确认。\n"
                            + "打开企业微信后会自动生效；若已在运行，请强停后重开。");
                } else {
                    status.setTextColor(Color.rgb(180, 120, 20));
                    status.setText("广播已发送，但没收到企业微信确认。\n"
                            + "请先打开企业微信，再回来点一次“写入并应用”。");
                }
            }
        };
        ui.removeCallbacks(fallback);
        ui.postDelayed(fallback, 3000);

        Intent i = new Intent(ACTION_SET);
        i.setPackage(PKG);
        i.putExtra("enabled", enabled);
        i.putExtra("lat", lat);
        i.putExtra("lng", lng);
        i.putExtra("name", name);
        i.putExtra("addr", addr);
        i.putExtra("token", pendingToken);
        try {
            sendBroadcast(i);
        } catch (Throwable t) {
            status.setText("广播发送失败：" + t);
        }

        new Thread(new Runnable() {
            @Override public void run() {
                rootOk = RootConfig.write(cfg);
            }
        }).start();
    }

    private void openWeCom() {
        try {
            Intent i = getPackageManager().getLaunchIntentForPackage(PKG);
            if (i == null) {
                Toast.makeText(this, "未安装企业微信", Toast.LENGTH_SHORT).show();
                return;
            }
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Throwable t) {
            Toast.makeText(this, "启动失败: " + t, Toast.LENGTH_SHORT).show();
        }
    }
}
