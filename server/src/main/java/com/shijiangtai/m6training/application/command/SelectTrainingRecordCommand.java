package com.shijiangtai.m6training.application.command;

import java.util.UUID;

/** Select one ended attempt into a retained training record (08 §10.5). */
public record SelectTrainingRecordCommand(UUID sessionId) {
}
