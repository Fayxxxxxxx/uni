package android.media;
public class PlaybackParams {
    private float speed = 1f;
    public PlaybackParams setSpeed(float value) { speed = value; return this; }
    public float getSpeed() { return speed; }
}
