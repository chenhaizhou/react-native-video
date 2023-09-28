package com.brentvatne.exoplayer;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.support.v4.media.session.PlaybackStateCompat;
import android.text.TextUtils;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.Window;
import android.view.accessibility.CaptioningManager;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;

import com.brentvatne.lockscreen.MusicControlNotification;
import com.brentvatne.lockscreen.Utils;
import com.brentvatne.react.R;
import com.brentvatne.receiver.AudioBecomingNoisyReceiver;
import com.brentvatne.receiver.BecomingNoisyListener;
import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.Dynamic;
import com.facebook.react.bridge.LifecycleEventListener;
import com.facebook.react.bridge.ReadableArray;
import com.facebook.react.bridge.WritableArray;
import com.facebook.react.bridge.WritableMap;
import com.facebook.react.uimanager.ThemedReactContext;
import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.ExoPlaybackException;
import com.google.android.exoplayer2.ExoPlayer;
import com.google.android.exoplayer2.Format;
import com.google.android.exoplayer2.MediaItem;
import com.google.android.exoplayer2.PlaybackParameters;
import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.SimpleExoPlayer;
import com.google.android.exoplayer2.mediacodec.MediaCodecRenderer;
import com.google.android.exoplayer2.mediacodec.MediaCodecUtil;
import com.google.android.exoplayer2.metadata.Metadata;
import com.google.android.exoplayer2.metadata.MetadataOutput;
import com.google.android.exoplayer2.source.BehindLiveWindowException;
import com.google.android.exoplayer2.source.MediaSource;
import com.google.android.exoplayer2.source.ProgressiveMediaSource;
import com.google.android.exoplayer2.source.TrackGroup;
import com.google.android.exoplayer2.source.TrackGroupArray;
import com.google.android.exoplayer2.source.dash.DashMediaSource;
import com.google.android.exoplayer2.source.dash.DefaultDashChunkSource;
import com.google.android.exoplayer2.source.hls.HlsMediaSource;
import com.google.android.exoplayer2.source.smoothstreaming.DefaultSsChunkSource;
import com.google.android.exoplayer2.source.smoothstreaming.SsMediaSource;
import com.google.android.exoplayer2.trackselection.DefaultTrackSelector;
import com.google.android.exoplayer2.trackselection.MappingTrackSelector;
import com.google.android.exoplayer2.ui.PlayerControlView;
import com.google.android.exoplayer2.upstream.BandwidthMeter;
import com.google.android.exoplayer2.upstream.DataSource;
import com.google.android.exoplayer2.upstream.DefaultLoadErrorHandlingPolicy;
import com.google.android.exoplayer2.util.Util;

import java.net.CookieHandler;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.util.Locale;
import java.util.Map;

@SuppressLint("ViewConstructor")
class ReactExoplayerView extends FrameLayout implements
        LifecycleEventListener,
        Player.EventListener,
        BandwidthMeter.EventListener,
        BecomingNoisyListener,
        MetadataOutput {

    private static final String TAG = "ReactExoplayerView";
    private static final boolean DEBUG = false;

    private static final CookieManager DEFAULT_COOKIE_MANAGER;
    private static final int SHOW_PROGRESS = 1;

    static {
        DEFAULT_COOKIE_MANAGER = new CookieManager();
        DEFAULT_COOKIE_MANAGER.setCookiePolicy(CookiePolicy.ACCEPT_ORIGINAL_SERVER);
    }

    private final VideoEventEmitter eventEmitter;
    private PlayerControlView playerControlView;
    private View playPauseControlContainer;
    private Player.EventListener eventListener;

    private ExoPlayerView exoPlayerView;

    private DataSource.Factory mediaDataSourceFactory;
    private SimpleExoPlayer player;
    private DefaultTrackSelector trackSelector;
    private boolean playerNeedsSource;

    private int resumeWindow;
    private long resumePosition;
    private boolean loadVideoStarted;
    private boolean isFullscreen;
    private boolean isInBackground;
    private boolean isPaused;
    private boolean isBuffering;
    private boolean muted = false;
    private float rate = 1f;
    private float audioVolume = 1f;
    private int minLoadRetryCount = 3;
    private int maxBitRate = 0;
    private long seekTime = C.TIME_UNSET;

    // Props from React
    private Uri srcUri;
    private String extension;
    private boolean repeat;
    private String audioTrackType;
    private Dynamic audioTrackValue;
    private String videoTrackType;
    private Dynamic videoTrackValue;
    private String textTrackType;
    private Dynamic textTrackValue;
    private ReadableArray textTracks;
    private boolean disableFocus;
    private float mProgressUpdateInterval = 250.0f;
    private boolean playInBackground = false;
    private Map<String, String> requestHeaders;
    private boolean mReportBandwidth = false;
    private boolean controls;
    // \ End props

    // React
    private final ThemedReactContext themedReactContext;
    private final AudioBecomingNoisyReceiver audioBecomingNoisyReceiver;

    private final WakeLockManager wakeLockManager;
    private final WifiLockManager wifiLockManager;

    // TODO: need to remove these types, and use isPaused to control audio play/pause when ready.
    private static final String PLAYER_TYPE_SPEAK_EASY = "SpeakEasy";
    private static final String PLAYER_TYPE_PAGE_READING = "PageReading";
    private static final String PLAYER_TYPE_MINI_LESSON = "MiniLesson";

    private final Handler progressHandler = new Handler(new Handler.Callback() {
        @Override
        public boolean handleMessage(@NonNull Message msg) {
            if (PlayerInstanceHolder.INSTANCE.isSwitchOtherSource() && !isPlayOtherSource()) return false;
            if (loadVideoStarted) return false; // Make sure onProgress is called after onLoad
            if (msg.what == SHOW_PROGRESS) {
                if (player != null
                        && player.getPlaybackState() == Player.STATE_READY
                        && player.getPlayWhenReady() && player.getDuration() > 0
                ) {
                    long pos = player.getCurrentPosition();
                    long bufferedDuration = player.getBufferedPercentage() * player.getDuration() / 100;
                    eventEmitter.progressChanged(pos, bufferedDuration, player.getDuration());
                    msg = progressHandler.obtainMessage(SHOW_PROGRESS);
                    progressHandler.removeMessages(SHOW_PROGRESS);
                    progressHandler.sendMessageDelayed(msg, Math.round(mProgressUpdateInterval));
                    debug("===");
                } else if (player != null) {
                    debug("player != null");
                } else {
                    Log.e(TAG, "SHOW_PROGRESS: something wrong..");
                }
            }
            return false;
        }
    });

    private void debug(String debugInfo) {
        if (!DEBUG) return;
        Log.d(TAG, "SHOW_PROGRESS: START > " + debugInfo + "<");
        Log.d(TAG, "SHOW_PROGRESS:getAudioAttributes " + player.getAudioAttributes());
        Log.d(TAG, "SHOW_PROGRESS:getAudioFormat " + player.getAudioFormat());
        Log.d(TAG, "SHOW_PROGRESS:getBufferedPercentage " + player.getBufferedPercentage());
        Log.d(TAG, "SHOW_PROGRESS:getBufferedPosition " + player.getBufferedPosition());
        Log.d(TAG, "SHOW_PROGRESS:getContentPosition " + player.getContentPosition());
        Log.d(TAG, "SHOW_PROGRESS:getCurrentTimeline " + player.getCurrentTimeline());
        Log.d(TAG, "SHOW_PROGRESS:getCurrentPosition " + player.getCurrentPosition());
        Log.d(TAG, "SHOW_PROGRESS:getDuration " + player.getDuration());
        Log.d(TAG, "SHOW_PROGRESS:isLoading " + player.isLoading());
        Log.d(TAG, "SHOW_PROGRESS:getVolume " + player.getVolume());
        Log.d(TAG, "SHOW_PROGRESS:getPlaybackParameters " + player.getPlaybackParameters());
        Log.d(TAG, "SHOW_PROGRESS:getPlaybackState " + player.getPlaybackState());
        Log.d(TAG, "SHOW_PROGRESS:getPlayWhenReady " + player.getPlayWhenReady());
        Log.d(TAG, "SHOW_PROGRESS: END > " + debugInfo + "<");
    }

    public ReactExoplayerView(ThemedReactContext context) {
        super(context);
        this.themedReactContext = context;
        this.eventEmitter = new VideoEventEmitter(context);
        this.wakeLockManager = new WakeLockManager(context);
        this.wakeLockManager.setEnabled(true);
        this.wifiLockManager = new WifiLockManager(context);
        this.wifiLockManager.setEnabled(true);

        createViews();
        themedReactContext.addLifecycleEventListener(this);
        audioBecomingNoisyReceiver = new AudioBecomingNoisyReceiver(themedReactContext);

    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        Log.w(TAG, "Create ReactExoplayerView onAttachedToWindow");
        initializePlayer();
    }


    @NonNull
    @Override
    public String toString() {
        return getClass().getName() + "@" + Integer.toHexString(System.identityHashCode(this));
    }

    @Override
    public void setId(int id) {
        super.setId(id);
        eventEmitter.setViewId(id);
    }

    private void createViews() {
        clearResumePosition();
        mediaDataSourceFactory = buildDataSourceFactory(true);
        if (CookieHandler.getDefault() != DEFAULT_COOKIE_MANAGER) {
            CookieHandler.setDefault(DEFAULT_COOKIE_MANAGER);
        }

        LayoutParams layoutParams = new LayoutParams(
                LayoutParams.MATCH_PARENT,
                LayoutParams.MATCH_PARENT);
        exoPlayerView = new ExoPlayerView(getContext());
        exoPlayerView.setLayoutParams(layoutParams);

        addView(exoPlayerView, 0, layoutParams);
    }

    // LifecycleEventListener implementation
    @Override
    public void onHostResume() {
        if (!playInBackground || !isInBackground) {
            setPlayWhenReady(!isPaused);
        }
        isInBackground = false;
    }

    @Override
    public void onHostPause() {
        isInBackground = true;
        if (playInBackground) {
            return;
        }
        setPlayWhenReady(false);
    }

    @Override
    public void onHostDestroy() {
    }

    //BandwidthMeter.EventListener implementation
    @Override
    public void onBandwidthSample(int elapsedMs, long bytes, long bitrate) {
        if (mReportBandwidth) {
            eventEmitter.bandwidthReport(bitrate);
        }
    }

    @Override
    public void setKeepScreenOn(boolean keepScreenOn) {
        updateWakeAndWifiLock(keepScreenOn);
        super.setKeepScreenOn(keepScreenOn);
    }

    // Internal methods

    private void updateWakeAndWifiLock(boolean shouldStayAwake) {
        wakeLockManager.setStayAwake(shouldStayAwake);
        wifiLockManager.setStayAwake(shouldStayAwake);
    }

    /**
     * Toggling the visibility of the player control view
     */
    private void togglePlayerControlVisibility() {
        if (player == null) return;
        reLayout(playerControlView);
        if (playerControlView.isVisible()) {
            playerControlView.hide();
        } else {
            playerControlView.show();
        }
    }

    /**
     * Initializing Player control
     */
    private void initializePlayerControl() {
        if (playerControlView == null) {
            playerControlView = new PlayerControlView(getContext());
        }

        // Setting the player for the playerControlView
        playerControlView.setPlayer(player);
        playerControlView.show();
        playPauseControlContainer = playerControlView.findViewById(R.id.exo_play_pause_container);

        // Invoking onClick event for exoplayerView
        exoPlayerView.setOnClickListener(v -> togglePlayerControlVisibility());

        // Invoking onPlayerStateChanged event for Player
        eventListener = new Player.EventListener() {
            @Override
            public void onPlayerStateChanged(boolean playWhenReady, int playbackState) {
                reLayout(playPauseControlContainer);
                //Remove this eventListener once its executed. since UI will work fine once after the reLayout is done
                if (player != null) player.removeListener(eventListener);
            }
        };
        player.addListener(eventListener);
    }

    /**
     * Adding Player control to the frame layout
     */
    private void addPlayerControl() {
        if (player == null) return;
        LayoutParams layoutParams = new LayoutParams(
                LayoutParams.MATCH_PARENT,
                LayoutParams.MATCH_PARENT);
        playerControlView.setLayoutParams(layoutParams);
        int indexOfPC = indexOfChild(playerControlView);
        if (indexOfPC != -1) {
            removeViewAt(indexOfPC);
        }
        addView(playerControlView, 1, layoutParams);
    }

    /**
     * Update the layout
     *
     * @param view view needs to update layout
     *             <p>
     *             This is a workaround for the open bug in react-native: https://github.com/facebook/react-native/issues/17968
     */
    private void reLayout(View view) {
        if (view == null) return;
        view.measure(MeasureSpec.makeMeasureSpec(getMeasuredWidth(), MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(getMeasuredHeight(), MeasureSpec.EXACTLY));
        view.layout(view.getLeft(), view.getTop(), view.getMeasuredWidth(), view.getMeasuredHeight());
    }

    private void initializePlayer() {
        ReactExoplayerView self = this;
        // This ensures all props have been setted, to avoid async racing conditions.
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
//            if (player == null) {
                player = PlayerInstanceHolder.INSTANCE.getPlayer(getContext());
                trackSelector = PlayerInstanceHolder.INSTANCE.getTrackSelector();
                player.addListener(self);
                player.addMetadataOutput(self);
                exoPlayerView.setPlayer(player);
                audioBecomingNoisyReceiver.setListener(self);
                if (PlayerInstanceHolder.INSTANCE.getBandwidthMeter() != null) {
                    PlayerInstanceHolder.INSTANCE.getBandwidthMeter().addEventListener(new Handler(), self);
                }
                setPlayWhenReady(!isPaused);
                playerNeedsSource = true;
                PlaybackParameters params = new PlaybackParameters(rate, 1f);
                player.setPlaybackParameters(params);
//            }

            if (playerNeedsSource && srcUri != null) {
                if (PlayerInstanceHolder.INSTANCE.getMediaSourceList().getSize() <= 0) {
                    PlayerInstanceHolder.INSTANCE.convertToExoplayerDataSource(themedReactContext);
                }
                Log.i(TAG, "source list size:" + PlayerInstanceHolder.INSTANCE.getMediaSourceList().getSize());
                resumeWindow = PlayerInstanceHolder.INSTANCE.mapToCurrentWindowIndex(srcUri);
                Log.i(TAG, "reload resource: resumeWindow" + resumeWindow + "extension:" + extension);
                boolean isRePrepareSource = false;

                if (resumeWindow < 0 || isPlayOtherSource()) {
                    PlayerInstanceHolder.INSTANCE.switchToOtherResource(true);
                    player.setRepeatMode(Player.REPEAT_MODE_OFF);
                    player.setMediaSource(buildMediaSource(srcUri, ""));
                    player.prepare();
                    if (!extension.equals(PLAYER_TYPE_PAGE_READING) && !extension.equals(PLAYER_TYPE_MINI_LESSON)) {
                        player.setPlayWhenReady(true);
                    }
                    isRePrepareSource = true;
                }  else {
                    if (PlayerInstanceHolder.INSTANCE.isSwitchOtherSource() ||
                            player.getCurrentTimeline().getWindowCount() <= resumeWindow ||
                            player.getPlaybackState() == Player.STATE_IDLE
                    ) {
                        player.stop(true);
                        PlayerInstanceHolder.INSTANCE.convertToExoplayerDataSource(themedReactContext);
                        player.prepare(PlayerInstanceHolder.INSTANCE.getMediaSourceList());
                        resumePosition = PlayerInstanceHolder.INSTANCE.getResumePosition();
                        isRePrepareSource = true;
                    }

                    Log.i(TAG, "play lesson list resumePosition:" + resumePosition + "isSwitchOtherSource" + PlayerInstanceHolder.INSTANCE.isSwitchOtherSource() );
                    player.seekTo(resumeWindow, resumePosition);
                    PlayerInstanceHolder.INSTANCE.switchToOtherResource(false);
                }

                playerNeedsSource = false;
                eventEmitter.loadStart();
                loadVideoStarted = true;
                if (!isRePrepareSource) { this.onPlayerStateChanged(player.getPlayWhenReady(), player.getPlaybackState());}
            }

            // Initializing the playerControlView
            initializePlayerControl();
            setControls(controls);
            applyModifiers();
        }, 1);
    }

    private MediaSource buildMediaSource(Uri uri, String overrideExtension) {
        int type = Util.inferContentType(!TextUtils.isEmpty(overrideExtension) ? "." + overrideExtension
                : uri.getLastPathSegment());
        switch (type) {
            case C.TYPE_SS:
                return new SsMediaSource.Factory(new DefaultSsChunkSource.Factory(mediaDataSourceFactory), buildDataSourceFactory(false))
                        .setLoadErrorHandlingPolicy(new DefaultLoadErrorHandlingPolicy(minLoadRetryCount))
                        .createMediaSource(MediaItem.fromUri(uri));
            case C.TYPE_DASH:
                return new DashMediaSource.Factory(new DefaultDashChunkSource.Factory(mediaDataSourceFactory), buildDataSourceFactory(false))
                        .setLoadErrorHandlingPolicy(new DefaultLoadErrorHandlingPolicy(minLoadRetryCount))
                        .createMediaSource(MediaItem.fromUri(uri));
            case C.TYPE_HLS:
                return new HlsMediaSource.Factory(mediaDataSourceFactory)
                        .setLoadErrorHandlingPolicy(new DefaultLoadErrorHandlingPolicy(minLoadRetryCount))
                        .createMediaSource(MediaItem.fromUri(uri));
            case C.TYPE_OTHER:
                return new ProgressiveMediaSource.Factory(mediaDataSourceFactory)
                        .createMediaSource(MediaItem.fromUri(uri));
            default: {
                throw new IllegalStateException("Unsupported type: " + type);
            }
        }
    }

    private void removeCurrentViewListener() {
        if (player != null) {
            updateResumePosition();
        }

        progressHandler.removeMessages(SHOW_PROGRESS);
        themedReactContext.removeLifecycleEventListener(this);
        audioBecomingNoisyReceiver.removeListener();
        PlayerInstanceHolder.INSTANCE.getPlayer(getContext()).removeListener(this);
        if (PlayerInstanceHolder.INSTANCE.getBandwidthMeter() != null) {
            PlayerInstanceHolder.INSTANCE.getBandwidthMeter().removeEventListener(this);
        }
    }

    private void setPlayWhenReady(boolean playWhenReady) {
        if (player == null) {
            return;
        }

        player.setPlayWhenReady(playWhenReady);
    }

    private void startPlayback() {
        if (player != null) {
            switch (player.getPlaybackState()) {
                case Player.STATE_IDLE:
                case Player.STATE_ENDED:
                    initializePlayer();
                    break;
                case Player.STATE_BUFFERING:
                case Player.STATE_READY:
                    if (!player.getPlayWhenReady()) {
                        setPlayWhenReady(true);
                    }
                    break;
                default:
                    break;
            }

        } else {
            initializePlayer();
        }
        if (!disableFocus) {
            setKeepScreenOn(true);
        }
    }

    private void pausePlayback() {
        if (player != null) {
            if (player.getPlayWhenReady()) {
                setPlayWhenReady(false);
            }
        }
        setKeepScreenOn(false);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        Log.w(TAG, "onDetachedFromWindow: extension" + extension);
        stopPlayback();
        if (isPlayOtherSource() && PlayerInstanceHolder.INSTANCE.isSwitchOtherSource()) {
            pausePlayback();
            resetResource();
        }
    }

    private void stopPlayback() {
        onStopPlayback();
        removeCurrentViewListener();
    }

    private void resetResource() {
        if (player != null) player.stop(true);
    }

    private void onStopPlayback() {
        if (isFullscreen) {
            setFullscreen(false);
        }
        setKeepScreenOn(false);
    }

    private void updateResumePosition() {
        resumeWindow = player.getCurrentWindowIndex();
        resumePosition = player.isCurrentWindowSeekable() ? Math.max(0, player.getCurrentPosition())
                : C.TIME_UNSET;
    }

    private void clearResumePosition() {
        resumeWindow = C.INDEX_UNSET;
        resumePosition = C.TIME_UNSET;
    }

    /**
     * Returns a new DataSource factory.
     *
     * @param useBandwidthMeter Whether to set as a listener to the new
     *                          DataSource factory.
     * @return A new DataSource factory.
     */
    private DataSource.Factory buildDataSourceFactory(boolean useBandwidthMeter) {
        return DataSourceUtil.getDefaultDataSourceFactory(this.themedReactContext, useBandwidthMeter ? PlayerInstanceHolder.INSTANCE.getBandwidthMeter() : null, requestHeaders);
    }

    @Override
    public void onAudioBecomingNoisy() {
        eventEmitter.audioBecomingNoisy();
    }


    @Override
    public void onPlayWhenReadyChanged(boolean playWhenReady, int reason) {
       if (!playWhenReady && reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS) {
           sendMediaButtonBroadcast(PlaybackStateCompat.ACTION_PAUSE);
       }
    }

    private void sendMediaButtonBroadcast(long action) {
        int keyCode = Utils.toKeyCode(action);
        Intent intent = new Intent(MusicControlNotification.MEDIA_BUTTON);
        intent.putExtra(Intent.EXTRA_KEY_EVENT, new KeyEvent(KeyEvent.ACTION_DOWN, keyCode));
        intent.putExtra(MusicControlNotification.PACKAGE_NAME, themedReactContext.getPackageName());
        themedReactContext.sendBroadcast(intent);
    }

    @Override
    public void onPlaybackStateChanged(int playbackState) {
        boolean playWhenReady = player.getPlayWhenReady();
        String text = "onStateChanged: playWhenReady=" + playWhenReady + ", playbackState=";
        updateWakeAndWifiLock(playWhenReady && (playbackState == Player.STATE_READY || playbackState == Player.STATE_BUFFERING));
        switch (playbackState) {
            case Player.STATE_IDLE:
                text += "idle";
                eventEmitter.idle();
                break;
            case Player.STATE_BUFFERING:
                text += "buffering";
                onBuffering(true);
                break;
            case Player.STATE_READY:
                text += "ready";
                eventEmitter.ready();
                onBuffering(false);
                startProgressHandler();
                videoLoaded();
                //Setting the visibility for the playerControlView
                if (playerControlView != null) {
                    playerControlView.show();
                }

                eventEmitter.audioFocusChanged(playWhenReady);
                break;
            case Player.STATE_ENDED:
                text += "ended";
                eventEmitter.end();
                onStopPlayback();
                break;
            default:
                text += "unknown";
                break;
        }
        Log.d(TAG, text);
    }

    private void startProgressHandler() {
        progressHandler.sendEmptyMessage(SHOW_PROGRESS);
    }

    private void videoLoaded() {
        if (loadVideoStarted) {
            loadVideoStarted = false;
            setSelectedAudioTrack(audioTrackType, audioTrackValue);
            setSelectedVideoTrack(videoTrackType, videoTrackValue);
            setSelectedTextTrack(textTrackType, textTrackValue);
            Format videoFormat = player.getVideoFormat();
            int width = videoFormat != null ? videoFormat.width : 0;
            int height = videoFormat != null ? videoFormat.height : 0;
            eventEmitter.load(player.getDuration(), player.getCurrentPosition(), width, height,
                    getAudioTrackInfo(), getTextTrackInfo(), getVideoTrackInfo());
        }
    }

    private WritableArray getAudioTrackInfo() {
        WritableArray audioTracks = Arguments.createArray();

        if (trackSelector == null) return audioTracks;

        MappingTrackSelector.MappedTrackInfo info = trackSelector.getCurrentMappedTrackInfo();
        int index = getTrackRendererIndex(C.TRACK_TYPE_AUDIO);
        if (info == null || index == C.INDEX_UNSET) {
            return audioTracks;
        }

        TrackGroupArray groups = info.getTrackGroups(index);
        for (int i = 0; i < groups.length; ++i) {
            Format format = groups.get(i).getFormat(0);
            WritableMap audioTrack = Arguments.createMap();
            audioTrack.putInt("index", i);
            audioTrack.putString("title", format.id != null ? format.id : "");
            audioTrack.putString("type", format.sampleMimeType);
            audioTrack.putString("language", format.language != null ? format.language : "");
            audioTrack.putString("bitrate", format.bitrate == Format.NO_VALUE ? ""
                    : String.format(Locale.US, "%.2fMbps", format.bitrate / 1000000f));
            audioTracks.pushMap(audioTrack);
        }
        return audioTracks;
    }

    private WritableArray getVideoTrackInfo() {
        WritableArray videoTracks = Arguments.createArray();

        if (trackSelector == null) return videoTracks;
        MappingTrackSelector.MappedTrackInfo info = trackSelector.getCurrentMappedTrackInfo();
        int index = getTrackRendererIndex(C.TRACK_TYPE_VIDEO);
        if (info == null || index == C.INDEX_UNSET) {
            return videoTracks;
        }

        TrackGroupArray groups = info.getTrackGroups(index);
        for (int i = 0; i < groups.length; ++i) {
            TrackGroup group = groups.get(i);

            for (int trackIndex = 0; trackIndex < group.length; trackIndex++) {
                Format format = group.getFormat(trackIndex);
                WritableMap videoTrack = Arguments.createMap();
                videoTrack.putInt("width", format.width == Format.NO_VALUE ? 0 : format.width);
                videoTrack.putInt("height", format.height == Format.NO_VALUE ? 0 : format.height);
                videoTrack.putInt("bitrate", format.bitrate == Format.NO_VALUE ? 0 : format.bitrate);
                videoTrack.putString("codecs", format.codecs != null ? format.codecs : "");
                videoTrack.putString("trackId",
                        format.id == null ? String.valueOf(trackIndex) : format.id);
                videoTracks.pushMap(videoTrack);
            }
        }
        return videoTracks;
    }

    private WritableArray getTextTrackInfo() {
        WritableArray textTracks = Arguments.createArray();

        if (trackSelector == null) return textTracks;
        MappingTrackSelector.MappedTrackInfo info = trackSelector.getCurrentMappedTrackInfo();
        int index = getTrackRendererIndex(C.TRACK_TYPE_TEXT);
        if (info == null || index == C.INDEX_UNSET) {
            return textTracks;
        }

        TrackGroupArray groups = info.getTrackGroups(index);
        for (int i = 0; i < groups.length; ++i) {
            Format format = groups.get(i).getFormat(0);
            WritableMap textTrack = Arguments.createMap();
            textTrack.putInt("index", i);
            textTrack.putString("title", format.id != null ? format.id : "");
            textTrack.putString("type", format.sampleMimeType);
            textTrack.putString("language", format.language != null ? format.language : "");
            textTracks.pushMap(textTrack);
        }
        return textTracks;
    }

    private void onBuffering(boolean buffering) {
        if (isBuffering == buffering) {
            return;
        }

        isBuffering = buffering;
        eventEmitter.buffering(buffering);
    }

    @Override
    public void onPositionDiscontinuity(int reason) {
        if (playerNeedsSource) {
            // This will only occur if the user has performed a seek whilst in the error state. Update the
            // resume position so that if the user then retries, playback will resume from the position to
            // which they seeked.
            updateResumePosition();
        }
        // When repeat is turned on, reaching the end of the video will not cause a state change
        // so we need to explicitly detect it.
        if (reason == ExoPlayer.DISCONTINUITY_REASON_PERIOD_TRANSITION
                && player.getRepeatMode() == Player.REPEAT_MODE_ONE) {
            eventEmitter.end();
        }
    }

    @Override
    public void onSeekProcessed() {
        eventEmitter.seek(player.getCurrentPosition(), seekTime);
        seekTime = C.TIME_UNSET;
    }

    @Override
    public void onPlaybackParametersChanged(PlaybackParameters params) {
        eventEmitter.playbackRateChange(params.speed);
    }

    @Override
    public void onPlayerError(ExoPlaybackException e) {
        String errorString = null;
        Exception ex = e;
        Log.w(TAG, "player error" + e.toString());
        if (e.type == ExoPlaybackException.TYPE_RENDERER) {
            Exception cause = e.getRendererException();
            if (cause instanceof MediaCodecRenderer.DecoderInitializationException) {
                // Special case for decoder initialization failures.
                MediaCodecRenderer.DecoderInitializationException decoderInitializationException =
                        (MediaCodecRenderer.DecoderInitializationException) cause;
                if (decoderInitializationException.codecInfo == null) {
                    if (decoderInitializationException.getCause() instanceof MediaCodecUtil.DecoderQueryException) {
                        errorString = getResources().getString(R.string.error_querying_decoders);
                    } else if (decoderInitializationException.secureDecoderRequired) {
                        errorString = getResources().getString(R.string.error_no_secure_decoder,
                                decoderInitializationException.mimeType);
                    } else {
                        errorString = getResources().getString(R.string.error_no_decoder,
                                decoderInitializationException.mimeType);
                    }
                } else {
                    errorString = getResources().getString(R.string.error_instantiating_decoder,
                            decoderInitializationException);
                }
            }
        } else if (e.type == ExoPlaybackException.TYPE_SOURCE) {
            ex = e.getSourceException();
            errorString = getResources().getString(R.string.unrecognized_media_format);
        }
        if (errorString != null) {
            eventEmitter.error(errorString, ex);
        }
        playerNeedsSource = true;
        if (isBehindLiveWindow(e)) {
            clearResumePosition();
            initializePlayer();
        } else {
            updateResumePosition();
        }
    }

    private static boolean isBehindLiveWindow(ExoPlaybackException e) {
        if (e.type != ExoPlaybackException.TYPE_SOURCE) {
            return false;
        }
        Throwable cause = e.getSourceException();
        while (cause != null) {
            if (cause instanceof BehindLiveWindowException) {
                return true;
            }
            cause = cause.getCause();
        }
        return false;
    }

    public int getTrackRendererIndex(int trackType) {
        int rendererCount = player.getRendererCount();
        for (int rendererIndex = 0; rendererIndex < rendererCount; rendererIndex++) {
            if (player.getRendererType(rendererIndex) == trackType) {
                return rendererIndex;
            }
        }
        return C.INDEX_UNSET;
    }

    @Override
    public void onMetadata(@NonNull Metadata metadata) {
        eventEmitter.timedMetadata(metadata);
    }

    // ReactExoplayerViewManager public api

    public void setSrc(final Uri uri, final String extension, Map<String, String> headers) {
        if (uri != null) {
            if ( PlayerInstanceHolder.INSTANCE.updateMediaItemToLocalUrl(uri)) {
                PlayerInstanceHolder.INSTANCE.convertToExoplayerDataSource(themedReactContext);
                if (player != null) {
                    player.setMediaSource(PlayerInstanceHolder.INSTANCE.getMediaSourceList());
                    player.prepare();
                }
            }
            boolean isSourceEqual = uri.equals(srcUri);

            this.srcUri = uri;
            this.extension = extension;
            this.requestHeaders = headers;
            this.mediaDataSourceFactory = DataSourceUtil.getDefaultDataSourceFactory(this.themedReactContext, PlayerInstanceHolder.INSTANCE.getBandwidthMeter(), this.requestHeaders);

            if (srcUri != null && !isSourceEqual) {
                reloadSource();
            }
        }
    }

    public void setProgressUpdateInterval(final float progressUpdateInterval) {
        mProgressUpdateInterval = progressUpdateInterval;
    }

    public void setReportBandwidth(boolean reportBandwidth) {
        mReportBandwidth = reportBandwidth;
    }

    public void setRawSrc(final Uri uri, final String extension) {
        if (uri != null) {
            boolean isOriginalSourceNull = srcUri == null;
            boolean isSourceEqual = uri.equals(srcUri);

            this.srcUri = uri;
            this.extension = extension;
            this.mediaDataSourceFactory = buildDataSourceFactory(true);

            if (!isOriginalSourceNull && !isSourceEqual) {
                reloadSource();
            }
        }
    }

    public void setTextTracks(ReadableArray textTracks) {
        this.textTracks = textTracks;
        reloadSource();
    }

    private void reloadSource() {
        playerNeedsSource = true;
        initializePlayer();
    }

    public void setResizeModeModifier(@ResizeMode.Mode int resizeMode) {
        exoPlayerView.setResizeMode(resizeMode);
    }

    private void applyModifiers() {
        setRepeatModifier(repeat);
        setMutedModifier(muted);
    }

    private boolean isPlayOtherSource() {
        return (extension!= null && !extension.isEmpty() || PlayerInstanceHolder.INSTANCE.mapToCurrentWindowIndex(srcUri) == C.INDEX_UNSET);
    }

    public void setRepeatModifier(boolean repeat) {
        if (player != null) {
            if (repeat) {
                player.setRepeatMode(Player.REPEAT_MODE_ONE);
            } else {
                player.setRepeatMode(Player.REPEAT_MODE_OFF);
            }
        }
        this.repeat = repeat;
    }

    public void setSelectedTrack(int trackType, String type, Dynamic value) {
        if (player == null || trackSelector == null) return;
        int rendererIndex = getTrackRendererIndex(trackType);
        if (rendererIndex == C.INDEX_UNSET) {
            return;
        }
        MappingTrackSelector.MappedTrackInfo info = trackSelector.getCurrentMappedTrackInfo();
        if (info == null) {
            return;
        }

        TrackGroupArray groups = info.getTrackGroups(rendererIndex);
        int groupIndex = C.INDEX_UNSET;
        int[] tracks = {0};

        if (TextUtils.isEmpty(type)) {
            type = "default";
        }

        DefaultTrackSelector.Parameters disableParameters = trackSelector.getParameters()
                .buildUpon()
                .setRendererDisabled(rendererIndex, true)
                .build();

        if (type.equals("disabled")) {
            trackSelector.setParameters(disableParameters);
            return;
        } else if (type.equals("language")) {
            for (int i = 0; i < groups.length; ++i) {
                Format format = groups.get(i).getFormat(0);
                if (format.language != null && format.language.equals(value.asString())) {
                    groupIndex = i;
                    break;
                }
            }
        } else if (type.equals("title")) {
            for (int i = 0; i < groups.length; ++i) {
                Format format = groups.get(i).getFormat(0);
                if (format.id != null && format.id.equals(value.asString())) {
                    groupIndex = i;
                    break;
                }
            }
        } else if (type.equals("index")) {
            if (value.asInt() < groups.length) {
                groupIndex = value.asInt();
            }
        } else if (type.equals("resolution")) {
            int height = value.asInt();
            for (int i = 0; i < groups.length; ++i) { // Search for the exact height
                TrackGroup group = groups.get(i);
                for (int j = 0; j < group.length; j++) {
                    Format format = group.getFormat(j);
                    if (format.height == height) {
                        groupIndex = i;
                        tracks[0] = j;
                        break;
                    }
                }
            }
        } else if (rendererIndex == C.TRACK_TYPE_TEXT && Util.SDK_INT > 18) { // Text default
            // Use system settings if possible
            CaptioningManager captioningManager
                    = (CaptioningManager) themedReactContext.getSystemService(Context.CAPTIONING_SERVICE);
            if (captioningManager != null && captioningManager.isEnabled()) {
                groupIndex = getGroupIndexForDefaultLocale(groups);
            }
        } else if (rendererIndex == C.TRACK_TYPE_AUDIO) { // Audio default
            groupIndex = getGroupIndexForDefaultLocale(groups);
        }

        if (groupIndex == C.INDEX_UNSET && trackType == C.TRACK_TYPE_VIDEO && groups.length != 0) { // Video auto
            // Add all tracks as valid options for ABR to choose from
            TrackGroup group = groups.get(0);
            tracks = new int[group.length];
            groupIndex = 0;
            for (int j = 0; j < group.length; j++) {
                tracks[j] = j;
            }
        }

        if (groupIndex == C.INDEX_UNSET) {
            trackSelector.setParameters(disableParameters);
            return;
        }

        DefaultTrackSelector.Parameters selectionParameters = trackSelector.getParameters()
                .buildUpon()
                .setRendererDisabled(rendererIndex, false)
                .setSelectionOverride(rendererIndex, groups,
                        new DefaultTrackSelector.SelectionOverride(groupIndex, tracks))
                .build();
        trackSelector.setParameters(selectionParameters);
    }

    private int getGroupIndexForDefaultLocale(TrackGroupArray groups) {
        if (groups.length == 0) {
            return C.INDEX_UNSET;
        }

        int groupIndex = 0; // default if no match
        String locale2 = Locale.getDefault().getLanguage(); // 2 letter code
        String locale3 = Locale.getDefault().getISO3Language(); // 3 letter code
        for (int i = 0; i < groups.length; ++i) {
            Format format = groups.get(i).getFormat(0);
            String language = format.language;
            if (language != null && (language.equals(locale2) || language.equals(locale3))) {
                groupIndex = i;
                break;
            }
        }
        return groupIndex;
    }

    public void setSelectedVideoTrack(String type, Dynamic value) {
        videoTrackType = type;
        videoTrackValue = value;
        setSelectedTrack(C.TRACK_TYPE_VIDEO, videoTrackType, videoTrackValue);
    }

    public void setSelectedAudioTrack(String type, Dynamic value) {
        audioTrackType = type;
        audioTrackValue = value;
        setSelectedTrack(C.TRACK_TYPE_AUDIO, audioTrackType, audioTrackValue);
    }

    public void setSelectedTextTrack(String type, Dynamic value) {
        textTrackType = type;
        textTrackValue = value;
        setSelectedTrack(C.TRACK_TYPE_TEXT, textTrackType, textTrackValue);
    }

    public void setPausedModifier(boolean paused) {
        Log.w(TAG, "called pause" + paused);
        isPaused = paused;
        if (player != null) {
            if (!paused) {
                startPlayback();
            } else {
                pausePlayback();
            }
        }
    }

    public void setMutedModifier(boolean muted) {
        this.muted = muted;
        audioVolume = muted ? 0.f : audioVolume;
        if (player != null) {
            player.setVolume(audioVolume);
        }
    }


    public void setVolumeModifier(float volume) {
        audioVolume = volume;
        if (player != null) {
            player.setVolume(audioVolume);
        }
    }

    public void seekTo(long positionMs) {

        if (player != null) {
            int  playingIndex = PlayerInstanceHolder.INSTANCE.mapToCurrentWindowIndex(srcUri);
            if (!extension.isEmpty() || playingIndex == C.INDEX_UNSET || playingIndex == player.getCurrentWindowIndex() ) {
                seekTime = positionMs;
                if (player.getPlaybackState() == Player.STATE_IDLE && extension.equals(PLAYER_TYPE_PAGE_READING)) {
                    player.setMediaSource(buildMediaSource(srcUri, ""));
                    player.prepare();
                    player.setPlayWhenReady(true);
                }
                player.seekTo(positionMs);
                Log.i(TAG, "execute seek:" + positionMs);
                if (PLAYER_TYPE_SPEAK_EASY.equals(extension)) setPlayWhenReady(true);
            } else {
                resumePosition = positionMs;
            }
        } else {
            resumePosition = positionMs;
        }
    }

    public void setRateModifier(float newRate) {
        rate = newRate;

        if (player != null) {
            PlaybackParameters params = new PlaybackParameters(rate, 1f);
            player.setPlaybackParameters(params);
        }
    }

    public void setMaxBitRateModifier(int newMaxBitRate) {
        maxBitRate = newMaxBitRate;
        if (player != null && trackSelector != null) {
            trackSelector.setParameters(trackSelector.buildUponParameters()
                    .setMaxVideoBitrate(maxBitRate == 0 ? Integer.MAX_VALUE : maxBitRate));
        }
    }

    public void setMinLoadRetryCountModifier(int newMinLoadRetryCount) {
        minLoadRetryCount = newMinLoadRetryCount;
        removeCurrentViewListener();
        initializePlayer();
    }

    public void setPlayInBackground(boolean playInBackground) {
        this.playInBackground = playInBackground;
    }

    public void setDisableFocus(boolean disableFocus) {
        this.disableFocus = disableFocus;
    }

    public void setFullscreen(boolean fullscreen) {
        if (fullscreen == isFullscreen) {
            return; // Avoid generating events when nothing is changing
        }
        isFullscreen = fullscreen;

        Activity activity = themedReactContext.getCurrentActivity();
        if (activity == null) {
            return;
        }
        Window window = activity.getWindow();
        View decorView = window.getDecorView();
        int uiOptions;
        if (isFullscreen) {
            if (Util.SDK_INT >= 19) { // 4.4+
                uiOptions = SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | SYSTEM_UI_FLAG_FULLSCREEN;
            } else {
                uiOptions = SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | SYSTEM_UI_FLAG_FULLSCREEN;
            }
            eventEmitter.fullscreenWillPresent();
            decorView.setSystemUiVisibility(uiOptions);
            eventEmitter.fullscreenDidPresent();
        } else {
            uiOptions = View.SYSTEM_UI_FLAG_VISIBLE;
            eventEmitter.fullscreenWillDismiss();
            decorView.setSystemUiVisibility(uiOptions);
            eventEmitter.fullscreenDidDismiss();
        }
    }

    public void setUseTextureView(boolean useTextureView) {
        exoPlayerView.setUseTextureView(useTextureView);
    }

    public void setHideShutterView(boolean hideShutterView) {
        exoPlayerView.setHideShutterView(hideShutterView);
    }

    /**
     * Handling controls prop
     *
     * @param controls Controls prop, if true enable controls, if false disable them
     */
    public void setControls(boolean controls) {
        this.controls = controls;
        if (player == null || exoPlayerView == null) return;
        if (controls) {
            addPlayerControl();
        } else {
            int indexOfPC = indexOfChild(playerControlView);
            if (indexOfPC != -1) {
                removeViewAt(indexOfPC);
            }
        }
    }
}
