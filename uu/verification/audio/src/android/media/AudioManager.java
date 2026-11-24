package android.media;
public class AudioManager {
    public static final int STREAM_MUSIC = 3, GET_DEVICES_OUTPUTS = 2;
    public int getStreamVolume(int stream) { return 10; }
    public AudioDeviceInfo[] getDevices(int mode) { return new AudioDeviceInfo[0]; }
}
