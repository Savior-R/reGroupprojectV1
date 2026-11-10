package com.shijiangtai.m6training.application.command;

import java.util.UUID;

/** Publish a template from a published / closed free task (08 §10.1). */
public record PublishTrainingTemplateCommand(
        UUID sourceTaskId, UUID courseId, String trainingLevel) {
}
