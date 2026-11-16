package com.shijiangtai.m4eval.application;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Input completeness flags captured in {@code input_snapshots.flags} (06 §4.5). */
public final class InputFlags {

    public static final String TRANSCRIPT_COMPLETE = "transcript_complete";
    public static final String AUDIO_COMPLETE = "audio_complete";
    public static final String FRAMES_COMPLETE = "frames_complete";
    public static final String EVENTS_COMPLETE = "events_complete";
    public static final String REFERENCE_COMPLETE = "reference_complete";
    public static final String ANSWER_POINTS_COMPLETE = "answer_points_complete";
    public static final String MISSING = "missing";

    private InputFlags() {
    }

    public static Map<String, Object> compute(
            boolean transcript,
            boolean audio,
            boolean frames,
            boolean events,
            boolean reference,
            boolean answerPoints) {
        LinkedHashMap<String, Object> flags = new LinkedHashMap<>();
        flags.put(TRANSCRIPT_COMPLETE, transcript);
        flags.put(AUDIO_COMPLETE, audio);
        flags.put(FRAMES_COMPLETE, frames);
        flags.put(EVENTS_COMPLETE, events);
        flags.put(REFERENCE_COMPLETE, reference);
        flags.put(ANSWER_POINTS_COMPLETE, answerPoints);
        List<String> missing = new ArrayList<>();
        if (!transcript) {
            missing.add("TRANSCRIPT_MISSING");
        }
        if (!audio) {
            missing.add("AUDIO_MISSING");
        }
        if (!frames) {
            missing.add("FRAMES_MISSING");
        }
        if (!answerPoints) {
            missing.add("ANSWER_POINTS_MISSING");
        }
        flags.put(MISSING, List.copyOf(missing));
        return Map.copyOf(flags);
    }

    public static boolean flag(Map<String, Object> flags, String key) {
        Object value = flags.get(key);
        return Boolean.TRUE.equals(value);
    }
}
