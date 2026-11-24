package dev.campuspocket.app;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.SystemClock;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;

import java.io.File;
import java.io.IOException;
import java.util.Locale;

final class TrainingAudio {
    interface Listener {
        void ready(int durationMs);
        default void playbackStarted(String device) {}
        default void playbackRecovery(String detail) {}
        void done();
        void error(String message);
    }

    private final Context context;
    private final Handler ui;
    private final float speed;
    private final Store.Voice voice;
    private final Listener listener;
    private File file;
    private TextToSpeech tts;
    private Boolean englishReady;
    private int ttsSession;
    private MediaPlayer player;
    private int generation;
    private int preparedDuration;
    private float appliedRate = 1f;
    private String preparedText = "";
    private boolean playing, useSystemVoice, startReported, restartAttempted;
    private long lastPlaybackProgressAt;
    private int lastPlaybackPosition;
    private final Runnable timeout = () -> fail(voiceDescription() + "准备超时。");
    private final Runnable pollPlayback = this::checkPlayback;

    TrainingAudio(Context context, Handler ui, float speed, Store.Voice voice, Listener listener) {
        this.context = context;
        this.ui = ui;
        this.speed = speed;
        this.voice = voice;
        this.listener = listener;
    }

    boolean usingPersonalVoice() { return !useSystemVoice && !voice.id.isEmpty(); }

    void switchToSystemVoice() {
        stop();
        useSystemVoice = true;
    }

    String voiceDescription() { return usingPersonalVoice() ? "个人音色" : "系统英语语音"; }

    void warmUp() {
        if (usingPersonalVoice() || tts != null || englishReady != null) return;
        int session = ++ttsSession;
        try {
            tts = new TextToSpeech(context, result -> ui.post(() -> {
                if (session != ttsSession || tts == null) return;
                try {
                    englishReady = result == TextToSpeech.SUCCESS && tts.setLanguage(Locale.US) >= 0;
                } catch (RuntimeException unavailable) {
                    englishReady = false;
                }
                if (file != null) synthesize(generation, preparedText);
            }));
        } catch (RuntimeException e) {
            englishReady = false;
        }
    }

    void prepare(String text, boolean interactive) {
        if (player != null && preparedDuration > 0 && !playing && text.equals(preparedText)) {
            try {
                int ticket = generation;
                ui.postDelayed(timeout, 3500);
                player.setOnSeekCompleteListener(p -> {
                    if (ticket != generation) return;
                    ui.removeCallbacks(timeout);
                    p.setOnSeekCompleteListener(null);
                    listener.ready(preparedDuration);
                });
                player.seekTo(0);
                return;
            } catch (RuntimeException ignored) {
                // A player that cannot be rewound is rebuilt.
            }
        }
        resetPlayback();
        preparedText = text;
        int ticket = generation;
        ui.postDelayed(timeout, usingPersonalVoice() ? (interactive ? 22000 : 90000) : (interactive ? 10000 : 18000));
        try {
            file = File.createTempFile("training-voice-", ".wav", context.getCacheDir());
            if (usingPersonalVoice()) {
                File output = file;
                ModelClient.WORK.execute(() -> {
                    try {
                        VoiceClient.render(voice, text, output, interactive);
                        verifyAudio(output, ticket);
                    } catch (Exception error) {
                        output.delete();
                        ui.post(() -> { if (ticket == generation) fail("个人音色生成失败：" + ModelClient.message(error)); });
                    }
                });
                return;
            }
            warmUp();
            if (englishReady != null) synthesize(ticket, text);
        } catch (Exception error) {
            fail("无法准备朗读音频。");
        }
    }

    private void synthesize(int ticket, String text) {
        if (ticket != generation) return;
        if (!Boolean.TRUE.equals(englishReady)) {
            fail("请在 Android 文字转语音设置中安装英语语音。");
            return;
        }
        tts.setSpeechRate(speed);
        String utterance = "training-" + ticket;
        tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override public void onStart(String id) { }
            @Override public void onDone(String id) {
                if (utterance.equals(id)) ui.post(() -> {
                    if (ticket == generation && file != null) {
                        File output = file;
                        ModelClient.WORK.execute(() -> verifyAudio(output, ticket));
                    }
                });
            }
            @Override public void onError(String id) {
                if (utterance.equals(id)) ui.post(() -> { if (ticket == generation) fail("系统未能生成英语语音。"); });
            }
        });
        if (tts.synthesizeToFile(text, new Bundle(), file, utterance) != TextToSpeech.SUCCESS) {
            fail("系统未能生成英语语音。");
        }
    }

    private void verifyAudio(File output, int ticket) {
        try {
            boolean silent = TrainingWave.silent16Bit(output);
            ui.post(() -> {
                if (ticket != generation) {
                    output.delete();
                } else if (silent) fail(voiceDescription() + "生成的音频是静音。");
                else preparePlayer(ticket);
            });
        } catch (IOException error) {
            ui.post(() -> { if (ticket == generation) fail("无法读取生成的语音文件。"); });
        }
    }

    private void preparePlayer(int ticket) {
        if (ticket != generation) return;
        try {
            player = new MediaPlayer();
            player.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build());
            player.setDataSource(file.getAbsolutePath());
            player.setOnPreparedListener(p -> {
                if (ticket != generation) return;
                ui.removeCallbacks(timeout);
                int duration = Math.round(p.getDuration() / (usingPersonalVoice() ? speed : 1f));
                if (duration > 0) {
                    preparedDuration = duration;
                    listener.ready(duration);
                }
                else fail("语音文件没有可用的时长。");
            });
            player.setOnCompletionListener(p -> {
                if (ticket == generation && playing) completePlayback();
            });
            player.setOnErrorListener((p, what, extra) -> {
                if (ticket == generation) fail("语音播放失败。");
                return true;
            });
            player.prepareAsync();
        } catch (IOException | RuntimeException e) {
            fail("无法打开生成的语音文件。");
        }
    }

    void play(boolean preferSpeaker) {
        try {
            AudioManager manager = context.getSystemService(AudioManager.class);
            if (manager != null && manager.getStreamVolume(AudioManager.STREAM_MUSIC) == 0) {
                fail("媒体音量为 0。");
                return;
            }
            if (preferSpeaker && manager != null && Build.VERSION.SDK_INT >= 28) {
                for (AudioDeviceInfo device : manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
                    if (device.getType() == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) {
                        try { player.setPreferredDevice(device); }
                        catch (RuntimeException unavailable) { /* Keep Android's default route. */ }
                        break;
                    }
                }
            }
            if (player == null || preparedDuration <= 0) {
                fail("音频没有准备完成，无法开始播放。");
                return;
            }
            startReported = restartAttempted = false;
            lastPlaybackPosition = 0;
            lastPlaybackProgressAt = SystemClock.uptimeMillis();
            playing = true;
            player.start();
            float rate = usingPersonalVoice() ? speed : 1f;
            if (rate != appliedRate) {
                player.setPlaybackParams(player.getPlaybackParams().setSpeed(rate));
                appliedRate = rate;
            }
            if (playing) ui.post(pollPlayback);
        } catch (RuntimeException error) {
            fail("手机未能播放英语语音。");
        }
    }

    private void checkPlayback() {
        if (!playing || player == null) return;
        long now = SystemClock.uptimeMillis();
        try {
            int position = player.getCurrentPosition();
            if (position > lastPlaybackPosition + 20) {
                lastPlaybackPosition = position;
                lastPlaybackProgressAt = now;
                if (!startReported) {
                    startReported = true;
                    listener.playbackStarted(outputDevice());
                }
            }
            // The watchdog detects an actually motionless player; it NEVER uses
            // the recording countdown or predicted total audio duration.
            long budget = startReported ? 9000 : 3200;
            if (now - lastPlaybackProgressAt >= budget) {
                if (restartAttempted) {
                    fail("音频播放没有进度：已尝试恢复输出，但仍然无声或播放器停滞。");
                    return;
                }
                restartAttempted = true;
                listener.playbackRecovery("播放器连续" + budget / 1000 + "秒无进度，正在重启本地输出 · " + outputDevice());
                player.pause();
                player.seekTo(Math.max(0, lastPlaybackPosition - 100));
                player.start();
                lastPlaybackProgressAt = now;
            }
        } catch (RuntimeException error) {
            fail("播放器无法读取音频输出状态，已停止当前次播放。");
            return;
        }
        if (playing) ui.postDelayed(pollPlayback, 240);
    }

    private String outputDevice() {
        AudioDeviceInfo device = Build.VERSION.SDK_INT >= 28 ? player.getRoutedDevice() : null;
        if (device == null) return "输出设备未确认";
        return device.getType() == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER ? "手机扬声器"
                : "其他音频设备（类型 " + device.getType() + "）";
    }

    private void completePlayback() {
        if (!playing) return;
        playing = false;
        ui.removeCallbacks(pollPlayback);
        listener.done();
    }

    private void fail(String text) {
        stop();
        listener.error(text);
    }

    void stop() {
        resetPlayback();
        ttsSession++;
        englishReady = null;
        if (tts != null) {
            tts.shutdown();
            tts = null;
        }
    }

    private void resetPlayback() {
        generation++;
        playing = false;
        preparedDuration = 0;
        appliedRate = 1f;
        preparedText = "";
        startReported = restartAttempted = false;
        lastPlaybackProgressAt = 0;
        lastPlaybackPosition = 0;
        ui.removeCallbacks(timeout);
        ui.removeCallbacks(pollPlayback);
        if (player != null) {
            player.release();
            player = null;
        }
        if (tts != null && Boolean.TRUE.equals(englishReady)) tts.stop();
        if (file != null) {
            file.delete();
            file = null;
        }
    }
}
