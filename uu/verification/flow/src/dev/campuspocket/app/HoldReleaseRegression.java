package dev.campuspocket.app;

import android.accessibilityservice.GestureDescription.StrokeDescription;
import android.os.Handler;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;

public class HoldReleaseRegression {
    private static final Handler UI = new Handler();
    private static final PageModel.Bounds BUTTON = new PageModel.Bounds(80, 850, 620, 947);

    public static void main(String[] args) throws Exception {
        var service = new CampusService();
        var hold = startHold(service);
        service.complete(); // Already traversed one continuation by the time audio finishes.
        StrokeDescription heldStroke = service.gesture.stroke;
        var run = new USpeakingTraining(service, UI, new USpeakingMonitor(service), new Store(), "cn.unipus.cloud");
        set(run, "itemKind", TrainingPage.Kind.CHAT);
        set(run, "itemTurn", 0);
        set(run, "itemWindow", 12);
        set(run, "deadline", 20_000L);
        set(run, "hold", hold);
        setPhase(run, "SPEAKING");
        service.current = chat(0, "按住说话"); // No recording indicator in the latest snapshot.
        tick(run);
        check(get(run, "phase").toString().equals("SPEAKING"), "Indicator flicker interrupted playback");
        service.windowActive = false; // The injected touch is still active but the next root check would fail.
        run.done();
        run.done(); // Ignore duplicate completion callback.
        check(get(run, "phase").toString().equals("WAIT_RESULT"), "Playback did not enter send confirmation");
        tick(run);
        check(get(run, "phase").toString().equals("WAIT_RESULT"), "Advanced on a stale turn");
        hold.cancel(); // A late stop signal cannot convert a queued send into a cancel swipe.
        service.complete();
        StrokeDescription release = service.gesture.stroke;
        check(release.previous == heldStroke && !release.willContinue, "Expected terminal continuation");
        check(release.path.startX == 350 && release.path.endX == 350
                && release.path.startY == BUTTON.y() && release.path.endY == BUTTON.y(), "Send must lift at its original point");
        service.complete();
        check(hold.finished() && service.dispatches == 3, "Expected exactly one release");
        service.current = chat(1, "按住说话");
        tick(run);
        check(get(run, "phase").toString().equals("ROUTE"), "Did not advance after new turn");

        var cancelService = new CampusService();
        var canceled = startHold(cancelService);
        canceled.cancel();
        check(!canceled.releaseToSend(), "An already canceled recording must not be sent");
        cancelService.complete();
        check(!cancelService.gesture.stroke.willContinue
                && cancelService.gesture.stroke.path.endY < BUTTON.y(), "Explicit cancellation should swipe away");

        var other = new USpeakingTraining(service, UI, new USpeakingMonitor(service), new Store(), "cn.unipus.cloud");
        set(other, "itemKind", TrainingPage.Kind.STEP_2);
        setPhase(other, "SPEAKING");
        other.done();
        check(get(other, "phase").toString().equals("WAIT_STOP"), "Changed Step 2 stop handling");
        System.out.println("PASS: indicator flicker, in-place release without a fresh window, one release per playback, wait for next turn, explicit cancel, Step 2");
    }

    private static CampusService.Capture chat(int turn, String control) {
        return new CampusService.Capture(Arrays.asList(line(1, "AI口语对话", 40), line(2, "话轮数：" + turn + "/10", 140),
                line(3, "What do you think we should do on our school trip?", 230), line(4, control, BUTTON.top)));
    }

    private static PageModel.Line line(int id, String text, int top) {
        return new PageModel.Line(id, text, true, false, false, false, false, true,
                new PageModel.Bounds(80, top, 620, top + 90));
    }

    private static TrainingHold startHold(CampusService service) {
        var hold = new TrainingHold(service, UI, 12, BUTTON, 350,
                () -> { throw new AssertionError("Unexpected gesture failure"); });
        check(hold.start(), "Unable to begin test hold");
        return hold;
    }

    private static void tick(USpeakingTraining run) throws Exception {
        Method method = run.getClass().getDeclaredMethod("tick");
        method.setAccessible(true);
        method.invoke(run);
    }

    private static Field field(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static void set(Object target, String name, Object value) throws Exception {
        field(target, name).set(target, value);
    }

    private static Object get(Object target, String name) throws Exception {
        return field(target, name).get(target);
    }

    private static void setPhase(USpeakingTraining run, String phase) throws Exception {
        Field field = field(run, "phase");
        for (Object value : field.getType().getEnumConstants()) {
            if (value.toString().equals(phase)) {
                field.set(run, value);
                return;
            }
        }
        throw new AssertionError("Missing phase " + phase);
    }

    private static void check(boolean passed, String details) {
        if (!passed) throw new AssertionError(details);
    }
}
