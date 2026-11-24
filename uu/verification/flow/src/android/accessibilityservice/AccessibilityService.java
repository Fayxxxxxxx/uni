package android.accessibilityservice;
public class AccessibilityService extends android.content.Context {
    public abstract static class GestureResultCallback {
        public void onCompleted(GestureDescription gesture) {}
        public void onCancelled(GestureDescription gesture) {}
    }
}
