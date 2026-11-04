package com.shijiangtai.m3classroom.support;

import com.shijiangtai.m3classroom.application.port.ContentClient;
import com.shijiangtai.m3classroom.application.port.ContentRequest;
import com.shijiangtai.m3classroom.application.port.ContentResponse;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Configurable in-memory {@link ContentClient} for actor and content-generation tests. */
public final class FakeContentClient implements ContentClient {

    private final List<ContentRequest> questionRequests = new CopyOnWriteArrayList<>();
    private final List<ContentRequest> answerRequests = new CopyOnWriteArrayList<>();
    private ContentResponse questionResponse =
            new ContentResponse("老师，我想问一下这里应该怎么理解？", 0.8, "fake-model", "prompt-v1");
    private ContentResponse answerResponse =
            new ContentResponse("老师，我觉得这一段写的是荷塘的景色。", 0.7, "fake-model", "prompt-v1");
    private RuntimeException questionFailure;
    private RuntimeException answerFailure;

    /** A client whose question and answer calls both fail, forcing template fallback. */
    public static FakeContentClient failing() {
        FakeContentClient client = new FakeContentClient();
        client.questionFailure = new IllegalStateException("content service unavailable");
        client.answerFailure = new IllegalStateException("content service unavailable");
        return client;
    }

    public FakeContentClient withQuestionResponse(ContentResponse response) {
        this.questionResponse = response;
        return this;
    }

    public FakeContentClient withAnswerResponse(ContentResponse response) {
        this.answerResponse = response;
        return this;
    }

    public FakeContentClient withAnswerFailure(RuntimeException failure) {
        this.answerFailure = failure;
        return this;
    }

    public FakeContentClient withQuestionFailure(RuntimeException failure) {
        this.questionFailure = failure;
        return this;
    }

    @Override
    public ContentResponse question(ContentRequest request) {
        questionRequests.add(request);
        if (questionFailure != null) {
            throw questionFailure;
        }
        return questionResponse;
    }

    @Override
    public ContentResponse answer(ContentRequest request) {
        answerRequests.add(request);
        if (answerFailure != null) {
            throw answerFailure;
        }
        return answerResponse;
    }

    public List<ContentRequest> questionRequests() {
        return List.copyOf(questionRequests);
    }

    public List<ContentRequest> answerRequests() {
        return List.copyOf(answerRequests);
    }
}
