package dev.campuspocket.app;

import android.os.Handler;
import android.os.SystemClock;

import static dev.campuspocket.app.DialogueSpeedRegression.*;

/** Replays the actual failure: WebView keeps the label "按住说话" after Android accepts a hold. */
public final class ChatRecordingRecoveryRegression {
    public static void main(String[] args) throws Exception {
        mutedUiStateDoesNotDeadlockPlayback();
        pendingGestureNeverProvokesBlindRepress();
        failedReleaseRetriesAtMostOneTime();
        targetTurnAdvancedStopsPlaybackImmediately();
        visibleRecordingEndStopsPlaybackImmediately();
        dialogueHoldingAliasesAreRecognized();
        System.out.println("PASS: missing record label, sustained hold, no repeated press, cancelled release, target turn/end stops playback, label variants");
    }

    private static USpeakingTraining prepareChat(CampusService service, long time) throws Exception {
        service.current = chat(0, FIRST, "按住说话");
        var run = new USpeakingTraining(service, new Handler(), new USpeakingMonitor(service), new Store(), "cn.unipus.cloud");
        tick(run, time);
        tick(run, time + 350);
        ModelClient.QUEUE.deliver();
        check(phase(run).equals("PREPARING_AUDIO"), "AI response not prepared");
        run.ready(3300);
        tick(run, time + 700);
        check(phase(run).equals("WAIT_RECORD") && service.dispatches == 1, "First hold not initiated");
        return run;
    }

    private static void mutedUiStateDoesNotDeadlockPlayback() throws Exception {
        var service = new CampusService();
        var run = prepareChat(service, 10_000);
        var audio = (TrainingAudio) field(run, "audio");
        tick(run, 11_200);
        check(service.dispatches == 1 && !audio.played, "Hold must not be repeated or played before Android acknowledges it");
        service.complete(); // Android accepted the first continuation, but the app text never changes.
        tick(run, 11_600);
        check(phase(run).equals("WAIT_RECORD") && !audio.played, "Should allow UI a brief grace interval");
        tick(run, 12_400);
        check(phase(run).equals("SPEAKING") && audio.played,
                "A sustained touch must allow playback without requiring an inaccessible label");
        check(service.dispatches == 2 && (boolean) field(run, "chatSoftStarted"),
                "Missing label must not cancel the established touch");
        run.done();
        service.complete(); // Continuation becomes in-place release.
        service.complete(); // Release acknowledgment.
        service.current = chat(1, "Thanks for sharing; here's my next question.", "按住说话");
        tick(run, 13_100);
        check(phase(run).equals("ROUTE"), "Next turn should be accepted without another confirmation");
    }

    private static void pendingGestureNeverProvokesBlindRepress() throws Exception {
        var service = new CampusService();
        var run = prepareChat(service, 20_000);
        var audio = (TrainingAudio) field(run, "audio");
        tick(run, 23_000);
        tick(run, 25_500);
        check(phase(run).equals("WAIT_RECORD") && service.dispatches == 1 && !audio.played,
                "A gesture never accepted by Android should not cause repeated long presses or fake playback");
        tick(run, 33_300);
        check(!(boolean) field(run, "active") && !audio.played,
                "Unacknowledged gesture must end with a bounded diagnostic, not loop forever");
    }

    private static void failedReleaseRetriesAtMostOneTime() throws Exception {
        var service = new CampusService();
        var run = prepareChat(service, 40_000);
        service.complete();
        tick(run, 42_400);
        check(phase(run).equals("SPEAKING"), "Expected to start in soft recording mode");
        run.done();
        service.complete();
        service.cancel(); // Android cancelled the release rather than sending the turn.
        tick(run, 43_200);
        check(phase(run).equals("PREPARING_AUDIO") && (boolean) field(run, "chatSendRetried"),
                "Release cancellation should retry the same prepared utterance once");
        run.ready(3300);
        tick(run, 43_500);
        service.complete();
        tick(run, 45_400);
        check(phase(run).equals("SPEAKING"), "Retry should reopen the hold and resume audio");
        run.done();
        service.complete();
        service.complete();
        tick(run, 62_400);
        check(phase(run).equals("WAIT_RESULT") && (boolean) field(run, "chatSendRetried"),
                "A second stalled send must not start an infinite replay cycle");
    }

    private static void targetTurnAdvancedStopsPlaybackImmediately() throws Exception {
        var service = new CampusService();
        var run = prepareChat(service, 70_000);
        var audio = (TrainingAudio) field(run, "audio");
        service.complete();
        tick(run, 72_400);
        check(phase(run).equals("SPEAKING") && audio.played, "Expected active playback before new turn");
        int stopsBefore = audio.stops;
        service.current = chat(1, "Now tell me about your next hobby.", "按住说话");
        tick(run, 72_600);
        check(audio.stops > stopsBefore && phase(run).equals("WAIT_RESULT"),
                "New U-campus turn must stop local speech immediately");
        check(((TrainingHold) field(run, "hold")).sendRequested(),
                "Target advance must release in-place rather than leave a held pointer");
        service.complete();
        service.complete();
        tick(run, 73_400);
        check(phase(run).equals("ROUTE"), "New turn should settle once release acknowledges");
    }

    private static void visibleRecordingEndStopsPlaybackImmediately() throws Exception {
        var service = new CampusService();
        var run = prepareChat(service, 80_000);
        var audio = (TrainingAudio) field(run, "audio");
        service.complete();
        tick(run, 82_400);
        check(phase(run).equals("SPEAKING"), "Expected playback before official recording end");
        int before = audio.stops;
        service.current = new CampusService.Capture(java.util.Arrays.asList(
                line(1, "AI口语对话", 111, 142, 340, 203),
                line(2, "话轮数：0/10", 593, 333, 782, 379),
                line(3, "Now tell me your favorite hobby.", 160, 563, 807, 856),
                line(4, "录音时间已到", 300, 975, 660, 1050),
                line(5, "按住说话", 41, 1787, 904, 1928)));
        tick(run, 82_700);
        check(audio.stops > before && phase(run).equals("WAIT_RESULT"),
                "Official target recording end must stop local playback without checking planned duration");
    }

    private static void dialogueHoldingAliasesAreRecognized() {
        for (String label : new String[]{"松开即可发送", "松开结束", "松开发送，上滑取消", "正在录音"}) {
            TrainingPage page = new TrainingPage(chat(0, FIRST, label).coursePage(), chat(0, FIRST, label).lines);
            check(page.kind == TrainingPage.Kind.CHAT && page.chatHolding(), "Recording variant was lost: " + label);
        }
    }
}
