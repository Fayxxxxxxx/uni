package dev.campuspocket.app;

import android.os.Handler;
import android.os.SystemClock;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

final class USpeakingFollow {
    private final CampusService service;
    private final Handler ui;
    private final USpeakingMonitor monitor;
    private final String packageName;
    private final int window;
    private final Set<String> confirmed = new HashSet<>();
    private final Set<String> timedOut = new HashSet<>();
    private final TrainingScore scores;
    private final Runnable waitCard = this::checkCard;
    private ReadAloudRun reader;
    private long deadline;
    private String expected, readingSentence;
    private boolean active = true;

    USpeakingFollow(CampusService service, Handler ui, USpeakingMonitor monitor, CampusService.Capture initial) {
        this.service = service;
        this.ui = ui;
        this.monitor = monitor;
        packageName = initial.packageName;
        window = initial.window;
        scores = new TrainingScore(new Store(service).trainingMinimum());
    }

    void start(CampusService.Capture cap) {
        USpeakingFollowPage page = page(cap);
        if (page == null) {
            finish("请先在范例学习 Step 2 展开要跟读的句子。");
            return;
        }
        if (page.scored() && scores.complete(page.sentence, page.state.score)) {
            advance(cap, page);
            return;
        }
        readingSentence = page.sentence;
        monitor.followProgress("正在准备英语朗读…");
        reader = new ReadAloudRun(service, ui, cap, false, new Store.Config("", "", "", "", "", "", false, false),
                new Store.Voice("", ""), this);
        reader.start();
    }

    void startRecording(String sentence, boolean touch) {
        CampusService.Capture cap = service.capture(true);
        USpeakingFollowPage page = page(cap);
        if (page == null || !page.sentence.equals(sentence)) return;
        PageModel.Line label = new TrainingPage(cap.coursePage(), cap.lines).microphone();
        if (label != null) service.pressFollowMicrophone(cap, label, touch);
    }

    USpeakingFollowPage page(CampusService.Capture cap) {
        if (cap == null || !inWindow(cap)) return null;
        return USpeakingFollowPage.read(cap.coursePage(), cap.lines);
    }

    boolean inWindow(CampusService.Capture cap) {
        return cap.window == window && packageName.equals(cap.packageName);
    }

    void progress(String status) { monitor.followProgress(status); }

    void recorded(ReadAloudRun.Outcome outcome, String status) {
        if (!active) return;
        reader = null;
        if (outcome == ReadAloudRun.Outcome.STOPPED) {
            finish(status);
            return;
        }
        CampusService.Capture cap = service.capture();
        USpeakingFollowPage page = page(cap);
        if (page == null || !page.sentence.equals(readingSentence)) {
            finish("页面已变化，请核对当前句评分。");
            return;
        }
        if (outcome == ReadAloudRun.Outcome.TIMED_OUT) {
            timedOut.add(page.sentence);
            advance(cap, page);
            return;
        }
        if (!page.scored()) {
            finish("当前句还未显示评分。");
            return;
        }
        int attempt = scores.record(page.sentence);
        if (!scores.meets(page.state.score) && attempt == 1) {
            start(cap);
            return;
        }
        advance(cap, page);
    }

    private void advance(CampusService.Capture cap, USpeakingFollowPage page) {
        if (!timedOut.contains(page.sentence)) confirmed.add(page.sentence);
        PageModel.Line next = page.cards.after(page.sentence);
        if (next == null) {
            finish(summary() + "。请查看下面是否还有句子。");
            return;
        }
        if (confirmed.size() + timedOut.size() >= 40 || confirmed.contains(next.text) || timedOut.contains(next.text)
                || page.cards.ambiguous(next.text)
                || !next.localControl || !service.press(cap, Collections.singletonList(next))) {
            finish(summary() + "，未能确认下一张卡片。");
            return;
        }
        expected = next.text;
        deadline = SystemClock.uptimeMillis() + 10000;
        monitor.followProgress(summary() + "，正在等待下一张卡片展开…");
        ui.postDelayed(waitCard, 450);
    }

    private String summary() {
        return "已确认 " + confirmed.size() + " 句" + (timedOut.isEmpty() ? "" : "，U校园超时 " + timedOut.size() + " 句");
    }

    private void checkCard() {
        if (!active) return;
        CampusService.Capture cap = service.capture();
        USpeakingFollowPage page = page(cap);
        if (page != null && page.sentence.equals(expected) && (page.microphone() != null || page.positiveScore())) {
            start(cap);
        } else if (SystemClock.uptimeMillis() < deadline) {
            ui.postDelayed(waitCard, 550);
        } else {
            finish("下一张卡片没有显示可确认的录音入口。");
        }
    }

    void finish(String status) {
        if (!active) return;
        active = false;
        ui.removeCallbacks(waitCard);
        if (reader != null) {
            reader.cancel(false);
            reader = null;
        }
        monitor.followFinished(this, status);
    }
}
