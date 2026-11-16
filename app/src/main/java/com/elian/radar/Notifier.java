package com.elian.radar;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;

import java.util.List;

/** Notificaciones del celular. Nada de WhatsApp ni servidores. */
public final class Notifier {
    private Notifier() {}

    static final String CANAL_NORMAL = "radar_normal";
    static final String CANAL_ALTA = "radar_alta";
    static final String CANAL_SISTEMA = "radar_sistema";

    public static final int ID_RESUMEN = 100;
    public static final int ID_VIGILANCIA = 900;
    public static final int ID_ACTUALIZACION = 901;

    public static void canales(Context c) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm == null) return;
        NotificationChannel n = new NotificationChannel(CANAL_NORMAL, "Resumen y recordatorios", NotificationManager.IMPORTANCE_DEFAULT);
        n.setDescription("Resumen diario, recordatorios y cambios");
        NotificationChannel a = new NotificationChannel(CANAL_ALTA, "Prioridad alta", NotificationManager.IMPORTANCE_HIGH);
        a.setDescription("Licitaciones de instituciones favoritas o grandes");
        NotificationChannel s = new NotificationChannel(CANAL_SISTEMA, "Estado de la app", NotificationManager.IMPORTANCE_LOW);
        s.setDescription("Autovigilancia y actualizaciones");
        nm.createNotificationChannel(n);
        nm.createNotificationChannel(a);
        nm.createNotificationChannel(s);
    }

    public static boolean permitidas(Context c) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        return nm != null && nm.areNotificationsEnabled();
    }

    public static void mostrar(Context c, int id, String canal, String titulo, String texto, List<String> lineas, String pantalla) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm == null || !nm.areNotificationsEnabled()) return;
        canales(c);
        Intent i = new Intent(c, MainActivity.class).putExtra("pantalla", pantalla == null ? "inicio" : pantalla)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(c, id, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = new Notification.Builder(c, canal)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle(titulo)
                .setContentText(texto)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .setColor(0xFFB42318);
        if (lineas != null && !lineas.isEmpty()) {
            Notification.InboxStyle st = new Notification.InboxStyle().setBigContentTitle(titulo);
            for (int k = 0; k < Math.min(7, lineas.size()); k++) st.addLine(lineas.get(k));
            if (lineas.size() > 7) st.setSummaryText("y " + (lineas.size() - 7) + " más");
            b.setStyle(st);
        } else {
            b.setStyle(new Notification.BigTextStyle().bigText(texto));
        }
        nm.notify(id, b.build());
    }
}
