package com.shijiangtai.m3classroom.infrastructure.storage;

import com.shijiangtai.m3classroom.application.port.RecordingObjectStorage;
import com.shijiangtai.m3classroom.domain.recording.RecordingStream;
import com.shijiangtai.shared.domain.DomainException;
import com.shijiangtai.shared.domain.ErrorCode;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Test/dev adapter that simulates MinIO multipart without storing bytes.
 *
 * <p>Uploads and completed objects live in in-process maps. Since no bytes are transferred, tests
 * reserve the completed object size via {@link #recordObjectSize(String, long)} before (or after)
 * completing uploads; {@link #stat(String)} reports that size together with the content type
 * declared in {@link #createMultipart(String, String)}.
 */
@Component
@ConditionalOnProperty(name = "m3.storage", havingValue = "memory", matchIfMissing = true)
public class InMemoryRecordingObjectStorage implements RecordingObjectStorage {
    static final String BUCKET = "memory-bucket";

    private final Map<String, Upload> uploads = new ConcurrentHashMap<>();
    private final Map<String, StoredObject> objects = new ConcurrentHashMap<>();
    private final Map<String, Long> recordedSizes = new ConcurrentHashMap<>();

    @Override
    public MultipartUpload createMultipart(String objectKey, String contentType) {
        Objects.requireNonNull(objectKey, "objectKey");
        Upload upload = new Upload(UUID.randomUUID().toString(), contentType);
        uploads.put(objectKey, upload);
        return new MultipartUpload(upload.uploadId, objectKey, RecordingStream.DEFAULT_PART_SIZE);
    }

    @Override
    public String presignPart(String objectKey, String uploadId, int partNumber, Duration ttl) {
        requireUpload(objectKey, uploadId);
        return url(objectKey) + "?partNumber=" + partNumber;
    }

    @Override
    public void completeMultipart(String objectKey, String uploadId, List<PartEtag> parts) {
        Upload upload = requireUpload(objectKey, uploadId);
        for (PartEtag part : parts) {
            upload.parts.put(part.partNumber(), part.etag());
        }
        objects.put(objectKey, new StoredObject(
                recordedSizes.getOrDefault(objectKey, 0L), upload.contentType));
        uploads.remove(objectKey);
    }

    @Override
    public void abortMultipart(String objectKey, String uploadId) {
        Upload upload = uploads.get(objectKey);
        if (upload != null && upload.uploadId.equals(uploadId)) {
            uploads.remove(objectKey);
        }
    }

    @Override
    public String presignGet(String objectKey, Duration ttl) {
        return url(objectKey) + "?get=1";
    }

    @Override
    public ObjectStat stat(String objectKey) {
        StoredObject object = objects.get(objectKey);
        if (object == null) {
            throw unavailable();
        }
        return new ObjectStat(object.size, object.contentType);
    }

    @Override
    public void delete(String objectKey) {
        uploads.remove(objectKey);
        objects.remove(objectKey);
        recordedSizes.remove(objectKey);
    }

    /**
     * Reserves the size that a completed multipart object should report. Test-visible only; the
     * production adapter derives the size from MinIO instead.
     */
    public void recordObjectSize(String objectKey, long size) {
        Objects.requireNonNull(objectKey, "objectKey");
        if (size < 0) {
            throw new IllegalArgumentException("size must not be negative");
        }
        recordedSizes.put(objectKey, size);
        objects.computeIfPresent(objectKey,
                (key, object) -> new StoredObject(size, object.contentType));
    }

    private Upload requireUpload(String objectKey, String uploadId) {
        Upload upload = uploads.get(objectKey);
        if (upload == null || !upload.uploadId.equals(uploadId)) {
            throw unavailable();
        }
        return upload;
    }

    private String url(String objectKey) {
        return "http://memory-minio/" + BUCKET + "/" + objectKey;
    }

    private DomainException unavailable() {
        return new DomainException(ErrorCode.SERVICE_UNAVAILABLE, "对象存储不可用");
    }

    private static final class Upload {
        private final String uploadId;
        private final String contentType;
        private final Map<Integer, String> parts = new ConcurrentHashMap<>();

        private Upload(String uploadId, String contentType) {
            this.uploadId = uploadId;
            this.contentType = contentType;
        }
    }

    private record StoredObject(long size, String contentType) {
    }
}
