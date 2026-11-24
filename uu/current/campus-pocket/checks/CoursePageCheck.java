package dev.campuspocket.app;

import java.util.Arrays;
import java.util.List;

final class CoursePageCheck {
    private static PageModel.Line label(int action, String text) {
        return new PageModel.Line(action, text, action > 0, false, false, false, false);
    }

    private static PageModel.Line field(int action) {
        return new PageModel.Line(action, "", false, true, false, false, false);
    }

    private static CoursePage screen(PageModel.Line... lines) {
        List<PageModel.Line> snapshot = Arrays.asList(lines);
        return CoursePage.read(PageModel.read(snapshot, false), snapshot);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    public static void main(String[] args) {
        CoursePage catalog = screen(label(0, "课程目录"), label(0, "Unit 1"),
                label(1, "Task 1. 选择题"), label(1, "未完成"), label(2, "Task 2. 朗读 已完成"),
                label(3, "期末考试 未完成"), label(4, "Task 3. 翻译 待完成"));
        check(catalog.kind == CoursePage.Kind.CATALOG, "Recognize catalog");
        check(catalog.tasks.size() == 2 && catalog.tasks.get(0).action.id == 1, "Map visible pending tasks in display order");
        check(catalog.completedTaskKeys.size() == 1 && catalog.catalogMarks.contains("unit 1"), "Read completion and unit labels");
        check(screen(label(0, "课程目录"), label(2, "Task 1 已完成")).kind == CoursePage.Kind.CATALOG,
                "An empty pending queue remains a catalog");

        CoursePage objective = screen(label(0, "Choose the correct answer."),
                label(0, "She has been speaking English since 2020."),
                label(2, "A. for"), label(3, "B. since"), label(4, "提交"));
        check(objective.kind == CoursePage.Kind.CHOICE && objective.compatibleAnswers(),
                "Exercise text mentioning speaking does not override button evidence");
        CoursePage listening = screen(label(0, "Listen to the audio and choose the correct answer."),
                label(9, "播放音频"), label(0, "00:00 / 02:13"), label(0, "限时 5 分钟"),
                label(2, "A. next week"), label(3, "B. last year"), label(4, "提交"));
        check(listening.kind == CoursePage.Kind.LISTENING && listening.play.size() == 1, "Audio comprehension needs media");
        check(listening.durationSeconds == 133 && listening.elapsedSeconds == 0 && listening.timeLimitSeconds == 300,
                "Separate the observed duration and the time limit");

        CoursePage playing = screen(label(0, "观看视频"), label(3, "暂停播放"), label(0, "00:11 / 01:02"));
        check(playing.kind == CoursePage.Kind.MEDIA && playing.playing && !playing.mediaEnded,
                "Playback is not complete while paused control is visible");
        CoursePage ended = screen(label(0, "观看视频"), label(0, "播放结束"), label(9, "返回目录"));
        check(ended.mediaEnded && ended.back.size() == 1, "Recognize playback completion and the explicit return path");
        CoursePage result = screen(label(0, "提交成功"), label(2, "下一任务"));
        check(result.kind == CoursePage.Kind.RESULT && result.next.size() == 1, "Navigate from a confirmed result");
        check(screen(label(0, "答题报告"), label(0, "客观题得分 7"), label(2, "返回目录")).kind == CoursePage.Kind.RESULT,
                "The mobile report has a result title");
        check(screen(label(0, "答题卡"), label(2, "1"), label(3, "2"), label(4, "提交")).kind == CoursePage.Kind.ANSWER_CARD,
                "A mobile answer card is not confirmation of submission");
        check(screen(label(0, "正在加载…"), label(0, "Loading")).loading,
                "An interim loading screen is not a ready exercise");
        check(screen(label(0, "Write an essay about your campus."), field(1), label(9, "提交"))
                .kind == CoursePage.Kind.WRITING, "Identify text writing separately");
        check(screen(label(0, "选词填空"), field(1), label(9, "提交")).kind == CoursePage.Kind.CLOZE,
                "Word-bank controls are not treated as ordinary text fields");
        check(screen(label(0, "将句子重新排序"), label(9, "提交")).kind == CoursePage.Kind.ORDERING,
                "Recognize ordering without guessing a gesture");
        CoursePage speech = screen(label(0, "朗读以下段落"), label(0, "This is the assigned passage."), label(5, "上传录音"));
        check(speech.kind == CoursePage.Kind.READ_ALOUD && speech.uploadAudio.size() == 1,
                "Observe the audio import route independently of the recording route");
        check(screen(label(0, "考试须知"), label(2, "期末考试 未完成")).kind == CoursePage.Kind.BLOCKED,
                "Stop on an explicit exam page");
        System.out.println("Passed 18 course monitor checks");
    }
}
