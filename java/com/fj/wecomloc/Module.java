package com.fj.wecomloc;

import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

/**
 * WeCom 5.0.13 location module.
 *
 * The app already has an internal simulated-location path used by its attendance
 * code: kzu.k(TencentLocation, boolean) builds qyu from the Tencent fix, then
 * overwrites qyu.d/qyu.e/qyu.i/qyu.j from we5.M/N/O/R/S when we5.M is true.
 * We arm that path and also rewrite the returned qyu as a fallback.
 */
public class Module implements IXposedHookLoadPackage {

    private static final String TAG = "WeComLoc";
    private static final String PKG = "com.tencent.wework";
    private static final String ACTION_SET = "com.fj.wecomloc.SET";
    private static final String ACTION_ACK = "com.fj.wecomloc.ACK";
    private static final String CONFIG_NAME = "fj_wecom_loc.conf";
    private static final String PREFS = "fj_wecom_loc";

    private static volatile boolean enabled = true;
    private static volatile double lat = 39.908722d;
    private static volatile double lng = 116.397499d;
    private static volatile String poiName = "东长安街";
    private static volatile String address = "北京市东城区东长安街";

    private static ClassLoader sCl;
    private static Context sCtx;
    private static boolean sInited;
    private static boolean sHooksInstalled;
    private static BroadcastReceiver sReceiver;

    @Override
    public void handleLoadPackage(LoadPackageParam lpparam) {
        if (!PKG.equals(lpparam.packageName)) return;
        sCl = lpparam.classLoader;
        XposedBridge.log(TAG + ": loaded in " + lpparam.processName);

        try {
            XposedHelpers.findAndHookMethod(Application.class, "attach", Context.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                init((Context) param.args[0]);
                            } catch (Throwable t) {
                                XposedBridge.log(TAG + ": attach init failed: " + t);
                            }
                        }
                    });
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": cannot hook Application.attach: " + t);
        }

        try {
            XposedHelpers.findAndHookMethod(Application.class, "onCreate", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        init((Context) param.thisObject);
                    } catch (Throwable t) {
                        XposedBridge.log(TAG + ": onCreate init failed: " + t);
                    }
                }
            });
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": cannot hook Application.onCreate: " + t);
        }
    }

    private static synchronized void init(Context ctx) {
        if (sInited || ctx == null) return;
        sInited = true;
        sCtx = ctx.getApplicationContext() != null ? ctx.getApplicationContext() : ctx;
        loadConfig(sCtx);
        installHooks();
        applyToWe5();
        registerReceiver(sCtx);
        XposedBridge.log(TAG + ": init done, enabled=" + enabled
                + " lat=" + lat + " lng=" + lng + " name=" + poiName);
    }

    private static void installHooks() {
        if (sHooksInstalled) return;
        sHooksInstalled = true;
        try {
            Class<?> tencentLocation = XposedHelpers.findClass(
                    "com.tencent.map.geolocation.sapp.TencentLocation", sCl);

            XposedHelpers.findAndHookMethod("kzu", sCl, "k",
                    tencentLocation, boolean.class, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (!enabled) return;
                            Object q = param.getResult();
                            if (q == null) return;
                            try {
                                rewriteQyu(q);
                            } catch (Throwable t) {
                                XposedBridge.log(TAG + ": rewrite qyu failed: " + t);
                            }
                        }
                    });
            XposedBridge.log(TAG + ": hooked kzu.k");

            try {
                XposedHelpers.findAndHookMethod("yf4", sCl, "j",
                        tencentLocation, new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) {
                                if (enabled) param.setResult(null);
                            }
                        });
                XposedBridge.log(TAG + ": hooked yf4.j");
            } catch (Throwable t) {
                XposedBridge.log(TAG + ": yf4.j hook skipped: " + t);
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": installHooks failed: " + t);
        }
    }

    private static void rewriteQyu(Object q) {
        long now = System.currentTimeMillis();
        XposedHelpers.setDoubleField(q, "d", lat);
        XposedHelpers.setDoubleField(q, "e", lng);
        XposedHelpers.setLongField(q, "f", now);
        XposedHelpers.setFloatField(q, "g", 20.0f);
        XposedHelpers.setObjectField(q, "i", address);
        XposedHelpers.setObjectField(q, "j", poiName);
        XposedHelpers.setIntField(q, "h", 0);
        XposedHelpers.setLongField(q, "x", 0L);
        XposedHelpers.setFloatField(q, "y", 0.0f);
        XposedHelpers.setObjectField(q, "u", "gps");
    }

    private static void applyToWe5() {
        try {
            Class<?> we5 = XposedHelpers.findClass("we5", sCl);
            XposedHelpers.setStaticBooleanField(we5, "M", enabled);
            XposedHelpers.setStaticFloatField(we5, "N", (float) lat);
            XposedHelpers.setStaticFloatField(we5, "O", (float) lng);
            XposedHelpers.setStaticIntField(we5, "P", 0);
            XposedHelpers.setStaticFloatField(we5, "Q", 0.0f);
            XposedHelpers.setStaticObjectField(we5, "R", poiName);
            XposedHelpers.setStaticObjectField(we5, "S", address);
            XposedBridge.log(TAG + ": we5 armed");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": applyToWe5 failed: " + t);
        }
    }

    private static double getNum(Bundle b, String key, double def) {
        Object o = b.get(key);
        if (o instanceof Number) return ((Number) o).doubleValue();
        if (o instanceof String) {
            try { return Double.parseDouble(((String) o).trim()); } catch (Throwable ignored) {}
        }
        return def;
    }

    private static boolean getBool(Bundle b, String key, boolean def) {
        Object o = b.get(key);
        if (o instanceof Boolean) return (Boolean) o;
        if (o instanceof Number) return ((Number) o).intValue() != 0;
        if (o instanceof String) {
            String s = ((String) o).trim();
            return "1".equals(s) || "true".equalsIgnoreCase(s);
        }
        return def;
    }

    private static void registerReceiver(Context ctx) {
        if (sReceiver != null) return;
        sReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (intent == null || !ACTION_SET.equals(intent.getAction())) return;
                Bundle b = intent.getExtras();
                if (b == null) return;
                enabled = getBool(b, "enabled", enabled);
                lat = getNum(b, "lat", lat);
                lng = getNum(b, "lng", lng);
                poiName = b.getString("name", poiName);
                address = b.getString("addr", address);
                if (poiName == null || poiName.trim().isEmpty()) poiName = address;
                if (address == null) address = "";
                applyToWe5();
                saveConfig(context);
                sendAck(context, b.getString("token", ""));
                final String msg = "定位已应用: " + lat + ", " + lng;
                XposedBridge.log(TAG + ": " + msg);
                new Handler(Looper.getMainLooper()).post(new Runnable() {
                    @Override public void run() {
                        try {
                            Toast.makeText(sCtx, msg, Toast.LENGTH_SHORT).show();
                        } catch (Throwable ignored) {
                        }
                    }
                });
            }
        };
        IntentFilter filter = new IntentFilter(ACTION_SET);
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                ctx.registerReceiver(sReceiver, filter, Context.RECEIVER_EXPORTED);
            } else {
                ctx.registerReceiver(sReceiver, filter);
            }
            XposedBridge.log(TAG + ": receiver registered");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": registerReceiver failed: " + t);
        }
    }

    private static void sendAck(Context ctx, String token) {
        try {
            Intent ack = new Intent(ACTION_ACK);
            ack.setPackage("com.fj.wecomloc");
            ack.putExtra("token", token == null ? "" : token);
            ack.putExtra("ok", true);
            ack.putExtra("lat", lat);
            ack.putExtra("lng", lng);
            ack.putExtra("name", poiName);
            ack.putExtra("addr", address);
            ctx.sendBroadcast(ack);
            XposedBridge.log(TAG + ": ack sent");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": sendAck failed: " + t);
        }
    }

    private static void loadConfig(Context ctx) {
        File f = new File(ctx.getFilesDir(), CONFIG_NAME);
        if (!f.exists()) {
            XposedBridge.log(TAG + ": no config file, using defaults");
            return;
        }
        BufferedReader r = null;
        try {
            r = new BufferedReader(new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8));
            String line;
            while ((line = r.readLine()) != null) {
                int eq = line.indexOf('=');
                if (eq <= 0) continue;
                String k = line.substring(0, eq).trim();
                String v = line.substring(eq + 1).trim();
                if ("enabled".equals(k)) enabled = "1".equals(v) || "true".equalsIgnoreCase(v);
                else if ("lat".equals(k)) lat = Double.parseDouble(v);
                else if ("lng".equals(k)) lng = Double.parseDouble(v);
                else if ("name".equals(k)) poiName = v;
                else if ("addr".equals(k)) address = v;
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": loadConfig failed: " + t);
        } finally {
            if (r != null) try { r.close(); } catch (Throwable ignored) {}
        }
    }

    private static void saveConfig(Context ctx) {
        File f = new File(ctx.getFilesDir(), CONFIG_NAME);
        FileOutputStream out = null;
        try {
            out = new FileOutputStream(f);
            String s = "enabled=" + (enabled ? "1" : "0") + "\n"
                    + "lat=" + lat + "\n"
                    + "lng=" + lng + "\n"
                    + "name=" + poiName + "\n"
                    + "addr=" + address + "\n";
            out.write(s.getBytes(StandardCharsets.UTF_8));
            out.flush();
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": saveConfig failed: " + t);
        } finally {
            if (out != null) try { out.close(); } catch (Throwable ignored) {}
        }
    }
}
