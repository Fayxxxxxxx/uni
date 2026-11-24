package dev.campuspocket.app;

import android.os.Handler;
import java.util.ArrayList;
import static dev.campuspocket.app.DialogueSpeedRegression.*;

public final class NaturalReadingRegression {
    public static void main(String[] args) throws Exception {
        verifyFullReadingAndOfficialTimeout();
        verifyCompletedReportRetest();
        System.out.println("PASS: long audio starts without budget gate; target official end terminates playback; low score report retest");
    }

    private static void verifyFullReadingAndOfficialTimeout() throws Exception {
        var service = new CampusService();
        var monitor = new USpeakingMonitor(service);
        var run = new USpeakingTraining(service, new Handler(), monitor, new Store(), "cn.unipus.cloud");
        service.current = cloze("录音限时 20 秒", "点击录音");
        check(new TrainingPage(service.current.coursePage(), service.current.lines).speechLimit == 20, "Official time limit lost");
        CampusService.Capture misleading = cloze("录音时长：3秒", "点击录音");
        check(new TrainingPage(misleading.coursePage(), misleading.lines).speechLimit == 0, "Elapsed duration was misread as a limit");
        tick(run, 10000);
        ModelClient.QUEUE.deliver();
        check(phase(run).equals("PREPARING_AUDIO"), "Missing Step 3 speech");
        run.ready(38000);
        check(phase(run).equals("AUDIO_READY"), "Old too-long error gated this verbatim recording");
        tick(run, 10500);
        check(service.micOpens == 1 && phase(run).equals("WAIT_RECORD"), "Did not start the recording");
        service.current = cloze("录音限时 20 秒", "正在录制…", "停止录音", "剩余时间：16秒");
        tick(run, 11000);
        var audio = (TrainingAudio) field(run, "audio");
        check(phase(run).equals("SPEAKING") && audio.played && audio.budget == null,
                "Long Step 3 audio should play without any elapsed-time budget");
        service.current = cloze("录音已超时", "下一题");
        tick(run, 200000);
        check(phase(run).equals("WAIT_RESULT") && !audio.stoppedAtLimit && (boolean) field(run, "platformTimedOut")
                        && audio.stops > 0,
                "An observed target recording end must stop audio; predicted time must not block its start");
        tick(run, 200010);
        check(phase(run).equals("NAVIGATING") && field(run, "navLabel").equals("下一题") && service.micOpens == 1,
                "An already-ended timed-out recording should progress without another microphone open");

        var uploadTimeout = cloze("操作超时");
        check(!new TrainingPage(uploadTimeout.coursePage(), uploadTimeout.lines).state.recordingTimedOut(),
                "A general operation timeout is not a recording timeout");
    }

    private static void verifyCompletedReportRetest() throws Exception {
        var service = new CampusService();
        var monitor = new USpeakingMonitor(service);
        var run = new USpeakingTraining(service, new Handler(), monitor, new Store(), "cn.unipus.cloud");
        service.current = page("范例学习 已完成 查看报告", "AI口语对话 待练习", "自由表达 未解锁");
        tick(run, 30000);
        check(phase(run).equals("NAVIGATING") && service.cardTaps == 1, "Completed module was skipped without checking its report");
        service.current = page("范例学习报告", "综合得分 84 分", "重新测评", "继续下一节");
        tick(run, 30500);
        tick(run, 30600);
        check(phase(run).equals("NAVIGATING") && service.cardClicks == 1
                && ((boolean[]) field(run, "reportRetested"))[0], "A past grade below 90 did not start a retest");
        service.current = cloze("点击录音");
        tick(run, 31300);
        check(phase(run).equals("ROUTE"), "Did not enter the retest task");
        service.current = page("范例学习报告", "综合得分 86 分", "重新测评", "继续下一节");
        tick(run, 32000);
        check(service.cardClicks == 2 && field(run, "navLabel").equals("继续下一节"),
                "Second low score incorrectly triggered another retest");
    }

    private static CampusService.Capture cloze(String... controls) {
        var labels = new ArrayList<String>();
        labels.add("范例学习");
        labels.add("Step 3");
        labels.add("第1/1题");
        labels.add("从下列表达中选择合适表达填空，朗读完整句子并录音");
        labels.add("write the report or close the window");
        labels.add("Please ___ now.");
        java.util.Collections.addAll(labels, controls);
        return page(labels.toArray(new String[0]));
    }

    private static CampusService.Capture page(String... labels) {
        var lines = new ArrayList<PageModel.Line>();
        for (int i = 0; i < labels.length; i++) {
            lines.add(new PageModel.Line(i + 1, labels[i], true, false, false, false, false, true,
                    new PageModel.Bounds(80, i * 95 + 20, 700, i * 95 + 70)));
        }
        return new CampusService.Capture(lines);
    }
}
