package dev.campuspocket.app;
class TrainingModel {
    static final int DIALOGUE_WORDS = 18, FREE_WORDS = 60;
    static String input;
    static String speak(Store.Config config, boolean dialogue, String input, int words, java.util.function.Consumer<String> retry) { return speak(config, dialogue, input, words); }
    static String speak(Store.Config config, boolean dialogue, String input, int words) {
        TrainingModel.input = input;
        return "I think we should go to the science museum because we can learn together and try the exhibits.";
    }
    static String cloze(Store.Config config, TrainingPage page, String example) { return "Example"; }
    static String cloze(Store.Config config, TrainingPage page, String example, java.util.function.Consumer<String> retry) { return cloze(config, page, example); }
}
