package com.fj.wecomloc;

import android.content.Context;
import android.util.Base64;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/** Writes the config into WeCom's private dir + photo staging via su. */
final class RootConfig {

    private RootConfig() {}

    static boolean write(String content) {
        try {
            String b64 = Base64.encodeToString(
                    content.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
            String cmd = "for d in /data/user/0/com.tencent.wework/files "
                    + "/data/user/128/com.tencent.wework/files "
                    + "/data/data/com.tencent.wework/files; do "
                    + "mkdir -p $d 2>/dev/null; "
                    + "echo '" + b64 + "' | base64 -d > $d/fj_wecom_loc.conf 2>/dev/null; "
                    + "chmod 644 $d/fj_wecom_loc.conf 2>/dev/null; "
                    + "done";
            Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", cmd});
            boolean done = p.waitFor(12, TimeUnit.SECONDS);
            if (!done) {
                p.destroy();
                return false;
            }
            return p.exitValue() == 0;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Run an arbitrary shell command as root. Returns exit code, -1 on timeout/failure. */
    static int run(String cmd, int timeoutSec) {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", cmd});
            if (!p.waitFor(timeoutSec, TimeUnit.SECONDS)) {
                p.destroy();
                return -1;
            }
            return p.exitValue();
        } catch (Throwable t) {
            return -1;
        }
    }

    /**
     * Copy queued punch photos (photo_0.jpg..photo_N.jpg in our external files
     * dir) into WeCom's private files dir so the in-process hook can swap them
     * into AttendanceTakePhotoPromise.resolve. Returns count copied, -1 on error.
     */
    static int pushPhotos(Context c) {
        try {
            File dir = PunchStore.photoDir(c);
            File[] files = dir.listFiles();
            if (files == null || files.length == 0) return 0;
            java.util.Arrays.sort(files);
            int n = 0;
            StringBuilder sb = new StringBuilder();
            for (File f : files) {
                if (sb.length() > 0) sb.append(" && ");
                sb.append("cp -f '").append(f.getAbsolutePath())
                  .append("' '/data/user/0/com.tencent.wework/files/fj_photos/")
                  .append(f.getName())
                  .append("' && chmod 644 '/data/user/0/com.tencent.wework/files/fj_photos/")
                  .append(f.getName()).append("'");
                n++;
            }
            String cmd = "mkdir -p /data/user/0/com.tencent.wework/files/fj_photos && " + sb;
            int rc = run(cmd, 20);
            return rc == 0 ? n : -1;
        } catch (Throwable t) {
            return -1;
        }
    }
}