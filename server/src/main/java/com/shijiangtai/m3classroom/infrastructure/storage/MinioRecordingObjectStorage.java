package com.shijiangtai.m3classroom.infrastructure.storage;

import com.google.common.collect.HashMultimap;
import com.google.common.collect.Multimap;
import com.shijiangtai.m3classroom.application.port.RecordingObjectStorage;
import com.shijiangtai.m3classroom.domain.recording.RecordingStream;
import com.shijiangtai.shared.domain.DomainException;
import com.shijiangtai.shared.domain.ErrorCode;
import io.minio.BucketExistsArgs;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioAsyncClient;
import io.minio.MinioClient;
import io.minio.RemoveObjectArgs;
import io.minio.StatObjectArgs;
import io.minio.StatObjectResponse;
import io.minio.http.Method;
import io.minio.messages.Part;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * MinIO multipart adapter. Requests to MinIO use the internal endpoint; presigned URLs are signed
 * with a second client bound to {@code m3.minio.public-endpoint} so a browser can reach MinIO.
 *
 * <p>MinIO SDK 8.5.17 only exposes multipart orchestration through the asynchronous client, so
 * create/complete/abort block on the public {@code *Async} methods. Every SDK failure maps to
 * {@link ErrorCode#SERVICE_UNAVAILABLE}.
 */
@Component
@ConditionalOnProperty(name = "m3.storage", havingValue = "minio")
public class MinioRecordingObjectStorage implements RecordingObjectStorage {
    private static final String DEFAULT_REGION = "us-east-1";

    private final MinioProperties properties;
    private final MinioAsyncClient internalClient;
    private final MinioClient presignClient;
    private final AtomicBoolean bucketReady = new AtomicBoolean(false);

    public MinioRecordingObjectStorage(MinioProperties properties) {
        this.properties = Objects.requireNonNull(properties, "properties");
        String region = region(properties);
        this.internalClient = MinioAsyncClient.builder()
                .endpoint(properties.endpoint())
                .region(region)
                .credentials(properties.accessKey(), properties.secretKey())
                .build();
        this.presignClient = MinioClient.builder()
                .endpoint(presignEndpoint(properties))
                .region(region)
                .credentials(properties.accessKey(), properties.secretKey())
                .build();
    }

    @Override
    public MultipartUpload createMultipart(String objectKey, String contentType) {
        ensureBucket();
        try {
            Multimap<String, String> headers = HashMultimap.create();
            if (contentType != null && !contentType.isBlank()) {
                headers.put("Content-Type", contentType);
            }
            String uploadId = await(internalClient.createMultipartUploadAsync(
                    properties.bucket(), null, objectKey, headers, null))
                    .result()
                    .uploadId();
            return new MultipartUpload(uploadId, objectKey, RecordingStream.DEFAULT_PART_SIZE);
        } catch (Exception exception) {
            throw unavailable(exception);
        }
    }

    @Override
    public String presignPart(String objectKey, String uploadId, int partNumber, Duration ttl) {
        try {
            return presignClient.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
                    .method(Method.PUT)
                    .bucket(properties.bucket())
                    .object(objectKey)
                    .expiry((int) ttl.toSeconds())
                    .extraQueryParams(Map.of(
                            "uploadId", uploadId,
                            "partNumber", String.valueOf(partNumber)))
                    .build());
        } catch (Exception exception) {
            throw unavailable(exception);
        }
    }

    @Override
    public void completeMultipart(String objectKey, String uploadId, List<PartEtag> parts) {
        try {
            Part[] minioParts = parts.stream()
                    .map(part -> new Part(part.partNumber(), part.etag()))
                    .toArray(Part[]::new);
            await(internalClient.completeMultipartUploadAsync(
                    properties.bucket(), null, objectKey, uploadId, minioParts, null, null));
        } catch (Exception exception) {
            throw unavailable(exception);
        }
    }

    @Override
    public void abortMultipart(String objectKey, String uploadId) {
        try {
            await(internalClient.abortMultipartUploadAsync(
                    properties.bucket(), null, objectKey, uploadId, null, null));
        } catch (Exception exception) {
            throw unavailable(exception);
        }
    }

    @Override
    public String presignGet(String objectKey, Duration ttl) {
        try {
            return presignClient.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
                    .method(Method.GET)
                    .bucket(properties.bucket())
                    .object(objectKey)
                    .expiry((int) ttl.toSeconds())
                    .build());
        } catch (Exception exception) {
            throw unavailable(exception);
        }
    }

    @Override
    public ObjectStat stat(String objectKey) {
        try {
            StatObjectResponse response = await(internalClient.statObject(StatObjectArgs.builder()
                    .bucket(properties.bucket())
                    .object(objectKey)
                    .build()));
            return new ObjectStat(response.size(), response.contentType());
        } catch (Exception exception) {
            throw unavailable(exception);
        }
    }

    @Override
    public void delete(String objectKey) {
        try {
            await(internalClient.removeObject(RemoveObjectArgs.builder()
                    .bucket(properties.bucket())
                    .object(objectKey)
                    .build()));
        } catch (Exception exception) {
            throw unavailable(exception);
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
                boolean exists = Boolean.TRUE.equals(await(internalClient.bucketExists(
                        BucketExistsArgs.builder().bucket(properties.bucket()).build())));
                if (!exists) {
                    await(internalClient.makeBucket(
                            MakeBucketArgs.builder().bucket(properties.bucket()).build()));
                }
                bucketReady.set(true);
            } catch (Exception exception) {
                throw unavailable(exception);
            }
        }
    }

    private static String presignEndpoint(MinioProperties properties) {
        String publicEndpoint = properties.publicEndpoint();
        return publicEndpoint == null || publicEndpoint.isBlank()
                ? properties.endpoint()
                : publicEndpoint;
    }

    private static String region(MinioProperties properties) {
        String region = properties.region();
        return region == null || region.isBlank() ? DEFAULT_REGION : region;
    }

    private <T> T await(CompletableFuture<T> future) {
        try {
            return future.get();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw unavailable(exception);
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            throw unavailable(cause instanceof Exception nested ? nested : exception);
        }
    }

    private DomainException unavailable(Exception cause) {
        if (cause instanceof DomainException domainException) {
            return domainException;
        }
        Map<String, Object> details = new HashMap<>();
        details.put("cause", cause.getClass().getSimpleName());
        if (properties.bucket() != null) {
            details.put("bucket", properties.bucket());
        }
        return new DomainException(ErrorCode.SERVICE_UNAVAILABLE, "对象存储不可用", details);
    }
}
