package dev.campuspocket.app;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.media.MediaMetadataRetriever;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputFilter;
import android.text.InputType;
import android.view.View;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.SocketTimeoutException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class VoiceActivity extends Activity {
    static final String PRACTICE_TEXT = "practice_text";
    private static final int MIC = 61, PICK_SAMPLE = 62, SAVE_WAV = 63;
    private static final String SCRIPT = "On Saturday morning, I went for a walk by the river. The air was cool, and I could hear birds in the trees.";
    private static final String PREVIEW = "A good conversation starts with listening. Take your time, speak clearly, and enjoy telling your story.";
    private static final int TEXT_LIMIT = 2000;

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private Store store;
    private Store.Voice profile;
    private EditText key, transcript, words;
    private CheckBox consent;
    private TextView status, recordButton, sampleInfo, samplePlay, pickButton, createButton, deleteButton;
    private TextView renderButton, replayButton, saveButton;
    private String previewText;
    private File sample, generated;
    private VoiceSampleRecorder recorder;
    private MediaPlayer player;
    private boolean busy, foreground, discardRecording;

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        store = new Store(this);
        profile = store.voice();
        String passed = getIntent().getStringExtra(PRACTICE_TEXT);
        previewText = passed == null || passed.trim().isEmpty() ? PREVIEW : passed.trim();
        show("");
    }

    private void show(String message) {
        LinearLayout root = Ui.column(this);
        root.setBackgroundColor(Ui.BG);
        ScrollView scroll = new ScrollView(this);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout content = Ui.column(this);
        Ui.pad(content, 20, 17, 28);
        content.setFocusableInTouchMode(true);
        scroll.addView(content);
        setContentView(root);

        TextView back = Ui.button(this, "返回口语跟读", false, this::onBackPressed);
        content.addView(back, new LinearLayout.LayoutParams(-2, -2));
        Ui.gap(content, 22);
        content.addView(Ui.text(this, "我的音色", 29, Ui.INK, true));
        Ui.gap(content, 8);
        content.addView(Ui.text(this, "录一次自己的声音，之后输入新文本就能用同一个音色试听。", 14, Ui.SUB, false));
        Ui.gap(content, 20);
        status = Ui.text(this, message, 14, Ui.GREEN, true);
        Ui.pad(status, 20, 11, 12);
        status.setBackgroundColor(Ui.MINT);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        status.setVisibility(message.isEmpty() ? View.GONE : View.VISIBLE);
        root.addView(status);
        if (profile.id.isEmpty()) showEnrollment(content); else showPreview(content);
        refreshActions();
        content.requestFocus();
    }

    private void showEnrollment(LinearLayout content) {
        LinearLayout sampleCard = Ui.card(this);
        sampleCard.addView(Ui.text(this, "参考样音", 18, Ui.INK, true));
        Ui.gap(sampleCard, 8);
        sampleCard.addView(Ui.text(this, "在安静的地方用平时的语气读下面这段话，建议 8–10 秒。也可导入自己的 WAV 或 MP3。", 13, Ui.SUB, false));
        Ui.gap(sampleCard, 10);
        transcript = field("填入录音里实际说的话", SCRIPT, true, 600);
        sampleCard.addView(transcript);
        Ui.gap(sampleCard, 10);
        LinearLayout controls = Ui.row(this);
        recordButton = Ui.button(this, "录样音", true, this::toggleRecording);
        pickButton = Ui.button(this, "导入样音", false, this::pick);
        Ui.stretch(controls, recordButton);
        Ui.spaceX(controls, 8);
        Ui.stretch(controls, pickButton);
        sampleCard.addView(controls);
        Ui.gap(sampleCard, 8);
        samplePlay = Ui.button(this, "试听样音", false, () -> play(sample));
        sampleCard.addView(samplePlay);
        Ui.gap(sampleCard, 9);
        sampleInfo = Ui.text(this, "尚未添加样音。", 13, Ui.SUB, false);
        sampleInfo.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        sampleCard.addView(sampleInfo);
        content.addView(sampleCard);
        Ui.gap(content, 14);

        LinearLayout service = Ui.card(this);
        service.addView(Ui.text(this, "创建个人音色", 18, Ui.INK, true));
        Ui.gap(service, 8);
        service.addView(Ui.text(this, "使用硅基流动 CosyVoice2，需要已实名认证账户的 API Key。", 13, Ui.SUB, false));
        Ui.gap(service, 10);
        Store.Config config = store.config();
        String reuse = config.speechBase().replaceAll("/+$", "").equals(VoiceClient.BASE) ? config.speechKey() : "";
        if (reuse.isEmpty() && config.base.replaceAll("/+$", "").equals(VoiceClient.BASE)) reuse = config.key;
        key = field("硅基流动 API Key", reuse, false, 512);
        key.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        service.addView(key);
        Ui.gap(service, 8);
        consent = new CheckBox(this);
        consent.setText("这是我自己的声音，我同意把样音和对应文字发送给硅基流动创建音色。");
        consent.setTextSize(13);
        consent.setTextColor(Ui.INK);
        consent.setButtonTintList(ColorStateList.valueOf(Ui.ACCENT));
        consent.setOnCheckedChangeListener((button, checked) -> refreshActions());
        service.addView(consent);
        Ui.gap(service, 8);
        createButton = Ui.button(this, "创建音色", true, this::enroll);
        service.addView(createButton);
        content.addView(service);
    }

    private void showPreview(LinearLayout content) {
        LinearLayout card = Ui.card(this);
        card.addView(Ui.text(this, "个人音色已保存", 18, Ui.INK, true));
        Ui.gap(card, 8);
        card.addView(Ui.text(this, "可直接试听口语跟读带来的文本，也可以换成任意新句子。生成时会把文本发送给硅基流动。", 13, Ui.SUB, false));
        Ui.gap(card, 11);
        words = Ui.field(this, "输入要朗读的英文", previewText, true);
        card.addView(words);
        Ui.gap(card, 10);
        renderButton = Ui.button(this, "生成并试听", true, this::generate);
        card.addView(renderButton);
        Ui.gap(card, 8);
        LinearLayout controls = Ui.row(this);
        replayButton = Ui.button(this, "重播上次", false, () -> play(generated));
        saveButton = Ui.button(this, "保存上次 WAV", false, this::export);
        Ui.stretch(controls, replayButton);
        Ui.spaceX(controls, 8);
        Ui.stretch(controls, saveButton);
        card.addView(controls);
        Ui.gap(card, 12);
        deleteButton = Ui.button(this, "删除个人音色", false, this::confirmDelete);
        card.addView(deleteButton);
        content.addView(card);
    }

    private EditText field(String hint, String value, boolean multiline, int limit) {
        EditText input = Ui.field(this, hint, "", multiline);
        input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(limit)});
        input.setText(value);
        return input;
    }

    private void refreshActions() {
        boolean idle = !busy && recorder == null;
        if (profile.id.isEmpty()) {
            setEnabled(recordButton, !busy);
            setEnabled(pickButton, idle);
            setEnabled(samplePlay, idle && sample != null);
            setEnabled(createButton, idle && sample != null && consent.isChecked());
        } else {
            setEnabled(renderButton, idle);
            setEnabled(deleteButton, idle);
            setEnabled(replayButton, idle && generated != null);
            setEnabled(saveButton, idle && generated != null);
        }
    }

    private void setEnabled(View view, boolean enabled) {
        view.setEnabled(enabled);
        view.setAlpha(enabled ? 1 : .48f);
    }

    private void message(String value) {
        status.setText(value);
        status.setVisibility(View.VISIBLE);
    }

    private void toggleRecording() {
        if (recorder != null) {
            recorder.stop();
            recordButton.setText("正在结束");
            setEnabled(recordButton, false);
            return;
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, MIC);
            return;
        }
        startRecording();
    }

    private void startRecording() {
        if (busy || recorder != null) return;
        stopPlayback();
        File target;
        try {
            target = File.createTempFile("voice-sample-", ".wav", getCacheDir());
        } catch (IOException e) {
            message("无法创建录音文件，请检查剩余存储空间。");
            return;
        }
        VoiceSampleRecorder current = new VoiceSampleRecorder();
        recorder = current;
        discardRecording = false;
        recordButton.setText("结束录音");
        sampleInfo.setText("录音中。读完请点结束，最长 20 秒。");
        refreshActions();
        worker.execute(() -> {
            long duration = 0;
            String error = "";
            try {
                duration = current.record(target);
            } catch (IOException e) {
                error = e.getMessage();
            } catch (RuntimeException e) {
                error = "无法使用麦克风。请检查权限或关闭正在录音的应用。";
            }
            final long length = duration;
            final String failure = error;
            runOnUiThread(() -> {
                recorder = null;
                if (isDestroyed()) { target.delete(); return; }
                recordButton.setText("录样音");
                if (discardRecording || !failure.isEmpty()) {
                    target.delete();
                    sampleInfo.setText(discardRecording ? "已结束离开页面时的录音。" : failure);
                } else {
                    useSample(target, length, false);
                }
                refreshActions();
            });
        });
    }

    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] grants) {
        super.onRequestPermissionsResult(request, permissions, grants);
        if (request != MIC) return;
        if (grants.length > 0 && grants[0] == PackageManager.PERMISSION_GRANTED) startRecording();
        else message("需要麦克风权限才能录样音，也可以导入现有音频。");
    }

    private void pick() {
        Intent picker = new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("audio/*")
                .addCategory(Intent.CATEGORY_OPENABLE);
        startActivityForResult(picker, PICK_SAMPLE);
    }

    private void export() {
        Intent picker = new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("audio/wav")
                .addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE, "我的音色-朗读.wav");
        startActivityForResult(picker, SAVE_WAV);
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (result != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        if (request == PICK_SAMPLE) importSample(uri);
        if (request == SAVE_WAV && generated != null) saveAudio(uri);
    }

    private void importSample(Uri uri) {
        runJob("正在读取样音…", () -> {
            File staging = File.createTempFile("voice-import-", ".audio", getCacheDir());
            File ready = null;
            boolean accepted = false;
            try {
                try (InputStream in = getContentResolver().openInputStream(uri);
                     OutputStream out = new FileOutputStream(staging)) {
                    if (in == null) throw new IOException("无法读取此音频文件。");
                    VoiceClient.copy(in, out, VoiceClient.SAMPLE_LIMIT);
                }
                String mime;
                long duration;
                MediaMetadataRetriever metadata = new MediaMetadataRetriever();
                try {
                    metadata.setDataSource(staging.getPath());
                    mime = metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE);
                    String value = metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
                    duration = value == null ? 0 : Long.parseLong(value);
                } catch (RuntimeException e) {
                    throw new IOException("无法读取此文件。请选择有效的 WAV 或 MP3。");
                } finally {
                    metadata.release();
                }
                String suffix;
                if ("audio/mpeg".equals(mime)) suffix = ".mp3";
                else if ("audio/x-wav".equals(mime) || "audio/wav".equals(mime)) suffix = ".wav";
                else throw new IOException("请导入 WAV 或 MP3 格式的样音。");
                if (duration < 3000 || duration > 30000) throw new IOException("请选择 3–30 秒的样音，建议 8–10 秒。");
                ready = new File(staging.getPath() + suffix);
                if (!staging.renameTo(ready)) throw new IOException("手机未能保存样音。");
                accepted = true;
                File file = ready;
                complete(() -> {
                    if (isDestroyed()) { file.delete(); return; }
                    useSample(file, duration, true);
                    message("样音已导入。请填入录音里实际说的话。");
                });
            } finally {
                staging.delete();
                if (!accepted && ready != null) ready.delete();
            }
        });
    }

    private void useSample(File file, long duration, boolean imported) {
        stopPlayback();
        remove(sample);
        sample = file;
        if (imported && text(transcript).equals(SCRIPT)) transcript.setText("");
        consent.setChecked(false);
        sampleInfo.setText(String.format(java.util.Locale.ROOT, "样音已就绪 · %.1f 秒", duration / 1000.0));
    }

    private void enroll() {
        String apiKey = text(key);
        String reference = text(transcript);
        if (sample == null || !consent.isChecked()) return;
        if (apiKey.isEmpty()) { message("请填写硅基流动 API Key。"); return; }
        if (reference.isEmpty()) { message("请填入样音里实际说的话。"); return; }
        File audio = sample;
        runJob("正在上传样音并创建个人音色…", () -> {
            String id = VoiceClient.enroll(apiKey, audio, reference);
            store.saveVoice(id, apiKey);
            complete(() -> {
                remove(audio);
                sample = null;
                if (isDestroyed()) return;
                profile = store.voice();
                show("音色已保存，可以用新句子试听。");
            });
        });
    }

    private void generate() {
        String input = text(words);
        if (input.isEmpty()) { message("请输入要朗读的文字。"); return; }
        if (input.length() > TEXT_LIMIT) { message("每次可生成最多 2,000 个字符，请分段试听。"); return; }
        Store.Voice voice = profile;
        if (voice.key.isEmpty()) { message("这台手机无法读取音色绑定的 API Key。"); return; }
        runJob("正在用你的音色生成语音…", () -> {
            File file = File.createTempFile("voice-preview-", ".wav", getCacheDir());
            try {
                VoiceClient.render(voice, input, file);
            } catch (Exception e) {
                file.delete();
                throw e;
            }
            complete(() -> {
                if (isDestroyed()) { file.delete(); return; }
                remove(generated);
                generated = file;
                message("已生成，可以重播或保存 WAV。");
                if (foreground) play(file);
            });
        });
    }

    private void saveAudio(Uri uri) {
        File file = generated;
        runJob("正在保存音频…", () -> {
            try (InputStream in = new FileInputStream(file); OutputStream out = getContentResolver().openOutputStream(uri, "w")) {
                if (out == null) throw new IOException("无法写入所选位置。");
                VoiceClient.copy(in, out, VoiceClient.WAV_LIMIT);
            }
            complete(() -> message("WAV 已保存到所选位置。"));
        });
    }

    private void confirmDelete() {
        if (profile.key.isEmpty()) { message("这台手机无法读取音色绑定的 API Key。"); return; }
        new AlertDialog.Builder(this).setTitle("删除个人音色")
                .setMessage("将从硅基流动和本机删除这个音色。重新使用时需要创建新的音色。")
                .setNegativeButton("取消", null).setPositiveButton("删除", (dialog, which) -> {
                    Store.Voice voice = profile;
                    previewText = text(words);
                    runJob("正在删除个人音色…", () -> {
                        VoiceClient.delete(voice);
                        store.clearVoice();
                        complete(() -> {
                            remove(generated);
                            generated = null;
                            if (isDestroyed()) return;
                            profile = store.voice();
                            show("已删除个人音色。");
                        });
                    });
                }).show();
    }

    private interface Job { void run() throws Exception; }

    private void runJob(String label, Job job) {
        if (busy) return;
        stopPlayback();
        busy = true;
        message(label);
        refreshActions();
        worker.execute(() -> {
            try {
                job.run();
            } catch (Exception e) {
                String detail = e instanceof SocketTimeoutException ? "语音服务响应超时，请稍后重试。"
                        : e instanceof IOException && e.getMessage() != null && e.getMessage().contains("。")
                        ? e.getMessage() : "这次操作未完成，请检查网络、API Key 或存储空间后重试。";
                complete(() -> { if (!isDestroyed()) message(detail); });
            }
        });
    }

    private void complete(Runnable result) {
        runOnUiThread(() -> {
            busy = false;
            result.run();
            if (isDestroyed()) {
                remove(sample);
                remove(generated);
            } else {
                refreshActions();
            }
        });
    }

    private void play(File file) {
        if (file == null) return;
        stopPlayback();
        MediaPlayer next = new MediaPlayer();
        player = next;
        try (FileInputStream in = new FileInputStream(file)) {
            next.setDataSource(in.getFD());
            next.setOnCompletionListener(p -> { if (player == p) stopPlayback(); });
            next.setOnErrorListener((p, what, extra) -> {
                if (player == p) {
                    stopPlayback();
                    message("当前音频无法播放。");
                }
                return true;
            });
            next.setOnPreparedListener(p -> { if (player == p && foreground) p.start(); });
            next.prepareAsync();
        } catch (IOException | RuntimeException e) {
            stopPlayback();
            message("当前音频无法播放。");
        }
    }

    private void stopPlayback() {
        if (player == null) return;
        player.release();
        player = null;
    }

    private static String text(EditText field) { return field.getText().toString().trim(); }
    private static void remove(File file) { if (file != null) file.delete(); }

    @Override protected void onStart() {
        super.onStart();
        foreground = true;
    }

    @Override protected void onStop() {
        foreground = false;
        if (recorder != null) {
            discardRecording = true;
            recorder.stop();
        }
        stopPlayback();
        super.onStop();
    }

    @Override public void onBackPressed() {
        if (busy) {
            message("请等当前操作结束。");
            return;
        }
        super.onBackPressed();
    }

    @Override protected void onDestroy() {
        worker.shutdown();
        if (!busy) {
            remove(sample);
            remove(generated);
        }
        super.onDestroy();
    }
}
