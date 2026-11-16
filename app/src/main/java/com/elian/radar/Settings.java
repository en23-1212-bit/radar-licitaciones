package com.elian.radar;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** Ajustes ya combinados (catálogo + lo que marcó el usuario), listos para comparar. */
public final class Settings {

    public static final class Cat {
        public final String id, nombre, code, prefijo;
        public final List<String> palabras;
        Cat(String id, String nombre, String code, List<String> palabras) {
            this.id = id; this.nombre = nombre; this.code = code; this.palabras = palabras;
            this.prefijo = Text.prefijoUnspsc(code);
        }
    }

    public static final class Inst {
        public final String id, nombre;
        public final List<String> patrones;
        public final int estado; // 0 nunca, 1 normal, 2 favorita
        Inst(String id, String nombre, List<String> patrones, int estado) {
            this.id = id; this.nombre = nombre; this.patrones = patrones; this.estado = estado;
        }
    }

    public final List<Cat> activas = new ArrayList<>();
    public final List<Cat> inactivas = new ArrayList<>();
    public final List<String> mias = new ArrayList<>();
    public final List<String> excluir = new ArrayList<>();
    public final List<Inst> instituciones = new ArrayList<>();
    public boolean pequenas = true, medianas = true, grandes = true;
    public double montoMin = 0, montoMax = 0, altaMonto = 3_000_000;
    public int diasMin = 3;
    public String resumen = "07:30", silencioIni = "21:00", silencioFin = "06:30", recordar = "3y1", rpe = "";

    /** Una palabra buscada y la categoría a la que pertenece (null = mis nombres / exclusión). */
    public static final class W {
        public final String palabra;
        public final Cat cat;
        W(String palabra, Cat cat) { this.palabra = palabra; this.cat = cat; }
    }

    // Índice por primera palabra: así cada texto se revisa con búsquedas directas, no probando cientos de palabras.
    private java.util.Map<String, List<W>> idxA, idxI, idxE;

    private static void indexar(java.util.Map<String, List<W>> m, String palabra, Cat cat) {
        String t = Text.primerToken(palabra);
        if (t.isEmpty()) return;
        W w = new W(palabra, cat);
        for (String f : Text.formas(t)) {
            List<W> l = m.get(f);
            if (l == null) { l = new ArrayList<>(); m.put(f, l); }
            l.add(w);
        }
    }

    private synchronized void preparar() {
        if (idxA != null) return;
        java.util.Map<String, List<W>> a = new java.util.HashMap<>(), in = new java.util.HashMap<>(), e = new java.util.HashMap<>();
        for (Cat c : activas) for (String w : c.palabras) indexar(a, w, c);
        for (String w : mias) indexar(a, w, null);
        for (Cat c : inactivas) for (String w : c.palabras) indexar(in, w, c);
        for (String w : excluir) indexar(e, w, null);
        idxE = e; idxI = in; idxA = a;
    }

    private static List<W> buscar(java.util.Map<String, List<W>> idx, String prep, String[] toks, boolean basta) {
        List<W> out = new ArrayList<>();
        java.util.Set<W> vistos = new java.util.HashSet<>();
        for (String t : toks) {
            List<W> l = idx.get(t);
            if (l == null) continue;
            for (W w : l) {
                if (!vistos.add(w)) continue;
                if (Text.has(prep, w.palabra)) { out.add(w); if (basta) return out; }
            }
        }
        return out;
    }

    public static String[] tokens(String prep) {
        String t = prep.trim();
        return t.isEmpty() ? new String[0] : t.split(" ");
    }

    /** Palabras de lo que vende que aparecen en el texto. */
    public List<W> activasEn(String prep, String[] toks) { preparar(); return buscar(idxA, prep, toks, false); }
    /** Palabras de categorías no marcadas que aparecen. */
    public List<W> inactivasEn(String prep, String[] toks) { preparar(); return buscar(idxI, prep, toks, false); }
    /** ¿Tiene alguna palabra prohibida? */
    public boolean excluido(String prep) { preparar(); return !buscar(idxE, prep, tokens(prep), true).isEmpty(); }

    /** Valores por defecto de los ajustes (lo que se guarda en estado.json → settings). */
    public static JSONObject defaults(JSONObject catalogo) {
        JSONObject s = new JSONObject();
        try {
            JSONObject on = new JSONObject();
            JSONArray grupos = catalogo.optJSONArray("grupos");
            for (int g = 0; grupos != null && g < grupos.length(); g++) {
                JSONArray items = grupos.getJSONObject(g).optJSONArray("items");
                for (int i = 0; items != null && i < items.length(); i++) {
                    JSONObject it = items.getJSONObject(i);
                    on.put(it.getString("id"), it.optBoolean("on", false));
                }
            }
            s.put("on", on);
            s.put("words", new JSONObject());
            s.put("mine", new JSONArray());
            s.put("exc", new JSONArray(catalogo.optJSONArray("exclusiones") != null ? catalogo.optJSONArray("exclusiones").toString() : "[]"));
            JSONObject inst = new JSONObject();
            JSONArray ins = catalogo.optJSONArray("instituciones");
            for (int i = 0; ins != null && i < ins.length(); i++) {
                JSONObject x = ins.getJSONObject(i);
                inst.put(x.getString("id"), x.optInt("s", 1));
            }
            s.put("inst", inst);
            s.put("sizes", new JSONObject().put("p", true).put("m", true).put("g", true));
            s.put("montoMin", 0);
            s.put("montoMax", 15_000_000);
            s.put("altaMonto", 3_000_000);
            s.put("diasMin", 3);
            s.put("resumen", "07:30");
            s.put("silencioIni", "21:00");
            s.put("silencioFin", "06:30");
            s.put("recordar", "3y1");
            s.put("rpe", "");
        } catch (JSONException ignored) {}
        return s;
    }

    /** Une los ajustes guardados con los valores por defecto (para claves nuevas de versiones futuras). */
    public static JSONObject merge(JSONObject catalogo, JSONObject guardados) {
        JSONObject d = defaults(catalogo);
        if (guardados == null) return d;
        try {
            JSONArray names = guardados.names();
            for (int i = 0; names != null && i < names.length(); i++) {
                String k = names.getString(i);
                Object v = guardados.get(k);
                if (("on".equals(k) || "inst".equals(k)) && v instanceof JSONObject) {
                    JSONObject base = d.getJSONObject(k);
                    JSONObject vv = (JSONObject) v;
                    JSONArray n2 = vv.names();
                    for (int j = 0; n2 != null && j < n2.length(); j++) base.put(n2.getString(j), vv.get(n2.getString(j)));
                } else {
                    d.put(k, v);
                }
            }
        } catch (JSONException ignored) {}
        return d;
    }

    public static Settings from(JSONObject catalogo, JSONObject s) {
        Settings r = new Settings();
        JSONObject on = s.optJSONObject("on");
        JSONObject words = s.optJSONObject("words");
        JSONArray grupos = catalogo.optJSONArray("grupos");
        for (int g = 0; grupos != null && g < grupos.length(); g++) {
            JSONArray items = grupos.optJSONObject(g).optJSONArray("items");
            for (int i = 0; items != null && i < items.length(); i++) {
                JSONObject it = items.optJSONObject(i);
                String id = it.optString("id");
                JSONArray w = words != null && words.optJSONArray(id) != null ? words.optJSONArray(id) : it.optJSONArray("w");
                Cat c = new Cat(id, it.optString("n"), it.optString("code"), strings(w));
                if (on != null && on.optBoolean(id, false)) r.activas.add(c); else r.inactivas.add(c);
            }
        }
        r.mias.addAll(strings(s.optJSONArray("mine")));
        r.excluir.addAll(strings(s.optJSONArray("exc")));
        JSONObject inst = s.optJSONObject("inst");
        JSONArray ins = catalogo.optJSONArray("instituciones");
        for (int i = 0; ins != null && i < ins.length(); i++) {
            JSONObject x = ins.optJSONObject(i);
            String id = x.optString("id");
            int est = inst != null ? inst.optInt(id, x.optInt("s", 1)) : x.optInt("s", 1);
            r.instituciones.add(new Inst(id, x.optString("n"), strings(x.optJSONArray("m")), est));
        }
        JSONObject sizes = s.optJSONObject("sizes");
        if (sizes != null) {
            r.pequenas = sizes.optBoolean("p", true);
            r.medianas = sizes.optBoolean("m", true);
            r.grandes = sizes.optBoolean("g", true);
        }
        r.montoMin = s.optDouble("montoMin", 0);
        r.montoMax = s.optDouble("montoMax", 0);
        r.altaMonto = s.optDouble("altaMonto", 3_000_000);
        r.diasMin = s.optInt("diasMin", 3);
        r.resumen = s.optString("resumen", "07:30");
        r.silencioIni = s.optString("silencioIni", "21:00");
        r.silencioFin = s.optString("silencioFin", "06:30");
        r.recordar = s.optString("recordar", "3y1");
        r.rpe = s.optString("rpe", "").replaceAll("[^0-9]", "");
        return r;
    }

    /** Revisa que los ajustes tengan sentido antes de guardarlos. Devuelve null si están bien. */
    public static String validar(JSONObject s) {
        JSONObject on = s.optJSONObject("on");
        boolean alguna = false;
        JSONArray n = on != null ? on.names() : null;
        for (int i = 0; n != null && i < n.length(); i++) if (on.optBoolean(n.optString(i))) alguna = true;
        if (!alguna && (s.optJSONArray("mine") == null || s.optJSONArray("mine").length() == 0))
            return "Marca al menos una categoría o agrega un nombre propio.";
        double min = s.optDouble("montoMin", 0), max = s.optDouble("montoMax", 0);
        if (min < 0 || max < 0) return "Los montos no pueden ser negativos.";
        if (max > 0 && min > max) return "El monto mínimo no puede ser mayor que el máximo.";
        int dias = s.optInt("diasMin", 3);
        if (dias < 0 || dias > 30) return "Los días deben estar entre 0 y 30.";
        JSONObject sizes = s.optJSONObject("sizes");
        if (sizes != null && !sizes.optBoolean("p") && !sizes.optBoolean("m") && !sizes.optBoolean("g"))
            return "Marca al menos un tamaño de licitación.";
        for (String k : new String[]{"resumen", "silencioIni", "silencioFin"})
            if (!s.optString(k, "07:30").matches("\\d{2}:\\d{2}")) return "Hora no válida.";
        return null;
    }

    public static List<String> strings(JSONArray a) {
        List<String> r = new ArrayList<>();
        for (int i = 0; a != null && i < a.length(); i++) {
            String v = a.optString(i, "").trim();
            if (!v.isEmpty()) r.add(v);
        }
        return r;
    }

    /** Minutos desde medianoche de "HH:MM". */
    public static int minutos(String hhmm) {
        try {
            String[] p = hhmm.split(":");
            return Integer.parseInt(p[0]) * 60 + Integer.parseInt(p[1]);
        } catch (Exception e) {
            return 7 * 60 + 30;
        }
    }
}
