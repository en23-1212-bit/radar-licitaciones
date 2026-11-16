package com.elian.radar;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** GET con reintentos y espera creciente. Solo se usa para la API de la DGCP. */
public final class Http {
    private Http() {}

    public static final class HttpError extends IOException {
        public final int code;
        HttpError(int code, String msg) { super(msg); this.code = code; }
    }

    public static String get(String url, int readTimeoutMs, int maxRetries) throws IOException {
        IOException last = null;
        for (int intento = 0; intento <= maxRetries; intento++) {
            long espera = backoff(intento);
            HttpURLConnection c = null;
            try {
                c = (HttpURLConnection) new URL(url).openConnection();
                c.setConnectTimeout(15_000);
                c.setReadTimeout(readTimeoutMs);
                c.setInstanceFollowRedirects(true);
                c.setRequestProperty("Accept", "application/json");
                c.setRequestProperty("User-Agent", "RadarLicitaciones-Android (uso interno)");
                int code = c.getResponseCode();
                if (code == 200) return readAll(c.getInputStream());
                String ra = c.getHeaderField("Retry-After");
                if (ra != null) {
                    try { espera = Math.min(60_000L, Long.parseLong(ra.trim()) * 1000L); } catch (NumberFormatException ignored) {}
                }
                last = new HttpError(code, "HTTP " + code);
                boolean reintentable = code == 408 || code == 429 || code >= 500;
                if (!reintentable) throw last;
            } catch (HttpError e) {
                if (!(e.code == 408 || e.code == 429 || e.code >= 500)) throw e;
                last = e;
            } catch (IOException e) {
                last = e;
            } finally {
                if (c != null) c.disconnect();
            }
            if (intento < maxRetries) dormir(espera);
        }
        throw last != null ? last : new IOException("Sin respuesta");
    }

    /** Descarga un archivo (para las actualizaciones). */
    public static void download(String url, File destino) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setConnectTimeout(20_000);
            c.setReadTimeout(60_000);
            c.setInstanceFollowRedirects(true);
            c.setRequestProperty("User-Agent", "RadarLicitaciones-Android (uso interno)");
            c.setRequestProperty("Accept", "application/octet-stream");
            int code = c.getResponseCode();
            if (code != 200) throw new HttpError(code, "Descarga HTTP " + code);
            try (InputStream in = c.getInputStream(); OutputStream out = new FileOutputStream(destino)) {
                byte[] buf = new byte[16 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            }
        } finally {
            c.disconnect();
        }
    }

    static String readAll(InputStream in) throws IOException {
        try (InputStream is = in) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[16 * 1024];
            int n;
            while ((n = is.read(buf)) > 0) bo.write(buf, 0, n);
            return new String(bo.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    static long backoff(int intento) {
        long[] t = {2_000, 6_000, 15_000, 30_000};
        return t[Math.min(intento, t.length - 1)];
    }

    static void dormir(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
