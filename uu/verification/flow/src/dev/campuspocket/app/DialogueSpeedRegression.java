package dev.campuspocket.app;

import android.os.Handler;
import android.os.SystemClock;
import java.lang.reflect.Field;
import java.util.Arrays;

public class DialogueSpeedRegression {
    static final String FIRST = "Hey, have you thought about where our class should go for the trip? I really want to hear your ideas on this.";

    static CampusService.Capture chat(int turn, String text, String button) {
        return new CampusService.Capture(Arrays.asList(line(1, "AI口语对话", 111, 142, 340, 203),
                line(2, "话轮数：" + turn + "/10", 593, 333, 782, 379),
                line(3, "6′", 172, 444, 335, 543),
                line(4, text, 160, 563, 807, 856),
                line(5, button, 41, 1787, 904, 1928)));
    }

    static PageModel.Line line(int id, String text, int left, int top, int right, int bottom) {
        return new PageModel.Line(id, text, true, false, false, false, false, true,
                new PageModel.Bounds(left, top, right, bottom));
    }

    static Object field(Object obj, String name) throws Exception {
        Field field = obj.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(obj);
    }

    static String phase(USpeakingTraining run) throws Exception { return field(run, "phase").toString(); }

    static void tick(USpeakingTraining run, long time) throws Exception {
        SystemClock.clock = time;
        var tick = run.getClass().getDeclaredMethod("tick");
        tick.setAccessible(true);
        tick.invoke(run);
    }

    static void check(boolean passed, String message) {
        if (!passed) throw new AssertionError(message);
    }
}
