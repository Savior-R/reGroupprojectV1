package com.shijiangtai.m3classroom.domain.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.shijiangtai.shared.domain.DomainException;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ReferenceMaterialTest {

    @Test
    void previewIsFirst500Chars() {
        assertThat(materialWithText("x".repeat(600)).preview()).hasSize(500);
    }

    @Test
    void previewHandlesShortText() {
        assertThat(materialWithText("text").preview()).isEqualTo("text");
    }

    @Test
    void acceptsValidMetadata() {
        ReferenceMaterial material = materialWithText("a".repeat(120));
        assertThat(material.charCount()).isEqualTo(120);
        assertThat(material.size()).isEqualTo(1024);
    }

    @Test
    void rejectsZeroSize() {
        assertThatThrownBy(() -> new ReferenceMaterial(
                UUID.randomUUID(), "a.txt", "key", "text/plain", 0, "text", 4))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void rejectsNegativeCharCount() {
        assertThatThrownBy(() -> new ReferenceMaterial(
                UUID.randomUUID(), "a.txt", "key", "text/plain", 1, "text", -1))
                .isInstanceOf(DomainException.class);
    }

    private ReferenceMaterial materialWithText(String text) {
        return new ReferenceMaterial(
                UUID.randomUUID(), "a.txt", "key", "text/plain", 1024, text, text.length());
    }
}
