package dev.campuspocket.app;

/**
 * A missing accessibility label is not a microphone failure. Android can report
 * that a continuous touch segment completed even when a WebView does not expose
 * its recording state. This gate keeps that distinction explicit.
 *
 * Neither a countdown nor the planned audio duration is an input here.
 */
final class ChatRecordingGate {
    enum Decision { WAIT, UI_CONFIRMED, TOUCH_ESTABLISHED }
    static final long UI_GRACE_MS = 1600;

    private ChatRecordingGate() {}

    static Decision check(TrainingPage page, TrainingHold hold, long touchAt, long now) {
        if (page == null || page.kind != TrainingPage.Kind.CHAT || hold == null || !hold.isHolding()
                || page.state.issue != USpeakingEngine.Issue.NONE || page.state.protectedPage) return Decision.WAIT;
        if (page.chatHolding()) return Decision.UI_CONFIRMED;
        if (touchAt >= 0 && hold.contactEstablished() && now - touchAt >= UI_GRACE_MS
                && !page.state.loading && page.state.turns >= 0) return Decision.TOUCH_ESTABLISHED;
        return Decision.WAIT;
    }
}
