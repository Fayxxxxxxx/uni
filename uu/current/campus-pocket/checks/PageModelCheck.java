package dev.campuspocket.app;

import java.util.Arrays;

final class PageModelCheck {
    private static PageModel.Line line(int id, String label) {
        return new PageModel.Line(id, label, id > 0, false, false, false, false);
    }

    private static PageModel.Page read(PageModel.Line... lines) {
        return PageModel.read(Arrays.asList(lines), false);
    }

    private static void check(boolean value, String description) {
        if (!value) throw new AssertionError(description);
    }

    public static void main(String[] args) {
        PageModel.Page page = read(line(0, "Choose the right answer"),
                line(0, "1. Where is Tom?"), line(1, "A."), line(1, "In the library"),
                line(2, "B. At school"), line(0, "2. Fill in the blank."),
                new PageModel.Line(3, "", false, true, false, false, false), line(4, "提交"));
        check(page.exercise && page.groups.size() == 2, "Find the two visible question groups");
        check(page.groups.get(0).choices.get("A").text.equals("A. In the library"), "Join a separated option label");
        check(page.groups.get(1).fields.size() == 1 && page.submit.size() == 1, "Map the text field and submit button");
        check(page.modelInput().contains("id=2"), "Preserve question numbers in the model input");

        PageModel.Page filled = read(line(0, "Choose the right answer"),
                line(0, "1. Where is Tom?"), line(1, "A."), line(1, "In the library"),
                new PageModel.Line(2, "B. At school", true, false, false, true, false),
                line(0, "2. Fill in the blank."),
                new PageModel.Line(3, "", false, true, true, false, false), line(4, "提交"));
        check(page.fingerprint().equals(filled.fingerprint()), "Answer state should not change target identity");

        PageModel.Page ambiguous = read(line(0, "Choose the right answer"),
                line(1, "A. One"), line(2, "B. Two"), line(3, "A. Three"), line(4, "B. Four"));
        check(ambiguous.groups.get(0).ambiguous, "Avoid writing unlabelled duplicate question groups");
        check(read(line(0, "请输入密码"), line(1, "提交")).blocked, "Exclude authentication screens");
        check(!read(line(0, "提交成功"), line(1, "下一题")).exercise, "Recognize the result page");
        check(read(line(0, "提交成功"), line(1, "下一题")).next.size() == 1, "Find next on a result page");
        check(read(line(1, "Unit 2"), line(2, "Exercise 4 未完成")).tasks.size() == 2, "Find visible directory tasks");
        check(read(line(0, "Listen and answer this question."), line(1, "播放")).audioKey()
                .equals(read(line(0, "Listen and answer this question."), line(1, "暂停")).audioKey()),
                "Track the original exercise across play states");
        System.out.println("Passed 10 checks");
    }
}
