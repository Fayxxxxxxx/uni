package android.net;
public final class ConnectivityManager {
    public static boolean available = true;
    public Network getActiveNetwork() { return available ? new Network() : null; }
}
