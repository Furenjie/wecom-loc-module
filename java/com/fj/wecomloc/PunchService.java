package com.fj.wecomloc;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/**
 * Accessibility service that drives the WeCom UI: opens the app, walks to the
 * attendance page and taps punch / photo / confirm buttons. Started either
 * from AutoPunchActivity ("test now") or AlarmReceiver (scheduled).
 */
public class PunchService extends AccessibilityService {

    private static final String PKG = "com.tencent.wework";
    private static final long STEP_MS = 700;
    private static final long TIMEOUT_MS = 90_000L;
    private static volatile PunchService sInst;

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private String mType = "";
    private boolean mNeedPhoto;
    private int mStep;
    private long mDeadline;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        sInst = this;
    }

    @Override
    public boolean onUnbind(Intent intent) {
        if (sInst == this) sInst = null;
        return super.onUnbind(intent);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
    }

    @Override
    public void onInterrupt() {
    }

    /** Returns the live service instance or null. */
    public static PunchService inst() {
        return sInst;
    }

    /** Entry point for AlarmReceiver / AutoPunchActivity. Safe from any thread. */
    public static void begin(String type, boolean needPhoto) {
        final PunchService s = sInst;
        if (s == null) return;
        mHandlerOf(s).post(new Runnable() {
            @Override
            public void run() {
                s.mType = type;
                s.mNeedPhoto = needPhoto;
                s.mStep = 0;
                s.mDeadline = System.currentTimeMillis() + TIMEOUT_MS;
                s.mHandler.removeCallbacks(s.mTick);
                s.mHandler.post(s.mTick);
            }
        });
    }

    private static Handler mHandlerOf(PunchService s) {
        return s.mHandler;
    }

    private final Runnable mTick = new Runnable() {
        @Override
        public void run() {
            if (System.currentTimeMillis() > mDeadline) {
                reset();
                return;
            }
            if (step()) {
                reset();
                return;
            }
            mHandler.postDelayed(this, STEP_MS);
        }
    };

    private void reset() {
        mHandler.removeCallbacks(mTick);
        mStep = 0;
    }

    /** Returns true when the whole flow finished. */
    private boolean step() {
        switch (mStep) {
            case 0: // bring WeCom to front
                Intent i = getPackageManager().getLaunchIntentForPackage(PKG);
                if (i != null) {
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(i);
                }
                mStep = 1;
                return false;
            case 1: // bottom tab "workbench"
                if (clickText(new String[]{"工作台"})) mStep = 2;
                return false;
            case 2: // attendance entry
                if (clickText(new String[]{"上班打卡", "下班打卡", "外出打卡", "打卡"})) {
                    mStep = mNeedPhoto ? 3 : 5;
                }
                return false;
            case 3: // camera shutter
                if (clickDesc(new String[]{"拍照", "拍照打卡", "快门", "Shutter"})
                        || clickText(new String[]{"拍照", "轻触拍照"})) {
                    mStep = 4;
                }
                return false;
            case 4: // confirm photo
                if (clickText(new String[]{"完成", "确定", "使用照片"})) mStep = 5;
                return false;
            default: // punch button
                if (clickText(new String[]{"上班打卡", "下班打卡", "外出打卡", "立即打卡", "打卡"})) {
                    return true;
                }
                return false;
        }
    }

    private boolean clickText(String[] texts) {
        return clickAny(texts, false);
    }

    private boolean clickDesc(String[] descs) {
        return clickAny(descs, true);
    }

    private boolean clickAny(String[] keys, final boolean byDesc) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return false;
        for (String k : keys) {
            List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByText(k);
            if (nodes != null && !nodes.isEmpty()) {
                for (AccessibilityNodeInfo n : nodes) {
                    if (tryClickNode(n)) return true;
                }
            }
            if (byDesc) {
                // also walk the tree matching content description
                if (walkDesc(root, k)) return true;
            }
        }
        return false;
    }

    private boolean walkDesc(AccessibilityNodeInfo n, String key) {
        if (n == null) return false;
        CharSequence d = n.getContentDescription();
        if (d != null && d.toString().contains(key) && tryClickNode(n)) return true;
        for (int i = 0; i < n.getChildCount(); i++) {
            if (walkDesc(n.getChild(i), key)) return true;
        }
        return false;
    }

    private boolean tryClickNode(AccessibilityNodeInfo n) {
        if (n == null || !n.isEnabled()) return false;
        if (n.isClickable() && n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true;
        AccessibilityNodeInfo p = n.getParent();
        int guard = 0;
        while (p != null && guard++ < 5) {
            if (p.isClickable() && p.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true;
            p = p.getParent();
        }
        for (int i = 0; i < n.getChildCount(); i++) {
            AccessibilityNodeInfo ch = n.getChild(i);
            if (ch != null && ch.isClickable()
                    && ch.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true;
        }
        return false;
    }
}