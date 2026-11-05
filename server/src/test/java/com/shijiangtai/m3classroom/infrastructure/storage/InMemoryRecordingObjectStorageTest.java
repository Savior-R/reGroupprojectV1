package com.shijiangtai.m3classroom.infrastructure.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.shijiangtai.m3classroom.application.port.RecordingObjectStorage.MultipartUpload;
import com.shijiangtai.m3classroom.application.port.RecordingObjectStorage.ObjectStat;
import com.shijiangtai.m3classroom.application.port.RecordingObjectStorage.PartEtag;
import com.shijiangtai.shared.domain.DomainException;
import com.shijiangtai.shared.domain.ErrorCode;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class InMemoryRecordingObjectStorageTest {
    private static final String OBJECT_KEY = "recordings/school/task/session/camera.webm";
    private static final String CONTENT_TYPE = "video/webm;codecs=vp8";
    private static final Duration TTL = Duration.ofMinutes(15);

    private final InMemoryRecordingObjectStorage storage = new InMemoryRecordingObjectStorage();

    @Test
    void multipartLifecycleStoresPartsAndCompletes() {
        MultipartUpload upload = storage.createMultipart(OBJECT_KEY, CONTENT_TYPE);

        assertThat(upload.uploadId()).isNotBlank();
        assertThat(upload.objectKey()).isEqualTo(OBJECT_KEY);
        assertThat(upload.partSize()).isEqualTo(5L * 1024 * 1024);

        String partUrl = storage.presignPart(OBJECT_KEY, upload.uploadId(), 1, TTL);
        assertThat(partUrl)
                .startsWith("http://memory-minio/")
                .contains(OBJECT_KEY)
                .endsWith("?partNumber=1");

        storage.recordObjectSize(OBJECT_KEY, 1234L);
        storage.completeMultipart(OBJECT_KEY, upload.uploadId(),
                List.of(new PartEtag(1, "etag-1")));

        assertThat(storage.stat(OBJECT_KEY)).isEqualTo(new ObjectStat(1234L, CONTENT_TYPE));

        storage.delete(OBJECT_KEY);
        assertThatThrownBy(() -> storage.stat(OBJECT_KEY))
                .isInstanceOfSatisfying(DomainException.class,
                        exception -> assertThat(exception.code())
                                .isEqualTo(ErrorCode.SERVICE_UNAVAILABLE));
    }

    @Test
    void abortRemovesUpload() {
        MultipartUpload upload = storage.createMultipart(OBJECT_KEY, CONTENT_TYPE);

        storage.abortMultipart(OBJECT_KEY, upload.uploadId());

        assertThatThrownBy(() -> storage.completeMultipart(OBJECT_KEY, upload.uploadId(),
                List.of(new PartEtag(1, "etag-1"))))
                .isInstanceOfSatisfying(DomainException.class,
                        exception -> assertThat(exception.code())
                                .isEqualTo(ErrorCode.SERVICE_UNAVAILABLE));
        assertThatThrownBy(() -> storage.presignPart(OBJECT_KEY, upload.uploadId(), 2, TTL))
                .isInstanceOfSatisfying(DomainException.class,
                        exception -> assertThat(exception.code())
                                .isEqualTo(ErrorCode.SERVICE_UNAVAILABLE));
    }

    @Test
    void presignedUrlsContainNoCredentials() {
        MultipartUpload upload = storage.createMultipart(OBJECT_KEY, CONTENT_TYPE);

        String partUrl = storage.presignPart(OBJECT_KEY, upload.uploadId(), 2, TTL);
        String getUrl = storage.presignGet(OBJECT_KEY, TTL);

        assertThat(partUrl)
                .contains(OBJECT_KEY)
                .contains("partNumber=2")
                .doesNotContain("minioadmin")
                .doesNotContain("X-Amz-Credential");
        assertThat(getUrl)
                .startsWith("http://memory-minio/")
                .contains(OBJECT_KEY)
                .endsWith("?get=1")
                .doesNotContain("minioadmin")
                .doesNotContain("X-Amz-Credential");
    }

    @Test
    void statOnUnknownObjectFails() {
        assertThatThrownBy(() -> storage.stat("recordings/missing.webm"))
                .isInstanceOfSatisfying(DomainException.class,
                        exception -> assertThat(exception.code())
                                .isEqualTo(ErrorCode.SERVICE_UNAVAILABLE));
    }
}
