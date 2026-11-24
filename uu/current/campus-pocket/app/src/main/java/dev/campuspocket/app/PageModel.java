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

final class PageModel {
    private static final Pattern OPTION = Pattern.compile("^([A-Ha-h])\\s*[.、．:：)）]\\s*(.+)$");
    private static final Pattern BARE_OPTION = Pattern.compile("^[A-Ha-h]\\s*[.、．:：)）]?$");
    private static final Pattern NUMBER = Pattern.compile("^(?:第\\s*(\\d{1,3})\\s*题|(?:Question\\s+|Q\\s*)?(\\d{1,3})\\s*(?:[.、．)）:：]|(?=\\s*$)))\\s*(.*)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern TASK = Pattern.compile("(?:^|\\b)(?:Unit|Lesson|Task|Exercise|Activity|Part|Section)\\s*[0-9IVX]+(?:\\b|[.、])|第.{1,6}[单课章]元?|未完成|已完成|未开始|待完成|必修|选修", Pattern.CASE_INSENSITIVE);
    private static final Pattern BLOCK = Pattern.compile("验证码|手机号码|请输入密码|账号登录|密码登录|人脸识别|正在监考|开启监考|proctoring|verification code", Pattern.CASE_INSENSITIVE);
    private static final Set<String> SUBMIT = new LinkedHashSet<>(java.util.Arrays.asList("提交", "提交答案", "交卷", "submit", "submit answers", "submit answer"));
    private static final Set<String> NEXT = new LinkedHashSet<>(java.util.Arrays.asList("下一题", "下一练习", "next question", "next exercise"));
    private static final Pattern RESULT = Pattern.compile("提交成功|作答已提交|本题得分|answers submitted|your score", Pattern.CASE_INSENSITIVE);
    private static final Set<String> PLAY = new LinkedHashSet<>(java.util.Arrays.asList("播放", "播放音频", "播放视频", "play", "play audio", "play video"));
    private static final Pattern CUE = Pattern.compile("choose|select|fill\\s+in|complete\\s+the|answer\\s+the|listen|listening|speak|speaking|read\\s+aloud|translate|write|multiple choice|单选|多选|选择|填空|听力|听录音|音频|朗读|口语|录音|作文|翻译", Pattern.CASE_INSENSITIVE);
    private static final Pattern MULTI = Pattern.compile("多选|choose (?:all|two|three)|select (?:all|two|three)|multiple answers", Pattern.CASE_INSENSITIVE);

    static final class Line {
        final int id, parent;
        final String text;
        final boolean click, edit, filled, checked, checkbox, enabled, localControl;
        final Bounds bounds;

        Line(int id, String text, boolean click, boolean edit, boolean filled, boolean checked, boolean checkbox) {
            this(id, text, click, edit, filled, checked, checkbox, true, null);
        }

        Line(int id, String text, boolean click, boolean edit, boolean filled, boolean checked, boolean checkbox,
             boolean enabled, Bounds bounds) {
            this(id, text, click, edit, filled, checked, checkbox, enabled, bounds, -1);
        }

        Line(int id, String text, boolean click, boolean edit, boolean filled, boolean checked, boolean checkbox,
             boolean enabled, Bounds bounds, int parent) {
            this(id, text, click, edit, filled, checked, checkbox, enabled, bounds, parent, click);
        }

        Line(int id, String text, boolean click, boolean edit, boolean filled, boolean checked, boolean checkbox,
             boolean enabled, Bounds bounds, int parent, boolean localControl) {
            this.id = id;
            this.parent = parent;
            this.text = clean(text);
            this.click = click;
            this.edit = edit;
            this.filled = filled;
            this.checked = checked;
            this.checkbox = checkbox;
            this.enabled = enabled;
            this.localControl = localControl;
            this.bounds = bounds;
        }
    }

    static final class Bounds {
        final int left, top, right, bottom;

        Bounds(int left, int top, int right, int bottom) {
            this.left = left;
            this.top = top;
            this.right = right;
            this.bottom = bottom;
        }

        int x() { return left + (right - left) / 2; }
        int y() { return top + (bottom - top) / 2; }

        boolean nearControl(int left, int top, int right, int bottom) {
            return x() >= left && x() <= right && y() >= top && y() <= bottom
                    && right - left <= 4 * (this.right - this.left) && bottom - top <= 5 * (this.bottom - this.top);
        }
    }

    static final class Group {
        final int index;
        final String label;
        final Map<String, Line> choices = new LinkedHashMap<>();
        final List<Line> fields = new ArrayList<>();
        boolean ambiguous, multiple;

        Group(int index, String label) {
            this.index = index;
            this.label = label;
        }
    }

    static final class Page {
        final List<Group> groups = new ArrayList<>();
        final List<Line> tasks = new ArrayList<>();
        final List<Line> submit = new ArrayList<>();
        final List<Line> next = new ArrayList<>();
        final List<Line> play = new ArrayList<>();
        final String text;
        ReadAloudPage reading;
        boolean blocked, exercise, result;

        Page(String text) { this.text = text; }

        String fingerprint() {
            StringBuilder signature = new StringBuilder(text);
            for (Group g : groups) {
                signature.append('|').append(g.label).append('/').append(g.ambiguous).append('/').append(g.multiple);
                for (Map.Entry<String, Line> c : g.choices.entrySet()) {
                    signature.append(c.getKey()).append(':').append(c.getValue().id).append(';');
                }
                for (Line f : g.fields) signature.append('#').append(f.id);
            }
            return signature.toString();
        }

        String audioKey() {
            StringBuilder key = new StringBuilder();
            for (String line : text.split("\n")) {
                if (line.length() >= 18 && !line.matches(".*[0-9]+:[0-9]+.*")) key.append(line).append('\n');
            }
            return key.toString();
        }

        String automationKey() {
            return reading != null && !reading.target().isEmpty() && groups.isEmpty()
                    ? "口语\n" + reading.target() : fingerprint();
        }

        String modelInput() {
            if (reading != null && reading.cards != null && !reading.passage.isEmpty()) {
                return "跟读当前句子。请给出句子重音、停顿和一个常见发音问题。\n" + reading.passage;
            }
            StringBuilder prompt = new StringBuilder(text);
            if (!groups.isEmpty()) {
                prompt.append("\n\n可识别的答题控件（id 为顺序编号）:");
                for (Group g : groups) {
                    prompt.append("\nid=").append(g.index).append(" 标题 ").append(g.label);
                    if (!g.choices.isEmpty()) {
                        prompt.append(g.multiple ? " 多选 " : " 单选 ");
                        for (Map.Entry<String, Line> choice : g.choices.entrySet()) {
                            prompt.append("\n").append(choice.getKey()).append(" → ").append(choice.getValue().text);
                        }
                    }
                    if (!g.fields.isEmpty()) prompt.append(" 文本框数 ").append(g.fields.size());
                    if (g.ambiguous) prompt.append("（结构不明确，仅生成解析）");
                }
            }
            return prompt.toString();
        }
    }

    static Page read(List<Line> lines, boolean sensitive) {
        lines = joinOptions(lines);
        StringBuilder visible = new StringBuilder();
        String preceding = "";
        for (Line line : lines) {
            if (!line.edit && !line.text.isEmpty() && !line.text.equals(preceding)) {
                if (visible.length() > 0) visible.append('\n');
                visible.append(line.text);
                preceding = line.text;
            }
        }
        String rawText = visible.toString();
        Page page = new Page(rawText.length() > 15000 ? rawText.substring(0, 15000) : rawText);
        page.blocked = sensitive || BLOCK.matcher(rawText).find();
        if (page.blocked) return page;
        page.reading = ReadAloudPage.read(lines);
        page.result = RESULT.matcher(rawText).find() || page.reading.graded;

        Group group = new Group(1, "当前题");
        boolean anyCue = CUE.matcher(rawText).find();
        boolean multi = MULTI.matcher(rawText).find();
        boolean hasQuestion = rawText.contains("?") || rawText.contains("？");
        Set<Integer> taskIds = new LinkedHashSet<>();
        Set<Integer> submits = new LinkedHashSet<>();
        Set<Integer> nexts = new LinkedHashSet<>();
        Set<Integer> plays = new LinkedHashSet<>();
        for (Line line : lines) {
            String label = line.text.toLowerCase(Locale.ROOT);
            if (line.edit) {
                group.fields.add(line);
                continue;
            }
            if (line.click && SUBMIT.contains(label) && submits.add(line.id)) page.submit.add(line);
            if (line.click && NEXT.contains(label) && nexts.add(line.id)) page.next.add(line);
            if (line.click && PLAY.contains(label) && plays.add(line.id)) page.play.add(line);
            if (line.click && TASK.matcher(line.text).find() && line.text.length() <= 160
                    && taskIds.add(line.id)) page.tasks.add(line);
            Matcher number = NUMBER.matcher(line.text);
            if (number.matches() && !TASK.matcher(line.text).find()) {
                if (hasControls(group)) {
                    page.groups.add(group);
                    group = new Group(page.groups.size() + 1, line.text);
                } else {
                    group = new Group(page.groups.size() + 1, line.text);
                }
            }
            Matcher option = OPTION.matcher(line.text);
            if (option.matches() && line.click) {
                String key = option.group(1).toUpperCase(Locale.ROOT);
                Line existing = group.choices.get(key);
                if (existing != null && existing.id != line.id) group.ambiguous = true;
                group.choices.put(key, line);
                group.multiple |= line.checkbox;
            }
        }
        if (hasControls(group)) page.groups.add(group);
        boolean choiceSection = false;
        for (Group g : page.groups) {
            Set<Integer> unique = new LinkedHashSet<>();
            for (Line choice : g.choices.values()) unique.add(choice.id);
            if ((!g.choices.isEmpty() && g.choices.size() < 2) || (!g.choices.isEmpty() && !g.fields.isEmpty())
                    || unique.size() != g.choices.size()) g.ambiguous = true;
            g.multiple |= multi;
            choiceSection |= g.choices.size() >= 2;
        }
        boolean writable = !page.groups.isEmpty();
        page.exercise = !page.result && (!page.reading.target().isEmpty()
                || (choiceSection && (anyCue || hasQuestion || page.tasks.isEmpty()))
                || (writable && (anyCue || hasQuestion) && (!page.submit.isEmpty() || page.tasks.isEmpty()))
                || (!page.submit.isEmpty() && (anyCue || hasQuestion))
                || (rawText.length() >= 35 && anyCue && hasQuestion && page.tasks.isEmpty()));
        return page;
    }

    private static List<Line> joinOptions(List<Line> input) {
        List<Line> joined = new ArrayList<>();
        for (int i = 0; i < input.size(); i++) {
            Line line = input.get(i);
            if (line.click && BARE_OPTION.matcher(line.text).matches() && i + 1 < input.size()) {
                Line next = input.get(i + 1);
                if (line.id == next.id && !next.edit && !next.text.isEmpty()
                        && !BARE_OPTION.matcher(next.text).matches() && !OPTION.matcher(next.text).matches()) {
                    String marker = line.text.substring(0, 1);
                    joined.add(new Line(line.id, marker + ". " + next.text, true, false, false,
                            line.checked || next.checked, line.checkbox || next.checkbox));
                    i++;
                    continue;
                }
            }
            joined.add(line);
        }
        return joined;
    }

    private static boolean hasControls(Group group) {
        return !group.choices.isEmpty() || !group.fields.isEmpty();
    }

    static boolean isSubmit(String label) {
        return SUBMIT.contains(label);
    }

    static boolean isPlayback(String label) { return PLAY.contains(label.toLowerCase(Locale.ROOT)); }

    static String clean(CharSequence input) {
        if (input == null) return "";
        return input.toString().trim().replaceAll("[\\t \\u00a0]+", " ").replace('\r', '\n');
    }
}
