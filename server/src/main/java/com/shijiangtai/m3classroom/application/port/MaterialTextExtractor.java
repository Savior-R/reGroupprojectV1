package com.shijiangtai.m3classroom.application.port;

public interface MaterialTextExtractor {
    boolean supports(String filename);

    String extract(byte[] content, String filename, String mime);
}
