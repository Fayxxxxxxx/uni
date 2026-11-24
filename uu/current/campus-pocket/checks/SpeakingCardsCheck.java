package dev.campuspocket.app;

import java.util.Arrays;
import java.util.List;

final class SpeakingCardsCheck {
    private static final String FIRST = "How do you usually seek academic help in college?";
    private static final String SECOND = "A quick question after class, an email, or office hours?";
    private static final String THIRD = "For me, nothing beats office hours.";

    private static PageModel.Line line(int id, String text, int x, int y, boolean enabled) {
        return new PageModel.Line(id, text, id > 0 && enabled, false, false, false, false, enabled,
                new PageModel.Bounds(x, y, x + 100, y + 30));
    }

    private static PageModel.Page follow(String recordingLabel, boolean replay, boolean next, boolean laterCard) {
        List<PageModel.Line> values = Arrays.asList(line(0, "范例学习", 160, 10, true),
                line(2, "下一步", 320, 10, next), line(0, "跟读以下句子。", 20, 48, true),
                line(laterCard ? 3 : 0, FIRST, 20, 100, true),
                line(laterCard ? 0 : 4, SECOND, 20, laterCard ? 145 : 215, true),
                line(5, "播放", 20, 180, true), line(6, recordingLabel, 150, 180, true),
                line(7, "回放", 270, 180, replay), line(8, THIRD, 20, 280, true));
        return PageModel.read(values, false);
    }

    private static void check(boolean result, String message) {
        if (!result) throw new AssertionError(message);
    }

    public static void main(String[] args) {
        PageModel.Page idle = follow("录音", false, false, false);
        check(idle.exercise && idle.reading.passage.equals(FIRST), "Read only the expanded sentence");
        check(idle.reading.question.isEmpty() && !idle.reading.saved, "A question-shaped sentence is still a reading prompt");
        check(idle.reading.cards.after(FIRST).text.equals(SECOND), "Identify the adjacent collapsed sentence");
        check(idle.reading.cards.next.isEmpty(), "Ignore a disabled next step");
        check(idle.modelInput().contains(FIRST) && !idle.modelInput().contains(SECOND), "Analysis uses the active sentence");
        check(follow("录音中 00:03", false, false, false).reading.stop.size() == 1, "Observe the active recording label");
        check(follow("录音", true, false, false).reading.saved, "Recognize the corresponding replay becoming available");
        PageModel.Page second = follow("录音", false, true, true);
        check(second.reading.passage.equals(SECOND) && second.reading.cards.after(SECOND).text.equals(THIRD),
                "Observe the next card after expansion");
        List<PageModel.Line> question = Arrays.asList(line(0, "范例学习", 160, 10, true),
                line(0, "请回答以下问题并录音。", 20, 48, true), line(0, FIRST, 20, 100, true),
                line(7, "开始录音", 150, 180, true));
        PageModel.Page open = PageModel.read(question, false);
        check(open.reading.answerRecording() && open.reading.question.contains(FIRST)
                && open.reading.passage.isEmpty(), "Route explicit speech questions to generated answers");
        check(CoursePage.read(open, question).kind == CoursePage.Kind.SPEAKING, "Classify open speech in the course monitor");
        System.out.println("Passed 10 follow-along and open-speaking checks");
    }
}
