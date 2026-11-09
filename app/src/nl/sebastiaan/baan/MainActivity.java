package nl.sebastiaan.baan;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.role.RoleManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Insets;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.service.notification.NotificationListenerService;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity {
    static final int REQ_CAL = 11, REQ_HOME = 12, REQ_LOC = 13;

    FrameLayout root;
    HudView hud;
    Drawer drawer;
    SharedPreferences prefs;
    AlertDialog dialog;
    boolean drawerOpen;

    final BroadcastReceiver pkgs = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { Apps.load(MainActivity.this); }
    };
    final BroadcastReceiver batt = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { battery(i); }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = App.prefs(this);
        Window w = getWindow();
        w.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        if (Build.VERSION.SDK_INT >= 30) w.setDecorFitsSystemWindows(false);
        else w.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);

        root = new FrameLayout(this);
        hud = new HudView(this);
        drawer = new Drawer(this);
        drawer.setVisibility(View.GONE);
        root.addView(hud, new FrameLayout.LayoutParams(-1, -1));
        root.addView(drawer, new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);
        root.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @Override public WindowInsets onApplyWindowInsets(View v, WindowInsets in) {
                int top, bottom, ime;
                if (Build.VERSION.SDK_INT >= 30) {
                    Insets s = in.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                    top = s.top; bottom = s.bottom;
                    ime = in.getInsets(WindowInsets.Type.ime()).bottom;
                } else {
                    top = in.getSystemWindowInsetTop(); bottom = in.getStableInsetBottom();
                    ime = in.getSystemWindowInsetBottom();
                }
                hud.setInsets(top, bottom);
                drawer.setPadding(0, top, 0, Math.max(bottom, ime));
                return in;
            }
        });

        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_PACKAGE_ADDED);
        f.addAction(Intent.ACTION_PACKAGE_REMOVED);
        f.addAction(Intent.ACTION_PACKAGE_CHANGED);
        f.addDataScheme("package");
        int flag = Build.VERSION.SDK_INT >= 33 ? 2 /* RECEIVER_EXPORTED */ : 0;
        registerReceiver(pkgs, f, flag);
        Intent sticky = registerReceiver(batt, new IntentFilter(Intent.ACTION_BATTERY_CHANGED), flag);
        if (sticky != null) battery(sticky);
        NotifService.onChange = new Runnable() { @Override public void run() { hud.invalidate(); drawer.grid.invalidateViews(); } };
        Apps.load(this);
    }

    void battery(Intent i) {
        int l = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1), s = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
        int st = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
        hud.onBattery(l < 0 || s <= 0 ? -1 : l * 100 / s,
                st == BatteryManager.BATTERY_STATUS_CHARGING || st == BatteryManager.BATTERY_STATUS_FULL);
    }

    @Override
    protected void onResume() {
        super.onResume();
        hud.onShow();
        Apps.rank(this);
        if (!NotifService.connected && notifAccess()) {
            try { NotificationListenerService.requestRebind(new ComponentName(this, NotifService.class)); } catch (Exception ignored) {}
        }
    }

    @Override protected void onPause() { hud.onHide(); super.onPause(); }

    @Override
    protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        // home button while we are in front: back to the clean home screen
        if (dialog != null && dialog.isShowing()) dialog.dismiss();
        closeDrawer();
    }

    @Override
    public void onBackPressed() {
        if (drawerOpen) closeDrawer();
        // a launcher never leaves on back
    }

    @Override
    protected void onDestroy() {
        try { unregisterReceiver(pkgs); } catch (Exception ignored) {}
        try { unregisterReceiver(batt); } catch (Exception ignored) {}
        super.onDestroy();
    }

    // ================================================================ drawer
    void openDrawer(boolean keyboard) {
        if (drawerOpen) return;
        drawerOpen = true;
        drawer.reset(keyboard);
        drawer.setVisibility(View.VISIBLE);
        drawer.setAlpha(0f);
        drawer.setTranslationY(drawer.getHeight() > 0 ? drawer.getHeight() * 0.06f : 80);
        drawer.animate().alpha(1f).translationY(0).setDuration(200).start();
        hud.onHide();
    }

    void closeDrawer() {
        if (!drawerOpen) return;
        drawerOpen = false;
        drawer.hideKeyboard();
        drawer.animate().alpha(0f).translationY(drawer.getHeight() * 0.05f).setDuration(160)
                .withEndAction(new Runnable() { @Override public void run() { drawer.setVisibility(View.GONE); } }).start();
        hud.onShow();
    }

    // ================================================================ helpers
    boolean startSafe(Intent i) {
        try {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
            return true;
        } catch (Exception e) {
            toast("Niet beschikbaar op dit toestel");
            return false;
        }
    }

    void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }

    void expandShade() {
        try {
            Object sb = getSystemService("statusbar");
            Class.forName("android.app.StatusBarManager").getMethod("expandNotificationsPanel").invoke(sb);
        } catch (Throwable e) {
            toast("Veeg vanaf de bovenrand omlaag voor meldingen");
        }
    }

    boolean notifAccess() {
        if (NotifService.connected) return true;
        try {
            android.app.NotificationManager nm = getSystemService(android.app.NotificationManager.class);
            return nm.isNotificationListenerAccessGranted(new ComponentName(this, NotifService.class));
        } catch (Exception e) { return false; }
    }

    void openEntry(NotifService.Entry e) {
        if (e.intent != null) {
            try {
                Bundle opts = null;
                if (Build.VERSION.SDK_INT >= 34) {
                    android.app.ActivityOptions ao = android.app.ActivityOptions.makeBasic();
                    ao.setPendingIntentBackgroundActivityStartMode(android.app.ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED);
                    opts = ao.toBundle();
                }
                e.intent.send(this, 0, null, null, null, null, opts);
                return;
            } catch (Exception ignored) {}
        }
        Intent li = getPackageManager().getLaunchIntentForPackage(e.pkg);
        if (li != null) startSafe(li);
    }

    AlertDialog.Builder builder() {
        return new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert);
    }

    void show(AlertDialog.Builder b) {
        if (dialog != null && dialog.isShowing()) dialog.dismiss();
        dialog = b.show();
    }

    // ================================================================ permissions
    void askCalendar() {
        if (checkSelfPermission(android.Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED) { hud.loadCalendar(); return; }
        requestPermissions(new String[]{android.Manifest.permission.READ_CALENDAR}, REQ_CAL);
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        // never re-request from here: only react
        if (code == REQ_CAL) {
            if (res.length > 0 && res[0] == PackageManager.PERMISSION_GRANTED) hud.loadCalendar();
            else toast("Zonder toestemming kan Baan je agenda niet tonen");
        } else if (code == REQ_LOC) {
            if (res.length > 0 && res[0] == PackageManager.PERMISSION_GRANTED) {
                prefs.edit().putBoolean("trackOn", true).apply();
                hud.peil.start(); hud.invalidate();
                toast("Kinderen volgen aan");
            } else {
                prefs.edit().putBoolean("trackOn", false).apply();
                toast("Zonder locatietoestemming kan Baan geen richting bepalen");
            }
        }
    }

    // ================================================================ kinderen volgen
    void toggleTrack(boolean currentlyOn, boolean feedSet) {
        if (currentlyOn) {
            prefs.edit().putBoolean("trackOn", false).apply();
            hud.peil.stop(); hud.invalidate();
            toast("Kinderen volgen uit");
            return;
        }
        if (!feedSet) { toast("Stel eerst de locatie-feed in"); feedDialog(); return; }
        if (hud.peil.locationPermission()) {
            prefs.edit().putBoolean("trackOn", true).apply();
            hud.peil.start(); hud.invalidate();
            toast("Kinderen volgen aan");
        } else {
            // toestemming vragen; nooit opnieuw vragen vanuit het resultaat
            requestPermissions(new String[]{android.Manifest.permission.ACCESS_COARSE_LOCATION}, REQ_LOC);
        }
    }

    void feedDialog() {
        final android.widget.EditText in = new android.widget.EditText(this);
        in.setHint("https://script.google.com/…/exec?token=…");
        in.setText(prefs.getString("feedUrl", ""));
        in.setSingleLine(true);
        FrameLayout wrap = new FrameLayout(this);
        int pad = Math.round(getResources().getDisplayMetrics().density * 20);
        wrap.setPadding(pad, pad / 2, pad, 0);
        wrap.addView(in);
        show(builder().setTitle("Locatie-feed")
                .setMessage("Plak de web-app-URL van je endpoint, met ?token=… erachter. "
                        + "De feed geeft een JSON-lijst met naam, lat, lon en tst per kind.")
                .setView(wrap)
                .setPositiveButton("Opslaan", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        String u = in.getText().toString().trim();
                        prefs.edit().putString("feedUrl", u).apply();
                        if (u.isEmpty()) { prefs.edit().putBoolean("trackOn", false).apply(); hud.peil.stop(); }
                        else if (prefs.getBoolean("trackOn", false)) hud.peil.start();
                        hud.invalidate();
                        toast(u.isEmpty() ? "Feed gewist" : "Feed opgeslagen");
                    }
                })
                .setNegativeButton("Annuleren", null));
    }

    void askNotifAccess() {
        show(builder().setTitle("Berichten koppelen")
                .setMessage("Baan toont nieuwe berichten in het blok INKOMEND en als tellertje op je apps. "
                        + "Daarvoor moet je Baan toegang geven tot meldingen. Er wordt niets opgeslagen of verstuurd.\n\n"
                        + "Is de schakelaar grijs? Tik op App-info, dan rechtsboven op ⋮ › Beperkte instellingen toestaan, en probeer opnieuw.")
                .setPositiveButton("Toegang geven", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        if (Build.VERSION.SDK_INT >= 30) {
                            Intent i = new Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS);
                            i.putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
                                    new ComponentName(MainActivity.this, NotifService.class).flattenToString());
                            if (startQuiet(i)) return;
                        }
                        startSafe(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
                    }
                })
                .setNeutralButton("App-info", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) { appInfo(getPackageName()); }
                })
                .setNegativeButton("Later", null));
    }

    boolean startQuiet(Intent i) {
        try { i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); startActivity(i); return true; } catch (Exception e) { return false; }
    }

    void askUsage() {
        show(builder().setTitle("Slimmere baan")
                .setMessage("Met toegang tot gebruiksgegevens zet Baan de apps die je echt het meest gebruikt op de boog. "
                        + "Zonder telt Baan alleen wat je via Baan opent. Zet Baan aan in de lijst.")
                .setPositiveButton("Openen", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) { startSafe(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)); }
                })
                .setNegativeButton("Later", null));
    }

    void appInfo(String pkg) {
        startSafe(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + pkg)));
    }

    // ================================================================ home role
    boolean isHome() {
        try {
            Intent i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
            ResolveInfo ri = getPackageManager().resolveActivity(i, PackageManager.MATCH_DEFAULT_ONLY);
            return ri != null && ri.activityInfo != null && getPackageName().equals(ri.activityInfo.packageName);
        } catch (Exception e) { return false; }
    }

    void requestHome() {
        if (isHome()) { toast("Baan is al je startscherm"); return; }
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                RoleManager rm = getSystemService(RoleManager.class);
                if (rm != null && rm.isRoleAvailable(RoleManager.ROLE_HOME) && !rm.isRoleHeld(RoleManager.ROLE_HOME)) {
                    startActivityForResult(rm.createRequestRoleIntent(RoleManager.ROLE_HOME), REQ_HOME);
                    return;
                }
            } catch (Exception ignored) {}
        }
        homeSettings();
    }

    void homeSettings() {
        if (!startQuiet(new Intent(Settings.ACTION_HOME_SETTINGS))) startSafe(new Intent(Settings.ACTION_SETTINGS));
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_HOME && !isHome()) homeSettings();
    }

    void afterTour() {
        if (!isHome() && !prefs.getBoolean("askedHome", false)) {
            prefs.edit().putBoolean("askedHome", true).apply();
            requestHome();
        }
    }

    // ================================================================ menus
    void appOptions(final Apps.Item it) {
        final boolean pinned = Apps.pinned(this).contains(it.key());
        final boolean inBar = Apps.inBar(this, it);
        String[] items = {"Openen",
                pinned ? "Losmaken van de baan" : "Vastzetten op de baan",
                inBar ? "Uit de balk halen" : "Vastzetten in de balk",
                "App-info", "Verwijderen"};
        show(builder().setTitle(it.label).setItems(items, new DialogInterface.OnClickListener() {
            @Override public void onClick(DialogInterface d, int w) {
                switch (w) {
                    case 0: if (Apps.launch(MainActivity.this, it)) closeDrawer(); break;
                    case 1: Apps.setPinned(MainActivity.this, it, !pinned); toast(pinned ? "Losgemaakt" : "Vastgezet op de baan"); break;
                    case 2: Apps.setBar(MainActivity.this, it, !inBar); toast(inBar ? "Uit de balk gehaald" : "Vastgezet in de balk"); break;
                    case 3: appInfo(it.pkg); break;
                    case 4: startSafe(new Intent(Intent.ACTION_DELETE, Uri.parse("package:" + it.pkg))); break;
                }
            }
        }));
    }

    void openSettings() {
        final int th = Theme.idx(this), fx = prefs.getInt("fx", 0), sz = Math.min(2, prefs.getInt("size", 0)),
                ic = prefs.getInt("icons", 0), am = prefs.getInt("arcMode", 0);
        boolean cal = checkSelfPermission(android.Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED;
        final boolean trackOn = prefs.getBoolean("trackOn", false);
        final boolean feedSet = !prefs.getString("feedUrl", "").isEmpty();
        String[] items = {
                "Kleur: " + Theme.NAMES[th],
                "Effecten: " + (fx == 0 ? "vol" : "rustig (zuiniger)"),
                "Tekstgrootte: " + Theme.SIZE_NAMES[sz],
                "Iconen: " + (ic == 0 ? "HUD (monochroom)" : "kleur"),
                "Baan toont: " + (am == 0 ? "meest gebruikt" : "alleen vastgezet"),
                "Agenda: " + (cal ? "gekoppeld" : "koppelen"),
                "Berichten: " + (notifAccess() ? "gekoppeld" : "koppelen"),
                "Gebruiksgegevens: " + (Apps.usageAccess(this) ? "gekoppeld" : "koppelen"),
                "Kinderen volgen: " + (trackOn ? "aan" : "uit"),
                "Locatie-feed: " + (feedSet ? "ingesteld" : "instellen"),
                "Uitleg opnieuw bekijken",
                "Baan als startscherm instellen",
                "Terug naar gewoon startscherm",
                "Diagnose"};
        show(builder().setTitle("Instellingen").setItems(items, new DialogInterface.OnClickListener() {
            @Override public void onClick(DialogInterface d, int w) {
                SharedPreferences.Editor e = prefs.edit();
                boolean reopen = true;
                switch (w) {
                    case 0: e.putInt("theme", (th + 1) % Theme.SETS.length); break;
                    case 1: e.putInt("fx", fx == 0 ? 1 : 0); break;
                    case 2: e.putInt("size", (sz + 1) % 3); break;
                    case 3: e.putInt("icons", ic == 0 ? 1 : 0); break;
                    case 4: e.putInt("arcMode", am == 0 ? 1 : 0); break;
                    case 5: reopen = false; askCalendar(); break;
                    case 6: reopen = false; askNotifAccess(); break;
                    case 7: reopen = false; askUsage(); break;
                    case 8: reopen = false; toggleTrack(trackOn, feedSet); break;
                    case 9: reopen = false; feedDialog(); break;
                    case 10: reopen = false; hud.tour = 0; hud.invalidate(); break;
                    case 11: reopen = false; requestHome(); break;
                    case 12: reopen = false; toast("Kies je oude startscherm"); homeSettings(); break;
                    case 13: reopen = false; diagnose(); break;
                }
                e.apply();
                if (w <= 4) {
                    if (w == 4) Apps.rank(MainActivity.this);
                    recreateViews();
                }
                if (reopen) openSettings();
            }
        }));
    }

    /** Theme / size changes: rebuild views so every cached shader and font size is fresh. */
    void recreateViews() {
        hud.layout();
        hud.invalidate();
        boolean open = drawerOpen;
        root.removeView(drawer);
        drawer = new Drawer(this);
        drawer.setVisibility(open ? View.VISIBLE : View.GONE);
        root.addView(drawer, new FrameLayout.LayoutParams(-1, -1));
        root.requestApplyInsets();
    }

    void diagnose() {
        StringBuilder sb = new StringBuilder();
        sb.append("Baan 1.0 · Android ").append(Build.VERSION.RELEASE).append(" · ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n');
        sb.append("Startscherm: ").append(isHome() ? "ja" : "nee").append(" · apps: ").append(Apps.all.size())
                .append(" · berichten: ").append(notifAccess() ? "ja" : "nee").append('\n');
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                android.app.ActivityManager am = getSystemService(android.app.ActivityManager.class);
                List<android.app.ApplicationExitInfo> l = am.getHistoricalProcessExitReasons(getPackageName(), 0, 3);
                SimpleDateFormat f = new SimpleDateFormat("dd-MM HH:mm", Locale.ROOT);
                for (android.app.ApplicationExitInfo x : l)
                    sb.append("EXIT ").append(f.format(new Date(x.getTimestamp()))).append(" reden=").append(x.getReason())
                            .append(' ').append(x.getDescription()).append('\n');
            } catch (Throwable ignored) {}
        }
        String crash = prefs.getString("crash", "");
        sb.append(crash.isEmpty() ? "Geen crashes." : "Laatste crash:\n" + crash);
        final String text = sb.toString();
        show(builder().setTitle("Diagnose").setMessage(text)
                .setPositiveButton("Kopiëren", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        android.content.ClipboardManager cm = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                        cm.setPrimaryClip(android.content.ClipData.newPlainText("diagnose", text));
                        prefs.edit().remove("crash").apply();
                        toast("Gekopieerd");
                    }
                })
                .setNegativeButton("Sluiten", null));
    }
}
