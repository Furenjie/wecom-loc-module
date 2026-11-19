package com.fj.wecomloc;

import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.Charset;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Direct attendance submission.
 *
 * <p>Instead of driving the UI we build the same protobuf payloads the app
 * builds and call the native entry points of AttendanceService:
 *
 * <ul>
 *   <li>上班/下班 (binary): {@code remoteCheckExceptionBinary_V27(localId,
 *       SimpliedBinaryCheckinData, ICommonCallback3, ITakePhotoCallback,
 *       ICommonBooleanCallback3, ICheckExceptionV27Callback)}</li>
 *   <li>外出打卡 (random): {@code remoteCheckExceptionRamdon_V2815(
 *       SimpliedRamdonCheckinData, ICommonCallback3, ICheckExceptionV27Callback,
 *       ITakePhotoCallback, ICommonBooleanCallback3, ICreateAttendanceCallBack2)}</li>
 * </ul>
 *
 * <p>Everything is reached through reflection because this class is compiled
 * without the WeCom classpath and is injected into the WeCom process.
 */
public final class PunchDirect {

    public static final String TAG = "WeComLoc-Punch";
    public static final String ACTION_RESULT = "com.fj.wecomloc.PUNCH_RESULT";

    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final byte[] EMPTY = "".getBytes(UTF8);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private static final String SVC = "com.tencent.wework.foundation.logic.AttendanceService";
    private static final String LI = "com.tencent.wework.foundation.model.pb.WwAttendance$LocationInfo";
    private static final String SBI = "com.tencent.wework.foundation.model.pb.WwAttendanceModel$SimpliedBinaryCheckinData";
    private static final String SRI = "com.tencent.wework.foundation.model.pb.WwAttendanceModel$SimpliedRamdonCheckinData";
    private static final String NSW = "com.tencent.wework.foundation.model.pb.WwAttendanceModel$NextCheckStateWrapper";
    private static final String NSL = "com.tencent.wework.foundation.model.pb.WwAttendanceModel$NextCheckStateList";
    private static final String NS = "com.tencent.wework.foundation.model.pb.WwAttendanceModel$NextCheckState";
    private static final String CB3 = "com.tencent.wework.foundation.callback.ICommonCallback3";
    private static final String BOOL3 = "com.tencent.wework.foundation.callback.ICommonBooleanCallback3";
    private static final String TAKE = SVC + "$ITakePhotoCallback";
    private static final String CHKEX = SVC + "$ICheckExceptionV27Callback";
    private static final String CREATE2 = "com.tencent.wework.foundation.callback.ICreateAttendanceCallBack2";
    private static final String STRARR = "com.tencent.wework.foundation.callback.ICommonStringArrayCallback";
    private static final String NEXT = SVC + "$IGetNextCheckinStateCallback";
    private static final String PROMISE = "com.tencent.wework.foundation.model.AttendanceTakePhotoPromise";
    private static final String STRCB = "com.tencent.wework.foundation.callback.ICommonStringCallback";
    private static final String IACCT = "com.tencent.wework.login.api.IAccount";
    private static final String FACEINFO = "com.tencent.wework.foundation.model.pb.WwAttendanceFaceRecg$UserFaceInfo";

    private PunchDirect() {
    }

    // ------------------------------------------------------------------ entry

    /**
     * Fire a check-in. Runs up to {@code attempts} rounds on the main thread
     * until the service becomes available.
     */
    public static void submit(final Context ctx, final boolean outside,
                              final double lat, final double lng,
                              final String poi, final String addr,
                              final String photoPath,
                              final ClassLoader cl) {
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                try {
                    doSubmit(ctx, outside, lat, lng, poi, addr, photoPath, cl, 0);
                } catch (Throwable t) {
                    report(ctx, "exception: " + t);
                }
            }
        });
    }

    private static void doSubmit(final Context ctx, final boolean outside,
                            final double lat, final double lng,
                            final String poi, final String addr,
                            final String photoPath, final ClassLoader cl,
                            final int attempt) {
        Object svc;
        try {
            svc = getService(cl);
        } catch (Throwable t) {
            svc = null;
        }
        if (svc == null) {
            if (attempt < 40) {
                MAIN.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        doSubmit(ctx, outside, lat, lng, poi, addr, photoPath, cl, attempt + 1);
                    }
                }, 500);
            } else {
                report(ctx, "fail: AttendanceService unavailable");
            }
            return;
        }
        try {
            Object manage = call(svc, "getManageInfo", new Class<?>[0]);
            if (manage == null && attempt < 20) {
                // native side has not loaded manage info yet - nudge it and wait
                try {
                    call(svc, "QueryManageInfo", new Class<?>[0]);
                } catch (Throwable ignored) {
                }
                MAIN.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        doSubmit(ctx, outside, lat, lng, poi, addr, photoPath, cl, attempt + 1);
                    }
                }, 700);
                return;
            }
            log(ctx, "service ready, outside=" + outside);
            if (outside) {
                submitRandom(ctx, svc, cl, lat, lng, poi, addr, photoPath);
            } else {
                submitBinary(ctx, svc, cl, lat, lng, poi, addr, photoPath, 0);
            }
        } catch (Throwable t) {
            report(ctx, "fail: " + t);
        }
    }

    // ---------------------------------------------------------------- payload

    private static Object buildLocation(ClassLoader cl, double lat, double lng,
                                        String poi, String addr) throws Exception {
        Class<?> c = Class.forName(LI, true, cl);
        Object li = c.newInstance();
        set(li, "latitude", Long.valueOf((long) (lat * 1000000.0d)));
        set(li, "longitude", Long.valueOf((long) (lng * 1000000.0d)));
        set(li, "accuracy", Long.valueOf(20L));
        set(li, "timestamp", Integer.valueOf((int) (System.currentTimeMillis() / 1000L)));
        set(li, "type", Integer.valueOf(1));          // 1=gps 2=wifi
        set(li, "distance", Long.valueOf(0L));
        set(li, "speed", Long.valueOf(0L));
        set(li, "course", Long.valueOf(0L));
        set(li, "locationTitle", toBytes(poi));
        set(li, "locationDetail", toBytes(addr));
        set(li, "locationProvider", toBytes("gps"));
        set(li, "simulateReason", Long.valueOf(0L));
        set(li, "simulateProbability", Float.valueOf(0.0f));
        set(li, "hasAccuracyException", Boolean.FALSE);
        // explicit has-flags => "these values are real, not simulated"
        setBool(li, "hasLatitude");
        setBool(li, "hasLongitude");
        setBool(li, "hasAccuracy");
        setBool(li, "hasTimestamp");
        setBool(li, "hasType");
        setBool(li, "hasSpeed");
        setBool(li, "hasCourse");
        setBool(li, "hasDistance");
        setBool(li, "hasLocationTitle");
        setBool(li, "hasLocationDetail");
        setBool(li, "hasLocationProvider");
        setBool(li, "hasSimulateReason");
        setBool(li, "hasSimulateProbability");
        return li;
    }

    private static Object buildBinaryData(ClassLoader cl, Object location) throws Exception {
        Class<?> c = Class.forName(SBI, true, cl);
        Object o = c.newInstance();
        set(o, "location", location);
        set(o, "kqj", null);
        set(o, "bssid", EMPTY);
        set(o, "wifimac", EMPTY);
        set(o, "wifiname", EMPTY);
        set(o, "cheatingAppname", EMPTY);
        set(o, "cheatingType", Integer.valueOf(0));
        set(o, "checkinScene", Integer.valueOf(0));
        set(o, "binaryOffBreak", Boolean.FALSE);
        set(o, "needRemindCheckKqj", Boolean.FALSE);
        set(o, "noScheduleOvertime", Boolean.FALSE);
        set(o, "pageUniqueId", Long.valueOf(0L));
        setBool(o, "hasBssid");
        setBool(o, "hasWifimac");
        setBool(o, "hasWifiname");
        setBool(o, "hasCheatingAppname");
        setBool(o, "hasCheatingType");
        return o;
    }

    private static Object buildRandomData(ClassLoader cl, Object location,
                                          String photoPath, boolean fromOut) throws Exception {
        Class<?> c = Class.forName(SRI, true, cl);
        Object o = c.newInstance();
        set(o, "location", location);
        set(o, "bssid", EMPTY);
        set(o, "wifimac", EMPTY);
        set(o, "wifiname", EMPTY);
        set(o, "cheatingAppname", EMPTY);
        set(o, "cheatingType", Integer.valueOf(0));
        set(o, "notes", EMPTY);
        set(o, "fromOutcheckin", Boolean.valueOf(fromOut));
        if (photoPath != null && photoPath.length() > 0) {
            set(o, "imagepathes", new byte[][]{toBytes(photoPath)});
        } else {
            set(o, "imagepathes", new byte[0][]);
        }
        setBool(o, "hasBssid");
        setBool(o, "hasWifimac");
        setBool(o, "hasWifiname");
        setBool(o, "hasCheatingAppname");
        setBool(o, "hasCheatingType");
        setBool(o, "hasNotes");
        setBool(o, "hasFromOutcheckin");
        return o;
    }

    // -------------------------------------------------------------- outside

    private static void submitRandom(final Context ctx, final Object svc,
                                     final ClassLoader cl, double lat, double lng,
                                     String poi, String addr, String photoPath)
            throws Exception {
        // WeCom refuses images outside its own files dir (server answers 901),
        // so stage the picture into our private dir with our own uid first.
        if (photoPath != null && photoPath.length() > 0) {
            photoPath = stageImage(ctx, photoPath, "fj_photos", "photo_0.jpg");
        }
        Object location = buildLocation(cl, lat, lng, poi, addr);
        Object data = buildRandomData(cl, location, photoPath, true);
        Class<?> cb3 = Class.forName(CB3, true, cl);
        Class<?> take = Class.forName(TAKE, true, cl);
        Class<?> bool3 = Class.forName(BOOL3, true, cl);
        Class<?> chkex = Class.forName(CHKEX, true, cl);
        Class<?> create2 = Class.forName(CREATE2, true, cl);
        Object pCb3 = proxy(cl, cb3, new H(ctx, "ICommonCallback3"));
        Object pTake = proxy(cl, take, makeTakeHandler(ctx, svc, cl, photoPath));
        Object pBool3 = proxy(cl, bool3, new H(ctx, "ICommonBooleanCallback3"));
        Object pChkex = proxy(cl, chkex, new H(ctx, "ICheckExceptionV27Callback"));
        Object pCreate2 = proxy(cl, create2, new H(ctx, "ICreateAttendanceCallBack2"));
        Method m = svc.getClass().getMethod("remoteCheckExceptionRamdon_V2815",
                Class.forName(SRI, true, cl), cb3, chkex, take, bool3, create2);
        m.invoke(svc, data, pCb3, pChkex, pTake, pBool3, pCreate2);
        report(ctx, "submitted: outside punch, lat=" + lat + " lng=" + lng
                + " photo=" + photoPath);
    }

    // --------------------------------------------------------------- binary

    private static void submitBinary(final Context ctx, final Object svc,
                                     final ClassLoader cl, double lat, double lng,
                                     String poi, String addr, String photoPath,
                                     final int hop) throws Exception {
        Object location = buildLocation(cl, lat, lng, poi, addr);
        final Object data = buildBinaryData(cl, location);
        final Class<?> nextCb = Class.forName(NEXT, true, cl);
        Object pNext = proxy(cl, nextCb, new InvocationHandler() {
            @Override
            public Object invoke(Object p, Method m, Object[] a) {
                if (!"onResult".equals(m.getName()) || a == null || a.length < 2) {
                    return def(m);
                }
                Object wrapper = a[1];
                long localId = 0L;
                try {
                    localId = pickLocalId(wrapper);
                } catch (Throwable t) {
                    log(ctx, "pickLocalId failed: " + t);
                }
                if (localId == 0L) {
                    if (hop < 6) {
                        MAIN.postDelayed(new Runnable() {
                            @Override
                            public void run() {
                                try {
                                    submitBinary(ctx, svc, cl, lat, lng, poi, addr, photoPath, hop + 1);
                                } catch (Throwable t) {
                                    report(ctx, "fail: " + t);
                                }
                            }
                        }, 800);
                    } else {
                        report(ctx, "fail: no NextCheckState.localId");
                    }
                    return null;
                }
                try {
                    fireBinary(ctx, svc, cl, localId, data, photoPath);
                } catch (Throwable t) {
                    report(ctx, "fail: " + t);
                }
                return null;
            }
        });
        Method m = svc.getClass().getMethod("getNextCheckinState", boolean.class, nextCb);
        m.invoke(svc, Boolean.FALSE, pNext);
        log(ctx, "getNextCheckinState requested (hop " + hop + ")");
    }

    private static void fireBinary(Context ctx, Object svc, ClassLoader cl, long localId,
                                   Object data, String photoPath) throws Exception {
        Class<?> sbi = Class.forName(SBI, true, cl);
        Class<?> cb3 = Class.forName(CB3, true, cl);
        Class<?> take = Class.forName(TAKE, true, cl);
        Class<?> bool3 = Class.forName(BOOL3, true, cl);
        Class<?> chkex = Class.forName(CHKEX, true, cl);
        Object pCb3 = proxy(cl, cb3, new H(ctx, "ICommonCallback3"));
        Object pTake = proxy(cl, take, makeTakeHandler(ctx, svc, cl, photoPath));
        Object pBool3 = proxy(cl, bool3, new H(ctx, "ICommonBooleanCallback3"));
        Object pChkex = proxy(cl, chkex, new H(ctx, "ICheckExceptionV27Callback"));
        Method m = svc.getClass().getMethod("remoteCheckExceptionBinary_V27",
                long.class, sbi, cb3, take, bool3, chkex);
        m.invoke(svc, Long.valueOf(localId), data, pCb3, pTake, pBool3, pChkex);
        report(ctx, "submitted: binary punch, localId=" + localId);
    }

    private static long pickLocalId(Object wrapper) throws Exception {
        if (wrapper == null) return 0L;
        Object listObj = get(wrapper, "nextCheckStateList");
        if (listObj == null) return 0L;
        Object arrObj = get(listObj, "list");
        if (!(arrObj instanceof Object[])) return 0L;
        Object[] arr = (Object[]) arrObj;
        long fallback = 0L;
        for (Object ns : arr) {
            if (ns == null) continue;
            long id = ((Number) get(ns, "localId")).longValue();
            if (id == 0L) continue;
            int code = ((Number) get(ns, "resultCode")).intValue();
            if (code == binaryResultCode(ns)) return id;
            if (fallback == 0L) fallback = id;
        }
        return fallback;
    }

    private static int binaryResultCode(Object ns) {
        try {
            Field f = ns.getClass().getField("Binary");
            return ((Number) f.get(null)).intValue();
        } catch (Throwable t) {
            return -1;
        }
    }

    // ------------------------------------------------------------------ face

    /**
     * Cache of the account's registered face file id. WeCom asks the backend for
     * {@code "vid:fileid,vid:fileid"}; the fileid is what the native check-in
     * wants when it requests a face verification instead of a photo.
     */
    private static volatile String sFaceFileId;
    private static volatile String sFacePhoto;

    /** Seed the face file id captured by the module (from a real face check-in). */
    public static void seedFaceFileId(String id) {
        if (id != null && id.length() > 0) sFaceFileId = id;
    }

    /** Path of a real face photo to upload when the server asks for a live face. */
    public static void seedFacePhoto(String path) {
        if (path != null && path.length() > 0) sFacePhoto = path;
    }

    /**
     * WeCom only accepts check-in images that live inside its own private files
     * dir, and the copy has to be done with WeCom's own uid (a root copy leaves
     * files owned by root, which the app cannot open and the server answers 901).
     *
     * <p>We are injected into the WeCom process, so a plain in-process copy is
     * exactly what the app itself would do. Returns the staged path, or the
     * original path when it already points inside our private dir.
     */
    public static String stageImage(Context ctx, String srcPath, String subDir, String name) {
        if (srcPath == null || srcPath.length() == 0) return srcPath;
        File src = new File(srcPath);
        try {
            File dir = new File(ctx.getFilesDir(), subDir);
            if (!dir.exists() && !dir.mkdirs()) {
                log(ctx, "stage: cannot create " + dir);
                return srcPath;
            }
            File dst = new File(dir, name);
            if (src.getAbsolutePath().equals(dst.getAbsolutePath()) && src.exists()) {
                return dst.getAbsolutePath();
            }
            if (!src.exists() || !src.canRead()) {
                // Android's scoped storage hides other apps' external dirs, so the
                // pool file may be unreadable from inside WeCom. Fall back to a
                // same-named file that was pre-staged into our private dir.
                File alt = new File(dir, src.getName());
                if (alt.exists() && alt.canRead()) {
                    return alt.getAbsolutePath();
                }
                if (dst.exists() && dst.canRead()) {
                    return dst.getAbsolutePath();
                }
                log(ctx, "stage: source unreadable " + srcPath);
                return srcPath;
            }
            copyFile(src, dst);
            //noinspection ResultOfMethodCallIgnored
            dst.setReadable(true, false);
            log(ctx, "stage: " + srcPath + " -> " + dst.getAbsolutePath()
                    + " (" + dst.length() + " bytes)");
            return dst.getAbsolutePath();
        } catch (Throwable t) {
            log(ctx, "stage failed: " + t);
            return srcPath;
        }
    }

    /** Diagnostic: verify the in-process copy works, without submitting anything. */
    public static void stageProbe(Context ctx, String photo, String face) {
        try {
            String a = stageImage(ctx, photo, "fj_photos", "photo_0.jpg");
            File fa = new File(a == null ? "" : a);
            report(ctx, "stage probe photo: src=" + photo + " -> " + a
                    + " exists=" + fa.exists() + " len=" + fa.length()
                    + " canRead=" + fa.canRead());
            String b2 = stageImage(ctx, face, "fj_faces", "face_0.jpg");
            File fb = new File(b2 == null ? "" : b2);
            report(ctx, "stage probe face: src=" + face + " -> " + b2
                    + " exists=" + fb.exists() + " len=" + fb.length()
                    + " canRead=" + fb.canRead());
        } catch (Throwable t) {
            report(ctx, "stage probe failed: " + t);
        }
    }

    private static void copyFile(File src, File dst) throws Exception {
        FileInputStream in = new FileInputStream(src);
        FileOutputStream out = new FileOutputStream(dst);
        try {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            out.flush();
        } finally {
            try {
                in.close();
            } catch (Throwable ignored) {
            }
            try {
                out.close();
            } catch (Throwable ignored) {
            }
        }
    }

    /** Diagnostic only: resolve the face file id and log it. Does not punch. */
    public static void probeFace(final Context ctx, final ClassLoader cl) {
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                Object svc;
                try {
                    svc = getService(cl);
                } catch (Throwable t) {
                    report(ctx, "probe: service unavailable: " + t);
                    return;
                }
                if (svc == null) {
                    report(ctx, "probe: service null");
                    return;
                }
                resolveFaceFileId(ctx, svc, cl, new FaceReady() {
                    @Override
                    public void onReady(String fileId) {
                        report(ctx, "probe face file id = " + fileId);
                    }
                });
            }
        });
    }

    /** Resolve the face file id via AttendanceService.getUserFace([vid], corpid, true, cb). */
    private static void resolveFaceFileId(final Context ctx, final Object svc,
                                          final ClassLoader cl,
                                          final FaceReady then) {
        if (sFaceFileId != null && sFaceFileId.length() > 0) {
            then.onReady(sFaceFileId);
            return;
        }
        long vid = 0L;
        long corpId = 0L;
        try {
            Class<?> acct = Class.forName(IACCT, true, cl);
            Object a = acct.getMethod("get").invoke(null);
            vid = ((Number) acct.getMethod("getVid").invoke(a)).longValue();
            corpId = ((Number) acct.getMethod("getCorpId").invoke(a)).longValue();
            if (vid == 0L) {
                vid = ((Number) acct.getMethod("getLoginUserId").invoke(a)).longValue();
            }
        } catch (Throwable t) {
            log(ctx, "account lookup failed: " + t);
        }
        log(ctx, "resolveFaceFileId vid=" + vid + " corpId=" + corpId);

        // local cache first - avoids a network round trip when the app already has it
        try {
            Method cache = svc.getClass().getMethod("getCacheUserFaceInfo", long.class);
            Object info = cache.invoke(svc, Long.valueOf(vid));
            if (info != null) {
                Object url = get(info, "faceUrl");
                if (url instanceof byte[]) {
                    String s = new String((byte[]) url, UTF8);
                    if (s.length() > 0) {
                        sFaceFileId = s;
                        log(ctx, "face file id from cache: " + s);
                        then.onReady(s);
                        return;
                    }
                }
            }
        } catch (Throwable t) {
            log(ctx, "getCacheUserFaceInfo failed: " + t);
        }

        if (vid == 0L) {
            then.onReady(null);
            return;
        }
        try {
            final Class<?> strcb = Class.forName(STRCB, true, cl);
            Object cb = proxy(cl, strcb, new InvocationHandler() {
                @Override
                public Object invoke(Object p, Method m, Object[] a) {
                    if (!"onResult".equals(m.getName()) || a == null || a.length < 2) {
                        return def(m);
                    }
                    int code = a[0] instanceof Number ? ((Number) a[0]).intValue() : -1;
                    String str = a[1] instanceof String ? (String) a[1] : null;
                    log(ctx, "getUserFace code=" + code + " str=" + str);
                    String fileId = parseFaceFileId(str);
                    if (fileId != null) {
                        sFaceFileId = fileId;
                    }
                    then.onReady(fileId);
                    return null;
                }
            });
            Method m = svc.getClass().getMethod("getUserFace",
                    long[].class, long.class, boolean.class, strcb);
            m.invoke(svc, new long[]{vid}, Long.valueOf(corpId), Boolean.TRUE, cb);
        } catch (Throwable t) {
            log(ctx, "getUserFace failed: " + t);
            then.onReady(null);
        }
    }

    /** Parse {@code "vid:fileid,vid:fileid"} and return the first non-empty fileid. */
    private static String parseFaceFileId(String s) {
        if (s == null) return null;
        String[] parts = s.split(",");
        for (String part : parts) {
            String p = part == null ? "" : part.trim();
            int i = p.indexOf(':');
            if (i < 0 || i + 1 >= p.length()) continue;
            String fileId = p.substring(i + 1).trim();
            if (fileId.length() > 0) return fileId;
        }
        return null;
    }

    private interface FaceReady {
        void onReady(String fileId);
    }

    // ----------------------------------------------------------------- photo

    /** ITakePhotoCallback: answer the native "give me a photo" request. */
    private static InvocationHandler makeTakeHandler(final Context ctx, final Object svc,
                                                      final ClassLoader cl,
                                                      final String photoPath) {
        return new InvocationHandler() {
            @Override
            public Object invoke(Object p, Method m, Object[] a) {
                if (!"onTakePhotoEvent".equals(m.getName()) || a == null || a.length < 4) {
                    return def(m);
                }
                final Object promise = a[0];
                final boolean need = a[1] instanceof Boolean && (Boolean) a[1];
                final int type = a[2] instanceof Number ? ((Number) a[2]).intValue() : 0;
                log(ctx, "onTakePhotoEvent need=" + need + " type=" + type
                        + " promise=" + (promise != null));
                if (promise == null || !need) return null;
                if (type != 0) {
                    // Face verification. The server runs liveness detection on the
                    // uploaded image, so the registered face file id is rejected -
                    // we must upload a real face photo and hand back its file id.
                    final String facePhoto = sFacePhoto;
                    if (facePhoto != null && facePhoto.length() > 0) {
                        MAIN.post(new Runnable() {
                            @Override
                            public void run() {
                                String staged = stageImage(ctx, facePhoto, "fj_faces", "face_0.jpg");
                                log(ctx, "face punch: uploading face photo " + staged);
                                uploadAndResolve(ctx, svc, cl, promise, staged);
                            }
                        });
                        return null;
                    }
                    MAIN.post(new Runnable() {
                        @Override
                        public void run() {
                            resolveFaceFileId(ctx, svc, cl, new FaceReady() {
                                @Override
                                public void onReady(String fileId) {
                                    try {
                                        if (fileId != null && fileId.length() > 0) {
                                            resolve(promise, true, fileId);
                                            log(ctx, "face punch resolved with file id: " + fileId);
                                        } else {
                                            resolve(promise, false, "");
                                            log(ctx, "face punch failed: no face file id");
                                        }
                                    } catch (Throwable t) {
                                        log(ctx, "face resolve failed: " + t);
                                    }
                                }
                            });
                        }
                    });
                    return null;
                }
                if (photoPath == null || photoPath.length() == 0) {
                    log(ctx, "photo disabled, cannot answer take-photo");
                    return null;
                }
                MAIN.post(new Runnable() {
                    @Override
                    public void run() {
                        uploadAndResolve(ctx, svc, cl, promise, photoPath);
                    }
                });
                return null;
            }
        };
    }

    private static void uploadAndResolve(final Context ctx, Object svc, ClassLoader cl,
                                         final Object promise, String photoPath) {
        try {
            Class<?> strArr = Class.forName(STRARR, true, cl);
            Object cb = proxy(cl, strArr, new InvocationHandler() {
                @Override
                public Object invoke(Object p, Method m, Object[] a) {
                    if (!"onResult".equals(m.getName()) || a == null || a.length < 3) {
                        return def(m);
                    }
                    String[] urls = (a[2] instanceof String[]) ? (String[]) a[2] : null;
                    try {
                        if (urls != null && urls.length > 0 && urls[0] != null
                                && urls[0].length() > 0) {
                            resolve(promise, true, urls[0]);
                            log(ctx, "photo uploaded -> " + urls[0]);
                        } else {
                            resolve(promise, false, "");
                            log(ctx, "photo upload failed: " + a[1]);
                        }
                    } catch (Throwable t) {
                        log(ctx, "resolve failed: " + t);
                    }
                    return null;
                }
            });
            Method up = svc.getClass().getMethod("uploadImages",
                    String[].class, boolean.class, strArr);
            up.invoke(svc, new String[]{photoPath}, Boolean.FALSE, cb);
        } catch (Throwable t) {
            log(ctx, "uploadImages failed: " + t);
        }
        report(ctx, "photo upload started: " + photoPath);
    }

    private static void resolve(Object promise, boolean ok, String value) throws Exception {
        Method m = promise.getClass().getMethod("resolve", boolean.class, String.class);
        m.invoke(promise, Boolean.valueOf(ok), value == null ? "" : value);
    }

    // ------------------------------------------------------------------ util

    /** Generic logging proxy for callbacks we only want to observe. */
    private static final class H implements InvocationHandler {
        private final Context ctx;
        private final String name;

        H(Context ctx, String name) {
            this.ctx = ctx;
            this.name = name;
            log(ctx, "callback prepared: " + name);
        }

        @Override
        public Object invoke(Object p, Method m, Object[] a) {
            if (m == null) return null;
            String n = m.getName();
            if ("toString".equals(n)) return name;
            if ("hashCode".equals(n)) return Integer.valueOf(System.identityHashCode(p));
            if ("equals".equals(n)) return Boolean.valueOf(p == (a == null ? null : a[0]));
            StringBuilder sb = new StringBuilder(n).append("(");
            if (a != null) {
                for (int i = 0; i < a.length; i++) {
                    if (i > 0) sb.append(", ");
                    Object v = a[i];
                    if (v instanceof byte[]) {
                        sb.append("bytes[").append(((byte[]) v).length).append("]");
                    } else if (v instanceof Object[]) {
                        sb.append("array[").append(((Object[]) v).length).append("]");
                    } else {
                        sb.append(v);
                    }
                }
            }
            sb.append(")");
            report(ctx, name + "." + sb);
            return def(m);
        }
    }

    private static Object def(Method m) {
        Class<?> r = m.getReturnType();
        if (r == Void.TYPE) return null;
        if (r == Boolean.TYPE) return Boolean.FALSE;
        if (r == Integer.TYPE) return Integer.valueOf(0);
        if (r == Long.TYPE) return Long.valueOf(0L);
        if (r == Float.TYPE) return Float.valueOf(0f);
        if (r == Double.TYPE) return Double.valueOf(0d);
        return null;
    }

    private static Object proxy(ClassLoader cl, Class<?> iface, InvocationHandler h) {
        return Proxy.newProxyInstance(cl, new Class<?>[]{iface}, h);
    }

    private static Object getService(ClassLoader cl) throws Exception {
        Class<?> c = Class.forName(SVC, true, cl);
        Method m = c.getMethod("getService", new Class<?>[0]);
        return m.invoke(null, new Object[0]);
    }

    private static Object call(Object target, String name, Class<?>[] sig, Object... args)
            throws Exception {
        Method m = target.getClass().getMethod(name, sig);
        return m.invoke(target, args);
    }

    private static Object get(Object o, String name) throws Exception {
        Field f = o.getClass().getField(name);
        return f.get(o);
    }

    private static void set(Object o, String name, Object v) throws Exception {
        Field f = o.getClass().getField(name);
        f.set(o, v);
    }

    private static void setBool(Object o, String name) throws Exception {
        set(o, name, Boolean.TRUE);
    }

    private static byte[] toBytes(String s) {
        if (s == null) return EMPTY;
        return s.getBytes(UTF8);
    }

    private static void log(Context ctx, String msg) {
        try {
            android.util.Log.i(TAG, msg);
        } catch (Throwable ignored) {
        }
        append(ctx, msg);
    }

    private static void report(Context ctx, String msg) {
        log(ctx, msg);
        try {
            Intent i = new Intent(ACTION_RESULT);
            i.setPackage("com.fj.wecomloc");
            i.putExtra("msg", msg);
            i.putExtra("ts", System.currentTimeMillis());
            ctx.sendBroadcast(i);
        } catch (Throwable ignored) {
        }
    }

    private static void append(Context ctx, String msg) {
        FileOutputStream out = null;
        try {
            File f = new File(ctx.getFilesDir(), "fj_punch_direct.log");
            long len = f.length();
            if (len > 200 * 1024) {
                f.delete();
                len = 0;
            }
            out = new FileOutputStream(f, true);
            String stamp = new SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)
                    .format(new Date());
            out.write((stamp + " " + msg + "\n").getBytes(UTF8));
            out.flush();
        } catch (Throwable ignored) {
        } finally {
            if (out != null) try {
                out.close();
            } catch (Throwable ignored) {
            }
        }
    }
}
