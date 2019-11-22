package com.brentvatne.lockscreen;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.NotificationChannel;
import android.content.ComponentCallbacks2;
import android.content.ComponentName;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.AudioManager;
import android.os.SystemClock;
import android.os.Build;

import androidx.annotation.RequiresApi;

import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.RatingCompat;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;

import androidx.core.app.NotificationCompat;
import androidx.media.app.NotificationCompat.MediaStyle;

import android.util.Log;

import com.brentvatne.react.R;
import com.facebook.common.executors.UiThreadImmediateExecutorService;
import com.facebook.common.references.CloseableReference;
import com.facebook.datasource.DataSource;
import com.facebook.drawee.backends.pipeline.Fresco;
import com.facebook.imagepipeline.datasource.BaseBitmapDataSubscriber;
import com.facebook.imagepipeline.image.CloseableImage;
import com.facebook.imagepipeline.request.ImageRequest;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactContextBaseJavaModule;
import com.facebook.react.bridge.ReactMethod;
import com.facebook.react.bridge.ReadableMap;
import com.facebook.react.bridge.ReadableType;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import javax.annotation.Nullable;

public class MusicControlModule extends ReactContextBaseJavaModule implements ComponentCallbacks2 {
    private static final String TAG = MusicControlModule.class.getSimpleName();

    static MusicControlModule INSTANCE;

    private boolean init = false;
    protected MediaSessionCompat session;

    private MediaMetadataCompat.Builder md;
    private PlaybackStateCompat.Builder pb;
    public NotificationCompat.Builder nb;

    private PlaybackStateCompat state;

    public MusicControlNotification notification;
    private MusicControlListener.VolumeListener volume;
    private MusicControlReceiver receiver;

    public ReactApplicationContext context;

    private boolean remoteVolume = false;
    private boolean isPlaying = false;
    private long controls = 0;
    protected int ratingType = RatingCompat.RATING_PERCENTAGE;
    private Bitmap realArtWork = null;

    public NotificationClose notificationClose = NotificationClose.PAUSED;

    public static final String CHANNEL_ID = "react-native-music-control";

    public static final int NOTIFICATION_ID = 100;

    public MusicControlModule(ReactApplicationContext context) {
        super(context);
    }

    @Override
    public String getName() {
        return "MusicControlManager";
    }

    @Override
    public Map<String, Object> getConstants() {
        Map<String, Object> map = new HashMap<>();
        map.put("STATE_ERROR", PlaybackStateCompat.STATE_ERROR);
        map.put("STATE_STOPPED", PlaybackStateCompat.STATE_STOPPED);
        map.put("STATE_PLAYING", PlaybackStateCompat.STATE_PLAYING);
        map.put("STATE_PAUSED", PlaybackStateCompat.STATE_PAUSED);
        map.put("STATE_BUFFERING", PlaybackStateCompat.STATE_BUFFERING);

        map.put("RATING_HEART", RatingCompat.RATING_HEART);
        map.put("RATING_THUMBS_UP_DOWN", RatingCompat.RATING_THUMB_UP_DOWN);
        map.put("RATING_3_STARS", RatingCompat.RATING_3_STARS);
        map.put("RATING_4_STARS", RatingCompat.RATING_4_STARS);
        map.put("RATING_5_STARS", RatingCompat.RATING_5_STARS);
        map.put("RATING_PERCENTAGE", RatingCompat.RATING_PERCENTAGE);
        return map;
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private void createChannel(ReactApplicationContext context) {
        NotificationManager mNotificationManager = (NotificationManager) context
                .getSystemService(Context.NOTIFICATION_SERVICE);

        NotificationChannel mChannel = new NotificationChannel(CHANNEL_ID, "Media playback",
                NotificationManager.IMPORTANCE_LOW);
        mChannel.setDescription("Media playback controls");
        mChannel.setShowBadge(false);
        mChannel.setLockscreenVisibility(NotificationCompat.VISIBILITY_PUBLIC);
        mNotificationManager.createNotificationChannel(mChannel);
    }
    private boolean hasControl(long control) {
        if((controls & control) == control) {
            return true;
        }
        return false;
    }

    private void updateNotificationMediaStyle() {
        if (!(Build.MANUFACTURER.toLowerCase(Locale.getDefault()).contains("huawei")
                && Build.VERSION.SDK_INT < Build.VERSION_CODES.M)) {
            MediaStyle style = new MediaStyle();
            style.setMediaSession(session.getSessionToken());
            int controlCount = 0;
            if(hasControl(PlaybackStateCompat.ACTION_PLAY)
                    || hasControl(PlaybackStateCompat.ACTION_PAUSE)
                    || hasControl(PlaybackStateCompat.ACTION_PLAY_PAUSE)) {
                controlCount += 1;
            }
            if(hasControl(PlaybackStateCompat.ACTION_SKIP_TO_NEXT)) {
                controlCount += 1;
            }
            if(hasControl(PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS)) {
                controlCount += 1;
            }
            int[] actions = new int[controlCount];
            for(int i=0; i<actions.length; i++) {
                actions[i] = i;
            }
            style.setShowActionsInCompactView(actions);
            nb.setStyle(style);
        }
    }
    private void init() {
        if (init)
            return;

        INSTANCE = this;

        context = getReactApplicationContext();

        //TODO for Samsung use "public MediaSessionCompat(Context context, String tag) "
        ComponentName compName = new ComponentName(context, MusicControlReceiver.class);
        session = new MediaSessionCompat(context, "MusicControl", compName, null);
        session.setFlags(
                MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS | MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS);
        session.setCallback(new MusicControlListener(context));

        volume = new MusicControlListener.VolumeListener(context, true, 100, 100);
        if (remoteVolume) {
            session.setPlaybackToRemote(volume);
        } else {
            session.setPlaybackToLocal(AudioManager.STREAM_MUSIC);
        }

        md = new MediaMetadataCompat.Builder();
        pb = new PlaybackStateCompat.Builder();
        pb.setActions(controls);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            createChannel(context);
        }
        nb = new NotificationCompat.Builder(context, CHANNEL_ID);
        nb.setVisibility(NotificationCompat.VISIBILITY_PUBLIC);

        updateNotificationMediaStyle();

        state = pb.build();

        notification = new MusicControlNotification(this, context);
        notification.updateActions(controls, null);

        IntentFilter filter = new IntentFilter();
        filter.addAction(MusicControlNotification.REMOVE_NOTIFICATION);
        filter.addAction(MusicControlNotification.MEDIA_BUTTON);
        filter.addAction(Intent.ACTION_MEDIA_BUTTON);
        filter.addAction(AudioManager.ACTION_AUDIO_BECOMING_NOISY);
        receiver = new MusicControlReceiver(this, context);
        context.registerReceiver(receiver, filter);

        Intent myIntent = new Intent(context, MusicControlNotification.NotificationService.class);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(myIntent);

        } else {
            context.startService(myIntent);
        }

        context.registerComponentCallbacks(this);

        isPlaying = false;
        init = true;
    }

    @ReactMethod
    public synchronized void stopControl() {
        if (context == null) {
            context = getReactApplicationContext();
        }
        Intent myIntent = new Intent();
        myIntent.setComponent(new ComponentName(context.getPackageName(),
                "com.brentvatne.lockscreen.MusicControlNotification$NotificationService"));
        if (getReactApplicationContext() != null)
            getReactApplicationContext().stopService(myIntent);
        if (!init)
            return;

        if (notification != null)
            notification.hide();
        if (session != null)
            session.release();

        try {
            if (receiver != null) {
                context.unregisterReceiver(receiver);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        context.unregisterComponentCallbacks(this);

        session = null;
        notification = null;
        volume = null;
        receiver = null;
        state = null;
        md = null;
        pb = null;
        nb = null;
        realArtWork = null;

        init = false;
    }

    synchronized public void destroy() {
        stopControl();
    }

    @ReactMethod
    public void enableBackgroundMode(boolean enable) {
        // Nothing?
    }

    @ReactMethod
    synchronized public void setNowPlaying(ReadableMap metadata) {
        init();

        String title = metadata.hasKey("title") ? metadata.getString("title") : null;
        String artist = metadata.hasKey("artist") ? metadata.getString("artist") : null;
        String album = metadata.hasKey("album") ? metadata.getString("album") : null;
        String genre = metadata.hasKey("genre") ? metadata.getString("genre") : null;
        String description = metadata.hasKey("description") ? metadata.getString("description") : null;
        String date = metadata.hasKey("date") ? metadata.getString("date") : null;
        long duration = metadata.hasKey("duration") ? (long) (metadata.getDouble("duration") * 1000) : 0;
        int notificationColor = metadata.hasKey("color") ? metadata.getInt("color") : NotificationCompat.COLOR_DEFAULT;
        String notificationIcon = metadata.hasKey("notificationIcon") ? metadata.getString("notificationIcon") : null;

        // If a color is supplied, we need to clear the MediaStyle set during init().
        // Otherwise, the color will not be used for the notification's background.
        boolean removeFade = metadata.hasKey("color");
        if(removeFade) {
            nb.setStyle(new MediaStyle());
        }
        RatingCompat rating;
        if (metadata.hasKey("rating")) {
            if (ratingType == RatingCompat.RATING_PERCENTAGE) {
                rating = RatingCompat.newPercentageRating((float) metadata.getDouble("rating"));
            } else if (ratingType == RatingCompat.RATING_HEART) {
                rating = RatingCompat.newHeartRating(metadata.getBoolean("rating"));
            } else if (ratingType == RatingCompat.RATING_THUMB_UP_DOWN) {
                rating = RatingCompat.newThumbRating(metadata.getBoolean("rating"));
            } else {
                rating = RatingCompat.newStarRating(ratingType, (float) metadata.getDouble("rating"));
            }
        } else {
            rating = RatingCompat.newUnratedRating(ratingType);
        }

        md.putText(MediaMetadataCompat.METADATA_KEY_TITLE, title);
        md.putText(MediaMetadataCompat.METADATA_KEY_ARTIST, artist);
        md.putText(MediaMetadataCompat.METADATA_KEY_ALBUM, album);
        md.putText(MediaMetadataCompat.METADATA_KEY_GENRE, genre);
        md.putText(MediaMetadataCompat.METADATA_KEY_DISPLAY_DESCRIPTION, description);
        md.putText(MediaMetadataCompat.METADATA_KEY_DATE, date);
        md.putLong(MediaMetadataCompat.METADATA_KEY_DURATION, duration);
        if (android.os.Build.VERSION.SDK_INT > 19) {
            md.putRating(MediaMetadataCompat.METADATA_KEY_RATING, rating);
        }

        nb.setContentTitle(title);
        nb.setContentText(artist);
        nb.setContentInfo(album);
        nb.setColor(notificationColor);

        notification.setCustomNotificationIcon(notificationIcon);

        if ((metadata.hasKey("artwork") && ReadableType.String == metadata.getType("artwork"))) {
            String artworkUrl = metadata.getString("artwork");

            ImageRequest imageRequest = ImageRequest.fromUri(artworkUrl);
            DataSource<CloseableReference<CloseableImage>> dataSource = Fresco.getImagePipeline().fetchDecodedImage(imageRequest, null);
            dataSource.subscribe(new BaseBitmapDataSubscriber() {
                @Override
                protected void onNewResultImpl(@Nullable Bitmap bitmap) {
                    realArtWork = bitmap;
                    Log.d(TAG, "onNewResultImpl bitmap=" + bitmap);
                    if (md != null) {
                        md.putBitmap(MediaMetadataCompat.METADATA_KEY_ART, realArtWork);
                        // session.setMetadata(md.build());
                    }
                    if (nb != null && notification != null) {
                        nb.setLargeIcon(realArtWork);
                        notification.show(nb, isPlaying);
                    }
                }

                @Override
                protected void onFailureImpl(DataSource<CloseableReference<CloseableImage>> dataSource) {
                    Log.d(TAG, "load failed to set color..");
                    realArtWork = BitmapFactory.decodeResource(context.getResources(), R.drawable.pimsleuricon11);
                    if (md != null) {
                        md.putBitmap(MediaMetadataCompat.METADATA_KEY_ART, realArtWork);
                    }
                    if (nb != null) {
                        nb.setLargeIcon(realArtWork);
                    }
                }
            }, UiThreadImmediateExecutorService.getInstance());
        } else if ((metadata.hasKey("artwork") && ReadableType.Map == metadata.getType("artwork"))) {
            realArtWork = BitmapFactory.decodeResource(context.getResources(), R.drawable.pimsleuricon11);
            if (md != null) {
                md.putBitmap(MediaMetadataCompat.METADATA_KEY_ART, realArtWork);
            }
            if (nb != null && notification != null) {
                nb.setLargeIcon(realArtWork);
                notification.show(nb, isPlaying);
            }
        }

        //session.setMetadata(md.build());
        session.setActive(true);
        notification.show(nb, isPlaying);
    }

    @ReactMethod
    synchronized public void updatePlayback(ReadableMap info) {
        // init();
        if (state == null || volume == null || pb == null || session == null || notification == null || nb == null) {
            Log.e(TAG, "updatePlayback did not work, somethingwrong..");
            dump();
            return;
        }

        long updateTime;
        long elapsedTime;
        long bufferedTime = info.hasKey("bufferedTime") ? (long) (info.getDouble("bufferedTime") * 1000)
                : state.getBufferedPosition();
        float speed = info.hasKey("speed") ? (float) info.getDouble("speed") : state.getPlaybackSpeed();
        int pbState = info.hasKey("state") ? info.getInt("state") : state.getState();
        int maxVol = info.hasKey("maxVolume") ? info.getInt("maxVolume") : volume.getMaxVolume();
        int vol = info.hasKey("volume") ? info.getInt("volume") : volume.getCurrentVolume();
        ratingType = info.hasKey("rating") ? info.getInt("rating") : ratingType;

        if (info.hasKey("elapsedTime")) {
            elapsedTime = (long) (info.getDouble("elapsedTime") * 1000);
            updateTime = SystemClock.elapsedRealtime();
        } else {
            elapsedTime = state.getPosition();
            updateTime = state.getLastPositionUpdateTime();
        }

        pb.setState(pbState, elapsedTime, speed, updateTime);
        pb.setBufferedPosition(bufferedTime);
        pb.setActions(controls);

        isPlaying = pbState == PlaybackStateCompat.STATE_PLAYING || pbState == PlaybackStateCompat.STATE_BUFFERING;
        if (session.isActive() && (realArtWork != null && !realArtWork.isRecycled())) {
            notification.show(nb, isPlaying);
        }

        state = pb.build();
        session.setPlaybackState(state);

        updateNotificationMediaStyle();

        if(session.isActive()) {
            notification.show(nb, isPlaying);
        }

        if (remoteVolume) {
            session.setPlaybackToRemote(volume.create(null, maxVol, vol));
        } else {
            session.setPlaybackToLocal(AudioManager.STREAM_MUSIC);
        }
    }

    @ReactMethod
    synchronized public void resetNowPlaying() {
        if (!init)
            return;

        md = new MediaMetadataCompat.Builder();

        if (notification != null)
            notification.hide();
        session.setActive(false);
    }

    @ReactMethod
    synchronized public void enableControl(String control, boolean enable, ReadableMap options) {
        if (session == null || notification == null || pb == null) {
            Log.e(TAG, "enableControl after stop, return..");
            dump();
            return;
        }
        // init();

        Map<String, Integer> skipOptions = new HashMap<String, Integer>();

        long controlValue = 0l;
        switch (control) {
            case "skipForward":
                if (options.hasKey("interval"))
                    skipOptions.put("skipForward", options.getInt("interval"));
                controlValue = PlaybackStateCompat.ACTION_FAST_FORWARD;
                break;
            case "skipBackward":
                if (options.hasKey("interval"))
                    skipOptions.put("skipBackward", options.getInt("interval"));
                controlValue = PlaybackStateCompat.ACTION_REWIND;
                break;
            case "nextTrack":
                controlValue = PlaybackStateCompat.ACTION_SKIP_TO_NEXT;
                break;
            case "previousTrack":
                controlValue = PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS;
                break;
            case "play":
                Log.d(TAG, "play..");
                controlValue = PlaybackStateCompat.ACTION_PLAY;
                break;
            case "pause":
                Log.d(TAG, "setPause..");
                controlValue = PlaybackStateCompat.ACTION_PAUSE;
                break;
            case "togglePlayPause":
                controlValue = PlaybackStateCompat.ACTION_PLAY_PAUSE;
                break;
            case "stop":
                controlValue = PlaybackStateCompat.ACTION_STOP;
                break;
            case "seek":
                controlValue = PlaybackStateCompat.ACTION_SEEK_TO;
                break;
            case "setRating":
                controlValue = PlaybackStateCompat.ACTION_SET_RATING;
                break;
            case "volume":
                volume = volume.create(enable, null, null);
                if (remoteVolume)
                    session.setPlaybackToRemote(volume);
                return;
            case "remoteVolume":
                remoteVolume = enable;
                if (enable) {
                    session.setPlaybackToRemote(volume);
                } else {
                    session.setPlaybackToLocal(AudioManager.STREAM_MUSIC);
                }
                return;
            case "closeNotification":
                if (enable) {
                    if (options.hasKey("when")) {
                        if ("always".equals(options.getString("when"))) {
                            this.notificationClose = notificationClose.ALWAYS;
                        } else if ("paused".equals(options.getString("when"))) {
                            this.notificationClose = notificationClose.PAUSED;
                        } else {
                            this.notificationClose = notificationClose.NEVER;
                        }
                    }
                    return;
                }
            default:
                // Unknown control type, let's just ignore it
                return;
        }

        if (enable) {
            controls |= controlValue;
        } else {
            controls &= ~controlValue;
        }

        notification.updateActions(controls, skipOptions);
        pb.setActions(controls);

        state = pb.build();
        session.setPlaybackState(state);
    }

    public Bitmap getRealArtWork() {
        return realArtWork;
    }

    private void dump() {
        Log.i(TAG, ">DUMP START<");
        if (session != null) Log.d(TAG, "session = " + session);
        if (notification != null) Log.d(TAG, "notification = " + notification);
        if (pb != null) Log.d(TAG, "pb = " + pb);
        if (nb != null) Log.d(TAG, "nb = " + nb);
        if (state != null) Log.d(TAG, "state = " + state);
        if (volume != null) Log.d(TAG, "volume = " + volume);
        Log.i(TAG, ">DUMP END<");
    }

    @Override
    public void onTrimMemory(int level) {
        switch (level) {
            // Trims memory when it reaches a moderate level and the session is inactive
            case ComponentCallbacks2.TRIM_MEMORY_MODERATE:
            case ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE:
                if (session != null && session.isActive())
                    break;

                // Trims memory when it reaches a critical level
            case ComponentCallbacks2.TRIM_MEMORY_COMPLETE:
            case ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL:

                Log.e(TAG, "Control resources are being removed due to system's low memory (Level: " + level + ")");

                destroy();
                break;
        }
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {

    }

    @Override
    public void onLowMemory() {
        Log.e(TAG, "Control resources are being removed due to system's low memory (Level: MEMORY_COMPLETE)");
        destroy();
    }

    public enum NotificationClose {
        ALWAYS, PAUSED, NEVER
    }
}
