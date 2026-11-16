package com.elian.radar;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Todo se guarda en un solo archivo JSON dentro de la app (estado.json), con copia de respaldo.
 * Android lo incluye en la copia automática de Google del teléfono.
 */
public final class Store {
    private Store() {}

    private static JSONObject estado;
    private static JSONObject catalogo;

    public static synchronized JSONObject catalogo(Context c) {
        if (catalogo == null) {
            try (InputStream in = c.getAssets().open("catalogo.json")) {
                catalogo = new JSONObject(Http.readAll(in));
            } catch (Exception e) {
                catalogo = new JSONObject();
            }
        }
        return catalogo;
    }

    public static synchronized JSONObject get(Context c) {
        if (estado == null) estado = cargar(c);
        return estado;
    }

    public static synchronized JSONObject settingsJson(Context c) {
        JSONObject e = get(c);
        JSONObject merged = Settings.merge(catalogo(c), e.optJSONObject("settings"));
        try { e.put("settings", merged); } catch (JSONException ignored) {}
        return merged;
    }

    public static synchronized Settings settings(Context c) {
        return Settings.from(catalogo(c), settingsJson(c));
    }

    public static synchronized JSONObject obj(Context c, String key) {
        JSONObject e = get(c);
        JSONObject o = e.optJSONObject(key);
        if (o == null) {
            o = new JSONObject();
            try { e.put(key, o); } catch (JSONException ignored) {}
        }
        return o;
    }

    public static synchronized JSONArray arr(Context c, String key) {
        JSONObject e = get(c);
        JSONArray a = e.optJSONArray(key);
        if (a == null) {
            a = new JSONArray();
            try { e.put(key, a); } catch (JSONException ignored) {}
        }
        return a;
    }

    public static synchronized void put(Context c, String key, Object v) {
        try { get(c).put(key, v); } catch (JSONException ignored) {}
    }

    private static final java.util.concurrent.ExecutorService GUARDAR = java.util.concurrent.Executors.newSingleThreadExecutor();
    private static final java.util.concurrent.atomic.AtomicBoolean pendiente = new java.util.concurrent.atomic.AtomicBoolean(false);

    /** Guarda en segundo plano (no traba la pantalla). Varios cambios seguidos se guardan una sola vez. */
    public static void saveAsync(Context c) {
        final Context app = c.getApplicationContext();
        if (pendiente.compareAndSet(false, true)) {
            GUARDAR.execute(() -> {
                pendiente.set(false);
                save(app);
            });
        }
    }

    /** Versión de los ajustes: cambia cada vez que se guardan, para saber cuándo recalcular. */
    public static synchronized int settingsVer(Context c) {
        return get(c).optInt("settingsVer", 1);
    }

    public static synchronized void subirVersionAjustes(Context c) {
        try { get(c).put("settingsVer", settingsVer(c) + 1); } catch (JSONException ignored) {}
    }

    public static synchronized void save(Context c) {
        File dir = c.getFilesDir();
        File tmp = new File(dir, "estado.tmp");
        File main = new File(dir, "estado.json");
        File bak = new File(dir, "estado.bak");
        String data = null;
        for (int intento = 0; intento < 3 && data == null; intento++) {
            try { data = get(c).toString(); } catch (RuntimeException e) { Http.dormir(80); }
        }
        if (data == null) return;
        try (OutputStream out = new FileOutputStream(tmp)) {
            out.write(data.getBytes(StandardCharsets.UTF_8));
            out.flush();
            ((FileOutputStream) out).getFD().sync();
        } catch (IOException e) {
            return;
        }
        if (main.exists()) {
            //noinspection ResultOfMethodCallIgnored
            bak.delete();
            //noinspection ResultOfMethodCallIgnored
            main.renameTo(bak);
        }
        //noinspection ResultOfMethodCallIgnored
        tmp.renameTo(main);
    }

    public static long bytes(Context c) {
        File f = new File(c.getFilesDir(), "estado.json");
        return f.exists() ? f.length() : 0;
    }

    private static JSONObject cargar(Context c) {
        File dir = c.getFilesDir();
        for (String nombre : new String[]{"estado.json", "estado.bak"}) {
            File f = new File(dir, nombre);
            if (!f.exists()) continue;
            try (InputStream in = new FileInputStream(f)) {
                return new JSONObject(Http.readAll(in));
            } catch (Exception ignored) {
                // archivo dañado: se intenta con el respaldo
            }
        }
        return new JSONObject();
    }

    /** Reemplaza los datos del usuario (ajustes, ofertas, facturas) desde una copia exportada. */
    public static synchronized void importar(Context c, JSONObject copia) throws JSONException {
        JSONObject e = get(c);
        if (copia.has("settings")) e.put("settings", copia.getJSONObject("settings"));
        if (copia.has("invoices")) e.put("invoices", copia.getJSONArray("invoices"));
        if (copia.has("offers")) {
            JSONObject offers = copia.getJSONObject("offers");
            JSONObject procs = obj(c, "procs");
            JSONArray n = offers.names();
            for (int i = 0; n != null && i < n.length(); i++) {
                String code = n.getString(i);
                JSONObject o = offers.getJSONObject(code);
                JSONObject p = procs.optJSONObject(code);
                if (p == null) procs.put(code, o); else {
                    p.put("status", o.optString("status"));
                    if (o.has("oferta")) p.put("oferta", o.opt("oferta"));
                }
            }
        }
        save(c);
    }

    public static synchronized JSONObject exportar(Context c) throws JSONException {
        JSONObject out = new JSONObject();
        out.put("app", "radar-licitaciones").put("fecha", System.currentTimeMillis());
        out.put("settings", settingsJson(c));
        out.put("invoices", arr(c, "invoices"));
        JSONObject offers = new JSONObject();
        JSONObject procs = obj(c, "procs");
        JSONArray n = procs.names();
        for (int i = 0; n != null && i < n.length(); i++) {
            JSONObject p = procs.getJSONObject(n.getString(i));
            String st = p.optString("status", "");
            if (!st.isEmpty() && !"descartada".equals(st)) offers.put(n.getString(i), p);
        }
        out.put("offers", offers);
        return out;
    }
}
