package com.nxteam.nxopencode;

import android.content.Context;
import android.os.Build;
import android.os.Environment;

import java.io.File;

public final class Workspace {

    private static final String FOLDER = "NXOpenCode";

    private Workspace() {
    }

    public static boolean hasFullStorageAccess(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return Environment.isExternalStorageManager();
        }
        return context.checkSelfPermission("android.permission.WRITE_EXTERNAL_STORAGE")
                == android.content.pm.PackageManager.PERMISSION_GRANTED;
    }

    public static File root(Context context) {
        if (hasFullStorageAccess(context)
                && Environment.MEDIA_MOUNTED.equals(Environment.getExternalStorageState())) {
            File shared = new File(Environment.getExternalStorageDirectory(), FOLDER);
            if (shared.exists() || shared.mkdirs()) {
                if (shared.canWrite()) return shared;
            }
        }
        File scoped = context.getExternalFilesDir(null);
        if (scoped != null) {
            File fallback = new File(scoped, "workspace");
            if (fallback.exists() || fallback.mkdirs()) return fallback;
        }
        File internal = new File(context.getFilesDir(), "workspace");
        internal.mkdirs();
        return internal;
    }

    public static File prepare(Context context) {
        File root = root(context);
        File starter = new File(root, "project");
        if (!starter.exists()) {
            starter.mkdirs();
            writeReadme(starter);
        }
        return root;
    }

    public static File createProject(Context context, String name) {
        String safe = name.trim().replaceAll("[^A-Za-z0-9._-]", "-");
        if (safe.isEmpty()) return null;
        File target = new File(root(context), safe);
        if (target.exists()) return target;
        if (!target.mkdirs()) return null;
        writeReadme(target);
        return target;
    }

    private static void writeReadme(File directory) {
        File readme = new File(directory, "README.md");
        if (readme.exists()) return;
        try {
            java.io.OutputStream output = new java.io.FileOutputStream(readme);
            try {
                output.write(("# " + directory.getName() + "\n").getBytes("UTF-8"));
            } finally {
                output.close();
            }
        } catch (java.io.IOException ignored) {
        }
    }
}
