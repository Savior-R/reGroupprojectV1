package com.shijiangtai.m3classroom.domain.interview;

import java.util.UUID;

/**
 * Session-facing snapshot of a structured interview.
 *
 * @param current     question currently being presented, or null before the interview starts
 * @param answerId    answer id shared by all ASR segments of the current question
 * @param remainingMs remaining time of the whole interview, zero before start and after end
 * @param ended       whether the interview has ended
 */
public record InterviewSnapshot(
        InterviewQuestionState current, UUID answerId, long remainingMs, boolean ended) {
}
