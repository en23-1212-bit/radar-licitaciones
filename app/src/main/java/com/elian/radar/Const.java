package com.elian.radar;

import java.util.TimeZone;

/** Valores fijos de la app. */
public final class Const {
    private Const() {}

    /** La app solo se conecta a dos sitios: la API pública de la DGCP y las versiones publicadas en GitHub. */
    public static final String API_DEFAULT = "https://datosabiertos.dgcp.gob.do/api-dgcp/v1";
    public static final String REPO = "en23-1212-bit/radar-licitaciones";
    public static final String RELEASES = "https://api.github.com/repos/" + REPO + "/releases/latest";
    /** Solo se aceptan descargas de actualizaciones desde aquí. */
    public static final String DESCARGAS = "https://github.com/" + REPO + "/releases/download/";
    public static final String PORTAL = "https://comunidad.comprasdominicana.gob.do/Public/Tendering/ContractNoticeManagement/Index";

    public static final TimeZone TZ = TimeZone.getTimeZone("America/Santo_Domingo");
    public static final long MIN = 60_000L;
    public static final long H = 3_600_000L;
    public static final long D = 86_400_000L;

    /** Horas sin una revisión buena a partir de las cuales la app se considera atrasada. */
    public static final long ATRASO_H = 14;
    /** Máximo de consultas de artículos por revisión (el resto queda para la siguiente). */
    public static final int MAX_ARTICULOS_POR_RONDA = 250;
}
