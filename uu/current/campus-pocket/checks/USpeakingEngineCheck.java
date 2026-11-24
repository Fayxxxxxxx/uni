package dev.campuspocket.app;

import java.util.Arrays;
import java.util.List;

final class USpeakingEngineCheck {
    private static final USpeakingEngine ENGINE = new USpeakingEngine();

    private static PageModel.Line line(String value) {
        return line(value, false, false, true);
    }

    private static PageModel.Line line(String value, boolean click, boolean active, boolean enabled) {
        return new PageModel.Line(click ? value.hashCode() : 0, value, click, false, false, active, false,
                enabled, new PageModel.Bounds(10, 10, 180, 80));
    }

    private static USpeakingEngine.Snapshot screen(boolean within, String toast, PageModel.Line... values) {
        List<PageModel.Line> lines = Arrays.asList(values);
        return ENGINE.read(CoursePage.read(PageModel.read(lines, false), lines), lines, within, toast);
    }

    private static void check(boolean success, String notice) {
        if (!success) throw new AssertionError(notice);
    }

    public static void main(String[] args) {
        USpeakingEngine.Snapshot home = screen(false, "", line("首页"), line("U听说", true, false, true));
        check(home.screen == USpeakingEngine.Screen.HOME && home.scope == USpeakingEngine.Scope.ENTRY,
                "Show the U听说 entry on the U校园 home");
        check(screen(false, "", line("首页"), line("U听说")).screen == USpeakingEngine.Screen.MODULE_HOME,
                "The non-clickable U听说 title identifies the module even with persistent navigation");
        check(screen(true, "", line("教材列表")).screen == USpeakingEngine.Screen.COURSES,
                "Distinguish the textbook list");
        USpeakingEngine.Snapshot task = screen(true, "", line("任务列表"), line("Task 1 待完成", true, false, true));
        check(task.screen == USpeakingEngine.Screen.TASK_LIST && task.pending == 1, "Find visible pending tasks");

        USpeakingEngine.Snapshot active = screen(false, "", line("范例学习"), line("Step 1"),
                line("Step 2", false, true, true), line("Step 3"), line("跟读以下句子。"),
                line("录音中，00:03", true, false, true), line("回放", false, false, false),
                line("剩余时间 00:16"));
        check(active.screen == USpeakingEngine.Screen.EXAMPLE && active.recording == USpeakingEngine.Media.RECORDING,
                "Recognize the sample screen and active recording separately");
        check(active.step == 2 && active.recordingTime == 3 && active.remaining == 16, "Extract observed step and timers");
        check(active.disabledControls.contains("回放") && active.controls.contains("录音中"),
                "Record disabled playback and actionable stop separately");
        check(screen(false, "", line("范例学习"), line("Step 1"), line("Step 2"), line("Step 3")).step == -1,
                "Multiple visible step labels are not evidence of selection");

        USpeakingEngine.Snapshot play = screen(true, "", line("听力理解"), line("播放中"), line("00:04 / 00:12"));
        check(play.screen == USpeakingEngine.Screen.LISTENING && play.playback == USpeakingEngine.Media.PLAYING
                && play.elapsed == 4 && play.duration == 12, "Track listening progress");
        check(screen(true, "", line("听力理解"), line("播放结束")).playback == USpeakingEngine.Media.ENDED,
                "Recognize playback completion");
        check(screen(true, "", line("正在上传录音")).screen == USpeakingEngine.Screen.UPLOADING,
                "Recognize the upload stage");
        check(screen(true, "上传录音失败", line("范例学习")).issue == USpeakingEngine.Issue.UPLOAD,
                "Retain a target-app upload error toast");
        check(screen(true, "", line("请开启麦克风权限")).issue == USpeakingEngine.Issue.PERMISSION,
                "Recognize a microphone permission error");
        check(screen(true, "网络连接异常", line("听力理解")).issue == USpeakingEngine.Issue.NETWORK,
                "Recognize a network failure");
        check(screen(true, "", line("录音已超时")).issue == USpeakingEngine.Issue.TIMEOUT,
                "Recognize a recording deadline");
        USpeakingEngine.Snapshot scored = screen(true, "", line("口语得分：91.5"), line("提交成功"));
        check(scored.screen == USpeakingEngine.Screen.RESULT && scored.completed && scored.score.equals("91.5"),
                "Record a numeric score and completion evidence");
        check(screen(true, "", line("角色扮演"), line("录音", true, false, true)).screen == USpeakingEngine.Screen.ROLE_PLAY,
                "Classify role-play before generic speech");
        check(screen(true, "", line("情景对话")).screen == USpeakingEngine.Screen.DIALOGUE, "Recognize dialogue");
        check(screen(true, "", line("请回答以下问题并录音。"), line("How do you prepare for a lecture?"),
                line("录音", true, false, true)).screen == USpeakingEngine.Screen.SPEAKING,
                "Recognize an open speaking prompt");
        check(screen(false, "", line("口语得分：91")).screen == USpeakingEngine.Screen.UNKNOWN,
                "Do not assume unrelated U校园 coursework belongs to the module");
        check(screen(true, "", line("请输入密码")).protectedPage, "Do not sample login pages");
        check(USpeakingEngine.background().screen == USpeakingEngine.Screen.BACKGROUND, "Expose foreground loss");
        USpeakingReadout.Progress progress = new USpeakingReadout.Progress();
        check(progress.observe("sample", 1000, 0, 72, true) == USpeakingEngine.Media.READY,
                "A clock at zero is ready");
        check(progress.observe("sample", 2000, 1, 72, true) == USpeakingEngine.Media.PLAYING,
                "A clock advancing at playback speed indicates playback");
        progress.observe("sample", 3000, 1, 72, true);
        check(progress.observe("sample", 4800, 1, 72, true) == USpeakingEngine.Media.STALLED,
                "An unchanged clock does not prove playback or completion");
        check(progress.observe("sample", 4900, 41, 72, true) == USpeakingEngine.Media.UNKNOWN,
                "Seeking alone does not prove playback");
        check(progress.observe("sample", 6000, 72, 72, true) == USpeakingEngine.Media.ENDED,
                "The full duration indicates the end");
        progress.reset();
        check(progress.observe("sample", 7000, 41, 72, true) == USpeakingEngine.Media.UNKNOWN,
                "Returning to the app starts a new clock observation");
        System.out.println("Passed 28 U听说 observer checks");
    }
}
