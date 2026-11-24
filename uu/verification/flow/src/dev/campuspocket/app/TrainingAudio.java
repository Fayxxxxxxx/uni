package dev.campuspocket.app;
import android.content.Context;
import android.os.Handler;
final class TrainingAudio {
    interface Listener {
        void ready(int durationMs);
        default void playbackStarted(String device) {}
        default void playbackRecovery(String detail) {}
        void done();
        void error(String message);
    }
    boolean warmed, prepared, played;
    int stops;
    Listener listener;
    boolean excerptAllowed, stoppedAtLimit;
    java.util.function.LongSupplier budget;
    void play(boolean preferSpeaker, java.util.function.LongSupplier timeLeft, boolean allowExcerpt) { budget = timeLeft; excerptAllowed = allowExcerpt; play(preferSpeaker); }
    void stopAtTimeLimit() { stoppedAtLimit = true; listener.done(); }
    String text = "";
    int completedParts() { return 0; }
    boolean usesParts() { return false; }
    int shortestPartDuration() { return 0; }
    void prepare(java.util.List<String> parts, boolean interactive) { prepare(String.join(" ", parts), interactive); }
    void play(boolean preferSpeaker, java.util.function.LongSupplier timeLeft) { play(preferSpeaker); }
    void setPlaybackFactor(float factor) {}
    boolean personal;
    boolean usingPersonalVoice() { return personal; }
    void switchToSystemVoice() { personal = false; }
    TrainingAudio(Context context, Handler ui, float speed, Store.Voice voice, Listener listener) { this.listener = listener; }
    void warmUp() { warmed = true; }
    void prepare(String speech, boolean dialogue) { prepared = true; text = speech; }
    String voiceDescription() { return "系统英语语音"; }
    void stop() { stops++; }
    void play(boolean preferSpeaker) { played = true; }
}
