package de.wetterwerk.app;

import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;

/** Wetterwerk-Widget für den Startbildschirm. */
public class WeatherWidget extends AppWidgetProvider {
    @Override
    public void onUpdate(Context context, AppWidgetManager manager, int[] ids) {
        WeatherCore.schedule(context);
        final PendingResult pr = goAsync();
        new Thread(() -> {
            try {
                WeatherCore.Now n = null;
                try { n = WeatherCore.fetch(context); } catch (Exception ignored) { }
                WeatherCore.updateWidgets(context, n);
            } catch (Exception ignored) { }
            pr.finish();
        }).start();
    }
}
