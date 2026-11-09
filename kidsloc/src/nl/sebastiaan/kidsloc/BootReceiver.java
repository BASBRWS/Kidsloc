package nl.sebastiaan.kidsloc;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Herstart het delen na een herstart van het toestel. */
public class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context c, Intent i) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(i.getAction())) Reporter.startIfOn(c);
    }
}
