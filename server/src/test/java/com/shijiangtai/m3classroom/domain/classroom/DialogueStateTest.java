package com.shijiangtai.m3classroom.domain.classroom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import com.shijiangtai.shared.domain.DomainException;
import java.util.UUID;
import java.util.random.RandomGenerator;
import org.junit.jupiter.api.Test;

class DialogueStateTest {

    private static final UUID DIALOGUE_ID =
            UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID STUDENT_ID =
            UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Test
    void followUpProbabilityIsQuestionRateTimesHalf() {
        assertThat(DialogueState.followUpProbability(0.6)).isCloseTo(0.3, within(1e-9));
        assertThat(DialogueState.followUpProbability(1.0)).isCloseTo(0.5, within(1e-9));
        assertThat(DialogueState.followUpProbability(0.0)).isZero();

        DialogueState dialogue = new DialogueState(DIALOGUE_ID, STUDENT_ID);

        assertThat(dialogue.shouldFollowUp(0.6, new ConstantRandom(0.299))).isTrue();
        assertThat(dialogue.shouldFollowUp(0.6, new ConstantRandom(0.3))).isFalse();
    }

    @Test
    void dialogueStopsAtThreeRounds() {
        DialogueState dialogue = new DialogueState(DIALOGUE_ID, STUDENT_ID);
        assertThat(dialogue.dialogueId()).isEqualTo(DIALOGUE_ID);
        assertThat(dialogue.studentId()).isEqualTo(STUDENT_ID);
        assertThat(dialogue.round()).isEqualTo(1);
        assertThat(dialogue.canFollowUp()).isTrue();

        dialogue.nextRound();
        assertThat(dialogue.round()).isEqualTo(2);
        assertThat(dialogue.canFollowUp()).isTrue();

        dialogue.nextRound();
        assertThat(dialogue.round()).isEqualTo(3);
        assertThat(dialogue.canFollowUp()).isFalse();
        assertThat(dialogue.shouldFollowUp(1.0, new ConstantRandom(0.0))).isFalse();

        assertThatThrownBy(dialogue::nextRound).isInstanceOf(DomainException.class);
        assertThat(dialogue.round()).isEqualTo(3);
    }

    private static final class ConstantRandom implements RandomGenerator {

        private final double value;

        private ConstantRandom(double value) {
            this.value = value;
        }

        @Override
        public long nextLong() {
            return 0L;
        }

        @Override
        public double nextDouble() {
            return value;
        }
    }
}
