package com.elian.radar;

import android.app.job.JobParameters;
import android.app.job.JobService;

/** Lo que Android despierta: hace una revisión corta y se apaga. */
public class RadarJob extends JobService {
    private volatile Thread hilo;
    private volatile boolean detenido;

    @Override
    public boolean onStartJob(JobParameters params) {
        final int id = params.getJobId();
        detenido = false;
        hilo = new Thread(() -> {
            try {
                if (id == Programador.JOB_VIGILANTE) {
                    // Vigilante interno: solo trabaja si hay atraso. No gasta nada si todo va bien.
                    long lastOk = Store.obj(this, "health").optLong("lastOk", 0);
                    if (System.currentTimeMillis() - lastOk > 9 * Const.H) Engine.correr(this, "vigilante");
                } else {
                    Engine.correr(this, "programada");
                }
            } catch (Throwable ignored) {
                // Engine ya registra sus errores; nunca se deja caer la app.
            } finally {
                if (!detenido) jobFinished(params, false);
                if (id == Programador.JOB_RONDA) Programador.programarSiguiente(this);
                Programador.asegurar(this);
            }
        }, "radar-ronda");
        hilo.start();
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        // Android pidió parar (sin internet, ahorro de batería). La próxima ronda o el vigilante se ponen al día.
        detenido = true;
        Thread t = hilo;
        if (t != null) t.interrupt();
        return false;
    }
}
