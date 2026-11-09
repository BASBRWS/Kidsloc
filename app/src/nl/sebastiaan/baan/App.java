package nl.sebastiaan.baan;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;

import java.io.PrintWriter;
import java.io.StringWriter;

/** Application: keeps the crash trace of the last run so Settings > Diagnose can show it. */
public class App extends Application {
    static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences("baan", Context.MODE_PRIVATE);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        final Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override public void uncaughtException(Thread t, Throwable e) {
                try {
                    StringWriter sw = new StringWriter();
                    e.printStackTrace(new PrintWriter(sw));
                    String s = sw.toString();
                    if (s.length() > 3000) s = s.substring(0, 3000);
                    prefs(App.this).edit().putString("crash", s).commit();
                } catch (Throwable ignored) {}
                if (prev != null) prev.uncaughtException(t, e);
            }
        });
    }
}
