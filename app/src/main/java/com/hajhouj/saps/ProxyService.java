package com.hajhouj.saps;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;

import androidx.core.app.NotificationCompat;

import java.io.IOException;

public class ProxyService extends Service {
    private static final String CHANNEL_ID = "ProxyServiceChannel";
    private static final int NOTIFICATION_ID = 1;

    private ProxyServer proxyServer;
    private int port = 8080;
    private final IBinder binder = new LocalBinder();
    private OnStatusChangeListener statusListener;

    public interface OnStatusChangeListener {
        void onStatusChanged(boolean running, String message);
    }

    public class LocalBinder extends Binder {
        ProxyService getService() {
            return ProxyService.this;
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            port = intent.getIntExtra("port", 8080);
        }
        startProxy();
        return START_STICKY;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Proxy Service",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("HTTP Proxy Server running");
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    private Notification createNotification() {
        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this, 0, notificationIntent,
                PendingIntent.FLAG_IMMUTABLE
        );

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("SAPS Proxy Server")
                .setContentText("Running on port " + port)
                .setSmallIcon(android.R.drawable.ic_menu_share)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build();
    }

    private void startProxy() {
        if (proxyServer != null && proxyServer.isRunning()) {
            return;
        }

        proxyServer = new ProxyServer(port);
        proxyServer.setLogListener(message -> {
            if (statusListener != null) {
                statusListener.onStatusChanged(proxyServer.isRunning(), message);
            }
        });

        new Thread(() -> {
            try {
                startForeground(NOTIFICATION_ID, createNotification());
                if (statusListener != null) {
                    statusListener.onStatusChanged(true, "Proxy started on port " + port);
                }
                proxyServer.start();
            } catch (IOException e) {
                if (statusListener != null) {
                    statusListener.onStatusChanged(false, "Failed to start: " + e.getMessage());
                }
                stopSelf();
            }
        }).start();
    }

    public void stopProxy() {
        if (proxyServer != null) {
            proxyServer.stop();
            proxyServer = null;
        }
        stopForeground(true);
        stopSelf();
        if (statusListener != null) {
            statusListener.onStatusChanged(false, "Proxy stopped");
        }
    }

    public boolean isRunning() {
        return proxyServer != null && proxyServer.isRunning();
    }

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public void setStatusListener(OnStatusChangeListener listener) {
        this.statusListener = listener;
    }

    @Override
    public void onDestroy() {
        stopProxy();
        super.onDestroy();
    }
}
