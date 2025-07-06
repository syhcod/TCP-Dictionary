package com.example.tcpclipboard;

import android.app.*;
import android.content.*;
import android.os.*;
import android.util.Log;
import android.widget.Toast;

import androidx.core.app.NotificationCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.Socket;
import java.net.SocketTimeoutException;

public class TcpService extends Service {
    private static final String CHANNEL_ID = "tcp_service_channel";
    public static final String ACTION_CONNECTED = "com.example.tcpclipboard.CONNECTED";
    public static final String ACTION_DISCONNECTED = "com.example.tcpclipboard.DISCONNECTED";

    private Thread tcpThread;
    private String serverIp;
    private int serverPort;
    private volatile boolean isRunning = false;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        serverIp = intent.getStringExtra("ip");
        serverPort = intent.getIntExtra("port", 80);

        if (serverIp == null || serverIp.isEmpty()) {
            stopSelf();
            return START_NOT_STICKY;
        }

        createNotificationChannel();
        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("TCP Service")
                .setContentText("Connecting to " + serverIp + ":" + serverPort)
                .setSmallIcon(android.R.drawable.ic_menu_send)
                .build();
        startForeground(1, notification);

        Log.d("TcpService", "Starting thread for TCP client loop");

        isRunning = true;

        tcpThread = new Thread(this::tcpClientLoop);
        tcpThread.start();

        return START_STICKY;
    }

    private void tcpClientLoop() {
        while (isRunning) {
            try (Socket socket = new Socket(serverIp, serverPort)) {
                socket.setSoTimeout(2000);
                BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);

                LocalBroadcastManager.getInstance(this).sendBroadcast(new Intent(ACTION_CONNECTED));
                Log.d("TcpService", "Connected to server");

                String line;
                while (isRunning) {
                    try {
                        line = reader.readLine();
                        if (line != null) {
                            Log.d("TcpService","Received data: " + line);

// Update clipboard
ClipData clip = ClipData.newPlainText("TCPData", line);
                            clipboard.setPrimaryClip(clip);

// Send LOCAL broadcast to MainActivity
Intent dataIntent = new Intent("com.example.tcpclipboard.NEW_DATA");
                            dataIntent.putExtra("data", line);
                            LocalBroadcastManager.getInstance(this).sendBroadcast(dataIntent);

                            Log.d("TcpService", "LOCAL broadcast sent with data: " + line);
                        } else {
                                // Connection closed by server
                                Log.d("TcpService", "Server closed connection");
                            break;
                                    }
                                    } catch (SocketTimeoutException e) {
        // Continue to check isRunning
        }
        }

        // Either disconnected or isRunning became false
        LocalBroadcastManager.getInstance(this).sendBroadcast(new Intent(ACTION_DISCONNECTED));
        Log.d("TcpService", "Disconnected from server");

            } catch (Exception e) {
        Log.e("TcpService", "Connection failed", e);
                LocalBroadcastManager.getInstance(this).sendBroadcast(new Intent(ACTION_DISCONNECTED));
        }

        // Wait before retrying
        if (isRunning) {
        try {
        Log.d("TcpService", "Reconnecting in 5 seconds...");
                    Thread.sleep(5000);
                } catch (InterruptedException e) {
        Log.d("TcpService", "Reconnect thread interrupted");
                    break;
                            }
                            }
                            }

stopSelf();  // End service if isRunning is false or thread interrupted
    }

private void createNotificationChannel() {
    NotificationChannel channel = new NotificationChannel(
            CHANNEL_ID,
            "TCP Service Channel",
            NotificationManager.IMPORTANCE_DEFAULT
    );
    NotificationManager manager = getSystemService(NotificationManager.class);
    manager.createNotificationChannel(channel);
}

@Override
public void onDestroy() {
    super.onDestroy();
    isRunning = false;

    if (tcpThread != null && tcpThread.isAlive()) {
        tcpThread.interrupt();
        try {
            tcpThread.join(1000); // Wait up to 1 second for thread to finish
        } catch (InterruptedException e) {
            Log.d("TcpService", "Interrupted while waiting for thread to finish");
        }
    }

    Log.d("TcpService", "Service destroyed");
}

@Override
public IBinder onBind(Intent intent) {
    return null;
}
}