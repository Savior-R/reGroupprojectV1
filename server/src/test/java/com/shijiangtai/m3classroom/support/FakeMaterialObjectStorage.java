package com.shijiangtai.m3classroom.support;

import com.shijiangtai.m3classroom.application.port.MaterialObjectStorage;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class FakeMaterialObjectStorage implements MaterialObjectStorage {
    private final Map<String, byte[]> objects = new ConcurrentHashMap<>();

    @Override
    public void put(String objectKey, byte[] content, String contentType) {
        objects.put(objectKey, Arrays.copyOf(content, content.length));
    }

    @Override
    public void delete(String objectKey) {
        objects.remove(objectKey);
    }

    public Set<String> keys() {
        return Set.copyOf(objects.keySet());
    }

    public byte[] get(String objectKey) {
        byte[] content = objects.get(objectKey);
        return content == null ? null : Arrays.copyOf(content, content.length);
    }
}
