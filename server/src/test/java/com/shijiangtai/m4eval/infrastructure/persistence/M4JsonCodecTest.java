package com.shijiangtai.m4eval.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.shijiangtai.m4eval.domain.EvaluationErrorCode;
import com.shijiangtai.m4eval.domain.EvaluationException;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** JSONB column codec used by every M4 JDBC adapter (06 §8). */
class M4JsonCodecTest {

    private final M4JsonCodec codec = new M4JsonCodec(JsonMapper.builder().build());

    @Test
    void nullIsWrittenAsAnEmptyJsonObject() {
        assertThat(codec.write(null)).isEqualTo("{}");
    }

    @Test
    void mapsRoundTripThroughJsonb() {
        String json = codec.write(Map.of(
                "weights", Map.of("content", 30),
                "flags", Map.of("transcript_complete", true)));

        Map<String, Object> read = codec.readMap(json);

        assertThat(read).containsKeys("weights", "flags");
        assertThat(read.get("flags")).isInstanceOf(Map.class);
    }

    @Test
    void blankColumnsReadAsEmptyMaps() {
        assertThat(codec.readMap(null)).isEmpty();
        assertThat(codec.readMap("  ")).isEmpty();
        assertThat(codec.readStringList(null)).isEmpty();
    }

    @Test
    void malformedJsonUsesTheInvalidResultCode() {
        assertThatThrownBy(() -> codec.readMap("{"))
                .isInstanceOfSatisfying(EvaluationException.class, exception ->
                        assertThat(exception.code())
                                .isEqualTo(EvaluationErrorCode.EVALUATION_INVALID_RESULT));
        assertThatThrownBy(() -> codec.readStringList("[oops"))
                .isInstanceOf(EvaluationException.class);
    }

    @Test
    void intMapKeepsNumbersAndIgnoresOtherValues() {
        Map<String, Integer> weights = codec.readIntMap(
                "{\"content\":30,\"expression\":20.0,\"board\":null,\"time\":\"15\"}");

        assertThat(weights)
                .containsEntry("content", 30)
                .containsEntry("expression", 20)
                .doesNotContainKeys("board", "time");
    }

    @Test
    void decimalMapKeepsTheWrittenPrecision() {
        String json = codec.write(Map.of(
                "content", new BigDecimal("82.5"),
                "expression", new BigDecimal("80.0")));

        Map<String, BigDecimal> scores = codec.readDecimalMap(json);

        assertThat(scores.get("content")).isEqualByComparingTo("82.5");
        assertThat(scores.get("expression")).isEqualByComparingTo("80.0");
    }

    @Test
    void stringListSkipsNullElements() {
        assertThat(codec.readStringList("[\"AUDIO_MISSING\",null,\"FRAMES_MISSING\"]"))
                .containsExactly("AUDIO_MISSING", "FRAMES_MISSING");
        assertThat(codec.readStringList("[]")).isEqualTo(List.of());
    }
}
