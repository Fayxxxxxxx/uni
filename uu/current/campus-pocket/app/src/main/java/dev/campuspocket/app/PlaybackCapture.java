package dev.campuspocket.app;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioPlaybackCaptureConfiguration;
import android.media.AudioRecord;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;

public final class PlaybackCapture extends Service {
    private static final int RATE = 44100;
    private static final int LIMIT_SECONDS = 120;
    static volatile boolean running;
    static volatile boolean finishing;
    private static PlaybackCapture instance;
    private volatile boolean shouldStop;
    private volatile boolean aborted;
    private boolean automatic;
    private boolean course;
    private String target;
    private int duration;
    private MediaProjection projection;
    private AudioPlaybackCaptureConfiguration audioConfig;
    private Store store;
    private final Handler main = new Handler(Looper.getMainLooper());

    @Override public IBinder onBind(Intent intent) { return null; }

    static boolean courseActive() { return instance != null && instance.course; }

    static boolean courseAuthorized(String pkg) {
        return courseActive() && pkg != null && pkg.equals(instance.target) && instance.projection != null;
    }

    static void closeCourse() {
        if (courseActive()) instance.cancelAuthorization();
    }

    @Override public int onStartCommand(Intent intent, int flags, int id) {
        if (intent == null) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if ("stop".equals(intent.getAction())) {
            shouldStop = true;
            return START_NOT_STICKY;
        }
        if ("course_close".equals(intent.getAction())) {
            closeCourse();
            return START_NOT_STICKY;
        }
        if ("course_next".equals(intent.getAction())) {
            if (courseAuthorized(intent.getStringExtra(CampusApp.EXTRA_PACKAGE)) && !running && !finishing) {
                beginSegment(intent.getIntExtra("duration", -1));
            } else if (course) {
                status("听力采集授权已结束，或上一段仍在转写。");
                signal("error");
            }
            return START_NOT_STICKY;
        }
        if (running || finishing) return START_NOT_STICKY;
        instance = this;
        store = new Store(this);
        automatic = intent.getBooleanExtra("automatic", false);
        course = automatic && intent.getBooleanExtra("course_audio", false);
        if (Build.VERSION.SDK_INT < 29 || !store.config().speechReady()) {
            status("播放采集需 Android 10 及转写服务。");
            signal("error");
            stopSelf();
            return START_NOT_STICKY;
        }
        try {
            Intent grant = Build.VERSION.SDK_INT >= 33
                    ? intent.getParcelableExtra("grant", Intent.class) : intent.getParcelableExtra("grant");
            target = intent.getStringExtra(CampusApp.EXTRA_PACKAGE);
            if (!CampusApp.supports(target)) throw new IOException("无法确定要采集的 U校园版本。");
            int uid = getPackageManager().getApplicationInfo(target, 0).uid;
            NotificationManager manager = getSystemService(NotificationManager.class);
            manager.createNotificationChannel(new NotificationChannel("campus_audio", "听力采集",
                    NotificationManager.IMPORTANCE_LOW));
            Intent stop = new Intent(this, PlaybackCapture.class).setAction("stop");
            PendingIntent end = PendingIntent.getService(this, 1, stop, PendingIntent.FLAG_IMMUTABLE);
            Notification notification = new Notification.Builder(this, "campus_audio")
                    .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                    .setContentTitle("正在采集 U校园播放声音")
                    .setContentText("听力播放结束后，点「停止并转写」。")
                    .setOngoing(true)
                    .addAction(new Notification.Action.Builder(null, "停止并转写", end).build())
                    .build();
            startForeground(8, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
            projection = getSystemService(MediaProjectionManager.class)
                    .getMediaProjection(Activity.RESULT_OK, grant);
            if (projection == null) throw new IOException("没有获得系统采集授权。");
            MediaProjection current = projection;
            projection.registerCallback(new MediaProjection.Callback() {
                @Override public void onStop() {
                    if (projection == current) {
                        projection = null;
                        signal("cancel");
                        cancelAuthorization();
                    }
                }
            }, main);
            audioConfig = new AudioPlaybackCaptureConfiguration.Builder(projection)
                    .addMatchingUid(uid)
                    .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                    .addMatchingUsage(AudioAttributes.USAGE_GAME)
                    .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                    .build();
            beginSegment(intent.getIntExtra("duration", -1));
        } catch (Exception e) {
            status("无法启动播放采集。可改用导入音频。");
            signal("error");
            cancelAuthorization();
        }
        return START_NOT_STICKY;
    }

    private void beginSegment(int seconds) {
        try {
            AudioFormat format = new AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(RATE).setChannelMask(AudioFormat.CHANNEL_IN_MONO).build();
            int buffer = Math.max(AudioRecord.getMinBufferSize(RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT) * 2, 16384);
            AudioRecord recorder = new AudioRecord.Builder().setAudioFormat(format)
                    .setBufferSizeInBytes(buffer).setAudioPlaybackCaptureConfig(audioConfig).build();
            shouldStop = false;
            aborted = false;
            duration = seconds;
            running = true;
            if (automatic) {
                status("正在等待 U校园播放。也可以手动点播放或从浮窗结束采集。");
            } else status("已授权。请在 U校园播放听力，结束后从浮窗停止并转写。");
            new Thread(() -> record(recorder), "campus-audio").start();
        } catch (Exception e) {
            status("无法启动播放采集。可改用导入音频。");
            signal("error");
            cancelAuthorization();
        }
    }

    private void record(AudioRecord recorder) {
        File temp = null;
        String signal = "error";
        try {
            temp = File.createTempFile("capture", ".wav", getCacheDir());
            int written = 0, peak = 0, voiced = 0, lastSound = 0;
            recorder.startRecording();
            signal("play");
            try (RandomAccessFile wav = new RandomAccessFile(temp, "rw")) {
                wav.setLength(44);
                wav.seek(44);
                byte[] bytes = new byte[8192];
                int limit = course ? 245 : LIMIT_SECONDS;
                while (!shouldStop && written < limit * RATE * 2) {
                    int count = recorder.read(bytes, 0, bytes.length);
                    if (count < 0) break;
                    int blockPeak = 0;
                    for (int i = 0; i + 1 < count; i += 2) {
                        int sample = (short) (((bytes[i + 1] & 255) << 8) | (bytes[i] & 255));
                        blockPeak = Math.max(blockPeak, Math.abs(sample));
                    }
                    wav.write(bytes, 0, count);
                    written += count;
                    peak = Math.max(peak, blockPeak);
                    if (blockPeak >= 64) {
                        lastSound = written;
                        voiced += count;
                    }
                    if (automatic && (((!course || duration <= 0) && voiced >= RATE * 4 && written - lastSound > RATE * 14)
                            || (lastSound == 0 && written > RATE * 40))) break;
                }
                header(wav, written);
            } finally {
                if (recorder.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) recorder.stop();
                recorder.release();
            }
            finishing = true;
            running = false;
            if (!course) main.post(this::stopProjection);
            if (aborted) return;
            if (written < RATE || peak < 24) throw new IOException("没有采集到 U校园声音。请确认已播放，或改用导入音频。");
            status("正在转写 U校园听力…");
            String text = ModelClient.transcribe(store.config(), temp, "wav", "audio/wav");
            if (aborted) return;
            store.transcript(text);
            store.prefs.edit().putBoolean("attach_once", !course).apply();
            store.event("听力", "已转写 U校园播放声音并备好下一题原文");
            status("听力已转写，下次在浮窗处理题目时会自动附上原文。");
            signal = "ready";
        } catch (Exception e) {
            status(ModelClient.message(e));
        } finally {
            if (temp != null) temp.delete();
            final String result = signal;
            main.post(() -> {
                running = false;
                finishing = false;
                if (!aborted) signal(result);
                if (!course || aborted || !"ready".equals(result)) {
                    stopProjection();
                    stopSelf();
                }
            });
        }
    }

    private static void header(RandomAccessFile file, int size) throws IOException {
        file.seek(0);
        file.writeBytes("RIFF");
        file.writeInt(Integer.reverseBytes(size + 36));
        file.writeBytes("WAVEfmt ");
        file.writeInt(Integer.reverseBytes(16));
        file.writeShort(Short.reverseBytes((short) 1));
        file.writeShort(Short.reverseBytes((short) 1));
        file.writeInt(Integer.reverseBytes(RATE));
        file.writeInt(Integer.reverseBytes(RATE * 2));
        file.writeShort(Short.reverseBytes((short) 2));
        file.writeShort(Short.reverseBytes((short) 16));
        file.writeBytes("data");
        file.writeInt(Integer.reverseBytes(size));
    }

    private void status(String message) {
        store.prefs.edit().putString("capture_status", message).apply();
    }

    private void signal(String event) {
        if (automatic) store.prefs.edit().putString("capture_signal", event).apply();
    }

    private void stopProjection() {
        if (projection != null) {
            MediaProjection old = projection;
            projection = null;
            old.stop();
        }
    }

    private void cancelAuthorization() {
        aborted = true;
        shouldStop = true;
        stopProjection();
        if (!running && !finishing) stopSelf();
    }

    @Override public void onDestroy() {
        shouldStop = true;
        aborted = true;
        stopProjection();
        if (instance == this) instance = null;
        super.onDestroy();
    }
}
