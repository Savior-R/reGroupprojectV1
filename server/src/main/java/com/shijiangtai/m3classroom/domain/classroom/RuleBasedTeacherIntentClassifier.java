package com.shijiangtai.m3classroom.domain.classroom;

import com.shijiangtai.m3classroom.domain.classroom.TeacherUtteranceContext.CandidateStudent;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Deterministic first-version teacher-utterance classifier.
 *
 * <p>This is deliberately a rules layer, not a trained model: it gives the dialogue state
 * machine a stable, testable signal while the training-data pipeline is built. The rules cover
 * the high-precision phrases used by the classroom UI and the first A-phase scenarios; a local
 * model can replace this class through {@link TeacherIntentClassifier} later.
 *
 * <p>Student names are matched against the task roster instead of being extracted from the
 * model. A name is only accepted when exactly one candidate matches; ambiguous matches leave
 * {@link TeacherUtteranceIntent#studentName()} null so the caller can ask the teacher to
 * confirm instead of calling on the wrong student.
 */
public final class RuleBasedTeacherIntentClassifier implements TeacherIntentClassifier {

    public static final String MODEL_VERSION = "teacher-intent-rules-v1";

    private static final Pattern SEPARATORS = Pattern.compile(
            "[\\s\\p{Punct}，。！？、；：\"'“”‘’（）()【】《》〈〉…—·]+");

    private static final List<String> END_DIALOGUE_PHRASES = List.of(
            "坐下", "请坐", "坐吧", "先坐", "可以坐", "回到座位");
    private static final List<String> END_QA_PHRASES = List.of(
            "结束问答", "问答结束", "开始讲课", "继续讲课", "我们继续讲",
            "继续上课", "我们讲下一");
    private static final List<String> MANAGE_PHRASES = List.of(
            "别说话", "不要说话", "不要讲话", "安静", "注意听", "认真听",
            "认真点", "不要玩手机", "站起来", "坐好", "专心", "回到座位");
    private static final List<String> PROMPT_STUDENT_QUESTION_PHRASES = List.of(
            "有什么问题", "有问题吗", "谁有问题", "可以提问", "大家提问",
            "有什么疑问", "提问吧", "有问题可以问");
    private static final List<String> COMMENT_PHRASES = List.of(
            "很好", "不错", "正确", "不对", "错误", "不完整", "再想想",
            "有道理", "说得好", "说得很好", "谢谢", "请坐", "可以了");
    private static final List<String> POSITIVE_PHRASES = List.of(
            "很好", "不错", "正确", "有道理", "说得好", "说得很好", "谢谢", "请坐");
    private static final List<String> NEGATIVE_PHRASES = List.of(
            "不对", "错误", "不完整", "再想想");
    private static final List<String> CALL_ON_PHRASES = List.of(
            "你来说", "你说", "你回答", "你来答", "你来说说", "你试试", "你来",
            "请回答", "你讲", "你读", "你解释", "你来说一下");
    private static final List<String> QUESTION_WORDS = List.of(
            "吗", "呢", "什么", "为什么", "怎么", "怎样", "哪", "谁", "多少",
            "几", "是不是", "对不对", "有没有", "能不能", "可不可以", "是否");
    private static final List<String> FOLLOW_UP_PHRASES = List.of(
            "再说说", "继续说", "然后呢", "还有呢", "具体说说", "举个例子", "展开说说");
    private static final List<String> CLASS_WORDS = List.of(
            "同学", "大家", "各位", "谁", "哪位");

    private static final List<TeacherIntent> PRECEDENCE = List.of(
            TeacherIntent.END_QA,
            TeacherIntent.END_DIALOGUE,
            TeacherIntent.MANAGE_DISCIPLINE,
            TeacherIntent.CALL_ON,
            TeacherIntent.PROMPT_STUDENT_QUESTION,
            TeacherIntent.ASK_STUDENT,
            TeacherIntent.ASK_CLASS,
            TeacherIntent.COMMENT_ANSWER,
            TeacherIntent.OTHER);

    @Override
    public TeacherUtteranceIntent classify(TeacherUtteranceContext context) {
        Objects.requireNonNull(context, "context");
        String raw = context.text() == null ? "" : context.text().trim();
        if (raw.isBlank()) {
            return other();
        }
        String normalized = normalize(raw);
        NameMatch name = matchName(normalized, context.candidates());

        List<TeacherIntentHit> hits = new ArrayList<>();
        if (containsAny(normalized, END_DIALOGUE_PHRASES)) {
            addHit(hits, TeacherIntent.END_DIALOGUE, 0.95);
        }
        if (containsAny(normalized, END_QA_PHRASES)) {
            addHit(hits, TeacherIntent.END_QA, 0.90);
        }
        if (containsAny(normalized, MANAGE_PHRASES)) {
            addHit(hits, TeacherIntent.MANAGE_DISCIPLINE, 0.90);
        }
        if (containsAny(normalized, PROMPT_STUDENT_QUESTION_PHRASES)) {
            addHit(hits, TeacherIntent.PROMPT_STUDENT_QUESTION, 0.90);
        }
        if (containsAny(normalized, COMMENT_PHRASES)) {
            addHit(hits, TeacherIntent.COMMENT_ANSWER, 0.85);
        }

        boolean question = raw.contains("？") || raw.contains("?")
                || containsAny(normalized, QUESTION_WORDS);
        if (containsAny(normalized, FOLLOW_UP_PHRASES)) {
            addHit(hits, TeacherIntent.ASK_CLASS, 0.80);
        }
        if (question && name.matched()) {
            addHit(hits, TeacherIntent.ASK_STUDENT, name.name() == null ? 0.75 : 0.85);
        } else if (question) {
            addHit(hits, TeacherIntent.ASK_CLASS,
                    containsAny(normalized, CLASS_WORDS) ? 0.85 : 0.75);
        }

        if (name.matched() && containsAny(normalized, CALL_ON_PHRASES)) {
            addHit(hits, TeacherIntent.CALL_ON, name.name() == null ? 0.75 : 0.90);
        } else if (!name.matched()
                && context.currentStudentId() != null
                && containsAny(normalized, CALL_ON_PHRASES)) {
            // The teacher says "你来说" to the student who is already the current focus.
            addHit(hits, TeacherIntent.ASK_STUDENT, 0.80);
        }

        if (hits.isEmpty()) {
            return other();
        }
        TeacherIntent primary = primary(hits);
        double primaryConfidence = hits.stream()
                .filter(hit -> hit.intent() == primary)
                .mapToDouble(TeacherIntentHit::confidence)
                .max()
                .orElse(0.0);
        String polarity = containsAny(normalized, COMMENT_PHRASES)
                ? polarity(normalized) : null;
        return new TeacherUtteranceIntent(
                hits,
                primary,
                primaryConfidence,
                name.name(),
                polarity,
                MODEL_VERSION);
    }

    private static TeacherUtteranceIntent other() {
        return new TeacherUtteranceIntent(
                List.of(new TeacherIntentHit(TeacherIntent.OTHER, 0.50)),
                TeacherIntent.OTHER,
                0.50,
                null,
                null,
                MODEL_VERSION);
    }

    private static TeacherIntent primary(List<TeacherIntentHit> hits) {
        for (TeacherIntent candidate : PRECEDENCE) {
            if (hits.stream().anyMatch(hit -> hit.intent() == candidate)) {
                return candidate;
            }
        }
        return TeacherIntent.OTHER;
    }

    private static void addHit(
            List<TeacherIntentHit> hits, TeacherIntent intent, double confidence) {
        for (int index = 0; index < hits.size(); index++) {
            TeacherIntentHit current = hits.get(index);
            if (current.intent() == intent) {
                if (confidence > current.confidence()) {
                    hits.set(index, new TeacherIntentHit(intent, confidence));
                }
                return;
            }
        }
        hits.add(new TeacherIntentHit(intent, confidence));
    }

    private static String polarity(String normalized) {
        if (containsAny(normalized, POSITIVE_PHRASES)) {
            return "positive";
        }
        if (containsAny(normalized, NEGATIVE_PHRASES)) {
            return "negative";
        }
        return "neutral";
    }

    private static NameMatch matchName(String normalized, List<CandidateStudent> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return NameMatch.none();
        }
        NameMatch best = NameMatch.none();
        boolean ambiguous = false;
        for (CandidateStudent candidate : candidates) {
            String candidateName = normalize(candidate.name());
            if (candidateName.isEmpty() || !normalized.contains(candidateName)) {
                continue;
            }
            if (candidateName.length() > best.length()) {
                best = new NameMatch(true, candidate.name(), candidateName);
                ambiguous = false;
            } else if (candidateName.length() == best.length()
                    && !candidateName.equals(best.normalizedName())) {
                ambiguous = true;
            }
        }
        if (!best.matched()) {
            return NameMatch.none();
        }
        return ambiguous
                ? new NameMatch(true, null, best.normalizedName(), best.length())
                : best;
    }

    private static boolean containsAny(String text, List<String> phrases) {
        return phrases.stream().anyMatch(text::contains);
    }

    private static String normalize(String text) {
        return SEPARATORS.matcher(text).replaceAll("");
    }

    private record NameMatch(boolean matched, String name, String normalizedName, int length) {

        private static NameMatch none() {
            return new NameMatch(false, null, null, 0);
        }

        private NameMatch(boolean matched, String name, String normalizedName) {
            this(matched, name, normalizedName, normalizedName.length());
        }
    }
}
