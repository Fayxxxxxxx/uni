package dev.campuspocket.app;

import android.os.Handler;
import android.os.SystemClock;

import java.util.HashSet;
import java.util.Set;

final class FollowAlongRun {
    private final CampusService service;
    private final Handler ui;
    private final String packageName;
    private final int window;
    private final Set<String> saved = new HashSet<>();
    private boolean active = true;
    private String current, expected = "";
    private long deadline;
    private int scrolls;

    FollowAlongRun(CampusService service, Handler ui, CampusService.Capture cap) {
        this.service = service;
        this.ui = ui;
        packageName = cap.packageName;
        window = cap.window;
    }

    void begin(CampusService.Capture cap) {
        ReadAloudPage reading = cap.page.reading;
        current = reading.passage;
        if (current.isEmpty() || !reading.stop.isEmpty()) {
            finish(false, "跟读暂停。请确认当前句没有正在进行的录音。");
        } else if (reading.saved) {
            saved.add(current);
            advance(cap, true);
        } else if (reading.start.size() == 1) {
            service.prepareReading(cap, false);
        } else {
            finish(false, "当前句没有唯一的录音入口。");
        }
    }

    void recorded(ReadAloudRun.Outcome result, String status) {
        if (!active) return;
        CampusService.Capture cap = currentPage();
        SpeakingCards cards = cap == null ? null : cap.page.reading.cards;
        if (result == ReadAloudRun.Outcome.STOPPED || cards == null || cards.current == null
                || !cards.current.text.equals(current) || !cards.saved) {
            finish(false, "已确认 " + saved.size() + " 句 · " + status);
            return;
        }
        saved.add(current);
        advance(cap, true);
    }

    private void advance(CampusService.Capture cap, boolean canScroll) {
        SpeakingCards cards = cap.page.reading.cards;
        if (cards.ambiguous(current)) {
            finish(false, "已确认 " + saved.size() + " 句。页面有重复句子，请确认从哪张卡片继续。");
            return;
        }
        PageModel.Line next = cards.after(current);
        if (next != null) {
            if (saved.contains(next.text) || saved.size() >= 40 || !service.followTap(cap, next)) {
                finish(false, "已确认 " + saved.size() + " 句。下一句重复或无法打开。");
                return;
            }
            expected = next.text;
            service.followNotice("已确认 " + saved.size() + " 句，等待下一张句子卡片展开…");
            deadline = now() + 10000;
            ui.postDelayed(this::waitCard, 600);
        } else if (canScroll && cards.current != null && scrolls++ < 4
                && service.scrollFollowCards(cap, cards.current)) {
            service.followNotice("已确认 " + saved.size() + " 句，正在检查下面还有没有句子…");
            deadline = now() + 3500;
            ui.postDelayed(this::waitScroll, 650);
        } else if (cards.next.size() == 1 && service.followTap(cap, cards.next.get(0))) {
            service.followNotice("已确认 " + saved.size() + " 句，正在等 U校园进入下一步…");
            deadline = now() + 12000;
            ui.postDelayed(this::waitStep, 650);
        } else {
            finish(false, "已确认 " + saved.size() + " 句。当前页面没有可确认的下一步。");
        }
    }

    private void waitCard() {
        if (!active) return;
        CampusService.Capture cap = currentPage();
        SpeakingCards cards = cap == null ? null : cap.page.reading.cards;
        if (cards != null && cards.current != null && cards.current.text.equals(expected)
                && (!cards.start.isEmpty() || cards.saved)) {
            scrolls = 0;
            begin(cap);
        } else if (now() < deadline) {
            ui.postDelayed(this::waitCard, 550);
        } else {
            finish(false, "已确认 " + saved.size() + " 句。下一张卡片未显示录音状态。");
        }
    }

    private void waitScroll() {
        if (!active) return;
        CampusService.Capture cap = currentPage();
        SpeakingCards cards = cap == null ? null : cap.page.reading.cards;
        if (cards != null && cards.after(current) != null) {
            advance(cap, true);
        } else if (now() < deadline) {
            ui.postDelayed(this::waitScroll, 550);
        } else if (cards != null && cards.sentences.stream().anyMatch(line -> line.text.equals(current))) {
            advance(cap, false);
        } else {
            finish(false, "已确认 " + saved.size() + " 句。滚动后原句离开了可见范围，请展开下一句继续。");
        }
    }

    private void waitStep() {
        if (!active) return;
        CampusService.Capture cap = service.capture();
        if (cap != null && sameApp(cap) && !cap.page.blocked && !cap.page.text.trim().isEmpty()
                && !cap.coursePage().loading
                && cap.page.reading != null && cap.page.reading.cards == null) {
            finish(true, "已确认 " + saved.size() + " 句，U校园已进入下一步。");
        } else if (now() < deadline) {
            ui.postDelayed(this::waitStep, 650);
        } else {
            finish(false, "已确认 " + saved.size() + " 句。U校园未确认进入下一步。");
        }
    }

    void cancel() { active = false; }

    private void finish(boolean advanced, String status) {
        cancel();
        service.followFinished(this, advanced, status);
    }

    private CampusService.Capture currentPage() {
        CampusService.Capture cap = service.capture();
        return cap != null && cap.window == window && sameApp(cap) && !cap.page.blocked
                && cap.page.reading != null ? cap : null;
    }

    private boolean sameApp(CampusService.Capture cap) { return packageName.equals(cap.packageName); }

    private static long now() { return SystemClock.uptimeMillis(); }
}
