package dev.campuspocket.app;

import android.Manifest;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.media.MediaPlayer;
import android.media.MediaRecorder;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.speech.tts.TextToSpeech;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.accessibility.AccessibilityManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public final class MainActivity extends Activity {
    private static final int AUDIO_FILE = 21, MIC = 22, CAPTURE_PERMISSION = 23, CAPTURE_CONSENT = 24;
    private static final String EXAMPLE = "The library has extended its opening hours during exam week.";
    private static final String SAMPLE_QUESTION = "Choose the correct answer.\nI have lived here ____ 2020.\nA. for\nB. since\nC. at\nD. during";
    private Store store;
    private String tab = "home", audioTab = "listen", filter = "all";
    private String english = EXAMPLE, practiceDraft = "", practiceAnswer = "", speechFeedback = "";
    private String mediaMessage = "";
    private LinearLayout shell, content;
    private TextView voiceStatus, practiceStatus, recordButton, captureStatus, campusLaunchStatus;
    private String launchMessage = "";
    private long launchAttempt;
    private EditText englishField;
    private boolean transcriptBusy, answerBusy;
    private TextToSpeech tts;
    private boolean ttsReady;
    private float speed = 0.9f;
    private boolean automaticCapture;
    private boolean courseCapture;
    private int captureDuration = -1;
    private String capturePackage;
    private MediaRecorder recorder;
    private MediaPlayer player;
    private File recording;
    private final SharedPreferences.OnSharedPreferenceChangeListener listenPrefs = (prefs, key) -> {
        if ("capture_status".equals(key) && captureStatus != null) {
            runOnUiThread(() -> {
                if (captureStatus != null) captureStatus.setText(prefs.getString("capture_status", ""));
            });
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        store = new Store(this);
        store.prefs.registerOnSharedPreferenceChangeListener(listenPrefs);
        if (state != null) {
            tab = state.getString("tab", "home");
            audioTab = state.getString("audio_tab", "listen");
            english = state.getString("english", EXAMPLE);
            practiceDraft = state.getString("draft", "");
            capturePackage = state.getString(CampusApp.EXTRA_PACKAGE);
            automaticCapture = state.getBoolean("capture_automatic");
            courseCapture = state.getBoolean("course_audio");
            captureDuration = state.getInt("duration", -1);
        }
        selectIntent(getIntent());
        show();
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        selectIntent(intent);
        show();
    }

    private void selectIntent(Intent intent) {
        String next = intent.getStringExtra("tab");
        if ("media".equals(next) || "settings".equals(next)) tab = next;
        intent.removeExtra("tab");
        if (intent.getBooleanExtra("capture_auto", false)) {
            intent.removeExtra("capture_auto");
            courseCapture = intent.getBooleanExtra("course_audio", false);
            captureDuration = intent.getIntExtra("duration", -1);
            intent.removeExtra("course_audio");
            intent.removeExtra("duration");
            capturePackage = intent.getStringExtra(CampusApp.EXTRA_PACKAGE);
            intent.removeExtra(CampusApp.EXTRA_PACKAGE);
            tab = "media";
            audioTab = "listen";
            new android.os.Handler(getMainLooper()).post(() -> beginCapture(true));
        }
    }

    @Override protected void onResume() {
        super.onResume();
        if (store != null && ("home".equals(tab) || "tasks".equals(tab))) show();
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        rememberEnglish();
        state.putString("tab", tab);
        state.putString("audio_tab", audioTab);
        state.putString("english", english);
        state.putString("draft", practiceDraft);
        state.putString(CampusApp.EXTRA_PACKAGE, capturePackage);
        state.putBoolean("capture_automatic", automaticCapture);
        state.putBoolean("course_audio", courseCapture);
        state.putInt("duration", captureDuration);
        super.onSaveInstanceState(state);
    }

    @Override public void onBackPressed() {
        if (!"home".equals(tab)) gotoTab("home");
        else super.onBackPressed();
    }

    @Override protected void onStop() {
        if (launchAttempt != 0) {
            launchAttempt = 0;
            setLaunchMessage("");
        }
        if (recorder != null) endRecording();
        if (tts != null) tts.stop();
        stopPlayback();
        super.onStop();
    }

    @Override protected void onDestroy() {
        store.prefs.unregisterOnSharedPreferenceChangeListener(listenPrefs);
        if (recorder != null) endRecording();
        if (tts != null) tts.shutdown();
        stopPlayback();
        if (recording != null) recording.delete();
        super.onDestroy();
    }

    private void gotoTab(String next) {
        rememberEnglish();
        tab = next;
        show();
    }

    private void show() {
        englishField = null;
        voiceStatus = recordButton = practiceStatus = captureStatus = campusLaunchStatus = null;
        shell = Ui.column(this);
        shell.setBackgroundColor(Ui.BG);
        FrameLayout host = new FrameLayout(this);
        host.setBackgroundColor(Ui.BG);
        int width = Math.min(getResources().getDisplayMetrics().widthPixels, Ui.dp(this, 680));
        FrameLayout.LayoutParams center = new FrameLayout.LayoutParams(width, -1, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        host.addView(shell, center);
        setContentView(host);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        scroll.setVerticalScrollBarEnabled(false);
        content = Ui.column(this);
        content.setFocusableInTouchMode(true);
        Ui.pad(content, 22, 15, 28);
        scroll.addView(content);
        shell.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        switch (tab) {
            case "media": media(); break;
            case "tasks": tasks(); break;
            case "settings": settings(); break;
            case "practice": practice(); break;
            default: home();
        }
        navigation();
    }

    private void heading(String title, String description) {
        content.addView(Ui.text(this, title, 30, Ui.INK, true));
        if (!description.isEmpty()) {
            Ui.gap(content, 7);
            content.addView(Ui.text(this, description, 14, Ui.SUB, false));
        }
        Ui.gap(content, 20);
    }

    private void navigation() {
        Ui.rule(shell);
        LinearLayout nav = Ui.row(this);
        nav.setBackgroundColor(Ui.WHITE);
        Ui.pad(nav, 10, 7, 7);
        navItem(nav, "home", "工作台", "home");
        navItem(nav, "media", "听说", "headphones");
        navItem(nav, "tasks", "任务", "list");
        navItem(nav, "settings", "设置", "tune");
        shell.addView(nav);
    }

    private void navItem(LinearLayout nav, String id, String label, String icon) {
        boolean selected = tab.equals(id) || ("practice".equals(tab) && "home".equals(id));
        LinearLayout item = Ui.column(this);
        item.setGravity(Gravity.CENTER);
        item.setMinimumHeight(Ui.dp(this, 49));
        item.setFocusable(true);
        item.setBackground(Ui.ripple(this, Ui.WHITE, 12, 0));
        item.setContentDescription(label);
        item.setSelected(selected);
        item.setOnClickListener(v -> gotoTab(id));
        LinearLayout mark = Ui.iconBox(this, icon, selected ? Ui.ACCENT : Ui.SUB, selected ? Ui.PALE : Ui.WHITE, 30);
        item.addView(mark);
        Ui.gap(item, 3);
        TextView caption = Ui.text(this, label, 11, selected ? Ui.ACCENT : Ui.SUB, selected);
        caption.setGravity(Gravity.CENTER);
        item.addView(caption);
        Ui.stretch(nav, item);
    }

    private void home() {
        LinearLayout top = Ui.row(this);
        top.addView(Ui.iconBox(this, "book", Ui.ACCENT, Ui.PALE, 43));
        Ui.spaceX(top, 10);
        Ui.stretch(top, Ui.text(this, "校园随练", 20, Ui.INK, true));
        boolean connected = CampusService.connected();
        boolean enabled = connected || overlayEnabled();
        TextView status = Ui.text(this, connected ? "浮窗已连接" : enabled ? "浮窗连接中" : "浮窗未开启", 12,
                connected ? Ui.GREEN : Ui.AMBER, true);
        Ui.pad(status, 11, 7, 7);
        status.setBackground(Ui.shape(this, connected ? Ui.MINT : Ui.APRICOT, 14, 0));
        top.addView(status);
        content.addView(top);
        Ui.gap(content, 24);

        FrameLayout hero = new HeroGraphic();
        hero.setBackground(Ui.shape(this, Ui.DARK, 25, 0));
        hero.setClipToOutline(true);
        LinearLayout lead = Ui.column(this);
        Ui.pad(lead, 21, 20, 21);
        TextView label = Ui.text(this, "U校园 · 安卓端", 12, Ui.LILAC, true);
        label.setLetterSpacing(.08f);
        lead.addView(label);
        Ui.gap(lead, 10);
        lead.addView(Ui.text(this, "练习工作台", 31, Ui.WHITE, true));
        Ui.gap(lead, 8);
        lead.addView(Ui.text(this, "从听力原文到口语练习", 14, Ui.LILAC, false));
        Ui.gap(lead, 19);
        TextView enter = Ui.button(this, "打开 U校园  ↗", false, this::openCampus);
        enter.setTextColor(Ui.DARK);
        enter.setBackground(Ui.ripple(this, Ui.LILAC, 13, 0));
        lead.addView(enter, new LinearLayout.LayoutParams(-2, -2));
        hero.addView(lead, new FrameLayout.LayoutParams(-1, -2));
        content.addView(hero, new LinearLayout.LayoutParams(-1, -2));
        campusLaunchStatus = Ui.text(this, launchMessage, 13, Ui.SUB, false);
        Ui.pad(campusLaunchStatus, 2, 10, 0);
        campusLaunchStatus.setVisibility(launchMessage.isEmpty() ? View.GONE : View.VISIBLE);
        content.addView(campusLaunchStatus);

        Ui.gap(content, 22);
        content.addView(Ui.text(this, "听说练习", 19, Ui.INK, true));
        Ui.gap(content, 10);
        LinearLayout tools = Ui.row(this);
        Ui.stretch(tools, tile("headphones", "听力转写", "采集与导入", Ui.ACCENT, Ui.PALE, () -> {
            audioTab = "listen"; gotoTab("media");
        }));
        Ui.spaceX(tools, 10);
        Ui.stretch(tools, tile("mic", "口语跟读", "朗读与反馈", Ui.GREEN, Ui.MINT, () -> {
            audioTab = "speak"; gotoTab("media");
        }));
        content.addView(tools);
        Ui.gap(content, 18);

        LinearLayout card = Ui.card(this);
        infoRow(card, "浮窗服务", enabled ? "进入 U校园后，轻点「随练」展开，按住可拖动" : "在系统无障碍设置中启用「校园随练」",
                enabled ? "测试浮窗" : "去开启", () -> {
                    if (CampusService.connected()) {
                        CampusService.testOverlay();
                    } else {
                        if (overlayEnabled()) toast("浮窗服务尚未连接，可在无障碍设置关闭后重新开启。");
                        openAccessibility();
                    }
                });
        Ui.gap(card, 11);
        Ui.rule(card);
        Ui.gap(card, 11);
        infoRow(card, "AI 接口", store.config().ready() ? "文本模型已配置" : "填入接口与模型即可开始", "设置",
                () -> gotoTab("settings"));
        content.addView(card);
        Ui.gap(content, 12);
        content.addView(Ui.button(this, "手动输入题目", false, () -> gotoTab("practice")));
    }

    private LinearLayout tile(String icon, String title, String detail, int color, int tint, Runnable action) {
        LinearLayout card = Ui.card(this);
        card.setBackground(Ui.ripple(this, Ui.WHITE, 20, Ui.BORDER));
        card.setOnClickListener(v -> action.run());
        card.setFocusable(true);
        card.addView(Ui.iconBox(this, icon, color, tint, 37));
        Ui.gap(card, 11);
        card.addView(Ui.text(this, title, 17, Ui.INK, true));
        Ui.gap(card, 5);
        card.addView(Ui.text(this, detail, 12, Ui.SUB, false));
        return card;
    }

    private void infoRow(LinearLayout card, String label, String body, String action, Runnable callback) {
        LinearLayout row = Ui.row(this);
        LinearLayout words = Ui.column(this);
        words.addView(Ui.text(this, label, 15, Ui.INK, true));
        Ui.gap(words, 4);
        words.addView(Ui.text(this, body, 12, Ui.SUB, false));
        Ui.stretch(row, words);
        Ui.spaceX(row, 8);
        row.addView(Ui.button(this, action, false, callback));
        card.addView(row);
    }

    private final class HeroGraphic extends FrameLayout {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        HeroGraphic() {
            super(MainActivity.this);
            setWillNotDraw(false);
        }
        @Override protected void onDraw(Canvas c) {
            float unit = Ui.dp(MainActivity.this, 1);
            c.save();
            c.translate(getWidth() - 92 * unit, getHeight() * .46f);
            c.scale(unit, unit);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(0xff352d61);
            c.drawCircle(4, 0, 72, paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(.8f);
            paint.setColor(0xff6c5eaa);
            c.drawCircle(4, 0, 72, paint);
            c.drawCircle(4, 0, 86, paint);
            c.drawCircle(4, 0, 102, paint);
            paint.setColor(0xffd5c9ff);
            paint.setStrokeWidth(2);
            paint.setStrokeCap(Paint.Cap.ROUND);
            int[] waves = {12, 26, 19, 41, 61, 34, 48, 23, 12};
            for (int i = 0; i < waves.length; i++) {
                float x = i * 10 - 36;
                c.drawLine(x, -waves[i] * .5f, x, waves[i] * .5f, paint);
            }
            paint.setStyle(Paint.Style.FILL);
            c.drawCircle(55, -55, 4, paint);
            c.drawCircle(-50, 53, 2.7f, paint);
            Path star = new Path();
            star.moveTo(-43, -49); star.lineTo(-41, -43); star.lineTo(-35, -41);
            star.lineTo(-41, -39); star.lineTo(-43, -33); star.lineTo(-45, -39);
            star.lineTo(-51, -41); star.lineTo(-45, -43); star.close();
            c.drawPath(star, paint);
            c.restore();
        }
    }

    private void pair(LinearLayout parent, String a, boolean primary, Runnable first, String b, Runnable second) {
        pair(parent, a, primary, first, b, false, second);
    }

    private void pair(LinearLayout parent, String a, boolean primary, Runnable first,
                      String b, boolean secondarySelected, Runnable second) {
        LinearLayout row = Ui.row(this);
        Ui.stretch(row, Ui.button(this, a, primary, first));
        Ui.spaceX(row, 8);
        Ui.stretch(row, Ui.button(this, b, secondarySelected, second));
        parent.addView(row);
    }

    private void practice() {
        heading("手动解题", "粘贴题干、选项和材料后生成解析。");
        LinearLayout card = Ui.card(this);
        EditText input = Ui.field(this, "输入题目", practiceDraft, true);
        card.addView(input);
        Ui.gap(card, 12);
        pair(card, "生成解析", true, () -> {
            practiceDraft = input.getText().toString().trim();
            if (practiceDraft.isEmpty() || answerBusy) return;
            if (!store.config().ready()) {
                practiceStatus.setText("请先在「设置」配置文本模型。");
                return;
            }
            answerBusy = true;
            practiceStatus.setText("正在生成…");
            Store.Config config = store.config();
            String draft = practiceDraft;
            ModelClient.WORK.execute(() -> {
                try {
                    String result = ModelClient.solve(config, draft, "").display();
                    runOnUiThread(() -> finishPractice(result, true));
                } catch (Exception ex) {
                    runOnUiThread(() -> finishPractice(ModelClient.message(ex), false));
                }
            });
        }, "载入例题", () -> {
            practiceDraft = SAMPLE_QUESTION;
            input.setText(practiceDraft);
        });
        content.addView(card);
        Ui.gap(content, 15);
        practiceStatus = Ui.text(this, answerBusy ? "正在生成…" : practiceAnswer, 15, Ui.INK, false);
        content.addView(practiceStatus);
    }

    private void finishPractice(String value, boolean success) {
        if (isDestroyed()) return;
        answerBusy = false;
        practiceAnswer = value;
        if (success) store.event("练习", "手动题目已生成解析");
        if (practiceStatus != null) practiceStatus.setText(value);
    }

    private void media() {
        heading("听力与口语", "采集听力、生成原文，或跟读并获取反馈。");
        pair(content, "听力转写", "listen".equals(audioTab), () -> {
            rememberEnglish(); audioTab = "listen"; show();
        }, "口语跟读", "speak".equals(audioTab), () -> {
            audioTab = "speak"; show();
        });
        Ui.gap(content, 14);
        if ("speak".equals(audioTab)) speaking();
        else listening();
    }

    private void listening() {
        LinearLayout card = Ui.card(this);
        card.addView(Ui.text(this, "采集 U校园播放", 18, Ui.INK, true));
        Ui.gap(card, 6);
        card.addView(Ui.text(this, "授权后打开 U校园，播放听力，结束时在「随练」浮窗点停止并转写。", 13, Ui.SUB, false));
        Ui.gap(card, 12);
        pair(card, "开始采集", true, this::beginCapture, "导入音频", () -> {
            Intent file = new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("audio/*")
                    .addCategory(Intent.CATEGORY_OPENABLE);
            startActivityForResult(file, AUDIO_FILE);
        });
        Ui.gap(card, 9);
        captureStatus = Ui.text(this, store.prefs.getString("capture_status", "Android 10+，音源需允许系统采集。"),
                12, Ui.SUB, false);
        card.addView(captureStatus);
        content.addView(card);
        Ui.gap(content, 14);

        LinearLayout raw = Ui.card(this);
        raw.addView(Ui.text(this, "听力原文", 18, Ui.INK, true));
        Ui.gap(raw, 9);
        EditText transcript = Ui.field(this, "导入音频转写，或在此粘贴原文", store.transcript(), true);
        raw.addView(transcript);
        Ui.gap(raw, 10);
        pair(raw, "用于 U校园下一页", true, () -> {
            String value = transcript.getText().toString().trim();
            if (value.isEmpty()) { toast("请先添加听力原文。"); return; }
            store.transcript(value);
            store.prefs.edit().putBoolean("attach_once", true).apply();
            toast("下次在浮窗处理时会附上原文。");
            openCampus();
        }, "清空原文", () -> {
            transcript.setText("");
            store.transcript("");
            store.prefs.edit().putBoolean("attach_once", false).apply();
        });
        content.addView(raw);
        Ui.gap(content, 13);
        voiceStatus = Ui.text(this, mediaMessage, 13, Ui.SUB, false);
        content.addView(voiceStatus);
    }

    private void speaking() {
        LinearLayout card = Ui.card(this);
        card.addView(Ui.text(this, "朗读范例", 18, Ui.INK, true));
        Ui.gap(card, 10);
        englishField = Ui.field(this, "输入英文范例或口语题目", english, true);
        card.addView(englishField);
        Ui.gap(card, 10);
        pair(card, "朗读英文", true, () -> {
            rememberEnglish();
            speakEnglish(english);
        }, speed < 0.8f ? "慢速 · 0.7×" : "常速 · 0.9×", () -> {
            rememberEnglish(); speed = speed < .8f ? .9f : .7f; show();
        });
        Ui.gap(card, 9);
        card.addView(Ui.button(this, "根据题目生成口语范例", false, this::prepareSpeech));
        Ui.gap(card, 9);
        card.addView(Ui.button(this, "我的音色", false, () -> {
            rememberEnglish();
            startActivity(new Intent(this, VoiceActivity.class).putExtra(VoiceActivity.PRACTICE_TEXT, english));
        }));
        content.addView(card);
        Ui.gap(content, 14);

        LinearLayout recordingCard = Ui.card(this);
        recordingCard.addView(Ui.text(this, "我的口语录音", 18, Ui.INK, true));
        Ui.gap(recordingCard, 7);
        recordingCard.addView(Ui.text(this, "转写回答后反馈内容、语法和表达，并提供一版修改稿。", 13, Ui.SUB, false));
        Ui.gap(recordingCard, 10);
        LinearLayout row = Ui.row(this);
        recordButton = Ui.button(this, recorder != null ? "停止录音" : "录音", true, this::toggleRecording);
        Ui.stretch(row, recordButton);
        Ui.spaceX(row, 8);
        Ui.stretch(row, Ui.button(this, "回放", false, this::playRecording));
        recordingCard.addView(row);
        Ui.gap(recordingCard, 8);
        recordingCard.addView(Ui.button(this, "转写并点评", false, () -> {
            rememberEnglish();
            if (recorder != null) endRecording();
            if (recording == null || !recording.isFile()) { toast("请先录一段回答。"); return; }
            voiceJob(recording, null, true);
        }));
        Ui.gap(recordingCard, 10);
        voiceStatus = Ui.text(this, mediaMessage, 13, Ui.SUB, false);
        recordingCard.addView(voiceStatus);
        if (!speechFeedback.isEmpty()) {
            Ui.gap(recordingCard, 12);
            recordingCard.addView(Ui.text(this, speechFeedback, 14, Ui.INK, false));
        }
        content.addView(recordingCard);
    }

    private void rememberEnglish() {
        if (englishField != null) english = englishField.getText().toString().trim();
    }

    private void speakEnglish(String words) {
        if (words.isEmpty()) { toast("请先输入要朗读的英文。"); return; }
        if (tts == null) {
            tts = new TextToSpeech(this, status -> runOnUiThread(() -> {
                if (isDestroyed() || tts == null) return;
                ttsReady = status == TextToSpeech.SUCCESS && tts.setLanguage(Locale.US) >= 0;
                if (ttsReady) speakEnglish(words);
                else setMediaStatus("系统缺少英文语音，可在文字转语音设置下载。");
            }));
            setMediaStatus("正在加载英语语音…");
        } else if (ttsReady) {
            tts.setSpeechRate(speed);
            String bounded = words.length() > TextToSpeech.getMaxSpeechInputLength()
                    ? words.substring(0, TextToSpeech.getMaxSpeechInputLength()) : words;
            tts.speak(bounded, TextToSpeech.QUEUE_FLUSH, null, "example");
            setMediaStatus("正在朗读");
        }
    }

    private void prepareSpeech() {
        rememberEnglish();
        if (transcriptBusy || english.isEmpty()) return;
        Store.Config c = store.config();
        if (!c.ready()) { setMediaStatus("请先配置文本模型。"); return; }
        transcriptBusy = true;
        String prompt = english;
        setMediaStatus("正在生成口语范例…");
        ModelClient.WORK.execute(() -> {
            try {
                String result = ModelClient.chat(c,
                        "你是英语教练。根据用户提供的题目写一段 60–120 词可直接朗读的英文示范。仅输出英文正文。",
                        prompt, 800);
                runOnUiThread(() -> {
                    if (isDestroyed()) return;
                    transcriptBusy = false;
                    english = result;
                    if (englishField != null) englishField.setText(result);
                    setMediaStatus("范例已生成");
                });
            } catch (Exception e) {
                runOnUiThread(() -> finishMediaError(e));
            }
        });
    }

    private void setMediaStatus(String value) {
        mediaMessage = value;
        if (voiceStatus != null) voiceStatus.setText(value);
    }

    private void beginCapture() {
        courseCapture = false;
        captureDuration = -1;
        beginCapture(false);
    }

    private void beginCapture(boolean automatic) {
        if (courseCapture && !CampusService.awaitingCourseAudio()) return;
        automaticCapture = automatic;
        if (Build.VERSION.SDK_INT < 29) { toast("系统播放采集需要 Android 10 或更新版本。"); return; }
        if (PlaybackCapture.running || PlaybackCapture.finishing || transcriptBusy) { toast("当前已有音频任务。"); return; }
        if (!store.config().speechReady()) { toast("请先在设置中配置转写模型。"); return; }
        if (!automatic || !CampusApp.supports(capturePackage)) {
            capturePackage = CampusApp.preferred(this, store.lastCampus());
        }
        if (capturePackage == null || !CampusApp.installed(this, capturePackage)) {
            openCampus();
            return;
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, CAPTURE_PERMISSION);
            return;
        }
        MediaProjectionManager manager = getSystemService(MediaProjectionManager.class);
        startActivityForResult(manager.createScreenCaptureIntent(), CAPTURE_CONSENT);
    }

    private void toggleRecording() {
        if (recorder != null) { endRecording(); return; }
        if (transcriptBusy || PlaybackCapture.running) { toast("请等待当前音频任务完成。"); return; }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, MIC);
        } else startRecording();
    }

    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(request, permissions, results);
        if (results.length == 0 || results[0] != PackageManager.PERMISSION_GRANTED) {
            toast("需要你授权录音权限才能使用该功能。");
            if (request == CAPTURE_PERMISSION && automaticCapture) {
                store.prefs.edit().putString("capture_signal", "cancel").apply();
            }
            return;
        }
        if (request == MIC) startRecording();
        if (request == CAPTURE_PERMISSION) beginCapture(automaticCapture);
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (result != RESULT_OK || data == null) {
            if (request == CAPTURE_CONSENT && automaticCapture) {
                store.prefs.edit().putString("capture_signal", "cancel").apply();
                openCampus(capturePackage);
            }
            return;
        }
        if (request == AUDIO_FILE && data.getData() != null) voiceJob(null, data.getData(), false);
        if (request == CAPTURE_CONSENT) {
            if (courseCapture && !CampusService.awaitingCourseAudio()) return;
            Intent service = new Intent(this, PlaybackCapture.class).putExtra("grant", data)
                    .putExtra("automatic", automaticCapture).putExtra(CampusApp.EXTRA_PACKAGE, capturePackage)
                    .putExtra("course_audio", courseCapture).putExtra("duration", captureDuration);
            startForegroundService(service);
            if (!openCampus(capturePackage)) {
                stopService(service);
                if (automaticCapture) store.prefs.edit().putString("capture_signal", "cancel").apply();
            }
        }
    }

    private void startRecording() {
        stopPlayback();
        if (tts != null) tts.stop();
        try {
            if (recording != null) recording.delete();
            recording = File.createTempFile("voice", ".m4a", getCacheDir());
            recorder = Build.VERSION.SDK_INT >= 31 ? new MediaRecorder(this) : new MediaRecorder();
            recorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            recorder.setAudioChannels(1);
            recorder.setMaxDuration(120000);
            recorder.setOnInfoListener((r, what, extra) -> {
                if (r == recorder && what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) endRecording();
            });
            recorder.setOutputFile(recording.getAbsolutePath());
            recorder.prepare();
            recorder.start();
            boolean hadFeedback = !speechFeedback.isEmpty();
            speechFeedback = "";
            if (hadFeedback && "media".equals(tab)) show();
            if (recordButton != null) recordButton.setText("停止录音");
            setMediaStatus("正在录音，最长 2 分钟。");
        } catch (Exception e) {
            if (recorder != null) recorder.release();
            recorder = null;
            if (recording != null) recording.delete();
            setMediaStatus("录音启动失败，请检查手机录音权限。");
        }
    }

    private void endRecording() {
        boolean ok = true;
        try { recorder.stop(); } catch (RuntimeException e) { ok = false; }
        recorder.release();
        recorder = null;
        if (!ok && recording != null) { recording.delete(); recording = null; }
        if (recordButton != null) recordButton.setText("录音");
        setMediaStatus(ok ? "录音已完成，可以回放或转写。" : "录音太短或失败，请重试。");
    }

    private void playRecording() {
        if (recorder != null || recording == null || !recording.isFile()) { toast("请先录一段回答。"); return; }
        stopPlayback();
        try {
            player = new MediaPlayer();
            player.setDataSource(recording.getAbsolutePath());
            player.setOnPreparedListener(MediaPlayer::start);
            player.setOnCompletionListener(p -> stopPlayback());
            player.prepareAsync();
        } catch (Exception e) {
            stopPlayback();
            setMediaStatus("无法回放，请重新录音。");
        }
    }

    private void stopPlayback() {
        if (player != null) {
            player.release();
            player = null;
        }
    }

    private void voiceJob(File microphone, Uri picked, boolean feedback) {
        if (transcriptBusy || PlaybackCapture.running || PlaybackCapture.finishing) return;
        Store.Config config = store.config();
        if (!config.speechReady()) { setMediaStatus("先在设置中配置转写接口和模型。"); return; }
        transcriptBusy = true;
        setMediaStatus("正在转写音频…");
        String topic = english;
        ModelClient.WORK.execute(() -> {
            File copied = null;
            try {
                String[] format = picked == null ? new String[]{"m4a", "audio/mp4"} : audioFormat(picked);
                if (picked != null) {
                    copied = File.createTempFile("import", "." + format[0], getCacheDir());
                    try (InputStream in = getContentResolver().openInputStream(picked);
                         FileOutputStream out = new FileOutputStream(copied)) {
                        if (in == null) throw new IOException("音频文件无法读取。");
                        byte[] buffer = new byte[16384];
                        int n;
                        long total = 0;
                        while ((n = in.read(buffer)) != -1) {
                            total += n;
                            if (total > ModelClient.MAX_AUDIO) throw new IOException("请使用小于 24 MB 的音频。");
                            out.write(buffer, 0, n);
                        }
                    }
                }
                String text = ModelClient.transcribe(config, picked == null ? microphone : copied, format[0], format[1]);
                String coaching = "";
                if (feedback && config.ready()) {
                    try {
                        coaching = ModelClient.chat(config, "你是英语口语教练。仅依据转写文本反馈内容、语法和词汇，"
                                        + "给两条中文建议和一版英文修改稿。不要断言发音表现。",
                                "参考题目或范例：\n" + topic + "\n我的回答转写：\n" + text, 1400);
                    } catch (Exception e) {
                        coaching = ModelClient.message(e);
                    }
                }
                String comment = coaching;
                runOnUiThread(() -> {
                    if (isDestroyed()) return;
                    transcriptBusy = false;
                    if (feedback) {
                        speechFeedback = "我的回答\n" + text + (comment.isEmpty() ? "" : "\n\n口语反馈\n" + comment);
                        store.event("口语", "已转写口语录音");
                    } else {
                        store.transcript(text);
                        store.prefs.edit().putBoolean("attach_once", false).apply();
                        store.event("听力", "已转写导入的听力音频");
                    }
                    mediaMessage = "转写完成";
                    if ("media".equals(tab)) show();
                });
            } catch (Exception e) {
                runOnUiThread(() -> finishMediaError(e));
            } finally {
                if (copied != null) copied.delete();
            }
        });
    }

    private void finishMediaError(Exception e) {
        if (isDestroyed()) return;
        transcriptBusy = false;
        setMediaStatus(ModelClient.message(e));
    }

    private String[] audioFormat(Uri uri) throws IOException {
        String mime = getContentResolver().getType(uri);
        if (mime == null) mime = "";
        switch (mime.toLowerCase(Locale.ROOT)) {
            case "audio/mpeg": case "audio/mp3": return new String[]{"mp3", "audio/mpeg"};
            case "audio/wav": case "audio/x-wav": return new String[]{"wav", "audio/wav"};
            case "audio/mp4": case "audio/m4a": case "audio/x-m4a": return new String[]{"m4a", "audio/mp4"};
            case "audio/webm": return new String[]{"webm", "audio/webm"};
            case "audio/ogg": case "application/ogg": return new String[]{"ogg", "audio/ogg"};
            case "audio/flac": case "audio/x-flac": return new String[]{"flac", "audio/flac"};
            case "audio/aac": return new String[]{"aac", "audio/aac"};
            default: throw new IOException("请选择 MP3、WAV、M4A、WebM、OGG、FLAC 或 AAC 音频。");
        }
    }

    private void tasks() {
        JSONArray items = store.list("tasks");
        heading("课程任务", "在 U校园课程目录点浮窗里的「扫描任务」。");
        pair(content, "全部任务", !"pending".equals(filter), () -> { filter = "all"; show(); },
                "只看待完成", "pending".equals(filter), () -> { filter = "pending"; show(); });
        Ui.gap(content, 12);
        LinearLayout list = Ui.card(this);
        int shown = 0;
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.optJSONObject(i);
            if (item == null) continue;
            boolean done = item.optBoolean("done");
            if ("pending".equals(filter) && done) continue;
            if (shown++ > 0) { Ui.gap(list, 9); Ui.rule(list); Ui.gap(list, 9); }
            int index = i;
            infoRow(list, item.optString("name"), done ? "已完成" : "待完成",
                    done ? "撤销" : "完成", () -> { store.changeTask(index, !done); show(); });
        }
        if (shown == 0) {
            list.addView(Ui.text(this, items.length() == 0 ? "还没有收录任务" : "没有待完成的任务", 16, Ui.INK, true));
            Ui.gap(list, 10);
            list.addView(Ui.button(this, "打开 U校园", false, this::openCampus));
        }
        content.addView(list);
        Ui.gap(content, 19);
        JSONArray history = store.list("activity");
        content.addView(Ui.text(this, "最近记录", 18, Ui.INK, true));
        Ui.gap(content, 10);
        LinearLayout timeline = Ui.card(this);
        if (history.length() == 0) timeline.addView(Ui.text(this, "暂无运行记录", 13, Ui.SUB, false));
        SimpleDateFormat time = new SimpleDateFormat("MM-dd HH:mm", Locale.getDefault());
        for (int i = 0; i < Math.min(history.length(), 10); i++) {
            JSONObject item = history.optJSONObject(i);
            if (item == null) continue;
            if (i > 0) { Ui.gap(timeline, 8); Ui.rule(timeline); Ui.gap(timeline, 8); }
            timeline.addView(Ui.text(this, item.optString("note"), 14, Ui.INK, false));
            Ui.gap(timeline, 3);
            timeline.addView(Ui.text(this, time.format(new Date(item.optLong("time"))), 11, Ui.SUB, false));
        }
        content.addView(timeline);
    }

    private void settings() {
        heading("设置", "配置模型、转写和自动处理。");
        Store.Config config = store.config();
        LinearLayout textModel = Ui.card(this);
        textModel.addView(Ui.text(this, "文本模型", 18, Ui.INK, true));
        EditText base = labeled(textModel, "接口地址", "https://服务商地址/v1", config.base, false);
        EditText key = labeled(textModel, "API 密钥", "填写密钥", config.key, true);
        EditText model = labeled(textModel, "模型名称", "填写服务商提供的模型 ID", config.model, false);
        content.addView(textModel);
        Ui.gap(content, 13);
        LinearLayout audioModel = Ui.card(this);
        audioModel.addView(Ui.text(this, "听力与口语转写", 18, Ui.INK, true));
        Ui.gap(audioModel, 7);
        audioModel.addView(Ui.text(this, "把听力材料或你的录音转成文字。听力原文可以用来解题，口语转写可以用来点评语法和表达。", 13, Ui.SUB, false));
        EditText speechBase = labeled(audioModel, "独立转写接口（可选）", "留空沿用文本接口地址", config.audioBase, false);
        EditText speechKey = labeled(audioModel, "转写密钥（可选）", "同一服务时可留空", config.audioKey, true);
        EditText speech = labeled(audioModel, "转写模型", "例如 whisper-1，以服务商文档为准", config.audioModel, false);
        Ui.gap(audioModel, 8);
        audioModel.addView(Ui.text(this, "文本和音频会发送给你配置的模型服务。密钥加密保存在本机。", 12, Ui.SUB, false));
        content.addView(audioModel);
        Ui.gap(content, 13);

        LinearLayout auto = Ui.card(this);
        auto.addView(Ui.text(this, "自动处理", 18, Ui.INK, true));
        Ui.gap(auto, 8);
        Switch submit = option(auto, "处理完成后自动提交", config.autoSubmit);
        Switch next = option(auto, "确认结果后继续下一题", config.autoSubmit && config.autoNext);
        Switch say = option(auto, "自动朗读口语范例", store.prefs.getBoolean("auto_speak", false));
        next.setEnabled(config.autoSubmit);
        submit.setOnCheckedChangeListener((view, enabled) -> {
            next.setEnabled(enabled);
            if (!enabled) next.setChecked(false);
        });
        Ui.gap(auto, 6);
        auto.addView(Ui.text(this, "每次由浮窗启动，连续处理最多 10 页。", 12, Ui.SUB, false));
        content.addView(auto);
        Ui.gap(content, 13);
        TextView state = Ui.text(this, "", 13, Ui.SUB, false);
        pair(content, "保存设置", true, () -> {
            Store.Config updated = new Store.Config(value(base), value(key), value(model), value(speechBase),
                    value(speechKey), value(speech), submit.isChecked(), submit.isChecked() && next.isChecked());
            try {
                store.save(updated);
                store.prefs.edit().putBoolean("auto_speak", say.isChecked()).apply();
                state.setText("已保存");
            } catch (Exception ex) {
                state.setText(ModelClient.message(ex));
            }
        }, "测试文本接口", () -> {
            Store.Config test = new Store.Config(value(base), value(key), value(model), "", "", "", false, false);
            if (!test.ready()) { state.setText("请先填写文本接口、密钥和模型。"); return; }
            state.setText("测试中…");
            ModelClient.WORK.execute(() -> {
                String status;
                try {
                    ModelClient.chat(test, "Reply with OK.", "Connection test", 512);
                    status = "文本接口已连通";
                } catch (Exception e) { status = ModelClient.message(e); }
                String reply = status;
                runOnUiThread(() -> state.setText(reply));
            });
        });
        Ui.gap(content, 9);
        content.addView(state);
        Ui.gap(content, 10);
        content.addView(Ui.button(this, "打开 Android 无障碍设置", false, this::openAccessibility));
    }

    private EditText labeled(LinearLayout card, String title, String hint, String text, boolean password) {
        Ui.gap(card, 11);
        card.addView(Ui.text(this, title, 13, Ui.INK, true));
        Ui.gap(card, 5);
        EditText edit = Ui.field(this, hint, text, false);
        if (password) edit.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        card.addView(edit);
        return edit;
    }

    private Switch option(LinearLayout card, String label, boolean checked) {
        Switch toggle = new Switch(this);
        toggle.setText(label);
        toggle.setTextColor(Ui.INK);
        toggle.setMinHeight(Ui.dp(this, 48));
        toggle.setChecked(checked);
        card.addView(toggle, new LinearLayout.LayoutParams(-1, -2));
        return toggle;
    }

    private static String value(EditText field) { return field.getText().toString(); }

    private void openCampus() {
        openCampus(CampusApp.preferred(this, store.lastCampus()));
    }

    private boolean openCampus(String name) {
        if (name == null) {
            setLaunchMessage("当前手机空间未检测到 U校园。");
            new AlertDialog.Builder(this).setTitle("需要安装 U校园")
                    .setMessage("当前手机空间未找到 U校园或 U校园（旧版）。如果使用应用分身，请把两个应用放在同一个空间。")
                    .setNegativeButton("关闭", null)
                    .setPositiveButton("官方下载", (d, w) -> {
                        try {
                            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(CampusApp.DOWNLOAD)));
                        } catch (ActivityNotFoundException | SecurityException e) {
                            setLaunchMessage("无法打开浏览器。官方下载地址 " + CampusApp.DOWNLOAD);
                            toast("系统没有打开浏览器。");
                        }
                    }).show();
            return false;
        }
        String nameLabel = CampusApp.label(name);
        if (!CampusApp.launch(this, name)) {
            String reason = "系统未能打开" + nameLabel + "。请从手机桌面打开，浮窗会在应用内显示。";
            setLaunchMessage(reason);
            new AlertDialog.Builder(this).setTitle("无法打开" + nameLabel)
                    .setMessage(reason).setPositiveButton("知道了", null).show();
            return false;
        }
        store.rememberCampus(name);
        setLaunchMessage("正在打开" + nameLabel + "…");
        long attempt = android.os.SystemClock.uptimeMillis();
        launchAttempt = attempt;
        new android.os.Handler(getMainLooper()).postDelayed(() -> {
            if (!isDestroyed() && launchAttempt == attempt && hasWindowFocus()) {
                setLaunchMessage("系统尚未切换到" + nameLabel + "。也可以从手机桌面直接打开。");
            }
        }, 2500);
        return true;
    }

    private void setLaunchMessage(String message) {
        launchMessage = message;
        if (campusLaunchStatus != null) {
            campusLaunchStatus.setText(message);
            campusLaunchStatus.setVisibility(message.isEmpty() ? View.GONE : View.VISIBLE);
        }
    }

    private boolean overlayEnabled() {
        AccessibilityManager manager = getSystemService(AccessibilityManager.class);
        if (manager == null) return false;
        ComponentName expected = new ComponentName(this, CampusService.class);
        for (AccessibilityServiceInfo service : manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)) {
            if (expected.equals(ComponentName.unflattenFromString(service.getId()))) return true;
        }
        return false;
    }

    private void openAccessibility() {
        try {
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
        } catch (ActivityNotFoundException | SecurityException e) {
            toast("请从手机设置的无障碍页面开启校园随练。");
        }
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }
}
