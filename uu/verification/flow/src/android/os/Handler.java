package android.os;
public class Handler {
    public boolean post(Runnable task) { task.run(); return true; }
    public boolean postDelayed(Runnable task, long delay) { return true; }
    public void removeCallbacks(Runnable task) {}
}
