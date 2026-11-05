package com.shijiangtai.m3classroom.application.port;

import java.time.Duration;
import java.util.List;

public interface RecordingObjectStorage {

    MultipartUpload createMultipart(String objectKey, String contentType);

    String presignPart(String objectKey, String uploadId, int partNumber, Duration ttl);

    void completeMultipart(String objectKey, String uploadId, List<PartEtag> parts);

    void abortMultipart(String objectKey, String uploadId);

    String presignGet(String objectKey, Duration ttl);

    ObjectStat stat(String objectKey);

    void delete(String objectKey);

    record MultipartUpload(String uploadId, String objectKey, long partSize) {
    }

    record PartEtag(int partNumber, String etag) {
    }

    record ObjectStat(long size, String contentType) {
    }
}
