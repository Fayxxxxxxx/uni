package dev.campuspocket.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.List;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

final class Store {
    private static final String KEY_ALIAS = "campus_pocket_provider_v1";
    final SharedPreferences prefs;

    Store(Context context) {
        prefs = context.getSharedPreferences("workbench", Context.MODE_PRIVATE);
    }

    String lastCampus() { return prefs.getString("last_campus", ""); }

    int trainingMinimum() {
        int minimum = prefs.getInt("training_minimum_score", TrainingScore.DEFAULT_MINIMUM);
        if (!prefs.getBoolean("training_score_target_updated", false)) {
            if (minimum == 60) minimum = TrainingScore.DEFAULT_MINIMUM;
            prefs.edit().putInt("training_minimum_score", minimum).putBoolean("training_score_target_updated", true).apply();
        }
        return Math.max(0, Math.min(100, minimum));
    }

    float trainingSpeed() {
        float value = prefs.getFloat("training_speed", 1f);
        return Float.isNaN(value) ? 1f : Math.max(.6f, Math.min(1.4f, value));
    }

    void rememberCampus(String name) {
        if (!name.equals(lastCampus())) prefs.edit().putString("last_campus", name).apply();
    }

    static final class Config {
        final String base, key, model, audioBase, audioKey, audioModel;
        final boolean autoSubmit, autoNext;

        Config(String base, String key, String model, String audioBase, String audioKey,
               String audioModel, boolean autoSubmit, boolean autoNext) {
            this.base = base.trim();
            this.key = key.trim();
            this.model = model.trim();
            this.audioBase = audioBase.trim();
            this.audioKey = audioKey.trim();
            this.audioModel = audioModel.trim();
            this.autoSubmit = autoSubmit;
            this.autoNext = autoNext;
        }

        String speechBase() { return audioBase.isEmpty() ? base : audioBase; }
        String speechKey() {
            boolean sameService = audioBase.isEmpty() || audioBase.replaceAll("/+$", "").equals(base.replaceAll("/+$", ""));
            return sameService && audioKey.isEmpty() ? key : audioKey;
        }
        boolean ready() { return !base.isEmpty() && !key.isEmpty() && !model.isEmpty(); }
        boolean speechReady() {
            return !speechBase().isEmpty() && !speechKey().isEmpty() && !audioModel.isEmpty();
        }
    }

    Config config() {
        return new Config(prefs.getString("base", "https://api.siliconflow.cn/v1"),
                decrypt("secret"), prefs.getString("model", ""), prefs.getString("audio_base", ""),
                decrypt("audio_secret"), prefs.getString("audio_model", ""), prefs.getBoolean("auto_submit", false),
                prefs.getBoolean("auto_next", false));
    }

    void save(Config c) throws Exception {
        if (!c.base.isEmpty()) validateBase(c.base);
        if (!c.audioBase.isEmpty()) validateBase(c.audioBase);
        String key = encrypt(c.key);
        String audioKey = encrypt(c.audioKey);
        if (!prefs.edit().putString("base", c.base).putString("secret", key).putString("model", c.model)
                .putString("audio_base", c.audioBase).putString("audio_secret", audioKey)
                .putString("audio_model", c.audioModel).putBoolean("auto_submit", c.autoSubmit)
                .putBoolean("auto_next", c.autoNext).commit()) {
            throw new IOException("设置保存失败。");
        }
    }

    static final class Voice {
        final String id;
        final String key;

        Voice(String id, String key) {
            this.id = id;
            this.key = key;
        }
    }

    Voice voice() {
        return new Voice(prefs.getString("personal_voice_id", ""), decrypt("personal_voice_secret"));
    }

    void saveVoice(String id, String apiKey) throws Exception {
        String secret = encrypt(apiKey);
        if (!prefs.edit().putString("personal_voice_id", id)
                .putString("personal_voice_secret", secret).commit()) {
            throw new IOException("手机未能保存音色设置。");
        }
    }

    void clearVoice() throws IOException {
        if (!prefs.edit().remove("personal_voice_id").remove("personal_voice_secret").commit()) {
            throw new IOException("手机未能更新音色设置。");
        }
    }

    static void validateBase(String value) throws Exception {
        URI url = new URI(value);
        if (!"https".equalsIgnoreCase(url.getScheme()) || url.getHost() == null || url.getUserInfo() != null
                || url.getFragment() != null || url.getQuery() != null) {
            throw new IOException("接口地址需要是有效的 HTTPS 基础地址。");
        }
    }

    void transcript(String text) {
        prefs.edit().putString("transcript", text.trim()).apply();
    }

    String transcript() { return prefs.getString("transcript", ""); }

    void event(String kind, String note) {
        JSONArray previous = list("activity");
        JSONArray next = new JSONArray();
        JSONObject item = new JSONObject();
        try {
            item.put("time", System.currentTimeMillis());
            item.put("kind", kind);
            item.put("note", note);
            next.put(item);
            for (int i = 0; i < Math.min(previous.length(), 39); i++) next.put(previous.get(i));
            prefs.edit().putString("activity", next.toString()).apply();
        } catch (Exception ignored) {
            // A history entry must not interrupt the active operation.
        }
    }

    void addSolved(int count) {
        prefs.edit().putInt("solved", prefs.getInt("solved", 0) + count).apply();
    }

    int solved() { return prefs.getInt("solved", 0); }

    JSONArray list(String name) {
        try {
            return new JSONArray(prefs.getString(name, "[]"));
        } catch (Exception e) {
            return new JSONArray();
        }
    }

    void addTasks(List<PageModel.Line> tasks) {
        JSONArray items = list("tasks");
        List<String> names = new ArrayList<>();
        for (int i = 0; i < items.length(); i++) names.add(items.optJSONObject(i).optString("name"));
        for (PageModel.Line task : tasks) {
            if (names.contains(task.text) || items.length() >= 150) continue;
            try {
                JSONObject item = new JSONObject();
                item.put("name", task.text);
                item.put("done", task.text.contains("已完成"));
                items.put(item);
                names.add(task.text);
            } catch (Exception ignored) {}
        }
        prefs.edit().putString("tasks", items.toString()).apply();
    }

    void changeTask(int index, boolean done) {
        JSONArray items = list("tasks");
        JSONObject item = items.optJSONObject(index);
        if (item == null) return;
        try {
            item.put("done", done);
            prefs.edit().putString("tasks", items.toString()).apply();
        } catch (Exception ignored) {}
    }

    private SecretKey key(boolean create) throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        SecretKey saved = (SecretKey) ks.getKey(KEY_ALIAS, null);
        if (saved != null || !create) return saved;
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
        return generator.generateKey();
    }

    private String encrypt(String value) throws Exception {
        if (value.isEmpty()) return "";
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key(true));
        byte[] iv = cipher.getIV();
        byte[] bytes = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
        return Base64.encodeToString(ByteBuffer.allocate(1 + iv.length + bytes.length)
                .put((byte) iv.length).put(iv).put(bytes).array(), Base64.NO_WRAP);
    }

    private String decrypt(String name) {
        String saved = prefs.getString(name, "");
        if (saved.isEmpty()) return "";
        try {
            ByteBuffer data = ByteBuffer.wrap(Base64.decode(saved, Base64.NO_WRAP));
            byte[] iv = new byte[data.get() & 255];
            data.get(iv);
            byte[] bytes = new byte[data.remaining()];
            data.get(bytes);
            SecretKey key = key(false);
            if (key == null) return "";
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, iv));
            return new String(cipher.doFinal(bytes), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }
}
