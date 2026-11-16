package com.elian.radar;

import java.text.Normalizer;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/** Búsqueda de palabras completas, sin acentos y aceptando singular/plural. */
public final class Text {
    private Text() {}

    private static final Pattern MARCAS = Pattern.compile("\\p{M}+");
    private static final Pattern NO_ALNUM = Pattern.compile("[^a-z0-9]+");
    private static final Map<String, Pattern> CACHE = new HashMap<>();

    public static String norm(String s) {
        if (s == null) return "";
        String n = Normalizer.normalize(s.toLowerCase(Locale.ROOT), Normalizer.Form.NFD);
        return MARCAS.matcher(n).replaceAll("");
    }

    /** Texto normalizado con un espacio al inicio y al final, para buscar palabras completas. */
    public static String prep(String s) {
        return " " + NO_ALNUM.matcher(norm(s)).replaceAll(" ").trim() + " ";
    }

    public static synchronized Pattern pattern(String palabra) {
        Pattern p = CACHE.get(palabra);
        if (p != null) return p;
        String limpio = NO_ALNUM.matcher(norm(palabra)).replaceAll(" ").trim();
        if (limpio.isEmpty()) return null;
        StringBuilder sb = new StringBuilder(" ");
        String[] partes = limpio.split(" ");
        for (int i = 0; i < partes.length; i++) {
            String x = partes[i];
            if (i > 0) sb.append(' ');
            sb.append("(?:").append(x).append('|').append(x).append("s|").append(x).append("es");
            if (x.length() > 4 && x.endsWith("s")) sb.append('|').append(x, 0, x.length() - 1);
            if (x.length() > 5 && x.endsWith("es")) sb.append('|').append(x, 0, x.length() - 2);
            sb.append(')');
        }
        sb.append(' ');
        p = Pattern.compile(sb.toString());
        if (CACHE.size() > 2000) CACHE.clear();
        CACHE.put(palabra, p);
        return p;
    }

    /** true si el texto (ya pasado por prep) contiene la palabra o frase completa. */
    public static boolean has(String textoPrep, String palabra) {
        Pattern p = pattern(palabra);
        return p != null && p.matcher(textoPrep).find();
    }

    /** Dígitos iniciales del código UNSPSC que hay que comparar según su nivel. */
    public static String prefijoUnspsc(String code) {
        if (code == null) return "";
        String c = code.replaceAll("[^0-9]", "");
        if (c.length() != 8) return c;
        if (c.endsWith("000000")) return c.substring(0, 2);
        if (c.endsWith("0000")) return c.substring(0, 4);
        if (c.endsWith("00")) return c.substring(0, 6);
        return c;
    }

    public static String monto(double v) {
        if (v <= 0) return "Monto no indicado";
        if (v >= 1_000_000) return String.format(Locale.US, "RD$ %.2f M", v / 1_000_000).replace(".00 M", " M");
        if (v >= 1_000) return String.format(Locale.US, "RD$ %,.0f", v);
        return String.format(Locale.US, "RD$ %.0f", v);
    }
}
