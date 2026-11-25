package com.gabriel.motolink;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

/**
 * Serviço em primeiro plano: mantém o app vivo (e o Wi-Fi acordado) quando a tela do celular apaga
 * ou quando outro app fica na frente. Sem ele o Android congela o Moto Link e o painel perde a imagem.
 */
public class Servico extends Service {

    private static final String CANAL = "conexao";
    private PowerManager.WakeLock acordado;
    private WifiManager.WifiLock wifi, wifi2;

    static void ligar(Context c) {
        try {
            Intent i = new Intent(c, Servico.class);
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i); else c.startService(i);
        } catch (Exception e) { Sessao.de(c).log("! Não consegui iniciar o serviço: " + e); }
    }

    static void desligar(Context c) {
        try { c.stopService(new Intent(c, Servico.class)); } catch (Exception ignored) {}
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26 && nm != null && nm.getNotificationChannel(CANAL) == null) {
            NotificationChannel ch = new NotificationChannel(CANAL, "Conexão com o painel", NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        }
        Intent abrir = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 0, abrir, PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CANAL) : new Notification.Builder(this);
        Notification n = b.setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setContentTitle("Moto Link")
                .setContentText("Conectado ao painel da moto")
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
        Sessao ss = Sessao.de(this);
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
            else startForeground(1, n);
            ss.log("Serviço em primeiro plano ligado ✓");
        } catch (Exception e) {
            ss.log("! Serviço com tipo falhou: " + e);
            try { startForeground(1, n); ss.log("Serviço em primeiro plano ligado (sem tipo) ✓"); }
            catch (Exception e2) { ss.log("! Serviço em primeiro plano NÃO ligou: " + e2); }
        }
        if (acordado == null) {
            try {
                PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
                acordado = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MotoLink:painel");
                acordado.acquire(6 * 60 * 60 * 1000L);
            } catch (Exception ignored) {}
        }
        if (wifi == null) {
            try {
                WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
                wifi = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "MotoLink:painel");
                wifi.acquire();
                if (Build.VERSION.SDK_INT >= 29) {
                    wifi2 = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "MotoLink:painel-rapido");
                    wifi2.acquire();
                }
            } catch (Exception ignored) {}
        }
        return START_NOT_STICKY;
    }

    @Override public void onDestroy() {
        try { if (acordado != null && acordado.isHeld()) acordado.release(); } catch (Exception ignored) {}
        try { if (wifi != null && wifi.isHeld()) wifi.release(); } catch (Exception ignored) {}
        try { if (wifi2 != null && wifi2.isHeld()) wifi2.release(); } catch (Exception ignored) {}
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
