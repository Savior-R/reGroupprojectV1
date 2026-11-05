package com.shijiangtai.m3classroom.application.port;

import java.util.List;

/**
 * Port to the Python teacher-speech classifier. Implementations may block; the caller runs
 * classification serially off the actor and drops failures.
 */
public interface TeacherSpeechClassifier {

    ClassifyResult classify(String text, List<String> recentTexts, String mode);
}
