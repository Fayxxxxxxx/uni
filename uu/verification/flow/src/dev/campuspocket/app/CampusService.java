package dev.campuspocket.app;
import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.os.Handler;
import java.util.List;

class CampusService extends AccessibilityService {
    static final class Capture {
        final PageModel.Page page;
        final List<PageModel.Line> lines;
        final org.json.JSONArray tree = null;
        final String packageName = "cn.unipus.cloud";
        final int window = 12;
        Capture(List<PageModel.Line> lines) {
            this.lines = lines;
            page = PageModel.read(lines, false);
        }
        CoursePage coursePage() { return CoursePage.read(page, lines); }
    }
    boolean windowActive = true;
    GestureDescription gesture;
    GestureResultCallback callback;
    Capture current;
    int dispatches;
    int cardClicks, cardTaps;
    int micOpens, micStops, taskNextFallbacks, tutorialDismissals;
    void captureTrainingMarkers(Capture cap, TrainingPage page, java.util.function.Consumer<TrainingMarkers.Status[]> done) { done.accept(null); }
    boolean pressFollowMicrophone(Capture cap, PageModel.Line mic, boolean retry) { micOpens++; return true; }
    boolean trainingStop(Capture cap, TrainingPage page, boolean retry) { micStops++; return true; }
    PageModel.Bounds trainingCardViewport(Capture cap, SpeakingCards cards) { return new PageModel.Bounds(0, 40, 900, 800); }
    boolean trainingClick(Capture cap, PageModel.Line line) { cardClicks++; return true; }
    boolean trainingTap(Capture cap, PageModel.Bounds box) { cardTaps++; return true; }
    public <T> T getSystemService(Class<T> type) { return type == android.net.ConnectivityManager.class ? type.cast(new android.net.ConnectivityManager()) : null; }
    Capture capture() { return current; }
    Capture capture(boolean includeTree) { return current; }
    PageModel.Bounds trainingHoldBounds(Capture cap, PageModel.Line label) { return label == null ? null : label.bounds; }
    boolean trainingBack(Capture cap) { return false; }
    boolean trainingTaskCardNextFallback(Capture cap) { taskNextFallbacks++; return true; }
    boolean trainingTutorialOkayFallback(Capture cap) { tutorialDismissals++; return true; }
    void captureTrainingControl(Capture cap, boolean next, java.util.function.Consumer<PageModel.Bounds> result) { result.accept(null); }
    PageModel.Bounds tutorialBox;
    void captureTrainingTutorialOkay(Capture cap, java.util.function.Consumer<PageModel.Bounds> result) {
        result.accept(tutorialBox);
    }
    PageModel.Line adaptiveTrainingButton(Capture cap, TrainingPage page) { return page.action("开始训练", "继续训练"); }
    boolean swipeTrainingCards(Capture cap, PageModel.Bounds viewport, int distance, boolean retry, java.util.function.Consumer<Boolean> delivered) {
        delivered.accept(true);
        return true;
    }
    boolean trainingPoint(Capture cap, int x, int y) { return true; }
    boolean trainingWindowActive(int window) { return windowActive; }
    boolean dispatchGesture(GestureDescription gesture, GestureResultCallback callback, Handler ui) {
        this.gesture = gesture;
        this.callback = callback;
        dispatches++;
        return true;
    }
    void complete() { callback.onCompleted(gesture); }
    void cancel() { callback.onCancelled(gesture); }
}
