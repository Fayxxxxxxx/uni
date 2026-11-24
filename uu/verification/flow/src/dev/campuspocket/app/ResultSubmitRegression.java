package dev.campuspocket.app;

import android.os.Handler;
import android.os.SystemClock;

import java.util.Arrays;

import static dev.campuspocket.app.DialogueSpeedRegression.*;

/** Screenshot scenario: final Step 3 question, AI score 87, and a blue 提交 button. */
public final class ResultSubmitRegression {
    private static PageModel.Line line(int id, String label, int x, int y, int w, int h, int parent, boolean checked) {
        return new PageModel.Line(id, label, id != 0, false, false, checked, false, true,
                new PageModel.Bounds(x, y, x + w, y + h), parent);
    }

    public static void main(String[] args) throws Exception {
        var service = new CampusService();
        service.current = new CampusService.Capture(Arrays.asList(
                line(0, "范例学习", 300, 100, 240, 55, 1, false),
                line(0, "Step 3", 560, 220, 150, 45, 1, true),
                line(0, "第 3 / 3 题", 55, 900, 170, 55, 9, false),
                line(0, "87分", 341, 1350, 130, 65, 10, false),
                line(0, "AI评分", 345, 1440, 120, 60, 11, false),
                line(5, "提交", 38, 1587, 758, 96, 13, false)
        ));
        TrainingPage page = new TrainingPage(service.current.coursePage(), service.current.lines);
        check(page.kind == TrainingPage.Kind.STEP_3 && page.state.question == 3 && page.state.questions == 3,
                "Final Step 3 result was misclassified");
        check(page.scored() && page.state.score.equals("87") && page.submitExample() != null,
                "AI score badge and Submit button must be detected across different view parents");
        var run = new USpeakingTraining(service, new Handler(), new USpeakingMonitor(service), new Store(), "cn.unipus.cloud");
        SystemClock.clock = 10000;
        run.start();
        tick(run, 10000);
        check(phase(run).equals("NAVIGATING") && service.cardClicks == 1,
                "Missing retake must submit the completed final question, not time out waiting for mic");
        System.out.println("PASS: Step 3 87-point score badge recognized, final Submit clicked with no retake control");
    }
}
