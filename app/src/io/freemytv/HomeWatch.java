package io.freemytv;

import android.app.Notification;
import android.app.Service;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.IBinder;

/** Fire OS has no choice of launcher: when Amazon's home screen comes up, this opens the home screen over it. */
public class HomeWatch extends Service {
    private static final String AMAZON_HOME = "com.amazon.tv.launcher.ui.HomeActivity_vNext";
    private static final long EVERY_MS = 400;

    private final Handler handler = new Handler();
    private String last;
    private long at, handled;

    private final Runnable check = new Runnable() {
        @Override public void run() {
            look();
            handler.postDelayed(this, EVERY_MS);
        }
    };

    /** The newest app to come up; Amazon's home means this one goes over it, once per time it came up. The first look reaches back, as Amazon's home is up before this starts. */
    private void look() {
        long now = System.currentTimeMillis();
        UsageEvents events = ((UsageStatsManager) getSystemService(Context.USAGE_STATS_SERVICE)).queryEvents(now - (last == null ? 86400000 : 10000), now);
        UsageEvents.Event e = new UsageEvents.Event();
        while (events.hasNextEvent()) {
            events.getNextEvent(e);
            if (e.getEventType() == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                last = e.getClassName();
                at = e.getTimeStamp();
            }
        }
        if (AMAZON_HOME.equals(last) && at != handled && !original(this)) {
            handled = at;
            startActivity(new Intent(this, HomeActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        }
    }

    /** Fire OS: Home Settings > Home screen > Original leaves Amazon's home screen up (a file, read by both processes). */
    static boolean original(Context c) {
        return new java.io.File(c.getFilesDir(), "home-original").exists();
    }

    static void setOriginal(Context c, boolean on) {
        java.io.File f = new java.io.File(c.getFilesDir(), "home-original");
        try {
            if (on) f.createNewFile(); else f.delete();
        } catch (java.io.IOException e) {
            AppLog.w("home choice: " + e.getMessage());
        }
    }

    /** tools/fire/homekey.sh opens the app with this when Amazon's home came up: with Original picked, it steps aside. */
    static final String FROM_HOME = "io.freemytv.FROM_HOME";

    static boolean stepAside(android.app.Activity a, Intent i) {
        if (i == null || !i.getBooleanExtra(FROM_HOME, false) || !original(a)) return false;
        a.moveTaskToBack(true);
        return true;
    }

    @Override public void onCreate() {
        super.onCreate();
        startForeground(1, new Notification.Builder(this).setSmallIcon(getApplicationInfo().icon).setContentTitle("Free My TV").build());
        handler.post(check);
    }

    @Override public int onStartCommand(Intent intent, int flags, int id) {
        return START_STICKY;
    }

    @Override public void onDestroy() {
        handler.removeCallbacks(check);
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) {
        return null;
    }

    public static class Boot extends BroadcastReceiver {
        @Override public void onReceive(Context c, Intent i) {
            c.startService(new Intent(c, HomeWatch.class));
        }
    }
}
