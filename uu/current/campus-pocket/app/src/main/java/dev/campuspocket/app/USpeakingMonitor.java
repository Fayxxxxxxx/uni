package dev.campuspocket.app;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Build;
import android.os.Handler;
import android.os.SystemClock;
import android.text.TextUtils;
import android.view.Display;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityWindowInfo;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.Locale;

final class USpeakingMonitor {
    private final CampusService service;
    private final Handler ui;
    private final OverlayWindow overlay;
    private final Store store;
    private final USpeakingEngine engine = new USpeakingEngine();
    private final USpeakingReadout.Progress playbackProgress = new USpeakingReadout.Progress();
    private final Runnable read = this::refresh;
    private long scheduledAt, screenshotAt, issueAt, lastClick;
    private String issueText = "", activity = "", previousKey = "", previousPackage = "";
    private String modulePackage = "", targetVersion = "";
    private int moduleWindow = -1;
    private TextView capsule, caption, details, sampling, followButton;
    private boolean expanded, stopped, foreground, autoSample;
    private boolean stoppedOnModelReply;
    private USpeakingEngine.Snapshot snapshot = USpeakingEngine.background();
    private USpeakingDiagnostics.Session session;
    private String message = "";
    private USpeakingFollow follow;
    private USpeakingTraining training;
    private CampusService.Capture trainingSnapshot;
    private long trainingSnapshotAt;
    private long trainingMissingAt = -1;
    private String followNote = "";

    USpeakingMonitor(CampusService service, Handler ui, OverlayWindow overlay, Store store) {
        this.service = service;
        this.ui = ui;
        this.overlay = overlay;
        this.store = store;
    }

    void signal(AccessibilityEvent event) {
        if (CampusApp.supports(event.getPackageName())) {
            int type = event.getEventType();
            if (training != null && (type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
                    || type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED || type == AccessibilityEvent.TYPE_VIEW_SCROLLED
                    || type == AccessibilityEvent.TYPE_WINDOWS_CHANGED || type == AccessibilityEvent.TYPE_VIEW_SELECTED)) {
                trainingSnapshotAt = 0;
                training.pageChanged();
            }
            if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && event.getClassName() != null) {
                String name = event.getClassName().toString();
                if (name.startsWith(event.getPackageName().toString()) || name.endsWith("Activity")) activity = name;
            } else if (type == AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED) {
                for (CharSequence value : event.getText()) {
                    if (value != null && value.length() <= 150) {
                        issueText = value.toString();
                        issueAt = now();
                        break;
                    }
                }
            } else if (type == AccessibilityEvent.TYPE_VIEW_CLICKED && autoSample && session != null
                    && foreground && !snapshot.protectedPage && now() - lastClick >= 350) {
                lastClick = now();
                session.event("manual_click", snapshot.screen.name());
            }
            schedule(130);
        } else if (event.getEventType() == AccessibilityEvent.TYPE_WINDOWS_CHANGED
                || event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            trainingSnapshotAt = 0;
            if (autoSample && foreground && event.getPackageName() != null
                    && event.getPackageName().toString().endsWith(".permissioncontroller")) {
                session.event("system_dialog", "android_permission");
            }
            schedule(130);
        }
    }

    void start() { schedule(0); }

    CampusService.Capture trainingCapture() {
        if (trainingSnapshotAt == 0 || now() - trainingSnapshotAt >= 135) {
            trainingSnapshot = service.capture();
            trainingSnapshotAt = now();
            if (trainingSnapshot != null) trainingMissingAt = -1;
            else if (trainingMissingAt < 0) trainingMissingAt = trainingSnapshotAt;
        }
        return trainingSnapshot;
    }

    boolean trainingCapturePending() { return trainingMissingAt >= 0 && now() - trainingMissingAt < 700; }

    private void refresh() {
        scheduledAt = 0;
        if (stopped) return;
        CampusService.Capture capture = training == null ? service.capture() : trainingCapture();
        if (capture == null) {
            if (training != null && trainingCapturePending()) {
                schedule(150);
                return;
            }
            if (autoSample && foreground) session.event("background", "target_not_in_foreground");
            snapshot = USpeakingEngine.background();
            playbackProgress.reset();
            foreground = false;
            if (follow != null) follow.finish("已离开 U校园，逐句跟读已暂停。");
            if (training != null) training.finish("已离开 U校园，口语训练已暂停。");
            overlay.dismiss();
            capsule = caption = details = sampling = followButton = null;
            schedule(900);
            return;
        }
        if (!foreground || !capture.packageName.equals(previousPackage)) {
            if (autoSample) session.event("foreground", capture.packageName);
            if (!previousPackage.isEmpty() && !previousPackage.equals(capture.packageName)) activity = "";
            targetVersion = version(capture.packageName);
            previousPackage = capture.packageName;
            store.rememberCampus(capture.packageName);
        }
        foreground = true;
        snapshot = read(capture, true);
        if (follow != null && (!follow.inWindow(capture) || snapshot.protectedPage
                || snapshot.step >= 0 && (snapshot.screen != USpeakingEngine.Screen.EXAMPLE || snapshot.step != 2))) {
            follow.finish("页面已离开范例学习 Step 2，逐句跟读已暂停。");
        }
        if (snapshot.screen == USpeakingEngine.Screen.HOME) {
            modulePackage = "";
            moduleWindow = -1;
        } else if (snapshot.scope == USpeakingEngine.Scope.CONFIRMED) {
            modulePackage = capture.packageName;
            moduleWindow = capture.window;
        }
        if (!overlay.showing()) render();
        updateLabels();
        if (autoSample && !snapshot.protectedPage) {
            String key = key(capture, snapshot);
            if (!key.equals(previousKey)) record(capture, key, false);
        }
        schedule(750);
    }

    private USpeakingEngine.Snapshot read(CampusService.Capture capture) {
        return read(capture, false);
    }

    private USpeakingEngine.Snapshot read(CampusService.Capture capture, boolean commit) {
        return engine.read(capture.coursePage(), capture.lines,
                modulePackage.equals(capture.packageName) && moduleWindow == capture.window,
                now() - issueAt <= 3500 ? issueText : "", playbackProgress,
                capture.packageName + "|" + capture.window, now(), commit);
    }

    private String key(CampusService.Capture frame, USpeakingEngine.Snapshot state) {
        StringBuilder key = new StringBuilder(frame.packageName).append('|').append(frame.window).append('|').append(state.key());
        for (PageModel.Line line : USpeakingReadout.join(frame.lines)) {
            if (line.edit || line.text.isEmpty()) continue;
            String stable = line.text.replaceAll("[0-9]{1,3}:[0-5][0-9]", "#")
                    .replaceAll("(?:剩余时间|剩余|倒计时)\\s*[:：]?\\s*\\d+\\s*秒", "倒计时");
            if (state.remaining >= 0 && USpeakingReadout.SECONDS.matcher(stable).matches()) stable = "录音倒计时";
            if (stable.length() <= 260) key.append('\n').append(stable).append('|').append(line.enabled);
            if (key.length() > 5500) break;
        }
        return key.toString();
    }

    private void schedule(long delay) {
        long due = now() + delay;
        if (stopped || scheduledAt != 0 && scheduledAt <= due) return;
        scheduledAt = due;
        ui.removeCallbacks(read);
        ui.postDelayed(read, delay);
    }

    private void render() {
        if (!foreground) return;
        try {
            renderOverlay();
        } catch (RuntimeException error) {
            overlay.dismiss();
            capsule = caption = details = sampling = followButton = null;
            android.util.Log.w("USpeakingObserver", "Unable to show observer overlay", error);
        }
    }

    private void renderOverlay() {
        if (expanded) {
            LinearLayout panel = Ui.card(service);
            panel.setElevation(Ui.dp(service, 8));
            LinearLayout heading = Ui.row(service);
            TextView title = Ui.text(service, "U听说监测", 19, Ui.INK, true);
            title.setMinHeight(Ui.dp(service, 44));
            title.setGravity(android.view.Gravity.CENTER_VERTICAL);
            overlay.dragHandle(title, false);
            Ui.stretch(heading, title);
            heading.addView(Ui.button(service, "收起", false, () -> {
                expanded = false;
                render();
            }));
            panel.addView(heading);
            Ui.gap(panel, 9);
            details = Ui.text(service, "", 14, Ui.INK, false);
            panel.addView(details);
            Ui.gap(panel, 12);
            String trainingButton = training != null ? "停止口语训练" : stoppedOnModelReply ? "重试当前任务" : "开始口语训练";
            panel.addView(Ui.button(service, trainingButton, true, this::toggleTraining));
            Ui.gap(panel, 9);
            followButton = Ui.button(service, "", true, this::toggleFollow);
            panel.addView(followButton);
            Ui.gap(panel, 9);
            sampling = Ui.text(service, "", 13, Ui.SUB, false);
            panel.addView(sampling);
            Ui.gap(panel, 8);
            LinearLayout row = Ui.row(service);
            Ui.stretch(row, Ui.button(service, autoSample ? "停止采样" : "开始采样", true, this::toggleSampling));
            Ui.spaceX(row, 8);
            Ui.stretch(row, Ui.button(service, "记录当前页", false, this::captureCurrent));
            panel.addView(row);
            Ui.gap(panel, 7);
            panel.addView(Ui.button(service, "保存诊断包", false, this::export));
            capsule = caption = null;
            updateLabels();
            overlay.show(panel, OverlayWindow.Mode.PANEL, follow != null || training != null);
        } else {
            LinearLayout chip = Ui.column(service);
            chip.setBackground(Ui.ripple(service, Ui.DARK, 17, 0));
            Ui.pad(chip, 12, 8, 8);
            chip.setMinimumWidth(Ui.dp(service, 142));
            chip.setElevation(Ui.dp(service, 8));
            chip.setFocusable(true);
            chip.setOnClickListener(view -> {
                expanded = true;
                render();
            });
            capsule = Ui.text(service, "", 13, Ui.WHITE, true);
            caption = Ui.text(service, "", 11, Ui.LILAC, false);
            for (TextView text : new TextView[]{capsule, caption}) {
                text.setSingleLine(true);
                text.setMaxWidth(Ui.dp(service, 130));
                text.setEllipsize(TextUtils.TruncateAt.END);
            }
            chip.addView(capsule);
            Ui.gap(chip, 4);
            chip.addView(caption);
            chip.setContentDescription("U听说实时监测，轻点展开，按住拖动");
            overlay.dragHandle(chip, true, true);
            details = sampling = followButton = null;
            updateLabels();
            overlay.show(chip, OverlayWindow.Mode.CHIP, follow != null || training != null);
        }
    }

    private void updateLabels() {
        String title = snapshot.screen.label + (snapshot.step >= 0 ? " · Step " + snapshot.step : "");
        if (capsule != null) {
            update(capsule, title);
            String brief = follow != null || training != null || !followNote.isEmpty() ? followNote : snapshot.state();
            update(caption, brief + (autoSample ? " · 采样" : ""));
            caption.setTextColor(snapshot.issue == USpeakingEngine.Issue.NONE ? Ui.LILAC : 0xffffcca1);
        }
        if (details == null) return;
        if (followButton != null) {
            update(followButton, follow == null ? "Step 2 逐句跟读" : "停止逐句跟读");
        }
        String flags = snapshot.scope == USpeakingEngine.Scope.CONFIRMED ? "U听说已识别"
                : snapshot.scope == USpeakingEngine.Scope.ENTRY ? "已见 U听说入口" : "模块待确认";
        StringBuilder text = new StringBuilder(title).append(" · ").append(flags).append("\n").append(snapshot.state());
        if (!followNote.isEmpty()) text.append("\n训练  ").append(followNote);
        if (snapshot.question >= 0) text.append("\n题号  ").append(snapshot.question).append('/').append(snapshot.questions);
        if (snapshot.turns >= 0) text.append("\n话轮  ").append(snapshot.turns).append('/').append(snapshot.turnLimit);
        if (!snapshot.instruction.isEmpty()) text.append("\n要求  ").append(snapshot.instruction);
        if (!snapshot.prompt.isEmpty()) text.append("\n题目  ").append(snapshot.prompt);
        text.append("\n播放  ").append(snapshot.playback.label);
        if (snapshot.elapsed >= 0 || snapshot.duration >= 0) {
            text.append("  ").append(clock(snapshot.elapsed)).append(" / ").append(clock(snapshot.duration));
        }
        text.append("\n录音  ").append(snapshot.recording.label);
        if (snapshot.recordingTime >= 0) text.append("  ").append(clock(snapshot.recordingTime));
        if (snapshot.limit >= 0) text.append("  限时 ").append(clock(snapshot.limit));
        if (snapshot.remaining >= 0) text.append("  剩余 ").append(clock(snapshot.remaining));
        if (snapshot.minimumRecording >= 0) text.append("  最少 ").append(snapshot.minimumRecording).append(" 秒");
        if (!snapshot.score.isEmpty()) text.append("\n得分  ").append(snapshot.score);
        if (!snapshot.controls.isEmpty()) text.append("\n可操作  ").append(String.join("、", snapshot.controls));
        if (!snapshot.disabledControls.isEmpty()) text.append("\n未就绪  ").append(String.join("、", snapshot.disabledControls));
        text.append("\n下一步  ").append(snapshot.plan());
        if (!snapshot.issueText.isEmpty()) text.append("\n提示  ").append(snapshot.issueText);
        update(details, text.toString());
        if (sampling != null) update(sampling, (session == null ? "采样尚未开始"
                : session.mode.equals("TRAINING") && session.frames == 0 ? "已记录自动训练时间线"
                : (autoSample ? "正在采样" : "已记录") + " · " + session.frames + " 页")
                + (message.isEmpty() ? "" : "\n" + message));
    }

    private void toggleFollow() {
        if (training != null) {
            followNote = "口语训练正在运行，可在上方停止。";
            updateLabels();
            return;
        }
        if (follow != null) {
            follow.finish("已停止。若 U校园仍显示录音中，可在原页面结束。");
            return;
        }
        CampusService.Capture cap = service.capture();
        USpeakingFollowPage page = cap == null ? null : USpeakingFollowPage.read(cap.coursePage(), cap.lines);
        if (page == null || page.microphone() == null && !page.positiveScore()) {
            followNote = "请在范例学习 Step 2 展开句子，等麦克风或重录入口出现。";
            updateLabels();
            return;
        }
        stopSampling("follow_started");
        follow = new USpeakingFollow(service, ui, this, cap);
        expanded = false;
        render();
        follow.start(cap);
    }

    void followProgress(String status) {
        followNote = status;
        updateLabels();
    }

    void followFinished(USpeakingFollow run, String status) {
        if (follow != run) return;
        follow = null;
        followNote = status;
        if (foreground && !stopped) {
            expanded = true;
            render();
        }
    }

    private void toggleTraining() {
        if (training != null) {
            training.finish("已停止。若 U校园还在录音，请在当前页面检查录音状态。");
            return;
        }
        if (follow != null) follow.finish("已切换到口语训练。");
        CampusService.Capture cap = service.capture();
        if (cap == null || cap.page.blocked) {
            followNote = "请先打开 U口语首页或口语训练目录。";
            updateLabels();
            return;
        }
        stopSampling("training_started");
        stoppedOnModelReply = false;
        session = new USpeakingDiagnostics.Session(service, "TRAINING");
        trainingEvent("training_started", "oral|app=" + version(service.getPackageName()));
        message = "";
        trainingSnapshotAt = 0;
        trainingMissingAt = -1;
        training = new USpeakingTraining(service, ui, this, store, cap.packageName);
        followNote = "正在识别口语训练…";
        expanded = false;
        render();
        training.start();
    }

    void trainingProgress(String status) {
        if (followNote.equals(status)) return;
        followNote = status;
        updateLabels();
    }

    void trainingEvent(String event, String metadata) {
        if (session != null && session.mode.equals("TRAINING")) session.event(event, metadata);
    }

    void trainingFinished(USpeakingTraining run, String status) {
        if (training != run) return;
        stoppedOnModelReply = run.stoppedOnModelReply();
        training = null;
        trainingSnapshot = null;
        trainingSnapshotAt = 0;
        trainingMissingAt = -1;
        followNote = status;
        if (foreground && !stopped) {
            expanded = true;
            render();
        }
    }

    private void toggleSampling() {
        if (follow != null || training != null) return;
        if (autoSample) {
            stopSampling("user_stop");
            return;
        }
        if (session == null || !session.mode.equals("OBSERVER") || session.frames >= USpeakingDiagnostics.MAX_FRAMES) {
            session = new USpeakingDiagnostics.Session(service);
        }
        session.event("sampling_started", "observer");
        autoSample = true;
        previousKey = "";
        message = "在 U校园打开不同题型或操作录音，状态变化会自动记录。";
        render();
        schedule(0);
    }

    private void stopSampling(String reason) {
        if (!autoSample) return;
        autoSample = false;
        session.event("sampling_stopped", reason);
        message = "可以保存诊断包。";
        if (foreground) render();
    }

    private void captureCurrent() {
        CampusService.Capture cap = service.capture();
        USpeakingEngine.Snapshot state = cap == null ? null : read(cap);
        if (state == null || state.protectedPage) {
            message = "当前页面暂不可采样。";
            updateLabels();
            return;
        }
        if (session == null || session.frames >= USpeakingDiagnostics.MAX_FRAMES) session = new USpeakingDiagnostics.Session(service);
        record(cap, key(cap, state), true);
    }

    private void record(CampusService.Capture original, String expected, boolean manual) {
        CampusService.Capture fresh = service.capture(true);
        boolean sameWindow = fresh != null && fresh.window == original.window && fresh.packageName.equals(original.packageName);
        USpeakingEngine.Snapshot state = sameWindow ? read(fresh) : null;
        if (state == null || state.protectedPage || !expected.equals(key(fresh, state))) {
            if (manual) {
                message = "页面正在变化，请稳定后再点记录。";
                updateLabels();
            }
            return;
        }
        previousKey = expected;
        File folder = session.frame(metadata(fresh, state, manual), fresh.tree);
        screenshot(fresh, state, folder);
        if (session.frames >= USpeakingDiagnostics.MAX_FRAMES) {
            stopSampling("frame_limit");
            message = "已到本次采样上限，可保存诊断包。";
        } else if (manual) {
            message = "已记录当前页，可打开下一种状态。";
        }
        updateLabels();
    }

    private JSONObject metadata(CampusService.Capture frame, USpeakingEngine.Snapshot state, boolean manual) {
        JSONObject data = new JSONObject();
        USpeakingDiagnostics.put(data, "timeEpochMs", System.currentTimeMillis());
        USpeakingDiagnostics.put(data, "trigger", manual ? "manual" : "state_change");
        USpeakingDiagnostics.put(data, "package", frame.packageName);
        USpeakingDiagnostics.put(data, "appVersion", targetVersion);
        USpeakingDiagnostics.put(data, "activity", activity);
        USpeakingDiagnostics.put(data, "windowId", frame.window);
        USpeakingDiagnostics.put(data, "screen", state.screen.name());
        USpeakingDiagnostics.put(data, "scope", state.scope.name());
        USpeakingDiagnostics.put(data, "step", state.step < 0 ? JSONObject.NULL : state.step);
        USpeakingDiagnostics.put(data, "instruction", state.instruction);
        USpeakingDiagnostics.put(data, "promptText", state.prompt);
        USpeakingDiagnostics.put(data, "playback", state.playback.name());
        USpeakingDiagnostics.put(data, "recording", state.recording.name());
        USpeakingDiagnostics.put(data, "elapsedSeconds", state.elapsed);
        USpeakingDiagnostics.put(data, "durationSeconds", state.duration);
        USpeakingDiagnostics.put(data, "recordingElapsedSeconds", state.recordingTime);
        USpeakingDiagnostics.put(data, "limitSeconds", state.limit);
        USpeakingDiagnostics.put(data, "remainingSeconds", state.remaining);
        USpeakingDiagnostics.put(data, "minimumRecordingSeconds", state.minimumRecording);
        USpeakingDiagnostics.put(data, "questionNumber", state.question);
        USpeakingDiagnostics.put(data, "questionTotal", state.questions);
        USpeakingDiagnostics.put(data, "dialogueTurns", state.turns);
        USpeakingDiagnostics.put(data, "dialogueTurnLimit", state.turnLimit);
        USpeakingDiagnostics.put(data, "pendingTasks", state.pending);
        USpeakingDiagnostics.put(data, "controls", new JSONArray(state.controls));
        USpeakingDiagnostics.put(data, "disabledOrVisibleOnlyControls", new JSONArray(state.disabledControls));
        USpeakingDiagnostics.put(data, "issue", state.issue.name());
        USpeakingDiagnostics.put(data, "issueText", state.issueText);
        USpeakingDiagnostics.put(data, "network", network());
        USpeakingDiagnostics.put(data, "score", state.score);
        USpeakingDiagnostics.put(data, "completed", state.completed);
        USpeakingDiagnostics.put(data, "evidence", new JSONArray(state.evidence));
        USpeakingDiagnostics.put(data, "observed", state.state());
        USpeakingDiagnostics.put(data, "plannedObservation", state.plan());
        USpeakingDiagnostics.put(data, "action", "NONE");
        return data;
    }

    private void screenshot(CampusService.Capture frame, USpeakingEngine.Snapshot state, File folder) {
        if (Build.VERSION.SDK_INT < 30) {
            USpeakingDiagnostics.imageStatus(folder, "android_version_unsupported");
            return;
        }
        if (frame.lines.stream().anyMatch(line -> line.edit && line.filled)) {
            USpeakingDiagnostics.imageStatus(folder, "visible_filled_input");
            return;
        }
        if (Build.VERSION.SDK_INT < 34 && !onlyTargetWindow(frame.window)) {
            USpeakingDiagnostics.imageStatus(folder, "display_has_other_windows");
            return;
        }
        if (now() - screenshotAt < 500) {
            USpeakingDiagnostics.imageStatus(folder, "screenshot_interval");
            return;
        }
        screenshotAt = now();
        USpeakingDiagnostics.imageStatus(folder, "requested");
        AccessibilityService.TakeScreenshotCallback callback = new AccessibilityService.TakeScreenshotCallback() {
            @Override public void onSuccess(AccessibilityService.ScreenshotResult result) {
                CampusService.Capture latest = stopped ? null : service.capture();
                if (latest == null || latest.window != frame.window || !latest.packageName.equals(frame.packageName)
                        || !read(latest).key().equals(state.key())) {
                    result.getHardwareBuffer().close();
                    USpeakingDiagnostics.imageStatus(folder, "window_or_state_changed");
                    return;
                }
                USpeakingDiagnostics.image(folder, result.getHardwareBuffer(), result.getColorSpace());
            }

            @Override public void onFailure(int errorCode) {
                USpeakingDiagnostics.imageStatus(folder, "android_error_" + errorCode);
            }
        };
        try {
            if (Build.VERSION.SDK_INT >= 34) service.takeScreenshotOfWindow(frame.window, service.getMainExecutor(), callback);
            else service.takeScreenshot(Display.DEFAULT_DISPLAY, service.getMainExecutor(), callback);
        } catch (RuntimeException error) {
            USpeakingDiagnostics.imageStatus(folder, "screenshot_request_failed");
        }
    }

    private boolean onlyTargetWindow(int targetWindow) {
        int found = 0;
        for (AccessibilityWindowInfo window : service.getWindows()) {
            if (window.getType() == AccessibilityWindowInfo.TYPE_APPLICATION) {
                if (window.getId() != targetWindow) return false;
                found++;
            }
        }
        return found == 1;
    }

    private String network() {
        ConnectivityManager connectivity = service.getSystemService(ConnectivityManager.class);
        Network active = connectivity == null ? null : connectivity.getActiveNetwork();
        NetworkCapabilities capabilities = active == null ? null : connectivity.getNetworkCapabilities(active);
        if (capabilities == null) return "NO_ACTIVE_NETWORK";
        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ? "WIFI"
                : capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ? "CELLULAR" : "OTHER";
    }

    private String version(String name) {
        try {
            String value = service.getPackageManager().getPackageInfo(name, 0).versionName;
            return value == null ? "" : value;
        } catch (PackageManager.NameNotFoundException error) {
            return "";
        }
    }

    private void export() {
        if (!USpeakingDiagnostics.hasSession(service)) {
            message = "请先运行一次口语训练，或记录当前页。";
            updateLabels();
            return;
        }
        stopSampling("export_requested");
        service.startActivity(new Intent(service, USpeakingActivity.class).putExtra(USpeakingActivity.EXPORT, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP));
    }

    String summary() { return snapshot.screen.label + " · " + snapshot.state(); }

    void redraw() { if (foreground) render(); }

    void collapseForControl() {
        if (expanded && foreground) {
            expanded = false;
            render();
        }
    }

    void close() {
        stopped = true;
        ui.removeCallbacks(read);
        if (follow != null) follow.finish("服务已断开，逐句跟读已暂停。");
        if (training != null) training.finish("服务已断开，口语训练已暂停。");
        if (autoSample) session.event("sampling_stopped", "service_disconnected");
        overlay.dismiss();
    }

    private static long now() { return SystemClock.uptimeMillis(); }

    private static void update(TextView view, String value) {
        if (!value.contentEquals(view.getText())) view.setText(value);
    }

    private static String clock(int seconds) {
        return seconds < 0 ? "—" : String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60);
    }
}
