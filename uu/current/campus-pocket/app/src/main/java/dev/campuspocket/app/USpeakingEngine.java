package dev.campuspocket.app;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class USpeakingEngine {
    enum Screen {
        BACKGROUND("目标不在前台"), HOME("U校园首页"), MODULE_HOME("U听说首页"),
        COURSES("课程或教材"), TASK_LIST("任务列表"), LISTENING("听力"),
        READ_ALOUD("朗读"), SHADOWING("跟读"), EXAMPLE("范例学习"), DIALOGUE("对话"),
        ROLE_PLAY("角色扮演"), SPEAKING("口语问答"), FREE_SPEECH("自由表达"), RESULT("结果"),
        UPLOADING("录音上传"), TASK_CARD("阅读任务卡"), ERROR("异常"), PRIVATE("受保护页面"), UNKNOWN("页面待识别");

        final String label;
        Screen(String label) { this.label = label; }
    }

    enum Media {
        UNKNOWN("未确认"), READY("待开始"), PLAYING("播放中"), ENDED("已播完"),
        STALLED("进度暂未变化"), PREPARING("准备录音"), RECORDING("录音中"),
        SAVING("上传或保存中"), SCORING("已提交，等待评分"), SAVED("已保存"), SCORED("已出分");

        final String label;
        Media(String label) { this.label = label; }
    }

    enum Issue {
        NONE(""), NETWORK("网络异常"), PERMISSION("权限提示"), UPLOAD("录音上传失败"),
        TIMEOUT("已超时"), TOO_SHORT("录音过短"), OTHER("页面错误");

        final String label;
        Issue(String label) { this.label = label; }
    }

    enum Scope {
        OUTSIDE, ENTRY, CONFIRMED, UNKNOWN
    }

    private static final Pattern MODULE = pattern("^(?:u[ ·-]*听说|u[ ·-]*speaking)$");
    private static final Pattern HOME = pattern("^(?:首页|主页|应用首页|工作台|学习首页|home)$");
    private static final Pattern COURSES = pattern("^(?:我的课程|课程列表|课程|我的教材|教材选择|教材列表|选择班级|班级列表|my courses|courses|textbooks)$");
    private static final Pattern EXAMPLE = pattern("^(?:范例学习|示例学习|范例跟读|model learning|example study)$");
    private static final Pattern DIALOGUE = pattern("^(?:ai\\s*口语对话|开始对话练习|对话练习|情景对话|对话跟读|对话学习|分角色对话|dialogue practice|practice the dialogue)$");
    private static final Pattern ROLE = pattern("^(?:角色扮演|角色练习|角色对话|模拟对话|role[- ]?play(?:ing)?)(?:$|[：:].*)");
    private static final Pattern LISTEN = pattern("^(?:听力练习|听力理解|听力|请听[录音音频对话材料].*|听(?:录音|音频)[，, ].*|listening(?: comprehension| practice)?|listen (?:to|and answer)\\b.*)$");
    private static final Pattern STEP = pattern("^(?:step\\s*(\\d{1,2})|第\\s*(\\d{1,2})\\s*步)(?:[：:. ]+.*)?$");
    private static final Pattern ACTIVE_STEP = pattern("^(?:当前(?:是|为)?\\s*(?:step|第)\\s*(\\d{1,2})\\s*步?|(?:step\\s*(\\d{1,2})|第\\s*(\\d{1,2})\\s*步)\\s*[，,·:： ]\\s*(?:当前|进行中|current))$");
    private static final Pattern SCORE = pattern("^(?:(?:本题|口语|朗读|录音|跟读|本次)?(?:得分|成绩|评分)|(?:your )?score)\\s*[:：]?\\s*(\\d{1,3}(?:\\.\\d+)?(?:\\s*/\\s*\\d{1,3})?)\\s*(?:分)?$");
    private static final Pattern REMAINING = pattern("(?:剩余(?:时间)?|倒计时|remaining)\\s*[：: ]*\\s*(?:(\\d{1,3}):([0-5]\\d)|(\\d{1,3})\\s*(?:秒|secs?|seconds?))");
    private static final Pattern RECORD_CLOCK = pattern("(?:录音中|录音时长|recording)\\s*[，,：: ]+\\s*(\\d{1,3}):([0-5]\\d)");
    private static final Pattern UPLOADING = pattern("^(?:正在上传(?:录音|音频)?|录音上传中|正在保存录音|uploading(?: recording)?)$");
    private static final Pattern SCORING = pattern("^(?:已提交[，,、\\s]*等待评分|等待评分|正在评分|评分中|正在测评|evaluating recording)$");
    private static final Pattern COMPLETE = pattern("^(?:本任务已完成|任务已完成|评测完成|测评完成|completed)$");
    private static final Pattern SUBMITTED = pattern("^(?:已提交|提交成功|submitted)$");
    private static final Pattern POINTS = pattern("^(\\d{1,3}(?:\\.\\d+)?)\\s*分$");
    private static final Pattern TURNS = pattern("话轮数\\s*[:：]?\\s*(\\d{1,3})\\s*/\\s*(\\d{1,3})");
    private static final Pattern TOO_SHORT = pattern("录音(?:时长|时间)?不足\\s*(\\d{1,3})\\s*(?:s|秒)(?:[，,\\s]*请重录)?");
    private static final Pattern FILL_AND_READ = pattern("^从下列表达中选择.+填空[，,\\s]+朗读完整句子并录音$");
    private static final Pattern PREPARING = pattern("^(?:准备录音|准备中)[…]*$");
    private static final Pattern RECORDING = pattern("^(?:录音中(?:[，,\\s]+点击提交)?[….]*|正在(?:录音|录制)[….]*|松开(?:即|可|即可|后)?(?:发送|结束|完成|提交)(?:[，,\\s]*(?:向上滑动|上滑)取消)?[….。]*)$");
    private static final Pattern PENDING = pattern("未开始|未完成|待完成|not started|incomplete");
    private static final Pattern NETWORK = pattern("网络(?:连接)?(?:异常|错误|不可用|断开)|无网络连接|连接失败|断网|network (?:error|unavailable)|no internet");
    private static final Pattern PERMISSION = pattern("(?:麦克风|录音).{0,10}(?:权限|未授权|被拒绝)|权限不足|permission denied|allow microphone");
    private static final Pattern UPLOAD_ERROR = pattern("(?:上传|保存|提交)(?:录音|音频)?失败|upload failed|submission failed");
    private static final Pattern RECORDING_TIMEOUT = pattern("录音(?:已)?(?:超时|超出时限)|(?:录音|作答)时间已到|已超出时限|time (?:is )?up");
    private static final Pattern TIMEOUT = pattern("已超时|操作超时|timed out");
    private static final Pattern GENERIC_ERROR = pattern("^(?:加载失败|操作失败|服务异常|出错了|发生错误|failed to load|something went wrong)$");
    private static final String[] ACTION_LABELS = {
            "下一步", "下一题", "继续", "返回目录", "选择课程", "重录", "重新录音", "回放", "重播",
            "提交录音", "提交作答", "确认提交", "提交", "播放", "暂停", "暂停播放", "暂停音频",
            "播放音频", "导入音频", "上传录音", "选择音频文件", "开始对话练习"
    };

    static final class Snapshot {
        final Screen screen;
        final Scope scope;
        final Media playback, recording;
        final Issue issue;
        final int step, elapsed, duration, limit, remaining, recordingTime, pending, question, questions,
                turns, turnLimit, minimumRecording;
        final String score, instruction, prompt, issueText;
        final boolean completed, protectedPage, loading;
        final Set<String> controls, disabledControls, evidence;

        Snapshot(Screen screen, Scope scope, Scan scan, CoursePage page,
                 Media playback, Media recording, String instruction, String prompt, boolean protectedPage, boolean loading) {
            this.screen = screen;
            this.scope = scope;
            this.playback = playback;
            this.recording = recording;
            this.issue = scan.issue;
            step = scan.step();
            elapsed = scan.elapsed >= 0 || page == null ? scan.elapsed : page.elapsedSeconds;
            duration = scan.duration >= 0 || page == null ? scan.duration : page.durationSeconds;
            limit = page == null ? -1 : page.timeLimitSeconds;
            remaining = scan.remaining;
            recordingTime = scan.recordingTime;
            pending = page == null ? 0 : Math.max(page.tasks.size(), scan.pendingCount);
            question = scan.question;
            questions = scan.questions;
            turns = scan.turns;
            turnLimit = scan.turnLimit;
            minimumRecording = scan.minimumRecording;
            score = scan.score;
            issueText = scan.issueText;
            completed = scan.issue == Issue.NONE && !scan.upload && !scan.scoring
                    && (scan.complete || screen == Screen.RESULT && (scan.submitted || page != null && page.page.result));
            controls = scan.controls;
            disabledControls = scan.disabled;
            evidence = scan.evidence;
            this.instruction = instruction;
            this.prompt = prompt;
            this.protectedPage = protectedPage;
            this.loading = loading;
        }

        String state() {
            if (issue != Issue.NONE) return issue.label;
            if (screen == Screen.BACKGROUND) return "已暂停监测";
            if (screen == Screen.PRIVATE) return "已暂停采样";
            if (recording == Media.SCORED) return "得分 " + score;
            if (recording == Media.RECORDING || recording == Media.SAVING || recording == Media.SAVED
                    || recording == Media.PREPARING || recording == Media.SCORING) return recording.label;
            if (playback == Media.PLAYING || playback == Media.ENDED || playback == Media.STALLED) return playback.label;
            if (loading) return "加载中";
            if (screen == Screen.RESULT) return score.isEmpty() ? "检测到结果" : "得分 " + score;
            if (screen == Screen.TASK_LIST) return "可见待完成 " + pending + " 项";
            if (recording == Media.READY) return "等待开始录音";
            if (playback == Media.READY) return "等待播放";
            return screen == Screen.UNKNOWN ? "等待页面证据" : "正在监测";
        }

        boolean recordingTimedOut() {
            return issue == Issue.TIMEOUT && (RECORDING_TIMEOUT.matcher(tidy(issueText)).find()
                    || tidy(issueText).matches("(?:已超时|timed out)[。.!！]?"));
        }

        String plan() {
            if (issue != Issue.NONE) return "等待当前异常处理";
            if (screen == Screen.BACKGROUND) return "等待 U校园回到前台";
            if (screen == Screen.PRIVATE) return "等待离开受保护页面";
            if (loading) return "等待页面加载";
            if (screen == Screen.HOME) return "等待进入 U听说";
            if (screen == Screen.MODULE_HOME || screen == Screen.COURSES || screen == Screen.TASK_LIST) return "等待打开教材或任务";
            if (recording == Media.RECORDING) return "观察录音结束";
            if (recording == Media.PREPARING) return "观察录音是否开始";
            if (recording == Media.SAVING) return "观察保存、上传或得分";
            if (recording == Media.SCORING) return "等待页面显示评分";
            if (recording == Media.SAVED || recording == Media.SCORED) return "观察下一题或重录";
            if (playback == Media.PLAYING) return "观察播放结束";
            if (recording == Media.READY) return "观察是否开始录音";
            if (playback == Media.READY) return "观察是否开始播放";
            if (screen == Screen.RESULT) return "观察返回列表或进入下一题";
            return "观察控件和页面变化";
        }

        String key() {
            return String.join("|", screen.name(), scope.name(), playback.name(), recording.name(),
                    issue.name(), String.valueOf(step), String.valueOf(completed), String.valueOf(pending),
                    String.valueOf(loading), score, instruction, prompt, issueText,
                    question + "/" + questions, turns + "/" + turnLimit, String.join(",", controls),
                    String.join(",", disabledControls), String.join(",", evidence));
        }
    }

    private static final class Scan {
        final Set<Integer> steps = new LinkedHashSet<>(), activeSteps = new LinkedHashSet<>();
        final Set<String> controls = new LinkedHashSet<>(), disabled = new LinkedHashSet<>(), evidence = new LinkedHashSet<>();
        boolean module, moduleTitle, moduleEntry, home, courses, example, follow, dialogue, freeSpeech, fillAndRead,
                role, listen, start, stop, preparing, activeRecording, upload, scoring, replay, redo, play, pause,
                complete, submitted, exampleListen, holdToTalk, retainedFollow, taskCard;
        int remaining = -1, recordingTime = -1, pendingCount;
        int elapsed = -1, duration = -1, plainSeconds = -1, question = -1, questions = -1,
                turns = -1, turnLimit = -1, minimumRecording = -1;
        String score = "", instruction = "", exercisePrompt = "", issueText = "";
        Issue issue = Issue.NONE;

        int step() {
            if (activeSteps.size() == 1) return activeSteps.iterator().next();
            if (!activeSteps.isEmpty()) return -1;
            if (steps.size() == 1) return steps.iterator().next();
            int matches = (exampleListen ? 1 : 0) + (follow ? 1 : 0) + (fillAndRead ? 1 : 0);
            if (matches == 0 && retainedFollow) return 2;
            if (!example || matches != 1) return -1;
            int inferred = exampleListen ? 1 : follow ? 2 : 3;
            return steps.contains(inferred) ? inferred : -1;
        }

        void read(PageModel.Line line) {
            if (line.edit) return;
            for (String part : line.text.split("\\n")) {
                String raw = part.trim();
                String label = tidy(raw);
                if (label.isEmpty()) continue;
                module |= MODULE.matcher(label).matches();
                moduleTitle |= !line.localControl && MODULE.matcher(label).matches();
                moduleEntry |= line.localControl && MODULE.matcher(label).matches();
                home |= HOME.matcher(label).matches();
                courses |= (!line.click || line.checked) && COURSES.matcher(label).matches();
                example |= EXAMPLE.matcher(label).matches();
                taskCard |= label.matches("阅读\\s*任务卡");
                follow |= ReadAloudPage.followDirection(raw);
                dialogue |= DIALOGUE.matcher(label).matches();
                freeSpeech |= label.equals("自由表达");
                boolean fill = FILL_AND_READ.matcher(label).matches();
                fillAndRead |= fill;
                exampleListen |= label.matches("^听音频[，, ]+学习.+$");
                role |= ROLE.matcher(label).matches();
                listen |= LISTEN.matcher(label).matches();
                boolean hold = label.matches("^(?:按住|长按)(?:说话|发言|回答|录音|开始说话)$");
                holdToTalk |= hold;
                boolean startLabel = ReadAloudPage.startControl(raw) || hold;
                boolean stopLabel = ReadAloudPage.stopControl(raw);
                boolean playLabel = PageModel.isPlayback(label)
                        || label.matches("^(?:开始播放|继续播放|播放原文|播放听力|播放材料|start playback)$");
                boolean pauseLabel = label.matches("^(?:暂停播放|暂停音频|暂停视频|正在播放|播放中|pause playback|playing)$");
                boolean replayLabel = ReadAloudPage.replayControl(raw);
                boolean selectedControl = line.click || line.bounds != null && line.enabled;
                if (selectedControl) {
                    start |= startLabel;
                    play |= playLabel;
                }
                stop |= stopLabel && line.enabled;
                activeRecording |= RECORDING.matcher(label).matches();
                preparing |= PREPARING.matcher(label).matches();
                pause |= pauseLabel && line.enabled;
                replay |= replayLabel && line.localControl && line.enabled;
                redo |= label.equals("重录") || label.equals("重新录音");
                upload |= UPLOADING.matcher(label).matches();
                scoring |= SCORING.matcher(label).matches();
                complete |= COMPLETE.matcher(label).matches();
                submitted |= SUBMITTED.matcher(label).matches();
                if (PENDING.matcher(label).find()) pendingCount++;
                if (startLabel || stopLabel || playLabel || pauseLabel || replayLabel || knownAction(label)) {
                    if (selectedControl || !line.enabled) {
                        Set<String> target = line.enabled && line.localControl ? controls : disabled;
                        String control = raw.replaceFirst("[，,：:\\s]+\\d{1,3}:\\d{2}$", "");
                        target.add(control + (line.enabled && !line.localControl ? "（仅可见）" : ""));
                    }
                }
                Matcher step = STEP.matcher(label);
                if (step.matches()) {
                    int value = number(step);
                    steps.add(value);
                    if (line.checked) activeSteps.add(value);
                }
                Matcher active = ACTIVE_STEP.matcher(label);
                if (active.matches()) activeSteps.add(number(active));
                Matcher grade = SCORE.matcher(label);
                if (grade.matches()) score = grade.group(1);
                Matcher clock = USpeakingReadout.CLOCK.matcher(label);
                if (clock.matches()) {
                    int position = parse(clock.group(1)) * 60 + parse(clock.group(2));
                    int length = parse(clock.group(3)) * 60 + parse(clock.group(4));
                    if (length > 0 && position <= length) {
                        this.elapsed = position;
                        duration = length;
                    }
                }
                Matcher seconds = USpeakingReadout.SECONDS.matcher(label);
                if (seconds.matches()) {
                    int value = parse(seconds.group(1));
                    if (plainSeconds == -1) plainSeconds = value;
                    else if (plainSeconds != value) plainSeconds = -2;
                }
                Matcher item = USpeakingReadout.QUESTION.matcher(label);
                if (item.matches() && parse(item.group(1)) <= parse(item.group(2))) {
                    question = parse(item.group(1));
                    questions = parse(item.group(2));
                }
                Matcher round = TURNS.matcher(label);
                if (round.find()) {
                    turns = parse(round.group(1));
                    turnLimit = parse(round.group(2));
                }
                Matcher left = REMAINING.matcher(label);
                if (left.find()) remaining = left.group(3) == null
                        ? parse(left.group(1)) * 60 + parse(left.group(2)) : parse(left.group(3));
                Matcher elapsed = RECORD_CLOCK.matcher(label);
                if (elapsed.find()) recordingTime = parse(elapsed.group(1)) * 60 + parse(elapsed.group(2));
                if (label.length() <= 120) readIssue(raw);
                if (instruction.isEmpty() && (ReadAloudPage.followDirection(raw) || label.startsWith("请")
                        || LISTEN.matcher(label).matches() || fill)) instruction = abbreviated(raw);
                if (label.contains("share your own experience") || label.startsWith("it's your show time")
                        || label.contains("___") && ReadAloudPage.sentence(raw)) exercisePrompt = abbreviated(raw);
            }
        }

        void readIssue(String text) {
            String label = tidy(text);
            Matcher minimum = TOO_SHORT.matcher(label);
            if (minimum.find()) minimumRecording = parse(minimum.group(1));
            Issue found = issue(label);
            if (found != Issue.NONE && (issue == Issue.NONE || issue == Issue.TOO_SHORT)) {
                issue = found;
                issueText = abbreviated(text);
            }
        }
    }

    Snapshot read(CoursePage page, List<PageModel.Line> lines, boolean withinModule, String toast) {
        return read(page, lines, withinModule, toast, null, "", 0, false);
    }

    Snapshot read(CoursePage page, List<PageModel.Line> lines, boolean withinModule, String toast,
                  USpeakingReadout.Progress progress, String window, long now, boolean commit) {
        return read(page, lines, withinModule, toast, progress, window, now, commit, false);
    }

    Snapshot read(CoursePage page, List<PageModel.Line> lines, boolean withinModule, String toast,
                  USpeakingReadout.Progress progress, String window, long now, boolean commit, boolean retainFollow) {
        Scan scan = new Scan();
        if (page.kind == CoursePage.Kind.BLOCKED) {
            if (commit && progress != null) progress.reset();
            return new Snapshot(Screen.PRIVATE, Scope.UNKNOWN, scan, null, Media.UNKNOWN, Media.UNKNOWN, "", "", true, false);
        }
        lines = USpeakingReadout.join(lines);
        for (PageModel.Line line : lines) scan.read(line);
        scan.retainedFollow = scan.example && scan.steps.contains(2) && scan.activeSteps.isEmpty()
                && (retainFollow || scan.play && scan.replay && (scan.start || scan.stop || scan.redo || scan.preparing));
        if (scan.issue == Issue.NONE) scan.readIssue(toast);
        if (scan.issue == Issue.TOO_SHORT && (scan.stop || scan.activeRecording || scan.preparing)) {
            scan.issue = Issue.NONE;
            scan.issueText = "";
        }
        ReadAloudPage oral = page.page.reading;
        boolean context = scan.moduleTitle || scan.module && !scan.home || scan.example || scan.freeSpeech || scan.taskCard
                || scan.dialogue && (scan.turns >= 0 || scan.holdToTalk) || withinModule && !scan.home;
        Scope scope = context ? Scope.CONFIRMED : scan.moduleEntry ? Scope.ENTRY : Scope.UNKNOWN;
        int step = scan.step();
        SpeakingCards cards = oral == null ? null : oral.cards;
        if (step == 2 && cards == null && scan.retainedFollow) cards = SpeakingCards.read(lines, true);
        boolean recordingNow = scan.stop || scan.activeRecording || oral != null && !oral.stop.isEmpty();
        boolean preparing = scan.preparing && (scan.example || scan.freeSpeech || scan.start);
        if (recordingNow && scan.remaining < 0 && (step == 3 || scan.freeSpeech)) scan.remaining = scan.plainSeconds;
        if ((step == 2 || step == 3 || scan.freeSpeech) && scan.score.isEmpty()
                && !recordingNow && !preparing && !scan.upload && !scan.scoring
                && (scan.redo || step == 3 && scan.controls.contains("提交"))) {
            scan.score = exerciseScore(lines, step, cards);
        }
        Screen screen = classify(page, scan, context);
        boolean saved = oral != null && oral.saved || scan.replay;
        Media recording;
        if (scan.upload) recording = Media.SAVING;
        else if (scan.scoring) recording = Media.SCORING;
        else if (recordingNow) recording = Media.RECORDING;
        else if (preparing) recording = Media.PREPARING;
        else if (!scan.score.isEmpty() && (scan.example || scan.freeSpeech)) recording = Media.SCORED;
        else if (saved) recording = Media.SAVED;
        else recording = scan.start ? Media.READY : Media.UNKNOWN;

        Media playback = page.mediaEnded ? Media.ENDED : page.playing || scan.pause ? Media.PLAYING
                : scan.play || !page.play.isEmpty() ? Media.READY : Media.UNKNOWN;
        if (scan.elapsed >= 0 && scope == Scope.CONFIRMED && (step == 1 || screen == Screen.LISTENING)) {
            if (playback == Media.UNKNOWN || playback == Media.READY) {
                playback = progress == null
                        ? scan.elapsed == scan.duration ? Media.ENDED : scan.elapsed == 0 ? Media.READY : playback
                        : progress.observe(window + "|" + scan.instruction + "|" + scan.duration, now,
                                scan.elapsed, scan.duration, commit);
                if (playback == Media.PLAYING || playback == Media.STALLED) scan.evidence.add("player_clock_progress");
            }
            scan.evidence.add("player_clock");
        } else if (commit && progress != null) progress.reset();
        if (scan.module) scan.evidence.add("u_speaking_label");
        if (scan.moduleEntry) scan.evidence.add("u_speaking_entry");
        if (withinModule && !scan.home) scan.evidence.add("module_navigation");
        if (scan.example) scan.evidence.add("example_heading");
        if (scan.follow) scan.evidence.add("follow_instruction");
        if (scan.start || scan.stop) scan.evidence.add("record_control");
        if (!scan.activeSteps.isEmpty()) scan.evidence.add("active_step");
        else if (scan.steps.size() > 1 && step >= 0) {
            scan.evidence.add(scan.retainedFollow && !scan.follow && step == 2 ? "follow_card_context" : "step_instruction");
        }
        if (scan.upload) scan.evidence.add("upload_status");
        if (scan.scoring) scan.evidence.add("scoring_status");
        if (scan.turns >= 0) scan.evidence.add("dialogue_turn_count");
        if (scan.remaining >= 0) scan.evidence.add("remaining_time");
        if (scan.issue != Issue.NONE) scan.evidence.add("visible_error");
        if (!scan.score.isEmpty()) scan.evidence.add("visible_score");
        String instruction = scan.instruction.isEmpty() ? page.instruction : scan.instruction;
        String prompt = scan.exercisePrompt.isEmpty() || !(step == 3 || scan.freeSpeech)
                ? oral == null ? "" : abbreviated(oral.target()) : scan.exercisePrompt;
        if (step == 2 && cards != null && cards.current != null) prompt = abbreviated(cards.current.text);
        if (step == 1 || scan.dialogue) prompt = "";
        return new Snapshot(screen, scope, scan, page, playback, recording, instruction, prompt, false, page.loading);
    }

    static Snapshot background() {
        return new Snapshot(Screen.BACKGROUND, Scope.OUTSIDE, new Scan(), null, Media.UNKNOWN, Media.UNKNOWN, "", "", false, false);
    }

    private Screen classify(CoursePage page, Scan scan, boolean context) {
        if (scan.home && scan.moduleEntry) return Screen.HOME;
        if (!context) return scan.moduleEntry ? Screen.HOME : Screen.UNKNOWN;
        if (scan.taskCard) return Screen.TASK_CARD;
        if (scan.example) return Screen.EXAMPLE;
        if (scan.freeSpeech) return Screen.FREE_SPEECH;
        if (scan.role) return Screen.ROLE_PLAY;
        if (scan.dialogue) return Screen.DIALOGUE;
        if (scan.issue != Issue.NONE) return Screen.ERROR;
        if (scan.upload || scan.scoring) return Screen.UPLOADING;
        if (page.kind == CoursePage.Kind.RESULT || !scan.score.isEmpty()
                || (scan.complete || scan.submitted) && page.tasks.isEmpty()) return Screen.RESULT;
        if (page.catalogHeading || page.kind == CoursePage.Kind.CATALOG || !page.tasks.isEmpty()) return Screen.TASK_LIST;
        if (scan.courses) return Screen.COURSES;
        if (scan.follow && (scan.start || scan.stop || scan.play || scan.replay)) return Screen.SHADOWING;
        if (page.kind == CoursePage.Kind.READ_ALOUD) return Screen.READ_ALOUD;
        if (page.kind == CoursePage.Kind.SPEAKING) return Screen.SPEAKING;
        if (scan.listen || page.kind.mediaQuestions()) return Screen.LISTENING;
        return scan.module ? Screen.MODULE_HOME : Screen.UNKNOWN;
    }

    private String exerciseScore(List<PageModel.Line> lines, int step, SpeakingCards cards) {
        if (step != 2) {
            for (PageModel.Line caption : lines) {
                if (!caption.text.equalsIgnoreCase("AI评分")) continue;
                for (PageModel.Line value : lines) {
                    boolean sameParent = caption.parent >= 0 && value.parent == caption.parent;
                    boolean adjacent = caption.bounds != null && value.bounds != null
                            && Math.abs(caption.bounds.x() - value.bounds.x()) <= 240
                            && Math.abs(caption.bounds.y() - value.bounds.y()) <= 200;
                    if (!sameParent && !adjacent) continue;
                    Matcher points = POINTS.matcher(value.text);
                    if (points.matches()) return points.group(1);
                }
            }
            return "";
        }
        if (cards == null || cards.current == null) return "";
        PageModel.Bounds sentence = cards.current.bounds;
        for (PageModel.Line line : lines) {
            PageModel.Bounds box = line.bounds;
            if (box == null || box.left < sentence.right || box.y() < sentence.top || box.y() > sentence.bottom) continue;
            Matcher points = POINTS.matcher(line.text);
            if (points.matches()) return points.group(1);
        }
        return "";
    }

    private static String abbreviated(String value) { return value.length() <= 240 ? value : value.substring(0, 240); }

    private static Issue issue(String value) {
        if (UPLOAD_ERROR.matcher(value).find()) return Issue.UPLOAD;
        if (TOO_SHORT.matcher(value).find()) return Issue.TOO_SHORT;
        if (PERMISSION.matcher(value).find()) return Issue.PERMISSION;
        if (NETWORK.matcher(value).find()) return Issue.NETWORK;
        if (RECORDING_TIMEOUT.matcher(value).find() || TIMEOUT.matcher(value).find()) return Issue.TIMEOUT;
        return GENERIC_ERROR.matcher(value).matches() ? Issue.OTHER : Issue.NONE;
    }

    private static boolean knownAction(String label) {
        for (String action : ACTION_LABELS) if (action.equals(label)) return true;
        return label.matches("^(?:next(?: step| question)?|submit(?: response| recording)?|confirm submission|replay|record again)$");
    }

    private static Pattern pattern(String pattern) { return Pattern.compile(pattern, Pattern.CASE_INSENSITIVE); }
    private static String tidy(String text) { return text.trim().toLowerCase(Locale.ROOT).replaceFirst("[.!。！]+$", ""); }
    private static int parse(String digits) { return Integer.parseInt(digits); }
    private static int number(Matcher match) {
        for (int i = 1; i <= match.groupCount(); i++) if (match.group(i) != null) return parse(match.group(i));
        throw new IllegalStateException("Missing step number");
    }
}
