package android.speech.tts;
import android.content.Context;
import android.os.Bundle;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class TextToSpeech {
    public static final int SUCCESS = 0;
    public static TextToSpeech instance;
    public final List<String> synthesis = new ArrayList<>();
    public interface OnInitListener { void onInit(int status); }
    private final OnInitListener initialization;
    private UtteranceProgressListener events;
    private String pending;
    public TextToSpeech(Context context, OnInitListener listener) { initialization = listener; instance = this; }
    public void initialize() { initialization.onInit(SUCCESS); }
    public int setLanguage(Locale locale) { return SUCCESS; }
    public int setSpeechRate(float rate) { return SUCCESS; }
    public int setOnUtteranceProgressListener(UtteranceProgressListener listener) { events = listener; return SUCCESS; }
    public int synthesizeToFile(CharSequence text, Bundle opts, File file, String id) {
        if (pending != null) throw new AssertionError("Parallel local TTS work was started");
        pending = id;
        synthesis.add(text.toString());
        return SUCCESS;
    }
    public Runnable completion() {
        var listener = events;
        String id = pending;
        return () -> listener.onDone(id);
    }
    public void finishOne() {
        Runnable callback = completion();
        pending = null;
        callback.run();
    }
    public int stop() { pending = null; return SUCCESS; }
    public void shutdown() {}
}
