package dev.campuspocket.app;

import java.util.HashMap;
import java.util.Map;

final class TrainingScore {
    static final int DEFAULT_MINIMUM = 90;
    private final int minimum;
    private final Map<String, Integer> attempts = new HashMap<>();

    TrainingScore(int minimum) { this.minimum = minimum; }

    boolean meets(String score) { return percentage(score) >= minimum; }

    boolean below(String score) {
        double value = percentage(score);
        return value >= 0 && value < minimum;
    }

    void observeExisting(String key, String score) {
        if (percentage(score) >= 0) attempts.putIfAbsent(key, 1);
    }

    boolean complete(String key, String score) {
        return meets(score) || attempts.getOrDefault(key, 0) >= 2;
    }

    int record(String key) { return attempts.merge(key, 1, Integer::sum); }

    static double percentage(String score) {
        if (!score.matches("\\d{1,3}(?:\\.\\d+)?(?:\\s*/\\s*\\d{1,3})?")) return -1;
        String[] parts = score.split("/");
        double points = Double.parseDouble(parts[0].trim());
        double total = parts.length == 1 ? 100 : Double.parseDouble(parts[1].trim());
        return total <= 0 || points > total ? -1 : 100 * points / total;
    }
}
