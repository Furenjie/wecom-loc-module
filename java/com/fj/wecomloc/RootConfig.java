package com.fj.wecomloc;

import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/** Writes the config into WeCom's private files dir via KernelSU/Magisk su. */
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
}
