package com.elian.radar;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.Calendar;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Cliente de la API de datos abiertos de la DGCP.
 * Particularidades conocidas (se confirman en la primera prueba real):
 *  - Las fechas vienen con "Z" pero son hora de RD (UTC-4).
 *  - enddate es exclusivo.
 *  - estado se compara exacto ("Proceso publicado").
 *  - Sin resultados: payload.content = null.
 */
public final class Dgcp {
    private Dgcp() {}

    public static final String base = Const.API_DEFAULT;
    public static final String PUBLICADO = "Proceso publicado";

    public static JSONObject get(String path, Map<String, String> params, int readTimeoutMs) throws IOException {
        String url = base + "/" + path + query(params);
        String body = Http.get(url, readTimeoutMs, 3);
        try {
            JSONObject o = new JSONObject(body);
            if (o.optBoolean("hasError", false)) throw new IOException("La DGCP respondió con error: " + o.optString("message"));
            return o;
        } catch (JSONException e) {
            throw new IOException("Respuesta de la DGCP no válida");
        }
    }

    /** Extrae la lista de resultados del sobre de la API. */
    public static JSONArray items(JSONObject raw) {
        if (raw == null) return new JSONArray();
        Object payload = raw.opt("payload");
        if (payload instanceof JSONArray) return (JSONArray) payload;
        if (payload instanceof JSONObject) {
            JSONArray c = ((JSONObject) payload).optJSONArray("content");
            if (c != null) return c;
        }
        JSONArray c = raw.optJSONArray("content");
        return c != null ? c : new JSONArray();
    }

    /** Recorre páginas de 1000 hasta que una venga incompleta o se llegue al tope. */
    public static JSONArray list(String path, Map<String, String> params, int maxPages, int readTimeoutMs) throws IOException {
        JSONArray all = new JSONArray();
        int limit = 1000;
        for (int page = 1; page <= maxPages; page++) {
            Map<String, String> p = new LinkedHashMap<>(params);
            p.put("page", String.valueOf(page));
            p.put("limit", String.valueOf(limit));
            JSONArray it = items(get(path, p, readTimeoutMs));
            for (int i = 0; i < it.length(); i++) all.put(it.opt(i));
            if (it.length() < limit) break;
        }
        return all;
    }

    private static final Pattern FECHA = Pattern.compile("^(\\d{4})-(\\d{2})-(\\d{2})(?:[T ](\\d{2}):(\\d{2})(?::(\\d{2}))?)?");

    /** Convierte una fecha de la API (hora RD aunque diga Z) a milisegundos. 0 si no se puede. */
    public static long fecha(String s) {
        if (s == null || s.isEmpty() || "null".equals(s)) return 0;
        Matcher m = FECHA.matcher(s.trim());
        if (!m.find()) return 0;
        Calendar c = Calendar.getInstance(Const.TZ, Locale.ROOT);
        c.clear();
        c.set(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)) - 1, Integer.parseInt(m.group(3)),
                m.group(4) != null ? Integer.parseInt(m.group(4)) : 0,
                m.group(5) != null ? Integer.parseInt(m.group(5)) : 0,
                m.group(6) != null ? Integer.parseInt(m.group(6)) : 0);
        return c.getTimeInMillis();
    }

    /** "YYYY-MM-DD" en hora RD, sumando días. */
    public static String dia(long ms, int sumarDias) {
        Calendar c = Calendar.getInstance(Const.TZ, Locale.ROOT);
        c.setTimeInMillis(ms);
        c.add(Calendar.DAY_OF_MONTH, sumarDias);
        return String.format(Locale.ROOT, "%04d-%02d-%02d", c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH));
    }

    public static String query(Map<String, String> params) {
        if (params == null || params.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("?");
        try {
            for (Map.Entry<String, String> e : params.entrySet()) {
                if (sb.length() > 1) sb.append('&');
                sb.append(URLEncoder.encode(e.getKey(), "UTF-8")).append('=').append(URLEncoder.encode(e.getValue(), "UTF-8").replace("+", "%20"));
            }
        } catch (UnsupportedEncodingException ignored) {}
        return sb.toString();
    }

    public static Map<String, String> params(String... kv) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        return m;
    }
}
