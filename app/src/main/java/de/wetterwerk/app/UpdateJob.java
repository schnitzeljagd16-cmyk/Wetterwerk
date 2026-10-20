package de.wetterwerk.app;

import android.app.job.JobParameters;
import android.app.job.JobService;

/** Läuft etwa alle 15 Minuten: Widget aktualisieren, Regen und Warnungen prüfen. */
public class UpdateJob extends JobService {
    @Override
    public boolean onStartJob(final JobParameters params) {
        new Thread(() -> {
            WeatherCore.run(getApplicationContext());
            jobFinished(params, false);
        }).start();
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return true;
    }
}
