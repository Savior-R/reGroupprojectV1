package com.shijiangtai.shared.application;

import java.time.Instant;

public interface ClockProvider {
    Instant now();
}
