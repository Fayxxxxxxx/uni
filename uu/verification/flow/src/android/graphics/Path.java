package android.graphics;
public class Path {
    public float startX, startY, endX, endY;
    public void moveTo(float x, float y) { startX = endX = x; startY = endY = y; }
    public void lineTo(float x, float y) { endX = x; endY = y; }
}
