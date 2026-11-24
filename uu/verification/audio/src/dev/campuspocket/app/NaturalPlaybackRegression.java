package dev.campuspocket.app;

import android.content.Context;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.SystemClock;
import android.speech.tts.TextToSpeech;

public final class NaturalPlaybackRegression {
    public static void main(String[] args) throws Exception {
        Events events = new Events();
        TrainingAudio audio = new TrainingAudio(new Context(), new Handler(), 1f, new Store.Voice(), events);
        audio.prepare("Let's describe the key points.", true);
        TextToSpeech.instance.initialize();
        TextToSpeech.instance.finishOne();
        check(events.duration == 52000, "Audio not prepared");
        var playerField = TrainingAudio.class.getDeclaredField("player");
        playerField.setAccessible(true);
        var probe = TrainingAudio.class.getDeclaredMethod("checkPlayback");
        probe.setAccessible(true);

        var player = (MediaPlayer) playerField.get(audio);
        audio.play(false);
        for (int i = 0; i < 10; i++) {
            SystemClock.clock += 5000;
            player.position += 4000;
            probe.invoke(audio);
        }
        check(player.playing && events.completed == 0 && events.errors == 0,
                "An advancing playback must never be cut off by the watchdog");
        player.position = 51000;
        probe.invoke(audio);
        player.position = 0;
        player.complete();
        player.complete();
        check(events.completed == 1 && events.errors == 0, "Actual end did not complete once");

        audio.prepare("Let's describe the key points.", true);
        check(TextToSpeech.instance.synthesis.size() == 1, "Identical audio should be reusable");
        audio.play(false);
        player.fail();
        check(events.completed == 1 && events.errors == 1, "Decode error counted as completion");

        Events stalled = new Events();
        TrainingAudio frozen = new TrainingAudio(new Context(), new Handler(), 1f, new Store.Voice(), stalled);
        frozen.prepare("Please practice this other line.", true);
        TextToSpeech.instance.initialize();
        TextToSpeech.instance.finishOne();
        frozen.play(true);
        var frozenPlayer = (MediaPlayer) playerField.get(frozen);
        SystemClock.clock += 3400;
        probe.invoke(frozen);
        check(stalled.recoveries == 1 && stalled.errors == 0 && frozenPlayer.playing,
                "Player at 0ms should be restarted once, not left silent indefinitely");
        SystemClock.clock += 3500;
        probe.invoke(frozen);
        check(stalled.errors == 1 && stalled.completed == 0,
                "Second stalled startup should report an error rather than hanging forever");
        System.out.println("PASS: advancing audio not truncated, natural completion once, audio reuse, decoder error, one silent-start restart, bounded failure");
    }

    private static final class Events implements TrainingAudio.Listener {
        int duration, completed, errors, recoveries;
        public void ready(int duration) { this.duration = duration; }
        public void playbackRecovery(String detail) { recoveries++; }
        public void done() { completed++; }
        public void error(String message) { errors++; }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
