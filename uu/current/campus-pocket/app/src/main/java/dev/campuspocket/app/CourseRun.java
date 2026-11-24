package dev.campuspocket.app;

import android.os.Handler;
import android.os.SystemClock;

import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;

final class CourseRun {
    private enum Phase { OBSERVE, NAVIGATE, WRITE, RESULT, MEDIA, AUDIO, ORAL, END }

    private static final long POLL = 900;
    private final CampusService service;
    private final Handler ui;
    private final String packageName;
    private final boolean observeOnly;
    private final Set<String> attempted = new HashSet<>();
    private final Set<String> answered = new HashSet<>();
    private final Set<String> results = new HashSet<>();
    private Set<String> catalog;
    private Phase phase = Phase.OBSERVE;
    private long deadline = clock() + 12000;
    private long nextUpdate;
    private final Runnable update = this::tick;
    private String previous = "", taskKey = "";
    private String audioPage = "", audioText = "";
    private String description = "", prompt = "", note = "";
    private boolean audioReady, audioStarted, audioStopped;
    private int confirmed;

    CourseRun(CampusService service, Handler ui, String packageName, boolean observeOnly) {
        this.service = service;
        this.ui = ui;
        this.packageName = packageName;
        this.observeOnly = observeOnly;
    }

    void signal() {
        if (phase == Phase.END) return;
        if (nextUpdate == 0 || nextUpdate > clock() + 160) scheduleAfter(160);
    }

    void begin() {
        if (observeOnly) note = "只监测页面，等待变化。";
        tick();
    }

    boolean allowsAuthorization() { return phase == Phase.AUDIO && !audioStarted; }

    boolean listening() { return phase == Phase.AUDIO; }

    private void tick() {
        nextUpdate = 0;
        if (phase == Phase.END) return;
        CampusService.Capture cap = service.capture();
        if (cap == null) {
            if (clock() < deadline && (allowsAuthorization() || phase == Phase.NAVIGATE)) {
                schedule();
            } else {
                end("U校园页面已离开，连续处理已暂停。");
            }
            return;
        }
        if (!packageName.equals(cap.packageName)) {
            end("已切换到另一版 U校园。");
            return;
        }
        CoursePage view = cap.coursePage();
        if (view.kind == CoursePage.Kind.BLOCKED) {
            end("当前页面需要本人处理。");
            return;
        }
        if (phase == Phase.ORAL) return;
        describe(view);
        if (observeOnly) {
            schedule();
            return;
        }
        if (view.loading && (phase == Phase.OBSERVE || phase == Phase.NAVIGATE || phase == Phase.RESULT)) {
            await("页面长时间未加载完，请查看 U校园提示。");
            return;
        }
        switch (phase) {
            case OBSERVE: handle(cap, view); break;
            case NAVIGATE:
                if (!previous.equals(view.identity)) {
                    phase = Phase.OBSERVE;
                    handle(cap, view);
                } else await("点击后页面未切换，已暂停。");
                break;
            case WRITE:
                if (!previous.equals(view.identity)) end("作答期间题目发生变化。");
                else await("等待模型或作答验证超时。");
                break;
            case RESULT:
                if (view.kind == CoursePage.Kind.RESULT) complete(cap, view);
                else if (view.kind == CoursePage.Kind.CATALOG && !taskKey.isEmpty()
                        && view.completedTaskKeys.contains(taskKey)) {
                    mark("课程目录确认任务已完成");
                    taskKey = "";
                    directory(cap, view);
                } else await("未收到明确的提交结果，请查看 U校园是否还在等确认。");
                break;
            case MEDIA:
                if (view.kind == CoursePage.Kind.RESULT || view.mediaEnded) complete(cap, view);
                else if (!previous.equals(view.identity)) end("播放期间页面发生变化。");
                else await("播放器未显示播放完成。");
                break;
            case AUDIO: listen(cap, view); break;
            default: break;
        }
    }

    private void handle(CampusService.Capture cap, CoursePage view) {
        if (view.kind == CoursePage.Kind.CATALOG) {
            directory(cap, view);
        } else if (view.kind == CoursePage.Kind.RESULT) {
            next(cap, view);
        } else if (view.kind.oral()) {
            previous = view.identity;
            phase = Phase.ORAL;
            if (view.kind == CoursePage.Kind.DUBBING || !service.startCourseOral(cap)) {
                end(view.uploadAudio.size() == 1 ? "本题提供音频导入。请在 U校园选择这道题对应的录音后继续。"
                        : "未看到可确认的口语任务和录音入口。");
            }
        } else if (view.kind.textAnswer() && view.compatibleAnswers()) {
            write(view, "");
        } else if (view.kind.mediaQuestions() && view.compatibleAnswers()) {
            if (view.playing || view.play.size() != 1) {
                end("听力材料需要一个可确认、未播放的入口。请停在听力题开始处。");
                return;
            }
            phase = Phase.AUDIO;
            deadline = clock() + 95000;
            audioPage = view.identity;
            audioReady = false;
            audioStarted = false;
            audioStopped = false;
            audioText = "";
            status("准备听力采集。同一次连续处理中只需授权一次。");
            service.startCourseAudio(cap, view.durationSeconds);
            schedule();
        } else if (view.kind == CoursePage.Kind.MEDIA) {
            if (view.mediaEnded) {
                next(cap, view);
            } else if (!view.playing && !service.press(cap, view.play)) {
                end("没有唯一可操作的播放按钮。");
            } else {
                previous = view.identity;
                phase = Phase.MEDIA;
                deadline = clock() + (view.durationSeconds >= 0 ? (view.durationSeconds + 25) * 1000L : 240000);
                status("按教材原速播放，等到页面显示结束再跳转。");
                schedule();
            }
        } else {
            end("识别到「" + view.kind.label + "」。当前页面还没有可验证的处理步骤。");
        }
    }

    void oralFinished(boolean advanced, String reason) {
        if (phase != Phase.ORAL) return;
        if (!advanced) {
            end(reason);
            return;
        }
        CampusService.Capture cap = service.capture();
        if (cap != null && cap.coursePage().kind == CoursePage.Kind.RESULT) {
            complete(cap, cap.coursePage());
        } else {
            phase = Phase.OBSERVE;
            deadline = clock() + 12000;
            status(reason);
            schedule();
        }
    }

    private void directory(CampusService.Capture cap, CoursePage view) {
        if (catalog == null) catalog = new LinkedHashSet<>(view.catalogMarks);
        else if (!catalog.isEmpty() && !catalog.equals(view.catalogMarks)) {
            end("目录或单元发生变化。请在新的课程目录重新启动。");
            return;
        }
        Set<String> keys = new HashSet<>();
        for (CoursePage.Task item : view.tasks) {
            if (!keys.add(item.key)) {
                end("目录出现同名的待完成任务。请打开要处理的任务后重启。");
                return;
            }
        }
        for (CoursePage.Task item : view.tasks) {
            if (attempted.contains(item.key)) continue;
            if (service.press(cap, Collections.singletonList(item.action))) {
                attempted.add(item.key);
                taskKey = item.key;
                navigation(view, "打开待完成任务 " + item.name);
            } else {
                end("目录入口已变化，请确认任务仍可打开。");
            }
            return;
        }
        end(view.tasks.isEmpty() ? "当前目录没有可见的待完成任务。" : "已到当前可见队列末尾。仍标为待完成的项目留在 U校园。");
    }

    private void write(CoursePage view, String transcript) {
        if (!answered.add(taskKey + "\n" + view.identity)) {
            end("返回了同一道题，已暂停重复提交。");
            return;
        }
        previous = view.identity;
        phase = Phase.WRITE;
        deadline = clock() + 110000;
        status(transcript.isEmpty() ? "读取题目并准备答案…" : "听力原文已就绪，正在处理本页…");
        service.solveCourse(transcript);
        schedule();
    }

    void submitted() {
        if (phase != Phase.WRITE) return;
        phase = Phase.RESULT;
        deadline = clock() + 35000;
        status("已点提交，等待 U校园确认。");
        schedule();
    }

    private void complete(CampusService.Capture cap, CoursePage view) {
        mark("U校园已显示完成结果");
        next(cap, view);
    }

    private void mark(String message) {
        confirmed++;
        service.courseEvent(message);
    }

    private void next(CampusService.Capture cap, CoursePage view) {
        if (!results.add(taskKey + "\n" + previous + "\n" + view.identity)) {
            end("返回了同一结果页，已停止重复跳转。");
            return;
        }
        if (view.next.size() == 1 && service.press(cap, view.next)) {
            navigation(view, "已打开下一项，正在识别题型…");
        } else if (view.back.size() == 1 && service.press(cap, view.back)) {
            navigation(view, "正在返回课程目录…");
        } else {
            end("已到结果页。页面没有唯一的下一项或返回目录入口。");
        }
    }

    private void navigation(CoursePage view, String message) {
        previous = view.identity;
        phase = Phase.NAVIGATE;
        deadline = clock() + 15000;
        status(message);
        schedule();
    }

    void audioSignal(String event, String text) {
        if (phase != Phase.AUDIO) return;
        if ("play".equals(event)) {
            audioReady = true;
            deadline = clock() + 11000;
        } else if ("ready".equals(event)) {
            if (!audioStarted || text.isEmpty()) {
                end("未取得当前听力题的有效原文。");
                return;
            }
            audioText = text;
        } else if ("error".equals(event) || "cancel".equals(event)) {
            end("cancel".equals(event) ? "已取消听力采集授权。" : text);
            return;
        }
        signal();
    }

    private void listen(CampusService.Capture cap, CoursePage view) {
        if (!audioPage.equals(view.identity)) {
            end("听力页面已变化，当前转写不会用于下一题。");
            return;
        }
        if (!audioText.isEmpty()) {
            write(view, audioText);
            audioText = "";
            return;
        }
        if (audioReady && !audioStarted && service.press(cap, view.play)) {
            audioStarted = true;
            deadline = clock() + 310000;
            status("正在采集当前听力，等待播放结束和转写。");
        }
        if (audioStarted && !audioStopped && (view.mediaEnded || (view.durationSeconds > 0
                && view.elapsedSeconds >= view.durationSeconds))) {
            audioStopped = true;
            service.finishCourseAudio();
            status("播放已结束，正在转写。");
        }
        await("听力采集或转写等待超时。");
    }

    private void describe(CoursePage view) {
        String current = view.description();
        if (current.equals(description) && view.instruction.equals(prompt)) return;
        description = current;
        prompt = view.instruction;
        service.courseNotice(confirmed, description, prompt, note);
    }

    private void status(String message) {
        note = message;
        service.courseNotice(confirmed, description, prompt, note);
    }

    private void await(String timeout) {
        if (clock() >= deadline) end(timeout);
        else schedule();
    }

    private void schedule() {
        if (phase == Phase.END) return;
        scheduleAfter(POLL);
    }

    private void scheduleAfter(long delay) {
        ui.removeCallbacks(update);
        nextUpdate = clock() + delay;
        ui.postDelayed(update, delay);
    }

    void end(String reason) {
        if (phase == Phase.END) return;
        phase = Phase.END;
        ui.removeCallbacks(update);
        service.endCourse(this, confirmed, reason);
    }

    private static long clock() { return SystemClock.uptimeMillis(); }
}
