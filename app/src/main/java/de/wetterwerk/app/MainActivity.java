package de.wetterwerk.app;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Looper;
import android.util.Base64;
import android.view.View;
import android.view.Window;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {

    private static final int REQ_LOC = 21;
    private WebView web;
    private final ExecutorService pool = Executors.newFixedThreadPool(4);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        web = new WebView(this);
        web.setBackgroundColor(Color.parseColor("#14263A"));
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setSupportMultipleWindows(false);

        web.addJavascriptInterface(new Bridge(), "AndroidNet");
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                String scheme = uri.getScheme();
                if ("http".equals(scheme) || "https".equals(scheme)) {
                    // Links (z. B. Datenquelle) im normalen Browser öffnen
                    try { startActivity(new Intent(Intent.ACTION_VIEW, uri)); } catch (Exception ignored) { }
                    return true;
                }
                return false;
            }
        });

        WeatherCore.createChannels(this);
        WeatherCore.schedule(this);

        if (savedInstanceState != null) web.restoreState(savedInstanceState);
        else web.loadUrl("file:///android_asset/index.html");
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (web != null) web.evaluateJavascript("window.onAppResume && window.onAppResume()", null);
    }

    private void askNotifyPermission() {
        if (Build.VERSION.SDK_INT >= 33 && !WeatherCore.permitted(getApplicationContext())) {
            runOnUiThread(() -> requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 31));
        }
    }

    /* ---------- Standort ---------- */

    private void locate() {
        if (Build.VERSION.SDK_INT >= 23
                && checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{
                    android.Manifest.permission.ACCESS_COARSE_LOCATION,
                    android.Manifest.permission.ACCESS_FINE_LOCATION}, REQ_LOC);
            return;
        }
        findLocation();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == 31) { web.evaluateJavascript("window.onNotifyPermission && window.onNotifyPermission()", null); return; }
        if (requestCode != REQ_LOC) return;
        boolean ok = false;
        for (int r : results) if (r == PackageManager.PERMISSION_GRANTED) ok = true;
        if (ok) findLocation();
        else geoResult(false, 0, 0, "Standort nicht erlaubt");
    }

    @SuppressWarnings("MissingPermission")
    private void findLocation() {
        try {
            LocationManager lm = (LocationManager) getSystemService(LOCATION_SERVICE);
            Location best = null;
            for (String p : lm.getProviders(true)) {
                Location l = lm.getLastKnownLocation(p);
                if (l != null && (best == null || l.getTime() > best.getTime())) best = l;
            }
            // Letzte Position jünger als 30 Minuten reicht fürs Wetter
            if (best != null && System.currentTimeMillis() - best.getTime() < 30 * 60 * 1000) {
                geoResult(true, best.getLatitude(), best.getLongitude(), "");
                return;
            }
            String provider = lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) ? LocationManager.NETWORK_PROVIDER
                    : lm.isProviderEnabled(LocationManager.GPS_PROVIDER) ? LocationManager.GPS_PROVIDER : null;
            if (provider == null) {
                if (best != null) geoResult(true, best.getLatitude(), best.getLongitude(), "");
                else geoResult(false, 0, 0, "Standortdienste sind ausgeschaltet");
                return;
            }
            final Location fallback = best;
            final boolean[] done = {false};
            final LocationListener listener = new LocationListener() {
                @Override public void onLocationChanged(Location l) {
                    if (done[0]) return;
                    done[0] = true;
                    lm.removeUpdates(this);
                    geoResult(true, l.getLatitude(), l.getLongitude(), "");
                }
                @Override public void onStatusChanged(String p, int s, Bundle b) { }
                @Override public void onProviderEnabled(String p) { }
                @Override public void onProviderDisabled(String p) { }
            };
            lm.requestLocationUpdates(provider, 0, 0, listener, Looper.getMainLooper());
            web.postDelayed(() -> {
                if (done[0]) return;
                done[0] = true;
                lm.removeUpdates(listener);
                if (fallback != null) geoResult(true, fallback.getLatitude(), fallback.getLongitude(), "");
                else geoResult(false, 0, 0, "Standort konnte nicht ermittelt werden");
            }, 15000);
        } catch (Exception e) {
            geoResult(false, 0, 0, "Standort konnte nicht ermittelt werden");
        }
    }

    private void geoResult(boolean ok, double lat, double lon, String msg) {
        final String js = "window.__geoCb && window.__geoCb(" + ok + "," + lat + "," + lon + "," + org.json.JSONObject.quote(msg) + ")";
        runOnUiThread(() -> web.evaluateJavascript(js, null));
    }

    /* ---------- Brücke zur Webseite ---------- */

    public class Bridge {
        /** Der angezeigte Ort gilt auch für Widget und Benachrichtigungen. */
        @JavascriptInterface
        public void setLocation(String name, double lat, double lon) {
            WeatherCore.setLocation(getApplicationContext(), name, lat, lon);
            pool.execute(() -> WeatherCore.run(getApplicationContext()));
        }

        @JavascriptInterface
        public String getNotify() {
            return WeatherCore.settings(getApplicationContext()).toString();
        }

        @JavascriptInterface
        public void setNotify(String json) {
            WeatherCore.saveSettings(getApplicationContext(), json);
            askNotifyPermission();
        }

        @JavascriptInterface
        public boolean hasNotifyPermission() {
            return WeatherCore.permitted(getApplicationContext());
        }

        @JavascriptInterface
        public void testNotify() {
            if (!WeatherCore.permitted(getApplicationContext())) { askNotifyPermission(); return; }
            WeatherCore.show(getApplicationContext(), 99, WeatherCore.CH_RAIN, "Wetterwerk",
                    "So sehen deine Hinweise aus: Regen-Alarm und Unwetterwarnungen für " + WeatherCore.name(getApplicationContext()) + ".", 0);
        }

        @JavascriptInterface
        public void locate() {
            runOnUiThread(MainActivity.this::locate);
        }

        /** Färbt die Statusleiste passend zum Himmel. */
        @JavascriptInterface
        public void setBarColors(final String top, final String bottom) {
            runOnUiThread(() -> {
                try {
                    Window w = getWindow();
                    w.setStatusBarColor(Color.parseColor(top));
                    w.setNavigationBarColor(Color.parseColor(bottom));
                    if (Build.VERSION.SDK_INT >= 23) {
                        View d = w.getDecorView();
                        int f = d.getSystemUiVisibility() & ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
                        if (Build.VERSION.SDK_INT >= 26) f &= ~View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
                        d.setSystemUiVisibility(f);
                    }
                } catch (Exception ignored) { }
            });
        }

        @JavascriptInterface
        public void get(final String id, final String url) {
            pool.execute(() -> {
                boolean ok = false;
                String payload;
                HttpURLConnection c = null;
                try {
                    if (!url.startsWith("https://")) throw new Exception("Nur https erlaubt");
                    c = (HttpURLConnection) new URL(url).openConnection();
                    c.setConnectTimeout(10000);
                    c.setReadTimeout(20000);
                    c.setInstanceFollowRedirects(true);
                    c.setRequestProperty("User-Agent", "Wetterwerk-Android/1.0");
                    c.setRequestProperty("Accept", "application/json");
                    int code = c.getResponseCode();
                    if (code < 200 || code >= 300) throw new Exception("HTTP " + code);
                    InputStream in = c.getInputStream();
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    byte[] buf = new byte[16384];
                    int n;
                    while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
                    in.close();
                    payload = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP);
                    ok = true;
                } catch (Exception e) {
                    payload = String.valueOf(e.getMessage()).replaceAll("[^0-9A-Za-z .:_-]", "");
                } finally {
                    if (c != null) c.disconnect();
                }
                final String js = "window.__netCb(" + quote(id) + "," + ok + ",'" + payload + "')";
                runOnUiThread(() -> web.evaluateJavascript(js, null));
            });
        }
    }

    private static String quote(String s) {
        return "'" + s.replaceAll("[^0-9A-Za-z_-]", "") + "'";
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        web.saveState(outState);
    }

    @Override
    public void onBackPressed() {
        web.evaluateJavascript("window.onAppBack ? window.onAppBack() : false", v -> {
            if (!"true".equals(v)) MainActivity.super.onBackPressed();
        });
    }

    @Override
    protected void onDestroy() {
        pool.shutdownNow();
        web.destroy();
        super.onDestroy();
    }
}
