package dev.campuspocket.app;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.Bitmap;
import android.hardware.HardwareBuffer;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class CampusService extends AccessibilityService {
    private static CampusService instance;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Set<String> seen = new HashSet<>();
    private Store store;
    private OverlayWindow overlay;
    private USpeakingMonitor observer;
    private boolean expanded, busy, foreground, covered;
    private boolean testing, retryingWindow, visibilityPending;
    private String foregroundPackage;
    private int generation;
    private String notice = "";
    private String signature;
    private int stagedWindow;
    private ModelClient.Solution staged;
    private ReadAloudRun reading;
    private FollowAlongRun following;
    private CourseRun course;
    private boolean applied, attach;
    private final Runnable visibility = () -> {
        visibilityPending = false;
        checkForeground();
    };
    private String listeningPage;
    private int listeningWindow;
    @Override protected void onServiceConnected() {
        if (observer != null) observer.close();
        instance = this;
        store = new Store(this);
        overlay = new OverlayWindow(this, getSystemService(WindowManager.class), store.prefs);
        observer = new USpeakingMonitor(this, ui, overlay, store);
        observer.start();
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        if (observer != null) observer.signal(event);
    }

    private void scheduleWindowCheck(long delay) {
        if (visibilityPending) return;
        visibilityPending = true;
        ui.postDelayed(visibility, delay);
    }

    private void checkForeground() {
        AccessibilityNodeInfo root = applicationRoot();
        if (root == null && (foreground || testing) && !retryingWindow) {
            retryingWindow = true;
            scheduleWindowCheck(250);
            return;
        }
        retryingWindow = false;
        if (testing && belongsToCompanion(root)) return;
        boolean leavingTest = testing;
        testing = false;
        if (!isCampus(root)) {
            if (foreground) {
                boolean readingInterrupted = reading != null || following != null;
                if (course == null || !course.allowsAuthorization()) reset();
                if (readingInterrupted) notice = "朗读已暂停。请回到原题，确认麦克风权限和录音状态后再启动。";
                if (speaker != null) speaker.stop();
            }
            foreground = false;
            foregroundPackage = null;
            remove();
        } else {
            String name = root.getPackageName().toString();
            boolean changed = !name.equals(foregroundPackage);
            if (changed && foreground) reset();
            if (changed || !foreground || !overlay.showing() || leavingTest) {
                foreground = true;
                foregroundPackage = name;
                covered = false;
                store.rememberCampus(name);
                render();
            }
            if (course != null) course.signal();
        }
    }

    // Tapping the overlay can make its own window active while the app below keeps input focus.
    private AccessibilityNodeInfo applicationRoot() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (isCampus(root)) return root;
        if (root != null && !belongsToCompanion(root)) {
            for (AccessibilityWindowInfo window : getWindows()) {
                if (window.getId() == root.getWindowId() && window.getType() == AccessibilityWindowInfo.TYPE_APPLICATION) {
                    return root;
                }
            }
        }
        for (AccessibilityWindowInfo window : getWindows()) {
            if (window.getType() == AccessibilityWindowInfo.TYPE_APPLICATION && window.isFocused()) {
                AccessibilityNodeInfo focused = window.getRoot();
                if (focused != null) return focused;
            }
        }
        return root;
    }

    private boolean isCampus(AccessibilityNodeInfo node) {
        return node != null && CampusApp.supports(node.getPackageName());
    }

    private boolean belongsToCompanion(AccessibilityNodeInfo node) {
        return node != null && node.getPackageName() != null && getPackageName().contentEquals(node.getPackageName());
    }

    static boolean connected() { return instance != null; }

    static String observedPage() {
        return instance == null || instance.observer == null ? "等待监测服务连接" : instance.observer.summary();
    }

    static boolean awaitingCourseAudio() {
        return instance != null && instance.course != null && instance.course.allowsAuthorization();
    }

    static boolean testOverlay() {
        CampusService service = instance;
        if (service == null) return false;
        service.reset();
        service.foreground = false;
        service.foregroundPackage = null;
        service.testing = true;
        service.render();
        return service.overlay.showing();
    }

    @Override public void onConfigurationChanged(Configuration configuration) {
        super.onConfigurationChanged(configuration);
        if (observer != null) observer.redraw();
    }

    @Override public void onInterrupt() {
        if (observer != null) observer.start();
    }

    @Override public void onDestroy() {
        if (instance == this) instance = null;
        if (observer != null) observer.close();
        observer = null;
        ui.removeCallbacksAndMessages(null);
        remove();
        super.onDestroy();
    }

    private void reset() {
        String interrupted = "";
        if (course != null) {
            course.end("连续处理已暂停。");
            interrupted = notice;
        }
        if (reading != null) {
            reading.cancel(false);
            reading = null;
        }
        if (following != null) {
            following.cancel();
            following = null;
        }
        generation++;
        busy = false;
        expanded = false;
        applied = false;
        attach = false;
        notice = interrupted;
        staged = null;
        signature = null;
        seen.clear();
    }

    private void remove() {
        if (overlay != null) overlay.dismiss();
    }

    private void render() {
        if ((!foreground && !testing) || overlay == null) return;
        OverlayWindow.Mode mode;
        View body;
        if (testing) {
            mode = OverlayWindow.Mode.PREVIEW;
            LinearLayout panel = Ui.card(this);
            TextView title = Ui.text(this, "⠿  浮窗已连接", 18, Ui.INK, true);
            overlay.dragHandle(title, false);
            panel.addView(title);
            Ui.gap(panel, 9);
            panel.addView(Ui.text(this, "按住标题可以移动窗口。进入 U校园后，轻点「随练」展开，按住它就能拖动。", 14, Ui.SUB, false));
            Ui.gap(panel, 12);
            panel.addView(Ui.button(this, "关闭测试", true, () -> {
                testing = false;
                remove();
            }));
            body = panel;
        } else if (!expanded) {
            mode = OverlayWindow.Mode.CHIP;
            TextView pill = Ui.button(this, covered ? "随练" : "✦  随练", true, () -> {
                expanded = true;
                covered = false;
                render();
            });
            pill.setElevation(Ui.dp(this, 6));
            pill.setContentDescription("随练，轻点展开，按住拖动");
            overlay.dragHandle(pill, true);
            body = pill;
        } else {
            mode = OverlayWindow.Mode.PANEL;
            LinearLayout panel = Ui.card(this);
            panel.setElevation(Ui.dp(this, 10));
            LinearLayout heading = Ui.row(this);
            TextView title = Ui.text(this, "⠿  随练工作台", 19, Ui.INK, true);
            title.setMinHeight(Ui.dp(this, 48));
            title.setGravity(android.view.Gravity.CENTER_VERTICAL);
            title.setContentDescription("随练工作台，按住拖动");
            overlay.dragHandle(title, false);
            Ui.stretch(heading, title);
            heading.addView(Ui.button(this, "收起", false, () -> {
                expanded = false;
                covered = true;
                render();
            }));
            panel.addView(heading);
            if (!notice.isEmpty()) {
                Ui.gap(panel, 8);
                panel.addView(Ui.text(this, notice, 13, Ui.SUB, false));
            }

            if (busy) {
                Ui.gap(panel, 12);
                panel.addView(Ui.button(this, "停止处理", false, this::stopProcessing));
            } else if (staged != null) {
                Ui.gap(panel, 10);
                ScrollView scroll = new ScrollView(this);
                TextView output = Ui.text(this, staged.display(), 14, Ui.INK, false);
                Ui.pad(output, 12, 11, 11);
                output.setBackground(Ui.shape(this, Ui.BG, 12, 0));
                scroll.addView(output);
                int height = Math.min(190, Math.max(70, getResources().getConfiguration().screenHeightDp / 4));
                panel.addView(scroll, new LinearLayout.LayoutParams(-1, Ui.dp(this, height)));
                Ui.gap(panel, 10);
                pair(panel, "复制解析", false, this::copyResult,
                        applied ? "提交本页" : "回填答案", true, applied ? this::submitManually : this::applyManually);
                Ui.gap(panel, 6);
                pair(panel, "返回浮窗菜单", false, () -> {
                    staged = null;
                    applied = false;
                    notice = "";
                    render();
                }, staged.speech.isEmpty() ? "语音听说" : "朗读范例", false, () -> {
                    if (staged.speech.isEmpty()) openCompanion("media");
                    else speak(staged.speech);
                });
            } else {
                Ui.gap(panel, 10);
                if (notice.isEmpty()) {
                    String hint = store.config().ready()
                            ? "进入课程练习后，点「自动处理本页」。"
                            : "浮窗已连接。先在「打开设置」配置文本模型，再处理题目。";
                    panel.addView(Ui.text(this, hint, 13, Ui.SUB, false));
                    Ui.gap(panel, 10);
                }
                pair(panel, "仅生成解析", false, () -> solve(false, true), "自动处理本页", true,
                        () -> beginAuto());
                Ui.gap(panel, 7);
                if (PlaybackCapture.running || PlaybackCapture.finishing) {
                    pair(panel, "播放本页音频", false, this::play,
                            PlaybackCapture.running ? "停止并转写" : "查看转写状态", false, this::stopListening);
                } else {
                    pair(panel, "自动听力", false, this::beginListening, "扫描任务", false, this::scan);
                }
                Ui.gap(panel, 7);
                pair(panel, "连续处理并提交", true, () -> beginCourse(false), "只监测", false, () -> beginCourse(true));
                Ui.gap(panel, 7);
                panel.addView(Ui.button(this, "自动朗读", false, this::beginReadOnly));
                Ui.gap(panel, 7);
                String transcript = store.transcript();
                pair(panel, transcript.isEmpty() ? "听力与口语" : attach ? "✓ 使用原文" : "附上听力原文",
                        false, () -> {
                            if (store.transcript().isEmpty()) openCompanion("media");
                            else {
                                attach = !attach;
                                render();
                            }
                        }, "打开设置", false, () -> openCompanion("settings"));
            }
            body = panel;
        }
        try {
            overlay.show(body, mode, course != null || reading != null || following != null);
        } catch (RuntimeException e) {
            Log.w("CampusPocket", "Accessibility overlay could not be shown", e);
            remove();
            if (testing) {
                testing = false;
                Toast.makeText(this, "系统未能显示测试浮窗。请在无障碍设置重新开启校园随练。", Toast.LENGTH_LONG).show();
            }
        }
    }

    private void pair(LinearLayout l, String left, boolean lp, Runnable la, String right, boolean rp, Runnable ra) {
        LinearLayout row = Ui.row(this);
        Ui.stretch(row, Ui.button(this, left, lp, la));
        Ui.spaceX(row, 8);
        Ui.stretch(row, Ui.button(this, right, rp, ra));
        l.addView(row);
    }

    private void beginAuto() {
        seen.clear();
        solve(true, true);
    }

    private void beginCourse(boolean observeOnly) {
        if (!observeOnly && (PlaybackCapture.running || PlaybackCapture.finishing)) {
            fail("上一段音频仍在采集或转写。完成后可启动连续处理。");
            return;
        }
        Capture cap = capture();
        if (cap == null || cap.coursePage().kind == CoursePage.Kind.BLOCKED) {
            fail("请先打开 U校园课程目录或普通练习页。");
            return;
        }
        seen.clear();
        generation++;
        staged = null;
        applied = false;
        busy = true;
        expanded = true;
        course = new CourseRun(this, ui, cap.packageName, observeOnly);
        store.event("连续处理", "开始监测当前课程");
        course.begin();
    }

    void courseNotice(int completed, String type, String prompt, String message) {
        busy = true;
        notice = "已确认 " + completed + " 项 · " + type
                + (prompt.isEmpty() ? "" : "\n要求 " + prompt)
                + (message.isEmpty() ? "" : "\n" + message);
        render();
    }

    void courseEvent(String message) {
        store.event("连续处理", message);
    }

    void endCourse(CourseRun ended, int count, String reason) {
        if (course != ended) return;
        course = null;
        if (reading != null) {
            reading.cancel(true);
            reading = null;
        }
        if (following != null) {
            following.cancel();
            following = null;
        }
        generation++;
        busy = false;
        staged = null;
        applied = false;
        notice = "连续处理已结束 · 已确认 " + count + " 项\n" + reason;
        courseEvent(notice.replace('\n', ' '));
        PlaybackCapture.closeCourse();
        render();
    }

    void solveCourse(String transcript) {
        solve(true, false, transcript);
    }

    private void beginReadOnly() {
        seen.clear();
        if (!routeReading(capture(), true)) {
            fail("请打开含有可见原文和录音按钮的朗读题。");
        }
    }

    private void solve(boolean auto, boolean initial) {
        solve(auto, initial, null);
    }

    private void solve(boolean auto, boolean initial, String courseAudio) {
        Capture cap = capture();
        boolean batch = courseAudio != null;
        if (auto && !batch && routeReading(cap, initial)) return;
        Store.Config c = store.config();
        if (!c.ready()) {
            fail("请在设置中填写文本模型及 API 密钥。");
            return;
        }
        if (cap == null || cap.page.blocked || !cap.page.exercise) {
            fail("没有识别到可处理的练习。请进入题目页，图片题也可以手动输入题目。");
            return;
        }
        String current = cap.page.automationKey();
        if (auto && !batch && (!seen.add(current) || seen.size() > 10)) {
            fail("本次自动处理已停止。");
            return;
        }
        int ticket = ++generation;
        busy = true;
        applied = false;
        staged = null;
        signature = cap.page.fingerprint();
        stagedWindow = cap.window;
        notice = initial ? "正在生成答案…" : "已切到下一题，正在生成答案…";
        render();
        String input = cap.page.modelInput();
        boolean openOral = cap.page.groups.isEmpty() && cap.page.reading != null
                && cap.page.reading.answerRecording();
        boolean pendingTranscript = !batch && store.prefs.getBoolean("attach_once", false);
        String audio = batch ? courseAudio : (attach || pendingTranscript ? store.transcript() : "");
        ModelClient.WORK.execute(() -> {
            try {
                ModelClient.Solution result = openOral ? ModelClient.oralSolution(c, cap.page.reading.question, audio)
                        : ModelClient.solve(c, input, audio);
                ui.post(() -> {
                    if (ticket != generation || !foreground) return;
                    if (pendingTranscript && !audio.isEmpty() && audio.equals(store.transcript())) {
                        store.prefs.edit().putBoolean("attach_once", false).apply();
                    }
                    staged = result;
                    busy = false;
                    notice = openOral ? "英文回答已生成" : "解析已生成";
                    store.event("练习", "已生成本页解析");
                    if (auto) {
                        if (!batch && !result.speech.isEmpty() && store.prefs.getBoolean("auto_speak", false)) speak(result.speech);
                        if (!applyStaged()) return;
                        if (batch || c.autoSubmit) {
                            busy = true;
                            render();
                            ui.postDelayed(() -> autoSubmit(ticket, c.autoNext), 650);
                            return;
                        }
                    }
                    render();
                });
            } catch (Exception e) {
                ui.post(() -> {
                    if (ticket == generation) fail(ModelClient.message(e));
                });
            }
        });
    }

    private boolean routeReading(Capture cap, boolean allowDirectory) {
        if (cap == null || cap.page.reading == null || !cap.page.groups.isEmpty()) return false;
        ReadAloudPage state = cap.page.reading;
        if (state.cards != null && !cap.page.result) {
            if (state.cards.current == null) {
                fail("已识别范例跟读，但 U校园未提供可定位的句子和录音控件。请展开要跟读的句子。");
            } else {
                following = new FollowAlongRun(this, ui, cap);
                following.begin(cap);
            }
            return true;
        }
        if (!state.direction && state.pending.isEmpty() && !state.answerRecording()) return false;
        if (cap.page.result || state.saved) {
            fail("这道朗读题已有录音或结果。可在 U校园查看录音，或手动开启下一次练习。");
        } else if (!state.stop.isEmpty()) {
            fail("U校园已有录音在进行。请先结束那次录音。");
        } else if ((!state.passage.isEmpty() || !state.question.isEmpty()) && state.start.size() == 1) {
            startReading(cap);
        } else if (allowDirectory && !cap.page.exercise && state.pending.size() == 1) {
            openReadingTask(cap);
        } else if (state.pending.size() > 1) {
            fail("目录里有多个待完成朗读题。请先打开要做的那一题。");
        } else if (state.passage.isEmpty()) {
            fail("识别到朗读要求。请打开显示可读原文的题目页。");
        } else {
            fail("未找到唯一的开始录音按钮。请打开单道朗读题再试。");
        }
        return true;
    }

    private void openReadingTask(Capture cap) {
        if (!press(cap, cap.page.reading.pending)) {
            fail("未能从目录打开朗读任务。");
            return;
        }
        int ticket = ++generation;
        long deadline = android.os.SystemClock.uptimeMillis() + 12000;
        busy = true;
        notice = "已打开待完成朗读任务，正在识别题目…";
        render();
        ui.postDelayed(() -> waitForReadingTask(ticket, cap.packageName, deadline), 450);
    }

    private void waitForReadingTask(int ticket, String target, long deadline) {
        if (ticket != generation || !foreground || !busy) return;
        Capture cap = capture();
        if (cap != null && target.equals(cap.packageName) && cap.page.reading != null
                && (!cap.page.reading.passage.isEmpty() || !cap.page.reading.start.isEmpty())) {
            if (!routeReading(cap, false)) fail("任务页已打开，请在 U校园查看题目。");
        } else if (android.os.SystemClock.uptimeMillis() < deadline) {
            ui.postDelayed(() -> waitForReadingTask(ticket, target, deadline), 650);
        } else {
            fail("任务页还没有显示可识别的朗读原文与录音按钮。");
        }
    }

    private void startReading(Capture cap) {
        if (!seen.add(cap.page.automationKey()) || seen.size() > 10) {
            fail("这道朗读题已处理过，本轮自动处理已停止。");
            return;
        }
        prepareReading(cap, course != null || store.config().autoSubmit);
    }

    void prepareReading(Capture cap, boolean autoSubmit) {
        if (speaker != null) {
            speaker.shutdown();
            speaker = null;
        }
        generation++;
        stagedWindow = cap.window;
        busy = true;
        staged = null;
        applied = false;
        notice = cap.page.reading.question.isEmpty() ? "正在准备当前句的录音和语音…"
                : "正在为当前口语题生成回答…";
        reading = new ReadAloudRun(this, ui, cap, autoSubmit, store.config(), store.voice());
        render();
        reading.start();
    }

    void followNotice(String text) {
        busy = true;
        notice = text;
        render();
    }

    void followFinished(FollowAlongRun run, boolean advanced, String text) {
        if (following != run) return;
        following = null;
        busy = false;
        notice = text;
        if (course != null) {
            course.oralFinished(advanced, text);
        } else {
            Capture cap = advanced ? capture() : null;
            if (cap == null || cap.page.reading == null || cap.page.reading.question.isEmpty()
                    || !routeReading(cap, false)) render();
        }
    }

    boolean startCourseOral(Capture cap) { return routeReading(cap, false); }

    void readProgress(ReadAloudRun run, String status) {
        if (reading != run) return;
        notice = status;
        render();
    }

    void readFinished(ReadAloudRun run, ReadAloudRun.Outcome outcome, String status) {
        if (reading != run) return;
        reading = null;
        busy = false;
        notice = status;
        if (following != null) {
            following.recorded(outcome, status);
            return;
        }
        if (course != null) {
            course.oralFinished(outcome == ReadAloudRun.Outcome.COMPLETE, status);
            return;
        }
        if (outcome != ReadAloudRun.Outcome.STOPPED) store.event("朗读", status);
        if (outcome == ReadAloudRun.Outcome.COMPLETE) {
            store.addSolved(1);
            if (store.config().autoNext && seen.size() < 10) {
                busy = true;
                int ticket = generation;
                ui.postDelayed(() -> waitNext(ticket, 0), 700);
            }
        }
        render();
    }

    private void stopProcessing() {
        if (reading != null) {
            reading.cancel(true);
            reading = null;
        }
        if (following != null) {
            following.cancel();
            following = null;
        }
        if (course != null) {
            course.end("已手动停止。");
            return;
        }
        generation++;
        busy = false;
        notice = "已停止后续操作。";
        render();
    }

    private void fail(String reason) {
        if (course != null) {
            course.end(reason);
            return;
        }
        notice = reason;
        busy = false;
        render();
    }

    private void applyManually() {
        if (applyStaged()) render();
    }

    private boolean applyStaged() {
        Capture cap = capture();
        if (!matches(cap)) {
            fail("练习页面已变化。请重新识别后再回填。");
            return false;
        }
        if (cap.page.groups.isEmpty()) {
            fail("此页只有解析，未检测到可写入的答题控件。");
            return false;
        }
        Map<Integer, ModelClient.Answer> answers = new HashMap<>();
        for (ModelClient.Answer a : staged.answers) {
            if (answers.put(a.id, a) != null) {
                fail("模型返回了重复编号，已停止回填。");
                return false;
            }
        }
        if (answers.size() != cap.page.groups.size()) {
            fail("答案数量与页面控件不一致，可查看解析后自行作答。");
            return false;
        }
        List<Write> writes = new ArrayList<>();
        for (PageModel.Group group : cap.page.groups) {
            ModelClient.Answer answer = answers.get(group.index);
            if (answer == null || !"ready".equals(answer.status) || group.ambiguous) {
                fail("这页需要原文或人工核对，答案已保留在解析中。");
                return false;
            }
            if (!group.choices.isEmpty()) {
                if (!answer.fields.isEmpty() || answer.choices.isEmpty() || (!group.multiple && answer.choices.size() != 1)
                        || !group.choices.keySet().containsAll(answer.choices)) {
                    fail("选项无法与页面对应，已停止回填。");
                    return false;
                }
                for (Map.Entry<String, PageModel.Line> row : group.choices.entrySet()) {
                    PageModel.Line line = row.getValue();
                    boolean desired = answer.choices.contains(row.getKey());
                    if (desired && !line.checked) writes.add(new Write(line.id, null));
                    if (!desired && line.checked) {
                        fail("已存在与你的答案不同的选项，请先手动核对。");
                        return false;
                    }
                }
            } else {
                if (!answer.choices.isEmpty() || answer.fields.size() != group.fields.size()) {
                    fail("文本答案数量与输入框不一致，已停止回填。");
                    return false;
                }
                for (int i = 0; i < group.fields.size(); i++) {
                    PageModel.Line field = group.fields.get(i);
                    String value = answer.fields.get(i);
                    if (field.filled || value.isEmpty() || value.length() > 7000) {
                        fail("页面已有输入或模型答案为空，请核对后填写。");
                        return false;
                    }
                    writes.add(new Write(field.id, value));
                }
            }
        }
        int count = 0;
        for (Write w : writes) {
            AccessibilityNodeInfo node = cap.actions.get(w.id);
            if (!active(cap.window) || node == null || !node.refresh() || !node.isVisibleToUser()) {
                fail("页面在回填时变化，已填写 " + count + " 项，请检查。");
                return false;
            }
            boolean success;
            if (w.text == null) {
                success = node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            } else {
                Bundle arguments = new Bundle();
                arguments.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, w.text);
                success = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments);
            }
            if (!success) {
                fail("有控件未接受填写，已填写 " + count + " 项，请检查。");
                return false;
            }
            count++;
        }
        applied = true;
        store.addSolved(cap.page.groups.size());
        store.event("回填", "本页已回填 " + count + " 项");
        notice = "已回填 " + count + " 项，可在 U校园核对。";
        return true;
    }

    private void submitManually() {
        Capture cap = capture();
        if (!matches(cap) || !verified(cap) || !press(cap, cap.page.submit)) {
            fail("未确认所有答案已生效，或没有唯一提交按钮，请在 U校园核对。");
            return;
        }
        submitted();
        render();
    }

    private void autoSubmit(int ticket, boolean next) {
        if (ticket != generation || !foreground || !busy) return;
        Capture cap = capture();
        if (!matches(cap) || !verified(cap) || !press(cap, cap.page.submit)) {
            fail("未确认所有答案已生效，或提交按钮有变化。请在 U校园核对。");
            return;
        }
        submitted();
        if (course != null) {
            course.submitted();
        } else if (next && seen.size() < 10) {
            busy = true;
            notice = "等待结果页，确认后继续…";
            render();
            ui.postDelayed(() -> waitNext(ticket, 0), 700);
        } else {
            render();
        }
    }

    private boolean verified(Capture cap) {
        for (PageModel.Group group : cap.page.groups) {
            ModelClient.Answer answer = null;
            for (ModelClient.Answer item : staged.answers) if (item.id == group.index) answer = item;
            if (answer == null) return false;
            for (Map.Entry<String, PageModel.Line> row : group.choices.entrySet()) {
                if (row.getValue().checked != answer.choices.contains(row.getKey())) return false;
            }
            for (int i = 0; i < group.fields.size(); i++) {
                AccessibilityNodeInfo node = cap.actions.get(group.fields.get(i).id);
                if (node == null || !node.refresh() || !PageModel.clean(node.getText()).equals(
                        PageModel.clean(answer.fields.get(i)))) return false;
            }
        }
        return true;
    }

    private void submitted() {
        busy = false;
        applied = false;
        staged = null;
        store.event("提交", "已点击 U校园的提交按钮");
        notice = "已点击提交，请留意 U校园提示。";
    }

    private void waitNext(int ticket, int tries) {
        if (ticket != generation || !foreground || !busy) return;
        Capture cap = capture();
        if (cap != null && cap.window == stagedWindow && !cap.page.blocked && cap.page.result
                && press(cap, cap.page.next)) {
            // A transcript belongs to the previous page unless the user attaches it again.
            attach = false;
            ui.postDelayed(() -> waitExercise(ticket, 0), 650);
        } else if (tries < 7) {
            ui.postDelayed(() -> waitNext(ticket, tries + 1), 700);
        } else {
            fail("已提交。页面未出现明确的结果和下一题按钮，连续处理已结束。");
        }
    }

    private void waitExercise(int ticket, int tries) {
        if (ticket != generation || !foreground || !busy) return;
        Capture cap = capture();
        if (cap != null && cap.page.exercise && !cap.page.blocked && !seen.contains(cap.page.automationKey())) {
            solve(true, false);
        } else if (tries < 7) {
            ui.postDelayed(() -> waitExercise(ticket, tries + 1), 650);
        } else {
            fail("没有识别到下一道练习，连续处理已结束。");
        }
    }

    private boolean matches(Capture cap) {
        return staged != null && cap != null && cap.window == stagedWindow && cap.page.exercise
                && !cap.page.blocked && cap.page.fingerprint().equals(signature);
    }

    private boolean active(int window) {
        AccessibilityNodeInfo root = applicationRoot();
        return isCampus(root) && root.getWindowId() == window;
    }

    boolean press(Capture cap, List<PageModel.Line> buttons) {
        if (cap.page.blocked || buttons.size() != 1 || !active(cap.window)) return false;
        AccessibilityNodeInfo n = cap.actions.get(buttons.get(0).id);
        return n != null && n.refresh() && n.isEnabled() && n.isVisibleToUser()
                && n.performAction(AccessibilityNodeInfo.ACTION_CLICK);
    }

    boolean oralPress(Capture cap, List<PageModel.Line> buttons) {
        if (cap.page.reading.cards == null) return press(cap, buttons);
        return buttons.size() == 1 && followTap(cap, buttons.get(0));
    }

    boolean followTap(Capture cap, PageModel.Line label) {
        if (cap.page.blocked || cap.page.reading == null || cap.page.reading.cards == null
                || label.bounds == null || !label.enabled || !active(cap.window)) return false;
        boolean shared = label.click && cap.lines.stream().anyMatch(line -> line.id == label.id && line.bounds != null
                && !line.text.equals(label.text) && (ReadAloudPage.recordingControl(line.text)
                || ReadAloudPage.replayControl(line.text) || PageModel.isPlayback(line.text)
                || ReadAloudPage.sentence(line.text)));
        if (label.click && !shared && press(cap, java.util.Collections.singletonList(label))) return true;
        int x = label.bounds.x(), y = label.bounds.y();
        if (overlay.covers(x, y)) {
            expanded = false;
            covered = true;
            render();
            if (overlay.covers(x, y)) return false;
        }
        return tapCampus(cap, x, y);
    }

    boolean pressFollowMicrophone(Capture cap, PageModel.Line label, boolean touch) {
        if (cap.page.blocked || !label.enabled) return false;
        PageModel.Bounds icon = trainingMicrophoneIcon(cap, label);
        if (icon != null) return trainingTap(cap, icon);
        if (!label.localControl) return false;
        return touch ? trainingTap(cap, label.bounds) : trainingClick(cap, label);
    }

    private PageModel.Bounds trainingMicrophoneIcon(Capture cap, PageModel.Line label) {
        if (cap.tree == null || label == null) return null;
        List<USpeakingFollowPage.MicBox> boxes = new ArrayList<>();
        for (int i = 0; i < cap.tree.length(); i++) {
            JSONObject node = cap.tree.optJSONObject(i);
            if (node == null || node.optInt("parent", -1) != label.parent || !node.optBoolean("visible")
                    || !node.optBoolean("enabled") || !node.optString("text").isEmpty()
                    || !node.optString("contentDescription").isEmpty()
                    || !node.optString("class").equals("android.view.View")) continue;
            JSONArray rect = node.optJSONArray("bounds");
            if (rect != null && rect.length() == 4) {
                boxes.add(new USpeakingFollowPage.MicBox(label.parent,
                        new PageModel.Bounds(rect.optInt(0), rect.optInt(1), rect.optInt(2), rect.optInt(3))));
            }
        }
        return USpeakingFollowPage.microphoneIcon(label, boxes);
    }

    boolean trainingWindowActive(int window) { return active(window); }

    boolean trainingBack(Capture cap) {
        return !cap.page.blocked && active(cap.window) && performGlobalAction(GLOBAL_ACTION_BACK);
    }

    void captureTrainingMarkers(Capture cap, TrainingPage page, java.util.function.Consumer<TrainingMarkers.Status[]> done) {
        if (page.kind != TrainingPage.Kind.MENU) { done.accept(null); return; }
        int[] rows = new int[TrainingPage.SECTIONS.length];
        for (int i = 0; i < rows.length; i++) {
            PageModel.Line line = page.menuEntry(TrainingPage.SECTIONS[i]);
            if (line == null || line.bounds == null) { done.accept(null); return; }
            rows[i] = line.bounds.y();
        }
        readTrainingWindow(cap, (bitmap, target) -> {
            int width = bitmap.getWidth(), height = bitmap.getHeight();
            int strip = Math.min(width, Math.round(width * .18f));
            int[] pixels = new int[strip * height];
            bitmap.getPixels(pixels, 0, strip, 0, 0, strip, height);
            TrainingMarkers.Status[] found = new TrainingMarkers.Status[rows.length];
            for (int i = 0; i < rows.length; i++) {
                int y = Math.round((rows[i] - target.top) * (height / (float) target.height()));
                found[i] = TrainingMarkers.inRow((x, py) -> pixels[py * strip + x], width, height, y);
            }
            return found;
        }, done);
    }

    void captureTrainingTutorialOkay(Capture cap, java.util.function.Consumer<PageModel.Bounds> done) {
        readTrainingWindow(cap, (bitmap, target) -> {
            int width = bitmap.getWidth(), height = bitmap.getHeight();
            PageModel.Bounds button = TrainingVisualControls.tutorialOkay(bitmap::getPixel, width, height);
            if (button == null) return null;
            float sx = target.width() / (float) width, sy = target.height() / (float) height;
            return new PageModel.Bounds(target.left + Math.round(button.left * sx),
                    target.top + Math.round(button.top * sy), target.left + Math.round(button.right * sx),
                    target.top + Math.round(button.bottom * sy));
        }, done);
    }

    void captureTrainingControl(Capture cap, boolean taskCard, java.util.function.Consumer<PageModel.Bounds> done) {
        readTrainingWindow(cap, (bitmap, target) -> {
            int width = bitmap.getWidth(), height = bitmap.getHeight();
            PageModel.Bounds box = taskCard ? TrainingVisualControls.taskCardNext(bitmap::getPixel, width, height)
                    : TrainingVisualControls.pressToTalk(bitmap::getPixel, width, height);
            if (box == null) return null;
            float sx = target.width() / (float) width, sy = target.height() / (float) height;
            return new PageModel.Bounds(target.left + Math.round(box.left * sx), target.top + Math.round(box.top * sy),
                    target.left + Math.round(box.right * sx), target.top + Math.round(box.bottom * sy));
        }, done);
    }

    private interface TrainingWindowRead<T> { T read(Bitmap bitmap, Rect target); }

    private <T> void readTrainingWindow(Capture cap, TrainingWindowRead<T> read, java.util.function.Consumer<T> done) {
        if (android.os.Build.VERSION.SDK_INT < 34 || cap.page.blocked || !active(cap.window)) {
            done.accept(null);
            return;
        }
        Rect target = new Rect();
        for (AccessibilityWindowInfo window : getWindows()) {
            if (window.getId() == cap.window && window.getType() == AccessibilityWindowInfo.TYPE_APPLICATION) {
                window.getBoundsInScreen(target);
                break;
            }
        }
        if (target.isEmpty()) { done.accept(null); return; }
        try {
            takeScreenshotOfWindow(cap.window, getMainExecutor(), new TakeScreenshotCallback() {
                @Override public void onSuccess(ScreenshotResult result) {
                    T found = null;
                    Bitmap hardware = null, bitmap = null;
                    try (HardwareBuffer buffer = result.getHardwareBuffer()) {
                        if (active(cap.window)) {
                            hardware = Bitmap.wrapHardwareBuffer(buffer, result.getColorSpace());
                            if (hardware != null) bitmap = hardware.copy(Bitmap.Config.ARGB_8888, false);
                            if (bitmap != null) found = read.read(bitmap, target);
                        }
                    } catch (RuntimeException ignored) {
                        found = null;
                    } finally {
                        if (bitmap != null) bitmap.recycle();
                        if (hardware != null) hardware.recycle();
                    }
                    done.accept(found);
                }
                @Override public void onFailure(int errorCode) { done.accept(null); }
            });
        } catch (RuntimeException error) {
            done.accept(null);
        }
    }

    PageModel.Bounds trainingHoldBounds(Capture cap, PageModel.Line label) {
        if (label == null || label.bounds == null) return null;
        if (cap.tree == null) return label.bounds;
        Rect window = new Rect();
        AccessibilityNodeInfo root = applicationRoot();
        if (!isCampus(root) || root.getWindowId() != cap.window) return null;
        root.getBoundsInScreen(window);
        PageModel.Bounds best = label.bounds;
        for (int i = 0; i < cap.tree.length(); i++) {
            JSONObject node = cap.tree.optJSONObject(i);
            if (node == null || !node.optBoolean("visible") || !node.optBoolean("enabled")
                    || !(node.optBoolean("clickable") || node.optBoolean("longClickable"))) continue;
            JSONArray rect = node.optJSONArray("bounds");
            if (rect == null || rect.length() != 4) continue;
            PageModel.Bounds box = new PageModel.Bounds(rect.optInt(0), rect.optInt(1), rect.optInt(2), rect.optInt(3));
            int height = box.bottom - box.top;
            if (box.right - box.left < window.width() / 2 || height < window.width() / 20
                    || height > window.width() / 4 || box.y() < window.top + window.height() * .72
                    || label.bounds.x() < box.left || label.bounds.x() > box.right
                    || label.bounds.y() < box.top || label.bounds.y() > box.bottom) continue;
            if (box.right - box.left > best.right - best.left) best = box;
        }
        return best;
    }

    boolean trainingPoint(Capture cap, int x, int y) {
        if (overlay.covers(x, y) && observer != null) observer.collapseForControl();
        AccessibilityNodeInfo root = applicationRoot();
        if (!isCampus(root) || root.getWindowId() != cap.window || overlay.covers(x, y)) return false;
        Rect bounds = new Rect();
        root.getBoundsInScreen(bounds);
        return bounds.contains(x, y);
    }

    boolean trainingClick(Capture cap, PageModel.Line label) {
        if (label == null || cap.page.blocked || !label.enabled) return false;
        if (label.localControl && press(cap, java.util.Collections.singletonList(label))) return true;
        return trainingTap(cap, label.bounds);
    }

    /**
     * Last-resort, single-use tap for the known full-screen "阅读任务卡" panel.
     * The panel in the supplied screenshot places its large Next action around
     * 79% of the application window height. Caller must verify the task-card
     * title first; this is NEVER a general blind-tapping routine.
     */
    boolean trainingTutorialOkayFallback(Capture cap) {
        if (cap == null || cap.page.blocked || !active(cap.window)) return false;
        AccessibilityNodeInfo root = applicationRoot();
        if (!isCampus(root) || root.getWindowId() != cap.window) return false;
        Rect rect = new Rect();
        root.getBoundsInScreen(rect);
        if (rect.isEmpty() || rect.width() < 300 || rect.height() < 600) return false;
        // Used only after the accessibility tree explicitly exposed the swipe tutorial text.
        int x = rect.left + Math.round(rect.width() * .279f);
        int y = rect.top + Math.round(rect.height() * .765f);
        return trainingPoint(cap, x, y) && tapCampus(cap, x, y);
    }

    boolean trainingTaskCardNextFallback(Capture cap) {
        if (cap == null || cap.page.blocked || !active(cap.window)) return false;
        AccessibilityNodeInfo root = applicationRoot();
        if (!isCampus(root) || root.getWindowId() != cap.window) return false;
        Rect rect = new Rect();
        root.getBoundsInScreen(rect);
        if (rect.isEmpty() || rect.width() < 300 || rect.height() < 600) return false;
        int x = rect.centerX();
        int y = rect.top + Math.round(rect.height() * .79f);
        return trainingPoint(cap, x, y) && tapCampus(cap, x, y);
    }

    boolean trainingTap(Capture cap, PageModel.Bounds bounds) {
        if (bounds == null || cap.page.blocked) return false;
        int x = bounds.x(), y = bounds.y();
        return trainingPoint(cap, x, y) && tapCampus(cap, x, y);
    }

    boolean trainingStop(Capture cap, TrainingPage page, boolean touch) {
        PageModel.Line label = page.stopRecording();
        if (label != null) return touch ? trainingTap(cap, label.bounds) : trainingClick(cap, label);
        PageModel.Bounds icon = trainingMicrophoneIcon(cap, page.recordingHint());
        return icon != null && trainingTap(cap, icon);
    }

    PageModel.Line adaptiveTrainingButton(Capture cap, TrainingPage page) {
        PageModel.Line label = page.homeStart();
        if (label != null || cap.tree == null || page.kind != TrainingPage.Kind.HOME) return label;
        PageModel.Line section = page.adaptiveHeading(), history = page.historyHeading();
        JSONObject root = cap.tree.optJSONObject(0);
        JSONArray frame = root == null ? null : root.optJSONArray("bounds");
        if (section == null || history == null || frame == null) return null;
        int width = frame.optInt(2) - frame.optInt(0);
        int height = frame.optInt(3) - frame.optInt(1);
        PageModel.Bounds hit = null;
        for (int i = 0; i < cap.tree.length(); i++) {
            JSONObject node = cap.tree.optJSONObject(i);
            if (node == null || !node.optBoolean("visible") || !node.optBoolean("enabled")
                    || !node.optBoolean("clickable") || !node.optString("class").endsWith("Button")) continue;
            String name = (node.optString("text") + node.optString("contentDescription")).trim();
            if (!name.isEmpty() && !TrainingPage.launchControl(name)) continue;
            JSONArray rect = node.optJSONArray("bounds");
            if (rect == null || rect.length() != 4) continue;
            PageModel.Bounds box = new PageModel.Bounds(rect.optInt(0), rect.optInt(1), rect.optInt(2), rect.optInt(3));
            if (box.top <= section.bounds.bottom || box.bottom >= history.bounds.top
                    || box.x() < frame.optInt(0) + width / 2 || box.right - box.left < width / 9
                    || box.right - box.left > width / 2 || box.bottom - box.top < height / 60
                    || box.bottom - box.top > height / 7) continue;
            if (hit != null) return null;
            hit = box;
        }
        return hit == null ? null : new PageModel.Line(0, "开始训练", false, false, false, false, false, true, hit);
    }

    PageModel.Bounds trainingCardViewport(Capture cap, SpeakingCards cards) {
        Rect viewport = new Rect();
        AccessibilityNodeInfo root = applicationRoot();
        if (!isCampus(root) || root.getWindowId() != cap.window) return null;
        root.getBoundsInScreen(viewport);
        Rect scope = new Rect();
        int bestHeight = viewport.height();
        for (AccessibilityNodeInfo node : cap.scrollable) {
            node.getBoundsInScreen(scope);
            if (scope.width() < viewport.width() / 2 || scope.height() < viewport.height() / 4
                    || scope.height() > bestHeight
                    || cards.sentences.stream().noneMatch(s -> scope.contains(s.bounds.x(), s.bounds.y()))) continue;
            bestHeight = scope.height();
            viewport.intersect(scope);
        }
        for (PageModel.Line line : cap.lines) {
            if (line.bounds != null && ReadAloudPage.followDirection(line.text)) {
                viewport.top = Math.max(viewport.top, line.bounds.bottom);
                break;
            }
        }
        return viewport.isEmpty() ? null : new PageModel.Bounds(viewport.left, viewport.top, viewport.right, viewport.bottom);
    }

    boolean swipeTrainingCards(Capture cap, PageModel.Bounds viewport, int distance, boolean alternate,
                               java.util.function.Consumer<Boolean> done) {
        int height = viewport.bottom - viewport.top;
        int shift = Math.min(Math.abs(distance), height * 54 / 100);
        if (distance == 0) return false;
        if (shift < Ui.dp(this, 26)) {
            boolean moved = scrollFollowCards(cap, viewport, distance > 0);
            if (moved) done.accept(true);
            return moved;
        }
        int x = viewport.left + (viewport.right - viewport.left) * (alternate ? 43 : 61) / 100;
        int center = viewport.y();
        int start = center + Integer.signum(distance) * shift / 2;
        int end = start - Integer.signum(distance) * shift;
        if (trainingPoint(cap, x, start) && trainingPoint(cap, x, end)) {
            Path path = new Path();
            path.moveTo(x, start);
            path.lineTo(x, end);
            if (dispatchGesture(new GestureDescription.Builder()
                    .addStroke(new GestureDescription.StrokeDescription(path, 0, 520)).build(), new GestureResultCallback() {
                        @Override public void onCompleted(GestureDescription gesture) { done.accept(true); }
                        @Override public void onCancelled(GestureDescription gesture) { done.accept(false); }
                    }, ui)) return true;
        }
        boolean moved = scrollFollowCards(cap, viewport, distance > 0);
        if (moved) done.accept(true);
        return moved;
    }

    private boolean tapCampus(Capture cap, int x, int y) {
        AccessibilityNodeInfo root = applicationRoot();
        Rect bounds = new Rect();
        if (!isCampus(root) || root.getWindowId() != cap.window) return false;
        root.getBoundsInScreen(bounds);
        if (!bounds.contains(x, y)) return false;
        Path point = new Path();
        point.moveTo(x, y);
        return dispatchGesture(new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(point, 0, 75)).build(), null, null);
    }

    boolean scrollFollowCards(Capture cap, PageModel.Line sentence) {
        return sentence.bounds != null && scrollFollowCards(cap, sentence.bounds, true);
    }

    private boolean scrollFollowCards(Capture cap, PageModel.Bounds bounds, boolean forward) {
        if (cap.page.blocked || !active(cap.window)) return false;
        AccessibilityNodeInfo target = null;
        long smallest = Long.MAX_VALUE;
        for (AccessibilityNodeInfo node : cap.scrollable) {
            Rect rect = new Rect();
            node.getBoundsInScreen(rect);
            long area = (long) rect.width() * rect.height();
            if (rect.contains(bounds.x(), bounds.y()) && area < smallest) {
                smallest = area;
                target = node;
            }
        }
        return target != null && target.refresh() && target.isVisibleToUser()
                && target.performAction(forward ? AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                        : AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD);
    }

    private void play() {
        Capture cap = capture();
        if (cap == null || !press(cap, cap.page.play)) {
            fail("没有找到唯一且可操作的播放按钮，可以直接点 U校园播放器。");
            return;
        }
        notice = "已点击播放。";
        expanded = false;
        render();
    }

    private void beginListening() {
        Capture cap = capture();
        if (cap == null || cap.page.blocked || !cap.page.exercise || cap.page.audioKey().isEmpty()) {
            fail("请打开具体听力题；也可以在「听说」里导入音频。");
            return;
        }
        if (!store.config().ready() || !store.config().speechReady()) {
            fail("自动听力需要在设置中配置文本模型和转写模型。");
            return;
        }
        listeningPage = cap.page.audioKey();
        listeningWindow = cap.window;
        store.prefs.edit().remove("capture_signal").apply();
        Intent request = new Intent(this, MainActivity.class).putExtra("tab", "media")
                .putExtra("capture_auto", true).putExtra(CampusApp.EXTRA_PACKAGE, cap.packageName)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(request);
    }

    private void captureProgress(String event) {
        if (course != null && course.listening()) {
            course.audioSignal(event, "ready".equals(event) ? store.transcript()
                    : store.prefs.getString("capture_status", "听力采集失败。"));
            return;
        }
        if (listeningPage == null) return;
        if ("play".equals(event)) {
            startAudio(0);
        } else if ("ready".equals(event) || "error".equals(event) || "cancel".equals(event)) {
            Capture cap = capture();
            boolean same = cap != null && cap.window == listeningWindow && !cap.page.blocked
                    && cap.page.exercise && listeningPage.equals(cap.page.audioKey());
            listeningPage = null;
            if ("ready".equals(event) && same && !busy) {
                expanded = true;
                beginAuto();
            } else if (foreground && !busy) {
                expanded = true;
                fail("cancel".equals(event) ? "已取消采集。" : store.prefs.getString("capture_status", ""));
            }
        }
    }

    void startCourseAudio(Capture cap, int duration) {
        if (android.os.Build.VERSION.SDK_INT < 29 || !store.config().ready() || !store.config().speechReady()) {
            fail("自动听力需要 Android 10、文本模型和语音转写模型。");
            return;
        }
        if (duration > 240) {
            fail("本题的听力材料超过当前单段采集上限。请先提供转写原文。");
            return;
        }
        store.prefs.edit().remove("capture_signal").apply();
        if (PlaybackCapture.courseAuthorized(cap.packageName)) {
            startService(new Intent(this, PlaybackCapture.class).setAction("course_next")
                    .putExtra(CampusApp.EXTRA_PACKAGE, cap.packageName).putExtra("duration", duration));
        } else if (PlaybackCapture.running || PlaybackCapture.finishing) {
            fail("此前的音频任务还在进行。");
        } else {
            Intent request = new Intent(this, MainActivity.class).putExtra("tab", "media")
                    .putExtra("capture_auto", true).putExtra("course_audio", true)
                    .putExtra("duration", duration)
                    .putExtra(CampusApp.EXTRA_PACKAGE, cap.packageName)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            startActivity(request);
        }
    }

    void finishCourseAudio() {
        if (PlaybackCapture.running && PlaybackCapture.courseActive()) {
            startService(new Intent(this, PlaybackCapture.class).setAction("stop"));
        }
    }

    private void startAudio(int tries) {
        if (listeningPage == null || !PlaybackCapture.running) return;
        Capture cap = capture();
        if (cap != null && cap.window == listeningWindow && cap.page.exercise
                && listeningPage.equals(cap.page.audioKey()) && press(cap, cap.page.play)) {
            if (foreground) {
                expanded = false;
                render();
            }
        } else if (tries < 12) {
            ui.postDelayed(() -> startAudio(tries + 1), 350);
        } else if (foreground) {
            expanded = true;
            fail("音频采集已开启，请手动点 U校园的播放键。");
        }
    }

    private void stopListening() {
        if (PlaybackCapture.running) {
            startService(new Intent(this, PlaybackCapture.class).setAction("stop"));
            fail("正在结束采集并转写。转写完成后，原文会用于下一次处理。");
        } else {
            fail(store.prefs.getString("capture_status", "转写处理中…"));
        }
    }

    private void scan() {
        Capture cap = capture();
        if (cap == null || cap.page.blocked || cap.page.exercise || cap.page.tasks.isEmpty()) {
            fail("未发现可识别的任务。进入课程目录、展开单元后再扫描。");
            return;
        }
        store.addTasks(cap.page.tasks);
        store.event("任务", "扫描到 " + cap.page.tasks.size() + " 项可见任务");
        notice = "已收录 " + cap.page.tasks.size() + " 项可见任务，去「任务」查看。";
        render();
    }

    private android.speech.tts.TextToSpeech speaker;

    private void speak(String text) {
        if (speaker != null) speaker.shutdown();
        speaker = new android.speech.tts.TextToSpeech(this, ready -> ui.post(() -> {
            if (speaker == null || !foreground) return;
            if (ready != android.speech.tts.TextToSpeech.SUCCESS
                    || speaker.setLanguage(java.util.Locale.US) < 0) {
                fail("请在系统文字转语音设置中安装英语语音。");
                return;
            }
            speaker.speak(text, android.speech.tts.TextToSpeech.QUEUE_FLUSH, null, "campus-example");
        }));
    }

    private void copyResult() {
        ClipboardManager clipboard = getSystemService(ClipboardManager.class);
        if (staged != null && clipboard != null) {
            clipboard.setPrimaryClip(ClipData.newPlainText("练习解析", staged.display()));
            notice = "已复制解析";
            render();
        }
    }

    private void openCompanion(String tab) {
        Intent intent = new Intent(this, MainActivity.class).putExtra("tab", tab)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(intent);
    }

    private static final class Write {
        final int id;
        final String text;
        Write(int id, String text) { this.id = id; this.text = text; }
    }

    static final class Capture {
        final PageModel.Page page;
        final Map<Integer, AccessibilityNodeInfo> actions;
        final List<PageModel.Line> lines;
        final List<AccessibilityNodeInfo> scrollable;
        final JSONArray tree;
        final int window;
        final String packageName;
        private CoursePage profile;

        Capture(PageModel.Page page, Map<Integer, AccessibilityNodeInfo> actions, List<PageModel.Line> lines,
                List<AccessibilityNodeInfo> scrollable, JSONArray tree,
                int window, String packageName) {
            this.page = page;
            this.actions = actions;
            this.lines = lines;
            this.scrollable = scrollable;
            this.tree = tree;
            this.window = window;
            this.packageName = packageName;
        }

        CoursePage coursePage() {
            if (profile == null) profile = CoursePage.read(page, lines);
            return profile;
        }
    }

    private static final class Visit {
        final AccessibilityNodeInfo node;
        final int inherited;
        final boolean checked, checkbox;
        final int parent;

        Visit(AccessibilityNodeInfo node, int inherited, boolean checked, boolean checkbox, int parent) {
            this.node = node;
            this.inherited = inherited;
            this.checked = checked;
            this.checkbox = checkbox;
            this.parent = parent;
        }
    }

    Capture capture() { return capture(false); }

    Capture capture(boolean includeTree) {
        AccessibilityNodeInfo root = applicationRoot();
        if (!isCampus(root)) return null;
        String packageName = root.getPackageName().toString();
        Map<Integer, AccessibilityNodeInfo> actions = new HashMap<>();
        Map<Integer, Rect> actionBounds = new HashMap<>();
        ArrayList<PageModel.Line> lines = new ArrayList<>();
        ArrayList<AccessibilityNodeInfo> scrollable = new ArrayList<>();
        JSONArray tree = includeTree ? new JSONArray() : null;
        ArrayDeque<Visit> queue = new ArrayDeque<>();
        queue.push(new Visit(root, 0, false, false, -1));
        int reads = 0;
        int id = 0;
        boolean sensitive = false;
        while (!queue.isEmpty() && reads++ < 1400) {
            Visit visit = queue.pop();
            AccessibilityNodeInfo node = visit.node;
            if (node.isPassword()) sensitive = true;
            boolean visible = node.isVisibleToUser() && !node.isPassword();
            boolean editable = visible && node.isEditable() && node.isEnabled();
            int nodeId = reads - 1;
            if (tree != null) tree.put(diagnosticNode(node, nodeId, visit.parent, visible));
            int action = visit.inherited;
            boolean toggle = visit.checked, checkBox = visit.checkbox;
            if (visible && node.isEnabled() && node.isClickable()) {
                action = ++id;
                actions.put(action, node);
                Rect bounds = new Rect();
                node.getBoundsInScreen(bounds);
                actionBounds.put(action, bounds);
                toggle = node.isChecked() || node.isSelected();
                CharSequence className = node.getClassName();
                checkBox = className != null && className.toString().contains("CheckBox");
            }
            if (visible) {
                if (node.isScrollable() && node.isEnabled()) scrollable.add(node);
                if (editable) {
                    int field = ++id;
                    actions.put(field, node);
                    boolean filled = !node.isShowingHintText() && !PageModel.clean(node.getText()).isEmpty();
                    lines.add(new PageModel.Line(field, "", false, true, filled, false, false));
                } else {
                    String t = PageModel.clean(node.getText());
                    String description = PageModel.clean(node.getContentDescription());
                    if (t.isEmpty()) t = description;
                    Rect box = new Rect();
                    if (!t.isEmpty() || !description.isEmpty()) node.getBoundsInScreen(box);
                    PageModel.Bounds bounds = box.isEmpty() ? null
                            : new PageModel.Bounds(box.left, box.top, box.right, box.bottom);
                    boolean enabled = node.isEnabled();
                    Rect control = actionBounds.get(action);
                    boolean localControl = enabled && (node.isClickable() || bounds != null && control != null
                            && bounds.nearControl(control.left, control.top, control.right, control.bottom));
                    if (!description.equals(t) && (TrainingPage.semanticDescription(description)
                            || action > 0 && (ReadAloudPage.recordingControl(description)
                            || ReadAloudPage.replayControl(description) || CoursePage.mediaControl(description)
                            || description.equalsIgnoreCase("end") || TrainingPage.launchControl(description)
                            || java.util.Arrays.asList(TrainingPage.STOP_LABELS).contains(description)))) {
                        lines.add(new PageModel.Line(action, description, action > 0 && enabled, false, false, false, false,
                                enabled, bounds, visit.parent, localControl));
                    }
                    if (!t.isEmpty() && t.length() <= 7000) {
                        lines.add(new PageModel.Line(action, t, action > 0 && enabled, false, false,
                                toggle || node.isChecked() || node.isSelected(), checkBox, enabled, bounds, visit.parent, localControl));
                    }
                }
            }
            for (int i = node.getChildCount() - 1; i >= 0; i--) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child != null && child.getPackageName() != null && packageName.contentEquals(child.getPackageName())) {
                    queue.push(new Visit(child, action, toggle, checkBox, nodeId));
                }
            }
        }
        return new Capture(PageModel.read(lines, sensitive), actions, lines, scrollable, tree, root.getWindowId(), packageName);
    }

    private JSONObject diagnosticNode(AccessibilityNodeInfo node, int id, int parent, boolean visible) {
        JSONObject item = new JSONObject();
        Rect box = new Rect();
        node.getBoundsInScreen(box);
        boolean textAllowed = visible && !node.isEditable() && !node.isPassword();
        USpeakingDiagnostics.put(item, "id", id);
        USpeakingDiagnostics.put(item, "parent", parent);
        USpeakingDiagnostics.put(item, "class", PageModel.clean(node.getClassName()));
        USpeakingDiagnostics.put(item, "viewId", node.getViewIdResourceName());
        USpeakingDiagnostics.put(item, "visible", visible);
        USpeakingDiagnostics.put(item, "clickable", node.isClickable());
        USpeakingDiagnostics.put(item, "longClickable", node.isLongClickable());
        USpeakingDiagnostics.put(item, "enabled", node.isEnabled());
        USpeakingDiagnostics.put(item, "selected", node.isSelected());
        USpeakingDiagnostics.put(item, "checked", node.isChecked());
        USpeakingDiagnostics.put(item, "scrollable", node.isScrollable());
        USpeakingDiagnostics.put(item, "editable", node.isEditable());
        USpeakingDiagnostics.put(item, "text", textAllowed ? boundedNodeText(node.getText()) : "");
        USpeakingDiagnostics.put(item, "contentDescription", textAllowed ? boundedNodeText(node.getContentDescription()) : "");
        USpeakingDiagnostics.put(item, "bounds", new JSONArray().put(box.left).put(box.top).put(box.right).put(box.bottom));
        return item;
    }

    private static String boundedNodeText(CharSequence value) {
        String text = PageModel.clean(value);
        return text.length() <= 6000 ? text : text.substring(0, 6000);
    }
}
