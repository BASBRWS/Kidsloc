package nl.sebastiaan.baan;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

/**
 * Leest de locatie van de kinderen uit een eigen feed (JSON-array met
 * {name, lat, lon, tst}), bepaalt vanuit de eigen positie de peiling en afstand,
 * en levert samen met de kompasrichting markeringen voor op de ring.
 *
 * Draait alleen terwijl het startscherm zichtbaar is: geen achtergrond-tracker.
 */
final class Peilingen implements SensorEventListener, LocationListener {

    static final class Kind {
        String name;
        double lat, lon, tst, acc, batt;
        float bearing;   // graden vanaf noord, met de klok mee
        double dist;     // meter
        int color;
    }

    static final int[] PALETTE = {0xFF46E3B4, 0xFF5AA9FF, 0xFFFFC24D, 0xFFFF6FA5, 0xFFB388FF, 0xFF7CF4A0};

    private final Context c;
    private final Runnable onUpdate;
    private final SharedPreferences prefs;
    private final Handler h = new Handler(Looper.getMainLooper());
    private final SensorManager sm;
    private final Sensor rot;
    private final LocationManager lm;

    private final float[] rmat = new float[9];
    private final float[] rmap = new float[9];
    private final float[] orient = new float[3];

    double myLat, myLon;
    boolean haveMe;
    float azimuth;                 // gesmoord, graden 0..360 (magnetisch)
    volatile List<Kind> kinderen = new ArrayList<>();
    String error;

    private boolean running;
    private long lastInvalidate;

    Peilingen(Context ctx, Runnable onUpdate) {
        this.c = ctx.getApplicationContext();
        this.onUpdate = onUpdate;
        this.prefs = App.prefs(c);
        this.sm = (SensorManager) c.getSystemService(Context.SENSOR_SERVICE);
        this.rot = sm != null ? sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR) : null;
        this.lm = (LocationManager) c.getSystemService(Context.LOCATION_SERVICE);
    }

    static boolean on(Context c) {
        return App.prefs(c).getBoolean("trackOn", false)
                && !App.prefs(c).getString("feedUrl", "").isEmpty();
    }

    boolean locationPermission() {
        return c.checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
                || c.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    void start() {
        stop();
        if (!on(c)) return;
        running = true;
        if (rot != null) sm.registerListener(this, rot, SensorManager.SENSOR_DELAY_UI);
        if (lm != null && locationPermission()) {
            try {
                Location last = null;
                for (String p : new String[]{LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER}) {
                    if (!lm.isProviderEnabled(p)) continue;
                    Location l = lm.getLastKnownLocation(p);
                    if (l != null && (last == null || l.getTime() > last.getTime())) last = l;
                    lm.requestLocationUpdates(p, 15000, 25, this, Looper.getMainLooper());
                }
                if (last != null) { myLat = last.getLatitude(); myLon = last.getLongitude(); haveMe = true; }
            } catch (Throwable ignored) {}
        }
        h.post(poll);
    }

    void stop() {
        running = false;
        if (sm != null) sm.unregisterListener(this);
        if (lm != null) { try { lm.removeUpdates(this); } catch (Throwable ignored) {} }
        h.removeCallbacks(poll);
    }

    private final Runnable poll = new Runnable() {
        @Override public void run() {
            if (!running) return;
            fetch();
            h.postDelayed(this, 45000);
        }
    };

    private void fetch() {
        final String url = prefs.getString("feedUrl", "");
        if (url.isEmpty()) return;
        new Thread(new Runnable() {
            @Override public void run() {
                String body = null, err = null;
                HttpURLConnection con = null;
                try {
                    con = (HttpURLConnection) new URL(url).openConnection();
                    con.setConnectTimeout(8000);
                    con.setReadTimeout(8000);
                    con.setInstanceFollowRedirects(true);
                    int code = con.getResponseCode();
                    if (code != 200) { err = "feed gaf " + code; }
                    else {
                        StringBuilder sb = new StringBuilder();
                        BufferedReader r = new BufferedReader(new InputStreamReader(con.getInputStream()));
                        String line;
                        while ((line = r.readLine()) != null) sb.append(line);
                        r.close();
                        body = sb.toString();
                    }
                } catch (Throwable t) {
                    err = "feed niet bereikbaar";
                } finally { if (con != null) con.disconnect(); }
                final String fb = body, fe = err;
                h.post(new Runnable() { @Override public void run() { parse(fb, fe); } });
            }
        }).start();
    }

    private void parse(String body, String err) {
        if (body == null) { error = err; signal(); return; }
        ArrayList<Kind> list = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(body);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                if (!o.has("lat") || !o.has("lon")) continue;
                Kind k = new Kind();
                k.name = o.optString("name", "?");
                k.lat = o.getDouble("lat");
                k.lon = o.getDouble("lon");
                k.tst = o.optDouble("tst", 0);
                k.acc = o.optDouble("acc", 0);
                k.batt = o.optDouble("batt", -1);
                k.color = PALETTE[list.size() % PALETTE.length];
                list.add(k);
            }
            error = null;
        } catch (Throwable t) {
            error = "feed onleesbaar";
        }
        kinderen = list;
        recompute();
        signal();
    }

    void recompute() {
        if (!haveMe) return;
        for (Kind k : kinderen) {
            k.bearing = bearing(myLat, myLon, k.lat, k.lon);
            k.dist = distance(myLat, myLon, k.lat, k.lon);
        }
    }

    // ---- sensoren
    @Override public void onSensorChanged(SensorEvent e) {
        if (e.sensor.getType() != Sensor.TYPE_ROTATION_VECTOR) return;
        SensorManager.getRotationMatrixFromVector(rmat, e.values);
        // rechtopstaand gebruikt: scherm naar je toe, bovenkant omhoog
        SensorManager.remapCoordinateSystem(rmat, SensorManager.AXIS_X, SensorManager.AXIS_Z, rmap);
        SensorManager.getOrientation(rmap, orient);
        float deg = (float) Math.toDegrees(orient[0]);
        if (deg < 0) deg += 360;
        // kortste-hoek low-pass voor een rustige naald
        float diff = ((deg - azimuth + 540) % 360) - 180;
        azimuth = (azimuth + diff * 0.15f + 360) % 360;
        long now = SystemClock.uptimeMillis();
        if (Math.abs(diff) > 0.4f && now - lastInvalidate > 90) { lastInvalidate = now; signal(); }
    }

    @Override public void onAccuracyChanged(Sensor sensor, int accuracy) {}

    // ---- locatie
    @Override public void onLocationChanged(Location l) {
        myLat = l.getLatitude(); myLon = l.getLongitude(); haveMe = true;
        recompute(); signal();
    }

    @Override public void onProviderEnabled(String p) {}
    @Override public void onProviderDisabled(String p) {}
    @Override public void onStatusChanged(String p, int s, Bundle b) {}

    private void signal() { if (onUpdate != null) onUpdate.run(); }

    boolean ready() { return on(c) && haveMe && !kinderen.isEmpty(); }

    // ---- aardrijkskunde
    static float bearing(double la1, double lo1, double la2, double lo2) {
        double p1 = Math.toRadians(la1), p2 = Math.toRadians(la2);
        double dl = Math.toRadians(lo2 - lo1);
        double y = Math.sin(dl) * Math.cos(p2);
        double x = Math.cos(p1) * Math.sin(p2) - Math.sin(p1) * Math.cos(p2) * Math.cos(dl);
        double t = Math.toDegrees(Math.atan2(y, x));
        return (float) ((t + 360) % 360);
    }

    static double distance(double la1, double lo1, double la2, double lo2) {
        double R = 6371000;
        double p1 = Math.toRadians(la1), p2 = Math.toRadians(la2);
        double dp = Math.toRadians(la2 - la1), dl = Math.toRadians(lo2 - lo1);
        double a = Math.sin(dp / 2) * Math.sin(dp / 2) + Math.cos(p1) * Math.cos(p2) * Math.sin(dl / 2) * Math.sin(dl / 2);
        return R * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    static String kompas(float deg) {
        String[] d = {"N", "NO", "O", "ZO", "Z", "ZW", "W", "NW"};
        return d[(int) Math.round(deg / 45f) % 8];
    }

    static String afstand(double m) {
        if (m < 1000) return Math.round(m) + " m";
        if (m < 10000) return String.format(java.util.Locale.ROOT, "%.1f km", m / 1000);
        return Math.round(m / 1000) + " km";
    }
}
