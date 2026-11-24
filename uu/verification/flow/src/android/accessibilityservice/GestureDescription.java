package android.accessibilityservice;
import android.graphics.Path;
public class GestureDescription {
    public StrokeDescription stroke;
    public static class Builder {
        private final GestureDescription gesture = new GestureDescription();
        public Builder addStroke(StrokeDescription stroke) { gesture.stroke = stroke; return this; }
        public GestureDescription build() { return gesture; }
    }
    public static class StrokeDescription {
        public final Path path;
        public final boolean willContinue;
        public StrokeDescription previous;
        public StrokeDescription(Path path, long startTime, long duration, boolean willContinue) {
            this.path = path;
            this.willContinue = willContinue;
        }
        public StrokeDescription continueStroke(Path path, long startTime, long duration, boolean willContinue) {
            if (!this.willContinue || path.startX != this.path.endX || path.startY != this.path.endY) {
                throw new AssertionError("Broken touch continuation");
            }
            StrokeDescription next = new StrokeDescription(path, startTime, duration, willContinue);
            next.previous = this;
            return next;
        }
    }
}
