package android.media;
public class MediaPlayer {
    public int position, duration = 52000;
    public android.media.AudioDeviceInfo getRoutedDevice() { return null; }
    public boolean setPreferredDevice(android.media.AudioDeviceInfo device) { return false; }
    private OnCompletionListener completed;
    private OnErrorListener failed;
    public void complete() { playing = false; completed.onCompletion(this); }
    public void fail() {
        if (!failed.onError(this, 1, 0)) completed.onCompletion(this);
    }
    public boolean playing;
    public final PlaybackParams params = new PlaybackParams();
    public interface OnPreparedListener { void onPrepared(MediaPlayer player); }
    public interface OnCompletionListener { void onCompletion(MediaPlayer player); }
    public interface OnErrorListener { boolean onError(MediaPlayer player, int what, int extra); }
    public interface OnSeekCompleteListener { void onSeekComplete(MediaPlayer player); }
    private OnPreparedListener prepared;
    private OnSeekCompleteListener rewound;
    public PlaybackParams getPlaybackParams() { return params; }
    public void setPlaybackParams(PlaybackParams applied) { params.setSpeed(applied.getSpeed()); }
    public void setAudioAttributes(AudioAttributes attributes) {}
    public void setDataSource(String file) throws java.io.IOException {
        if (!new java.io.File(file).isFile()) throw new IllegalStateException("missing synthetic audio");
    }
    public void setOnPreparedListener(OnPreparedListener listener) { prepared = listener; }
    public void setOnCompletionListener(OnCompletionListener listener) { completed = listener; }
    public void setOnErrorListener(OnErrorListener listener) { failed = listener; }
    public void setOnSeekCompleteListener(OnSeekCompleteListener listener) { rewound = listener; }
    public void seekTo(int at) { position = at; if (rewound != null) rewound.onSeekComplete(this); }
    public void prepareAsync() { prepared.onPrepared(this); }
    public void start() { playing = true; }
    public void pause() { playing = false; }
    public boolean isPlaying() { return playing; }
    public int getCurrentPosition() { return position; }
    public int getDuration() { return duration; }
    public void release() { playing = false; }
}
