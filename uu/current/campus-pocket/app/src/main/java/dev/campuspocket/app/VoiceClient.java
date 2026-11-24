package dev.campuspocket.app;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import javax.net.ssl.HttpsURLConnection;

final class VoiceClient {
    static final String BASE = "https://api.siliconflow.cn/v1";
    private static final String MODEL = "FunAudioLLM/CosyVoice2-0.5B";
    static final int SAMPLE_LIMIT = 8 * 1024 * 1024;
    static final int WAV_LIMIT = 24 * 1024 * 1024;

    static String enroll(String key, File sample, String transcript) throws Exception {
        String unique = UUID.randomUUID().toString().replace("-", "");
        String boundary = "PocketVoice" + unique;
        String format = sample.getName().endsWith(".mp3") ? "mp3" : "wav";
        ByteArrayOutputStream parts = new ByteArrayOutputStream();
        part(parts, boundary, "model", MODEL);
        part(parts, boundary, "customName", "campus-" + unique.substring(0, 8));
        part(parts, boundary, "text", transcript);
        write(parts, "--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"sample."
                + format + "\"\r\nContent-Type: " + ("mp3".equals(format) ? "audio/mpeg" : "audio/wav") + "\r\n\r\n");
        byte[] end = ("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8);
        HttpsURLConnection con = connect("/uploads/audio/voice", key, "multipart/form-data; boundary=" + boundary);
        try {
            con.setFixedLengthStreamingMode(parts.size() + sample.length() + end.length);
            try (OutputStream out = con.getOutputStream(); InputStream in = new FileInputStream(sample)) {
                parts.writeTo(out);
                copy(in, out, SAMPLE_LIMIT);
                out.write(end);
            }
            checkStatus(con);
            try (InputStream in = con.getInputStream()) {
                ByteArrayOutputStream result = new ByteArrayOutputStream();
                copy(in, result, 16384);
                String id = new JSONObject(result.toString("UTF-8")).optString("uri");
                if (!id.startsWith("speech:") || id.length() > 512) {
                    throw new IOException("服务未返回有效音色。");
                }
                return id;
            }
        } finally {
            con.disconnect();
        }
    }

    static void render(Store.Voice voice, String words, File target) throws Exception {
        render(voice, words, target, false);
    }

    static void render(Store.Voice voice, String words, File target, boolean dialogue) throws Exception {
        JSONObject body = new JSONObject().put("model", MODEL).put("voice", voice.id)
                .put("input", words).put("response_format", "wav").put("sample_rate", 24000).put("stream", false);
        HttpsURLConnection con = post("/audio/speech", voice.key, body, dialogue);
        try {
            checkStatus(con);
            try (InputStream in = con.getInputStream(); OutputStream out = new FileOutputStream(target)) {
                copy(in, out, WAV_LIMIT);
            }
            try (InputStream in = new FileInputStream(target)) {
                byte[] header = new byte[12];
                if (target.length() <= 44 || in.read(header) != header.length
                        || !new String(header, 0, 4, StandardCharsets.US_ASCII).equals("RIFF")
                        || !new String(header, 8, 4, StandardCharsets.US_ASCII).equals("WAVE")) {
                    throw new IOException("服务未返回可用的 WAV 音频。");
                }
            }
        } finally {
            con.disconnect();
        }
    }

    static void delete(Store.Voice voice) throws Exception {
        HttpsURLConnection con = post("/audio/voice/deletions", voice.key, new JSONObject().put("uri", voice.id), false);
        try {
            checkStatus(con);
        } finally {
            con.disconnect();
        }
    }

    private static HttpsURLConnection connect(String path, String key, String contentType) throws IOException {
        HttpsURLConnection con = (HttpsURLConnection) new URL(BASE + path).openConnection();
        con.setRequestMethod("POST");
        con.setInstanceFollowRedirects(false);
        con.setConnectTimeout(15000);
        con.setReadTimeout(90000);
        con.setRequestProperty("Authorization", "Bearer " + key);
        con.setRequestProperty("Content-Type", contentType);
        con.setDoOutput(true);
        return con;
    }

    private static HttpsURLConnection post(String path, String key, JSONObject body, boolean dialogue) throws IOException {
        HttpsURLConnection con = connect(path, key, "application/json; charset=UTF-8");
        if (dialogue) {
            con.setConnectTimeout(6000);
            con.setReadTimeout(15000);
        }
        byte[] data = body.toString().getBytes(StandardCharsets.UTF_8);
        con.setFixedLengthStreamingMode(data.length);
        try (OutputStream out = con.getOutputStream()) {
            out.write(data);
        } catch (IOException failure) {
            con.disconnect();
            throw failure;
        }
        return con;
    }

    private static void checkStatus(HttpsURLConnection con) throws IOException {
        int status = con.getResponseCode();
        if (status >= 200 && status < 300) return;
        switch (status) {
            case 400: throw new IOException("服务未接受请求。请检查样音格式、文字或音色是否有效。");
            case 401: throw new IOException("硅基流动 API Key 无效或已过期。");
            case 402: throw new IOException("语音服务账户余额不足。");
            case 403: throw new IOException("语音服务拒绝访问，请检查账户权限和实名认证。");
            case 413: throw new IOException("样音文件太大，请重新录制短样音。");
            case 429: throw new IOException("语音服务已限流，请稍后再试。");
            default: throw new IOException("语音服务返回 HTTP " + status + "，请稍后再试。");
        }
    }

    private static void part(OutputStream out, String boundary, String name, String value) throws IOException {
        write(out, "--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + name
                + "\"\r\n\r\n" + value + "\r\n");
    }

    private static void write(OutputStream out, String text) throws IOException {
        out.write(text.getBytes(StandardCharsets.UTF_8));
    }

    static void copy(InputStream in, OutputStream out, long limit) throws IOException {
        byte[] buffer = new byte[8192];
        long total = 0;
        int count;
        while ((count = in.read(buffer)) != -1) {
            total += count;
            if (total > limit) throw new IOException("音频文件超过大小限制。");
            out.write(buffer, 0, count);
        }
    }
}
