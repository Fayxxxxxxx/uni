package dev.campuspocket.app;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

final class VoiceSampleRecorder {
    private static final int RATE = 16000;
    static final int MAX_SECONDS = 20;
    private static final int MIN_SECONDS = 3;
    private volatile boolean stopped;

    void stop() { stopped = true; }

    long record(File output) throws IOException {
        int size = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        if (size <= 0) throw new IOException("手机无法以所需格式录音。");
        AudioRecord mic = new AudioRecord(MediaRecorder.AudioSource.MIC, RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, Math.max(size, RATE / 5));
        int samples = 0;
        int peak = 0;
        try (RandomAccessFile wav = new RandomAccessFile(output, "rw")) {
            if (mic.getState() != AudioRecord.STATE_INITIALIZED) throw new IOException("麦克风初始化失败。");
            wav.setLength(44);
            wav.seek(44);
            short[] buffer = new short[RATE / 10];
            ByteBuffer pcm = ByteBuffer.allocate(buffer.length * 2).order(ByteOrder.LITTLE_ENDIAN);
            if (!stopped) mic.startRecording();
            int limit = RATE * MAX_SECONDS;
            while (!stopped && samples < limit) {
                int count = mic.read(buffer, 0, Math.min(buffer.length, limit - samples));
                if (count <= 0) throw new IOException("录音中断，请重新录制。");
                pcm.clear();
                for (int i = 0; i < count; i++) {
                    peak = Math.max(peak, Math.abs((int) buffer[i]));
                    pcm.putShort(buffer[i]);
                }
                wav.write(pcm.array(), 0, count * 2);
                samples += count;
            }
            if (samples < RATE * MIN_SECONDS) throw new IOException("样音少于 3 秒，请重新录制。");
            if (peak < 128) throw new IOException("样音声音太小，请靠近麦克风重新录制。");
            int bytes = samples * 2;
            ByteBuffer header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
            header.putInt(0x46464952).putInt(36 + bytes).putInt(0x45564157);
            header.putInt(0x20746d66).putInt(16).putShort((short) 1).putShort((short) 1);
            header.putInt(RATE).putInt(RATE * 2).putShort((short) 2).putShort((short) 16);
            header.putInt(0x61746164).putInt(bytes);
            wav.seek(0);
            wav.write(header.array());
        } finally {
            try {
                if (mic.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) mic.stop();
            } finally {
                mic.release();
            }
        }
        return samples * 1000L / RATE;
    }
}
