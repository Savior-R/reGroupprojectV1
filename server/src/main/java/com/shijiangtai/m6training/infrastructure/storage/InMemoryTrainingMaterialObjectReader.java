package com.shijiangtai.m6training.infrastructure.storage;

import com.shijiangtai.m3classroom.infrastructure.storage.InMemoryMaterialObjectStorage;
import com.shijiangtai.m6training.domain.port.TrainingMaterialObjectReader;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "m3.storage", havingValue = "memory", matchIfMissing = true)
public class InMemoryTrainingMaterialObjectReader implements TrainingMaterialObjectReader {
    private final InMemoryMaterialObjectStorage storage;

    public InMemoryTrainingMaterialObjectReader(InMemoryMaterialObjectStorage storage) {
        this.storage = storage;
    }

    @Override
    public Optional<byte[]> read(String objectKey) {
        return Optional.ofNullable(storage.get(objectKey));
    }
}
