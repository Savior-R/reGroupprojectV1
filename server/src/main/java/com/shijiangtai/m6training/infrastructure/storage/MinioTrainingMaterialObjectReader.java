package com.shijiangtai.m6training.infrastructure.storage;

import com.shijiangtai.m3classroom.infrastructure.storage.MinioProperties;
import com.shijiangtai.m6training.domain.port.TrainingMaterialObjectReader;
import io.minio.GetObjectArgs;
import io.minio.MinioClient;
import java.io.InputStream;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "m3.storage", havingValue = "minio")
public class MinioTrainingMaterialObjectReader implements TrainingMaterialObjectReader {
    private static final Logger log =
            LoggerFactory.getLogger(MinioTrainingMaterialObjectReader.class);

    private final MinioProperties properties;
    private final MinioClient client;

    public MinioTrainingMaterialObjectReader(MinioProperties properties) {
        this.properties = properties;
        this.client = MinioClient.builder()
                .endpoint(properties.endpoint())
                .credentials(properties.accessKey(), properties.secretKey())
                .build();
    }

    @Override
    public Optional<byte[]> read(String objectKey) {
        try (InputStream stream = client.getObject(GetObjectArgs.builder()
                .bucket(properties.bucket())
                .object(objectKey)
                .build())) {
            return Optional.of(stream.readAllBytes());
        } catch (Exception exception) {
            log.warn("Failed to read material object {}", objectKey, exception);
            return Optional.empty();
        }
    }
}
