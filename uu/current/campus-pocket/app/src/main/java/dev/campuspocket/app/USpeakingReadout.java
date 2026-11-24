package dev.campuspocket.app;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

final class USpeakingReadout {
    static final Pattern CLOCK = Pattern.compile("^(\\d{1,3}):([0-5]\\d)\\s*/\\s*(\\d{1,3}):([0-5]\\d)$");
    static final Pattern SECONDS = Pattern.compile("^(\\d{1,3})\\s*(?:s|秒)$", Pattern.CASE_INSENSITIVE);
    static final Pattern QUESTION = Pattern.compile("^第\\s*(\\d{1,3})\\s*/\\s*(\\d{1,3})\\s*题$");
    private static final Pattern TURN_HEAD = Pattern.compile("(?s).*话轮数\\s*[:：]?\\s*$");
    private static final Pattern TURNS = Pattern.compile("^话轮数[：:]?\\s*\\d{1,3}\\s*/\\s*\\d{1,3}$");
    private static final Pattern FRAGMENT = Pattern.compile("[第题话轮数0-9:：/s秒 ]+", Pattern.CASE_INSENSITIVE);

    static List<PageModel.Line> join(List<PageModel.Line> lines) {
        List<PageModel.Line> joined = new ArrayList<>(lines.size());
        for (int i = 0; i < lines.size(); i++) {
            PageModel.Line first = lines.get(i);
            boolean counters = !first.edit && first.parent >= 0 && first.bounds != null
                    && TURN_HEAD.matcher(first.text).matches();
            if (!fragment(first) && !counters) {
                joined.add(first);
                continue;
            }
            StringBuilder value = new StringBuilder(counters ? "话轮数:" : first.text);
            PageModel.Line previous = first;
            int end = i;
            String label = first.text;
            for (int j = i + 1; j < lines.size() && value.length() < 16; j++) {
                PageModel.Line next = lines.get(j);
                if (!fragment(next) || !sameRow(previous, next)) break;
                value.append(next.text);
                previous = next;
                String candidate = value.toString();
                if (CLOCK.matcher(candidate).matches() || SECONDS.matcher(candidate).matches()
                        || QUESTION.matcher(candidate).matches() || TURNS.matcher(candidate).matches()) {
                    end = j;
                    label = candidate;
                }
            }
            joined.add(end == i ? first : new PageModel.Line(first.id, label, first.click, false, false,
                    first.checked, first.checkbox, first.enabled, first.bounds, first.parent, first.localControl));
            i = end;
        }
        return joined;
    }

    private static boolean fragment(PageModel.Line line) {
        return !line.edit && line.parent >= 0 && line.bounds != null && line.text.length() <= 15
                && FRAGMENT.matcher(line.text).matches();
    }

    private static boolean sameRow(PageModel.Line left, PageModel.Line right) {
        if (left.parent != right.parent) return false;
        PageModel.Bounds a = left.bounds, b = right.bounds;
        int height = Math.max(a.bottom - a.top, b.bottom - b.top);
        return b.x() > a.x() && b.left - a.right <= height / 2 && Math.abs(a.y() - b.y()) <= height / 3;
    }

    static final class Progress {
        private String key = "";
        private int lastPosition;
        private long lastRead, lastAdvance = -1;

        USpeakingEngine.Media observe(String player, long now, int position, int length, boolean commit) {
            long advanced = -1;
            if (player.equals(key) && now - lastRead <= 4500) {
                int delta = position - lastPosition;
                if (delta == 0) advanced = lastAdvance;
                else if (delta > 0 && delta <= 1 + 2 * (now - lastRead) / 1000) advanced = now;
            }
            if (commit) {
                key = player;
                lastRead = now;
                lastPosition = position;
                lastAdvance = advanced;
            }
            if (position == length) return USpeakingEngine.Media.ENDED;
            if (position == 0) return USpeakingEngine.Media.READY;
            if (advanced < 0) return USpeakingEngine.Media.UNKNOWN;
            return now - advanced <= 2500 ? USpeakingEngine.Media.PLAYING : USpeakingEngine.Media.STALLED;
        }

        void reset() {
            key = "";
            lastAdvance = -1;
        }
    }
}
