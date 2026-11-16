package com.elian.radar;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;
import android.webkit.JavascriptInterface;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Puente entre la pantalla (HTML) y la app. Todo devuelve JSON en texto. */
public class Bridge {
    private final MainActivity a;

    Bridge(MainActivity a) { this.a = a; }

    // ---------------- pantallas ----------------

    @JavascriptInterface
    public String home() {
        try {
            long ahora = System.currentTimeMillis();
            com.elian.radar.Settings s = Store.settings(a);
            JSONObject procs = Store.obj(a, "procs");
            List<JSONObject> nuevas = new ArrayList<>();
            int semana = 0, nuevasN = 0;
            for (String code : Engine.claves(procs)) {
                JSONObject p = procs.optJSONObject(code);
                if (p == null || !Engine.abierta(p, ahora)) continue;
                String st = p.optString("status", "");
                if ("descartada".equals(st)) continue;
                Evaluador.Res r = Evaluador.evaluar(p, s);
                if (!r.mostrar) continue;
                long dias = Engine.diasRestantes(p, ahora);
                if (dias <= 7) semana++;
                if (st.isEmpty() && ahora - p.optLong("first", 0) < 72 * Const.H && !"baja".equals(r.prioridad)) {
                    nuevasN++;
                    nuevas.add(card(code, p, r, ahora));
                }
            }
            ordenar(nuevas);
            JSONObject o = new JSONObject();
            o.put("health", estadoCorto());
            o.put("nuevas", new JSONArray(nuevas.subList(0, Math.min(15, nuevas.size()))));
            o.put("nNuevas", nuevasN);
            o.put("semana", semana);
            double debe = 0;
            int viejas = 0;
            JSONArray inv = Store.arr(a, "invoices");
            for (int i = 0; i < inv.length(); i++) {
                JSONObject f = inv.getJSONObject(i);
                if (f.optBoolean("paid")) continue;
                debe += f.optDouble("monto", 0);
                if ((ahora - f.optLong("fecha", ahora)) / Const.D > 60) viejas++;
            }
            o.put("debe", Text.monto(debe));
            o.put("debeN", debe);
            o.put("viejas", viejas);
            o.put("primeraVez", Store.obj(a, "procs").length() == 0);
            return o.toString();
        } catch (Exception e) {
            return err(e);
        }
    }

    @JavascriptInterface
    public String list() {
        try {
            long ahora = System.currentTimeMillis();
            com.elian.radar.Settings s = Store.settings(a);
            JSONObject procs = Store.obj(a, "procs");
            List<JSONObject> out = new ArrayList<>();
            for (String code : Engine.claves(procs)) {
                JSONObject p = procs.optJSONObject(code);
                if (p == null || !Engine.abierta(p, ahora) || "descartada".equals(p.optString("status"))) continue;
                Evaluador.Res r = Evaluador.evaluar(p, s);
                if (!r.mostrar) continue;
                out.add(card(code, p, r, ahora));
            }
            ordenar(out);
            return new JSONArray(out).toString();
        } catch (Exception e) {
            return err(e);
        }
    }

    @JavascriptInterface
    public String detalle(String code) {
        try {
            JSONObject p = Store.obj(a, "procs").optJSONObject(code);
            if (p == null) return "{}";
            Evaluador.Res r = Evaluador.evaluar(p, Store.settings(a));
            JSONObject o = card(code, p, r, System.currentTimeMillis());
            o.put("desc", p.optString("desc"));
            o.put("arts", p.optJSONArray("arts") != null ? p.optJSONArray("arts") : new JSONArray());
            o.put("apertura", Engine.fechaCorta(p.optLong("apert")));
            o.put("publicada", Engine.fechaCorta(p.optLong("pub")));
            o.put("estado", p.optString("est"));
            o.put("ganadores", p.optJSONArray("ganadores") != null ? p.optJSONArray("ganadores") : new JSONArray());
            o.put("oferta", p.optDouble("oferta", 0));
            return o.toString();
        } catch (Exception e) {
            return err(e);
        }
    }

    @JavascriptInterface
    public String offers() {
        try {
            JSONObject procs = Store.obj(a, "procs");
            JSONObject g = new JSONObject();
            String[] estados = {"interes", "preparando", "ofertada", "resultado", "ganada", "perdida"};
            for (String e : estados) g.put(e, new JSONArray());
            long ahora = System.currentTimeMillis();
            int ofertadas = 0, ganadas = 0;
            double adjudicado = 0;
            com.elian.radar.Settings s = Store.settings(a);
            for (String code : Engine.claves(procs)) {
                JSONObject p = procs.optJSONObject(code);
                if (p == null) continue;
                String st = p.optString("status", "");
                if (!g.has(st)) continue;
                JSONObject c = card(code, p, Evaluador.evaluar(p, s), ahora);
                c.put("oferta", p.optDouble("oferta", 0));
                c.put("ganadores", p.optJSONArray("ganadores") != null ? p.optJSONArray("ganadores") : new JSONArray());
                g.getJSONArray(st).put(c);
                if (!"interes".equals(st) && !"preparando".equals(st)) ofertadas++;
                if ("ganada".equals(st)) {
                    ganadas++;
                    adjudicado += p.optDouble("valorContrato", p.optDouble("oferta", 0));
                }
            }
            JSONObject o = new JSONObject();
            o.put("grupos", g).put("ofertadas", ofertadas).put("ganadas", ganadas).put("adjudicado", Text.monto(adjudicado));
            return o.toString();
        } catch (Exception e) {
            return err(e);
        }
    }

    @JavascriptInterface
    public String setStatus(String code, String status, String oferta) {
        try {
            JSONObject p = Store.obj(a, "procs").optJSONObject(code);
            if (p == null) return "no";
            p.put("status", status);
            p.put("stAt", System.currentTimeMillis());
            if (oferta != null && !oferta.isEmpty()) {
                double v = Double.parseDouble(oferta.replaceAll("[^0-9.]", ""));
                if (v > 0) p.put("oferta", v);
            }
            Store.save(a);
            return "ok";
        } catch (Exception e) {
            return err(e);
        }
    }

    /** "No me interesa": devuelve palabras sugeridas para excluir si el motivo es "no vendo eso". */
    @JavascriptInterface
    public String descartar(String code, String motivo) {
        try {
            JSONObject p = Store.obj(a, "procs").optJSONObject(code);
            if (p == null) return "{}";
            p.put("status", "descartada");
            p.put("motivo", motivo);
            Store.save(a);
            JSONObject o = new JSONObject();
            if ("producto".equals(motivo)) {
                Evaluador.Res r = Evaluador.evaluar(p, Store.settings(a));
                o.put("palabras", new JSONArray(r.palabras));
            }
            o.put("inst", p.optString("inst"));
            return o.toString();
        } catch (Exception e) {
            return err(e);
        }
    }

    // ---------------- cobros ----------------

    @JavascriptInterface
    public String invoices() {
        try {
            JSONArray inv = Store.arr(a, "invoices");
            long ahora = System.currentTimeMillis();
            JSONArray out = new JSONArray();
            double debe = 0;
            long sumaDias = 0;
            int pagadas = 0;
            for (int i = 0; i < inv.length(); i++) {
                JSONObject f = new JSONObject(inv.getJSONObject(i).toString());
                long fecha = f.optLong("fecha", ahora);
                if (f.optBoolean("paid")) {
                    long dias = (f.optLong("paidAt", ahora) - fecha) / Const.D;
                    f.put("dias", dias);
                    sumaDias += dias;
                    pagadas++;
                } else {
                    f.put("dias", (ahora - fecha) / Const.D);
                    debe += f.optDouble("monto", 0);
                }
                f.put("fechaTxt", Engine.fechaCorta(fecha));
                f.put("montoTxt", Text.monto(f.optDouble("monto", 0)));
                out.put(f);
            }
            JSONObject o = new JSONObject();
            o.put("items", out).put("debe", Text.monto(debe)).put("promedio", pagadas > 0 ? (sumaDias / pagadas) + " días" : "—");
            return o.toString();
        } catch (Exception e) {
            return err(e);
        }
    }

    @JavascriptInterface
    public String saveInvoice(String json) {
        try {
            JSONObject f = new JSONObject(json);
            if (f.optString("inst").trim().isEmpty()) return "Escribe la institución.";
            if (f.optDouble("monto", 0) <= 0) return "Escribe el monto.";
            JSONArray inv = Store.arr(a, "invoices");
            String id = f.optString("id", "");
            if (id.isEmpty()) {
                f.put("id", "f" + System.currentTimeMillis());
                if (!f.has("fecha")) f.put("fecha", System.currentTimeMillis());
                inv.put(f);
            } else {
                for (int i = 0; i < inv.length(); i++) if (id.equals(inv.getJSONObject(i).optString("id"))) inv.put(i, f);
            }
            Store.save(a);
            return "ok";
        } catch (Exception e) {
            return err(e);
        }
    }

    @JavascriptInterface
    public String invoiceAction(String id, String accion) {
        try {
            JSONArray inv = Store.arr(a, "invoices");
            for (int i = 0; i < inv.length(); i++) {
                JSONObject f = inv.getJSONObject(i);
                if (!id.equals(f.optString("id"))) continue;
                if ("pagada".equals(accion)) { f.put("paid", true); f.put("paidAt", System.currentTimeMillis()); }
                else if ("borrar".equals(accion)) { inv.remove(i); }
                break;
            }
            Store.save(a);
            return "ok";
        } catch (Exception e) {
            return err(e);
        }
    }

    // ---------------- ajustes ----------------

    @JavascriptInterface
    public String catalog() { return Store.catalogo(a).toString(); }

    @JavascriptInterface
    public String settings() { return Store.settingsJson(a).toString(); }

    @JavascriptInterface
    public String saveSettings(String json) {
        try {
            JSONObject s = Settings_merge(new JSONObject(json));
            String problema = com.elian.radar.Settings.validar(s);
            if (problema != null) return problema;
            Store.put(a, "settings", s);
            Store.save(a);
            Programador.programarSiguiente(a);
            return "ok";
        } catch (Exception e) {
            return err(e);
        }
    }

    private JSONObject Settings_merge(JSONObject nuevo) {
        return com.elian.radar.Settings.merge(Store.catalogo(a), nuevo);
    }

    @JavascriptInterface
    public String restoreDefaults() {
        Store.put(a, "settings", com.elian.radar.Settings.defaults(Store.catalogo(a)));
        Store.save(a);
        return "ok";
    }

    @JavascriptInterface
    public String test(String frase) {
        try {
            com.elian.radar.Settings s = com.elian.radar.Settings.from(Store.catalogo(a), Store.settingsJson(a));
            return Evaluador.probar(frase, s).toString();
        } catch (Exception e) {
            return err(e);
        }
    }

    /** Prueba con ajustes aún sin guardar (los que están en pantalla). */
    @JavascriptInterface
    public String testWith(String frase, String settingsJson) {
        try {
            JSONObject sj = Settings_merge(new JSONObject(settingsJson));
            return Evaluador.probar(frase, com.elian.radar.Settings.from(Store.catalogo(a), sj)).toString();
        } catch (Exception e) {
            return err(e);
        }
    }

    @JavascriptInterface
    public String prices() {
        return Store.obj(a, "prices").toString();
    }

    // ---------------- autovigilancia ----------------

    @JavascriptInterface
    public String health() {
        try {
            JSONObject h = Store.obj(a, "health");
            JSONObject o = estadoCorto();
            JSONArray runs = Store.arr(a, "runs");
            long ahora = System.currentTimeMillis();
            int ok = 0, total = 0;
            for (int i = 0; i < runs.length(); i++) {
                JSONObject r = runs.getJSONObject(i);
                if (ahora - r.optLong("t") > 30 * Const.D) continue;
                total++;
                if (r.optBoolean("ok")) ok++;
            }
            o.put("runsOk", ok).put("runsTotal", total);
            JSONObject ultima = runs.length() > 0 ? runs.getJSONObject(runs.length() - 1) : new JSONObject();
            o.put("leidos", ultima.optInt("n", 0));
            o.put("lastError", h.optString("lastError", ""));
            o.put("bytes", Store.bytes(a));
            o.put("version", nombreVersion());
            return o.toString();
        } catch (Exception e) {
            return err(e);
        }
    }

    private JSONObject estadoCorto() throws JSONException {
        JSONObject h = Store.obj(a, "health");
        long ahora = System.currentTimeMillis();
        long lastOk = h.optLong("lastOk", 0);
        boolean notif = Notifier.permitidas(a);
        PowerManager pm = a.getSystemService(PowerManager.class);
        boolean bateria = pm != null && pm.isIgnoringBatteryOptimizations(a.getPackageName());
        int errs = h.optInt("errs", 0);
        String nivel, msg;
        if (Engine.ocupado()) { nivel = "ok"; msg = "Revisando la DGCP ahora…"; }
        else if (!notif) { nivel = "bad"; msg = "Las notificaciones están apagadas: no te puedo avisar."; }
        else if (lastOk == 0) { nivel = "warn"; msg = "Todavía no he revisado. Necesito internet."; }
        else if (ahora - lastOk > Const.ATRASO_H * Const.H) { nivel = "bad"; msg = "Llevo " + ((ahora - lastOk) / Const.H) + " horas sin revisar."; }
        else if (errs >= 3) { nivel = "warn"; msg = "La DGCP no está respondiendo; sigo intentando."; }
        else if (!bateria) { nivel = "warn"; msg = "Todo bien, pero falta quitarme el ahorro de batería."; }
        else { nivel = "ok"; msg = "Todo bien."; }
        long next = Programador.proximaProgramada(a);
        JSONObject o = new JSONObject();
        o.put("nivel", nivel).put("msg", msg)
                .put("lastOk", lastOk > 0 ? hace(lastOk, ahora) : "nunca")
                .put("next", next > 0 ? hora(next) : "—")
                .put("notif", notif).put("bateria", bateria).put("corriendo", Engine.ocupado());
        return o;
    }

    @JavascriptInterface
    public String runNow() {
        if (Engine.ocupado()) return "ocupado";
        new Thread(() -> Engine.correr(a.getApplicationContext(), "manual"), "radar-manual").start();
        return "ok";
    }

    @JavascriptInterface
    public void fixNotif() {
        a.runOnUiThread(() -> {
            if (Build.VERSION.SDK_INT >= 33 && a.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                    && a.shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)) {
                a.requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, MainActivity.REQ_NOTIF);
            } else {
                Intent i = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, a.getPackageName());
                lanzar(i);
            }
        });
    }

    @JavascriptInterface
    @SuppressWarnings("BatteryLife")
    public void fixBattery() {
        Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + a.getPackageName()));
        if (!lanzar(i)) lanzar(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
    }

    @JavascriptInterface
    public void open(String url) {
        if (url != null && (url.startsWith("https://") || url.startsWith("http://"))) a.abrirFuera(url);
        else a.abrirFuera(Const.PORTAL);
    }

    /** Diagnóstico en palabras sencillas, con lo que hay que hacer en cada caso. */
    @JavascriptInterface
    public String diagnostico() {
        try {
            JSONObject h = new JSONObject(health());
            JSONArray l = new JSONArray();
            l.put(linea(h.optBoolean("notif"), "Notificaciones", h.optBoolean("notif") ? "Permitidas." : "Están apagadas: no te puedo avisar.", "notif"));
            l.put(linea(h.optBoolean("bateria"), "Batería", h.optBoolean("bateria") ? "Sin restricciones." : "Android me puede apagar para ahorrar batería.", "bateria"));
            String lastOk = h.optString("lastOk");
            boolean atrasada = "bad".equals(h.optString("nivel")) && h.optBoolean("notif");
            l.put(linea(!atrasada && !"nunca".equals(lastOk), "Última revisión", "nunca".equals(lastOk) ? "Todavía no he revisado. Necesito internet." : lastOk + (atrasada ? " (atrasada: revisa que tenga internet)" : "."), "revisar"));
            String err = h.optString("lastError", "");
            l.put(linea(err.isEmpty(), "Conexión con la DGCP", err.isEmpty() ? "Bien." : "La última vez falló (" + err + "). Sigo intentando solo.", ""));
            l.put(linea(true, "Revisiones del mes", h.optInt("runsOk") + " de " + h.optInt("runsTotal") + " salieron bien.", ""));
            l.put(linea(true, "Versión", h.optString("version"), ""));
            l.put(linea(true, "Celular", "Android " + Build.VERSION.RELEASE + " · " + Build.MANUFACTURER + " " + Build.MODEL, ""));
            return new JSONObject().put("lineas", l).toString();
        } catch (Exception e) {
            return err(e);
        }
    }

    private static JSONObject linea(boolean ok, String titulo, String texto, String accion) throws JSONException {
        return new JSONObject().put("ok", ok).put("t", titulo).put("x", texto).put("a", ok ? "" : accion);
    }

    /** Si él quiere mandarle el diagnóstico a alguien, abre el menú de compartir del celular. */
    @JavascriptInterface
    public void compartir(String texto) {
        Intent i = new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, texto);
        lanzar(Intent.createChooser(i, "Compartir diagnóstico"));
    }

    // ---------------- actualizaciones y copias ----------------

    @JavascriptInterface
    public void exportData() {
        try {
            String json = Store.exportar(a).toString(1);
            Intent i = new Intent(Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(Intent.EXTRA_SUBJECT, "Copia Radar " + Dgcp.dia(System.currentTimeMillis(), 0))
                    .putExtra(Intent.EXTRA_TEXT, json);
            lanzar(Intent.createChooser(i, "Guardar o enviar la copia"));
        } catch (Exception ignored) {}
    }

    @JavascriptInterface
    @SuppressWarnings("deprecation")
    public void importData() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*");
        a.runOnUiThread(() -> {
            try { a.startActivityForResult(i, MainActivity.REQ_IMPORTAR); } catch (Exception ignored) {}
        });
    }

    // ---------------- utilidades ----------------

    private String nombreVersion() {
        try {
            return a.getPackageManager().getPackageInfo(a.getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "?";
        }
    }

    private boolean lanzar(Intent i) {
        try {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            a.startActivity(i);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private JSONObject card(String code, JSONObject p, Evaluador.Res r, long ahora) throws JSONException {
        long dias = Engine.diasRestantes(p, ahora);
        JSONObject o = new JSONObject();
        o.put("code", code)
                .put("inst", p.optString("inst"))
                .put("corto", Engine.corto(p))
                .put("tit", p.optString("tit"))
                .put("mod", p.optString("mod"))
                .put("monto", p.optDouble("monto", 0))
                .put("montoTxt", Text.monto(p.optDouble("monto", 0)))
                .put("cierre", Engine.fechaCorta(p.optLong("cierre")))
                .put("dias", dias)
                .put("abierta", Engine.abierta(p, ahora))
                .put("prio", r.prioridad)
                .put("why", r.porQue)
                .put("status", p.optString("status", ""))
                .put("url", p.optString("url", ""))
                .put("nueva", ahora - p.optLong("first", 0) < 48 * Const.H)
                .put("grupo", grupo(p.optString("inst")));
        return o;
    }

    private static String grupo(String inst) {
        String n = Text.norm(inst);
        if (n.contains("hospital")) return "salud";
        if (n.contains("comedores") || n.contains("inabie") || n.contains("bienestar")) return "social";
        if (n.contains("fuerza aerea") || n.contains("armada") || n.contains("ejercito") || n.contains("policia") || n.contains("defensa")) return "mil";
        return "otro";
    }

    private static void ordenar(List<JSONObject> l) {
        Collections.sort(l, (x, y) -> {
            int px = rank(x.optString("prio")), py = rank(y.optString("prio"));
            if (px != py) return px - py;
            return Long.compare(x.optLong("dias"), y.optLong("dias"));
        });
    }

    private static int rank(String p) {
        return "alta".equals(p) ? 0 : "media".equals(p) ? 1 : 2;
    }

    private static String hace(long t, long ahora) {
        long min = (ahora - t) / Const.MIN;
        if (min < 1) return "ahora";
        if (min < 60) return "hace " + min + " min";
        if (Dgcp.dia(t, 0).equals(Dgcp.dia(ahora, 0))) return "hoy " + hora(t);
        if (Dgcp.dia(t, 1).equals(Dgcp.dia(ahora, 0))) return "ayer " + hora(t);
        return Engine.fechaCorta(t) + " " + hora(t);
    }

    private static String hora(long t) {
        java.util.Calendar c = java.util.Calendar.getInstance(Const.TZ, Locale.ROOT);
        c.setTimeInMillis(t);
        int h = c.get(java.util.Calendar.HOUR_OF_DAY), m = c.get(java.util.Calendar.MINUTE);
        String ampm = h < 12 ? "am" : "pm";
        int h12 = h % 12 == 0 ? 12 : h % 12;
        return String.format(Locale.ROOT, "%d:%02d %s", h12, m, ampm);
    }

    private static String err(Exception e) {
        return "{\"error\":" + JSONObject.quote(String.valueOf(e.getMessage())) + "}";
    }
}
