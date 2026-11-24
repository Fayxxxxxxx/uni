package dev.campuspocket.app;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class TrainingPage {
    enum Kind { HOME, MENU, STEP_1, STEP_2, STEP_3, INSTRUCTIONS, CHAT_INTRO, CHAT, FREE, REPORT, UNKNOWN }

    static final String[] SECTIONS = {"范例学习", "AI口语对话", "自由表达"};
    private static final String HOLD = "^(?:按住|长按)(?:说话|发言|回答|录音|开始说话)$";
    private static final String HELD = "^松开(?:即|可|即可|后)?(?:发送|结束|完成|提交)(?:[，,、:：\\s]*(?:手指)?(?:向上滑动|上滑)取消)?[。…]*$";
    private static final Pattern INSTRUCTIONS = Pattern.compile("(?:^|\\n)(?:考前|题前|练习|训练|作答|录音|对话|口语练习|开始前)"
            + "(?:提示|须知|说明|准备)(?:$|[。！:：\\s])");

    static final String[] STOP_LABELS = {
            "停止录音", "结束录音", "停止录制", "结束录制", "完成录音", "点击停止录音",
            "点击结束录音", "停止并提交", "结束并提交"
    };
    private static final String RECORDING_HINT = "(?:录音中|正在录音|录制中|正在录制)(?:[，,：:\\s]*点击提交)?[….。]*";

    static final Pattern BLANK = Pattern.compile("_{2,}|[＿﹍]{2,}");
    private static final Pattern LIMIT = Pattern.compile("(?i)(?:in|within|for)\\s+(\\d{1,3})\\s*(seconds?|minutes?)|(?:限时|请在)\\s*(\\d{1,3})\\s*秒");
    private static final Pattern RECORDING_LIMIT = Pattern.compile("(?i)(?:录音限时|作答限时|朗读限时|录音时限|最长录音(?:时长|时间)?|recording time limit|recording limit)\\s*[:：]?\\s*(\\d{1,3})\\s*(分钟|秒|minutes?|seconds?)");
    private static final String GRADE = "\\d{1,3}(?:\\.\\d+)?(?:\\s*/\\s*\\d{1,3})?";
    private static final Pattern GRADE_WITH_UNIT = Pattern.compile("^\\s*(" + GRADE + ")\\s*分\\s*$");
    private static final Pattern OVERALL_GRADE = Pattern.compile("(?i)(?:总分|总成绩|综合(?:得分|评分)|overall score|total score)\\s*[:：]?\\s*(" + GRADE + ")(?![\\d.])\\s*分?");
    private static final Pattern ROW_GRADE = Pattern.compile("(?i)(?:得分|评分|成绩)\\s*[:：]?\\s*(" + GRADE + ")(?![\\d.])|(" + GRADE + ")\\s*分");
    private static final Pattern CLIP = Pattern.compile("^(\\d{1,3})\\s*['′’″\"]$");
    private static final Pattern ENGLISH = Pattern.compile("^[A-Za-z].*[A-Za-z]{2,}.*", Pattern.DOTALL);
    private static final Pattern SINGLE_WORD_REPLY = Pattern.compile("^[A-Za-z]{2,20}[.!?…]$");
    private static final USpeakingEngine ENGINE = new USpeakingEngine();

    final List<PageModel.Line> lines;
    final USpeakingEngine.Snapshot state;
    final Kind kind;
    final String task, bank, latestReply;
    final int speechLimit, clipDuration, replyCount;
    final SpeakingCards cards;
    final boolean taskCard;

    TrainingPage(CoursePage page, List<PageModel.Line> lines) {
        this(page, lines, false);
    }

    TrainingPage(CoursePage page, List<PageModel.Line> lines, boolean retainFollow) {
        this.lines = lines;
        state = ENGINE.read(page, lines, true, "", null, "", 0, false, retainFollow);
        taskCard = matches("(?s).*阅读[\\s\\u3000]*任务卡.*")
                || state.turns < 0 && holdControl() == null
                && lines.stream().anyMatch(line -> line.text.toLowerCase(Locale.ROOT).contains("talk with ai"))
                && lines.stream().anyMatch(line -> line.text.toLowerCase(Locale.ROOT).matches("(?s).*(?:as your classmate|following points|your conversation).*"));
        SpeakingCards parsed = page.page.reading == null ? null : page.page.reading.cards;
        cards = parsed == null && state.step == 2 && state.screen == USpeakingEngine.Screen.EXAMPLE
                ? SpeakingCards.read(lines, true) : parsed;
        if (state.protectedPage) kind = Kind.UNKNOWN;
        else if (taskCard || tutorialVisible() || instructionsVisible()) kind = Kind.INSTRUCTIONS;
        else if (adaptiveHeading() != null && (matches("(?i)^u\\s*口语(?:\\s*vip)?$")
                || historyHeading() != null && matches(".*(?:当前级别|训练计划|语音专项).*"))) kind = Kind.HOME;
        else if (state.step < 0 && state.turns < 0 && menuEntry("范例学习") != null
                && menuEntry("AI口语对话") != null && menuEntry("自由表达") != null) kind = Kind.MENU;
        else if (matches(".*(?:评价报告|评测报告|训练报告|测评报告|训练已完成|本次训练已完成).*")
                || matches("^(?:范例学习|自由表达|AI\\s*口语对话)(?:报告|结果)$")
                || has("作答分析") && has("继续下一节")) kind = Kind.REPORT;
        else if (state.screen == USpeakingEngine.Screen.EXAMPLE && state.step == 1) kind = Kind.STEP_1;
        else if (state.screen == USpeakingEngine.Screen.EXAMPLE && state.step == 2) kind = Kind.STEP_2;
        else if (state.screen == USpeakingEngine.Screen.EXAMPLE && state.step == 3) kind = Kind.STEP_3;
        else if (has("开始对话练习")) kind = Kind.CHAT_INTRO;
        else if ((state.screen == USpeakingEngine.Screen.DIALOGUE || state.turns >= 0 && holdControl() != null)
                && (state.turns >= 0 || holdControl() != null || structuredLabel(HELD) != null)) kind = Kind.CHAT;
        else if (state.screen == USpeakingEngine.Screen.FREE_SPEECH) kind = Kind.FREE;
        else kind = Kind.UNKNOWN;

        String foundTask = "", foundBank = "", reply = "";
        int seconds = 0, replies = 0;
        PageModel.Line clip = null;
        PageModel.Line holdLabel = kind == Kind.CHAT ? holdControl() : null;
        if (kind == Kind.CHAT && holdLabel == null) holdLabel = structuredLabel(HELD);
        PageModel.Line lastBubble = null;
        int visibleWidth = kind == Kind.CHAT ? lines.stream().filter(line -> line.bounds != null)
                .mapToInt(line -> line.bounds.right).max().orElse(0) : 0;
        int replyLeftLimit = holdLabel != null ? holdLabel.bounds.left + (int) ((holdLabel.bounds.right - holdLabel.bounds.left) * .40)
                : visibleWidth * 38 / 100;
        if (kind == Kind.STEP_3 || kind == Kind.FREE || kind == Kind.STEP_1 || kind == Kind.CHAT || taskCard) {
            for (PageModel.Line line : lines) {
                if (line.edit || line.bounds == null) continue;
                if (kind == Kind.CHAT) {
                    Matcher duration = CLIP.matcher(line.text.trim());
                    if (duration.matches()) {
                        clip = line;
                        continue;
                    }
                    boolean afterVoice = clip != null && line.bounds.top >= clip.bounds.y()
                            && line.bounds.left <= clip.bounds.left
                            && clip.bounds.left - line.bounds.left <= 2 * (clip.bounds.bottom - clip.bounds.top);
                    boolean sentence = line.text.contains(" ") && ENGLISH.matcher(line.text).matches();
                    boolean shortReply = (afterVoice || sameBubble(lastBubble, line)) && SINGLE_WORD_REPLY.matcher(line.text).matches();
                    if ((sentence || shortReply) && line.bounds.left < replyLeftLimit
                            && (holdLabel == null || line.bounds.y() < holdLabel.bounds.top)
                            && !(lastBubble != null && line.text.equals(lastBubble.text) && Math.abs(lastBubble.bounds.y() - line.bounds.y()) <= 3)) {
                        if (clip == null && sameBubble(lastBubble, line)) {
                            reply += " " + line.text;
                        } else {
                            reply = line.text;
                            replies++;
                            seconds = 0;
                        }
                        lastBubble = line;
                        if (afterVoice) {
                            Matcher measured = CLIP.matcher(clip.text.trim());
                            if (measured.matches()) seconds = Integer.parseInt(measured.group(1));
                        }
                        clip = null;
                    }
                    continue;
                }
                if (!ENGLISH.matcher(line.text).matches() || line.text.length() > 3500) continue;
                if (kind == Kind.STEP_3) {
                    if (BLANK.matcher(line.text).find()) foundTask = line.text;
                    else if (foundTask.isEmpty() && line.text.length() > foundBank.length()) foundBank = line.text;
                } else {
                    if (foundTask.length() < 3500) foundTask += (foundTask.isEmpty() ? "" : "\n") + line.text;
                }
            }
        }
        task = foundTask;
        bank = foundBank;
        latestReply = reply;
        replyCount = replies;
        clipDuration = seconds;
        int explicitLimit = explicitRecordingLimit(lines);
        speechLimit = explicitLimit > 0 ? explicitLimit : kind == Kind.FREE ? timeLimit(task) : 0;
    }

    private static boolean sameBubble(PageModel.Line previous, PageModel.Line next) {
        if (previous == null || previous.parent < 0 || previous.parent != next.parent
                || previous.text.endsWith("?") || previous.text.endsWith(".")) return false;
        int lineHeight = Math.min(previous.bounds.bottom - previous.bounds.top, next.bounds.bottom - next.bounds.top);
        return Math.abs(previous.bounds.left - next.bounds.left) <= Math.max(4, lineHeight / 5)
                && next.bounds.top >= previous.bounds.y() && next.bounds.top - previous.bounds.bottom < lineHeight / 2;
    }

    private boolean has(String text) { return action(text) != null; }
    private boolean matches(String re) { return lines.stream().anyMatch(line -> line.text.matches(re)); }

    private PageModel.Line structuredLabel(String pattern) {
        return structuredLabel(pattern, false);
    }

    private PageModel.Line structuredLabel(String pattern, boolean includeDisabled) {
        PageModel.Line chosen = null;
        Pattern label = Pattern.compile(pattern);
        for (PageModel.Line line : lines) {
            if (!line.edit && (line.enabled || includeDisabled) && line.bounds != null && label.matcher(compact(line.text)).matches()) {
                if (chosen != null && !sameControl(chosen, line)) return null;
                if (chosen == null || line.localControl) chosen = line;
            }
        }
        return chosen;
    }

    PageModel.Line adaptiveHeading() { return structuredLabel("自适应训练(?:本周进度.*)?"); }
    PageModel.Line historyHeading() { return structuredLabel("历史训练(?:话题.*)?"); }

    PageModel.Line menuEntry(String name) {
        return structuredLabel(Pattern.quote(compact(name))
                + "(?:(?:查看报告|开始练习|继续练习|开始训练|继续训练|已完成|未完成|未开始|待练习|待测评|未解锁|已锁定|按钮"
                + "|[（(]?(?:得分|评分|成绩)?[:：]?" + GRADE + "分[）)]?))*[›>»]?", true);
    }

    PageModel.Line holdControl() { return structuredLabel(HOLD); }

    boolean chatHolding() {
        return kind == Kind.CHAT && (state.recording == USpeakingEngine.Media.RECORDING || structuredLabel(HELD) != null);
    }

    boolean tutorialVisible() {
        return matches("(?s).*(?:操作引导|左右滑动可以切换题目|在屏幕中左右滑动|向右滑动可以切换题目|新手引导).*");
    }

    private boolean instructionsVisible() {
        return lines.stream().anyMatch(line -> !line.edit && INSTRUCTIONS.matcher(line.text).find()) && instructionButton() != null;
    }

    PageModel.Line instructionButton() {
        if (tutorialVisible()) return action("OK", "好的", "知道了", "我知道了", "确定", "跳过");
        if (taskCard) {
            PageModel.Line next = structuredLabel("(?:按钮)?下一步(?:按钮)?");
            if (next != null) return next;
            return action("继续", "开始对话练习");
        }
        return action("我知道了", "知道了", "我明白了", "下一步", "开始对话练习", "开始练习", "开始答题", "开始训练", "继续", "跳过");
    }

    static boolean semanticDescription(String name) {
        return compact(name).matches(HOLD) || compact(name).matches(HELD)
                || compact(name).matches("(?:阅读任务卡|下一步|已完成|打勾|完成图标|已测评|未解锁|已锁定|锁定|✓|✔)");
    }

    TrainingMarkers.Status menuTextStatus(int index) {
        PageModel.Line row = menuEntry(SECTIONS[index]);
        if (row == null) return TrainingMarkers.Status.UNKNOWN;
        if (hasReportFor(SECTIONS[index]) || compact(row.text).endsWith("已完成")) return TrainingMarkers.Status.DONE;
        if (!row.enabled || compact(row.text).matches(".*(?:未解锁|已锁定)$")) return TrainingMarkers.Status.LOCKED;
        for (PageModel.Line line : lines) {
            if (line.bounds == null || line.edit || Math.abs(line.bounds.y() - row.bounds.y()) > row.bounds.bottom - row.bounds.top) continue;
            String name = compact(line.text);
            if (name.matches("(?:已完成|打勾|完成图标|已测评|✓|✔)")) return TrainingMarkers.Status.DONE;
            if (name.matches("(?:未解锁|已锁定|锁定|请先完成上一项)")) return TrainingMarkers.Status.LOCKED;
            if (name.matches("(?:待练习|未完成|未开始|当前训练|开始练习|继续练习)")) return TrainingMarkers.Status.READY;
        }
        return TrainingMarkers.Status.UNKNOWN;
    }

    String menuKey() {
        StringBuilder key = new StringBuilder();
        for (int i = 0; i < SECTIONS.length; i++) {
            PageModel.Line row = menuEntry(SECTIONS[i]);
            if (row != null) key.append(row.text).append('@').append(row.bounds.y() / 8).append('|').append(menuTextStatus(i));
        }
        return key.toString();
    }

    String instructionsKey() {
        StringBuilder key = new StringBuilder();
        for (PageModel.Line line : lines) {
            if (!line.edit && !line.text.isEmpty() && line.text.length() < 300) key.append(line.text).append('\n');
            if (key.length() > 4000) break;
        }
        return key.toString();
    }

    static boolean launchControl(String text) {
        return compact(text).matches("(?:按钮)?(?:开始|继续)(?:训练|练习)(?:按钮)?[›>»]?");
    }

    private static String compact(String text) { return text.replaceAll("[\\s\\u00a0\\u3000]+", "").toLowerCase(Locale.ROOT); }

    private static boolean sameControl(PageModel.Line a, PageModel.Line b) {
        return a.id > 0 && a.id == b.id || a.bounds != null && b.bounds != null
                && Math.abs(a.bounds.x() - b.bounds.x()) <= 4 && Math.abs(a.bounds.y() - b.bounds.y()) <= 4;
    }

    PageModel.Line homeStart() {
        if (kind != Kind.HOME) return null;
        PageModel.Line section = adaptiveHeading();
        PageModel.Line history = historyHeading();
        if (section == null || section.bounds == null) return null;
        PageModel.Line chosen = null;
        for (PageModel.Line line : lines) {
            if (line.edit || !line.enabled || line.bounds == null
                    || !launchControl(line.text)
                    || line.bounds.y() < section.bounds.bottom
                    || history != null && history.bounds != null && line.bounds.y() >= history.bounds.top) continue;
            if (chosen != null && !sameControl(chosen, line)) return null;
            if (chosen == null || line.localControl) chosen = line;
        }
        return chosen;
    }

    PageModel.Line stopRecording() { return action(STOP_LABELS); }

    PageModel.Line recordingHint() {
        PageModel.Line hint = null;
        for (PageModel.Line line : lines) {
            if (line.edit || !line.enabled || line.bounds == null || !line.text.matches(RECORDING_HINT)) continue;
            if (hint != null && (hint.bounds.x() != line.bounds.x() || hint.bounds.y() != line.bounds.y())) return null;
            hint = line;
        }
        return hint;
    }

    boolean recording() {
        if (state.recording == USpeakingEngine.Media.RECORDING) return true;
        if (kind != Kind.STEP_2 && kind != Kind.STEP_3 && kind != Kind.FREE) return false;
        return (state.recording == USpeakingEngine.Media.UNKNOWN || state.recording == USpeakingEngine.Media.READY)
                && (stopRecording() != null || recordingHint() != null);
    }

    boolean recordingEnded() {
        USpeakingEngine.Media media = state.recording;
        return media == USpeakingEngine.Media.SAVING || media == USpeakingEngine.Media.SCORING
                || media == USpeakingEngine.Media.SCORED || media == USpeakingEngine.Media.SAVED
                || state.completed || confirmsTrainingAction();
    }

    boolean isDialogueReport() { return matches(".*AI\\s*口语对话.*报告.*") || has("继续下一节") && has("作答分析"); }
    boolean isFreeReport() { return matches(".*自由表达.*报告.*"); }

    boolean confirmsTrainingAction() {
        return !matches(".*(?:丢失|放弃|作废|不保存).*") && (
                matches(".*(?:是否|确定要?|确认要?|您确认)[：:，,\\s]*(?:结束(?:本次)?对话|提交(?:本次)?(?:录音|练习|训练|答案|范例学习)).*")
                || action("确认提交", "确定提交", "确认结束") != null
                || matches(".*提交后.*(?:无法|不能|不可)(?:修改|重录|更改).*") && action("确定", "提交") != null);
    }

    PageModel.Line confirmButton() { return action("确认提交", "确定提交", "确认结束", "确定", "确认", "提交"); }

    PageModel.Line submitRecording() {
        return recording() ? null : action("提交录音", "提交作答", "保存并提交", "确认提交", "提交答案", "提交");
    }

    PageModel.Line submitExample() {
        return action("提交范例学习", "完成范例学习", "提交并完成", "提交练习", "完成练习",
                "提交答案", "提交", "完成", "查看报告", "继续下一节", "下一步");
    }

    PageModel.Line leaveReport() {
        return action("继续下一节", "下一节", "继续训练", "返回训练", "返回目录", "返回", "关闭");
    }

    boolean hasReportFor(String section) {
        PageModel.Line title = menuEntry(section);
        return title != null && (title.text.contains("查看报告") || lines.stream().anyMatch(line -> line.bounds != null
                && line.text.equals("查看报告")
                && Math.abs(line.bounds.y() - title.bounds.y()) < title.bounds.bottom - title.bounds.top));
    }

    PageModel.Line reportEntry(int section) {
        PageModel.Line row = menuEntry(SECTIONS[section]);
        if (row == null || !row.enabled) return null;
        if (compact(row.text).contains("查看报告")) return row;
        PageModel.Bounds range = menuRowRange(section, row);
        return lines.stream().filter(line -> line.enabled && compact(line.text).equals("查看报告") && inMenuRow(range, line))
                .findFirst().orElse(null);
    }

    String menuScore(int section) {
        PageModel.Line row = menuEntry(SECTIONS[section]);
        if (row == null) return "";
        Matcher embedded = ROW_GRADE.matcher(row.text);
        if (embedded.find()) return embedded.group(1) == null ? embedded.group(2) : embedded.group(1);
        PageModel.Bounds range = menuRowRange(section, row);
        String value = "";
        for (PageModel.Line line : lines) {
            if (!inMenuRow(range, line)) continue;
            Matcher points = GRADE_WITH_UNIT.matcher(line.text);
            if (!points.matches()) continue;
            String candidate = points.group(1);
            if (!value.isEmpty() && !value.equals(candidate)) return "";
            value = candidate;
        }
        return value;
    }

    private PageModel.Bounds menuRowRange(int section, PageModel.Line row) {
        int center = row.bounds.y();
        int height = Math.max(12, row.bounds.bottom - row.bounds.top);
        int top = center - height, bottom = center + height;
        for (int i = 0; i < SECTIONS.length; i++) {
            if (i == section) continue;
            PageModel.Line other = menuEntry(SECTIONS[i]);
            if (other == null) continue;
            int otherCenter = other.bounds.y();
            if (otherCenter < center) top = Math.max(top, (otherCenter + center) / 2 + 1);
            else if (otherCenter > center) bottom = Math.min(bottom, (otherCenter + center - 1) / 2);
            else return new PageModel.Bounds(0, 1, 0, 0);
        }
        return new PageModel.Bounds(0, top, 0, bottom);
    }

    private static boolean inMenuRow(PageModel.Bounds range, PageModel.Line line) {
        return line.bounds != null && !line.edit && line.bounds.y() >= range.top && line.bounds.y() <= range.bottom;
    }

    String reportScore() {
        for (PageModel.Line line : lines) {
            if (line.edit) continue;
            Matcher grade = OVERALL_GRADE.matcher(line.text);
            if (grade.find() && TrainingScore.percentage(grade.group(1)) >= 0) return grade.group(1);
        }
        return state.score;
    }

    PageModel.Line retestButton() {
        return action("重新测评", "再次测评", "再测一次", "重新练习", "再次练习", "再练一次",
                "重新训练", "再次训练", "重新录音", "重录");
    }

    PageModel.Line retestConfirmation() {
        if (!matches(".*(?:是否|确定|确认|将要).*(?:重新(?:录音|练习|测评|训练)|覆盖(?:本次|上次)?(?:成绩|录音)).*")
                && action("确认重录", "确认重测", "确认重新练习") == null) return null;
        return action("确认重录", "确认重测", "确认重新练习", "确定", "确认");
    }

    PageModel.Line action(String... labels) {
        for (String label : labels) {
            PageModel.Line match = null;
            for (PageModel.Line line : lines) {
                if (line.edit || !line.enabled || !line.text.equalsIgnoreCase(label)) continue;
                if (match != null && match.id > 0 && match.id == line.id) {
                    if (line.localControl) match = line;
                    continue;
                }
                if (match != null && (match.bounds == null || line.bounds == null
                        || match.bounds.x() != line.bounds.x() || match.bounds.y() != line.bounds.y())) return null;
                match = line;
            }
            if (match != null) return match;
        }
        return null;
    }

    PageModel.Line microphone() {
        if (state.issue != USpeakingEngine.Issue.NONE || recording()
                || state.recording == USpeakingEngine.Media.PREPARING
                || state.recording == USpeakingEngine.Media.SCORING
                || state.recording == USpeakingEngine.Media.SAVING) return null;
        if (kind == Kind.STEP_2 && cards != null && cards.current != null) {
            if (!scored() && cards.start.size() == 1) return cards.start.get(0);
            if (scored() && cards.redo.size() == 1) return cards.redo.get(0);
        } else if (kind == Kind.STEP_3 || kind == Kind.FREE) {
            PageModel.Line start = action("点击录音", "开始录音", "录音", "开始录制", "点击按钮录音",
                    "点击开始录音", "点击按钮开始录音", "点此录音", "立即录音");
            if (!scored() && start != null) return start;
            if (scored() || state.recording == USpeakingEngine.Media.SAVED) return retestButton();
        }
        return null;
    }

    boolean scored() { return TrainingScore.percentage(state.score) >= 0
            && (state.issue == USpeakingEngine.Issue.NONE || state.recordingTimedOut())
            && (state.recording == USpeakingEngine.Media.SCORED || state.screen == USpeakingEngine.Screen.RESULT); }

    String identity() {
        switch (kind) {
            case STEP_2: return cards == null || cards.current == null ? "" : cards.current.text;
            case STEP_3: return state.question + "/" + state.questions + "|" + task;
            case CHAT: return state.turns + "/" + state.turnLimit + "|" + latestReply;
            case FREE: return task;
            default: return "";
        }
    }

    private static int timeLimit(String task) {
        Matcher match = LIMIT.matcher(task);
        if (!match.find()) return 0;
        if (match.group(3) != null) return Integer.parseInt(match.group(3));
        return Integer.parseInt(match.group(1)) * (match.group(2).toLowerCase(Locale.ROOT).startsWith("m") ? 60 : 1);
    }

    private static int explicitRecordingLimit(List<PageModel.Line> lines) {
        for (PageModel.Line line : lines) {
            if (line.edit) continue;
            Matcher limit = RECORDING_LIMIT.matcher(line.text);
            if (limit.find()) {
                String unit = limit.group(2).toLowerCase(Locale.ROOT);
                return Integer.parseInt(limit.group(1)) * (unit.startsWith("min") || unit.equals("分钟") ? 60 : 1);
            }
        }
        return 0;
    }

    static String complete(String cloze, String bank, List<String> choices) {
        Matcher gaps = BLANK.matcher(cloze);
        StringBuffer spoken = new StringBuffer();
        int index = 0;
        while (gaps.find()) {
            if (index >= choices.size()) throw new IllegalArgumentException("填空数量与空格不一致。");
            String choice = choices.get(index++).trim().replaceAll("\\s+", " ");
            if (!choice.matches("[A-Za-z][A-Za-z '’\\-]{0,100}") || !normalize(bank).contains(normalize(choice))) {
                throw new IllegalArgumentException("生成的填空不在页面词库里。");
            }
            gaps.appendReplacement(spoken, Matcher.quoteReplacement(choice));
        }
        if (index == 0 || index != choices.size()) throw new IllegalArgumentException("填空数量与空格不一致。");
        return gaps.appendTail(spoken).toString();
    }

    private static String normalize(String text) {
        return text.toLowerCase(Locale.ROOT).replaceAll("[^a-z]", "");
    }
}
