package com.elian.radar;

import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;

import java.util.Calendar;
import java.util.Locale;

/**
 * Usa el programador de tareas de Android (JobScheduler): no hay servicio corriendo todo el tiempo.
 *  - Tarea 1: la próxima revisión (resumen, 2:30 pm, 9:30 pm). Al terminar, programa la siguiente.
 *  - Tarea 2: vigilante interno cada 6 horas. Si la tarea 1 se perdió o hay atraso, la repone.
 * Ambas sobreviven a reinicios del celular y solo corren con internet.
 */
public final class Programador {
    private Programador() {}

    public static final int JOB_RONDA = 1;
    public static final int JOB_VIGILANTE = 2;

    public static void asegurar(Context c) {
        JobScheduler js = c.getSystemService(JobScheduler.class);
        if (js == null) return;
        if (js.getPendingJob(JOB_RONDA) == null) programarSiguiente(c);
        if (js.getPendingJob(JOB_VIGILANTE) == null) {
            JobInfo v = new JobInfo.Builder(JOB_VIGILANTE, new ComponentName(c, RadarJob.class))
                    .setPeriodic(6 * Const.H, Const.H)
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setPersisted(true)
                    .build();
            js.schedule(v);
        }
    }

    public static void programarSiguiente(Context c) {
        JobScheduler js = c.getSystemService(JobScheduler.class);
        if (js == null) return;
        long espera = Math.max(Const.MIN, proximaHora(c, System.currentTimeMillis()) - System.currentTimeMillis());
        JobInfo j = new JobInfo.Builder(JOB_RONDA, new ComponentName(c, RadarJob.class))
                .setMinimumLatency(espera)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPersisted(true)
                .build();
        js.schedule(j);
    }

    /** Horarios fijos: la hora del resumen, 2:30 pm y 9:30 pm (justo después de los cortes de la DGCP). */
    public static int[] horarios(Context c) {
        int res = Settings.minutos(Store.settings(c).resumen);
        return new int[]{res, 14 * 60 + 30, 21 * 60 + 30};
    }

    public static long proximaHora(Context c, long ahora) {
        int[] hs = horarios(c);
        long mejor = Long.MAX_VALUE;
        for (int d = 0; d <= 1; d++) {
            for (int m : hs) {
                Calendar cal = Calendar.getInstance(Const.TZ, Locale.ROOT);
                cal.setTimeInMillis(ahora);
                cal.add(Calendar.DAY_OF_MONTH, d);
                cal.set(Calendar.HOUR_OF_DAY, m / 60);
                cal.set(Calendar.MINUTE, m % 60);
                cal.set(Calendar.SECOND, 0);
                cal.set(Calendar.MILLISECOND, 0);
                long t = cal.getTimeInMillis();
                if (t > ahora + Const.MIN && t < mejor) mejor = t;
            }
        }
        return mejor == Long.MAX_VALUE ? ahora + 6 * Const.H : mejor;
    }

    public static long proximaProgramada(Context c) {
        JobScheduler js = c.getSystemService(JobScheduler.class);
        if (js == null || js.getPendingJob(JOB_RONDA) == null) return 0;
        return proximaHora(c, System.currentTimeMillis());
    }
}
