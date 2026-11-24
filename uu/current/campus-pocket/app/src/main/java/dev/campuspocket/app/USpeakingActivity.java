package dev.campuspocket.app;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

public final class USpeakingActivity extends Activity {
    static final String EXPORT = "export_observer_diagnostics";
    private static final int EXPORT_REQUEST = 81;
    private Store store;
    private TextView status, feedback;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            String value = CampusService.connected() ? CampusService.observedPage() : "请在系统设置启用 U听说监测服务";
            if (!value.contentEquals(status.getText())) status.setText(value);
            ui.postDelayed(this, 750);
        }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        store = new Store(this);
        show();
        openExportIfRequested(getIntent());
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        openExportIfRequested(intent);
    }

    @Override protected void onResume() {
        super.onResume();
        refresh.run();
    }

    @Override protected void onPause() {
        ui.removeCallbacks(refresh);
        super.onPause();
    }

    private void show() {
        ScrollView screen = new ScrollView(this);
        screen.setFillViewport(true);
        screen.setBackgroundColor(Ui.BG);
        LinearLayout body = Ui.column(this);
        Ui.pad(body, 22, 23, 28);
        screen.addView(body);
        setContentView(screen);
        body.addView(Ui.text(this, "校园随练 · 口语训练", 31, Ui.INK, true));
        Ui.gap(body, 8);
        body.addView(Ui.text(this, "保存 API 后，打开官方 U口语首页。在浮窗点「开始口语训练」，随练会打开自适应训练，依次完成范例学习、AI 口语对话和自由表达。朗读通过手机媒体播放，由 U校园收录。", 14, Ui.SUB, false));
        Ui.gap(body, 14);
        body.addView(Ui.button(this, "API 与语音", true, () -> startActivity(new Intent(this, TrainingSettingsActivity.class))));
        Ui.gap(body, 18);
        LinearLayout monitor = Ui.card(this);
        monitor.addView(Ui.text(this, "实时监测", 18, Ui.INK, true));
        Ui.gap(monitor, 9);
        status = Ui.text(this, CampusService.observedPage(), 14, Ui.INK, false);
        monitor.addView(status);
        Ui.gap(monitor, 14);
        monitor.addView(Ui.button(this, "打开 U校园", true, this::openCampus));
        Ui.gap(monitor, 8);
        monitor.addView(Ui.button(this, "打开无障碍设置", false, () -> {
            try {
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            } catch (ActivityNotFoundException error) {
                feedback.setText("请在手机系统设置搜索「无障碍」。");
            }
        }));
        body.addView(monitor);
        Ui.gap(body, 14);
        LinearLayout capture = Ui.card(this);
        capture.addView(Ui.text(this, "真机诊断", 18, Ui.INK, true));
        Ui.gap(capture, 9);
        capture.addView(Ui.text(this, "在 U听说展开浮窗，点「开始采样」，然后按平时的方式打开题目、播放、录音和查看结果。截图和节点树保存在手机内。", 14, Ui.SUB, false));
        Ui.gap(capture, 12);
        capture.addView(Ui.button(this, "保存最近的诊断包", false, this::chooseDestination));
        Ui.gap(capture, 10);
        feedback = Ui.text(this, "", 13, Ui.GREEN, false);
        feedback.setAccessibilityLiveRegion(android.view.View.ACCESSIBILITY_LIVE_REGION_POLITE);
        capture.addView(feedback);
        body.addView(capture);
        Ui.gap(body, 18);
        body.addView(Ui.text(this, "Fayx @Copyright\n江西财经大学VR学院枫零社", 13, Ui.SUB, true));
    }

    private void openCampus() {
        String target = CampusApp.preferred(this, store.lastCampus());
        if (target == null || !CampusApp.launch(this, target)) {
            feedback.setText("无法从这里打开 U校园，可从手机桌面打开已安装的官方 U校园。");
        }
    }

    private void openExportIfRequested(Intent intent) {
        if (!intent.getBooleanExtra(EXPORT, false)) return;
        intent.removeExtra(EXPORT);
        getWindow().getDecorView().post(this::chooseDestination);
    }

    private void chooseDestination() {
        if (!USpeakingDiagnostics.hasSession(this)) {
            feedback.setText("先展开浮窗，记录一页 U听说。");
            return;
        }
        Intent picker = new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("application/zip")
                .addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE, "U听说-真机诊断.zip");
        try {
            startActivityForResult(picker, EXPORT_REQUEST);
        } catch (ActivityNotFoundException error) {
            feedback.setText("当前手机无法打开文件保存界面。");
        }
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != EXPORT_REQUEST || result != RESULT_OK || data == null || data.getData() == null) return;
        feedback.setText("正在整理诊断包…");
        USpeakingDiagnostics.export(this, data.getData(), success -> {
            if (!isDestroyed()) feedback.setText(success ? "诊断包已保存。" : "未能保存诊断包，请重试。");
        });
    }
}
