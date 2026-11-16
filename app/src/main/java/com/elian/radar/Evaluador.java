package com.elian.radar;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Decide si una licitación le sirve, con qué prioridad y por qué. */
public final class Evaluador {
    private Evaluador() {}

    public static final class Res {
        public boolean mostrar;
        public String prioridad = "";   // alta, media, baja
        public String porQue = "";
        public String oculta = "";      // motivo si no se muestra
        public final Set<String> palabras = new LinkedHashSet<>();
        public final Set<String> categorias = new LinkedHashSet<>();
        public final Set<String> sugerir = new LinkedHashSet<>(); // categorías no marcadas que aparecen
        public int institucion = 1;

        public JSONObject json() {
            JSONObject o = new JSONObject();
            try {
                o.put("mostrar", mostrar).put("prio", prioridad).put("why", porQue).put("oculta", oculta)
                        .put("palabras", new JSONArray(palabras)).put("cats", new JSONArray(categorias))
                        .put("sugerir", new JSONArray(sugerir));
            } catch (JSONException ignored) {}
            return o;
        }
    }

    /**
     * proc: {tit, desc, inst, monto, arts:[[descripcion, subclase], ...]}
     */
    public static Res evaluar(JSONObject proc, Settings s) {
        Res r = new Res();
        List<String[]> segmentos = new ArrayList<>();
        segmentos.add(new String[]{"título", proc.optString("tit")});
        segmentos.add(new String[]{"descripción", proc.optString("desc")});
        JSONArray arts = proc.optJSONArray("arts");
        List<String> subclases = new ArrayList<>();
        for (int i = 0; arts != null && i < arts.length(); i++) {
            JSONArray a = arts.optJSONArray(i);
            if (a == null) continue;
            segmentos.add(new String[]{"artículos", a.optString(0)});
            String sub = a.optString(1, "");
            if (!sub.isEmpty() && !s.excluido(Text.prep(a.optString(0)))) subclases.add(sub.replaceAll("[^0-9]", ""));
        }

        Set<String> donde = new LinkedHashSet<>();
        boolean huboExcluidos = false;
        for (String[] seg : segmentos) {
            String prep = Text.prep(seg[1]);
            String[] toks = Settings.tokens(prep);
            if (toks.length == 0) continue;
            if (s.excluido(prep)) { huboExcluidos = true; continue; }
            for (Settings.W w : s.inactivasEn(prep, toks)) r.sugerir.add(w.cat.nombre);
            for (Settings.W w : s.activasEn(prep, toks)) {
                r.palabras.add(w.palabra);
                r.categorias.add(w.cat != null ? w.cat.nombre : "Mis nombres");
                donde.add(seg[0]);
            }
        }

        Set<String> porCodigo = new LinkedHashSet<>();
        for (String sub : subclases) {
            for (Settings.Cat c : s.activas) {
                if (!c.prefijo.isEmpty() && sub.startsWith(c.prefijo)) porCodigo.add(familia(c));
            }
        }

        if (r.palabras.isEmpty() && porCodigo.isEmpty()) {
            r.mostrar = false;
            r.oculta = huboExcluidos ? "exclusion" : "no-coincide";
            return r;
        }

        // Institución
        String inst = Text.norm(proc.optString("inst"));
        r.institucion = 1;
        for (Settings.Inst i : s.instituciones) {
            boolean coincide = false;
            for (String p : i.patrones) if (inst.contains(Text.norm(p))) { coincide = true; break; }
            if (coincide) { r.institucion = i.estado; break; }
        }
        if (r.institucion == 0) { r.mostrar = false; r.oculta = "institucion"; return r; }

        // Rangos de monto
        double monto = proc.optDouble("monto", 0);
        if (monto > 0) {
            boolean tam = monto < 500_000 ? s.pequenas : (monto <= 3_000_000 ? s.medianas : s.grandes);
            if (!tam) { r.mostrar = false; r.oculta = "tamano"; return r; }
            if (s.montoMax > 0 && monto > s.montoMax) { r.mostrar = false; r.oculta = "monto-max"; return r; }
            if (s.montoMin > 0 && monto < s.montoMin) { r.mostrar = false; r.oculta = "monto-min"; return r; }
        }

        r.mostrar = true;
        List<String> partes = new ArrayList<>();
        if (!r.palabras.isEmpty()) {
            List<String> ws = new ArrayList<>(r.palabras);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < Math.min(3, ws.size()); i++) {
                if (i > 0) sb.append(", ");
                sb.append('“').append(ws.get(i)).append('”');
            }
            if (ws.size() > 3) sb.append(" y ").append(ws.size() - 3).append(" más");
            if (!donde.contains("título") && !donde.contains("descripción")) sb.append(" en los artículos (el título no lo dice)");
            partes.add(sb.toString());
            boolean alta = r.institucion == 2 || (s.altaMonto > 0 && monto >= s.altaMonto);
            r.prioridad = alta ? "alta" : "media";
            if (r.institucion == 2) partes.add("institución favorita");
            if (s.altaMonto > 0 && monto >= s.altaMonto) partes.add("más de " + Text.monto(s.altaMonto));
        } else {
            r.prioridad = "baja";
            partes.add("solo por el código oficial de algunos artículos");
        }
        if (!porCodigo.isEmpty()) partes.add("código oficial: " + String.join(", ", porCodigo));
        r.porQue = String.join(" · ", partes);
        return r;
    }

    private static final java.util.Map<String, String> FAMILIAS = new java.util.HashMap<>();
    static {
        FAMILIAS.put("5011", "carnes y aves");
        FAMILIAS.put("5012", "pescados y mariscos");
        FAMILIAS.put("5013", "lácteos y huevos");
        FAMILIAS.put("5015", "aceites y grasas");
        FAMILIAS.put("5016", "azúcar y dulces");
        FAMILIAS.put("5017", "sazones");
        FAMILIAS.put("5018", "panadería");
        FAMILIAS.put("5019", "comida preparada");
        FAMILIAS.put("5020", "bebidas");
        FAMILIAS.put("5022", "cereales y granos");
        FAMILIAS.put("5030", "frutas");
        FAMILIAS.put("5040", "vegetales");
    }

    static String familia(Settings.Cat c) {
        String f = FAMILIAS.get(c.prefijo);
        return f != null ? f : c.nombre.toLowerCase(java.util.Locale.ROOT);
    }

    /** Para el probador de frases en Ajustes. */
    public static JSONObject probar(String frase, Settings s) {
        JSONObject p = new JSONObject();
        try {
            p.put("tit", frase).put("inst", "").put("monto", 0).put("arts", new JSONArray());
        } catch (JSONException ignored) {}
        Res r = evaluar(p, s);
        JSONObject o = r.json();
        try {
            List<String> excl = new ArrayList<>();
            String prep = Text.prep(frase);
            for (String x : s.excluir) if (Text.has(prep, x)) excl.add(x);
            o.put("excluidas", new JSONArray(excl));
            // sugerencias aunque no coincida
            Set<String> sug = new LinkedHashSet<>();
            for (Settings.Cat c : s.inactivas) for (String w : c.palabras) if (Text.has(prep, w)) { sug.add(c.nombre); break; }
            o.put("sugerir", new JSONArray(sug));
        } catch (JSONException ignored) {}
        return o;
    }
}
