package dev.campuspocket.app;

import android.media.AudioManager;
import android.net.ConnectivityManager;
import android.os.Handler;
import android.os.SystemClock;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Future;
import java.util.function.Consumer;
import java.util.function.Predicate;

final class USpeakingTraining implements TrainingAudio.Listener {
    static final long MIC_RETRY_MS = 1750;
    static final int MIC_ATTEMPTS = 3;
    private enum Phase { ROUTE, NAVIGATING, OPENING_CARD, SCROLLING, WAIT_NETWORK, GENERATING_REPLY, PREPARING_AUDIO, AUDIO_READY,
        WAIT_RECORD, SPEAKING, RECOVER_AUDIO, WAIT_STOP, WAIT_RESULT }

    private final CampusService service;
    private final Handler ui;
    private final USpeakingMonitor monitor;
    private final Store.Config config;
    private final TrainingAudio audio;
    private final String packageName;
    private final Set<String> finishedCards = new LinkedHashSet<>();
    private final Set<String> seenCards = new LinkedHashSet<>();
    private final Set<String> deferredCards = new LinkedHashSet<>();
    private final Set<String> timedOutCards = new LinkedHashSet<>();
    private final Set<String> timedOutScores = new LinkedHashSet<>();
    private final Set<String> retrySweepCards = new LinkedHashSet<>();
    private final Set<String> unavailableRetests = new LinkedHashSet<>();
    private final TrainingScore scores;
    private final int minimumScore;
    private final boolean[] verifiedSections = new boolean[TrainingPage.SECTIONS.length];
    private final boolean[] reportRetested = new boolean[TrainingPage.SECTIONS.length];
    private final Runnable cycle = this::tick;
    private Phase phase = Phase.ROUTE;
    private Phase tracedPhase;
    private TrainingPage.Kind itemKind;
    private int chapter, itemWindow, itemTurn, sentTurn = -1;
    private int completedSection = -1, reviewingSection = -1, retestNavigation = -1,
            itemQuestion, itemTotal, stopAttempts, startAttempts;
    private long deadline, recordAt, stableAt, routeSince, nextStopAttempt, navRetryAt, nextStartAttempt;
    private long scoreReadyAt, submitRetryAt, scheduledAt, nextCardCaptureAt, tracedAt, cardRetryAt;
    private boolean platformTimedOut, timeoutDismissed;
    private String cardTarget = "";
    private int cardWindow, cardAttempts;
    private boolean retriedDeferredCards;
    private Predicate<TrainingPage> arrived;
    private Future<?> answerRequest;
    private Answer pendingAnswer;
    private boolean networkRetried;
    private TrainingHold hold;
    private String item = "", previousScore = "", example = "", activeReply = "", lastAi = "";
    private String currentSpeech = "", lastState = "", navigationError = "", navLabel = "", navOrigin = "";
    private TrainingPage.Kind navKind;
    private int navWindow, navAttempts, followWindow = -1;
    private boolean active = true, sawScoring, navConfirmed, homeStarted, stopConfirmed, navCanBack;
    private int submitAttempts;
    private CampusService.Capture lastCapture;
    private TrainingPage lastPage;
    private boolean seekingFirst = true, foundBottom;
    private int scrollDirection, stationaryScrolls, rejectedScrolls, scrollTicket;
    private long scrollSettleAt, scrollQuietAt;
    private Boolean scrollDelivered;
    private String scrollBefore = "", scrollObserved = "";
    private String markerKey = "";
    private boolean awaitingMarkers;
    private long markerDeadline, markersRequestedAt;
    private TrainingMarkers.Status[] observedMarkers;
    private int aiCountAtSend, lastSentAiCount, markerEpoch, replyGeneration;
    private String conversationTask = "", lastExchange = "";
    private boolean findingHold, findingTaskNext;
    private String taskCardFallbackKey = "", tutorialProbeKey = "", tutorialFallbackKey = "";
    private long tutorialQuietUntil;
    private boolean findingTutorial;
    private boolean audioRetried;
    private boolean modelReplyFailed;
    private long chatTouchAt = -1, chatSentAt = -1;
    private boolean chatSoftStarted, chatSendRetried;

    USpeakingTraining(CampusService service, Handler ui, USpeakingMonitor monitor, Store store, String packageName) {
        this.service = service;
        this.ui = ui;
        this.monitor = monitor;
        this.packageName = packageName;
        config = store.config();
        minimumScore = store.trainingMinimum();
        scores = new TrainingScore(minimumScore);
        audio = new TrainingAudio(service, ui, store.trainingSpeed(), store.voice(), this);
    }

    void start() {
        if (!config.ready()) {
            finish("请先在「API 与语音」保存接口地址、密钥和模型 ID。");
            return;
        }
        AudioManager sound = service.getSystemService(AudioManager.class);
        if (sound != null && sound.getStreamVolume(AudioManager.STREAM_MUSIC) == 0) {
            finish("媒体音量为 0，请调高后再开始。");
            return;
        }
        routeSince = now();
        schedule(0);
    }

    private void tick() {
        scheduledAt = 0;
        if (!active) return;
        CampusService.Capture cap = monitor.trainingCapture();
        if (cap == null && monitor.trainingCapturePending()) {
            schedule(150);
            return;
        }
        if (cap == null || !packageName.equals(cap.packageName) || cap.page.blocked) {
            finish("U校园已离开前台或打开了受保护页面，已暂停。");
            return;
        }
        TrainingPage page = trainingPage(cap);
        if (page.state.protectedPage) {
            finish("当前是受保护页面，口语训练已暂停。");
            return;
        }
        if (page.kind != TrainingPage.Kind.MENU && !markerKey.isEmpty()) {
            markerEpoch++;
            markerKey = "";
            observedMarkers = null;
            awaitingMarkers = false;
        }
        if (page.kind == TrainingPage.Kind.STEP_2) followWindow = cap.window;
        else if (page.kind != TrainingPage.Kind.UNKNOWN) followWindow = -1;
        if (page.state.recordingTimedOut()) {
            observeOfficialTimeout(cap, page);
        } else if (page.state.issue != USpeakingEngine.Issue.NONE && phase != Phase.SPEAKING) {
            finish(page.state.issueText.isEmpty() ? page.state.issue.label : page.state.issueText);
            return;
        }
        if (!active) return;
        // Routine in-app instruction cards can appear between the AI prompt and the microphone.
        // Re-enter routing instead of timing out on the old, obscured recording control.
        if (page.kind == TrainingPage.Kind.INSTRUCTIONS && phase != Phase.ROUTE
                && phase != Phase.NAVIGATING && phase != Phase.OPENING_CARD && phase != Phase.SCROLLING) {
            trace("instruction_interruption", phase.name());
            cancelAnswer();
            audio.stop();
            if (hold != null) hold.cancel();
            hold = null;
            resumeRoute();
            return;
        }
        switch (phase) {
            case ROUTE: route(cap, page); break;
            case NAVIGATING:
                if (arrived.test(page)) {
                    reviewingSection = retestNavigation = -1;
                    resumeRoute();
                }
                else if (!navConfirmed && retestNavigation >= 0 && page.retestConfirmation() != null
                        && service.trainingClick(cap, page.retestConfirmation())) {
                    navConfirmed = true;
                    navRetryAt = Long.MAX_VALUE;
                    deadline = now() + 20000;
                }
                else if (!navConfirmed && page.confirmsTrainingAction()
                        && service.trainingClick(cap, page.confirmButton())) {
                    navConfirmed = true;
                    navRetryAt = Long.MAX_VALUE;
                    deadline = now() + 20000;
                }
                else if (now() > deadline) recoverReviewNavigation(page);
                else if (navCanBack && now() >= navRetryAt && cap.window == navWindow
                        && page.kind == TrainingPage.Kind.REPORT && navAttempts >= 3) {
                    navCanBack = false;
                    service.trainingBack(cap);
                }
                else if (now() >= navRetryAt && navAttempts < 3 && cap.window == navWindow && page.kind == navKind
                        && page.identity().equals(navOrigin)) {
                    if (page.kind == TrainingPage.Kind.INSTRUCTIONS && page.taskCard && navAttempts == 2) {
                        locateTaskNext(cap, page, true);
                    }
                    PageModel.Line control = page.kind == TrainingPage.Kind.HOME ? adaptiveButton(cap, page)
                            : page.kind == TrainingPage.Kind.MENU ? menuNavigationControl(page)
                            : page.kind == TrainingPage.Kind.INSTRUCTIONS ? page.instructionButton() : page.action(navLabel);
                    if (control != null) {
                        if (navAttempts == 1) service.trainingClick(cap, control);
                        else service.trainingTap(cap, control.bounds);
                    }
                    navAttempts++;
                    navRetryAt = now() + 2000;
                }
                break;
            case OPENING_CARD: waitForCard(cap, page); break;
            case SCROLLING: waitForCardScroll(cap, page); break;
            case WAIT_NETWORK: waitForNetwork(cap, page); break;
            case GENERATING_REPLY:
            case PREPARING_AUDIO:
                if (checkReplyPage(cap, page) && now() > deadline) {
                    if (phase == Phase.GENERATING_REPLY) replyFailure("文本模型生成回答超时，请检查 API 或网络。");
                    else error(audio.voiceDescription() + "准备超时。");
                }
                break;
            case AUDIO_READY: waitForAudioReady(cap, page); break;
            case WAIT_RECORD: waitForRecording(cap, page); break;
            case SPEAKING:
                // A target-side recording end or turn change outranks local playback.
                // Keep sampling during playback, even when the WebView emits no event.
                observeTargetDuringPlayback(cap, page);
                if (active && phase == Phase.SPEAKING) schedule(390);
                return;
            case RECOVER_AUDIO: recoverAudio(cap, page); break;
            case WAIT_STOP: waitForStop(cap, page); break;
            case WAIT_RESULT: result(cap, page); break;
        }
        if (active && phase != Phase.SPEAKING) schedule(phase == Phase.WAIT_NETWORK ? 1000
                : phase == Phase.PREPARING_AUDIO || phase == Phase.GENERATING_REPLY ? 720
                : phase == Phase.WAIT_RECORD || phase == Phase.WAIT_STOP || phase == Phase.WAIT_RESULT ? 225
                : page.kind == TrainingPage.Kind.CHAT ? 250 : 375);
    }

    /**
     * Playback is terminated by an *observed* target event, not an estimated
     * audio length or the page countdown. This is also polled while speaking:
     * some WebViews do not dispatch a useful accessibility change event.
     */
    private void observeTargetDuringPlayback(CampusService.Capture cap, TrainingPage page) {
        boolean officialEnd = page.state.recordingTimedOut();
        if (!officialEnd && page.state.issue != USpeakingEngine.Issue.NONE) {
            trace("target_issue_while_playing", page.state.issue.name());
            finish(page.state.issueText.isEmpty() ? page.state.issue.label : page.state.issueText);
            return;
        }
        boolean newTurn = itemKind == TrainingPage.Kind.CHAT && page.kind == TrainingPage.Kind.CHAT
                && page.state.turns > itemTurn;
        boolean newPage = page.kind == TrainingPage.Kind.REPORT || page.kind == TrainingPage.Kind.MENU
                || itemKind == TrainingPage.Kind.CHAT && (page.kind == TrainingPage.Kind.FREE
                    || page.kind == TrainingPage.Kind.CHAT_INTRO)
                || itemKind == TrainingPage.Kind.STEP_2 && page.kind == TrainingPage.Kind.STEP_3;
        USpeakingEngine.Media media = page.state.recording;
        boolean saved = media == USpeakingEngine.Media.SAVING || media == USpeakingEngine.Media.SCORING
                || media == USpeakingEngine.Media.SAVED || media == USpeakingEngine.Media.SCORED;
        boolean endedSamePage = page.kind == itemKind && !recording(page)
                && (saved || page.state.completed);
        if (!officialEnd && !newTurn && !newPage && !endedSamePage) return;
        trace("target_end_during_playback", officialEnd ? "official_recording_end"
                : newTurn ? "new_chat_turn" : newPage ? "page_advanced" : "recording_finalized");
        audio.stop();
        if (itemKind == TrainingPage.Kind.CHAT) {
            // End the active pointer at the same coordinate. Do not start a new
            // recording or resend after the target has already ended this one.
            if (hold != null && !hold.finished()) hold.releaseToSend();
            chatSentAt = now();
            chatSendRetried = true;
        }
        stopConfirmed = false;
        phase = Phase.WAIT_RESULT;
        deadline = now() + 40000;
        notice("U校园已结束录音或切换页面，已停止播放并开始同步结果…");
        schedule(0);
    }

    private void waitForRecording(CampusService.Capture cap, TrainingPage page) {
        boolean dialogue = itemKind == TrainingPage.Kind.CHAT;
        boolean current = sameItem(cap, page);
        ChatRecordingGate.Decision decision = dialogue && current
                ? ChatRecordingGate.check(page, hold, chatTouchAt, now()) : ChatRecordingGate.Decision.WAIT;
        if (current && (dialogue ? decision != ChatRecordingGate.Decision.WAIT : recording(page))) {
            chatSoftStarted = dialogue && decision == ChatRecordingGate.Decision.TOUCH_ESTABLISHED;
            if (dialogue) trace("recording_gate", decision.name() + "|attempt=" + startAttempts);
            phase = Phase.SPEAKING;
            recordAt = now();
            notice(chatSoftStarted ? "长按已持续，页面未暴露录音文字，开始播放并监测话轮…"
                    : "录音已开始，正在启动播放器…");
            audio.play(dialogue || itemKind == TrainingPage.Kind.FREE);
            return;
        }
        if (advanceAfterOfficialTimeout(cap, page)) return;
        if (now() > deadline) {
            if (dialogue && hold != null) hold.cancel();
            trace("recording_gate_failed", "attempts=" + startAttempts + "|held="
                    + (hold != null && hold.contactEstablished()) + "|page=" + page.kind.name());
            finish(dialogue && startAttempts == 0 ? "语音已备好，但未定位到按住说话按钮（含画面识别回退）。"
                    : dialogue ? "长按未建立或页面已变化，本轮没有启动播放；已停止重试并记录诊断。"
                    : "U校园未确认开启本次录音，已停止重试并记录诊断。");
            return;
        }
        if (!current || now() < nextStartAttempt) return;
        if (dialogue) retryChatHold(cap, page);
        else if (startAttempts < MIC_ATTEMPTS) tryMicrophone(cap, page);
    }

    private void observeOfficialTimeout(CampusService.Capture cap, TrainingPage page) {
        if (platformTimedOut || cap.window != itemWindow
                || phase != Phase.WAIT_RECORD && phase != Phase.SPEAKING && phase != Phase.WAIT_STOP && phase != Phase.WAIT_RESULT) return;
        if (sameItem(cap, page) || page.kind == TrainingPage.Kind.UNKNOWN) {
            platformTimedOut = true;
            trace("official_record_timeout", itemKind.name());
        }
    }

    /** Detect the screenshot-only Step 1 swipe tutorial before it can hide Next. */
    private boolean probeTutorialOverlay(CampusService.Capture cap, TrainingPage page) {
        if (now() < tutorialQuietUntil || findingTutorial) return true;
        if (page.kind != TrainingPage.Kind.STEP_1 && page.kind != TrainingPage.Kind.UNKNOWN
                && !page.tutorialVisible()) return false;
        String key = cap.window + "|" + page.kind + "|" + page.tutorialVisible();
        if (key.equals(tutorialProbeKey)) return false;
        tutorialProbeKey = key;
        findingTutorial = true;
        service.captureTrainingTutorialOkay(cap, found -> {
            findingTutorial = false;
            if (!active || phase != Phase.ROUTE || found == null) return;
            CampusService.Capture fresh = service.capture();
            if (fresh == null || fresh.window != cap.window || !packageName.equals(fresh.packageName)
                    || fresh.page.blocked) return;
            TrainingPage current = trainingPage(fresh);
            if (current.state.protectedPage) return;
            if (service.trainingTap(fresh, found)) {
                trace("tutorial_dismissed", "detected_cyan_rim_ok");
                tutorialQuietUntil = now() + 950;
                notice("已处理屏幕滑动教学提示，继续当前练习…");
            }
        });
        return true;
    }

    private void route(CampusService.Capture cap, TrainingPage page) {
        if (probeTutorialOverlay(cap, page)) return;
        switch (page.kind) {
            case HOME:
                if (homeStarted || chapter != 0 || !finishedCards.isEmpty()) {
                    finish("已返回 U口语首页，本次训练已停止。");
                    return;
                }
                PageModel.Line start = adaptiveButton(cap, page);
                if (start == null) {
                    waitRoute("正在等待自适应训练卡片上的开始训练按钮…", 8500);
                    return;
                }
                homeStarted = true;
                notice("正在打开自适应训练…");
                open(cap, start, next -> next.kind == TrainingPage.Kind.MENU || next.kind == TrainingPage.Kind.STEP_1
                        || next.kind == TrainingPage.Kind.STEP_2 || next.kind == TrainingPage.Kind.STEP_3
                        || next.kind == TrainingPage.Kind.CHAT_INTRO || next.kind == TrainingPage.Kind.CHAT
                        || next.kind == TrainingPage.Kind.FREE || next.kind == TrainingPage.Kind.INSTRUCTIONS,
                        "U口语没有确认进入当前训练。");
                return;
            case MENU: selectMenuTask(cap, page); return;
            case INSTRUCTIONS: advanceInstructions(cap, page); return;
            case STEP_1:
                if (!page.task.isEmpty()) example = page.task;
                open(cap, page.action("下一步"), next -> next.kind == TrainingPage.Kind.STEP_2 || next.kind == TrainingPage.Kind.INSTRUCTIONS,
                        "未切换到 Step 2。");
                return;
            case STEP_2: followCards(cap, page); return;
            case STEP_3: cloze(cap, page); return;
            case CHAT_INTRO:
                chapter = 1;
                open(cap, page.action("开始对话练习"), next -> next.kind == TrainingPage.Kind.CHAT
                                || next.kind == TrainingPage.Kind.INSTRUCTIONS,
                        "未进入 AI 口语对话。");
                return;
            case CHAT: dialogue(cap, page); return;
            case FREE:
                chapter = 2;
                observeExistingScore(page);
                if (accepted(page) || page.state.completed && !page.scored()) {
                    finish("自由表达已显示结果，口语训练结束。");
                    return;
                }
                if (page.microphone() == null || page.task.isEmpty()) {
                    waitRoute("正在等待自由表达主题和录音入口…", 10000);
                    return;
                }
                prepare(cap, page, retry -> TrainingModel.speak(config, false, page.task, TrainingModel.FREE_WORDS, retry));
                return;
            case REPORT: report(cap, page); return;
            default: waitRoute("正在等待可识别的口语训练页面…", 12000);
        }
    }

    private String sectionName() { return TrainingPage.SECTIONS[chapter]; }

    private void selectMenuTask(CampusService.Capture cap, TrainingPage page) {
        String key = cap.window + "|" + page.menuKey();
        if (!key.equals(markerKey)) {
            markerKey = key;
            awaitingMarkers = true;
            observedMarkers = null;
            markersRequestedAt = now();
            markerDeadline = now() + 1700;
            int request = ++markerEpoch;
            service.captureTrainingMarkers(cap, page, result -> {
                if (active && markerEpoch == request) {
                    awaitingMarkers = false;
                    observedMarkers = result;
                    schedule(45);
                }
            });
        }
        if (awaitingMarkers && now() < markerDeadline) return;
        for (int i = 0; i < TrainingPage.SECTIONS.length; i++) {
            TrainingMarkers.Status text = page.menuTextStatus(i);
            TrainingMarkers.Status marker = observedMarkers == null ? TrainingMarkers.Status.UNKNOWN : observedMarkers[i];
            if (verifiedSections[i]) continue;
            chapter = i;
            boolean completed = marker == TrainingMarkers.Status.DONE
                    || marker == TrainingMarkers.Status.UNKNOWN && text == TrainingMarkers.Status.DONE;
            if (completed) {
                String score = page.menuScore(i);
                if (scores.meets(score)) {
                    verifiedSections[i] = true;
                    continue;
                }
                PageModel.Line report = page.reportEntry(i);
                if (report == null && scores.below(score)) report = page.menuEntry(sectionName());
                if (report == null || !report.enabled) {
                    if (scores.below(score)) unavailableRetests.add(sectionName());
                    verifiedSections[i] = true;
                    continue;
                }
                completedSection = -1;
                reviewingSection = i;
                notice("正在核对" + sectionName() + "的已有成绩…");
                open(cap, report, this::enteredSection, "未能打开" + sectionName() + "的成绩入口。");
                return;
            }
            if (marker == TrainingMarkers.Status.LOCKED || text == TrainingMarkers.Status.LOCKED) {
                waitRoute(sectionName() + "还未解锁，正在等待目录更新…", 11000);
                if (active && now() - markersRequestedAt >= 1350) markerKey = "";
                return;
            }
            PageModel.Line entry = page.menuEntry(sectionName());
            if (entry == null || !entry.enabled) {
                waitRoute("正在确认" + sectionName() + "的入口…", 9000);
                return;
            }
            completedSection = -1;
            open(cap, entry, this::enteredSection, "未进入" + sectionName() + "。");
            return;
        }
        finish("目录中三项训练均已完成。");
    }

    private void recoverReviewNavigation(TrainingPage page) {
        if (reviewingSection >= 0 && page.kind == TrainingPage.Kind.MENU) {
            if (scores.below(page.menuScore(reviewingSection))) unavailableRetests.add(TrainingPage.SECTIONS[reviewingSection]);
            verifiedSections[reviewingSection] = true;
            reviewingSection = -1;
            resumeRoute();
        } else if (retestNavigation >= 0 && page.kind == TrainingPage.Kind.REPORT) {
            unavailableRetests.add(TrainingPage.SECTIONS[retestNavigation]);
            retestNavigation = -1;
            resumeRoute();
        } else finish(navigationError);
    }

    private PageModel.Line menuNavigationControl(TrainingPage page) {
        PageModel.Line report = reviewingSection < 0 ? null : page.reportEntry(reviewingSection);
        return report != null ? report : page.menuEntry(sectionName());
    }

    private void advanceInstructions(CampusService.Capture cap, TrainingPage page) {
        if (page.taskCard && !page.task.isEmpty()) conversationTask = page.task;
        PageModel.Line button = page.instructionButton();
        if (button == null && page.taskCard) {
            locateTaskNext(cap, page, false);
            return;
        }
        if (button == null && page.tutorialVisible()) {
            String key = cap.window + "|" + page.instructionsKey();
            if (!key.equals(tutorialFallbackKey)) {
                tutorialFallbackKey = key;
                if (service.trainingTutorialOkayFallback(cap)) {
                    tutorialQuietUntil = now() + 950;
                    trace("tutorial_dismissed", "accessible_text_geometry_once");
                    notice("已关闭屏幕滑动引导，继续练习…");
                    return;
                }
            }
            waitRoute("正在等待教学引导退出并同步当前页面…", 8500);
            return;
        }
        openInstructions(cap, page, button);
    }

    private void openInstructions(CampusService.Capture cap, TrainingPage page, PageModel.Line button) {
        String key = page.instructionsKey();
        open(cap, button, next -> next.kind == TrainingPage.Kind.INSTRUCTIONS
                        ? !key.equals(next.instructionsKey()) : next.kind == TrainingPage.Kind.MENU || enteredSection(next)
                                || page.taskCard && (next.kind == TrainingPage.Kind.CHAT_INTRO || next.kind == TrainingPage.Kind.CHAT),
                "训练提示仍在当前页面。");
    }

    private void locateTaskNext(CampusService.Capture cap, TrainingPage page, boolean retry) {
        if (!retry && now() - routeSince > 8500) { finish("已识别阅读任务卡，未能确认卡片上的下一步。"); return; }
        if (findingTaskNext || now() < nextCardCaptureAt) return;
        nextCardCaptureAt = now() + 650;
        findingTaskNext = true;
        String key = page.instructionsKey();
        service.captureTrainingControl(cap, true, bounds -> {
            findingTaskNext = false;
            if (!active || (retry ? phase != Phase.NAVIGATING : phase != Phase.ROUTE)) return;
            CampusService.Capture fresh = service.capture();
            if (fresh == null || fresh.window != cap.window || !fresh.packageName.equals(cap.packageName)) return;
            TrainingPage current = trainingPage(fresh);
            if (!current.taskCard || current.state.protectedPage || !key.equals(current.instructionsKey())) return;
            if (bounds == null) {
                // Android 13 and some WebViews do not expose either a Next node or
                // an API-34 window screenshot. Try the known task-card layout once.
                String fallbackKey = fresh.window + "|" + key;
                if (!retry && now() - routeSince >= 1800 && !fallbackKey.equals(taskCardFallbackKey)) {
                    taskCardFallbackKey = fallbackKey;
                    if (service.trainingTaskCardNextFallback(fresh)) {
                        trace("instruction_fallback", "task_card_next_geometry_once");
                        String prior = current.instructionsKey();
                        awaitNavigation(fresh, current, "", next -> next.kind == TrainingPage.Kind.INSTRUCTIONS
                                ? !prior.equals(next.instructionsKey()) : enteredSection(next),
                                "已尝试任务卡下一步，页面仍未切换。");
                    }
                }
                return;
            }
            if (retry) service.trainingTap(fresh, bounds);
            else {
                PageModel.Line button = new PageModel.Line(0, "下一步", false, false, false, false, false, true, bounds);
                openInstructions(fresh, current, button);
            }
        });
    }

    private boolean enteredSection(TrainingPage next) {
        if (next.kind == TrainingPage.Kind.REPORT || next.kind == TrainingPage.Kind.INSTRUCTIONS) return true;
        if (chapter == 0) return next.kind == TrainingPage.Kind.STEP_1 || next.kind == TrainingPage.Kind.STEP_2
                || next.kind == TrainingPage.Kind.STEP_3;
        if (chapter == 1) return next.kind == TrainingPage.Kind.CHAT || next.kind == TrainingPage.Kind.CHAT_INTRO;
        return next.kind == TrainingPage.Kind.FREE;
    }

    private PageModel.Line adaptiveButton(CampusService.Capture cap, TrainingPage page) {
        PageModel.Line start = page.homeStart();
        if (start != null) return start;
        CampusService.Capture fresh = service.capture(true);
        if (fresh == null || fresh.window != cap.window || !fresh.packageName.equals(cap.packageName)) return null;
        return service.adaptiveTrainingButton(fresh, new TrainingPage(fresh.coursePage(), fresh.lines));
    }

    private TrainingPage trainingPage(CampusService.Capture cap) {
        if (cap != lastCapture) {
            lastCapture = cap;
            lastPage = new TrainingPage(cap.coursePage(), cap.lines, followWindow == cap.window);
        }
        return lastPage;
    }

    private void followCards(CampusService.Capture cap, TrainingPage page) {
        if (page.cards == null || page.cards.sentences.isEmpty()) {
            waitRoute("正在等待 Step 2 句子卡片…", 8000);
            return;
        }
        SpeakingCards cards = page.cards;
        PageModel.Bounds viewport = service.trainingCardViewport(cap, cards);
        if (viewport == null) { waitRoute("正在确认跟读列表的范围…", 8000); return; }
        if (seekingFirst && page.lines.stream().anyMatch(line -> ReadAloudPage.followDirection(line.text))) {
            seekingFirst = false;
            stationaryScrolls = 0;
        }
        if (seekingFirst) {
            scrollCards(cap, cards, viewport, -(viewport.bottom - viewport.top) / 2);
            return;
        }
        PageModel.Line current = cards.current;
        for (PageModel.Line sentence : cards.sentences) {
            if (sentence.bounds.bottom <= viewport.top || sentence.bounds.top >= viewport.bottom) continue;
            seenCards.add(sentence.text);
            if (sentence != current && !cards.ambiguous(sentence.text)) {
                String score = cards.visibleScore(sentence.text);
                String key = scoreKey(TrainingPage.Kind.STEP_2, sentence.text);
                scores.observeExisting(key, score);
                if (scores.complete(key, score)) {
                    finishedCards.add(sentence.text);
                    deferredCards.remove(sentence.text);
                }
            }
        }
        observeExistingScore(page);
        boolean justFinished = current != null && accepted(page) && finishedCards.add(current.text);
        PageModel.Line pending = firstPending(cards, viewport);
        if (justFinished && (pending == null || pending.bounds.y() > current.bounds.y())) {
            foundBottom = false;
            stationaryScrolls = 0;
            int distance = cardDistance(cards, viewport, current, pending);
            if (distance >= (viewport.bottom - viewport.top) / 18) {
                scrollCards(cap, cards, viewport, distance);
                return;
            }
        }
        if (pending != null) {
            foundBottom = false;
            if (cards.ambiguous(pending.text)) { deferCard(pending.text, "ambiguous"); return; }
            if (current == null || !current.text.equals(pending.text)) { expandCard(cap, page, pending); return; }
            if (page.microphone() == null) { waitRoute("正在等待当前句麦克风…", 10000); return; }
            stationaryScrolls = 0;
            prepare(cap, page, ignored -> pending.text);
            return;
        }
        if (!foundBottom) {
            scrollCards(cap, cards, viewport, (viewport.bottom - viewport.top) / 3);
            return;
        }
        if (!deferredCards.isEmpty() && !retriedDeferredCards) {
            retriedDeferredCards = true;
            retrySweepCards.addAll(deferredCards);
            deferredCards.clear();
            seekingFirst = true;
            foundBottom = false;
            stationaryScrolls = rejectedScrolls = 0;
            scrollCards(cap, cards, viewport, -(viewport.bottom - viewport.top) / 2);
            return;
        }
        retrySweepCards.removeAll(finishedCards);
        deferredCards.addAll(retrySweepCards);
        if (seenCards.stream().anyMatch(sentence -> !finishedCards.contains(sentence) && !deferredCards.contains(sentence)
                && !timedOutCards.contains(sentence))) {
            finish("已到列表底部，还有识别到的句子未确认录完。");
            return;
        }
        String failure = deferredCards.isEmpty() ? "未切换到 Step 3。"
                : "仍有 " + deferredCards.size() + " 句未能展开，U校园未开放 Step 3。";
        open(cap, page.action("下一步"), p -> p.kind == TrainingPage.Kind.STEP_3, failure);
    }

    private PageModel.Line firstPending(SpeakingCards cards, PageModel.Bounds viewport) {
        int margin = foundBottom ? 1 : Math.max(8, (viewport.bottom - viewport.top) / 35);
        for (PageModel.Line sentence : cards.sentences) {
            if (!finishedCards.contains(sentence.text) && !deferredCards.contains(sentence.text)
                    && !timedOutCards.contains(sentence.text)
                    && sentence.bounds.y() >= viewport.top + margin
                    && sentence.bounds.y() <= viewport.bottom - margin) return sentence;
        }
        return null;
    }

    private int cardDistance(SpeakingCards cards, PageModel.Bounds viewport, PageModel.Line current, PageModel.Line next) {
        int height = viewport.bottom - viewport.top;
        if (next == null) next = cards.after(current.text);
        if (next == null) return height / 3;
        return Math.min(next.bounds.top - current.bounds.top, next.bounds.y() - viewport.top - height / 8);
    }

    private void scrollCards(CampusService.Capture cap, SpeakingCards cards, PageModel.Bounds viewport, int distance) {
        scrollDirection = Integer.signum(distance);
        scrollBefore = scrollKey(cards, viewport);
        scrollObserved = scrollBefore;
        scrollDelivered = null;
        scrollQuietAt = now();
        scrollSettleAt = now() + 650;
        deadline = now() + 5000;
        phase = Phase.SCROLLING;
        int ticket = ++scrollTicket;
        notice(seekingFirst ? "正在回到跟读列表开头…" : "已确认 " + finishedCards.size() + " 句，正在向下查看下一张卡片…");
        if (!service.swipeTrainingCards(cap, viewport, distance, stationaryScrolls + rejectedScrolls > 0, ok -> {
            if (active && phase == Phase.SCROLLING && ticket == scrollTicket) {
                scrollDelivered = ok;
                schedule(130);
            }
        })) scrollDelivered = false;
    }

    private void waitForCardScroll(CampusService.Capture cap, TrainingPage page) {
        if (now() >= deadline) { finish("滚动后未能确认跟读列表，已暂停。"); return; }
        if (page.kind != TrainingPage.Kind.STEP_2 || page.cards == null || page.cards.sentences.isEmpty()) return;
        PageModel.Bounds viewport = service.trainingCardViewport(cap, page.cards);
        if (viewport == null) return;
        String visible = scrollKey(page.cards, viewport);
        if (!visible.equals(scrollObserved)) {
            scrollObserved = visible;
            scrollQuietAt = now();
        }
        if (scrollDelivered == null || now() < scrollSettleAt || now() - scrollQuietAt < 240) return;
        if (!visible.equals(scrollBefore)) {
            stationaryScrolls = rejectedScrolls = 0;
        } else if (scrollDelivered) {
            rejectedScrolls = 0;
            if (++stationaryScrolls >= 2) {
                if (scrollDirection < 0) seekingFirst = false;
                else foundBottom = true;
                stationaryScrolls = 0;
            }
        } else if (++rejectedScrolls >= 2) {
            finish("跟读列表没有收到滑动，已停在当前句。");
            return;
        }
        resumeRoute();
    }

    private String scrollKey(SpeakingCards cards, PageModel.Bounds viewport) {
        StringBuilder key = new StringBuilder();
        for (PageModel.Line line : cards.sentences) {
            if (line.bounds.bottom <= viewport.top || line.bounds.top >= viewport.bottom) continue;
            key.append(line.text).append('@').append(line.bounds.y() / 16).append('\n');
        }
        return key.toString();
    }

    private void expandCard(CampusService.Capture cap, TrainingPage page, PageModel.Line sentence) {
        cardTarget = sentence.text;
        cardWindow = cap.window;
        cardAttempts = 0;
        cardRetryAt = now();
        deadline = now() + 5400;
        phase = Phase.OPENING_CARD;
        notice("正在展开下一句…");
        waitForCard(cap, page);
    }

    private void waitForCard(CampusService.Capture cap, TrainingPage page) {
        if (cap.window == cardWindow && page.kind == TrainingPage.Kind.STEP_2 && page.cards != null) {
            SpeakingCards cards = page.cards;
            if (cards.current != null && cards.current.text.equals(cardTarget) && !cards.ambiguous(cardTarget)) {
                deferredCards.remove(cardTarget);
                retrySweepCards.remove(cardTarget);
                resumeRoute();
                return;
            }
            if (cardAttempts >= 3 && now() >= cardRetryAt) {
                deferCard(cardTarget, "not_opened");
                return;
            }
            if (now() >= cardRetryAt && cardAttempts < 3) {
                PageModel.Bounds viewport = service.trainingCardViewport(cap, cards);
                if (viewport != null && !cards.ambiguous(cardTarget)) {
                    PageModel.Line target = cards.sentences.stream()
                            .filter(line -> line.text.equals(cardTarget) && line.bounds.y() >= viewport.top
                                    && line.bounds.y() < viewport.bottom)
                            .findFirst().orElse(null);
                    if (target != null) {
                        boolean accepted = cardAttempts++ == 0 && service.trainingClick(cap, target);
                        if (!accepted) accepted = service.trainingTap(cap, target.bounds);
                        trace("follow_card_open", (accepted ? "dispatched|" : "rejected|") + cardAttempts);
                        cardRetryAt = now() + (accepted ? 800 : 300);
                    }
                }
            }
        }
        if (now() >= deadline) deferCard(cardTarget, "not_opened");
    }

    private void deferCard(String sentence, String reason) {
        deferredCards.add(sentence);
        trace("follow_card_deferred", reason);
        notice("暂时未能展开这句，先处理其他卡片…");
        resumeRoute();
    }

    private void cloze(CampusService.Capture cap, TrainingPage page) {
        observeExistingScore(page);
        if (accepted(page)) {
            int number = page.state.question;
            if (number > 0 && number == page.state.questions) {
                completedSection = 0;
                chapter = 1;
                leaveSection(cap, page.submitExample() == null ? page.action("返回训练", "返回目录", "返回") : page.submitExample(),
                        next -> next.kind == TrainingPage.Kind.REPORT || next.kind == TrainingPage.Kind.MENU
                                || next.kind == TrainingPage.Kind.CHAT || next.kind == TrainingPage.Kind.CHAT_INTRO
                                || next.kind == TrainingPage.Kind.INSTRUCTIONS,
                        "已完成最后一题，U校园没有显示报告或训练目录。");
            } else {
                open(cap, page.action("下一题"), next -> next.kind == TrainingPage.Kind.STEP_3
                        && next.state.question == number + 1, "没有确认已切换到下一道填空。");
            }
            return;
        }
        if (page.scored() && page.microphone() == null) {
            // A low score without an exposed retake button is not a dead end.
            // The screenshot's last question has an actionable "提交" button.
            PageModel.Line continueButton = page.state.question == page.state.questions
                    ? page.submitExample() : page.action("下一题", "下一步");
            if (continueButton != null) {
                trace("score_no_retake_control", "score=" + page.state.score + "|question=" + page.state.question);
                if (page.state.question == page.state.questions) {
                    completedSection = 0;
                    chapter = 1;
                    leaveSection(cap, continueButton, next -> next.kind == TrainingPage.Kind.REPORT
                                    || next.kind == TrainingPage.Kind.MENU || next.kind == TrainingPage.Kind.CHAT
                                    || next.kind == TrainingPage.Kind.CHAT_INTRO || next.kind == TrainingPage.Kind.INSTRUCTIONS,
                            "已尝试提交最后一题，但 U校园没有切换到报告或下一项。");
                } else {
                    int number = page.state.question;
                    open(cap, continueButton, next -> next.kind == TrainingPage.Kind.STEP_3
                            && next.state.question > number, "未能进入下一道填空题。");
                }
                return;
            }
        }
        if (page.state.question <= 0 || page.task.isEmpty() || page.bank.isEmpty() || page.microphone() == null) {
            waitRoute("正在等待填空题干、词库和麦克风…", 10000);
            return;
        }
        prepare(cap, page, retry -> TrainingModel.cloze(config, page, example, retry));
    }

    private void dialogue(CampusService.Capture cap, TrainingPage page) {
        chapter = 1;
        if (page.state.turnLimit > 0 && page.state.turns >= page.state.turnLimit) {
            if (now() - routeSince < 2500) return;
            completedSection = 1;
            open(cap, page.action("结束对话", "结束练习", "end"), next -> next.kind == TrainingPage.Kind.REPORT
                    || next.kind == TrainingPage.Kind.FREE || next.kind == TrainingPage.Kind.MENU,
                    "话轮已满，未确认进入对话报告。");
            return;
        }
        if (page.state.turns < 0) {
            waitRoute("AI 对话已打开，正在识别话轮数…", 12000);
            return;
        }
        if (page.latestReply.isEmpty()) {
            waitRoute("等待 AI 发言的文字显示在页面上…", 22000);
            return;
        }
        if (page.state.turns <= sentTurn || page.latestReply.equals(lastAi) && page.replyCount <= lastSentAiCount) {
            waitRoute("等待 AI 的下一条发言…", 40000);
            return;
        }
        String key = page.identity();
        if (!lastState.equals(key)) {
            lastState = key;
            stableAt = now();
        }
        long stableDelay = page.latestReply.matches("(?s).*[.!?][\"’”]?$") ? 350 : 1800;
        long untilStable = stableAt + stableDelay - now();
        if (untilStable > 0) {
            notice("已识别 AI 发言，正在读取完整句子…");
            schedule(untilStable);
            return;
        }
        String input = (conversationTask.isEmpty() ? "" : "本次对话任务\n" + conversationTask + "\n")
                + (lastExchange.isEmpty() ? "" : "上一轮对话\n" + lastExchange + "\n")
                + "AI 的最新发言\n" + page.latestReply;
        prepare(cap, page, retry -> TrainingModel.speak(config, true, input, TrainingModel.DIALOGUE_WORDS, retry));
    }

    private boolean checkReplyPage(CampusService.Capture cap, TrainingPage page) {
        if (cap.window == itemWindow && page.kind == TrainingPage.Kind.UNKNOWN) return true;
        boolean dialogue = itemKind == TrainingPage.Kind.CHAT;
        if (dialogue ? cap.window != itemWindow
                || page.kind != TrainingPage.Kind.CHAT && page.kind != TrainingPage.Kind.UNKNOWN
                || page.state.turns >= 0 && page.state.turns != itemTurn
                : !sameItem(cap, page)) {
            finish("题目已经变化，已取消准备中的语音。");
            return false;
        }
        if (!dialogue) return true;
        if (page.state.turns == itemTurn && !page.latestReply.isEmpty() && !page.latestReply.equals(activeReply)) {
            // The current subtitle can still be expanding. Prepare again once the new text settles.
            cancelAnswer();
            audio.stop();
            currentSpeech = "";
            resumeRoute();
            return false;
        }
        return true;
    }

    private void waitForAudioReady(CampusService.Capture cap, TrainingPage page) {
        if (!checkReplyPage(cap, page)) return;
        if (itemKind == TrainingPage.Kind.CHAT && page.state.playback == USpeakingEngine.Media.PLAYING) {
            notice("语音已备好，等 AI 播完就开麦…");
            deadline = now() + 15000;
        } else if (now() > deadline) {
            finish("语音已备好，但 U校园未恢复发言入口。");
        } else if (sameItem(cap, page)) {
            startRecording(cap, page);
        }
    }

    private void report(CampusService.Capture cap, TrainingPage page) {
        int section = completedSection >= 0 ? completedSection : page.isFreeReport() ? 2
                : page.isDialogueReport() ? 1 : chapter;
        if (retryLowReport(cap, page, section)) return;
        verifiedSections[section] = true;
        if (section == 2) {
            finish("已看到自由表达结果，口语训练结束。");
            return;
        }
        chapter = section + 1;
        completedSection = -1;
        leaveSection(cap, page.leaveReport(),
                next -> next.kind == TrainingPage.Kind.MENU || next.kind == TrainingPage.Kind.CHAT
                        || next.kind == TrainingPage.Kind.CHAT_INTRO || next.kind == TrainingPage.Kind.FREE
                        || next.kind == TrainingPage.Kind.INSTRUCTIONS,
                "已看到评价报告，未确认进入下一项。");
    }

    private boolean retryLowReport(CampusService.Capture cap, TrainingPage page, int section) {
        String grade = page.reportScore();
        int previousSection = itemKind == TrainingPage.Kind.CHAT ? 1 : itemKind == TrainingPage.Kind.FREE ? 2 : 0;
        if (platformTimedOut && section == previousSection) return false;
        if (reportRetested[section] || !scores.below(grade)) return false;
        PageModel.Line retry = page.retestButton();
        if (retry == null) {
            unavailableRetests.add(TrainingPage.SECTIONS[section]);
            return false;
        }
        reportRetested[section] = true;
        retestNavigation = section;
        chapter = section;
        completedSection = -1;
        if (section == 0) {
            seenCards.clear();
            finishedCards.clear();
            deferredCards.clear();
            retrySweepCards.clear();
            seekingFirst = true;
            foundBottom = retriedDeferredCards = false;
            stationaryScrolls = rejectedScrolls = 0;
        } else if (section == 1) {
            sentTurn = -1;
            lastAi = lastState = lastExchange = "";
            lastSentAiCount = 0;
        }
        notice(TrainingPage.SECTIONS[section] + "得分 " + grade + "，正在自动复测…");
        open(cap, retry, next -> next.kind != TrainingPage.Kind.REPORT && enteredSection(next), "未能进入复测。");
        return true;
    }

    private interface Answer { String get(Consumer<String> retrying) throws Exception; }

    private void prepare(CampusService.Capture cap, TrainingPage page, Answer content) {
        boolean retry = itemKind == page.kind && item.equals(page.identity()) && !currentSpeech.isEmpty();
        item = page.identity();
        itemWindow = cap.window;
        itemKind = page.kind;
        itemTurn = page.state.turns;
        itemQuestion = page.state.question;
        itemTotal = page.state.questions;
        platformTimedOut = timeoutDismissed = false;
        activeReply = page.latestReply;
        aiCountAtSend = page.replyCount;
        previousScore = page.state.score;
        sawScoring = false;
        scoreReadyAt = 0;
        audioRetried = false;
        networkRetried = false;
        chatTouchAt = chatSentAt = -1;
        chatSoftStarted = chatSendRetried = false;
        audio.warmUp();
        if (retry) {
            prepareSpeech(currentSpeech);
            return;
        }
        if (itemKind == TrainingPage.Kind.STEP_2) {
            prepareSpeech(item);
            return;
        }
        currentSpeech = "";
        requestReply(content);
    }

    private void requestReply(Answer content) {
        long timeout = itemKind == TrainingPage.Kind.CHAT ? 30000 : itemKind == TrainingPage.Kind.FREE ? 60000 : 150000;
        pendingAnswer = content;
        if (networkIsDown()) {
            if (networkRetried) failReply("网络再次断开，当前任务已暂停。");
            else waitForConnection();
            return;
        }
        phase = Phase.GENERATING_REPLY;
        deadline = now() + timeout;
        notice(itemKind == TrainingPage.Kind.CHAT ? "文本模型正在生成本轮回答…"
                : "正在根据当前题目生成英文回答…");
        int request = ++replyGeneration;
        answerRequest = ModelClient.WORK.submit(() -> {
            try {
                String answer = content.get(reason -> ui.post(() -> {
                    if (active && phase == Phase.GENERATING_REPLY && request == replyGeneration) {
                        trace("model_retry", reason);
                        notice("文本模型正在自动重试当前任务…");
                    }
                }));
                ui.post(() -> {
                    if (active && phase == Phase.GENERATING_REPLY && request == replyGeneration) {
                        answerRequest = null;
                        pendingAnswer = null;
                        prepareSpeech(answer);
                    }
                });
            } catch (Exception e) {
                ui.post(() -> {
                    if (active && phase == Phase.GENERATING_REPLY && request == replyGeneration) {
                        replyFailure("文本模型：" + ModelClient.message(e));
                    }
                });
            }
        });
    }

    private void replyFailure(String message) {
        cancelAnswer();
        if (!networkRetried && networkIsDown()) {
            waitForConnection();
            return;
        }
        failReply(message);
    }

    private boolean networkIsDown() {
        ConnectivityManager manager = service.getSystemService(ConnectivityManager.class);
        return manager != null && manager.getActiveNetwork() == null;
    }

    private void waitForConnection() {
        networkRetried = true;
        phase = Phase.WAIT_NETWORK;
        deadline = now() + 40000;
        notice("网络已断开，等待恢复后继续当前题…");
        schedule(0);
    }

    private void waitForNetwork(CampusService.Capture cap, TrainingPage page) {
        if (!checkReplyPage(cap, page)) return;
        if (!networkIsDown() && page.kind != TrainingPage.Kind.UNKNOWN) {
            requestReply(pendingAnswer);
        } else if (now() >= deadline) failReply("网络长时间未恢复，当前任务已暂停。");
    }

    private void failReply(String message) {
        modelReplyFailed = true;
        trace("model_error", message);
        finish(message);
    }

    boolean stoppedOnModelReply() { return modelReplyFailed; }

    private void cancelAnswer() {
        replyGeneration++;
        if (answerRequest != null) {
            answerRequest.cancel(true);
            answerRequest = null;
        }
    }

    private void prepareSpeech(String speech) {
        currentSpeech = speech;
        phase = Phase.PREPARING_AUDIO;
        boolean interactive = itemKind == TrainingPage.Kind.CHAT || itemKind == TrainingPage.Kind.FREE;
        deadline = now() + (interactive ? 23000 : 150000);
        notice("正在准备" + audio.voiceDescription() + "…");
        audio.prepare(speech, interactive);
    }

    @Override public void ready(int durationMs) {
        if (!active || phase != Phase.PREPARING_AUDIO) return;
        trace("audio_prepared", audio.voiceDescription() + "|" + durationMs + "ms");
        phase = Phase.AUDIO_READY;
        deadline = now() + 15000;
        notice("语音已备好，正在确认录音入口…");
        schedule(0);
    }

    private void startRecording(CampusService.Capture cap, TrainingPage page) {
        phase = Phase.WAIT_RECORD;
        deadline = now() + 12000;
        startAttempts = 0;
        chatTouchAt = -1;
        nextStartAttempt = now();
        notice(itemKind == TrainingPage.Kind.CHAT ? "语音已备好，正在定位「按住说话」…"
                : "正在打开麦克风，等待 U校园显示录音中…");
        if (itemKind == TrainingPage.Kind.CHAT) {
            startChatHold(cap, page);
        } else tryMicrophone(cap, page);
    }

    private void retryChatHold(CampusService.Capture cap, TrainingPage page) {
        if (findingHold || startAttempts >= MIC_ATTEMPTS) return;
        // Do NOT cancel an active touch just because the WebView did not change its label.
        // Cancelling it was the repeated-press/no-audio deadlock in 2.0.2.
        if (hold != null && !hold.finished()) return;
        CampusService.Capture fresh = service.capture();
        if (fresh != null && fresh.window == cap.window) startChatHold(fresh, trainingPage(fresh));
    }

    private void startChatHold(CampusService.Capture cap, TrainingPage page) {
        if (!sameItem(cap, page) || page.chatHolding()) return;
        PageModel.Bounds touch = service.trainingHoldBounds(cap, page.holdControl());
        if (touch != null && startAttempts < 2) {
            beginChatTouch(cap, touch);
            return;
        }
        findingHold = true;
        service.captureTrainingControl(cap, false, discovered -> {
            findingHold = false;
            if (!active || phase != Phase.WAIT_RECORD) return;
            CampusService.Capture fresh = service.capture();
            if (fresh == null) return;
            TrainingPage current = trainingPage(fresh);
            if (!sameItem(fresh, current) || current.chatHolding()) return;
            PageModel.Bounds target = discovered == null ? touch : discovered;
            if (target != null) beginChatTouch(fresh, target);
            else nextStartAttempt = now() + MIC_RETRY_MS;
        });
    }

    private void beginChatTouch(CampusService.Capture cap, PageModel.Bounds target) {
        int index = startAttempts++;
        int x = target.left + (target.right - target.left) * (index == 0 ? 50 : index == 1 ? 62 : 38) / 100;
        nextStartAttempt = now() + 1600;
        if (!service.trainingPoint(cap, x, target.y())) {
            trace("record_press", "point_unavailable|" + startAttempts);
            return;
        }
        notice(index == 0 ? "正在按住说话，等待录音提示…" : "还未确认录音，正在重新按住说话…");
        hold = new TrainingHold(service, ui, cap.window, target, x, () -> {
            if (!active) return;
            if (phase == Phase.WAIT_RECORD) {
                nextStartAttempt = now();
                schedule(80);
            }
            else if (phase == Phase.SPEAKING) {
                trace("record_gesture_interrupted", "while_playing");
                error("AI 对话录音手势被系统中断，正在尝试恢复。");
            } else if (phase == Phase.WAIT_RESULT) {
                trace("record_release_interrupted", "send_was_not_acknowledged");
                schedule(0);
            } else if (!platformTimedOut) error("AI 对话的按住录音中断。");
        });
        boolean dispatched = hold.start();
        chatTouchAt = dispatched ? now() : -1;
        trace("record_press", (dispatched ? "dispatched|" : "rejected|") + startAttempts);
        if (!dispatched) nextStartAttempt = now() + 400;
    }

    private void tryMicrophone(CampusService.Capture cap, TrainingPage page) {
        if (!sameItem(cap, page)) return;
        PageModel.Line mic = page.microphone();
        if (mic == null) return;
        boolean retry = startAttempts++ > 0;
        if (retry && startAttempts == 2) notice("页面仍在待录音，正在重新定位并触摸麦克风…");
        boolean pressed = !retry && mic.localControl && service.pressFollowMicrophone(cap, mic, false);
        if (!pressed) {
            CampusService.Capture fresh = cap.tree != null ? cap : service.capture(true);
            TrainingPage current = fresh == null ? null : trainingPage(fresh);
            PageModel.Line button = current != null && sameItem(fresh, current) ? current.microphone() : null;
            if (button != null) pressed = service.pressFollowMicrophone(fresh, button, retry);
        }
        nextStartAttempt = now() + (pressed ? MIC_RETRY_MS : 450);
    }

    @Override public void done() {
        if (!active || phase != Phase.SPEAKING) return;
        if (itemKind == TrainingPage.Kind.CHAT) {
            boolean released = hold != null && hold.releaseToSend();
            chatSentAt = now();
            phase = Phase.WAIT_RESULT;
            stopConfirmed = false;
            deadline = now() + 40000;
            notice(released ? "已播完，正在原地松开发送，等待 AI 确认…" : "已播完，正在读取 U校园的对话结果…");
        } else {
            phase = Phase.WAIT_STOP;
            stopAttempts = submitAttempts = 0;
            stopConfirmed = false;
            nextStopAttempt = now();
            submitRetryAt = 0;
            deadline = now() + 11500;
            notice("已播完，正在结束本次录音…");
        }
        schedule(0);
    }

    @Override public void playbackRecovery(String detail) {
        if (!active || phase != Phase.SPEAKING) return;
        trace("playback_recovery", detail);
        notice(detail);
    }

    @Override public void playbackStarted(String device) {
        if (!active || phase != Phase.SPEAKING) return;
        trace("playback_progress", device);
        notice("正在朗读 · " + device);
    }

    @Override public void error(String message) {
        if (!active) return;
        trace("audio_error", phase.name() + "|" + message);
        boolean personal = audio.usingPersonalVoice();
        boolean canRetry = !audioRetried && (itemKind == TrainingPage.Kind.CHAT
                && (phase == Phase.PREPARING_AUDIO || phase == Phase.SPEAKING)
                || itemKind == TrainingPage.Kind.FREE && phase == Phase.PREPARING_AUDIO && personal);
        if (!canRetry) {
            finish(message);
            return;
        }
        audioRetried = true;
        if (personal) audio.switchToSystemVoice();
        else audio.stop();
        if (phase == Phase.SPEAKING && hold != null) hold.cancel();
        phase = Phase.RECOVER_AUDIO;
        deadline = now() + 6500;
        notice(personal ? "个人音色失败，改用系统英语语音重试…"
                : "语音失败，正在恢复本轮并重试…");
        schedule(0);
    }

    private void recoverAudio(CampusService.Capture cap, TrainingPage page) {
        if (!checkReplyPage(cap, page)) return;
        boolean ready = page.kind == TrainingPage.Kind.FREE && itemKind == TrainingPage.Kind.FREE && page.microphone() != null
                || page.kind == TrainingPage.Kind.CHAT && page.state.turns == itemTurn && !page.chatHolding()
                    && (hold == null || hold.finished());
        if (ready) {
            hold = null;
            prepareSpeech(currentSpeech);
        } else if (now() > deadline) {
            finish("U校园未恢复到待录音状态，已暂停本轮重试。");
        }
    }

    private boolean stopAcknowledged(CampusService.Capture cap, TrainingPage page) {
        if (page.kind == TrainingPage.Kind.REPORT) return true;
        if (itemKind == TrainingPage.Kind.STEP_2 && page.kind == TrainingPage.Kind.STEP_3) return true;
        if (page.kind == TrainingPage.Kind.MENU) {
            return itemKind == TrainingPage.Kind.FREE && page.hasReportFor("自由表达")
                    || itemKind == TrainingPage.Kind.STEP_3 && itemQuestion > 0 && itemQuestion == itemTotal
                    && page.hasReportFor("范例学习");
        }
        return sameItem(cap, page) && !page.recording() && (page.recordingEnded()
                || stopAttempts > 0 && page.action("提交录音", "提交作答", "保存并提交") != null);
    }

    private void waitForStop(CampusService.Capture cap, TrainingPage page) {
        if (stopAcknowledged(cap, page)) {
            phase = Phase.WAIT_RESULT;
            deadline = now() + 40000;
            notice("录音已结束，正在等提交或评分确认…");
            result(cap, page);
            return;
        }
        if (advanceAfterOfficialTimeout(cap, page)) return;
        if (now() >= deadline) {
            finish("语音已播完，但 U校园未确认停止录音，流程暂停在当前题。");
            return;
        }
        if (now() < nextStopAttempt || !sameItem(cap, page) || !page.recording()) return;
        if (stopAttempts >= 3) return;
        CampusService.Capture fresh = service.capture(true);
        if (fresh == null) return;
        TrainingPage current = trainingPage(fresh);
        if (!sameItem(fresh, current) || !current.recording()) return;
        service.trainingStop(fresh, current, stopAttempts++ > 0);
        if (stopAttempts == 2) notice("页面仍在录音，已改用触摸停止键…");
        nextStopAttempt = now() + 1500;
    }

    /**
     * Only a stable, ready-to-record UI on the same dialogue turn permits a bounded replay.
     * Never resend during loading/scoring/recording, or after the turn has advanced.
     */
    private boolean replayChatIfNoSend(CampusService.Capture cap, TrainingPage page) {
        if (chatSendRetried || hold == null || !hold.finished() || !hold.sendRequested()
                || cap.window != itemWindow || page.kind != TrainingPage.Kind.CHAT
                || page.state.turns != itemTurn || page.state.loading
                || page.state.issue != USpeakingEngine.Issue.NONE || page.chatHolding()
                || page.holdControl() == null || chatSentAt < 0) return false;
        long delay = hold.sendAcknowledged() ? 16000 : 700;
        if (now() - chatSentAt < delay) return false;
        chatSendRetried = true;
        trace("chat_send_retry", hold.sendAcknowledged() ? "no_new_turn" : "release_cancelled");
        hold = null;
        chatTouchAt = chatSentAt = -1;
        phase = Phase.PREPARING_AUDIO;
        notice("当前话轮没有收到发送结果，正在自动重试最后一次…");
        prepareSpeech(currentSpeech);
        return true;
    }

    private void result(CampusService.Capture cap, TrainingPage page) {
        if (itemKind == TrainingPage.Kind.CHAT && hold != null && !hold.finished()) {
            if (now() > deadline) {
                hold.cancel();
                finish("播放已结束，但连续触摸未能结束；已安全停止并记录诊断。");
            }
            return;
        }
        if (itemKind == TrainingPage.Kind.CHAT && replayChatIfNoSend(cap, page)) return;
        if (!stopConfirmed && cap.window == itemWindow && page.confirmsTrainingAction()
                && service.trainingClick(cap, page.confirmButton())) {
            stopConfirmed = true;
            deadline = now() + 40000;
            return;
        }
        if (page.kind == TrainingPage.Kind.REPORT) {
            if (itemKind == TrainingPage.Kind.FREE && TrainingScore.percentage(page.reportScore()) >= 0) {
                int attempt = scores.record(scoreKey(itemKind, item));
                if (attempt >= 2) reportRetested[2] = true;
                if (retryLowReport(cap, page, 2)) return;
            }
            completedSection = itemKind == TrainingPage.Kind.FREE ? 2 : itemKind == TrainingPage.Kind.CHAT ? 1 : 0;
            resumeRoute();
            return;
        }
        if (itemKind == TrainingPage.Kind.STEP_2 && page.kind == TrainingPage.Kind.STEP_3) {
            if (seenCards.stream().allMatch(sentence -> finishedCards.contains(sentence) || timedOutCards.contains(sentence)
                    || sentence.equals(item))) resumeRoute();
            else finish("U口语已经离开跟读列表，仍有句子未确认录完。");
            return;
        }
        if (itemKind == TrainingPage.Kind.STEP_3 && itemQuestion > 0 && itemQuestion == itemTotal
                && page.kind == TrainingPage.Kind.MENU && page.hasReportFor("范例学习")) {
            chapter = 1;
            resumeRoute();
            return;
        }
        if (itemKind == TrainingPage.Kind.CHAT && cap.window == itemWindow
                && page.kind == TrainingPage.Kind.CHAT && page.state.turns > itemTurn) {
            sentTurn = itemTurn;
            lastAi = activeReply;
            lastSentAiCount = aiCountAtSend;
            lastExchange = "AI: " + activeReply + "\nStudent: " + currentSpeech;
            hold = null;
            resumeRoute();
        } else if (itemKind != TrainingPage.Kind.CHAT && sameItem(cap, page)) {
            if (page.state.recording == USpeakingEngine.Media.SCORING || page.state.recording == USpeakingEngine.Media.SAVING) {
                sawScoring = true;
            } else if (page.scored()) {
                if (scoreReadyAt == 0) scoreReadyAt = now();
                if (sawScoring || !previousScore.equals(page.state.score)
                        || !platformTimedOut && recordAt > 0 && now() - scoreReadyAt >= 600) markScore(page);
            } else if (page.submitRecording() != null && submitAttempts < 3 && now() >= submitRetryAt) {
                PageModel.Line submit = page.submitRecording();
                if (submitAttempts++ == 0) service.trainingClick(cap, submit);
                else service.trainingTap(cap, submit.bounds);
                submitRetryAt = now() + 1450;
                notice("正在提交本题录音，等待 U口语确认…");
            } else if (itemKind == TrainingPage.Kind.FREE && page.state.completed) {
                finish("U校园已确认自由表达完成，口语训练结束。");
            }
        } else if (itemKind == TrainingPage.Kind.FREE && page.kind == TrainingPage.Kind.MENU && page.hasReportFor("自由表达")) {
            finish("已看到自由表达报告，口语训练结束。");
        }
        if (active && phase == Phase.WAIT_RESULT && advanceAfterOfficialTimeout(cap, page)) return;
        if (active && phase == Phase.WAIT_RESULT && now() > deadline) {
            finish(itemKind == TrainingPage.Kind.CHAT
                    ? "已松开发送，但没有看到话轮增加，已暂停在这一轮。"
                    : "未看到本题的新评分，已暂停在这一题。");
        }
    }

    private boolean advanceAfterOfficialTimeout(CampusService.Capture cap, TrainingPage page) {
        if (!platformTimedOut || cap.window != itemWindow) return false;
        if (itemKind == TrainingPage.Kind.STEP_3 && page.kind == itemKind
                && itemQuestion > 0 && page.state.question > itemQuestion) {
            resumeRoute();
            return true;
        }
        boolean oldScore = page.scored() && previousScore.equals(page.state.score) && !sawScoring;
        if (recording(page) || page.kind == TrainingPage.Kind.REPORT
                || page.recordingEnded() && !oldScore || page.submitRecording() != null) return false;
        if (!timeoutDismissed && page.state.recordingTimedOut()) {
            PageModel.Line dismiss = page.action("我知道了", "确定", "知道了");
            if (dismiss != null && service.trainingClick(cap, dismiss)) {
                timeoutDismissed = true;
                return true;
            }
        }
        if (!sameItem(cap, page)) return false;
        if (itemKind == TrainingPage.Kind.STEP_2) {
            timedOutCards.add(item);
            notice("U校园已结束这一句，继续找下一张卡片…");
            resumeRoute();
            return true;
        }
        if (itemKind == TrainingPage.Kind.STEP_3) {
            PageModel.Line next = page.action("下一题", "下一步");
            if (next == null) return false;
            open(cap, next, p -> p.kind == TrainingPage.Kind.REPORT || p.kind == TrainingPage.Kind.MENU
                    || p.kind == TrainingPage.Kind.STEP_3 && !p.identity().equals(item), "U校园未打开下一项。");
            return true;
        }
        if (itemKind == TrainingPage.Kind.CHAT) {
            PageModel.Line end = page.action("结束对话", "结束练习", "end");
            if (end == null) return false;
            completedSection = 1;
            open(cap, end, p -> p.kind == TrainingPage.Kind.REPORT || p.kind == TrainingPage.Kind.MENU
                    || p.kind == TrainingPage.Kind.FREE, "U校园未打开对话结果。");
            return true;
        }
        finish("U校园已结束本题录音，当前没有新的评分结果。");
        return true;
    }

    private void markScore(TrainingPage page) {
        String key = scoreKey(itemKind, item);
        int attempt = scores.record(key);
        if (platformTimedOut) {
            timedOutScores.add(key);
            resumeRoute();
            return;
        }
        if (!scores.meets(page.state.score)) {
            notice("本次 " + page.state.score + " 分，低于 " + minimumScore + " 分，"
                    + (attempt == 1 ? "准备重录一次。" : "本题两次已完成，继续。"));
        }
        resumeRoute();
    }

    private boolean accepted(TrainingPage page) {
        String key = scoreKey(page.kind, page.identity());
        return page.scored() && (timedOutScores.contains(key) || scores.complete(key, page.state.score));
    }

    private void observeExistingScore(TrainingPage page) {
        if (page.scored() && !page.identity().isEmpty()) scores.observeExisting(scoreKey(page.kind, page.identity()), page.state.score);
    }

    private static String scoreKey(TrainingPage.Kind kind, String id) { return kind.name() + "|" + id; }

    private boolean sameItem(CampusService.Capture cap, TrainingPage page) {
        return cap.window == itemWindow && packageName.equals(cap.packageName) && page.kind == itemKind
                && (itemKind == TrainingPage.Kind.CHAT ? page.state.turns == itemTurn
                    || page.state.turns < 0 && hold != null && page.chatHolding() : item.equals(page.identity()));
    }

    private boolean recording(TrainingPage page) {
        return itemKind == TrainingPage.Kind.CHAT ? page.chatHolding() : page.recording();
    }

    private void open(CampusService.Capture cap, PageModel.Line control, Predicate<TrainingPage> check, String error) {
        if (control == null) { finish(error); return; }
        TrainingPage origin = trainingPage(cap);
        if (origin.kind == TrainingPage.Kind.HOME || origin.kind == TrainingPage.Kind.MENU
                || origin.kind == TrainingPage.Kind.INSTRUCTIONS) {
            service.trainingTap(cap, control.bounds);
        } else {
            service.trainingClick(cap, control);
        }
        awaitNavigation(cap, origin, control.text, check, error);
    }

    private void leaveSection(CampusService.Capture cap, PageModel.Line control, Predicate<TrainingPage> check, String error) {
        if (control != null) {
            open(cap, control, check, error);
            navCanBack = true;
        } else if (service.trainingBack(cap)) {
            awaitNavigation(cap, trainingPage(cap), "", check, error);
            navRetryAt = Long.MAX_VALUE;
        } else finish(error);
    }

    private void awaitNavigation(CampusService.Capture cap, TrainingPage origin, String label,
                                 Predicate<TrainingPage> check, String error) {
        phase = Phase.NAVIGATING;
        navConfirmed = false;
        navCanBack = false;
        navAttempts = 1;
        navKind = origin.kind;
        navOrigin = origin.identity();
        navWindow = cap.window;
        navLabel = label;
        navRetryAt = now() + 2400;
        arrived = check;
        navigationError = error;
        deadline = now() + 14000;
    }

    private void waitRoute(String text, long timeout) {
        if (now() - routeSince > timeout) finish(text.replace("…", "超时，已暂停。"));
        else notice(text);
    }

    private void resumeRoute() {
        phase = Phase.ROUTE;
        routeSince = now();
        schedule(40);
    }

    private void schedule(long delay) {
        long due = now() + delay;
        if (scheduledAt != 0 && scheduledAt <= due) return;
        scheduledAt = due;
        ui.removeCallbacks(cycle);
        ui.postDelayed(cycle, delay);
    }

    void pageChanged() {
        if (active && (phase == Phase.ROUTE || phase == Phase.NAVIGATING || phase == Phase.OPENING_CARD || phase == Phase.AUDIO_READY
                || phase == Phase.WAIT_RECORD || phase == Phase.WAIT_STOP || phase == Phase.WAIT_RESULT
                || phase == Phase.RECOVER_AUDIO || phase == Phase.SCROLLING || phase == Phase.SPEAKING)) schedule(90);
    }

    private void trace(String event, String detail) { monitor.trainingEvent(event, detail); }

    private void notice(String message) {
        if (phase != tracedPhase) {
            long time = now();
            trace("training_stage", phase.name() + "|turn=" + itemTurn
                    + (tracedPhase == null ? "" : "|prior_ms=" + (time - tracedAt)));
            tracedPhase = phase;
            tracedAt = time;
        }
        monitor.trainingProgress(message);
    }

    void finish(String message) {
        if (!active) return;
        active = false;
        trace("training_finished", phase.name() + (tracedPhase == null ? "" : "|stage_ms=" + (now() - tracedAt)));
        ui.removeCallbacks(cycle);
        cancelAnswer();
        audio.stop();
        if (hold != null) hold.cancel();
        String details = deferredCards.isEmpty() ? "" : " 本次仍有 " + deferredCards.size() + " 句跟读未展开确认。";
        if (!timedOutCards.isEmpty()) details += " U校园有 " + timedOutCards.size() + " 句录音超时，已继续其他句。";
        if (!unavailableRetests.isEmpty()) details += " 以下未达标项没有可用的复测入口：" + String.join("、", unavailableRetests) + "。";
        monitor.trainingFinished(this, message + details);
    }

    private static long now() { return SystemClock.uptimeMillis(); }
}
