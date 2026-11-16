package com.elian.radar;

import android.content.Context;

import org.json.JSONObject;

/**
 * Archivo de ajustes que controla Eli desde GitHub (config/remoto.json).
 * Permite pausar la app, cambiar la dirección de la API o dejar un mensaje, sin sacar versión nueva.
 * Si no se puede leer, se usa la última copia guardada.
 */
public final class Remoto {
    private Remoto() {}

    public static void cargar(Context c) {
        JSONObject guardado = Store.obj(c, "remote");
        try {
            String body = Http.get(Const.REMOTE + "?t=" + (System.currentTimeMillis() / Const.H), 15_000, 1);
            JSONObject r = new JSONObject(body);
            Store.put(c, "remote", r);
            guardado = r;
        } catch (Exception ignored) {
            // sin cambios: se usa lo guardado
        }
        aplicar(guardado);
    }

    public static void aplicar(JSONObject r) {
        String api = r == null ? "" : r.optString("apiBase", "");
        Dgcp.base = api.startsWith("https://") ? api.replaceAll("/+$", "") : Const.API_DEFAULT;
    }
}
