package com.shijiangtai.m3classroom.infrastructure.persistence.jpa;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "recording_streams", schema = "m3_classroom")
public class RecordingStreamEntity {
    @Id
    private UUID id;

    @Column(name = "recording_id", nullable = false)
    private UUID recordingId;

    @Column(name = "stream_type", nullable = false, length = 16)
    private String streamType;

    @Column(name = "upload_id", length = 255)
    private String uploadId;

    @Column(name = "object_key", length = 512)
    private String objectKey;

    @Column(name = "part_size", nullable = false)
    private long partSize;

    @Column(name = "parts_uploaded", nullable = false)
    private int partsUploaded;

    @Column(name = "bytes_uploaded", nullable = false)
    private long bytesUploaded;

    @Column(name = "duration_ms")
    private Long durationMs;

    @Column(name = "offset_ms", nullable = false)
    private long offsetMs;

    @Column(length = 100)
    private String mime;

    @Column(nullable = false, length = 16)
    private String status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getRecordingId() {
        return recordingId;
    }

    public void setRecordingId(UUID recordingId) {
        this.recordingId = recordingId;
    }

    public String getStreamType() {
        return streamType;
    }

    public void setStreamType(String streamType) {
        this.streamType = streamType;
    }

    public String getUploadId() {
        return uploadId;
    }

    public void setUploadId(String uploadId) {
        this.uploadId = uploadId;
    }

    public String getObjectKey() {
        return objectKey;
    }

    public void setObjectKey(String objectKey) {
        this.objectKey = objectKey;
    }

    public long getPartSize() {
        return partSize;
    }

    public void setPartSize(long partSize) {
        this.partSize = partSize;
    }

    public int getPartsUploaded() {
        return partsUploaded;
    }

    public void setPartsUploaded(int partsUploaded) {
        this.partsUploaded = partsUploaded;
    }

    public long getBytesUploaded() {
        return bytesUploaded;
    }

    public void setBytesUploaded(long bytesUploaded) {
        this.bytesUploaded = bytesUploaded;
    }

    public Long getDurationMs() {
        return durationMs;
    }

    public void setDurationMs(Long durationMs) {
        this.durationMs = durationMs;
    }

    public long getOffsetMs() {
        return offsetMs;
    }

    public void setOffsetMs(long offsetMs) {
        this.offsetMs = offsetMs;
    }

    public String getMime() {
        return mime;
    }

    public void setMime(String mime) {
        this.mime = mime;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
