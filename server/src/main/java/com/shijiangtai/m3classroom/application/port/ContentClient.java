package com.shijiangtai.m3classroom.application.port;

/**
 * Port to the Python content-generation service (05A §7.4). Implementations may block; the
 * application service enforces the hard timeouts.
 */
public interface ContentClient {

    ContentResponse question(ContentRequest request);

    ContentResponse answer(ContentRequest request);
}
