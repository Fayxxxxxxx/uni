package dev.campuspocket.app;

import android.os.Handler;
import android.os.SystemClock;

import java.lang.reflect.Field;
import java.util.ArrayList;

import static dev.campuspocket.app.DialogueSpeedRegression.*;

public final class TaskCardRecoveryRegression {
    public static void main(String[] args) throws Exception {
        taskCardWithoutNodeUsesOneConstrainedFallback();
        accessibleTaskCardUsesRealControlFirst();
        System.out.println("PASS: screenshot task card uses Next, inaccessible Next tries the screenshot-specific location once, no repeated blind taps");
    }

    private static CampusService.Capture card(boolean accessibleNext) {
        var lines = new ArrayList<PageModel.Line>();
        lines.add(line(1, "阅读任务卡", 116, 488, 650, 562));
        lines.add(line(2, "You've just listened to an audio about using AI to learn digital drawing.", 110, 575, 709, 725));
        lines.add(line(3, "Share your experience with AI (acting as your friend).", 110, 729, 709, 908));
        if (accessibleNext) lines.add(line(4, "下一步", 238, 1398, 596, 1455));
        return new CampusService.Capture(lines);
    }

    private static USpeakingTraining start(CampusService service) throws Exception {
        var run = new USpeakingTraining(service, new Handler(), new USpeakingMonitor(service), new Store(), "cn.unipus.cloud");
        Field chapter = run.getClass().getDeclaredField("chapter");
        chapter.setAccessible(true);
        chapter.setInt(run, 1);
        SystemClock.clock = 10_000;
        run.start();
        return run;
    }

    private static void taskCardWithoutNodeUsesOneConstrainedFallback() throws Exception {
        var service = new CampusService();
        service.current = card(false);
        var run = start(service);
        check(new TrainingPage(service.current.coursePage(), service.current.lines).taskCard, "Screenshot task card not recognized");
        tick(run, 10_000);
        check(service.taskNextFallbacks == 0, "Fallback must allow normal discovery first");
        tick(run, 11_900);
        check(service.taskNextFallbacks == 1 && phase(run).equals("NAVIGATING"),
                "Missing accessibility Next control should trigger the one-time safe task-card tap");
        tick(run, 14_500);
        tick(run, 17_000);
        check(service.taskNextFallbacks == 1, "Should not repeatedly tap a guessed screen location");
        service.current = chat(0, "Where should I begin learning to draw?", "按住说话");
        tick(run, 17_200);
        check(phase(run).equals("ROUTE"), "Program did not resume after task card disappeared");
    }

    private static void accessibleTaskCardUsesRealControlFirst() throws Exception {
        var service = new CampusService();
        service.current = card(true);
        var run = start(service);
        tick(run, 10_000);
        check(service.cardTaps == 1 && service.taskNextFallbacks == 0 && phase(run).equals("NAVIGATING"),
                "Accessible Next button should take precedence over geometric fallback");
    }
}
