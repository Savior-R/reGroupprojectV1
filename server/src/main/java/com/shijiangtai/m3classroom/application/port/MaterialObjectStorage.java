package com.shijiangtai.m3classroom.application.port;

public interface MaterialObjectStorage {
    void put(String objectKey, byte[] content, String contentType);

    void delete(String objectKey);
}
