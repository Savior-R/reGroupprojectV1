package com.shijiangtai.m3classroom.infrastructure.perception;

import com.shijiangtai.m3classroom.domain.classroom.TeacherIntentClassifier;
import com.shijiangtai.m3classroom.domain.classroom.TeacherUtteranceContext;
import com.shijiangtai.m3classroom.domain.classroom.TeacherUtteranceIntent;
import java.util.Objects;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Shadow deployment: the rules still drive the classroom, while the local model's prediction is
 * logged for comparison and offline evaluation. A model failure is logged and never changes the
 * rules result.
 *
 * <p>Logs contain the teacher utterance text because that is what an annotator needs when
 * building the golden set; treat the application log as classroom data and apply the same access
 * / retention rules as session transcripts.
 */
public final class ShadowTeacherIntentClassifier implements TeacherIntentClassifier {

    private static final Logger LOG =
            LoggerFactory.getLogger(ShadowTeacherIntentClassifier.class);

    private final TeacherIntentClassifier model;
    private final TeacherIntentClassifier rules;

    public ShadowTeacherIntentClassifier(
            TeacherIntentClassifier model, TeacherIntentClassifier rules) {
        this.model = Objects.requireNonNull(model, "model");
        this.rules = Objects.requireNonNull(rules, "rules");
    }

    @Override
    public TeacherUtteranceIntent classify(TeacherUtteranceContext context) {
        TeacherUtteranceIntent rulesResult = rules.classify(context);
        try {
            TeacherUtteranceIntent modelResult = model.classify(context);
            LOG.info("teacher.intent.shadow text=\"{}\" model={} model_confidence={} "
                            + "model_intents=[{}] rules={} rules_confidence={}",
                    context.text(),
                    modelResult.primary().wireValue(),
                    modelResult.confidence(),
                    modelResult.hits().stream()
                            .map(hit -> hit.intent().wireValue() + ":" + hit.confidence())
                            .collect(Collectors.joining(",")),
                    rulesResult.primary().wireValue(),
                    rulesResult.confidence());
        } catch (RuntimeException exception) {
            LOG.warn("Shadow teacher-intent model failed; keeping rules result", exception);
        }
        return rulesResult;
    }
}
