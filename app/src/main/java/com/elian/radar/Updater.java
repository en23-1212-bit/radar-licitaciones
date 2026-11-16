package com.elian.radar;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Actualizaciones: lee las versiones publicadas en GitHub (solo lectura, sin claves).
 * Si hay una nueva, la app muestra "Actualizar". Android pide un toque para instalar (siempre, por seguridad)
 * y solo la instala si está firmada con la misma llave que la app actual.
 */
public final class Updater {
    private Updater() {}

    public static final String ACCION_INSTALAR = "com.elian.radar.INSTALAR";

    public static long versionActual(Context c) {
        try {
            PackageInfo pi = c.getPackageManager().getPackageInfo(c.getPackageName(), 0);
            return Build.VERSION.SDK_INT >= 28 ? pi.getLongVersionCode() : legacyCode(pi);
        } catch (Exception e) {
            return 0;
        }
    }

    @SuppressWarnings("deprecation")
    private static long legacyCode(PackageInfo pi) { return pi.versionCode; }

    /** Consulta GitHub y guarda el resultado. Si hay versión nueva y se pide, avisa una sola vez por versión. */
    public static JSONObject revisar(Context c, boolean notificar) throws Exception {
        JSONObject rel = new JSONObject(Http.get(Const.RELEASES, 20_000, 1));
        String digitos = rel.optString("tag_name", "").replaceAll("[^0-9]", "");
        long code = digitos.isEmpty() ? 0 : Long.parseLong(digitos);
        String url = "";
        JSONArray assets = rel.optJSONArray("assets");
        for (int i = 0; assets != null && i < assets.length(); i++) {
            JSONObject a = assets.getJSONObject(i);
            String u = a.optString("browser_download_url", "");
            if (a.optString("name").endsWith(".apk") && u.startsWith(Const.DESCARGAS)) { url = u; break; }
        }
        JSONObject u = Store.obj(c, "update");
        u.put("checked", System.currentTimeMillis());
        u.put("code", code);
        u.put("name", "Versión 1.0." + code);
        u.put("url", url);
        boolean nueva = code > versionActual(c) && !url.isEmpty();
        u.put("nueva", nueva);
        if (nueva && notificar && u.optLong("notified", 0) != code) {
            u.put("notified", code);
            Notifier.mostrar(c, Notifier.ID_ACTUALIZACION, Notifier.CANAL_SISTEMA, "Hay una versión nueva del Radar",
                    "Toca aquí y luego \"Actualizar\".", null, "inicio");
        }
        return u;
    }

    /** Revisa como máximo cada 6 horas (al abrir la app o en la revisión de la mañana). */
    public static void revisarSiToca(Context c, boolean notificar) {
        long ultima = Store.obj(c, "update").optLong("checked", 0);
        if (System.currentTimeMillis() - ultima < 6 * Const.H) return;
        try { revisar(c, notificar); } catch (Exception ignored) {}
    }

    public static boolean puedeInstalar(Context c) {
        return c.getPackageManager().canRequestPackageInstalls();
    }

    /** Descarga e inicia la instalación. Devuelve null si va bien o un mensaje de error. */
    public static String instalar(Context c) {
        try {
            JSONObject u = Store.obj(c, "update");
            String url = u.optString("url", "");
            if (!url.startsWith(Const.DESCARGAS)) return "No hay versión nueva.";
            File apk = new File(c.getCacheDir(), "radar-nuevo.apk");
            Http.download(url, apk);
            PackageInstaller pi = c.getPackageManager().getPackageInstaller();
            PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
            params.setAppPackageName(c.getPackageName());
            int id = pi.createSession(params);
            PackageInstaller.Session s = pi.openSession(id);
            try {
                try (InputStream in = new FileInputStream(apk); OutputStream out = s.openWrite("radar.apk", 0, apk.length())) {
                    byte[] buf = new byte[64 * 1024];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                    s.fsync(out);
                }
                Intent i = new Intent(c, MainActivity.class).setAction(ACCION_INSTALAR).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
                int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
                PendingIntent p = PendingIntent.getActivity(c, 77, i, flags);
                s.commit(p.getIntentSender());
            } finally {
                s.close();
            }
            return null;
        } catch (Exception e) {
            return "No se pudo descargar la actualización. Revisa el internet e intenta de nuevo.";
        }
    }
}
