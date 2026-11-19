package com.fj.wecomloc;

import android.content.Context;
import android.util.Base64;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/** Writes the config into WeCom's private dir + photo staging via su. */
final class RootConfig {

    /** WeCom's private files dir - the only place its process reads images from. */
    private static final String WECOM_FILES = "/data/user/0/com.tencent.wework/files";
    private static final String PHOTO_DIR = WECOM_FILES + "/fj_photos";
    private static final String FACE_DIR = WECOM_FILES + "/fj_faces";

    private RootConfig() {}

    static boolean write(String content) {
        try {
            String b64 = Base64.encodeToString(
                    content.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
            String cmd = "for d in " + WECOM_FILES + " "
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
     * Copy the face pool into WeCom's private dir as face_0.jpg..face_N.jpg,
     * owned by WeCom's uid/gid. The images must be readable by the WeCom
     * process (mode 644, WeCom's own uid) or the upload returns an empty file id
     * and the server answers "liveness failed".
     *
     * <p>Returns the staged paths in pool order, or an empty array on failure.
     */
    static String[] pushFaces(File[] faces) {
        if (faces == null || faces.length == 0) return new String[0];
        StringBuilder sb = new StringBuilder();
        sb.append("mkdir -p ").append(FACE_DIR).append("; ")
          .append("rm -f ").append(FACE_DIR).append("/face_*.jpg; ");
        for (int i = 0; i < faces.length; i++) {
            sb.append("cp -f '").append(faces[i].getAbsolutePath()).append("' ")
              .append(FACE_DIR).append("/face_").append(i).append(".jpg; ");
        }
        sb.append("U=$(stat -c %u ").append(WECOM_FILES).append("); ")
          .append("G=$(stat -c %g ").append(WECOM_FILES).append("); ")
          .append("chown $U:$G ").append(FACE_DIR).append("/face_*.jpg 2>/dev/null; ")
          .append("chmod 644 ").append(FACE_DIR).append("/face_*.jpg; ")
          .append("ls ").append(FACE_DIR).append(" | wc -l");
        int rc = run(sb.toString(), 30);
        if (rc != 0) return new String[0];
        String[] out = new String[faces.length];
        for (int i = 0; i < faces.length; i++) {
            out[i] = FACE_DIR + "/face_" + i + ".jpg";
        }
        return out;
    }

    /**
     * Copy the punch photo pool into WeCom's private dir as photo_0.jpg..,
     * owned by WeCom's uid/gid (same permission rule as the face pool).
     *
     * <p>Returns the staged paths in pool order, or an empty array on failure.
     */
    static String[] pushPhotos(File[] files) {
        if (files == null || files.length == 0) return new String[0];
        StringBuilder sb = new StringBuilder();
        sb.append("mkdir -p ").append(PHOTO_DIR).append("; ")
          .append("rm -f ").append(PHOTO_DIR).append("/photo_*.jpg; ");
        for (int i = 0; i < files.length; i++) {
            sb.append("cp -f '").append(files[i].getAbsolutePath()).append("' ")
              .append(PHOTO_DIR).append("/photo_").append(i).append(".jpg; ");
        }
        sb.append("U=$(stat -c %u ").append(WECOM_FILES).append("); ")
          .append("G=$(stat -c %g ").append(WECOM_FILES).append("); ")
          .append("chown $U:$G ").append(PHOTO_DIR).append("/photo_*.jpg 2>/dev/null; ")
          .append("chmod 644 ").append(PHOTO_DIR).append("/photo_*.jpg; ")
          .append("echo \"uid=$U files=$(ls ").append(PHOTO_DIR).append(" | wc -l)\"");
        int rc = run(sb.toString(), 30);
        if (rc != 0) return new String[0];
        String[] out = new String[files.length];
        for (int i = 0; i < files.length; i++) {
            out[i] = PHOTO_DIR + "/photo_" + i + ".jpg";
        }
        return out;
    }
}
