package dev.campuspocket.app;

import android.os.Handler;
import android.os.SystemClock;
import static dev.campuspocket.app.DialogueSpeedRegression.*;

public class AudioRecoveryRegression {
    private static final String PROMPT = "Hey, have you thought about where our class should go for the trip?";
    public static void main(String[] args) throws Exception {
        var service = new CampusService();
        var monitor = new USpeakingMonitor(service);
        var run = new USpeakingTraining(service, new Handler(), monitor, new Store(), "cn.unipus.cloud");
        var audio = (TrainingAudio) field(run, "audio");
        audio.personal = true;
        service.current = chat(0, PROMPT, "按住说话");
        tick(run, 10_000);
        tick(run, 10_350);
        ModelClient.QUEUE.deliver();
        SystemClock.clock = 11_100;
        run.ready(3500);
        tick(run, 16_250);
        service.current = chat(0, PROMPT, "松开发送，上滑取消");
        tick(run, 16_340);
        check(phase(run).equals("SPEAKING") && audio.played, "Initial recording and playback not started");
        run.error("Player reported an error");
        check(phase(run).equals("RECOVER_AUDIO") && !audio.usingPersonalVoice(), "System fallback not queued");
        tick(run, 16_500);
        check(phase(run).equals("RECOVER_AUDIO") && service.dispatches == 1, "Reopened mic before canceled touch ended");
        service.complete();
        check(!service.gesture.stroke.willContinue && service.gesture.stroke.path.endY < service.gesture.stroke.path.startY,
                "An incomplete recording must be canceled");
        service.complete();
        service.current = chat(0, PROMPT, "按住说话");
        tick(run, 16_800);
        check(phase(run).equals("PREPARING_AUDIO"), "Retry should reuse the existing text");
        run.ready(3200);
        tick(run, 17_200);
        service.current = chat(0, PROMPT, "松开发送，上滑取消");
        tick(run, 17_300);
        check(phase(run).equals("SPEAKING"), "Retry failed to start playback");
        run.error("Retry failed");
        check(monitor.status.equals("Retry failed") && !(boolean) field(run, "active"), "Failed retry should exit clearly");

        var secondService = new CampusService();
        var secondMonitor = new USpeakingMonitor(secondService);
        var secondRun = new USpeakingTraining(secondService, new Handler(), secondMonitor, new Store(), "cn.unipus.cloud");
        var secondAudio = (TrainingAudio) field(secondRun, "audio");
        secondAudio.personal = true;
        secondService.current = chat(0, PROMPT, "按住说话");
        tick(secondRun, 30_000);
        tick(secondRun, 30_350);
        ModelClient.QUEUE.deliver();
        secondRun.error("Voice API failed");
        tick(secondRun, 30_400);
        check(phase(secondRun).equals("PREPARING_AUDIO") && !secondAudio.usingPersonalVoice() && secondService.dispatches == 0,
                "Voice API fallback must occur without opening a mic");
        System.out.println("PASS: personal voice fallback, player error cancels before retry, same-turn retry, no duplicate send, one retry limit");
    }
}
