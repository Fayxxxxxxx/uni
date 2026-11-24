package android.os;
public class SystemClock {
    public static long clock = 10_000;
    public static long uptimeMillis() { return clock; }
}
