package com.shijiangtai.m3classroom.domain.classroom;

import static org.assertj.core.api.Assertions.assertThat;

import com.shijiangtai.m3classroom.domain.classroom.TeacherUtteranceContext.CandidateStudent;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RuleBasedTeacherIntentClassifierTest {

    private static final UUID ZHANG_SAN =
            UUID.fromString("00000000-0000-0000-0000-000000000401");
    private static final UUID LI_SI =
            UUID.fromString("00000000-0000-0000-0000-000000000402");

    private final RuleBasedTeacherIntentClassifier classifier =
            new RuleBasedTeacherIntentClassifier();

    @Test
    void sitDownIsBothEndDialogueAndComment() {
        TeacherUtteranceIntent intent = classifier.classify(context("很好，请坐"));

        assertThat(intent.has(TeacherIntent.END_DIALOGUE)).isTrue();
        assertThat(intent.has(TeacherIntent.COMMENT_ANSWER)).isTrue();
        assertThat(intent.primary()).isEqualTo(TeacherIntent.END_DIALOGUE);
        assertThat(intent.polarity()).isEqualTo("positive");
    }

    @Test
    void callOnMatchesUniqueStudentName() {
        TeacherUtteranceIntent intent = classifier.classify(context("张三，你来说说"));

        assertThat(intent.has(TeacherIntent.CALL_ON)).isTrue();
        assertThat(intent.studentName()).isEqualTo("张三");
        assertThat(intent.confidenceOf(TeacherIntent.CALL_ON)).isGreaterThanOrEqualTo(0.9);
    }

    @Test
    void askStudentQuestionCarriesStudentName() {
        TeacherUtteranceIntent intent =
                classifier.classify(context("张三，光合作用需要什么条件？"));

        assertThat(intent.has(TeacherIntent.ASK_STUDENT)).isTrue();
        assertThat(intent.studentName()).isEqualTo("张三");
    }

    @Test
    void askClassQuestionWithoutNameDoesNotCarryStudent() {
        TeacherUtteranceIntent intent =
                classifier.classify(context("同学们，光合作用需要什么条件？"));

        assertThat(intent.has(TeacherIntent.ASK_CLASS)).isTrue();
        assertThat(intent.studentName()).isNull();
    }

    @Test
    void manageDisciplineCarriesStudentName() {
        TeacherUtteranceIntent intent = classifier.classify(context("张三别说话"));

        assertThat(intent.has(TeacherIntent.MANAGE_DISCIPLINE)).isTrue();
        assertThat(intent.studentName()).isEqualTo("张三");
    }

    @Test
    void endQaPhraseIsDetected() {
        TeacherUtteranceIntent intent = classifier.classify(context("我们继续讲下一部分"));

        assertThat(intent.has(TeacherIntent.END_QA)).isTrue();
        assertThat(intent.primary()).isEqualTo(TeacherIntent.END_QA);
    }

    @Test
    void followUpCueWithoutQuestionMarkAdvancesDialogue() {
        TeacherUtteranceIntent intent = classifier.classify(context("你具体说说"));

        assertThat(intent.has(TeacherIntent.ASK_CLASS)).isTrue();
        assertThat(intent.confidenceOf(TeacherIntent.ASK_CLASS)).isGreaterThanOrEqualTo(0.8);
    }

    @Test
    void promptStudentQuestionWinsOverQuestionIntent() {
        TeacherUtteranceIntent intent = classifier.classify(context("大家有什么问题吗"));

        assertThat(intent.has(TeacherIntent.PROMPT_STUDENT_QUESTION)).isTrue();
        assertThat(intent.primary()).isEqualTo(TeacherIntent.PROMPT_STUDENT_QUESTION);
    }

    @Test
    void ambiguousNamesStillProduceCallOnButNoStudent() {
        TeacherUtteranceIntent intent = classifier.classify(context("张三和李四你来说"));

        assertThat(intent.has(TeacherIntent.CALL_ON)).isTrue();
        assertThat(intent.studentName()).isNull();
        assertThat(intent.confidenceOf(TeacherIntent.CALL_ON)).isLessThan(0.9);
    }

    @Test
    void youLaiWithoutNameUsesCurrentStudent() {
        TeacherUtteranceIntent intent = classifier.classify(new TeacherUtteranceContext(
                "你来说", "qa", "waiting_hands", null, ZHANG_SAN, candidates()));

        assertThat(intent.has(TeacherIntent.ASK_STUDENT)).isTrue();
        assertThat(intent.studentName()).isNull();
    }

    @Test
    void emptyTextIsOther() {
        TeacherUtteranceIntent intent = classifier.classify(context("   "));

        assertThat(intent.primary()).isEqualTo(TeacherIntent.OTHER);
        assertThat(intent.hits()).hasSize(1);
    }

    private static TeacherUtteranceContext context(String text) {
        return new TeacherUtteranceContext(
                text, "qa", "dialogue", "teacher", null, candidates());
    }

    private static List<CandidateStudent> candidates() {
        return List.of(
                new CandidateStudent(ZHANG_SAN, "张三"),
                new CandidateStudent(LI_SI, "李四"));
    }
}
