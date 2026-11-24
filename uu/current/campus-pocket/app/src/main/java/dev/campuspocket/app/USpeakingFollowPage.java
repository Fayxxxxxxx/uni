package dev.campuspocket.app;

import java.util.List;

final class USpeakingFollowPage {
    private static final USpeakingEngine ENGINE = new USpeakingEngine();

    final SpeakingCards cards;
    final USpeakingEngine.Snapshot state;
    final String sentence;

    private USpeakingFollowPage(SpeakingCards cards, USpeakingEngine.Snapshot state) {
        this.cards = cards;
        this.state = state;
        sentence = cards.current.text;
    }

    static USpeakingFollowPage read(CoursePage page, List<PageModel.Line> lines) {
        if (page.page.reading == null || page.page.reading.cards == null) return null;
        SpeakingCards cards = page.page.reading.cards;
        if (cards.current == null || cards.ambiguous(cards.current.text)) return null;
        USpeakingEngine.Snapshot state = ENGINE.read(page, lines, false, "");
        if (state.protectedPage || state.screen != USpeakingEngine.Screen.EXAMPLE || state.step != 2) return null;
        return new USpeakingFollowPage(cards, state);
    }

    PageModel.Line microphone() {
        if (state.issue != USpeakingEngine.Issue.NONE || state.recording == USpeakingEngine.Media.RECORDING
                || state.recording == USpeakingEngine.Media.PREPARING || state.recording == USpeakingEngine.Media.SAVING
                || state.recording == USpeakingEngine.Media.SCORING) return null;
        if (!scored() && cards.start.size() == 1) return cards.start.get(0);
        if (scored() && !positiveScore() && cards.redo.size() == 1) return cards.redo.get(0);
        return null;
    }

    boolean recording() {
        return state.recording == USpeakingEngine.Media.RECORDING && cards.stop.size() == 1;
    }

    boolean scored() {
        return state.issue == USpeakingEngine.Issue.NONE && state.recording == USpeakingEngine.Media.SCORED
                && state.score.matches("\\d{1,3}(?:\\.\\d+)?");
    }

    boolean positiveScore() { return scored() && Double.parseDouble(state.score) > 0; }

    static PageModel.Bounds microphoneIcon(PageModel.Line label, List<MicBox> boxes) {
        if (label.parent < 0 || label.bounds == null) return null;
        PageModel.Bounds caption = label.bounds;
        int captionHeight = caption.bottom - caption.top;
        PageModel.Bounds match = null;
        for (MicBox box : boxes) {
            PageModel.Bounds b = box.bounds;
            if (box.parent != label.parent || b.bottom > caption.top
                    || caption.top - b.bottom > captionHeight || Math.abs(b.x() - caption.x()) > captionHeight / 2) continue;
            int height = b.bottom - b.top, width = b.right - b.left;
            if (height < captionHeight || width < height * 0.75 || width > height * 1.25) continue;
            if (match != null) return null;
            match = b;
        }
        return match;
    }

    static final class MicBox {
        final int parent;
        final PageModel.Bounds bounds;

        MicBox(int parent, PageModel.Bounds bounds) {
            this.parent = parent;
            this.bounds = bounds;
        }
    }
}
