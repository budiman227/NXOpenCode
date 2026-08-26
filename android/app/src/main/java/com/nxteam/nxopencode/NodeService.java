package com.nxteam.nxopencode;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class NodeService extends Service {

    public interface StatusListener {
        void onStatus(String message);

        void onReady(String url);

        void onFailure(String message);
    }

    private static final String TAG = "NodeService";
    private static final String CHANNEL_ID = "nxopencode_server";
    private static final int NOTIFICATION_ID = 1001;
    private static final int READY_TIMEOUT_MS = 120000;

    private static final Object lock = new Object();
    private static Process process;
    private static String readyUrl;
    private static String failure;
    private static StatusListener listener;
    private static boolean starting;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    public static void setListener(StatusListener value) {
        synchronized (lock) {
            listener = value;
            if (value == null) return;
            if (readyUrl != null) value.onReady(readyUrl);
            else if (failure != null) value.onFailure(failure);
        }
    }

    public static String currentUrl() {
        synchronized (lock) {
            return readyUrl;
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        startForeground(NOTIFICATION_ID, buildNotification(getString(R.string.notification_starting)));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        synchronized (lock) {
            if (starting || readyUrl != null) return START_STICKY;
            starting = true;
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                boot();
            }
        }, "nxopencode-boot").start();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        synchronized (lock) {
            if (process != null) {
                process.destroy();
                process = null;
            }
            readyUrl = null;
            starting = false;
        }
        super.onDestroy();
    }

    private void boot() {
        try {
            AssetInstaller installer = new AssetInstaller(this);
            if (!installer.isInstalled()) {
                installer.install(new AssetInstaller.ProgressListener() {
                    @Override
                    public void onProgress(String message) {
                        publishStatus(message);
                    }
                });
            }
            publishStatus(getString(R.string.setup_starting));
            launch(installer);
        } catch (Exception error) {
            Log.e(TAG, "boot failed", error);
            publishFailure(String.valueOf(error.getMessage()));
        }
    }

    private void launch(AssetInstaller installer) throws IOException {
        File nodeBinary = new File(getApplicationInfo().nativeLibraryDir, "libnode.so");
        if (!nodeBinary.exists()) {
            publishFailure(getString(R.string.error_node_missing));
            return;
        }
        if (!nodeBinary.canExecute() && !nodeBinary.setExecutable(true)) {
            Log.w(TAG, "could not mark node binary executable");
        }

        int port = findFreePort();
        File home = new File(getFilesDir(), "home");
        File tmp = new File(getFilesDir(), "tmp");
        File workspace = Workspace.prepare(this);

        List<String> command = new ArrayList<String>();
        command.add(nodeBinary.getAbsolutePath());
        command.add("--max-old-space-size=768");
        command.add(installer.serverScript().getAbsolutePath());

        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(workspace);
        builder.redirectErrorStream(true);

        Map<String, String> environment = builder.environment();
        environment.put("HOME", workspace.getAbsolutePath());
        environment.put("TMPDIR", tmp.getAbsolutePath());
        environment.put("TMP", tmp.getAbsolutePath());
        environment.put("PATH", getApplicationInfo().nativeLibraryDir + ":/system/bin:/system/xbin");
        environment.put("LD_LIBRARY_PATH", getApplicationInfo().nativeLibraryDir + ":/system/lib64:/vendor/lib64");
        environment.put("LANG", "en_US.UTF-8");
        environment.put("OPENCODE_HOSTNAME", "127.0.0.1");
        environment.put("OPENCODE_PORT", String.valueOf(port));
        environment.put("OPENCODE_WEB_DIR", installer.webDir().getAbsolutePath());
        environment.put("XDG_DATA_HOME", new File(home, ".local/share").getAbsolutePath());
        environment.put("XDG_CONFIG_HOME", new File(home, ".config").getAbsolutePath());
        environment.put("XDG_CACHE_HOME", new File(home, ".cache").getAbsolutePath());
        environment.put("XDG_STATE_HOME", new File(home, ".local/state").getAbsolutePath());

        Process started = builder.start();
        synchronized (lock) {
            process = started;
        }
        readOutput(started, port);
    }

    private void readOutput(Process started, int port) {
        final long deadline = System.currentTimeMillis() + READY_TIMEOUT_MS;
        final StringBuilder tail = new StringBuilder();
        try {
            BufferedReader reader = new BufferedReader(new InputStreamReader(started.getInputStream()));
            String line;
            while ((line = reader.readLine()) != null) {
                Log.i(TAG, line);
                if (tail.length() < 4000) tail.append(line).append('\n');
                if (line.startsWith("OPENCODE_READY")) {
                    publishReady("http://127.0.0.1:" + port);
                } else if (line.startsWith("OPENCODE_FAILED")) {
                    publishFailure(line);
                } else if (currentUrl() == null && System.currentTimeMillis() > deadline) {
                    publishFailure(getString(R.string.error_timeout));
                }
            }
        } catch (IOException error) {
            Log.e(TAG, "output stream closed", error);
        }
        if (currentUrl() == null) {
            publishFailure(tail.length() > 0 ? tail.toString() : getString(R.string.error_exited));
        }
    }

    private int findFreePort() {
        try {
            ServerSocket socket = new ServerSocket(0);
            try {
                socket.setReuseAddress(true);
                return socket.getLocalPort();
            } finally {
                socket.close();
            }
        } catch (IOException error) {
            return 4096;
        }
    }

    private void publishStatus(final String message) {
        updateNotification(message);
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                StatusListener target;
                synchronized (lock) {
                    target = listener;
                }
                if (target != null) target.onStatus(message);
            }
        });
    }

    private void publishReady(final String url) {
        synchronized (lock) {
            readyUrl = url;
            failure = null;
        }
        updateNotification(getString(R.string.notification_running));
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                StatusListener target;
                synchronized (lock) {
                    target = listener;
                }
                if (target != null) target.onReady(url);
            }
        });
    }

    private void publishFailure(final String message) {
        synchronized (lock) {
            if (readyUrl != null) return;
            failure = message;
            starting = false;
        }
        updateNotification(getString(R.string.notification_failed));
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                StatusListener target;
                synchronized (lock) {
                    target = listener;
                }
                if (target != null) target.onFailure(message);
            }
        });
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, getString(R.string.notification_channel), NotificationManager.IMPORTANCE_LOW);
        channel.setShowBadge(false);
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) manager.createNotificationChannel(channel);
    }

    private Notification buildNotification(String text) {
        Intent intent = new Intent(this, MainActivity.class);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, intent, flags);

        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        return builder
                .setContentTitle(getString(R.string.app_name))
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build();
    }

    private void updateNotification(String text) {
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager != null) manager.notify(NOTIFICATION_ID, buildNotification(text));
    }
}
