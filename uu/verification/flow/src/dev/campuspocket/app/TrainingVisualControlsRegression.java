package dev.campuspocket.app;

/** The right margin is occluded by the helper's own floating action bubble. */
public final class TrainingVisualControlsRegression {
    private static final int W = 945, H = 2048;
    private static final int SHADE = 0xff565867, WHITE = 0xffffffff, BLUE = 0xff447bfd;

    public static void main(String[] args) {
        TrainingMarkers.Pixels actualLike = (x,y) -> {
            if (x >= 896 && y >= 1500 && y <= 1640) return WHITE; // Floating bubble obscures right backdrop.
            if (x >= 274 && x <= 672 && y >= 1544 && y <= 1652) return BLUE;
            if (x >= 72 && x <= 875 && y >= 475 && y <= 1740) return WHITE;
            return SHADE;
        };
        PageModel.Bounds box = TrainingVisualControls.taskCardNext(actualLike, W, H);
        if (box == null || Math.abs(box.y() / (float) H - .78) > .02)
            throw new AssertionError("Did not recognize blue Next under a floating overlay");
        TrainingMarkers.Pixels noBlue = (x,y) -> x > 72 && x < 875 ? WHITE : SHADE;
        if (TrainingVisualControls.taskCardNext(noBlue, W, H) != null)
            throw new AssertionError("Guessed a Next button when none was present");
        TrainingMarkers.Pixels tutorial = (x,y) -> {
            if (x >= 160 && x <= 380 && y >= 1525 && y <= 1610) {
                if (y >= 1540 && y <= 1590 && x >= 178 && x <= 360) return BLUE;
                return 0xff9debd9; // Cyan mint ring around the OK button.
            }
            return SHADE;
        };
        PageModel.Bounds ok = TrainingVisualControls.tutorialOkay(tutorial, W, H);
        if (ok == null || Math.abs(ok.y() / (float) H - .765) > .015)
            throw new AssertionError("Tutorial OK should be located, not mistaken for Next");
        if (TrainingVisualControls.tutorialOkay(actualLike, W, H) != null)
            throw new AssertionError("Task-card Next is not the smaller tutorial OK overlay");
        System.out.println("PASS: task-card Next survives floating overlay, tutorial OK distinguished, blank page rejected");
    }
}
