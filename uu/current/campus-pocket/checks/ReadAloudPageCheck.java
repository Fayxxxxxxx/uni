package dev.campuspocket.app;

import java.util.Arrays;

final class ReadAloudPageCheck {
    private static final String PASSAGE = "We left the station just before sunrise.";

    private static PageModel.Line line(int id, String label) {
        return new PageModel.Line(id, label, id > 0, false, false, false, false);
    }

    private static PageModel.Page page(PageModel.Line... lines) {
        return PageModel.read(Arrays.asList(lines), false);
    }

    private static void check(boolean accepted, String description) {
        if (!accepted) throw new AssertionError(description);
    }

    public static void main(String[] args) {
        PageModel.Page ready = page(line(0, "Task 3. Read the following passage aloud."),
                line(0, PASSAGE), line(0, "Click the record button to begin."), line(5, "Start recording"));
        check(ready.exercise && ready.groups.isEmpty(), "Read-aloud task uses its own automation");
        check(PASSAGE.equals(ready.reading.passage), "Synthesize the passage without the directions");
        check(ready.reading.start.size() == 1 && ready.reading.stop.isEmpty(), "Recognize the exact start control");

        PageModel.Page recording = page(line(0, "请朗读下列课文"), line(0, "朗读原文：" + PASSAGE),
                line(5, "停止录音，00:01"), line(0, "00:01"));
        check(recording.reading.stop.size() == 1 && recording.reading.start.isEmpty(), "Recognize recording controls with timers");
        check(recording.automationKey().equals(ready.automationKey()), "The changing recording state keeps the same passage identity");

        PageModel.Page saved = page(line(0, "Read aloud"), line(0, PASSAGE), line(0, "Recording saved."),
                line(6, "Play my recording"), line(8, "Submit recording"));
        check(saved.reading.saved && saved.reading.submit.size() == 1, "Submit is separate from save");
        check(!saved.result && saved.reading.stop.isEmpty(), "Saving alone is not a confirmed platform result");

        check(page(line(0, "朗读得分：92")).result, "A numeric reading score is a result");
        check(!page(line(0, "录音评分")).result, "The grade placeholder is not a result");
        check(page(line(0, "单词朗读"), line(0, "education"), line(5, "开始录音"))
                .reading.passage.equals("education"), "Read an identified vocabulary word");
        check(page(line(0, "Read aloud"), line(0, "Answer the following questions."), line(0, PASSAGE),
                line(4, "Record")).reading.passage.isEmpty(), "Do not read generated answers for free-response tasks");
        check(page(line(8, "段落朗读"), line(8, "未完成"), line(9, "句子朗读 已完成")).reading.pending.size() == 1,
                "Open only an explicitly pending visible task entry");
        check(page(line(0, "Read aloud"), line(0, PASSAGE), line(3, "Record"), line(4, "Start recording"))
                .reading.start.size() == 2, "Retain ambiguity instead of selecting a recording row");
        check(PageModel.read(Arrays.asList(line(0, "Read aloud"), line(0, PASSAGE), line(1, "Start recording")), true)
                .reading == null, "Authentication screens remain excluded");
        System.out.println("Passed 14 read-aloud checks");
    }
}
