package com.shijiangtai.m3classroom.infrastructure.time;

import com.shijiangtai.shared.application.ClockProvider;
import java.time.Instant;

public class SystemClockProvider implements ClockProvider {

    @Override
    public Instant now() {
        return Instant.now();
    }
}
