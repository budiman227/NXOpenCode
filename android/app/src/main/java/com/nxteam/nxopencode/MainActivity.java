package com.nxteam.nxopencode;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.provider.Settings;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.webkit.ConsoleMessage;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;

public class MainActivity extends AppCompatActivity implements NodeService.StatusListener {

    private WebView webView;
    private View overlay;
    private TextView statusView;
    private Button newProjectButton;
    private boolean loaded;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        webView = findViewById(R.id.web_view);
        overlay = findViewById(R.id.overlay);
        statusView = findViewById(R.id.status);
        newProjectButton = findViewById(R.id.new_project);
        newProjectButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                showNewProjectDialog();
            }
        });

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setSupportZoom(false);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        }
        WebView.setWebContentsDebuggingEnabled(true);

        webView.setWebViewClient(new WebViewClient());
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onConsoleMessage(ConsoleMessage message) {
                android.util.Log.i("NXWebView", message.message() + " @" + message.lineNumber());
                return true;
            }
        });

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (webView.canGoBack()) webView.goBack();
                else finish();
            }
        });

        requestNotificationPermission();
        requestStoragePermission();

        NodeService.setListener(this);
        String existing = NodeService.currentUrl();
        if (existing != null) {
            onReady(existing);
        } else {
            setStatus(getString(R.string.setup_preparing));
            Intent intent = new Intent(this, NodeService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent);
            else startService(intent);
        }
    }

    private void requestStoragePermission() {
        if (Workspace.hasFullStorageAccess(this)) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                intent.setData(Uri.parse("package:" + getPackageName()));
                startActivity(intent);
            } catch (Exception ignored) {
            }
            return;
        }
        ActivityCompat.requestPermissions(this, new String[]{
                "android.permission.READ_EXTERNAL_STORAGE",
                "android.permission.WRITE_EXTERNAL_STORAGE"}, 2);
    }

    private void showNewProjectDialog() {
        final EditText input = new EditText(this);
        input.setHint(R.string.new_project_hint);
        input.setSingleLine(true);
        new AlertDialog.Builder(this)
                .setTitle(R.string.new_project_title)
                .setView(input)
                .setPositiveButton(R.string.new_project_create, (dialog, which) -> {
                    File created = Workspace.createProject(MainActivity.this, input.getText().toString());
                    if (created == null) {
                        Toast.makeText(this, R.string.new_project_failed, Toast.LENGTH_LONG).show();
                        return;
                    }
                    Toast.makeText(this, getString(R.string.new_project_created, created.getAbsolutePath()),
                            Toast.LENGTH_LONG).show();
                })
                .setNegativeButton(R.string.new_project_cancel, null)
                .show();
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return;
        ActivityCompat.requestPermissions(this, new String[]{"android.permission.POST_NOTIFICATIONS"}, 1);
    }

    private void setStatus(String message) {
        statusView.setText(message);
    }

    @Override
    public void onStatus(String message) {
        setStatus(message);
    }

    @Override
    public void onReady(String url) {
        if (loaded) return;
        loaded = true;
        overlay.setVisibility(View.GONE);
        webView.setVisibility(View.VISIBLE);
        newProjectButton.setVisibility(View.VISIBLE);
        webView.loadUrl(url);
    }

    @Override
    public void onFailure(String message) {
        overlay.setVisibility(View.VISIBLE);
        webView.setVisibility(View.GONE);
        newProjectButton.setVisibility(View.GONE);
        setStatus(getString(R.string.error_prefix) + "\n\n" + message);
    }

    @Override
    protected void onDestroy() {
        NodeService.setListener(null);
        super.onDestroy();
    }
}
