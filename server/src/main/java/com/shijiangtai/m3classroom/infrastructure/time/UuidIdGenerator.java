package com.shijiangtai.m3classroom.infrastructure.time;

import com.shijiangtai.shared.application.IdGenerator;
import java.util.UUID;

public class UuidIdGenerator implements IdGenerator {

    @Override
    public UUID nextId() {
        return UUID.randomUUID();
    }
}
