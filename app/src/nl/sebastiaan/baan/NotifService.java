package nl.sebastiaan.baan;

import android.app.Notification;
import android.app.PendingIntent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;

/** Optional: per-app summary of current notifications for the INKOMEND block and badges. */
public class NotifService extends NotificationListenerService {
    static class Entry {
        String pkg, title;
        int count;
        long when;
        boolean message;
        PendingIntent intent;
    }

    static volatile ArrayList<Entry> entries = new ArrayList<>();
    static volatile boolean connected;
    static Runnable onChange;
    static final HashSet<String> MESSAGING = new HashSet<>();
    static {
        String[] p = {"com.whatsapp", "com.whatsapp.w4b", "org.telegram.messenger", "org.thoughtcrime.securesms",
                "com.google.android.apps.messaging", "com.android.mms", "com.oneplus.mms", "com.samsung.android.messaging",
                "com.facebook.orca", "com.instagram.android", "com.snapchat.android", "com.google.android.gm",
                "com.microsoft.office.outlook", "com.google.android.dialer", "com.android.dialer", "com.discord",
                "com.microsoft.teams", "com.Slack"};
        Collections.addAll(MESSAGING, p);
    }
    private final Handler main = new Handler(Looper.getMainLooper());

    @Override public void onListenerConnected() { connected = true; rebuild(); }
    @Override public void onListenerDisconnected() { connected = false; entries = new ArrayList<>(); fire(); }
    @Override public void onNotificationPosted(StatusBarNotification s) { rebuild(); }
    @Override public void onNotificationRemoved(StatusBarNotification s) { rebuild(); }

    void rebuild() {
        StatusBarNotification[] arr;
        try { arr = getActiveNotifications(); } catch (Exception e) { return; }
        HashMap<String, Entry> map = new HashMap<>();
        if (arr != null) for (StatusBarNotification s : arr) {
            if (getPackageName().equals(s.getPackageName()) || !s.isClearable()) continue;
            Notification n = s.getNotification();
            if (n == null || (n.flags & (Notification.FLAG_GROUP_SUMMARY | Notification.FLAG_ONGOING_EVENT)) != 0) continue;
            Entry e = map.get(s.getPackageName());
            if (e == null) { e = new Entry(); e.pkg = s.getPackageName(); map.put(e.pkg, e); }
            e.count++;
            if (Notification.CATEGORY_MESSAGE.equals(n.category) || Notification.CATEGORY_EMAIL.equals(n.category)
                    || "missed_call".equals(n.category) || MESSAGING.contains(e.pkg)) e.message = true;
            if (s.getPostTime() >= e.when) {
                e.when = s.getPostTime();
                e.intent = n.contentIntent;
                Bundle x = n.extras;
                CharSequence t = x == null ? null : x.getCharSequence(Notification.EXTRA_TITLE);
                e.title = t == null ? null : t.toString();
            }
        }
        ArrayList<Entry> l = new ArrayList<>(map.values());
        Collections.sort(l, new Comparator<Entry>() {
            @Override public int compare(Entry a, Entry b) { return Long.compare(b.when, a.when); }
        });
        entries = l;
        fire();
    }

    void fire() {
        main.post(new Runnable() { @Override public void run() { Runnable r = onChange; if (r != null) r.run(); } });
    }

    static int count(String pkg) {
        for (Entry e : entries) if (e.pkg.equals(pkg)) return e.count;
        return 0;
    }
}
