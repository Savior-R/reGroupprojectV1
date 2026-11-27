package com.shijiangtai.m6training.application.command;

import java.util.UUID;

/** Publish one teacher template to a target course (08 §4.4). */
public record PublishTrainingTemplateCommand(UUID courseId) {
}
