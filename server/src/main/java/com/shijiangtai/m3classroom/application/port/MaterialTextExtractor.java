package com.shijiangtai.m3classroom.application.port;

public interface MaterialTextExtractor {
    String extract(byte[] content, String filename, String mime);
}
