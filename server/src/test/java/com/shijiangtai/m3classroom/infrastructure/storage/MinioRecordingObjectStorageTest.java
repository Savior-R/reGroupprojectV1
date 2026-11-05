package com.shijiangtai.m3classroom.infrastructure.storage;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class MinioRecordingObjectStorageTest {
    private static final String REACHABLE_NOWHERE = "http://127.0.0.1:1";
    private static final String PUBLIC_ENDPOINT = "http://127.0.0.1:2";
    private static final Duration TTL = Duration.ofMinutes(15);

    @Test
    void presignsAgainstPublicEndpointWithoutCallingTheEndpoint() {
        MinioRecordingObjectStorage storage =
                storageWithRegion("us-east-1");

        String partUrl = storage.presignPart("recordings/key.webm", "upload-1", 3, TTL);
        String getUrl = storage.presignGet("recordings/key.webm", TTL);

        assertThat(partUrl)
                .startsWith(PUBLIC_ENDPOINT + "/")
                .contains("recordings/key.webm")
                .contains("uploadId=upload-1")
                .contains("partNumber=3")
                .contains("X-Amz-Signature");
        assertThat(getUrl)
                .startsWith(PUBLIC_ENDPOINT + "/")
                .contains("recordings/key.webm")
                .contains("X-Amz-Signature");
    }

    @Test
    void blankRegionDefaultsToUsEast1WithoutCallingTheEndpoint() {
        MinioRecordingObjectStorage storage = storageWithRegion("  ");

        assertThat(storage.presignPart("recordings/key.webm", "upload-1", 1, TTL))
                .startsWith(PUBLIC_ENDPOINT + "/")
                .contains("X-Amz-Signature");
        assertThat(storage.presignGet("recordings/key.webm", TTL))
                .startsWith(PUBLIC_ENDPOINT + "/")
                .contains("X-Amz-Signature");
    }

    private static MinioRecordingObjectStorage storageWithRegion(String region) {
        return new MinioRecordingObjectStorage(new MinioProperties(
                REACHABLE_NOWHERE, PUBLIC_ENDPOINT, region,
                "access-key", "secret-key", "recordings"));
    }
}
