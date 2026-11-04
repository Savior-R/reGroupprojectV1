package com.shijiangtai.m3classroom.infrastructure.storage;

import com.shijiangtai.m3classroom.application.port.MaterialObjectStorage;
import com.shijiangtai.shared.domain.DomainException;
import com.shijiangtai.shared.domain.ErrorCode;
import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import java.io.ByteArrayInputStream;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "m3.storage", havingValue = "minio")
public class MinioMaterialObjectStorage implements MaterialObjectStorage {
    private final MinioProperties properties;
    private final MinioClient client;
    private final AtomicBoolean bucketReady = new AtomicBoolean(false);

    public MinioMaterialObjectStorage(MinioProperties properties) {
        this.properties = Objects.requireNonNull(properties, "properties");
        this.client = MinioClient.builder()
                .endpoint(properties.endpoint())
                .credentials(properties.accessKey(), properties.secretKey())
                .build();
    }

    @Override
    public void put(String objectKey, byte[] content, String contentType) {
        ensureBucket();
        try {
            client.putObject(PutObjectArgs.builder()
                    .bucket(properties.bucket())
                    .object(objectKey)
                    .stream(new ByteArrayInputStream(content), content.length, -1)
                    .contentType(contentType)
                    .build());
        } catch (Exception exception) {
            throw unavailable("材料上传失败", exception);
        }
    }

    @Override
    public void delete(String objectKey) {
        try {
            client.removeObject(RemoveObjectArgs.builder()
                    .bucket(properties.bucket())
                    .object(objectKey)
                    .build());
        } catch (Exception exception) {
            throw unavailable("材料删除失败", exception);
        }
    }

    private void ensureBucket() {
        if (bucketReady.get()) {
            return;
        }
        synchronized (bucketReady) {
            if (bucketReady.get()) {
                return;
            }
            try {
                boolean exists = client.bucketExists(BucketExistsArgs.builder()
                        .bucket(properties.bucket())
                        .build());
                if (!exists) {
                    client.makeBucket(MakeBucketArgs.builder()
                            .bucket(properties.bucket())
                            .build());
                }
                bucketReady.set(true);
            } catch (Exception exception) {
                throw unavailable("对象存储不可用", exception);
            }
        }
    }

    private DomainException unavailable(String message, Exception cause) {
        return new DomainException(ErrorCode.SERVICE_UNAVAILABLE,
                message, Map.of("bucket", properties.bucket(), "cause", cause.getClass().getSimpleName()));
    }
}
