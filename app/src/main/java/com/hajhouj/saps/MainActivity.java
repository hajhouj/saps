package com.hajhouj.saps;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.IBinder;
import android.text.format.Formatter;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.google.android.material.card.MaterialCardView;

public class MainActivity extends AppCompatActivity {

    private Button btnToggle;
    private EditText etPort;
    private TextView tvStatus;
    private TextView tvProxyInfo;
    private TextView tvLogs;
    private MaterialCardView cardStatus;
    private ScrollView scrollLogs;

    private ProxyService proxyService;
    private boolean serviceBound = false;

    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            ProxyService.LocalBinder binder = (ProxyService.LocalBinder) service;
            proxyService = binder.getService();
            serviceBound = true;
            proxyService.setStatusListener((running, message) -> {
                runOnUiThread(() -> {
                    updateUI(running);
                    addLog(message);
                });
            });
            updateUI(proxyService.isRunning());
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            serviceBound = false;
            proxyService = null;
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        initViews();
        bindToService();
    }

    private void initViews() {
        btnToggle = findViewById(R.id.btnToggle);
        etPort = findViewById(R.id.etPort);
        tvStatus = findViewById(R.id.tvStatus);
        tvProxyInfo = findViewById(R.id.tvProxyInfo);
        tvLogs = findViewById(R.id.tvLogs);
        cardStatus = findViewById(R.id.cardStatus);
        scrollLogs = findViewById(R.id.scrollLogs);

        btnToggle.setOnClickListener(v -> toggleProxy());

        findViewById(R.id.btnClearLogs).setOnClickListener(v -> clearLogs());
        findViewById(R.id.btnCopyLogs).setOnClickListener(v -> copyLogs());
        findViewById(R.id.btnAbout).setOnClickListener(v -> showAbout());
    }

    private void bindToService() {
        Intent intent = new Intent(this, ProxyService.class);
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE);
    }

    private void toggleProxy() {
        if (!serviceBound || proxyService == null) return;

        if (proxyService.isRunning()) {
            proxyService.stopProxy();
        } else {
            String portStr = etPort.getText().toString().trim();
            int port = portStr.isEmpty() ? 8080 : Integer.parseInt(portStr);

            if (port < 1024 || port > 65535) {
                Toast.makeText(this, "Port must be between 1024 and 65535", Toast.LENGTH_SHORT).show();
                return;
            }

            Intent intent = new Intent(this, ProxyService.class);
            intent.putExtra("port", port);
            ContextCompat.startForegroundService(this, intent);
        }
    }

    private void updateUI(boolean running) {
        if (running) {
            btnToggle.setText(R.string.stop_proxy);
            btnToggle.setBackgroundTintList(ContextCompat.getColorStateList(this, R.color.red));
            tvStatus.setText(R.string.status_running);
            tvStatus.setTextColor(ContextCompat.getColor(this, R.color.green));
            cardStatus.setStrokeColor(ContextCompat.getColor(this, R.color.green));
            etPort.setEnabled(false);

            String ipAddress = getDeviceIpAddress();
            int port = proxyService != null ? proxyService.getPort() : 8080;
            tvProxyInfo.setText(getString(R.string.proxy_info, ipAddress, port));
            tvProxyInfo.setVisibility(View.VISIBLE);
        } else {
            btnToggle.setText(R.string.start_proxy);
            btnToggle.setBackgroundTintList(ContextCompat.getColorStateList(this, R.color.green));
            tvStatus.setText(R.string.status_stopped);
            tvStatus.setTextColor(ContextCompat.getColor(this, R.color.red));
            cardStatus.setStrokeColor(ContextCompat.getColor(this, R.color.red));
            etPort.setEnabled(true);
            tvProxyInfo.setVisibility(View.GONE);
        }
    }

    private String getDeviceIpAddress() {
        WifiManager wifiManager = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (wifiManager != null) {
            int ipInt = wifiManager.getConnectionInfo().getIpAddress();
            return Formatter.formatIpAddress(ipInt);
        }
        return "Unknown";
    }

    private void addLog(String message) {
        String timestamp = new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
                .format(new java.util.Date());
        tvLogs.append("[" + timestamp + "] " + message + "\n");
        scrollLogs.post(() -> scrollLogs.fullScroll(View.FOCUS_DOWN));
    }

    private void clearLogs() {
        tvLogs.setText("");
    }

    private void copyLogs() {
        String logs = tvLogs.getText().toString();
        if (logs.isEmpty()) {
            Toast.makeText(this, "No logs to copy", Toast.LENGTH_SHORT).show();
            return;
        }
        
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        ClipData clip = ClipData.newPlainText("SAPS Proxy Logs", logs);
        clipboard.setPrimaryClip(clip);
        Toast.makeText(this, "Logs copied to clipboard", Toast.LENGTH_SHORT).show();
    }

    private void showAbout() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.app_name)
                .setMessage("Simple Android Proxy Server (SAPS)\n\nVersion 1.0\n\nA lightweight HTTP/HTTPS proxy server for Android.")
                .setPositiveButton("OK", null)
                .show();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (serviceBound) {
            unbindService(serviceConnection);
            serviceBound = false;
        }
    }
}
