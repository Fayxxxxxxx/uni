package dev.campuspocket.app;

final class TrainingScoreCheck {
    private static void check(boolean actual, String message) {
        if (!actual) throw new AssertionError(message);
    }

    public static void main(String[] args) {
        TrainingScore policy = new TrainingScore(75);
        check(policy.meets("75"), "Scores at the minimum pass");
        check(policy.meets("15 / 20"), "Normalize the reported scale");
        check(!policy.meets("74.9"), "Fractional scores below the minimum do not pass");
        check(!policy.complete("sentence", "50"), "An existing low score is not a new attempt");
        check(policy.record("sentence") == 1 && !policy.complete("sentence", "50"),
                "A low first score requests one retry");
        check(policy.record("sentence") == 2 && policy.complete("sentence", "0"),
                "A second confirmed score advances, including zero");
        check(!policy.complete("next", "50"), "Attempts are counted separately for the next card");
        policy.observeExisting("prior", "74");
        policy.observeExisting("prior", "74");
        check(!policy.complete("prior", "74") && policy.record("prior") == 2 && policy.complete("prior", "73"),
                "A verified earlier score counts as the first attempt and allows exactly one retest");
        TrainingScore zero = new TrainingScore(0);
        check(zero.meets("0") && !zero.meets("") && !zero.meets("0/0"), "Distinguish zero from missing or invalid data");
        System.out.println("Passed 9 threshold and retry checks");
    }
}
