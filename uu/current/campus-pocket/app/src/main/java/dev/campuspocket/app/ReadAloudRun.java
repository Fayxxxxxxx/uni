package dev.campuspocket.app;

import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.SystemClock;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;

import java.io.File;
import java.io.FileInputStream;
import java.util.Locale;

final class ReadAloudRun {
    enum Outcome { STOPPED, TIMED_OUT, RECORDED, COMPLETE }

    private static final String UTTERANCE = "campus-read-aloud";
    private static final long POLL_MS = 650;
    private final CampusService service;
    private final Handler ui;
    private final String task, packageName;
    final int window;
    private final boolean autoSubmit;
    private final boolean openQuestion, cards;
    private final Store.Config config;
    private final Store.Voice voice;
    private final USpeakingFollow observedFollow;
    private String previousScore = "";
    private boolean scoringObserved, timeoutDismissed;
    private int touchAttempts, micAttempts;
    private long lastStopAttempt, nextMicAttempt, scoreDisplayedAt;
    private final Runnable initTimeout = () -> failed("语音准备超时，尚未打开 U校园录音。");
    private TextToSpeech speaker;
    private MediaPlayer player;
    private File audioFile;
    private String words;
    private boolean active = true, ownsRecording, speaking;
    private boolean confirmedSubmit;
    private long deadline;

    ReadAloudRun(CampusService service, Handler ui, CampusService.Capture initial, boolean autoSubmit,
                 Store.Config config, Store.Voice voice) {
        this(service, ui, initial, autoSubmit, config, voice, null);
    }

    ReadAloudRun(CampusService service, Handler ui, CampusService.Capture initial, boolean autoSubmit,
                 Store.Config config, Store.Voice voice, USpeakingFollow observedFollow) {
        this.service = service;
        this.ui = ui;
        task = initial.page.reading.target();
        openQuestion = !initial.page.reading.question.isEmpty();
        cards = initial.page.reading.cards != null;
        packageName = initial.packageName;
        window = initial.window;
        this.autoSubmit = autoSubmit;
        this.config = config;
        this.voice = voice;
        this.observedFollow = observedFollow;
        if (observedFollow != null) {
            USpeakingFollowPage page = observedFollow.page(initial);
            if (page != null && page.scored()) previousScore = page.state.score;
        }
    }

    void start() {
        AudioManager audio = service.getSystemService(AudioManager.class);
        if (audio != null && audio.getStreamVolume(AudioManager.STREAM_MUSIC) == 0) {
            failed("手机媒体音量为零。调高音量并使用手机扬声器后再启动朗读。");
            return;
        }
        if (openQuestion && !config.ready()) {
            failed("识别到口语问答。请配置文本模型后再生成回答和录音。");
            return;
        }
        if (!voice.id.isEmpty() && voice.key.isEmpty()) {
            failed("个人音色的 API Key 无法读取，尚未开启录音。");
            return;
        }
        if (!openQuestion && voice.id.isEmpty()) {
            words = task;
            systemSpeaker();
            return;
        }
        ui.postDelayed(initTimeout, 180000);
        ModelClient.WORK.execute(() -> {
            File file = null;
            try {
                String answer = openQuestion ? ModelClient.oralAnswer(config, task) : task;
                if (!voice.id.isEmpty()) {
                    file = File.createTempFile("follow-along-", ".wav", service.getCacheDir());
                    VoiceClient.render(voice, answer, file);
                }
                File prepared = file;
                ui.post(() -> {
                    if (!active) {
                        if (prepared != null) prepared.delete();
                        return;
                    }
                    ui.removeCallbacks(initTimeout);
                    words = answer;
                    if (prepared == null) systemSpeaker();
                    else preparePlayer(prepared);
                });
            } catch (Exception error) {
                if (file != null) file.delete();
                ui.post(() -> failed(ModelClient.message(error)));
            }
        });
    }

    private void systemSpeaker() {
        ui.postDelayed(initTimeout, 12000);
        try {
            speaker = new TextToSpeech(service, status -> ui.post(() -> configure(status)));
        } catch (RuntimeException e) {
            failed("无法打开系统文字转语音。");
        }
    }

    private void preparePlayer(File file) {
        audioFile = file;
        try {
            player = new MediaPlayer();
            player.setAudioAttributes(speechAttributes());
            try (FileInputStream input = new FileInputStream(file)) {
                player.setDataSource(input.getFD());
            }
            player.setOnPreparedListener(ready -> beginRecording());
            player.setOnCompletionListener(done -> ui.postDelayed(this::stopAfterSpeech, 450));
            player.setOnErrorListener((failed, what, extra) -> {
                failed("个人音色音频播放失败。");
                return true;
            });
            ui.postDelayed(initTimeout, 10000);
            player.prepareAsync();
        } catch (Exception e) {
            failed("个人音色音频无法打开，尚未开启录音。");
        }
    }

    private static AudioAttributes speechAttributes() {
        return new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build();
    }

    private void configure(int status) {
        if (!active) return;
        ui.removeCallbacks(initTimeout);
        if (status != TextToSpeech.SUCCESS || speaker == null || speaker.setLanguage(Locale.US) < 0) {
            failed("请在系统文字转语音设置中安装英文语音。");
            return;
        }
        speaker.setAudioAttributes(speechAttributes());
        speaker.setSpeechRate(observedFollow == null ? 0.94f : new Store(service).trainingSpeed());
        speaker.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override public void onStart(String id) {}

            @Override public void onDone(String id) {
                if (UTTERANCE.equals(id)) ui.postDelayed(ReadAloudRun.this::stopAfterSpeech, 450);
            }

            @Override public void onError(String id) {
                if (UTTERANCE.equals(id)) ui.post(() -> failed("手机英语语音播放失败，已停止自动处理。"));
            }
        });
        beginRecording();
    }

    private void beginRecording() {
        if (!active) return;
        ui.removeCallbacks(initTimeout);
        CampusService.Capture current = sameTask();
        if (observedFollow != null) {
            tryObservedMicrophone(current);
        } else if (current == null || current.page.result || current.page.reading.saved
                || !current.page.reading.stop.isEmpty() || !service.oralPress(current, current.page.reading.start)) {
            failed("原题或录音按钮发生变化，请在朗读题页面重新启动。");
            return;
        }
        ownsRecording = true;
        progress("正在等待 U校园开始录音…");
        deadline = now() + 12000;
        ui.postDelayed(this::waitForRecording, 450);
    }

    private void waitForRecording() {
        if (!active) return;
        CampusService.Capture cap = sameTask();
        if (recording(cap)) {
            progress(openQuestion ? "录音已开始，正在播放生成的回答…"
                    : "录音已开始，正在读当前句…");
            ui.postDelayed(this::play, 330);
        } else if (now() < deadline) {
            if (observedFollow != null) tryObservedMicrophone(cap);
            ui.postDelayed(this::waitForRecording, POLL_MS);
        } else {
            failed("未看到 U校园进入录音。请检查麦克风权限或页面上的录音提示。");
        }
    }

    private void tryObservedMicrophone(CampusService.Capture cap) {
        if (cap == null || micAttempts >= USpeakingTraining.MIC_ATTEMPTS || now() < nextMicAttempt
                || new TrainingPage(cap.coursePage(), cap.lines).microphone() == null) return;
        observedFollow.startRecording(task, micAttempts++ > 0);
        nextMicAttempt = now() + USpeakingTraining.MIC_RETRY_MS;
    }

    private void play() {
        if (!active) return;
        CampusService.Capture cap = sameTask();
        if (!recording(cap)) {
            failed("录音状态已变化，已停止朗读。");
            return;
        }
        speaking = true;
        try {
            if (player != null) player.start();
            else if (speaker.speak(words, TextToSpeech.QUEUE_FLUSH, null, UTTERANCE) != TextToSpeech.SUCCESS) {
                failed("手机未能开始朗读，请检查系统文字转语音设置。");
                return;
            }
        } catch (RuntimeException error) {
            failed("音频未能开始播放。");
            return;
        }
    }

    private void stopAfterSpeech() {
        if (!active || !speaking) return;
        speaking = false;
        if (observedFollow != null) {
            progress("朗读已结束，正在确认 U校园停止录音…");
            deadline = now() + 25000;
            waitForSaved();
            return;
        }
        CampusService.Capture cap = sameTask();
        if (recording(cap) && !service.oralPress(cap, cap.page.reading.stop)) {
            failed("朗读已结束，请在 U校园检查录音是否已停止。");
            return;
        }
        ownsRecording = false;
        progress("朗读已结束，正在等待 U校园评分…");
        deadline = now() + 25000;
        ui.postDelayed(this::waitForSaved, POLL_MS);
    }

    private void waitForSaved() {
        if (!active) return;
        CampusService.Capture result = service.capture();
        if (observedFollow != null) {
            USpeakingFollowPage page = observedFollow.page(result);
            if (page != null && task.equals(page.sentence)
                    && page.state.recording == USpeakingEngine.Media.RECORDING) {
                if (touchAttempts >= 3 && now() - lastStopAttempt > 2500) {
                    failed("语音已播完，但 U校园未确认停止录音。");
                    return;
                }
                if (touchAttempts < 3 && (lastStopAttempt == 0 || now() - lastStopAttempt >= 1500)) {
                    CampusService.Capture fresh = service.capture(true);
                    USpeakingFollowPage current = observedFollow.page(fresh);
                    if (current != null && task.equals(current.sentence)
                            && current.state.recording == USpeakingEngine.Media.RECORDING) {
                        service.trainingStop(fresh, new TrainingPage(fresh.coursePage(), fresh.lines), touchAttempts++ > 0);
                        lastStopAttempt = now();
                    }
                }
            }
            if (page != null && task.equals(page.sentence)
                    && (page.state.recording == USpeakingEngine.Media.SCORING
                        || page.state.recording == USpeakingEngine.Media.SAVING)) scoringObserved = true;
            if (page != null && task.equals(page.sentence) && page.scored() && scoreDisplayedAt == 0) {
                scoreDisplayedAt = now();
            }
            if (page != null && task.equals(page.sentence) && page.scored()
                    && (scoringObserved || !page.state.score.equals(previousScore)
                        || touchAttempts > 0 && now() - scoreDisplayedAt >= 600)) {
                finish(Outcome.RECORDED, "U校园已显示评分。");
            } else if (page != null && task.equals(page.sentence) && page.state.recordingTimedOut()
                    && page.state.recording != USpeakingEngine.Media.RECORDING) {
                TrainingPage current = new TrainingPage(result.coursePage(), result.lines);
                PageModel.Line dismiss = current.action("我知道了", "确定", "知道了");
                if (!timeoutDismissed && dismiss != null && service.trainingClick(result, dismiss)) {
                    timeoutDismissed = true;
                    ui.postDelayed(this::waitForSaved, POLL_MS);
                } else {
                    finish(Outcome.TIMED_OUT, "U校园已结束本句录音，继续下一句。");
                }
            } else if (page != null && task.equals(page.sentence) && timeoutDismissed
                    && !scoringObserved && !page.scored() && !recording(result)) {
                finish(Outcome.TIMED_OUT, "U校园已结束本句录音，继续下一句。");
            } else if (page != null && page.state.issue != USpeakingEngine.Issue.NONE
                    && !page.state.recordingTimedOut()) {
                failed(page.state.issueText);
            } else if (now() < deadline) {
                ui.postDelayed(this::waitForSaved, POLL_MS);
            } else {
                failed("暂未看到当前句的评分，逐句跟读已暂停。");
            }
            return;
        }
        CampusService.Capture cap = sameTask(result) ? result : null;
        if (cap != null && (cap.page.reading.saved || cap.page.reading.graded) && cap.page.reading.stop.isEmpty()) {
            if (!autoSubmit && (cards || !cap.page.result)) {
                finish(Outcome.RECORDED, "U校园已保存录音。可在题目页试听并提交。");
                return;
            }
            if (autoSubmit && cap.page.reading.submit.size() == 1) {
                if (service.press(cap, cap.page.reading.submit)) {
                    service.readProgress(this, "已点提交，正在等待 U校园确认…");
                    deadline = now() + 18000;
                    ui.postDelayed(this::waitForResult, POLL_MS);
                } else {
                    finish(Outcome.RECORDED, "U校园已保存录音。请在题目页提交。");
                }
                return;
            }
        }
        if (!cards && sameWindow(result) && !result.page.blocked && result.page.result
                && result.page.reading != null && result.page.reading.stop.isEmpty()
                && result.page.reading.submit.isEmpty()) {
            finish(Outcome.COMPLETE, "U校园已显示口语结果。");
            return;
        }
        if (now() < deadline) {
            ui.postDelayed(this::waitForSaved, POLL_MS);
        } else if (cap != null && cap.page.reading.saved) {
            finish(Outcome.RECORDED, "U校园已保存录音。请按当前页面提示提交。");
        } else {
            finish(Outcome.STOPPED, "朗读已结束。请在 U校园核对录音或等候评分。");
        }
    }

    private void waitForResult() {
        if (!active) return;
        CampusService.Capture cap = service.capture();
        if (cap != null && packageName.equals(cap.packageName) && !cap.page.blocked && cap.page.reading != null
                && cap.page.result && cap.page.reading.submit.isEmpty() && cap.page.reading.confirm.isEmpty()) {
            finish(Outcome.COMPLETE, "U校园已显示朗读提交结果。");
        } else if (!confirmedSubmit && cap != null && packageName.equals(cap.packageName)
                && cap.page.reading != null && service.press(cap, cap.page.reading.confirm)) {
            confirmedSubmit = true;
            deadline = now() + 18000;
            ui.postDelayed(this::waitForResult, POLL_MS);
        } else if (now() < deadline) {
            ui.postDelayed(this::waitForResult, POLL_MS);
        } else {
            finish(Outcome.RECORDED, "已点击提交。请在 U校园查看是否还需确认。");
        }
    }

    void cancel(boolean stopRecording) {
        if (!active) return;
        close();
        if (ownsRecording && stopRecording) {
            CampusService.Capture cap = sameTask();
            if (cap != null && !cap.page.result) service.oralPress(cap, cap.page.reading.stop);
        }
        ownsRecording = false;
    }

    private void failed(String text) {
        if (!active) return;
        cancel(observedFollow == null);
        report(Outcome.STOPPED, text);
    }

    private void finish(Outcome outcome, String text) {
        close();
        report(outcome, text);
    }

    private void report(Outcome outcome, String text) {
        if (observedFollow == null) service.readFinished(this, outcome, text);
        else observedFollow.recorded(outcome, text);
    }

    private void progress(String text) {
        if (observedFollow == null) service.readProgress(this, text);
        else observedFollow.progress(text);
    }

    private boolean recording(CampusService.Capture cap) {
        if (observedFollow == null) return cap != null && !cap.page.result && cap.page.reading.stop.size() == 1;
        USpeakingFollowPage page = observedFollow.page(cap);
        return page != null && task.equals(page.sentence) && new TrainingPage(cap.coursePage(), cap.lines).recording();
    }

    private void close() {
        active = false;
        speaking = false;
        ui.removeCallbacks(initTimeout);
        if (speaker != null) {
            speaker.stop();
            speaker.shutdown();
            speaker = null;
        }
        if (player != null) {
            player.release();
            player = null;
        }
        if (audioFile != null) {
            audioFile.delete();
            audioFile = null;
        }
    }

    private CampusService.Capture sameTask() {
        CampusService.Capture cap = service.capture();
        return sameTask(cap) ? cap : null;
    }

    private boolean sameTask(CampusService.Capture cap) {
        return sameWindow(cap) && cap.page.reading != null && task.equals(cap.page.reading.target());
    }

    private boolean sameWindow(CampusService.Capture cap) {
        return cap != null && window == cap.window && packageName.equals(cap.packageName);
    }

    private static long now() { return SystemClock.uptimeMillis(); }
}
