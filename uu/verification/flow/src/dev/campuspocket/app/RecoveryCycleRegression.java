package dev.campuspocket.app;

import android.net.ConnectivityManager;
import android.os.Handler;
import java.util.ArrayList;
import java.util.Set;
import static dev.campuspocket.app.DialogueSpeedRegression.*;

public final class RecoveryCycleRegression {
    static final String FIRST = "How do you usually seek academic help in college?";
    static final String SECOND = "A quick question after class, an email, or office hours?";
    static final String THIRD = "For me, nothing beats office hours.";

    public static void main(String[] args) throws Exception {
        cardDoesNotExpandOnFirstTap();
        cardIsDeferredAndOtherCardContinues();
        networkReturnsDuringSameRound();
        System.out.println("PASS: card reacquired after collapse, unreachable card deferred without claiming completion, other card continues, disconnected model resumes without another start");
    }

    private static void cardDoesNotExpandOnFirstTap() throws Exception {
        var service = new CampusService();
        service.current = following(1);
        var run = new USpeakingTraining(service, new Handler(), new USpeakingMonitor(service), new Store(), "cn.unipus.cloud");
        finished(run).add(FIRST);
        tick(run, 10000);
        check(phase(run).equals("OPENING_CARD") && service.cardClicks == 1, "Expected first expansion");
        service.current = following(0);
        tick(run, 10800);
        check(phase(run).equals("OPENING_CARD") && service.cardTaps == 1, "Retry failed after old card collapsed");
        service.current = following(2);
        tick(run, 10900);
        check(phase(run).equals("ROUTE"), "Reopened target was not accepted");
        tick(run, 11000);
        check(((TrainingAudio) field(run, "audio")).text.equals(SECOND), "Wrong card prepared after recovery");
    }

    private static void cardIsDeferredAndOtherCardContinues() throws Exception {
        var service = new CampusService();
        service.current = following(0);
        var run = new USpeakingTraining(service, new Handler(), new USpeakingMonitor(service), new Store(), "cn.unipus.cloud");
        finished(run).add(FIRST);
        tick(run, 20000);
        tick(run, 20800);
        tick(run, 21600);
        tick(run, 22400);
        check(((Set<?>) field(run, "deferredCards")).contains(SECOND) && !finished(run).contains(SECOND),
                "Inaccessible card was marked complete or stopped the flow");
        tick(run, 22600);
        check(field(run, "cardTarget").equals(THIRD), "Did not move on to the next card");
        service.current = following(3);
        tick(run, 22700);
        tick(run, 22800);
        check(((TrainingAudio) field(run, "audio")).text.equals(THIRD), "Next card was not prepared");
    }

    private static void networkReturnsDuringSameRound() throws Exception {
        var service = new CampusService();
        var run = new USpeakingTraining(service, new Handler(), new USpeakingMonitor(service), new Store(), "cn.unipus.cloud");
        service.current = chat(0, "Where do you think our class should go for the school trip?", "按住说话");
        ConnectivityManager.available = false;
        tick(run, 30000);
        tick(run, 30350);
        check(phase(run).equals("WAIT_NETWORK"), "Offline request did not pause at the current question");
        ConnectivityManager.available = true;
        tick(run, 31350);
        check(phase(run).equals("GENERATING_REPLY"), "Run did not resume automatically");
        ModelClient.QUEUE.deliver();
        check(phase(run).equals("PREPARING_AUDIO"), "Resumed answer did not progress to speech");
    }

    @SuppressWarnings("unchecked")
    private static Set<String> finished(USpeakingTraining run) throws Exception {
        return (Set<String>) field(run, "finishedCards");
    }

    private static CampusService.Capture following(int activeRow) {
        var lines = new ArrayList<PageModel.Line>();
        lines.add(line(1, "范例学习", 15));
        lines.add(line(7, "Step 2", 45));
        lines.add(line(2, "跟读以下句子。", 65));
        lines.add(line(3, FIRST, 125));
        lines.add(line(4, SECOND, 285));
        lines.add(line(5, THIRD, 445));
        if (activeRow == 1) lines.add(line(6, "录音", 210));
        if (activeRow == 2) lines.add(line(6, "录音", 370));
        if (activeRow == 3) lines.add(line(6, "录音", 540));
        return new CampusService.Capture(lines);
    }

    private static PageModel.Line line(int id, String text, int y) {
        return new PageModel.Line(id, text, true, false, false, false, false, true,
                new PageModel.Bounds(80, y, 520, y + 30));
    }
}
