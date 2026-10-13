package local.qwentranslate;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

/**
 * Foreground service kept while a command runs, like Termux's session
 * notification: it keeps the app (and so the translate process) alive and the
 * CPU awake when the user switches away during a long download or translation.
 */
public final class SessionService extends Service {
    private static final String CHANNEL = "session";
    private static final int NOTIFICATION = 1;
    private static final String EXTRA_COMMAND = "command";
    private static final String ACTION_STOP = "local.qwentranslate.STOP";
    /** Upper bound in case the service is never stopped. */
    private static final long WAKE_LOCK_MS = 6 * 60 * 60 * 1000L;

    /** The running instance, if any (main thread only; cleared in onDestroy). */
    @SuppressLint("StaticFieldLeak")
    private static SessionService current;

    private PowerManager.WakeLock wakeLock;

    /** Shows (or updates) the notification for {@code command}. */
    static void start(Context context, String command) {
        Intent intent = new Intent(context, SessionService.class).putExtra(EXTRA_COMMAND, command);
        try {
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent);
            else context.startService(intent);
        } catch (RuntimeException e) {
            // Not allowed from the background (Android 12+): the command runs anyway
        }
    }

    /**
     * Ends the service once the session is idle. The service stops itself: stopping
     * it from outside before it reached startForeground() would crash the app.
     */
    static void stop() {
        if (current != null) current.stopSelf();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            // Notification button; the session stops the service once idle
            Session.get(this).interrupt();
            return START_NOT_STICKY;
        }
        String command = intent == null ? null : intent.getStringExtra(EXTRA_COMMAND);
        Notification n = notification(command == null ? "" : command);
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIFICATION, n);
        }
        current = this;
        if (wakeLock == null) {
            PowerManager pm = getSystemService(PowerManager.class);
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "qwentranslate:session");
            wakeLock.setReferenceCounted(false);
            wakeLock.acquire(WAKE_LOCK_MS);
        }
        // The command may have ended before the service got here
        if (!Session.get(this).busy()) stopSelf();
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        current = null;
        if (wakeLock != null) wakeLock.release();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private Notification notification(String command) {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(CHANNEL,
                    getString(R.string.notification_channel), NotificationManager.IMPORTANCE_LOW);
            getSystemService(NotificationManager.class).createNotificationChannel(channel);
        }
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, TerminalActivity.class), PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 1,
                new Intent(this, SessionService.class).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL)
                : legacyBuilder();
        return b.setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(getString(R.string.notification_running))
                .setContentText(command)
                .setContentIntent(open)
                .setOngoing(true)
                .setShowWhen(false)
                .addAction(new Notification.Action.Builder(null, getString(R.string.notification_stop), stop).build())
                .build();
    }

    @SuppressWarnings("deprecation") // the only constructor before Android 8
    private Notification.Builder legacyBuilder() {
        return new Notification.Builder(this).setPriority(Notification.PRIORITY_LOW);
    }
}
