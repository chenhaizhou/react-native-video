package com.brentvatne.lockscreen;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.res.Resources;
import android.os.Build;
import android.os.IBinder;
import android.support.v4.media.session.PlaybackStateCompat;
import android.view.KeyEvent;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import com.facebook.react.bridge.ReactApplicationContext;

import java.util.Map;

import static com.brentvatne.lockscreen.MusicControlModule.NOTIFICATION_ID;

public class MusicControlNotification {

    private static final String TAG = MusicControlNotification.class.getSimpleName();
    public static final String REMOVE_NOTIFICATION = "music_control_remove_notification";
    public static final String MEDIA_BUTTON = "music_control_media_button";
    public static final String PACKAGE_NAME = "music_control_package_name";

    private final ReactApplicationContext context;
    private final MusicControlModule module;

    private int smallIcon;
    private int customIcon;
    private NotificationCompat.Action play, pause, stop, next, previous, skipForward, skipBackward;

    public MusicControlNotification(MusicControlModule module, ReactApplicationContext context) {
        this.context = context;
        this.module = module;

        Resources r = context.getResources();
        String packageName = context.getPackageName();

        // Optional custom icon with fallback to the play icon
        smallIcon = r.getIdentifier("music_control_icon", "drawable", packageName);
        if (smallIcon == 0) smallIcon = r.getIdentifier("play", "drawable", packageName);
    }

    public synchronized void setCustomNotificationIcon(String resourceName) {
        if (resourceName == null) {
            customIcon = 0;
            return;
        }

        Resources r = context.getResources();
        String packageName = context.getPackageName();

        customIcon = r.getIdentifier(resourceName, "drawable", packageName);
    }

    public synchronized void updateActions(long mask, Map<String, Integer> options) {
        play = createAction("play", "Play", mask, PlaybackStateCompat.ACTION_PLAY, play);
        pause = createAction("pause", "Pause", mask, PlaybackStateCompat.ACTION_PAUSE, pause);
        stop = createAction("stop", "Stop", mask, PlaybackStateCompat.ACTION_STOP, stop);
        next = createAction("next", "Next", mask, PlaybackStateCompat.ACTION_SKIP_TO_NEXT, next);
        previous = createAction("previous", "Previous", mask, PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS, previous);

        if (options != null && options.containsKey("skipForward") && (options.get("skipForward") == 10 || options.get("skipForward") == 5 || options.get("skipForward") == 30)) {
            skipForward = createAction("skip_forward_" + options.get("skipForward").toString(), "Skip Forward", mask, PlaybackStateCompat.ACTION_FAST_FORWARD, skipForward);
        } else {
            skipForward = createAction("skip_forward_10", "Skip Forward", mask, PlaybackStateCompat.ACTION_FAST_FORWARD, skipForward);
        }

        if (options != null && options.containsKey("skipBackward") && (options.get("skipBackward") == 10 || options.get("skipBackward") == 5 || options.get("skipBackward") == 30)) {
            skipBackward = createAction("skip_backward_" + options.get("skipBackward").toString(), "Skip Backward", mask, PlaybackStateCompat.ACTION_REWIND, skipBackward);
        } else {
            skipBackward = createAction("skip_backward_10", "Skip Backward", mask, PlaybackStateCompat.ACTION_REWIND, skipBackward);
        }
    }

    @SuppressLint("RestrictedApi")
    public synchronized Notification prepareNotification(NotificationCompat.Builder builder, boolean isPlaying) {
        if (MusicControlModule.INSTANCE == null) {
            return null;
        }
        // Add the buttons
        builder.mActions.clear();
        if (previous != null && module.hasControl(PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS)) {
            builder.addAction(previous);
        }
        if (skipBackward != null) builder.addAction(skipBackward);
        if (play != null) builder.addAction(play);
        if (pause != null) builder.addAction(pause);
        if (stop != null) builder.addAction(stop);
        if (skipForward != null) builder.addAction(skipForward);
        if (next != null && module.hasControl(PlaybackStateCompat.ACTION_SKIP_TO_NEXT)) builder.addAction(next);

        // Set whether notification can be closed based on closeNotification control (default PAUSED)
        if (module.notificationClose == MusicControlModule.NotificationClose.ALWAYS) {
            builder.setOngoing(false);
        } else if (module.notificationClose == MusicControlModule.NotificationClose.PAUSED) {
            builder.setOngoing(isPlaying);
        } else { // NotificationClose.NEVER
            builder.setOngoing(false);
        }

        builder.setSmallIcon(customIcon != 0 ? customIcon : smallIcon);

        // Open the app when the notification is clicked
        String packageName = context.getPackageName();
        Intent openApp = context.getPackageManager().getLaunchIntentForPackage(packageName);
        if (openApp != null) {
            int flag = PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_ONE_SHOT;
            builder.setContentIntent(PendingIntent.getActivity(context, 0, openApp, flag));
        }
        // Remove notification
        Intent remove = new Intent(REMOVE_NOTIFICATION);
        remove.putExtra(PACKAGE_NAME, context.getApplicationInfo().packageName);
        int flag = PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT;
        builder.setDeleteIntent(PendingIntent.getBroadcast(context, 0, remove, flag));

        Notification notification = null;
        try {
            notification = builder.build();
        } catch (IllegalArgumentException ignore) {
            // builder.build may throw IllegalArgumentException in Android 6.0
        }
        return notification;
    }

    public synchronized void show(NotificationCompat.Builder builder, boolean isPlaying) {
        Notification notification  = prepareNotification(builder, isPlaying);
        if (notification != null) {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification);
        }
    }

    public void hide() {
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID);
        Intent myIntent = new Intent(context, MusicControlNotification.NotificationService.class);
        context.stopService(myIntent);
    }

    private NotificationCompat.Action createAction(String iconName, String title, long mask, long action, NotificationCompat.Action oldAction) {
        if ((mask & action) == 0) return null; // When this action is not enabled, return null
        if (oldAction != null)
            return oldAction; // If this action was already created, we won't create another instance

        // Finds the icon with the given name
        Resources r = context.getResources();
        String packageName = context.getPackageName();
        int icon = r.getIdentifier(iconName, "drawable", packageName);

        // Creates the intent based on the action
        int keyCode = Utils.toKeyCode(action);
        Intent intent = new Intent(MEDIA_BUTTON);
        intent.putExtra(Intent.EXTRA_KEY_EVENT, new KeyEvent(KeyEvent.ACTION_DOWN, keyCode));
        intent.putExtra(PACKAGE_NAME, packageName);
        int flag = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? (PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT) : PendingIntent.FLAG_UPDATE_CURRENT;
        PendingIntent i = PendingIntent.getBroadcast(context, keyCode, intent, flag);

        return new NotificationCompat.Action(icon, title, i);
    }

    public static class NotificationService extends Service {

        @Override
        public IBinder onBind(Intent intent) {
            return null;
        }

        @Override
        public void onCreate() {
            super.onCreate();
            startForegroundService();
        }

        @Override
        public int onStartCommand(Intent intent, int flags, int startId) {
            startForegroundService();
            return START_NOT_STICKY;
        }

        @Override
        public void onTaskRemoved(Intent rootIntent) {
            super.onTaskRemoved(rootIntent);
            // Stop the service when the task is removed (closed, killed, etc)
            stopSelf();
        }

        @Override
        public void onDestroy() {
            super.onDestroy();
            stopForeground(true);
        }

        private void startForegroundService() {
            if (MusicControlModule.INSTANCE != null && MusicControlModule.INSTANCE.notification != null) {
                Notification notification = MusicControlModule.INSTANCE.notification.prepareNotification(MusicControlModule.INSTANCE.nb, false);
                if (notification != null) {
                    startForeground(NOTIFICATION_ID, notification);
                }
            }
        }
    }

}
