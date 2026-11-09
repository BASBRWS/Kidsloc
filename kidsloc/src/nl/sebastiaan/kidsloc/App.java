package nl.sebastiaan.kidsloc;

import android.content.Context;
import android.content.SharedPreferences;

final class App {
    static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences("kidsloc", Context.MODE_PRIVATE);
    }

    static String url(Context c)   { return prefs(c).getString("url", ""); }
    static String token(Context c) { return prefs(c).getString("token", ""); }
    static String name(Context c)  { return prefs(c).getString("name", ""); }
    static int    interval(Context c) { return prefs(c).getInt("interval", 5); }   // minuten
    static boolean on(Context c)   { return prefs(c).getBoolean("on", false); }

    static boolean configured(Context c) {
        return !url(c).isEmpty() && !token(c).isEmpty() && !name(c).isEmpty();
    }
}
