package com.elian.radar;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Una vez por semana baja los precios unitarios de contratos de lo que vende y los resume. */
public final class Precios {
    private Precios() {}

    public static void actualizar(Context c, Settings s, long ahora) throws Exception {
        JSONObject pr = Store.obj(c, "prices");
        if (ahora - pr.optLong("updated", 0) < 7 * Const.D) return;
        Set<String> familias = new LinkedHashSet<>();
        for (Settings.Cat cat : s.activas) {
            if (cat.code.startsWith("50") && cat.code.endsWith("0000")) familias.add(cat.code);
        }
        JSONArray items = new JSONArray();
        for (String fam : familias) {
            JSONArray arts = Dgcp.list("contratos/articulos", Dgcp.params("familia", fam), 1, 60_000);
            for (int i = 0; i < arts.length(); i++) {
                JSONObject a = arts.optJSONObject(i);
                if (a == null) continue;
                double precio = a.optDouble("precio_unitario", 0);
                if (precio <= 0) continue;
                String desc = Engine.limpio(a.optString("descripcion_usuario", "")) + " " + Engine.limpio(a.optString("descripcion_articulo", ""));
                String prep = Text.prep(desc);
                String palabra = null;
                for (Settings.Cat cat : s.activas) {
                    for (String w : cat.palabras) if (Text.has(prep, w)) { palabra = w; break; }
                    if (palabra != null) break;
                }
                if (palabra == null) for (String w : s.mias) if (Text.has(prep, w)) { palabra = w; break; }
                if (palabra == null) continue;
                JSONArray x = new JSONArray();
                x.put(palabra);
                x.put(Engine.limpio(a.optString("unidad_medida", "")).toLowerCase());
                x.put(precio);
                x.put(Dgcp.fecha(a.optString("fecha_creacion_contrato")));
                x.put(Engine.limpio(a.optString("razon_social", "")));
                items.put(x);
                if (items.length() >= 3000) break;
            }
            Http.dormir(500);
        }
        pr.put("updated", ahora);
        pr.put("resumen", resumir(items));
    }

    /** Agrupa por producto + unidad y descarta precios absurdos (10× fuera de la mediana). */
    static JSONArray resumir(JSONArray items) throws JSONException {
        Map<String, List<Double>> grupos = new HashMap<>();
        Map<String, Map<String, Integer>> provs = new HashMap<>();
        for (int i = 0; i < items.length(); i++) {
            JSONArray x = items.getJSONArray(i);
            String key = x.getString(0) + "|" + x.optString(1);
            grupos.computeIfAbsent(key, k -> new ArrayList<>()).add(x.getDouble(2));
            String pv = x.optString(4, "");
            if (!pv.isEmpty()) {
                Map<String, Integer> m = provs.computeIfAbsent(key, k -> new HashMap<>());
                m.put(pv, m.getOrDefault(pv, 0) + 1);
            }
        }
        JSONArray out = new JSONArray();
        for (Map.Entry<String, List<Double>> e : grupos.entrySet()) {
            List<Double> v = e.getValue();
            Collections.sort(v);
            double med = v.get(v.size() / 2);
            List<Double> ok = new ArrayList<>();
            for (double d : v) if (d >= med / 10 && d <= med * 10) ok.add(d);
            if (ok.size() < 2) continue;
            double sum = 0;
            for (double d : ok) sum += d;
            String[] k = e.getKey().split("\\|", -1);
            String top = "";
            Map<String, Integer> m = provs.get(e.getKey());
            if (m != null) {
                int best = 0;
                for (Map.Entry<String, Integer> pe : m.entrySet()) if (pe.getValue() > best) { best = pe.getValue(); top = pe.getKey(); }
            }
            out.put(new JSONObject().put("producto", k[0]).put("unidad", k.length > 1 ? k[1] : "")
                    .put("n", ok.size()).put("prom", sum / ok.size()).put("min", ok.get(0)).put("max", ok.get(ok.size() - 1))
                    .put("top", top));
        }
        return out;
    }
}
