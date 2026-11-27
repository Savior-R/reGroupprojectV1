package com.shijiangtai.m6training.api.dto;

/** Recommended / maximum duration in seconds (08 §4.1). */
public record DurationRequest(Integer recommendedSeconds, Integer maxSeconds) {
}
