package nl.sebastiaan.kidsloc;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Eenmalige instelling op het kindertoestel: naam, endpoint, token — en aan/uit. */
public class MainActivity extends Activity {
    static final int REQ = 1;
    EditText name, url, token, interval;
    TextView status;
    Button toggle;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        SharedPreferences p = App.prefs(this);
        int pad = dp(20);

        ScrollView sv = new ScrollView(this);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(pad, pad, pad, pad);
        sv.addView(col);

        col.addView(head("Kidsloc", 22));
        col.addView(sub("Deelt de locatie van dit toestel met het startscherm van je ouder. "
                + "Vul in wat je ouder je geeft."));

        name = field(col, "Naam van dit kind (bv. Emma)", p.getString("name", ""), InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        url = field(col, "Endpoint-URL (eindigt op /exec)", p.getString("url", ""), InputType.TYPE_TEXT_VARIATION_URI | InputType.TYPE_CLASS_TEXT);
        token = field(col, "Token (geheim woord)", p.getString("token", ""), InputType.TYPE_CLASS_TEXT);
        interval = field(col, "Elke hoeveel minuten delen", String.valueOf(p.getInt("interval", 5)), InputType.TYPE_CLASS_NUMBER);

        col.addView(gap());
        col.addView(btn("1 · Locatie toestaan", new View.OnClickListener() {
            @Override public void onClick(View v) { askLocation(); }
        }));
        col.addView(btn("2 · Op 'Altijd toestaan' zetten", new View.OnClickListener() {
            @Override public void onClick(View v) {
                toast("Kies bij Locatie: Altijd toestaan");
                startSafe(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName())));
            }
        }));
        col.addView(btn("3 · Accu niet beperken", new View.OnClickListener() {
            @Override public void onClick(View v) { batteryExempt(); }
        }));

        col.addView(gap());
        toggle = btn(App.on(this) ? "STOP met delen" : "START met delen", new View.OnClickListener() {
            @Override public void onClick(View v) { onToggle(); }
        });
        toggle.setTextSize(17);
        toggle.setPadding(0, dp(14), 0, dp(14));
        col.addView(toggle);

        status = sub("");
        col.addView(status);

        setContentView(sv);
    }

    @Override protected void onResume() { super.onResume(); refresh(); }

    // ---- bediening
    void onToggle() {
        if (App.on(this)) {
            App.prefs(this).edit().putBoolean("on", false).apply();
            Reporter.stop(this);
            refresh();
            return;
        }
        save();
        if (!App.configured(this)) { toast("Vul naam, endpoint en token in"); return; }
        if (!hasLocation()) { toast("Geef eerst locatietoestemming (knop 1)"); askLocation(); return; }
        App.prefs(this).edit().putBoolean("on", true).apply();
        Reporter.startIfOn(this);
        toast("Delen gestart");
        refresh();
        if (Build.VERSION.SDK_INT >= 29 && checkSelfPermission(android.Manifest.permission.ACCESS_BACKGROUND_LOCATION) != PackageManager.PERMISSION_GRANTED)
            toast("Tip: zet locatie nog op 'Altijd toestaan' (knop 2)");
    }

    void save() {
        int min = 5;
        try { min = Math.max(1, Integer.parseInt(interval.getText().toString().trim())); } catch (Exception ignored) {}
        App.prefs(this).edit()
                .putString("name", name.getText().toString().trim())
                .putString("url", url.getText().toString().trim())
                .putString("token", token.getText().toString().trim())
                .putInt("interval", min)
                .apply();
    }

    void refresh() {
        boolean on = App.on(this);
        toggle.setText(on ? "STOP met delen" : "START met delen");
        SharedPreferences p = App.prefs(this);
        long last = p.getLong("lastSent", 0);
        String err = p.getString("lastErr", "");
        StringBuilder s = new StringBuilder();
        s.append(on ? "● Delen staat AAN" : "○ Delen staat uit").append("\n");
        s.append("Locatie: ").append(hasLocation() ? "toegestaan" : "nog niet toegestaan");
        if (Build.VERSION.SDK_INT >= 29)
            s.append(checkSelfPermission(android.Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED
                    ? " (altijd)" : " (alleen in app)");
        s.append("\n");
        if (last > 0) s.append("Laatst gedeeld: ").append(new SimpleDateFormat("dd-MM HH:mm", Locale.ROOT).format(new Date(last))).append("\n");
        if (!err.isEmpty()) s.append("Laatste fout: ").append(err);
        status.setText(s.toString());
    }

    // ---- toestemmingen
    boolean hasLocation() {
        return checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                || checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    void askLocation() {
        if (Build.VERSION.SDK_INT >= 33)
            requestPermissions(new String[]{android.Manifest.permission.ACCESS_FINE_LOCATION,
                    android.Manifest.permission.ACCESS_COARSE_LOCATION,
                    android.Manifest.permission.POST_NOTIFICATIONS}, REQ);
        else
            requestPermissions(new String[]{android.Manifest.permission.ACCESS_FINE_LOCATION,
                    android.Manifest.permission.ACCESS_COARSE_LOCATION}, REQ);
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        // nooit opnieuw vragen vanuit hier: alleen reageren
        if (code == REQ) {
            if (hasLocation()) toast("Locatie toegestaan — zet 'm nu op 'Altijd toestaan' (knop 2)");
            else toast("Zonder locatie kan Kidsloc niets delen");
            refresh();
        }
    }

    void batteryExempt() {
        try {
            if (Build.VERSION.SDK_INT >= 23) {
                Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + getPackageName()));
                startActivity(i);
            }
        } catch (Throwable t) {
            startSafe(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName())));
        }
    }

    boolean startSafe(Intent i) {
        try { i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); startActivity(i); return true; }
        catch (Throwable t) { toast("Niet beschikbaar"); return false; }
    }

    void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }

    // ---- UI helpers
    int dp(float v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    TextView head(String s, int sz) {
        TextView t = new TextView(this);
        t.setText(s); t.setTextSize(sz); t.setPadding(0, 0, 0, dp(6));
        return t;
    }

    TextView sub(String s) {
        TextView t = new TextView(this);
        t.setText(s); t.setTextSize(13); t.setPadding(0, dp(6), 0, dp(10));
        t.setTextColor(0xFF888888);
        return t;
    }

    View gap() { View v = new View(this); v.setMinimumHeight(dp(10)); return v; }

    EditText field(ViewGroup parent, String hint, String val, int type) {
        TextView lab = new TextView(this); lab.setText(hint); lab.setTextSize(12); lab.setPadding(0, dp(8), 0, dp(2));
        parent.addView(lab);
        EditText e = new EditText(this);
        e.setHint(hint); e.setText(val); e.setSingleLine(true); e.setInputType(type);
        parent.addView(e);
        return e;
    }

    Button btn(String s, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(s); b.setAllCaps(false); b.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(6);
        b.setLayoutParams(lp);
        return b;
    }
}
