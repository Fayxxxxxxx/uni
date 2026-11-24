package android.content;
public class Context {
    public <T> T getSystemService(Class<T> type) { return null; }
    public java.io.File getCacheDir() { return new java.io.File(System.getProperty("java.io.tmpdir")); }
}
