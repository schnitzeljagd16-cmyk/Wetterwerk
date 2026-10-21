package de.wetterwerk.app;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.widget.RemoteViews;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/** Wetter für Widget und Benachrichtigungen – läuft auch ohne geöffnete App. */
public class WeatherCore {

    static final String PREFS = "wetterwerk";
    static final String CH_RAIN = "regen";
    static final String CH_WARN = "warnungen";
    static final int JOB_ID = 2207;
    static final int ID_RAIN = 101;

    static SharedPreferences prefs(Context c) { return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE); }

    /* ---------- Ort & Einstellungen (kommen aus der App) ---------- */

    static void setLocation(Context c, String name, double lat, double lon) {
        SharedPreferences p = prefs(c);
        boolean changed = Math.abs(p.getFloat("lat", 0) - lat) > 0.01 || Math.abs(p.getFloat("lon", 0) - lon) > 0.01;
        SharedPreferences.Editor e = p.edit().putString("name", name).putFloat("lat", (float) lat).putFloat("lon", (float) lon);
        if (changed) e.remove("rainUntil").remove("warnIds");
        e.apply();
    }

    static String name(Context c) { return prefs(c).getString("name", "Leverkusen"); }
    static double lat(Context c) { return prefs(c).getFloat("lat", 51.0303f); }
    static double lon(Context c) { return prefs(c).getFloat("lon", 6.9843f); }

    static JSONObject settings(Context c) {
        try { return new JSONObject(prefs(c).getString("settings", "{\"rain\":true,\"warn\":true}")); }
        catch (Exception e) { return new JSONObject(); }
    }

    static void saveSettings(Context c, String json) {
        prefs(c).edit().putString("settings", json).apply();
        createChannels(c);
        schedule(c);
    }

    /** Hintergrund-Job alle 15 Minuten: Widget aktualisieren und auf Regen/Warnungen prüfen. */
    static void schedule(Context c) {
        JobScheduler js = (JobScheduler) c.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (js == null) return;
        for (JobInfo j : js.getAllPendingJobs()) if (j.getId() == JOB_ID) return;
        try {
            js.schedule(new JobInfo.Builder(JOB_ID, new ComponentName(c, UpdateJob.class))
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setPeriodic(15 * 60 * 1000L)
                    .setPersisted(true)
                    .build());
        } catch (Exception ignored) { }
    }

    /* ---------- Netzwerk ---------- */

    static String get(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(10000);
        c.setReadTimeout(20000);
        c.setRequestProperty("User-Agent", "Wetterwerk-Android/1.1");
        c.setRequestProperty("Accept", "application/json");
        try {
            if (c.getResponseCode() != 200) throw new Exception("HTTP " + c.getResponseCode());
            InputStream in = c.getInputStream();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            return out.toString("UTF-8");
        } finally {
            c.disconnect();
        }
    }

    /* ---------- Wetter holen ---------- */

    static class Now {
        double temp, max, min;
        int code, cloud;
        boolean day = true;
        String rainText = "";       // z. B. "Regen ab etwa 19:30"
        int rainInMin = -1;         // Minuten bis Regenbeginn, -1 = keiner
        boolean rainingNow = false;
        String rainEnd = null;
        JSONArray alerts = new JSONArray();
    }

    static Now fetch(Context c) throws Exception {
        double la = lat(c), lo = lon(c);
        String url = "https://api.open-meteo.com/v1/forecast?latitude=" + la + "&longitude=" + lo
                + "&timezone=auto&current=temperature_2m,weather_code,is_day,cloud_cover"
                + "&daily=temperature_2m_max,temperature_2m_min&forecast_days=1"
                + "&minutely_15=precipitation&forecast_minutely_15=12";
        JSONObject j = new JSONObject(get(url));
        Now n = new Now();
        JSONObject cur = j.getJSONObject("current");
        n.temp = cur.optDouble("temperature_2m");
        n.code = cur.optInt("weather_code");
        n.cloud = cur.optInt("cloud_cover");
        n.day = cur.optInt("is_day", 1) == 1;
        JSONObject d = j.getJSONObject("daily");
        n.max = d.getJSONArray("temperature_2m_max").optDouble(0);
        n.min = d.getJSONArray("temperature_2m_min").optDouble(0);

        JSONObject m = j.optJSONObject("minutely_15");
        if (m != null) {
            JSONArray t = m.getJSONArray("time"), p = m.getJSONArray("precipitation");
            int firstWet = -1, firstDry = -1;
            for (int i = 0; i < p.length(); i++) {
                boolean wet = p.optDouble(i, 0) >= 0.1;
                if (wet && firstWet < 0) firstWet = i;
                if (!wet && firstDry < 0) firstDry = i;
            }
            n.rainingNow = p.length() > 0 && p.optDouble(0, 0) >= 0.1;
            if (n.rainingNow) {
                n.rainText = firstDry < 0 ? "Regen hält an" : "Regen bis etwa " + t.getString(firstDry).substring(11, 16);
            } else if (firstWet >= 0) {
                n.rainInMin = firstWet * 15;
                n.rainText = "Regen ab etwa " + t.getString(firstWet).substring(11, 16);
                for (int i = firstWet; i < p.length(); i++) if (p.optDouble(i, 0) < 0.1) { n.rainEnd = t.getString(i); break; }
            } else {
                n.rainText = "Kein Regen in den nächsten 3 Std";
            }
        }
        try { applyRadar(n, la, lo); } catch (Exception ignored) { }
        try {
            JSONObject a = new JSONObject(get("https://api.brightsky.dev/alerts?lat=" + la + "&lon=" + lo));
            JSONArray arr = a.optJSONArray("alerts");
            if (arr != null) n.alerts = arr;
        } catch (Exception ignored) { }
        return n;
    }

    /** Regenradar des DWD (5-Minuten-Schritte, 2 Stunden): genauer als das Wettermodell. */
    static void applyRadar(Now n, double la, double lo) throws Exception {
        JSONObject r = new JSONObject(get("https://api.brightsky.dev/radar?lat=" + la + "&lon=" + lo + "&distance=3000&format=plain"));
        JSONArray frames = r.getJSONArray("radar");
        JSONObject pos = r.getJSONObject("latlon_position");
        int px = (int) Math.round(pos.getDouble("x")), py = (int) Math.round(pos.getDouble("y"));
        SimpleDateFormat iso = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US);
        SimpleDateFormat hm = new SimpleDateFormat("HH:mm", Locale.GERMANY);
        long now = System.currentTimeMillis();
        int count = 0, firstWet = -1, firstDry = -1;
        long wetAt = 0, dryAt = 0;
        boolean wetFirst = false;
        for (int i = 0; i < frames.length(); i++) {
            JSONObject f = frames.getJSONObject(i);
            long t = iso.parse(f.getString("timestamp")).getTime();
            if (t < now - 7 * 60 * 1000L) continue;
            JSONArray g = f.getJSONArray("precipitation_5");
            int m = 0;
            for (int dy = -1; dy <= 1; dy++) {
                JSONArray row = g.optJSONArray(py + dy);
                if (row == null) continue;
                for (int dx = -1; dx <= 1; dx++) m = Math.max(m, row.optInt(px + dx, 0));
            }
            boolean wet = m >= 2;
            if (count == 0) wetFirst = wet;
            if (wet && firstWet < 0) { firstWet = count; wetAt = t; }
            if (!wet && firstDry < 0) { firstDry = count; dryAt = t; }
            count++;
        }
        if (count < 6) return;
        n.rainingNow = wetFirst;
        n.rainInMin = -1;
        if (wetFirst) {
            n.rainText = firstDry < 0 ? "Regen hält an" : "Regen bis etwa " + hm.format(new Date(dryAt));
        } else if (firstWet >= 0) {
            n.rainInMin = (int) Math.max(0, (wetAt - now) / 60000L);
            n.rainText = "Regen ab etwa " + hm.format(new Date(wetAt));
        } else {
            n.rainText = "Kein Regen in den nächsten 2 Std";
        }
    }

    /* ---------- Symbole ---------- */

    static int iconFor(int code, int cloud, boolean day) {
        if (code >= 95) return R.drawable.wx_thunder;
        if ((code >= 71 && code <= 77) || code == 85 || code == 86) return R.drawable.wx_snow;
        if (code == 65 || code == 67 || code == 82) return R.drawable.wx_heavy;
        if ((code >= 51 && code <= 64) || code == 66) return R.drawable.wx_rain;
        if (code == 80 || code == 81) return day ? R.drawable.wx_shower_day : R.drawable.wx_shower_night;
        if (code == 45 || code == 48) return R.drawable.wx_fog;
        if (code == 0 || cloud < 20) return day ? R.drawable.wx_clear_day : R.drawable.wx_clear_night;
        if (code == 1 || code == 2 || cloud < 55) return day ? R.drawable.wx_partly_day : R.drawable.wx_partly_night;
        if (code == 3 && cloud >= 85) return R.drawable.wx_overcast;
        return R.drawable.wx_cloudy;
    }

    static String labelFor(int code, int cloud, boolean day) {
        if (code >= 95) return "Gewitter";
        if ((code >= 71 && code <= 77) || code == 85 || code == 86) return "Schnee";
        if (code == 65 || code == 67 || code == 82) return "Starker Regen";
        if (code >= 51 && code <= 67) return "Regen";
        if (code == 80 || code == 81) return "Schauer";
        if (code == 45 || code == 48) return "Nebel";
        if (code == 0 || cloud < 20) return day ? "Sonnig" : "Klar";
        if (code == 1 || code == 2 || cloud < 55) return day ? "Heiter" : "Leicht bewölkt";
        if (cloud >= 85) return "Bedeckt";
        return "Wolkig";
    }

    static int backgroundFor(int code, int cloud, boolean day) {
        if (code >= 95) return R.drawable.wbg_storm;
        if (!day) return R.drawable.wbg_night;
        if ((code >= 51 && code <= 67) || code >= 80) return R.drawable.wbg_rain;
        if ((code >= 71 && code <= 77) || code == 45 || code == 48 || cloud >= 55) return R.drawable.wbg_cloud;
        return R.drawable.wbg_clear;
    }

    /* ---------- Widget ---------- */

    static void updateWidgets(Context c, Now n) {
        AppWidgetManager am = AppWidgetManager.getInstance(c);
        int[] ids = am.getAppWidgetIds(new ComponentName(c, WeatherWidget.class));
        if (ids == null || ids.length == 0) return;
        RemoteViews v = new RemoteViews(c.getPackageName(), R.layout.widget_weather);
        Intent open = new Intent(c, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        v.setOnClickPendingIntent(R.id.w_root, PendingIntent.getActivity(c, 0, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
        v.setTextViewText(R.id.w_place, name(c));
        if (n == null) {
            v.setTextViewText(R.id.w_temp, "--°");
            v.setTextViewText(R.id.w_cond, "Keine Verbindung");
            v.setTextViewText(R.id.w_rain, "Tippen zum Öffnen");
        } else {
            v.setInt(R.id.w_root, "setBackgroundResource", backgroundFor(n.code, n.cloud, n.day));
            v.setImageViewResource(R.id.w_icon, iconFor(n.code, n.cloud, n.day));
            v.setTextViewText(R.id.w_temp, Math.round(n.temp) + "°");
            v.setTextViewText(R.id.w_cond, labelFor(n.code, n.cloud, n.day) + "  ·  " + Math.round(n.max) + "° / " + Math.round(n.min) + "°");
            JSONObject top = topAlert(n.alerts);
            v.setTextViewText(R.id.w_rain, top != null ? "⚠ " + top.optString("event_de", "Wetterwarnung") : n.rainText);
            v.setTextViewText(R.id.w_time, new SimpleDateFormat("HH:mm", Locale.GERMANY).format(new Date()));
        }
        am.updateAppWidget(ids, v);
    }

    static int severityRank(String s) {
        if (s == null) return 0;
        switch (s) { case "extreme": return 4; case "severe": return 3; case "moderate": return 2; case "minor": return 1; default: return 0; }
    }

    static JSONObject topAlert(JSONArray a) {
        JSONObject best = null;
        for (int i = 0; a != null && i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o == null) continue;
            if (best == null || severityRank(o.optString("severity")) > severityRank(best.optString("severity"))) best = o;
        }
        return best;
    }

    /* ---------- Benachrichtigungen ---------- */

    static void createChannels(Context c) {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm == null) return;
        NotificationChannel r = new NotificationChannel(CH_RAIN, "Regen-Alarm", NotificationManager.IMPORTANCE_DEFAULT);
        r.setDescription("Hinweis kurz bevor es an deinem Ort zu regnen beginnt");
        nm.createNotificationChannel(r);
        NotificationChannel w = new NotificationChannel(CH_WARN, "Unwetterwarnungen", NotificationManager.IMPORTANCE_HIGH);
        w.setDescription("Amtliche Warnungen des Deutschen Wetterdienstes");
        nm.createNotificationChannel(w);
    }

    static boolean permitted(Context c) {
        if (Build.VERSION.SDK_INT >= 33)
            return c.checkSelfPermission("android.permission.POST_NOTIFICATIONS") == android.content.pm.PackageManager.PERMISSION_GRANTED;
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        return nm == null || nm.areNotificationsEnabled();
    }

    static void show(Context c, int id, String channel, String title, String text, int icon) {
        if (!permitted(c)) return;
        createChannels(c);
        Intent open = new Intent(c, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(c, id, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        android.app.Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new android.app.Notification.Builder(c, channel)
                : new android.app.Notification.Builder(c);
        b.setSmallIcon(R.drawable.ic_stat)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new android.app.Notification.BigTextStyle().bigText(text))
                .setColor(0xFF2F6FB8)
                .setAutoCancel(true)
                .setContentIntent(pi);
        if (icon != 0) b.setLargeIcon(android.graphics.BitmapFactory.decodeResource(c.getResources(), icon));
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(id, b.build());
    }

    static void checkNotifications(Context c, Now n) {
        JSONObject s = settings(c);
        SharedPreferences p = prefs(c);
        long now = System.currentTimeMillis();

        // Regen-Alarm: nur wenn es gerade trocken ist und innerhalb einer Stunde Regen kommt
        if (s.optBoolean("rain", true) && !n.rainingNow && n.rainInMin >= 0 && n.rainInMin <= 60
                && now > p.getLong("rainUntil", 0)) {
            String when = n.rainInMin <= 15 ? "in wenigen Minuten" : "in etwa " + n.rainInMin + " Minuten";
            show(c, ID_RAIN, CH_RAIN, "Gleich Regen in " + name(c),
                    "Es fängt " + when + " an zu regnen (" + n.rainText.replace("Regen ab etwa ", "ab ") + " Uhr). Schirm mitnehmen!",
                    iconFor(61, 100, true));
            // Für die nächsten 3 Stunden nicht noch einmal melden
            p.edit().putLong("rainUntil", now + 3 * 60 * 60 * 1000L).apply();
        }

        // Unwetterwarnungen: jede Warnung nur einmal melden
        if (s.optBoolean("warn", true) && n.alerts != null) {
            Set<String> seen = new HashSet<>(p.getStringSet("warnIds", new HashSet<>()));
            Set<String> current = new HashSet<>();
            boolean minorToo = s.optBoolean("warnMinor", false);
            for (int i = 0; i < n.alerts.length(); i++) {
                JSONObject a = n.alerts.optJSONObject(i);
                if (a == null) continue;
                String id = a.optString("alert_id", a.optString("id", ""));
                current.add(id);
                if (seen.contains(id)) continue;
                if (!minorToo && severityRank(a.optString("severity")) < 2) continue;
                String head = a.optString("headline_de", a.optString("event_de", "Wetterwarnung"));
                String desc = a.optString("description_de", "");
                show(c, 200 + (id.hashCode() & 0xffff), CH_WARN, "⚠ " + head + " – " + name(c), desc, 0);
            }
            p.edit().putStringSet("warnIds", current).apply();
        }
    }

    /** Wird vom Hintergrund-Job aufgerufen. */
    static void run(Context c) {
        Now n = null;
        try { n = fetch(c); } catch (Exception ignored) { }
        try { updateWidgets(c, n); } catch (Exception ignored) { }
        if (n != null) try { checkNotifications(c, n); } catch (Exception ignored) { }
    }

    static String enc(String s) {
        try { return URLEncoder.encode(s, "UTF-8"); } catch (Exception e) { return s; }
    }
}
