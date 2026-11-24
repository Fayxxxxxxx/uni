package dev.campuspocket.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.ColorSpace;
import android.hardware.HardwareBuffer;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Comparator;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

final class USpeakingDiagnostics {
    static final int MAX_FRAMES = 36;
    private static final String PREFS = "uspeaking_diagnostics";
    private static final ExecutorService FILES = Executors.newSingleThreadExecutor();

    static final class Session {
        final File folder;
        final String mode;
        int frames;

        Session(Context context) {
            this(context, "OBSERVER");
        }

        Session(Context context, String mode) {
            this.mode = mode;
            String id = UUID.randomUUID().toString();
            folder = new File(base(context), id);
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("latest", id).apply();
            enqueue(() -> {
                ensure(folder);
                JSONObject manifest = new JSONObject();
                put(manifest, "schema", 1);
                put(manifest, "mode", mode);
                put(manifest, "session", id);
                put(manifest, "startedAtEpochMs", System.currentTimeMillis());
                put(manifest, "androidApi", Build.VERSION.SDK_INT);
                put(manifest, "manufacturer", Build.MANUFACTURER);
                put(manifest, "model", Build.MODEL);
                put(manifest, "samplingLimit", MAX_FRAMES);
                write(new File(folder, "manifest.json"), manifest.toString(2), false);
            });
        }

        File frame(JSONObject metadata, JSONArray tree) {
            File frame = new File(folder, String.format(java.util.Locale.ROOT, "%04d", ++frames));
            enqueue(() -> {
                ensure(frame);
                put(metadata, "sample", frame.getName());
                write(new File(frame, "state.json"), metadata.toString(2), false);
                write(new File(frame, "tree.json"), tree.toString(2), false);
                timeline("snapshot", metadata);
            });
            return frame;
        }

        void event(String kind, String info) {
            JSONObject event = new JSONObject();
            put(event, "detail", info);
            enqueue(() -> timeline(kind, event));
        }

        private void timeline(String kind, JSONObject detail) throws Exception {
            JSONObject row = new JSONObject();
            put(row, "timeEpochMs", System.currentTimeMillis());
            put(row, "event", kind);
            put(row, "data", detail);
            write(new File(folder, "timeline.ndjson"), row.toString() + "\n", true);
        }
    }

    static void image(File frame, HardwareBuffer buffer, ColorSpace colorSpace) {
        enqueue(() -> {
            Bitmap gpu = null, pixels = null, scaled = null;
            try {
                gpu = Bitmap.wrapHardwareBuffer(buffer, colorSpace);
                if (gpu == null) throw new IOException("Screenshot buffer unavailable");
                pixels = gpu.copy(Bitmap.Config.ARGB_8888, false);
                if (pixels == null) throw new IOException("Screenshot pixels unavailable");
                int height = pixels.getHeight(), width = pixels.getWidth();
                float scale = Math.min(1f, 1920f / Math.max(width, height));
                scaled = scale == 1f ? pixels : Bitmap.createScaledBitmap(pixels,
                        Math.max(1, Math.round(width * scale)), Math.max(1, Math.round(height * scale)), true);
                String hash = differenceHash(scaled);
                File image = new File(frame, "screen.png");
                try (OutputStream out = new FileOutputStream(image)) {
                    if (!scaled.compress(Bitmap.CompressFormat.PNG, 100, out)) throw new IOException("PNG write failed");
                }
                JSONObject status = new JSONObject();
                put(status, "screenshot", "saved");
                put(status, "differenceHash", hash);
                put(status, "width", scaled.getWidth());
                put(status, "height", scaled.getHeight());
                write(new File(frame, "screenshot.json"), status.toString(2), false);
            } catch (Exception error) {
                new File(frame, "screen.png").delete();
                writeImageStatus(frame, "screenshot_write_failed");
            } finally {
                if (scaled != null && scaled != pixels) scaled.recycle();
                if (pixels != null) pixels.recycle();
                if (gpu != null) gpu.recycle();
                buffer.close();
            }
        });
    }

    static void imageStatus(File frame, String reason) {
        enqueue(() -> writeImageStatus(frame, reason));
    }

    private static void writeImageStatus(File frame, String reason) throws Exception {
        JSONObject status = new JSONObject();
        put(status, "screenshot", reason);
        write(new File(frame, "screenshot.json"), status.toString(2), false);
    }

    private static String differenceHash(Bitmap image) {
        Bitmap tiny = Bitmap.createScaledBitmap(image, 9, 8, true);
        long hash = 0;
        for (int y = 0; y < 8; y++) {
            for (int x = 0; x < 8; x++) {
                hash = hash << 1;
                if (luma(tiny.getPixel(x, y)) > luma(tiny.getPixel(x + 1, y))) hash |= 1;
            }
        }
        if (tiny != image) tiny.recycle();
        return String.format(java.util.Locale.ROOT, "%016x", hash);
    }

    private static int luma(int pixel) {
        return 299 * ((pixel >> 16) & 255) + 587 * ((pixel >> 8) & 255) + 114 * (pixel & 255);
    }

    interface Finished { void accept(boolean success); }

    static boolean hasSession(Context context) { return selected(context) != null; }

    static void export(Context context, Uri destination, Finished finished) {
        File folder = selected(context);
        Context app = context.getApplicationContext();
        FILES.execute(() -> {
            boolean success = false;
            if (folder != null && new File(folder, "manifest.json").isFile()) {
                try (OutputStream raw = app.getContentResolver().openOutputStream(destination, "w")) {
                    if (raw != null) {
                        try (ZipOutputStream zip = new ZipOutputStream(raw)) {
                            zipFolder(folder, folder, zip);
                            zip.finish();
                        }
                        success = true;
                    }
                } catch (IOException | SecurityException error) {
                    android.util.Log.w("USpeakingObserver", "Diagnostic export failed", error);
                }
            }
            boolean exported = success;
            new Handler(Looper.getMainLooper()).post(() -> finished.accept(exported));
        });
    }

    private static void zipFolder(File root, File folder, ZipOutputStream zip) throws IOException {
        File[] files = folder.listFiles();
        if (files == null) throw new IOException("Diagnostic directory unavailable");
        Arrays.sort(files, Comparator.comparing(File::getName));
        byte[] chunk = new byte[16384];
        for (File file : files) {
            if (file.isDirectory()) {
                zipFolder(root, file, zip);
                continue;
            }
            zip.putNextEntry(new ZipEntry(file.getPath().substring(root.getPath().length() + 1)));
            try (FileInputStream stream = new FileInputStream(file)) {
                int count;
                while ((count = stream.read(chunk)) >= 0) zip.write(chunk, 0, count);
            }
            zip.closeEntry();
        }
    }

    static void put(JSONObject data, String key, Object value) {
        try {
            data.put(key, value);
        } catch (JSONException error) {
            throw new IllegalArgumentException("Invalid diagnostic field " + key, error);
        }
    }

    private static File selected(Context context) {
        String id = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("latest", "");
        return id.matches("[0-9a-f\\-]{36}") ? new File(base(context), id) : null;
    }

    private static File base(Context context) { return new File(context.getFilesDir(), "uspeaking"); }

    private static void ensure(File folder) throws IOException {
        if (!folder.isDirectory() && !folder.mkdirs()) throw new IOException("Diagnostic directory could not be created");
    }

    private static void write(File file, String value, boolean append) throws IOException {
        try (FileOutputStream out = new FileOutputStream(file, append)) {
            out.write(value.getBytes(StandardCharsets.UTF_8));
        }
    }

    private interface FileJob { void run() throws Exception; }

    private static void enqueue(FileJob job) {
        FILES.execute(() -> {
            try {
                job.run();
            } catch (Exception error) {
                android.util.Log.w("USpeakingObserver", "Diagnostic file operation failed", error);
            }
        });
    }
}
