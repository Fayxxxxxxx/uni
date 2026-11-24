package dev.campuspocket.app;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;

final class TrainingWave {
    private TrainingWave() {}

    // Other PCM formats are checked by the media decoder.
    static boolean silent16Bit(File audio) throws IOException {
        try (RandomAccessFile file = new RandomAccessFile(audio, "r")) {
            if (file.length() < 12 || file.readInt() != 0x52494646) return false;
            file.skipBytes(4);
            if (file.readInt() != 0x57415645) return false;
            boolean pcm16 = false;
            while (file.getFilePointer() + 8 <= file.length()) {
                int chunk = file.readInt();
                long declared = Integer.toUnsignedLong(Integer.reverseBytes(file.readInt()));
                long start = file.getFilePointer();
                long size = Math.min(declared, file.length() - start);
                if (chunk == 0x666d7420 && size >= 16) {
                    int encoding = Short.toUnsignedInt(Short.reverseBytes(file.readShort()));
                    file.skipBytes(12);
                    pcm16 = encoding == 1 && Short.reverseBytes(file.readShort()) == 16;
                } else if (chunk == 0x64617461 && pcm16) {
                    return hasNoSignal(file, size);
                }
                file.seek(Math.min(file.length(), start + declared + (declared & 1)));
            }
            return false;
        }
    }

    private static boolean hasNoSignal(RandomAccessFile file, long size) throws IOException {
        byte[] data = new byte[8192];
        long remaining = size & ~1L;
        while (remaining > 0) {
            int count = (int) Math.min(remaining, data.length);
            file.readFully(data, 0, count);
            for (int offset = 0; offset < count; offset += 2) {
                int sample = (short) ((data[offset] & 255) | data[offset + 1] << 8);
                if (Math.abs(sample) > 16) return false;
            }
            remaining -= count;
        }
        return true;
    }
}
