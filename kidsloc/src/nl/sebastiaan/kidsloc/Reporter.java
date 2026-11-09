package nl.sebastiaan.kidsloc;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Looper;

import org.json.JSONObject;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Deelt op de achtergrond periodiek de locatie met het eigen endpoint, in het
 * OwnTracks-formaat. Draait als foreground service met een vaste melding, zodat
 * Android 'm niet afsluit.
 */
public class Reporter extends Service implements LocationListener {

    static final String CH = "kidsloc";
    static final int NID = 1;
    LocationManager lm;
    long lastSent;

    @Override public IBinder onBind(Intent i) { return null; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // ALTIJD eerst startForeground, anders sluit Android de app af.
        startForeground(NID, note("Locatie delen staat aan"));
        if (!App.on(this) || !App.configured(this)) { stopSelf(); return START_NOT_STICKY; }
        start();
        return START_STICKY;
    }

    void start() {
        lm = (LocationManager) getSystemService(LOCATION_SERVICE);
        if (lm == null) return;
        boolean fine = checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        boolean coarse = checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        if (!fine && !coarse) return;
        long minTime = App.interval(this) * 60000L;
        try {
            Location last = null;
            for (String p : new String[]{LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER}) {
                if (!lm.isProviderEnabled(p)) continue;
                lm.requestLocationUpdates(p, minTime, 50, this, Looper.getMainLooper());
                Location l = lm.getLastKnownLocation(p);
                if (l != null && (last == null || l.getTime() > last.getTime())) last = l;
            }
            if (last != null) post(last);   // meteen één positie sturen
        } catch (SecurityException ignored) {
        } catch (Throwable ignored) {}
    }

    @Override public void onLocationChanged(Location l) { post(l); }
    @Override public void onProviderEnabled(String p) {}
    @Override public void onProviderDisabled(String p) {}
    @Override public void onStatusChanged(String p, int s, Bundle b) {}

    void post(final Location l) {
        final String base = App.url(this), token = App.token(this), name = App.name(this);
        if (base.isEmpty() || token.isEmpty() || name.isEmpty()) return;
        final int batt = battery();
        new Thread(new Runnable() {
            @Override public void run() {
                HttpURLConnection con = null;
                try {
                    String slug = name.trim().toLowerCase().replaceAll("[^a-z0-9]", "");
                    String tid = (name.trim() + "XX").substring(0, 2).toUpperCase();
                    JSONObject o = new JSONObject();
                    o.put("_type", "location");
                    o.put("tid", tid);
                    o.put("topic", "kidsloc/" + slug);
                    o.put("lat", l.getLatitude());
                    o.put("lon", l.getLongitude());
                    o.put("tst", l.getTime() / 1000);
                    o.put("acc", l.hasAccuracy() ? Math.round(l.getAccuracy()) : 0);
                    o.put("batt", batt);
                    String sep = base.contains("?") ? "&" : "?";
                    URL u = new URL(base + sep + "token=" + token);
                    con = (HttpURLConnection) u.openConnection();
                    con.setConnectTimeout(8000);
                    con.setReadTimeout(8000);
                    con.setDoOutput(true);
                    con.setRequestMethod("POST");
                    con.setRequestProperty("Content-Type", "application/json");
                    OutputStream os = con.getOutputStream();
                    os.write(o.toString().getBytes("UTF-8"));
                    os.close();
                    int code = con.getResponseCode();
                    con.getInputStream().close();
                    if (code == 200) {
                        lastSent = System.currentTimeMillis();
                        App.prefs(Reporter.this).edit().putLong("lastSent", lastSent).putString("lastErr", "").apply();
                        update("Laatst gedeeld: zojuist");
                    } else {
                        App.prefs(Reporter.this).edit().putString("lastErr", "endpoint gaf " + code).apply();
                        update("Fout: endpoint gaf " + code);
                    }
                } catch (Throwable t) {
                    App.prefs(Reporter.this).edit().putString("lastErr", "geen verbinding").apply();
                    update("Geen verbinding — probeert opnieuw");
                } finally { if (con != null) con.disconnect(); }
            }
        }).start();
    }

    int battery() {
        try {
            Intent i = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (i == null) return -1;
            int l = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1), s = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
            return l < 0 || s <= 0 ? -1 : l * 100 / s;
        } catch (Throwable t) { return -1; }
    }

    void update(String text) {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) nm.notify(NID, note(text));
        } catch (Throwable ignored) {}
    }

    Notification note(String text) {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null && nm.getNotificationChannel(CH) == null) {
                NotificationChannel ch = new NotificationChannel(CH, "Locatie delen", NotificationManager.IMPORTANCE_LOW);
                ch.setShowBadge(false);
                nm.createNotificationChannel(ch);
            }
        }
        PendingIntent pi = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CH) : new Notification.Builder(this);
        return b.setContentTitle("Kidsloc")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setOngoing(true)
                .setContentIntent(pi)
                .build();
    }

    @Override
    public void onDestroy() {
        if (lm != null) { try { lm.removeUpdates(this); } catch (Throwable ignored) {} }
        super.onDestroy();
    }

    // ---- bediening
    static void startIfOn(Context c) {
        if (!App.on(c) || !App.configured(c)) return;
        Intent i = new Intent(c, Reporter.class);
        if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i); else c.startService(i);
    }

    static void stop(Context c) {
        c.stopService(new Intent(c, Reporter.class));
    }
}
