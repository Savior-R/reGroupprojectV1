package com.shijiangtai.m6training.application.command;

import java.util.UUID;

/** Copy a teacher publication into the student's personal library (08 §5.3). */
public record CopyTrainingTemplateCommand(UUID publicationId, String name) {
}
