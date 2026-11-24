package dev.campuspocket.app;

import android.app.Activity;
import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.InputType;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import java.net.InetAddress;
import java.net.URI;
import java.util.Locale;
import java.util.concurrent.Future;

public final class TrainingSettingsActivity extends Activity {
    private Store store;
    private EditText base, key, model, asrBase, asrKey, asrModel, minimumScore;
    private SeekBar speed;
    private TextView result, apiResult, voiceResult, deviceResult, dnsResult;
    private Future<?> apiTest, dnsTest;
    private int apiTestTicket, dnsTestTicket;
    private TrainingAudio voiceTest;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        store = new Store(this);
        Store.Config current = store.config();
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Ui.BG);
        LinearLayout content = Ui.column(this);
        Ui.pad(content, 22, 21, 28);
        content.setFocusableInTouchMode(true);
        scroll.addView(content);
        setContentView(scroll);
        content.addView(Ui.button(this, "返回校园随练", false, this::finish));
        Ui.gap(content, 20);
        content.addView(Ui.text(this, "API 与语音", 29, Ui.INK, true));
        Ui.gap(content, 16);
        LinearLayout diagnostics = Ui.card(this);
        diagnostics.addView(Ui.text(this, "运行诊断", 18, Ui.INK, true));
        deviceResult = Ui.text(this, "", 13, Ui.SUB, false);
        diagnostics.addView(deviceResult);
        diagnostics.addView(Ui.button(this, "刷新设备状态", false, this::checkDevice));
        diagnostics.addView(Ui.button(this, "检测 API 域名", false, this::checkDns));
        dnsResult = Ui.text(this, "", 13, Ui.SUB, false);
        diagnostics.addView(dnsResult);
        content.addView(diagnostics);
        Ui.gap(content, 14);
        LinearLayout card = Ui.card(this);
        card.addView(Ui.text(this, "文本模型", 18, Ui.INK, true));
        base = field(card, "接口地址", current.base, false);
        key = field(card, "API 密钥", current.key, true);
        model = field(card, "模型 ID", current.model, false);
        Ui.gap(card, 13);
        card.addView(Ui.text(this, "生成口语答案时，会把当前题目的可读文字发送给这里配置的服务。密钥加密保存在手机。", 13, Ui.SUB, false));
        Ui.gap(card, 9);
        card.addView(Ui.button(this, "测试文本 API", false, () -> test(true)));
        card.addView(Ui.button(this, "测试自由表达长回答", false, () -> test(false)));
        apiResult = Ui.text(this, "", 13, Ui.SUB, false);
        card.addView(apiResult);
        content.addView(card);
        Ui.gap(content, 14);

        LinearLayout speech = Ui.card(this);
        TextView speedLabel = Ui.text(this, formatSpeed(store.trainingSpeed()), 18, Ui.INK, true);
        speech.addView(speedLabel);
        speed = new SeekBar(this);
        speed.setMax(16);
        speed.setProgress(Math.round((store.trainingSpeed() - .60f) * 20));
        speed.setContentDescription("朗读语速，0.6 到 1.4 倍");
        speech.addView(speed);
        speed.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                speedLabel.setText(formatSpeed(selectedSpeed()));
            }
            @Override public void onStartTrackingTouch(SeekBar bar) { }
            @Override public void onStopTrackingTouch(SeekBar bar) { }
        });
        speech.addView(Ui.button(this, "我的音色", false, () -> startActivity(new Intent(this, VoiceActivity.class))));
        Ui.gap(speech, 8);
        speech.addView(Ui.button(this, "试听系统英语", false, () -> testVoice(false)));
        speech.addView(Ui.button(this, "试听个人音色", false, () -> testVoice(true)));
        voiceResult = Ui.text(this, "", 13, Ui.SUB, false);
        speech.addView(voiceResult);
        minimumScore = field(speech, "最低分（0–100）", String.valueOf(store.trainingMinimum()), false);
        minimumScore.setInputType(InputType.TYPE_CLASS_NUMBER);
        speech.addView(Ui.text(this, "有数字评分的题目低于设定值时重录一次，第二次出分后继续。", 13, Ui.SUB, false));
        content.addView(speech);
        Ui.gap(content, 14);

        LinearLayout asr = Ui.card(this);
        asr.addView(Ui.text(this, "转写接口", 18, Ui.INK, true));
        asr.addView(Ui.text(this, "保留给后续听力和转写使用。口语对话优先读取 U校园显示的文字。", 13, Ui.SUB, false));
        asrBase = field(asr, "独立接口地址（可选）", current.audioBase, false);
        asrKey = field(asr, "转写密钥（可选）", current.audioKey, true);
        asrModel = field(asr, "转写模型 ID", current.audioModel, false);
        content.addView(asr);
        Ui.gap(content, 14);
        content.addView(Ui.button(this, "保存设置", true, this::save));
        Ui.gap(content, 10);
        result = Ui.text(this, "", 14, Ui.SUB, false);
        content.addView(result);
        content.requestFocus();
        checkDevice();
    }

    private float selectedSpeed() { return .60f + speed.getProgress() * .05f; }
    private String formatSpeed(float value) { return String.format(Locale.ROOT, "朗读语速 %.2f×", value); }

    private EditText field(LinearLayout content, String label, String value, boolean secret) {
        Ui.gap(content, 10);
        content.addView(Ui.text(this, label, 13, Ui.INK, true));
        EditText input = Ui.field(this, label, value, false);
        input.setSaveEnabled(false);
        if (secret) input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        content.addView(input);
        return input;
    }

    private Store.Config selected() {
        return new Store.Config(value(base), value(key), value(model), value(asrBase), value(asrKey), value(asrModel), false, false);
    }

    private String value(EditText field) { return field.getText().toString().trim(); }

    private void save() {
        String minimum = value(minimumScore);
        if (!minimum.matches("\\d{1,3}") || Integer.parseInt(minimum) > 100) {
            minimumScore.setError("请输入 0 到 100 的整数");
            minimumScore.requestFocus();
            return;
        }
        try {
            store.save(selected());
            store.prefs.edit().putFloat("training_speed", selectedSpeed())
                    .putInt("training_minimum_score", Integer.parseInt(minimum)).apply();
            result.setText("设置已保存。");
        } catch (Exception error) {
            result.setText(ModelClient.message(error));
        }
    }

    private void test(boolean dialogue) {
        Store.Config settings = selected();
        int ticket = ++apiTestTicket;
        if (apiTest != null) apiTest.cancel(true);
        if (!settings.ready()) {
            apiResult.setText("请先填写接口地址、密钥和模型 ID。");
            return;
        }
        long start = SystemClock.uptimeMillis();
        apiResult.setText(dialogue ? "正在用口语对话的格式请求示例回答…" : "正在请求限时 60 秒的自由表达示例…");
        int targetWords = dialogue ? TrainingModel.DIALOGUE_WORDS : TrainingModel.FREE_WORDS;
        apiTest = ModelClient.WORK.submit(() -> {
            String response;
            try {
                Store.validateBase(settings.base);
                String question = dialogue ? "AI 的最新发言\nWhere should our class go for a day trip, and what could we do there?"
                        : "Describe a plan for a class trip in 60 seconds. Say where to go, what to do there, and why the class would benefit.";
                String answer = TrainingModel.speak(settings, dialogue, question, targetWords, reason -> runOnUiThread(() -> {
                    if (!isDestroyed() && ticket == apiTestTicket) apiResult.setText(reason + "\n正在自动重试…");
                }));
                response = "文本 API 已返回可朗读回答 · " + elapsed(start) + "\n" + answer;
            } catch (Exception error) {
                response = "文本 API 测试失败 · " + elapsed(start) + "\n" + ModelClient.message(error);
            }
            String status = response;
            runOnUiThread(() -> {
                if (!isDestroyed() && ticket == apiTestTicket) {
                    apiTest = null;
                    apiResult.setText(status);
                }
            });
        });
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            if (isDestroyed() || ticket != apiTestTicket || apiTest == null) return;
            apiTestTicket++;
            apiTest.cancel(true);
            apiTest = null;
            apiResult.setText("文本 API 测试超时 · " + elapsed(start) + "\n请检查网络、接口地址和模型响应。");
        }, dialogue ? 30000 : 60000);
    }

    private void testVoice(boolean personal) {
        stopVoiceTest();
        Store.Voice voice = personal ? store.voice() : new Store.Voice("", "");
        if (personal && (voice.id.isEmpty() || voice.key.isEmpty())) {
            voiceResult.setText("请先在「我的音色」保存个人音色。");
            return;
        }
        AudioManager manager = getSystemService(AudioManager.class);
        if (manager != null && manager.getStreamVolume(AudioManager.STREAM_MUSIC) == 0) {
            voiceResult.setText("媒体音量为 0，请调高后再试听。");
            return;
        }
        long start = SystemClock.uptimeMillis();
        String source = personal ? "个人音色" : "系统英语语音";
        voiceResult.setText("正在生成" + source + "试听音频…");
        voiceTest = new TrainingAudio(this, new Handler(Looper.getMainLooper()), selectedSpeed(), voice,
                new TrainingAudio.Listener() {
                    private String device = "输出设备未确认";
                    @Override public void ready(int durationMs) {
                        voiceResult.setText("音频已生成 · " + elapsed(start) + " · 正在启动播放器…");
                        voiceTest.play(true);
                    }
                    @Override public void playbackStarted(String output) {
                        device = output;
                        voiceResult.setText("播放器已开始 · " + device);
                    }
                    @Override public void done() {
                        voiceResult.setText("播放器已播完 · " + device);
                        stopVoiceTest();
                    }
                    @Override public void error(String message) {
                        voiceResult.setText(source + "试听失败 · " + elapsed(start) + "\n" + message);
                        stopVoiceTest();
                    }
                });
        voiceTest.prepare("This is the speaking audio test. Can you hear this sentence clearly?", true);
    }

    private void checkDevice() {
        long start = SystemClock.uptimeMillis();
        ConnectivityManager connectivity = getSystemService(ConnectivityManager.class);
        Network network = connectivity == null ? null : connectivity.getActiveNetwork();
        NetworkCapabilities caps = network == null ? null : connectivity.getNetworkCapabilities(network);
        String connection = network == null ? "未连接"
                : caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) ? "Android 已验证互联网连接"
                : "已连接，互联网尚未验证";
        AudioManager manager = getSystemService(AudioManager.class);
        String volume = manager == null ? "未提供" : manager.getStreamVolume(AudioManager.STREAM_MUSIC)
                + "/" + manager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        String mic = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                ? "已授权" : "未授权";
        deviceResult.setText("网络 · " + connection + "\n无障碍服务 · " + (CampusService.connected() ? "已连接" : "未连接")
                + "\n媒体音量 · " + volume + "\n随练麦克风权限 · " + mic
                + "\n本地读取耗时 " + (SystemClock.uptimeMillis() - start) + " ms");
    }

    private void checkDns() {
        int ticket = ++dnsTestTicket;
        if (dnsTest != null) dnsTest.cancel(true);
        String address = value(base);
        String host;
        try {
            Store.validateBase(address);
            host = new URI(address).getHost();
            if (host == null) throw new IllegalArgumentException();
        } catch (Exception error) {
            dnsResult.setText("请填写有效的 HTTPS 接口地址。");
            return;
        }
        long start = SystemClock.uptimeMillis();
        dnsResult.setText("正在解析当前 API 域名…");
        dnsTest = ModelClient.WORK.submit(() -> {
            boolean resolved;
            try { resolved = InetAddress.getAllByName(host).length > 0; }
            catch (Exception error) { resolved = false; }
            String output = (resolved ? "API 域名可解析" : "API 域名解析失败") + " · " + elapsed(start);
            runOnUiThread(() -> {
                if (!isDestroyed() && ticket == dnsTestTicket) {
                    dnsTest = null;
                    dnsResult.setText(output);
                }
            });
        });
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            if (isDestroyed() || ticket != dnsTestTicket || dnsTest == null) return;
            dnsTestTicket++;
            dnsTest.cancel(true);
            dnsTest = null;
            dnsResult.setText("API 域名解析超时 · " + elapsed(start));
        }, 5000);
    }

    private static String elapsed(long start) {
        return String.format(Locale.ROOT, "%.1f 秒", (SystemClock.uptimeMillis() - start) / 1000.0);
    }

    private void stopVoiceTest() {
        if (voiceTest == null) return;
        voiceTest.stop();
        voiceTest = null;
    }

    @Override protected void onStop() {
        stopVoiceTest();
        super.onStop();
    }

    @Override protected void onDestroy() {
        apiTestTicket++;
        dnsTestTicket++;
        if (apiTest != null) apiTest.cancel(true);
        if (dnsTest != null) dnsTest.cancel(true);
        super.onDestroy();
    }
}
