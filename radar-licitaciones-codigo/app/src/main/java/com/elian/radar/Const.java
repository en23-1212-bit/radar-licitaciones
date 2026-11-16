package com.elian.radar;

import java.util.TimeZone;

/** Valores fijos de la app. */
public final class Const {
    private Const() {}

    /** Único sitio al que se conecta la app: la API pública de datos abiertos de la DGCP. */
    public static final String API_DEFAULT = "https://datosabiertos.dgcp.gob.do/api-dgcp/v1";
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
