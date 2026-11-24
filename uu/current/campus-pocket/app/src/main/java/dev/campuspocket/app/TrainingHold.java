package dev.campuspocket.app;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.os.Handler;

final class TrainingHold {
    private final CampusService service;
    private final Handler ui;
    private final int window, x, y, cancelY;
    private final Runnable failed;
    private GestureDescription.StrokeDescription stroke;
    private boolean sendRequested, cancelRequested, finished, sentSuccessfully;
    private int completedSegments;

    TrainingHold(CampusService service, Handler ui, int window, PageModel.Bounds bounds, int x, Runnable failed) {
        this.service = service;
        this.ui = ui;
        this.window = window;
        this.failed = failed;
        this.x = x;
        y = bounds.y();
        cancelY = Math.max(0, y - Math.max(2 * (bounds.bottom - bounds.top), (bounds.right - bounds.left) / 4));
    }

    boolean start() {
        if (dispatch(false, false)) return true;
        finished = true;
        return false;
    }

    boolean releaseToSend() {
        if (!isHolding()) return false;
        sendRequested = true;
        return true;
    }

    void cancel() {
        if (isHolding()) cancelRequested = true;
    }

    boolean isHolding() {
        return stroke != null && !finished && !sendRequested && !cancelRequested;
    }

    boolean finished() { return finished; }

    // A successfully dispatched gesture is not yet evidence of a sustained press.
    // The first completed continuation segment confirms that Android maintained it.
    boolean contactEstablished() { return isHolding() && completedSegments > 0; }

    // A release callback can be cancelled even if releaseToSend() returned true.
    boolean sendAcknowledged() { return finished && sentSuccessfully; }
    boolean sendRequested() { return sendRequested; }

    private boolean dispatch(boolean release, boolean swipeAway) {
        // An in-place release only ends the existing touch. It does not need a fresh page snapshot.
        if ((stroke == null || !release || swipeAway) && !service.trainingWindowActive(window)) return false;
        Path path = new Path();
        path.moveTo(x, y);
        if (swipeAway) path.lineTo(x, cancelY);
        stroke = stroke == null ? new GestureDescription.StrokeDescription(path, 0, 720, true)
                : stroke.continueStroke(path, 0, release ? 160 : 550, !release);
        return service.dispatchGesture(new GestureDescription.Builder().addStroke(stroke).build(),
                new AccessibilityService.GestureResultCallback() {
                    @Override public void onCompleted(GestureDescription gesture) {
                        if (release) {
                            finished = true;
                            sentSuccessfully = !swipeAway;
                        } else {
                            completedSegments++;
                            if (!dispatch(sendRequested || cancelRequested, cancelRequested)) {
                                finished = true;
                                if (!cancelRequested) failed.run();
                            }
                        }
                    }
                    @Override public void onCancelled(GestureDescription gesture) {
                        finished = true;
                        if (!cancelRequested) failed.run();
                    }
                }, ui);
    }
}
