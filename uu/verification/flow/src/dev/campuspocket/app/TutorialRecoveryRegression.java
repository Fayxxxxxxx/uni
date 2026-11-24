package dev.campuspocket.app;

import android.os.Handler;
import android.os.SystemClock;
import java.util.Arrays;

import static dev.campuspocket.app.DialogueSpeedRegression.*;

public final class TutorialRecoveryRegression {
    private static CampusService.Capture stepOne() {
        return new CampusService.Capture(Arrays.asList(line(1, "范例学习", 200, 120, 440, 175),
                line(2, "Step 1", 100, 230, 280, 285),
                line(3, "听音频，学习分享利用AI学习新爱好的经历。", 110, 320, 850, 370),
                line(4, "下一步", 765, 100, 900, 190)));
    }

    private static USpeakingTraining begin(CampusService service) {
        var run = new USpeakingTraining(service, new Handler(), new USpeakingMonitor(service), new Store(), "cn.unipus.cloud");
        SystemClock.clock = 10_000;
        run.start();
        return run;
    }

    public static void main(String[] args) throws Exception {
        var service = new CampusService();
        service.current = stepOne();
        service.tutorialBox = new PageModel.Bounds(170, 1520, 365, 1609);
        var run = begin(service);
        tick(run, 10_000);
        check(phase(run).equals("ROUTE") && service.cardTaps == 1 && service.cardClicks == 0,
                "Tutorial should be dismissed before attempting Step 1 Next");
        tick(run, 10_450);
        check(service.cardClicks == 0 && service.cardTaps == 1,
                "A short settle period should prevent tapping the obscured Next control");
        tick(run, 11_100);
        check(phase(run).equals("NAVIGATING") && service.cardClicks == 1,
                "After dismissing the overlay, the normal Step 1 Next control must execute");

        var another = new CampusService();
        another.current = stepOne();
        var next = begin(another);
        tick(next, 10_000);
        tick(next, 10_450);
        check(another.cardClicks == 1 && another.cardTaps == 0,
                "No overlay must not cause guessed taps");

        var semantic = new CampusService();
        semantic.current = new CampusService.Capture(Arrays.asList(
                line(1, "范例学习", 190, 120, 440, 180),
                line(2, "在屏幕中左右滑动可以切换题目", 180, 1100, 700, 1155)));
        var semanticRun = begin(semantic);
        tick(semanticRun, 10_000);
        tick(semanticRun, 10_450);
        check(phase(semanticRun).equals("ROUTE") && semantic.tutorialDismissals == 1,
                "Known tutorial text should use one tightly constrained fallback if OK is image-only");
        tick(semanticRun, 10_700);
        tick(semanticRun, 11_500);
        check(semantic.tutorialDismissals == 1, "Tutorial fallback must never be tapped in a loop");
        System.out.println("PASS: Step 1 tutorial auto-OK before Next, no-overlay detection, semantic text-only fallback once");
    }
}
