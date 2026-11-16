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
 * Actualizaciones desde GitHub Releases. Eli sube una versión; aquí aparece "Actualizar".
 * Android siempre pide un toque para instalar (eso no se puede saltar, y es por seguridad).
 */
public final class Updater {
    private Updater() {}

    public static final String ACCION_INSTALAR = "com.elian.radar.INSTALAR";

    public static long versionActual(Context c) {
        try {
            PackageInfo pi = c.getPackageManager().getPackageInfo(c.getPackageName(), 0);
            return Build.VERSION.SDK_INT >= 28 ? pi.getLongVersionCode() : pi.versionCode;
        } catch (Exception e) {
            return 0;
        }
    }

    public static String nombreVersion(Context c) {
        try {
            return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "?";
        }
    }

    /** Consulta GitHub. Guarda la info y, si hay nueva y se pide, avisa con una notificación (una vez por versión). */
    public static JSONObject revisar(Context c, boolean notificar) throws Exception {
        JSONObject rel = new JSONObject(Http.get(Const.RELEASES, 20_000, 1));
        String digitos = rel.optString("tag_name", "").replaceAll("[^0-9]", "");
        long code = digitos.isEmpty() ? 0 : Long.parseLong(digitos);
        String url = "";
        JSONArray assets = rel.optJSONArray("assets");
        for (int i = 0; assets != null && i < assets.length(); i++) {
            JSONObject a = assets.getJSONObject(i);
            if (a.optString("name").endsWith(".apk")) { url = a.optString("browser_download_url"); break; }
        }
        JSONObject u = Store.obj(c, "update");
        u.put("checked", System.currentTimeMillis());
        u.put("code", code);
        u.put("name", rel.optString("name", "v" + code));
        u.put("notes", rel.optString("body", ""));
        u.put("url", url);
        boolean nueva = code > versionActual(c) && !url.isEmpty();
        u.put("nueva", nueva);
        if (nueva && notificar && u.optLong("notified", 0) != code) {
            u.put("notified", code);
            Notifier.mostrar(c, Notifier.ID_ACTUALIZACION, Notifier.CANAL_SISTEMA, "Hay una versión nueva del Radar",
                    "Abre la app › Ajustes › Copia y actualizaciones › Actualizar.", null, "a-copia");
        }
        return u;
    }

    public static boolean puedeInstalar(Context c) {
        return c.getPackageManager().canRequestPackageInstalls();
    }

    /** Descarga e inicia la instalación. Devuelve null si va bien o un mensaje de error. */
    public static String instalar(Context c) {
        try {
            JSONObject u = Store.obj(c, "update");
            String url = u.optString("url", "");
            if (url.isEmpty()) return "No hay versión nueva.";
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
            //noinspection ResultOfMethodCallIgnored
            apk.deleteOnExit();
            return null;
        } catch (Exception e) {
            return "No se pudo descargar la actualización: " + e.getMessage();
        }
    }
}
