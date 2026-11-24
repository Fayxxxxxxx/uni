package dev.campuspocket.app;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class CoursePage {
    enum Kind {
        BLOCKED("登录、考试或受限页面"), CATALOG("课程目录"), RESULT("结果页"), ANSWER_CARD("答题卡"),
        CHOICE("选择判断"), FILL("填空"), READING("阅读"), WRITING("写作"), TRANSLATION("翻译"),
        LISTENING("听力答题"), VIDEO_QUESTION("视频答题"), MEDIA("音视频学习"),
        READ_ALOUD("朗读跟读"), SPEAKING("开放式口语"), DUBBING("配音"),
        ORDERING("排序"), MATCHING("匹配"), CLOZE("选词填空"), DROPDOWN("下拉选择"),
        VOCABULARY("词汇卡"), SELF_CHECK("自评"), DISCUSSION("讨论"), ATTACHMENT("作品上传"), UNKNOWN("未识别");

        final String label;
        Kind(String label) { this.label = label; }

        boolean textAnswer() {
            return this == CHOICE || this == FILL || this == READING || this == WRITING || this == TRANSLATION;
        }

        boolean mediaQuestions() { return this == LISTENING || this == VIDEO_QUESTION; }
        boolean oral() { return this == READ_ALOUD || this == SPEAKING || this == DUBBING; }
    }

    private static final Pattern CATALOG = pattern("^(?:课程目录|任务目录|课程任务|任务列表|学习任务|单元目录|课文目录|活动列表"
            + "|course (?:content|catalog|tasks)|unit (?:tasks|contents?)|task list|activities)$");
    private static final Pattern PENDING = pattern("未完成|待完成|未开始|未提交|\\b(?:not started|incomplete|to do|pending)\\b");
    private static final Pattern FINISHED = pattern("已完成|已提交|待批阅|已批阅|已交卷|播放完毕|播放结束|观看完成|学习完成|已播完"
            + "|(?:^|\\b)(?:completed|submitted|playback ended|video ended|audio ended)(?:$|[.!。！])");
    private static final Pattern EXAM = pattern("^(?:考试须知|考试说明|考试进行中|在线考试|开始考试|期末考试|期中考试|测验须知"
            + "|exam instructions|start (?:the )?exam|exam in progress)$");
    private static final Pattern NEXT = pattern("^(?:下一题|下一练习|下一项|下一任务|下一个任务|下一活动"
            + "|next question|next exercise|next activity|next task)$");
    private static final Pattern BACK = pattern("^(?:返回目录|返回课程目录|返回任务列表|返回单元|回到目录"
            + "|back to (?:the )?(?:catalog|tasks|task list|unit)|return to (?:the )?(?:catalog|task list))$");
    private static final Pattern PLAY = pattern("^(?:播放|播放音频|播放视频|开始播放|播放听力|播放材料|播放原文"
            + "|play|play audio|play video|start playback|play the recording)$");
    private static final Pattern PLAYING = pattern("^(?:暂停|暂停播放|暂停音频|暂停视频|播放中|正在播放|pause|pause playback|pause audio|pause video|playing)$");
    private static final Pattern IMPORT = pattern("^(?:导入音频|选择音频文件|上传录音|上传音频|选择录音"
            + "|import audio|upload audio|upload (?:my )?recording|choose audio file|select recording)$");
    private static final Pattern CLOCK = Pattern.compile("(?<!\\d)(\\d{1,3}):([0-5]\\d)(?!\\d)");
    private static final Pattern TIMELIMIT = pattern("(?:限时|时限|时长|最多|倒计时|time limit|recording limit)\\s*[:：]?\\s*(\\d{1,3})"
            + "\\s*(分钟|秒|minutes?|secs?|seconds?)");
    private static final Pattern DURATION = pattern("(?:总时长|音频时长|视频时长|总长度|duration)\\s*[:：]?\\s*(\\d{1,3}):([0-5]\\d)");
    private static final Pattern INSTRUCTION = pattern("^(?:请|选择|听|读|根据|完成|将|匹配|排序|写|翻译|"
            + "choose\\b|select\\b|fill\\b|listen\\b|read\\b|write\\b|translate\\b|match\\b|order\\b|watch\\b|record\\b)");
    private static final Pattern AUDIO = pattern("听力|听录音|听对话|听音频|听写|\\blistening\\b|\\blisten\\b|\\baudio\\b");
    private static final Pattern VIDEO = pattern("观看视频|视听|视频|\\bwatch\\b|\\bvideo\\b");
    private static final Pattern SPEECH = pattern("口语|录音作答|自由发言|看图说话|\\bspeaking\\b|\\bmy voice\\b|\\btalk about\\b|\\brecord your answer\\b");
    private static final Pattern SPECIAL = pattern("考试|测验|签到|投票|班级讨论|\\bexam\\b|\\bproctored\\b|attendance");

    static final class Task {
        final String name, key;
        final PageModel.Line action;
        Task(String name, PageModel.Line action) {
            this.name = name;
            this.key = PENDING.matcher(name).replaceAll("").replaceAll("^[\\s|:：-]+|[\\s|:：-]+$", "")
                    .replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
            this.action = action;
        }
    }

    final PageModel.Page page;
    final List<Task> tasks = new ArrayList<>();
    final List<PageModel.Line> next = new ArrayList<>();
    final List<PageModel.Line> back = new ArrayList<>();
    final List<PageModel.Line> play = new ArrayList<>();
    final List<PageModel.Line> uploadAudio = new ArrayList<>();
    final Set<String> catalogMarks = new LinkedHashSet<>();
    final Set<String> completedTaskKeys = new LinkedHashSet<>();
    Kind kind;
    boolean playing, finished, catalogHeading, mediaEnded, answerCard, report, loading;
    int elapsedSeconds = -1, durationSeconds = -1, timeLimitSeconds = -1;
    String instruction = "";
    String identity = "";

    private CoursePage(PageModel.Page page) { this.page = page; }

    static CoursePage read(PageModel.Page parsed, List<PageModel.Line> lines) {
        CoursePage view = new CoursePage(parsed);
        Map<Integer, List<String>> rows = new LinkedHashMap<>();
        Map<Integer, PageModel.Line> actions = new LinkedHashMap<>();
        boolean exam = parsed.blocked;
        StringBuilder staticText = new StringBuilder();
        for (PageModel.Line line : lines) {
            if (line.edit) continue;
            for (String part : line.text.split("\\n")) {
                String clean = part.trim();
                if (clean.isEmpty()) continue;
                String name = clean.replaceFirst("[.!。！:：\\s]+$", "");
                exam |= EXAM.matcher(name).matches();
                view.finished |= FINISHED.matcher(clean).find() && !PENDING.matcher(clean).find();
                view.mediaEnded |= name.matches("(?i)^(?:已播放|已播完|播放完毕|播放结束|观看完成|playback ended|video ended|audio ended)$");
                view.playing |= PLAYING.matcher(name).matches();
                view.loading |= name.matches("(?i)^(?:加载中|正在加载|正在初始化|正在提交|请稍候|loading|please wait|submitting)$");
                view.answerCard |= !line.click && name.equals("答题卡");
                view.report |= !line.click && name.equals("答题报告");
                if (CATALOG.matcher(name).matches()) {
                    view.catalogHeading = true;
                    view.catalogMarks.add(name.toLowerCase(Locale.ROOT));
                }
                if (!line.click && name.matches("(?i)^(?:unit|lesson|chapter)\\s+\\d.*|^第.{1,7}[单课章]元?.*$")) {
                    view.catalogMarks.add(name.toLowerCase(Locale.ROOT));
                }
                if (view.instruction.isEmpty() && INSTRUCTION.matcher(clean).find() && clean.length() > 8) {
                    view.instruction = clean.substring(0, Math.min(clean.length(), 80));
                }
                view.timestamps(clean);
                if (!line.click && !CLOCK.matcher(clean).find() && !FINISHED.matcher(clean).find()
                        && !PLAYING.matcher(name).matches() && !CATALOG.matcher(name).matches()) {
                    staticText.append(clean).append('\n');
                }
                if (line.click) {
                    rows.computeIfAbsent(line.id, key -> new ArrayList<>()).add(clean);
                    actions.putIfAbsent(line.id, line);
                    if (NEXT.matcher(name).matches()) add(view.next, line);
                    if (BACK.matcher(name).matches()) add(view.back, line);
                    if (PLAY.matcher(name).matches()) add(view.play, line);
                    if (IMPORT.matcher(name).matches()) add(view.uploadAudio, line);
                }
            }
        }
        if (!exam) {
            for (Map.Entry<Integer, List<String>> row : rows.entrySet()) {
                Set<String> parts = new LinkedHashSet<>(row.getValue());
                String text = String.join(" · ", parts);
                boolean done = parts.stream().anyMatch(part -> !PENDING.matcher(part).find() && FINISHED.matcher(part).find());
                if (text.length() <= 155 && PENDING.matcher(text).find() && !done && !SPECIAL.matcher(text).find()) {
                    Task task = new Task(text, actions.get(row.getKey()));
                    if (task.key.length() > 1) view.tasks.add(task);
                }
                if (done && !PENDING.matcher(text).find()) {
                    view.completedTaskKeys.add(new Task(FINISHED.matcher(text).replaceAll(""), actions.get(row.getKey())).key);
                }
            }
        }
        view.identity = staticText.length() == 0 ? parsed.automationKey() : staticText.toString();
        if (!parsed.groups.isEmpty()) {
            for (PageModel.Group g : parsed.groups) {
                for (PageModel.Line option : g.choices.values()) view.identity += "\n" + option.text;
            }
        }
        view.kind = view.classify(exam);
        if (view.kind == Kind.UNKNOWN && parsed.text.trim().isEmpty()) view.loading = true;
        return view;
    }

    private Kind classify(boolean exam) {
        if (exam) return Kind.BLOCKED;
        if (page.result || (report && page.groups.isEmpty() && page.submit.isEmpty())) return Kind.RESULT;
        if (answerCard && page.groups.isEmpty()) return Kind.ANSWER_CARD;
        if (((catalogHeading || (!catalogMarks.isEmpty() && !tasks.isEmpty())) && page.groups.isEmpty() && play.isEmpty()
                && page.submit.isEmpty()) || (!page.exercise && tasks.size() >= 2 && play.isEmpty())) return Kind.CATALOG;
        String text = page.text;
        if (page.groups.isEmpty() && has(text, "配音|\\bdubbing\\b|dub the")) return Kind.DUBBING;
        if (page.reading != null && page.reading.direction && (page.reading.cards != null || !page.reading.passage.isEmpty()
                || !page.reading.start.isEmpty() || !uploadAudio.isEmpty())) return Kind.READ_ALOUD;
        if (!uploadAudio.isEmpty() || (page.groups.isEmpty() && (SPEECH.matcher(text).find()
                || (page.reading != null && !page.reading.question.isEmpty() && !page.reading.start.isEmpty())))) {
            return Kind.SPEAKING;
        }
        boolean presentedMedia = !play.isEmpty() || playing;
        boolean videoPrompt = VIDEO.matcher(instruction).find();
        if (!page.groups.isEmpty() && (videoPrompt || AUDIO.matcher(instruction).find()
                || (presentedMedia && (AUDIO.matcher(text).find() || VIDEO.matcher(text).find())))) {
            return videoPrompt || VIDEO.matcher(text).find() ? Kind.VIDEO_QUESTION : Kind.LISTENING;
        }
        if (has(text, "排序|重新排列|arrange .* order|put .* in order|reorder")) return Kind.ORDERING;
        if (has(text, "匹配|连线|段落配对|match (?:the|each|these)\\b")) return Kind.MATCHING;
        if (has(text, "选词填空|词库|word bank|banked cloze")) return Kind.CLOZE;
        if (has(text, "下拉选择|下拉菜单|drop[- ]down")) return Kind.DROPDOWN;
        if (has(text, "词汇卡|单词卡|flashcards?")) return Kind.VOCABULARY;
        if (has(text, "自评|self[- ](?:check|assessment)")) return Kind.SELF_CHECK;
        if (has(text, "讨论区|discussion board|forum")) return Kind.DISCUSSION;
        if (has(text, "上传作品|上传附件|upload (?:a file|attachment|assignment)")) return Kind.ATTACHMENT;
        if (!page.groups.isEmpty()) {
            if (has(text, "翻译|\\btranslate\\b|translation")) return Kind.TRANSLATION;
            if (has(text, "写作|作文|\\bwrite\\b|\\bessay\\b")) return Kind.WRITING;
            if (has(text, "阅读理解|read the (?:text|passage|article)|reading comprehension")) return Kind.READING;
            return page.groups.stream().anyMatch(group -> !group.choices.isEmpty()) ? Kind.CHOICE : Kind.FILL;
        }
        if (!play.isEmpty() || playing || (finished && (durationSeconds >= 0
                || AUDIO.matcher(text).find() || VIDEO.matcher(text).find()))) return Kind.MEDIA;
        return Kind.UNKNOWN;
    }

    boolean compatibleAnswers() {
        return page.exercise && !page.groups.isEmpty() && page.submit.size() == 1
                && page.groups.stream().noneMatch(group -> group.ambiguous);
    }

    static boolean mediaControl(String label) {
        return PLAY.matcher(label).matches() || PLAYING.matcher(label).matches() || IMPORT.matcher(label).matches()
                || NEXT.matcher(label).matches() || BACK.matcher(label).matches();
    }

    String description() {
        String state = kind == Kind.RESULT ? "已确认结果" : loading ? "正在加载或提交" : playing ? "正在播放"
                : mediaEnded && kind == Kind.MEDIA ? "已播放完毕"
                : kind.oral() && page.reading != null && !page.reading.stop.isEmpty() ? "正在录音"
                : kind.oral() && page.reading != null && page.reading.saved ? "录音已保存"
                : kind == Kind.CATALOG ? tasks.size() + " 项可见待完成任务" : "等待处理";
        String duration = durationSeconds < 0 ? "" : " · 素材 "
                + (elapsedSeconds < 0 ? "" : formatDuration(elapsedSeconds) + "/") + formatDuration(durationSeconds);
        String limit = timeLimitSeconds < 0 ? "" : " · 限时 " + formatDuration(timeLimitSeconds);
        String input = kind.oral() && uploadAudio.size() == 1 ? " · 有音频导入" : "";
        return kind.label + " · " + state + duration + limit + input;
    }

    private void timestamps(String text) {
        Matcher duration = DURATION.matcher(text);
        if (duration.find()) durationSeconds = seconds(duration);
        Matcher clock = CLOCK.matcher(text);
        if (text.contains("/") && clock.find()) {
            int first = seconds(clock);
            if (clock.find()) {
                int total = seconds(clock);
                if (total > 0 && first <= total) {
                    durationSeconds = total;
                    elapsedSeconds = first;
                }
            }
        }
        Matcher limit = TIMELIMIT.matcher(text);
        if (limit.find()) {
            String units = limit.group(2).toLowerCase(Locale.ROOT);
            timeLimitSeconds = Integer.parseInt(limit.group(1)) * (units.startsWith("min") || units.equals("分钟") ? 60 : 1);
        }
    }

    private static Pattern pattern(String expression) { return Pattern.compile(expression, Pattern.CASE_INSENSITIVE); }
    private static boolean has(String text, String expression) { return pattern(expression).matcher(text).find(); }
    private static int seconds(Matcher matcher) { return Integer.parseInt(matcher.group(1)) * 60 + Integer.parseInt(matcher.group(2)); }
    private static String formatDuration(int seconds) { return String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60); }

    private static void add(List<PageModel.Line> list, PageModel.Line candidate) {
        if (list.stream().noneMatch(existing -> existing.id == candidate.id)) list.add(candidate);
    }
}
