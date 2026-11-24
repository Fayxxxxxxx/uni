package dev.campuspocket.app;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class ReadAloudPage {
    private static final Pattern DIRECTION = Pattern.compile(
            "^(?:请\\s*)?(?:(?:单词|词汇|句子|段落|课文|课文段落|文本|英语|英文)?(?:朗读|跟读|读出)"
                    + "(?:$|[：:\\s。]|练习|题|以下|下列|下面|并|原文|内容|录音|课文|句子|单词)"
                    + "|(?:listen\\s+and\\s+)?repeat\\s+after\\b|listen\\s+and\\s+repeat\\b"
                    + "|(?:please\\s+)?read(?:\\s+[^:\n]{1,100}?)?\\s+(?:aloud|out\\s+loud)\\b"
                    + "|(?:please\\s+)?read\\s+and\\s+record\\b)", Pattern.CASE_INSENSITIVE);
    private static final Pattern SOURCE = Pattern.compile(
            "^(?:朗读原文|朗读内容|跟读内容|原文|朗读材料|passage|text to read|original text|read this)"
                    + "(?:\\s*[:：]\\s*(.*)|\\s*)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern OPEN_QUESTION = Pattern.compile(
            "^(?:请\\s*)?(?:回答(?:下列|以下|下面|这个|问题)|看图说话|自由发言|复述|口语问答|自由回答"
                    + "|answer\\s+(?:the|these|in)\\b|record\\s+your\\s+(?:answer|response)\\b|talk\\s+about\\b"
                    + "|describe\\s+(?:the|this)\\b|retell\\b)", Pattern.CASE_INSENSITIVE);
    private static final Pattern START = Pattern.compile(
            "^(?:录音|录制|开始录音|开始录制|点击录音|点击按钮录音|点击开始录音|点击按钮开始录音|点此录音|立即录音"
                    + "|record|start recording|start record|tap to record|click to record|record now|start)$");
    private static final Pattern STOP = Pattern.compile(
            "^(?:停止录音|结束录音|停止录制|结束录制|点击结束录音|点击停止录音|完成录音|录音中(?:[，,：:\\s]+(?:点按|点击)?(?:停止|结束))?"
                    + "|stop recording|finish recording|stop)$");
    private static final Pattern SAVED = Pattern.compile(
            "^(?:录音(?:完成|成功|已保存|保存成功|已上传|上传成功|已完成)|已录音|录音文件已上传"
                    + "|recording (?:saved|complete|completed|uploaded)|recorded successfully)"
                    + "(?:[.!。！]|$)", Pattern.CASE_INSENSITIVE);
    private static final Pattern REPLAY = Pattern.compile(
            "^(?:播放录音|回放录音|回放我的录音|重听录音|播放我的录音|play recording|play my recording|replay recording)$");
    private static final Pattern SUBMIT = Pattern.compile(
            "^(?:提交录音|提交作答|submit recording|submit my recording|submit response)$");
    private static final Pattern CONFIRM = Pattern.compile(
            "^(?:确认提交|确定提交|确认提交录音|confirm submission|confirm submit)$");
    private static final Pattern PENDING = Pattern.compile(
            "未完成|未开始|待完成|not started|incomplete|to do", Pattern.CASE_INSENSITIVE);
    private static final Pattern GRADE = Pattern.compile(
            "^(?:朗读得分|录音得分|口语得分|朗读评分|录音评分|口语评分|reading score|pronunciation score)"
                    + "\\s*[:：]?\\s*\\d{1,3}(?:\\.\\d+)?(?:\\s*/\\s*\\d+)?(?:\\s*分)?\\s*$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern STEP = Pattern.compile(
            "^(?:instructions?|directions?|submission|recording|task details|my recording|attempts?"
                    + "|show (?:answer|transcript|translation)|next(?: question)?|previous(?: question)?|"
                    + "(?:(?:unit|lesson|task|activity|section|part|exercise|step)\\s+(?:\\d+|[ivx]+)(?:\\b.*)?))$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern ALPHABET = Pattern.compile("[A-Za-z]+(?:['’\\-][A-Za-z]+)*");
    private static final Pattern ENGLISH = Pattern.compile("[A-Za-z0-9\\p{Punct}‘’“”–—…\\s]+");

    private static final int MAX_SOURCE = 1800;
    final List<PageModel.Line> start = new ArrayList<>();
    final List<PageModel.Line> stop = new ArrayList<>();
    final List<PageModel.Line> submit = new ArrayList<>();
    final List<PageModel.Line> confirm = new ArrayList<>();
    final List<PageModel.Line> pending = new ArrayList<>();
    String passage = "", question = "";
    SpeakingCards cards;
    boolean direction, saved, graded;

    static ReadAloudPage read(List<PageModel.Line> lines) {
        ReadAloudPage state = new ReadAloudPage();
        Set<Integer> starts = new HashSet<>();
        Set<Integer> stops = new HashSet<>();
        Set<Integer> submits = new HashSet<>();
        Set<Integer> confirms = new HashSet<>();
        Set<Integer> pendingIds = new HashSet<>();
        Map<Integer, PageModel.Line> taskLabels = new LinkedHashMap<>();
        List<String> paragraphs = new ArrayList<>();
        boolean collecting = false;
        String questionDirection = "";
        List<String> questionParts = new ArrayList<>();
        boolean vocabulary = false;
        for (PageModel.Line node : lines) {
            if (node.edit || node.text.isEmpty()) continue;
            for (String part : node.text.split("\n")) {
                String text = part.trim();
                String label = label(text);
                boolean start = START.matcher(label).matches();
                boolean stop = STOP.matcher(label).matches();
                boolean replay = REPLAY.matcher(label).matches();
                boolean submit = SUBMIT.matcher(label).matches() || PageModel.isSubmit(label);
                if (SAVED.matcher(text).find() || (node.click && replay)) state.saved = true;
                if (GRADE.matcher(text).matches()) state.graded = true;
                if (node.click && start && starts.add(node.id)) state.start.add(node);
                if (node.click && stop && stops.add(node.id)) state.stop.add(node);
                if (node.click && submit && submits.add(node.id)) state.submit.add(node);
                if (node.click && CONFIRM.matcher(label).matches() && confirms.add(node.id)) state.confirm.add(node);
                if (node.click && PENDING.matcher(text).find()) pendingIds.add(node.id);
                String instruction = text.replaceFirst("(?i)^(?:(?:Task|Exercise|Question)\\s+)?\\d{1,3}[.)、:：]\\s*", "");
                if (questionDirection.isEmpty() && OPEN_QUESTION.matcher(instruction).find()) {
                    questionDirection = instruction;
                    collecting = false;
                    int colon = firstColon(instruction);
                    if (colon >= 0) add(questionParts, instruction.substring(colon + 1), true);
                    continue;
                }
                if (DIRECTION.matcher(instruction).find()) {
                    if (node.click) taskLabels.putIfAbsent(node.id, node);
                    state.direction = true;
                    collecting = true;
                    vocabulary |= instruction.matches("^(?:请\\s*)?(?:单词|词汇)(?:朗读|跟读).*");
                    int colon = firstColon(instruction);
                    if (colon >= 0) add(paragraphs, instruction.substring(colon + 1), true);
                    continue;
                }
                if (state.direction) {
                    Matcher source = SOURCE.matcher(text);
                    if (source.matches()) {
                        collecting = true;
                        if (source.group(1) != null) add(paragraphs, source.group(1), true);
                        continue;
                    }
                }
                if (start || stop || replay || submit || SAVED.matcher(text).find()
                        || GRADE.matcher(text).matches()) {
                    if (!paragraphs.isEmpty()) collecting = false;
                    continue;
                }
                if (!questionDirection.isEmpty() && !STEP.matcher(label).matches() && questionParts.size() < 8) {
                    add(questionParts, text, false);
                }
                if (collecting && !STEP.matcher(label).matches() && !isSpeechInstruction(label)) {
                    add(paragraphs, text, vocabulary && paragraphs.isEmpty());
                }
            }
        }
        String passage = String.join("\n", paragraphs);
        if (state.direction && questionDirection.isEmpty() && passage.length() <= MAX_SOURCE) state.passage = passage;
        String prompts = String.join("\n", questionParts);
        if (prompts.contains("?") || (questionDirection.endsWith("?") || questionDirection.endsWith("？")
                || questionDirection.matches("(?i)^(?:talk about|describe)\\s+.{8,}"))) {
            if (prompts.length() <= MAX_SOURCE) {
                state.question = questionDirection + (prompts.isEmpty() ? "" : "\n" + prompts);
                state.direction = false;
            }
        }
        state.cards = SpeakingCards.read(lines);
        if (state.cards != null) {
            state.direction = true;
            state.passage = state.cards.current == null ? "" : state.cards.current.text;
            state.question = "";
            state.graded = false;
            state.start.clear();
            state.start.addAll(state.cards.start);
            state.stop.clear();
            state.stop.addAll(state.cards.stop);
            state.saved = state.cards.saved;
        }
        if (state.passage.isEmpty() && state.start.isEmpty() && state.stop.isEmpty()) {
            for (Map.Entry<Integer, PageModel.Line> task : taskLabels.entrySet()) {
                if (pendingIds.contains(task.getKey())) state.pending.add(task.getValue());
            }
        }
        return state;
    }

    String target() { return question.isEmpty() ? passage : question; }

    boolean answerRecording() {
        return !question.isEmpty() && (!start.isEmpty() || !stop.isEmpty() || saved);
    }

    static boolean recordingControl(String source) {
        String label = label(source);
        return START.matcher(label).matches() || STOP.matcher(label).matches() || REPLAY.matcher(label).matches()
                || SUBMIT.matcher(label).matches() || CONFIRM.matcher(label).matches();
    }

    static boolean startControl(String source) { return START.matcher(label(source)).matches(); }

    static boolean stopControl(String source) { return STOP.matcher(label(source)).matches(); }

    static boolean savedLabel(String source) { return SAVED.matcher(source).find() || GRADE.matcher(source).matches(); }

    static boolean replayControl(String source) {
        return REPLAY.matcher(label(source)).matches() || label(source).equals("回放");
    }

    static boolean followDirection(String source) {
        String text = label(source);
        return (text.startsWith("跟读") && DIRECTION.matcher(text).find())
                || text.startsWith("请跟读") || text.startsWith("listen and repeat") || text.startsWith("repeat after");
    }

    static boolean sentence(String source) {
        List<String> one = new ArrayList<>();
        if (STEP.matcher(label(source)).matches() || source.matches("(?i)^step\\s*\\d.*")
                || OPEN_QUESTION.matcher(source).find()) return false;
        add(one, source, false);
        return !one.isEmpty();
    }

    private static boolean isSpeechInstruction(String text) {
        return text.startsWith("click ") || text.startsWith("tap ") || text.startsWith("press ")
                || text.startsWith("you have ") || text.startsWith("your recording")
                || text.startsWith("record your voice") || text.startsWith("record the ")
                || text.startsWith("listen to ") || text.startsWith("play the ");
    }

    private static int firstColon(String text) {
        int normal = text.indexOf(':');
        int fullWidth = text.indexOf('：');
        if (normal < 0) return fullWidth;
        return fullWidth < 0 ? normal : Math.min(normal, fullWidth);
    }

    private static String label(String text) {
        return PageModel.clean(text).toLowerCase(Locale.ROOT)
                .replaceFirst("(?:[，,：:\\s]+\\d{1,2}:\\d{2})?[.!。！:：\\s]+$", "")
                .replaceFirst("[，,：:\\s]+\\d{1,2}:\\d{2}$", "");
    }

    private static void add(List<String> paragraphs, String source, boolean explicit) {
        String text = source.trim().replaceFirst("^\\s*\\d{1,3}[.)、]\\s*", "")
                .replaceAll("(?i)\\([ws]\\)", " ").replaceAll(" +", " ").trim();
        if (text.isEmpty() || text.endsWith("...") || text.endsWith("…") || !ENGLISH.matcher(text).matches()
                || isSpeechInstruction(label(text))) return;
        Matcher words = ALPHABET.matcher(text);
        int count = 0;
        while (words.find()) count++;
        if (count < (explicit ? 1 : 2) || (count < 4 && !explicit && !text.matches(".*[.!?'\"”’]$"))) return;
        if (!paragraphs.contains(text)) paragraphs.add(text);
    }
}
