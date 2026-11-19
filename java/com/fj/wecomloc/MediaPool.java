package com.fj.wecomloc;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.File;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;

/**
 * Read/write the punch photo pool under {@code Pictures/wecom_punch[_face]}.
 *
 * <p>Why MediaStore: the module App may not write {@code /sdcard/Pictures} with a
 * plain FileOutputStream on Android 11+ (WRITE_EXTERNAL_STORAGE is capped at
 * SDK 32 and scoped storage rejects it), so picking a photo used to fail with
 * "已保存 0". Inserting through MediaStore needs no permission at all. WeCom in
 * turn cannot read this app's own Android/data dir, so the pool has to live in a
 * shared media directory - and that is exactly where MediaStore puts it.
 */
final class MediaPool {

    static final String DIR_PHOTO = "wecom_punch";
    static final String DIR_FACE = "wecom_punch_face";

    private MediaPool() {
    }

    static String relPath(String subDir) {
        return Environment.DIRECTORY_PICTURES + "/" + subDir + "/";
    }

    static File dir(String subDir) {
        return new File(Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_PICTURES), subDir);
    }

    /** Writes {@code bm} as JPEG into the pool; returns the absolute path or null. */
    static String insert(Context c, Bitmap bm, String subDir, String displayName) {
        if (bm == null) {
            return null;
        }
        ContentResolver cr = c.getContentResolver();
        ContentValues v = new ContentValues();
        v.put(MediaStore.MediaColumns.DISPLAY_NAME, displayName);
        v.put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg");
        v.put(MediaStore.MediaColumns.RELATIVE_PATH, relPath(subDir));
        v.put(MediaStore.MediaColumns.IS_PENDING, 1);

        Uri uri = null;
        OutputStream os = null;
        try {
            uri = cr.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v);
            if (uri == null) {
                return null;
            }
            os = cr.openOutputStream(uri);
            if (os == null) {
                return null;
            }
            if (!bm.compress(Bitmap.CompressFormat.JPEG, 88, os)) {
                return null;
            }
            os.close();
            os = null;

            ContentValues done = new ContentValues();
            done.put(MediaStore.MediaColumns.IS_PENDING, 0);
            cr.update(uri, done, null, null);
            return new File(dir(subDir), displayName).getAbsolutePath();
        } catch (Throwable t) {
            w("media insert failed: " + subDir + "/" + displayName + ": " + t);
            if (uri != null) {
                try {
                    cr.delete(uri, null, null);
                } catch (Throwable ignored) {
                }
            }
            return null;
        } finally {
            if (os != null) {
                try {
                    os.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /**
     * Lists {@code prefix*.jpg} in the pool.
     *
     * <p>Union of a direct File listing (works when READ_MEDIA_IMAGES is granted,
     * and picks up files pushed over adb that MediaStore has not indexed) and a
     * MediaStore query (works for everything this app inserted itself even with no
     * permission at all).
     */
    static File[] list(Context c, String subDir, String prefix) {
        LinkedHashSet<String> paths = new LinkedHashSet<String>();

        File d = dir(subDir);
        File[] fs = d.listFiles();
        if (fs != null) {
            for (File f : fs) {
                if (matches(f.getName(), prefix)) {
                    paths.add(f.getAbsolutePath());
                }
            }
        }

        Cursor cu = null;
        try {
            String[] proj = {
                    MediaStore.MediaColumns.DISPLAY_NAME,
                    MediaStore.MediaColumns.RELATIVE_PATH,
            };
            cu = c.getContentResolver().query(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI, proj,
                    MediaStore.MediaColumns.RELATIVE_PATH + "=?",
                    new String[]{relPath(subDir)}, null);
            if (cu != null) {
                int iName = cu.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME);
                int iRel = cu.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH);
                while (cu.moveToNext()) {
                    String name = iName >= 0 ? cu.getString(iName) : null;
                    String rel = iRel >= 0 ? cu.getString(iRel) : relPath(subDir);
                    if (name == null || !matches(name, prefix)) {
                        continue;
                    }
                    File f = new File(new File("/sdcard", rel == null ? relPath(subDir) : rel), name);
                    if (f.exists()) {
                        paths.add(f.getAbsolutePath());
                    }
                }
            }
        } catch (Throwable t) {
            w("media query failed: " + t);
        } finally {
            if (cu != null) {
                try {
                    cu.close();
                } catch (Throwable ignored) {
                }
            }
        }

        ArrayList<File> out = new ArrayList<File>();
        for (String p : paths) {
            out.add(new File(p));
        }
        File[] arr = out.toArray(new File[0]);
        Arrays.sort(arr);
        return arr;
    }

    /** Removes every {@code prefix*.jpg} in the pool (File entry + MediaStore row). */
    static int clear(Context c, String subDir, String prefix) {
        int n = 0;
        for (File f : list(c, subDir, prefix)) {
            if (f.delete()) {
                n++;
            }
        }
        Cursor cu = null;
        try {
            String[] proj = {
                    MediaStore.MediaColumns._ID,
                    MediaStore.MediaColumns.DISPLAY_NAME,
                    MediaStore.MediaColumns.RELATIVE_PATH,
            };
            cu = c.getContentResolver().query(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI, proj,
                    MediaStore.MediaColumns.RELATIVE_PATH + "=?",
                    new String[]{relPath(subDir)}, null);
            if (cu != null) {
                int iId = cu.getColumnIndex(MediaStore.MediaColumns._ID);
                int iName = cu.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME);
                while (cu.moveToNext()) {
                    String name = iName >= 0 ? cu.getString(iName) : null;
                    if (name == null || !matches(name, prefix)) {
                        continue;
                    }
                    long id = cu.getLong(iId);
                    Uri u = ContentUris.withAppendedId(
                            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id);
                    try {
                        c.getContentResolver().delete(u, null, null);
                    } catch (Throwable ignored) {
                    }
                }
            }
        } catch (Throwable t) {
            w("media clear failed: " + t);
        } finally {
            if (cu != null) {
                try {
                    cu.close();
                } catch (Throwable ignored) {
                }
            }
        }
        return n;
    }

    private static boolean matches(String name, String prefix) {
        return name != null && name.startsWith(prefix) && name.endsWith(".jpg");
    }

    private static void w(String msg) {
        android.util.Log.w("WeComLoc-Media", msg);
    }
}
