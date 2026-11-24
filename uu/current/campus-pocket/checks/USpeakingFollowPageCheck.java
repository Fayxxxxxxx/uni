package dev.campuspocket.app;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

final class USpeakingFollowPageCheck {
    private static final String WORDS = "Please tell me how you prepare for a presentation.";

    private static PageModel.Line line(int id, String label, int x, int y, int right, int bottom, boolean control) {
        return new PageModel.Line(id, label, control, false, false, false, false, true,
                new PageModel.Bounds(x, y, right, bottom), 12);
    }

    private static USpeakingFollowPage screen(String status, String points) {
        List<PageModel.Line> lines = Arrays.asList(
                line(0, "范例学习", 130, 0, 280, 35, false),
                line(0, "Step 1", 10, 35, 90, 64, false),
                line(0, "Step 2", 100, 35, 190, 64, false),
                line(0, "Step 3", 220, 35, 290, 64, false),
                line(0, "跟读以下句子。", 30, 80, 160, 120, false),
                line(1, WORDS, 20, 140, 290, 220, true),
                line(0, points, 310, 140, 365, 220, false),
                line(2, status, 130, 315, 240, 350, false),
                line(3, "Please listen carefully to the message.", 20, 400, 290, 445, true));
        return USpeakingFollowPage.read(CoursePage.read(PageModel.read(lines, false), lines), lines);
    }

    private static void check(boolean success, String message) {
        if (!success) throw new AssertionError(message);
    }

    public static void main(String[] args) {
        USpeakingFollowPage ready = screen("点击录音", "0分");
        check(ready != null && WORDS.equals(ready.sentence) && ready.microphone() != null, "Find an unrecorded card");
        List<USpeakingFollowPage.MicBox> correct = Collections.singletonList(
                new USpeakingFollowPage.MicBox(12, new PageModel.Bounds(138, 220, 233, 310)));
        check(USpeakingFollowPage.microphoneIcon(ready.microphone(), correct) != null, "Use the icon above its caption");
        check(USpeakingFollowPage.microphoneIcon(ready.microphone(),
                    Arrays.asList(correct.get(0), correct.get(0))) == null, "Skip ambiguous targets");
        check(USpeakingFollowPage.microphoneIcon(ready.microphone(), Collections.singletonList(
                    new USpeakingFollowPage.MicBox(14, correct.get(0).bounds))) == null, "Ignore another card's icon");
        USpeakingFollowPage waiting = screen("已提交 等待评分", "0分");
        check(waiting != null && !waiting.scored() && waiting.microphone() == null, "Wait through scoring even with placeholder points");
        USpeakingFollowPage zero = screen("重录", "0分");
        check(zero != null && zero.scored() && !zero.positiveScore() && zero.microphone() != null,
                "A zero result can be retried");
        USpeakingFollowPage positive = screen("重录", "82分");
        check(positive != null && positive.positiveScore() && positive.microphone() == null
                    && positive.cards.after(positive.sentence) != null, "Advance without replacing a scored recording");
        System.out.println("Passed 7 targeted Step 2 checks");
    }
}
