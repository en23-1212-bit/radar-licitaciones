package com.elian.radar;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/** Una revisión completa: leer la DGCP, comparar, avisar, vigilarse y limpiar. */
public final class Engine {
    private Engine() {}

    public interface Listener { void onFin(); }
    public static volatile Listener listener;

    private static final AtomicBoolean corriendo = new AtomicBoolean(false);
    public static boolean ocupado() { return corriendo.get(); }

    private static final String[] GUARDADAS = {"interes", "preparando", "ofertada"};

    public static void correr(Context ctx, String motivo) {
        if (!corriendo.compareAndSet(false, true)) return;
        Context c = ctx.getApplicationContext();
        long ahora = System.currentTimeMillis();
        JSONObject h = Store.obj(c, "health");
        long prevOk = h.optLong("lastOk", 0);
        int leidos = 0, nuevosN = 0;
        try {
            put(h, "lastAttempt", ahora);
            put(h, "lastReason", motivo);
            Settings s = Store.settings(c);
            JSONObject procs = Store.obj(c, "procs");
            boolean primeraVez = procs.length() == 0;
            boolean completo = primeraVez || ahora - h.optLong("lastFull", 0) > 20 * Const.H;

            // 1) Leer procesos publicados
            JSONArray lista = completo
                    ? Dgcp.list("procesos", Dgcp.params("estado", Dgcp.PUBLICADO), 3, 60_000)
                    : Dgcp.list("procesos", Dgcp.params("estado", Dgcp.PUBLICADO, "startdate", Dgcp.dia(ahora, -3), "enddate", Dgcp.dia(ahora, 1)), 2, 45_000);
            leidos = lista.length();

            List<String> cambios = new ArrayList<>();
            Set<String> vistos = new HashSet<>();
            for (int i = 0; i < lista.length(); i++) {
                JSONObject raw = lista.optJSONObject(i);
                if (raw == null) continue;
                String code = raw.optString("codigo_proceso", "").trim();
                if (code.isEmpty()) continue;
                vistos.add(code);
                JSONObject p = procs.optJSONObject(code);
                boolean nuevo = p == null;
                if (nuevo) {
                    p = new JSONObject();
                    p.put("first", ahora);
                    synchronized (procs) { procs.put(code, p); }
                    nuevosN++;
                }
                String cambio = copiar(raw, p);
                if (!nuevo && cambio != null && guardada(p)) cambios.add(corto(p) + ": " + cambio);
            }

            // 2) Guardadas que ya no salen como publicadas: ver en qué quedaron
            if (completo) {
                for (String code : claves(procs)) {
                    JSONObject p = procs.optJSONObject(code);
                    if (p == null || vistos.contains(code) || !guardada(p) || !Dgcp.PUBLICADO.equals(p.optString("est"))) continue;
                    try {
                        JSONArray r = Dgcp.items(Dgcp.get("procesos", Dgcp.params("proceso", code), 30_000));
                        if (r.length() > 0) {
                            String cambio = copiar(r.getJSONObject(0), p);
                            if (cambio != null) cambios.add(corto(p) + ": " + cambio);
                        }
                    } catch (IOException ignored) {}
                    if (Thread.currentThread().isInterrupted()) break;
                }
                put(h, "lastFull", ahora);
            }

            // 3) Artículos de cada proceso nuevo (para detectar "alimentos para pacientes" con carne adentro)
            int consultas = 0;
            for (String code : claves(procs)) {
                if (consultas >= Const.MAX_ARTICULOS_POR_RONDA || Thread.currentThread().isInterrupted()) break;
                JSONObject p = procs.optJSONObject(code);
                if (p == null || p.optBoolean("artsOk") || !abierta(p, ahora) || esObra(p)) continue;
                try {
                    JSONArray arts = Dgcp.list("procesos/articulos", Dgcp.params("proceso", code), 1, 30_000);
                    JSONArray compact = new JSONArray();
                    for (int k = 0; k < arts.length() && k < 30; k++) {
                        JSONObject a = arts.optJSONObject(k);
                        if (a == null) continue;
                        String d = a.optString("descripcion_usuario", "");
                        String d2 = a.optString("descripcion_articulo", "");
                        String texto = (d.isEmpty() || "null".equals(d) ? "" : d) + (d2.isEmpty() || "null".equals(d2) ? "" : " · " + d2);
                        JSONArray x = new JSONArray();
                        x.put(recortar(texto, 140));
                        x.put(a.optString("subclase_unspsc", a.optString("clase_unspsc", "")));
                        x.put(a.optDouble("cantidad", 0));
                        x.put(a.optString("unidad_medida", ""));
                        x.put(a.optDouble("precio_unitario_estimado", 0));
                        compact.put(x);
                    }
                    p.put("arts", compact);
                    p.put("nArts", compact.length());
                    p.put("artsOk", true);
                } catch (Http.HttpError e) {
                    if (e.code == 404 || e.code == 400) p.put("artsOk", true);
                } catch (IOException e) {
                    // se intenta en la próxima ronda
                }
                consultas++;
                Http.dormir(200);
            }

            // 4) Comparar con lo que vende y decidir avisos
            boolean silencio = enSilencio(s, ahora);
            JSONArray pend = Store.arr(c, "pendSum");
            List<String> altas = new ArrayList<>();
            List<String> altasCodes = new ArrayList<>();
            for (String code : claves(procs)) {
                JSONObject p = procs.optJSONObject(code);
                if (p == null || p.optBoolean("avisada") || !abierta(p, ahora)) continue;
                boolean sinArticulos = !p.optBoolean("artsOk") && !esObra(p);
                if (sinArticulos && ahora - p.optLong("first", ahora) < 24 * Const.H) continue; // espera a tener los artículos
                Evaluador.Res r = Evaluador.evaluar(p, s);
                if (!r.mostrar) continue;
                p.put("avisada", true);
                if ("baja".equals(r.prioridad)) continue;
                if (diasRestantes(p, ahora) < s.diasMin) continue;
                if ("alta".equals(r.prioridad) && !silencio && !primeraVez) {
                    altas.add(corto(p) + " · " + Text.monto(p.optDouble("monto")) + " · cierra " + fechaCorta(p.optLong("cierre")));
                    altasCodes.add(code);
                } else if (!contiene(pend, code)) {
                    pend.put(code);
                }
            }
            if (!altas.isEmpty()) {
                if (altas.size() == 1) {
                    JSONObject p = procs.getJSONObject(altasCodes.get(0));
                    Notifier.mostrar(c, altasCodes.get(0).hashCode(), Notifier.CANAL_ALTA, "🔴 " + corto(p),
                            p.optString("tit") + "\n" + Text.monto(p.optDouble("monto")) + " · cierra " + fechaCorta(p.optLong("cierre")), null, "lic");
                } else {
                    Notifier.mostrar(c, 200, Notifier.CANAL_ALTA, "🔴 " + altas.size() + " licitaciones de prioridad alta", altas.get(0), altas, "lic");
                }
            }

            // 5) Cambios en las que guardó
            JSONArray pendCambios = Store.arr(c, "pendCambios");
            for (String x : cambios) pendCambios.put(x);

            String hoy = Dgcp.dia(ahora, 0);
            boolean tocaResumen = !hoy.equals(h.optString("lastSummaryDay")) && minutosHoy(ahora) >= Settings.minutos(s.resumen) && !silencio;
            if (primeraVez) {
                List<String> lineas = lineasPendientes(c, procs, pend, s, ahora);
                Store.put(c, "pendSum", new JSONArray());
                Notifier.mostrar(c, Notifier.ID_RESUMEN, Notifier.CANAL_NORMAL, "Radar listo",
                        lineas.isEmpty() ? "Ya estoy vigilando. Por ahora no hay licitaciones abiertas de lo que vendes."
                                : "Encontré " + lineas.size() + " licitaciones abiertas que te sirven", lineas, "inicio");
            } else if (tocaResumen) {
                put(h, "lastSummaryDay", hoy);
                List<String> lineas = lineasPendientes(c, procs, pend, s, ahora);
                Store.put(c, "pendSum", new JSONArray());
                if (!lineas.isEmpty())
                    Notifier.mostrar(c, Notifier.ID_RESUMEN, Notifier.CANAL_NORMAL, lineas.size() + (lineas.size() == 1 ? " licitación nueva" : " licitaciones nuevas") + " para ti",
                            lineas.get(0), lineas, "inicio");
                recordatorios(c, procs, s, ahora);
                resultados(c, procs, s);
                avisarCambios(c);
                try { Precios.actualizar(c, s, ahora); } catch (Exception ignored) {}
            } else if (!silencio) {
                avisarCambios(c);
            }

            // 6) Autovigilancia: si estuvo mucho tiempo sin revisar, avisar que ya se puso al día
            if (prevOk > 0 && ahora - prevOk > Const.ATRASO_H * Const.H) {
                long horas = (ahora - prevOk) / Const.H;
                Notifier.mostrar(c, Notifier.ID_VIGILANCIA, Notifier.CANAL_SISTEMA, "Me puse al día",
                        "Estuve " + horas + " horas sin revisar (celular apagado, sin internet o ahorro de batería). Ya revisé todo lo que salió.", null, "inicio");
            }

            // 7) Avisarle a él mismo si algo del celular le impide trabajar bien (máximo cada 3 días)
            android.os.PowerManager pm = c.getSystemService(android.os.PowerManager.class);
            if (pm != null && !pm.isIgnoringBatteryOptimizations(c.getPackageName()) && ahora - h.optLong("avisoBateria", 0) > 3 * Const.D) {
                put(h, "avisoBateria", ahora);
                Notifier.mostrar(c, 902, Notifier.CANAL_SISTEMA, "Para no perder avisos",
                        "Toca aquí y luego \"Arreglar\" en Batería. Así Android no me apaga.", null, "a-vigila");
            }

            limpiar(procs, ahora);
            reevaluar(c);
            put(h, "lastOk", ahora);
            put(h, "errs", 0);
            put(h, "lastError", "");
            registrar(c, true, leidos, nuevosN, "");
        } catch (Exception e) {
            String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            put(h, "lastError", msg);
            int errs = h.optInt("errs", 0) + 1;
            put(h, "errs", errs);
            registrar(c, false, leidos, nuevosN, msg);
            if (errs == 3) {
                Notifier.mostrar(c, 903, Notifier.CANAL_SISTEMA, "La DGCP no está respondiendo",
                        "No tienes que hacer nada: sigo intentando solo y me pongo al día cuando vuelva.", null, "a-vigila");
            }
        } finally {
            Store.save(c);
            corriendo.set(false);
            Listener l = listener;
            if (l != null) l.onFin();
        }
    }

    // ---------- avisos del resumen ----------

    private static List<String> lineasPendientes(Context c, JSONObject procs, JSONArray pend, Settings s, long ahora) {
        List<String> lineas = new ArrayList<>();
        for (int i = 0; i < pend.length(); i++) {
            JSONObject p = procs.optJSONObject(pend.optString(i));
            if (p == null || !abierta(p, ahora)) continue;
            Evaluador.Res r = Evaluador.evaluar(p, s);
            if (!r.mostrar) continue;
            lineas.add(("alta".equals(r.prioridad) ? "🔴 " : "") + corto(p) + " · " + Text.monto(p.optDouble("monto")) + " · cierra " + fechaCorta(p.optLong("cierre")));
        }
        return lineas;
    }

    private static void recordatorios(Context c, JSONObject procs, Settings s, long ahora) throws JSONException {
        List<String> lineas = new ArrayList<>();
        for (String code : claves(procs)) {
            JSONObject p = procs.optJSONObject(code);
            if (p == null || !abierta(p, ahora)) continue;
            String st = p.optString("status");
            if (!"interes".equals(st) && !"preparando".equals(st)) continue;
            long dias = diasRestantes(p, ahora);
            boolean tres = "3y1".equals(s.recordar) && dias == 3 && !p.optBoolean("rec3");
            boolean uno = dias <= 1 && !p.optBoolean("rec1");
            if (tres) p.put("rec3", true);
            if (uno) p.put("rec1", true);
            if (tres || uno) lineas.add("⏰ " + corto(p) + " cierra " + (dias <= 0 ? "hoy" : dias == 1 ? "mañana" : "en " + dias + " días"));
        }
        if (!lineas.isEmpty())
            Notifier.mostrar(c, 300, Notifier.CANAL_NORMAL, lineas.size() == 1 ? "Recordatorio" : lineas.size() + " recordatorios", lineas.get(0), lineas, "ofertas");
    }

    private static void avisarCambios(Context c) {
        JSONArray pc = Store.arr(c, "pendCambios");
        if (pc.length() == 0) return;
        List<String> l = new ArrayList<>();
        for (int i = 0; i < pc.length(); i++) l.add("✏️ " + pc.optString(i));
        Notifier.mostrar(c, 301, Notifier.CANAL_NORMAL, l.size() == 1 ? "Cambió una licitación que guardaste" : l.size() + " cambios en tus licitaciones", l.get(0), l, "ofertas");
        Store.put(c, "pendCambios", new JSONArray());
    }

    /** Para las ofertadas: busca el contrato y marca ganada/perdida. */
    private static void resultados(Context c, JSONObject procs, Settings s) {
        List<String> lineas = new ArrayList<>();
        for (String code : claves(procs)) {
            JSONObject p = procs.optJSONObject(code);
            if (p == null || !"ofertada".equals(p.optString("status"))) continue;
            try {
                JSONArray cs = Dgcp.items(Dgcp.get("contratos", Dgcp.params("proceso", code), 30_000));
                if (cs.length() == 0) {
                    String est = p.optString("est");
                    if (est.contains("desierto") || est.contains("Cancelado")) {
                        p.put("status", "perdida");
                        p.put("resultado", est);
                        lineas.add(corto(p) + ": " + est);
                    }
                    continue;
                }
                JSONArray ganadores = new JSONArray();
                boolean gane = false;
                double total = 0;
                for (int i = 0; i < cs.length(); i++) {
                    JSONObject k = cs.getJSONObject(i);
                    String rpe = k.optString("rpe", "").replaceAll("[^0-9]", "");
                    ganadores.put(k.optString("razon_social", "Otro suplidor"));
                    total += k.optDouble("valor_contratado", 0);
                    if (!s.rpe.isEmpty() && s.rpe.equals(rpe)) gane = true;
                }
                p.put("ganadores", ganadores);
                p.put("valorContrato", total);
                if (!s.rpe.isEmpty()) {
                    p.put("status", gane ? "ganada" : "perdida");
                    lineas.add((gane ? "✅ Ganaste: " : "❌ Perdiste: ") + corto(p) + (gane ? "" : " · ganó " + ganadores.optString(0)));
                } else {
                    p.put("status", "resultado");
                    lineas.add("🏁 Resultado: " + corto(p) + " · ganó " + ganadores.optString(0));
                }
            } catch (Exception ignored) {}
            Http.dormir(200);
        }
        if (!lineas.isEmpty())
            Notifier.mostrar(c, 302, Notifier.CANAL_NORMAL, "Resultados publicados", lineas.get(0), lineas, "ofertas");
    }

    // ---------- utilidades ----------

    /** Copia los campos de la API. Devuelve una descripción del cambio importante, o null. */
    static String copiar(JSONObject raw, JSONObject p) throws JSONException {
        long cierreAntes = p.optLong("cierre", 0);
        double montoAntes = p.optDouble("monto", 0);
        String estAntes = p.optString("est", "");
        long apertAntes = p.optLong("apert", 0);
        boolean teniaDatos = p.has("est");

        p.put("tit", recortar(limpio(raw.optString("titulo")), 240));
        p.put("desc", recortar(limpio(raw.optString("descripcion")), 400));
        p.put("inst", limpio(raw.optString("unidad_compra")));
        p.put("mod", limpio(raw.optString("modalidad")));
        p.put("est", limpio(raw.optString("estado_proceso")));
        p.put("monto", raw.optDouble("monto_estimado", 0));
        p.put("div", limpio(raw.optString("divisa")));
        p.put("pub", Dgcp.fecha(raw.optString("fecha_publicacion")));
        p.put("cierre", Dgcp.fecha(raw.optString("fecha_fin_recepcion_ofertas")));
        p.put("apert", Dgcp.fecha(raw.optString("fecha_apertura_ofertas")));
        p.put("url", limpio(raw.optString("url")));
        p.put("obj", limpio(raw.optString("objeto_proceso")));
        p.put("code", limpio(raw.optString("codigo_proceso")));
        if (!teniaDatos) return null;

        List<String> c = new ArrayList<>();
        long cierre = p.optLong("cierre");
        if (cierreAntes > 0 && cierre > 0 && Math.abs(cierre - cierreAntes) > Const.MIN) c.add("cierre del " + fechaCorta(cierreAntes) + " al " + fechaCorta(cierre));
        long apert = p.optLong("apert");
        if (apertAntes > 0 && apert > 0 && Math.abs(apert - apertAntes) > Const.MIN) c.add("apertura del " + fechaCorta(apertAntes) + " al " + fechaCorta(apert));
        double monto = p.optDouble("monto");
        if (montoAntes > 0 && Math.abs(monto - montoAntes) > 1) c.add("monto de " + Text.monto(montoAntes) + " a " + Text.monto(monto));
        if (!estAntes.isEmpty() && !estAntes.equals(p.optString("est"))) c.add("ahora está: " + p.optString("est"));
        return c.isEmpty() ? null : String.join("; ", c);
    }

    static String limpio(String s) {
        if (s == null || "null".equals(s)) return "";
        return s.replaceAll("\\s+", " ").trim();
    }

    static String recortar(String s, int n) {
        return s.length() <= n ? s : s.substring(0, n - 1) + "…";
    }

    static boolean guardada(JSONObject p) {
        String st = p.optString("status", "");
        for (String g : GUARDADAS) if (g.equals(st)) return true;
        return false;
    }

    public static boolean abierta(JSONObject p, long ahora) {
        return Dgcp.PUBLICADO.equals(p.optString("est")) && p.optLong("cierre", 0) > ahora;
    }

    static boolean esObra(JSONObject p) {
        return Text.norm(p.optString("obj")).contains("obra");
    }

    /** Días de calendario que faltan para el cierre (0 = hoy). */
    public static long diasRestantes(JSONObject p, long ahora) {
        long cierre = p.optLong("cierre", 0);
        if (cierre <= 0) return 99;
        return (inicioDia(cierre) - inicioDia(ahora)) / Const.D;
    }

    static long inicioDia(long ms) {
        Calendar c = Calendar.getInstance(Const.TZ, Locale.ROOT);
        c.setTimeInMillis(ms);
        c.set(Calendar.HOUR_OF_DAY, 12);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    static int minutosHoy(long ahora) {
        Calendar c = Calendar.getInstance(Const.TZ, Locale.ROOT);
        c.setTimeInMillis(ahora);
        return c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE);
    }

    static boolean enSilencio(Settings s, long ahora) {
        int m = minutosHoy(ahora), ini = Settings.minutos(s.silencioIni), fin = Settings.minutos(s.silencioFin);
        return ini > fin ? (m >= ini || m < fin) : (m >= ini && m < fin);
    }

    private static final String[] MESES = {"ene", "feb", "mar", "abr", "may", "jun", "jul", "ago", "sep", "oct", "nov", "dic"};

    public static String fechaCorta(long ms) {
        if (ms <= 0) return "sin fecha";
        Calendar c = Calendar.getInstance(Const.TZ, Locale.ROOT);
        c.setTimeInMillis(ms);
        return c.get(Calendar.DAY_OF_MONTH) + " " + MESES[c.get(Calendar.MONTH)];
    }

    /** Nombre corto de la institución para notificaciones. */
    public static String corto(JSONObject p) {
        String inst = p.optString("inst");
        String n = Text.norm(inst);
        if (n.contains("fuerza aerea")) return "Fuerza Aérea";
        if (n.contains("armada")) return "Armada";
        if (n.contains("ejercito")) return "Ejército";
        if (n.contains("policia nacional")) return "Policía Nacional";
        if (n.contains("ministerio de defensa")) return "Ministerio de Defensa";
        if (n.contains("comedores economicos")) return "Comedores Económicos";
        return recortar(inst.isEmpty() ? p.optString("code") : inst, 40);
    }

    static boolean contiene(JSONArray a, String v) {
        for (int i = 0; i < a.length(); i++) if (v.equals(a.optString(i))) return true;
        return false;
    }

    static List<String> claves(JSONObject o) {
        synchronized (o) {
            List<String> r = new ArrayList<>();
            Iterator<String> it = o.keys();
            while (it.hasNext()) r.add(it.next());
            return r;
        }
    }

    /** Clave para saber si el resultado guardado de una licitación sigue sirviendo. */
    static String claveEval(JSONObject p, int ver) {
        return ver + "|" + p.optString("h") + "|" + p.optBoolean("artsOk") + "|" + p.optInt("nArts", 0);
    }

    /** Resultado de comparar una licitación con los ajustes, guardado para no recalcular cada vez. */
    public static JSONObject resultado(JSONObject p, Settings s, int ver) {
        String k = claveEval(p, ver);
        JSONObject m = p.optJSONObject("m");
        if (m != null && k.equals(p.optString("mk"))) return m;
        m = Evaluador.evaluar(p, s).json();
        try { p.put("m", m); p.put("mk", k); } catch (JSONException ignored) {}
        return m;
    }

    /** Recalcula todo de una vez (después de una revisión o de cambiar ajustes). */
    public static void reevaluar(Context c) {
        Settings s = Store.settings(c);
        int ver = Store.settingsVer(c);
        long ahora = System.currentTimeMillis();
        JSONObject procs = Store.obj(c, "procs");
        for (String code : claves(procs)) {
            JSONObject p = procs.optJSONObject(code);
            if (p == null) continue;
            if (!abierta(p, ahora) && p.optString("status", "").isEmpty()) continue;
            synchronized (p) { resultado(p, s, ver); }
        }
    }

    /** Borra lo viejo para que siempre ocupe poco. */
    static void limpiar(JSONObject procs, long ahora) {
        for (String code : claves(procs)) {
            JSONObject p = procs.optJSONObject(code);
            if (p == null) { synchronized (procs) { procs.remove(code); } continue; }
            String st = p.optString("status", "");
            long cierre = p.optLong("cierre", 0);
            long ref = cierre > 0 ? cierre : p.optLong("first", ahora);
            boolean importante = !st.isEmpty() && !"descartada".equals(st);
            long vida = importante ? 400 * Const.D : 30 * Const.D;
            if (ahora - ref > vida) { synchronized (procs) { procs.remove(code); } }
            else if (!importante && !abierta(p, ahora) && cierre > 0 && ahora - cierre > 2 * Const.D) {
                p.remove("arts"); // ya cerró: no hace falta guardar sus artículos
            }
        }
    }

    private static void registrar(Context c, boolean ok, int leidos, int nuevos, String err) {
        JSONArray runs = Store.arr(c, "runs");
        JSONObject r = new JSONObject();
        try {
            r.put("t", System.currentTimeMillis()).put("ok", ok).put("n", leidos).put("nuevos", nuevos).put("err", err);
        } catch (JSONException ignored) {}
        runs.put(r);
        while (runs.length() > 120) runs.remove(0);
    }

    private static void put(JSONObject o, String k, Object v) {
        try { o.put(k, v); } catch (JSONException ignored) {}
    }

    static Set<String> set(String... v) {
        return new LinkedHashSet<>(java.util.Arrays.asList(v));
    }
}
