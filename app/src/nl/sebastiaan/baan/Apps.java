package nl.sebastiaan.baan;

import android.app.AppOpsManager;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.AdaptiveIconDrawable;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Installed apps, their icons (colour + HUD mask) and the ranking for the arc. */
final class Apps {
    static final class Item {
        String label, pkg, cls, search;
        Bitmap color;   // full colour icon
        Bitmap mask;    // alpha mask used for the monochrome HUD style
        long installed;
        String key() { return pkg + "/" + cls; }
    }

    interface Listener { void onApps(); }

    static volatile List<Item> all = new ArrayList<>();
    static volatile List<Item> arc = new ArrayList<>();
    static final ArrayList<Listener> listeners = new ArrayList<>();
    private static final Handler main = new Handler(Looper.getMainLooper());
    private static volatile boolean loading;

    static final String[] GUESS = {"com.whatsapp", "com.google.android.gm", "com.google.android.apps.maps",
            "com.spotify.music", "com.android.chrome", "com.google.android.dialer", "com.oneplus.camera",
            "com.android.camera", "com.google.android.GoogleCamera", "com.google.android.apps.photos",
            "com.google.android.calendar", "com.google.android.apps.messaging"};

    static void load(final Context ctx) {
        if (loading) return;
        loading = true;
        final Context c = ctx.getApplicationContext();
        new Thread(new Runnable() {
            @Override public void run() {
                final ArrayList<Item> list = new ArrayList<>();
                try {
                    PackageManager pm = c.getPackageManager();
                    Intent i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
                    HashMap<String, Item> old = new HashMap<>();
                    for (Item it : all) old.put(it.key(), it);
                    for (ResolveInfo ri : pm.queryIntentActivities(i, 0)) {
                        if (ri.activityInfo == null || c.getPackageName().equals(ri.activityInfo.packageName)) continue;
                        Item it = new Item();
                        it.pkg = ri.activityInfo.packageName;
                        it.cls = ri.activityInfo.name;
                        CharSequence l = ri.loadLabel(pm);
                        it.label = l == null ? it.pkg : l.toString().trim();
                        it.search = norm(it.label);
                        try { it.installed = pm.getPackageInfo(it.pkg, 0).firstInstallTime; } catch (Exception e) { it.installed = 0; }
                        Item prev = old.get(it.key());
                        if (prev != null && prev.color != null) { it.color = prev.color; it.mask = prev.mask; }
                        else {
                            try {
                                Drawable d = ri.loadIcon(pm);
                                it.color = render(d, 144);
                                it.mask = hudMask(d, it.color);
                            } catch (Throwable ignored) {}
                        }
                        list.add(it);
                    }
                } catch (Throwable ignored) {}
                Collections.sort(list, new Comparator<Item>() {
                    @Override public int compare(Item a, Item b) { return a.label.compareToIgnoreCase(b.label); }
                });
                main.post(new Runnable() {
                    @Override public void run() {
                        all = list;
                        loading = false;
                        rank(c);
                    }
                });
            }
        }).start();
    }

    static String norm(String s) {
        String n = java.text.Normalizer.normalize(s.toLowerCase(), java.text.Normalizer.Form.NFD);
        return n.replaceAll("\\p{M}", "");
    }

    static Bitmap render(Drawable d, int size) {
        Bitmap b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(b);
        d.setBounds(0, 0, size, size);
        d.draw(cv);
        return b;
    }

    /** One-colour silhouette: themed monochrome layer on API 33+, otherwise luminance threshold. */
    static Bitmap hudMask(Drawable d, Bitmap color) {
        if (Build.VERSION.SDK_INT >= 33 && d instanceof AdaptiveIconDrawable) {
            Drawable mono = ((AdaptiveIconDrawable) d).getMonochrome();
            if (mono != null) {
                int s = 144;
                Bitmap b = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888);
                Canvas cv = new Canvas(b);
                // the monochrome layer is designed for the 108dp adaptive canvas; crop like a mask would
                int pad = s / 4;
                mono.setBounds(-pad, -pad, s + pad, s + pad);
                mono.draw(cv);
                return b;
            }
        }
        int s = color.getWidth();
        int[] px = new int[s * s];
        color.getPixels(px, 0, s, 0, 0, s, s);
        double sum = 0;
        int n = 0;
        for (int p : px) if (Color.alpha(p) > 100) { sum += lum(p); n++; }
        double mean = n == 0 ? 128 : sum / n;
        int[] out = new int[s * s];
        int ink = 0;
        for (int i = 0; i < px.length; i++) {
            int p = px[i];
            boolean on = Color.alpha(p) > 100 && lum(p) > mean + 10;
            if (on) ink++;
            out[i] = on ? 0xFFFFFFFF : 0;
        }
        // light glyph on dark plate not found: fall back to the inverse, then to the silhouette
        if (ink < s * s / 40) {
            ink = 0;
            for (int i = 0; i < px.length; i++) {
                boolean on = Color.alpha(px[i]) > 100 && lum(px[i]) < mean - 10;
                if (on) ink++;
                out[i] = on ? 0xFFFFFFFF : 0;
            }
        }
        if (ink < s * s / 40) for (int i = 0; i < px.length; i++) out[i] = Color.alpha(px[i]) > 100 ? 0xFFFFFFFF : 0;
        return Bitmap.createBitmap(out, s, s, Bitmap.Config.ARGB_8888);
    }

    static double lum(int c) { return 0.299 * Color.red(c) + 0.587 * Color.green(c) + 0.114 * Color.blue(c); }

    // ------------------------------------------------------------- ranking

    static Set<String> pinned(Context c) {
        return new HashSet<>(App.prefs(c).getStringSet("pinned", new HashSet<String>()));
    }

    static void setPinned(Context c, Item it, boolean on) {
        Set<String> s = pinned(c);
        if (on) s.add(it.key()); else s.remove(it.key());
        App.prefs(c).edit().putStringSet("pinned", s).apply();
        rank(c);
    }

    // ------------------------------------------------------------- pin-balk
    // Eigen, geordende rij vastgezette apps boven de baan. Opgeslagen als
    // nieuwe-regel-gescheiden sleutels, zodat de volgorde bewaard blijft.
    static List<String> barKeys(Context c) {
        String s = App.prefs(c).getString("barApps", "");
        ArrayList<String> l = new ArrayList<>();
        if (!s.isEmpty()) for (String k : s.split("\n")) if (!k.isEmpty()) l.add(k);
        return l;
    }

    static List<Item> bar(Context c) {
        ArrayList<Item> l = new ArrayList<>();
        for (String k : barKeys(c)) { Item it = byKey(k); if (it != null) l.add(it); }
        return l;
    }

    static boolean inBar(Context c, Item it) { return barKeys(c).contains(it.key()); }

    static void setBar(Context c, Item it, boolean on) {
        List<String> l = barKeys(c);
        l.remove(it.key());
        if (on) l.add(it.key());
        StringBuilder sb = new StringBuilder();
        for (String k : l) { if (sb.length() > 0) sb.append('\n'); sb.append(k); }
        App.prefs(c).edit().putString("barApps", sb.toString()).apply();
        for (Listener li : new ArrayList<>(listeners)) li.onApps();
    }

    static Item byKey(String key) {
        for (Item it : all) if (it.key().equals(key)) return it;
        return null;
    }

    static void counted(Context c, Item it) {
        SharedPreferences p = c.getSharedPreferences("counts", Context.MODE_PRIVATE);
        p.edit().putInt(it.key(), p.getInt(it.key(), 0) + 1).apply();
    }

    static boolean usageAccess(Context c) {
        try {
            AppOpsManager ao = (AppOpsManager) c.getSystemService(Context.APP_OPS_SERVICE);
            return ao.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), c.getPackageName())
                    == AppOpsManager.MODE_ALLOWED;
        } catch (Exception e) { return false; }
    }

    static void rank(final Context c) {
        final List<Item> src = all;
        new Thread(new Runnable() {
            @Override public void run() {
                final Map<String, Double> score = new HashMap<>();
                Map<String, Long> use = new HashMap<>();
                if (usageAccess(c)) {
                    try {
                        UsageStatsManager um = (UsageStatsManager) c.getSystemService(Context.USAGE_STATS_SERVICE);
                        long now = System.currentTimeMillis();
                        for (Map.Entry<String, UsageStats> e : um.queryAndAggregateUsageStats(now - 14L * 86400000, now).entrySet())
                            use.put(e.getKey(), e.getValue().getTotalTimeInForeground());
                    } catch (Exception ignored) {}
                }
                SharedPreferences cnt = c.getSharedPreferences("counts", Context.MODE_PRIVATE);
                Set<String> pin = pinned(c);
                boolean onlyPinned = App.prefs(c).getInt("arcMode", 0) == 1;
                for (Item it : src) {
                    double s = 0;
                    Long u = use.get(it.pkg);
                    if (u != null) s += u / 60000.0;                 // minutes in the last two weeks
                    s += cnt.getInt(it.key(), 0) * 3.0;               // our own launches
                    for (int g = 0; g < GUESS.length; g++) if (GUESS[g].equals(it.pkg)) s += 0.5 - g * 0.01;
                    if (pin.contains(it.key())) s += 1e9;
                    score.put(it.key(), s);
                }
                ArrayList<Item> l = new ArrayList<>();
                for (Item it : src) {
                    double s = score.get(it.key());
                    if (onlyPinned ? s >= 1e9 : s > 0) l.add(it);
                }
                Collections.sort(l, new Comparator<Item>() {
                    @Override public int compare(Item a, Item b) { return Double.compare(score.get(b.key()), score.get(a.key())); }
                });
                if (!onlyPinned) for (Item it : src) { if (l.size() >= 9) break; if (!l.contains(it)) l.add(it); }
                final ArrayList<Item> top = new ArrayList<>(l.subList(0, Math.min(12, l.size())));
                main.post(new Runnable() {
                    @Override public void run() {
                        arc = top;
                        for (Listener li : new ArrayList<>(listeners)) li.onApps();
                    }
                });
            }
        }).start();
    }

    static boolean launch(Context c, Item it) {
        try {
            Intent i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                    .setComponent(new ComponentName(it.pkg, it.cls))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            c.startActivity(i);
            counted(c, it);
            return true;
        } catch (Exception e) { return false; }
    }

    static Item byPkg(String pkg) {
        for (Item it : all) if (it.pkg.equals(pkg)) return it;
        return null;
    }
}
