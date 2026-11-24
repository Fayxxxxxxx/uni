package dev.campuspocket.app;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

final class SpeakingCards {
    private static final Pattern POINTS = Pattern.compile("\\d{1,3}(?:\\.\\d+)?\\s*分");
    final List<PageModel.Line> sentences = new ArrayList<>();
    final List<PageModel.Line> next = new ArrayList<>();
    final List<PageModel.Line> start = new ArrayList<>();
    final List<PageModel.Line> stop = new ArrayList<>();
    final List<PageModel.Line> redo = new ArrayList<>();
    private final Map<String, Integer> positions = new HashMap<>();
    private final Map<String, String> visibleScores = new HashMap<>();
    PageModel.Line current;
    boolean saved;

    static SpeakingCards read(List<PageModel.Line> lines) {
        return read(lines, false);
    }

    static SpeakingCards read(List<PageModel.Line> lines, boolean confirmedFollow) {
        boolean sample = false;
        boolean direction = confirmedFollow;
        SpeakingCards result = new SpeakingCards();
        List<PageModel.Line> replay = new ArrayList<>();
        List<PageModel.Line> marks = new ArrayList<>();
        List<PageModel.Line> phaseLabels = new ArrayList<>();
        List<PageModel.Line> grades = new ArrayList<>();
        for (PageModel.Line line : lines) {
            if (line.edit) continue;
            String label = line.text.toLowerCase(Locale.ROOT).replaceFirst("[。.!！：:]+$", "");
            sample |= label.equals("范例学习") || label.equals("示例学习")
                    || label.equals("example study") || label.equals("model learning");
            direction |= ReadAloudPage.followDirection(line.text);
            if (line.bounds == null) continue;
            if (POINTS.matcher(label).matches()) grades.add(line);
            if (label.equals("下一步") || label.equals("next step")) add(result.next, line);
            else if (ReadAloudPage.startControl(label)) add(result.start, line);
            else if (ReadAloudPage.stopControl(label)) add(result.stop, line);
            else if (ReadAloudPage.replayControl(label)) replay.add(line);
            else if (label.equals("重录") || label.equals("重新录音")) {
                add(result.redo, line);
                marks.add(line);
            } else if (ReadAloudPage.savedLabel(line.text)) marks.add(line);
            else if (label.matches("^(?:准备录音|准备中|录音中[，,\\s]*点击提交|正在录音|已提交[，,\\s]*等待评分|等待评分|正在评分)[…]*$")) {
                phaseLabels.add(line);
            }
            else if (ReadAloudPage.sentence(line.text) && result.sentences.stream().noneMatch(
                    item -> item.text.equals(line.text) && Math.abs(item.bounds.y() - line.bounds.y()) <= 2)) {
                result.sentences.add(line);
            }
        }
        if (!sample || !direction) return null;
        result.sentences.sort(Comparator.comparingInt(line -> line.bounds.y()));
        result.index(grades);
        List<PageModel.Line> controls = result.stop.isEmpty() ? result.start : result.stop;
        if (controls.isEmpty()) {
            if (phaseLabels.size() == 1) controls = phaseLabels;
            else if (result.redo.size() == 1) controls = result.redo;
        }
        if (controls.isEmpty()) {
            for (PageModel.Line line : replay) if (line.enabled && line.click) controls = replay;
            if (controls.isEmpty() && marks.size() == 1) controls = marks;
        }
        if (controls.size() != 1 || result.sentences.isEmpty()) return result;
        PageModel.Line control = controls.get(0);
        for (PageModel.Line sentence : result.sentences) {
            if (sentence.bounds.bottom > control.bounds.y()) break;
            result.current = sentence;
        }
        if (result.current == null) return result;
        PageModel.Line after = result.after(result.current.text);
        int end = after == null ? Integer.MAX_VALUE : after.bounds.top;
        for (PageModel.Line line : replay) {
            if (line.enabled && line.click && inCard(line, result.current, end)) result.saved = true;
        }
        for (PageModel.Line line : marks) {
            if (inCard(line, result.current, end)) result.saved = true;
        }
        return result;
    }

    PageModel.Line after(String previous) {
        int position = positions.getOrDefault(previous, -1);
        return position >= 0 && position + 1 < sentences.size() ? sentences.get(position + 1) : null;
    }

    boolean ambiguous(String sentence) {
        return positions.getOrDefault(sentence, -2) == -1;
    }

    String visibleScore(String sentence) { return visibleScores.getOrDefault(sentence, ""); }

    private void index(List<PageModel.Line> grades) {
        grades.sort(Comparator.comparingInt(line -> line.bounds.y()));
        int grade = 0;
        for (int i = 0; i < sentences.size(); i++) {
            PageModel.Line card = sentences.get(i);
            positions.merge(card.text, i, (a, b) -> -1);
            while (grade < grades.size() && grades.get(grade).bounds.y() < card.bounds.top) grade++;
            int compatible = 0;
            String points = "";
            for (int j = grade; j < grades.size() && grades.get(j).bounds.y() <= card.bounds.bottom; j++) {
                PageModel.Line candidate = grades.get(j);
                if (candidate.bounds.left < card.bounds.right) continue;
                compatible++;
                points = candidate.text.replaceFirst("\\s*分$", "");
            }
            if (compatible == 1 && !points.matches("0+(?:\\.0+)?")) visibleScores.put(card.text, points);
        }
    }

    private static boolean inCard(PageModel.Line line, PageModel.Line sentence, int end) {
        return line.bounds.y() >= sentence.bounds.bottom && line.bounds.y() < end;
    }

    private static void add(List<PageModel.Line> lines, PageModel.Line candidate) {
        if (!candidate.enabled) return;
        for (PageModel.Line current : lines) {
            if (Math.abs(current.bounds.y() - candidate.bounds.y()) <= 2
                    && Math.abs(current.bounds.x() - candidate.bounds.x()) <= 2) return;
        }
        lines.add(candidate);
    }
}
