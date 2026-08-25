package com.nxteam.nxopencode;

import android.content.Context;
import android.content.res.AssetManager;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

public final class AssetInstaller {

    public interface ProgressListener {
        void onProgress(String message);
    }

    private static final String RUNTIME_DIR = "runtime";
    private static final String WEB_DIR = "web";
    private static final String STAMP_FILE = ".installed";

    private final Context context;

    public AssetInstaller(Context context) {
        this.context = context.getApplicationContext();
    }

    public File runtimeDir() {
        return new File(context.getFilesDir(), RUNTIME_DIR);
    }

    public File webDir() {
        return new File(context.getFilesDir(), WEB_DIR);
    }

    public File serverScript() {
        return new File(runtimeDir(), "server.js");
    }

    public boolean isInstalled() {
        File stamp = new File(context.getFilesDir(), STAMP_FILE);
        if (!stamp.exists()) return false;
        String recorded = readStamp(stamp);
        return currentVersion().equals(recorded);
    }

    public void install(ProgressListener listener) throws IOException {
        deleteRecursively(runtimeDir());
        deleteRecursively(webDir());

        listener.onProgress(context.getString(R.string.setup_runtime));
        copyAssetDirectory(RUNTIME_DIR, runtimeDir(), listener);

        listener.onProgress(context.getString(R.string.setup_interface));
        copyAssetDirectory(WEB_DIR, webDir(), listener);

        File home = new File(context.getFilesDir(), "home");
        File tmp = new File(context.getFilesDir(), "tmp");
        File workspace = new File(context.getFilesDir(), "workspace");
        home.mkdirs();
        tmp.mkdirs();
        workspace.mkdirs();

        writeStamp(new File(context.getFilesDir(), STAMP_FILE), currentVersion());
    }

    private String currentVersion() {
        try {
            return context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionName
                    + "-" + context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionCode;
        } catch (Exception error) {
            return "unknown";
        }
    }

    private void copyAssetDirectory(String assetPath, File target, ProgressListener listener) throws IOException {
        AssetManager assets = context.getAssets();
        String[] entries = assets.list(assetPath);
        if (entries == null || entries.length == 0) {
            copyAssetFile(assetPath, target);
            return;
        }
        if (!target.exists() && !target.mkdirs()) {
            throw new IOException("cannot create directory " + target);
        }
        for (String entry : entries) {
            String childAsset = assetPath + "/" + entry;
            File childTarget = new File(target, entry);
            String[] childEntries = assets.list(childAsset);
            if (childEntries != null && childEntries.length > 0) {
                copyAssetDirectory(childAsset, childTarget, listener);
            } else {
                copyAssetFile(childAsset, childTarget);
            }
        }
    }

    private void copyAssetFile(String assetPath, File target) throws IOException {
        File parent = target.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("cannot create directory " + parent);
        }
        InputStream input = context.getAssets().open(assetPath, AssetManager.ACCESS_STREAMING);
        try {
            OutputStream output = new FileOutputStream(target);
            try {
                byte[] buffer = new byte[65536];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    output.write(buffer, 0, read);
                }
                output.flush();
            } finally {
                output.close();
            }
        } finally {
            input.close();
        }
    }

    private String readStamp(File file) {
        try {
            InputStream input = new java.io.FileInputStream(file);
            try {
                byte[] buffer = new byte[256];
                int read = input.read(buffer);
                return read > 0 ? new String(buffer, 0, read, "UTF-8").trim() : "";
            } finally {
                input.close();
            }
        } catch (IOException error) {
            return "";
        }
    }

    private void writeStamp(File file, String value) throws IOException {
        OutputStream output = new FileOutputStream(file);
        try {
            output.write(value.getBytes("UTF-8"));
        } finally {
            output.close();
        }
    }

    private void deleteRecursively(File file) {
        if (!file.exists()) return;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursively(child);
                }
            }
        }
        file.delete();
    }
}
