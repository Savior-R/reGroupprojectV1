package com.shijiangtai.m3classroom.support;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import org.apache.poi.xwpf.usermodel.XWPFDocument;

public final class DocxTestFiles {
    private DocxTestFiles() {
    }

    public static byte[] docx(String text) {
        try (XWPFDocument document = new XWPFDocument();
                ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            document.createParagraph().createRun().setText(text);
            document.write(output);
            return output.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException("Could not create DOCX fixture", exception);
        }
    }
}
