package com.shijiangtai.m3classroom.infrastructure.material;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.shijiangtai.m3classroom.application.port.MaterialExtractionError;
import com.shijiangtai.m3classroom.application.port.MaterialExtractionException;
import com.shijiangtai.m3classroom.support.DocxTestFiles;
import com.shijiangtai.m3classroom.support.PdfTestFiles;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class MaterialTextExtractorTest {

    private final PdfMaterialTextExtractor pdfExtractor = new PdfMaterialTextExtractor();
    private final DocxMaterialTextExtractor docxExtractor = new DocxMaterialTextExtractor();
    private final PlainTextMaterialTextExtractor plainExtractor =
            new PlainTextMaterialTextExtractor();
    private final CompositeMaterialTextExtractor composite = new CompositeMaterialTextExtractor(
            List.of(pdfExtractor, docxExtractor, plainExtractor));

    @Test
    void extractsTextFromPdf() {
        String text = pdfExtractor.extract(
                PdfTestFiles.pdf("Classroom introduction"), "a.pdf", "application/pdf");
        assertThat(text).contains("Classroom introduction");
    }

    @Test
    void blankPdfFails() {
        assertError(() -> pdfExtractor.extract(
                PdfTestFiles.blankPdf(), "a.pdf", "application/pdf"), MaterialExtractionError.NO_TEXT);
    }

    @Test
    void extractsTextFromDocx() {
        String text = docxExtractor.extract(
                DocxTestFiles.docx("课堂导入材料内容"), "a.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document");
        assertThat(text).contains("课堂导入材料内容");
    }

    @Test
    void plainTextFallsBackToGb18030() {
        String expected = "课堂导入材料内容足够长用于测试";
        byte[] content = expected.getBytes(Charset.forName("GB18030"));
        assertThat(plainExtractor.extract(content, "a.txt", "text/plain")).contains(expected);
    }

    @Test
    void markdownIsSupported() {
        byte[] content = "# 课堂\n课堂导入材料内容足够长用于测试".getBytes(StandardCharsets.UTF_8);
        assertThat(plainExtractor.extract(content, "a.md", "text/markdown"))
                .contains("课堂导入材料内容");
    }

    @Test
    void unsupportedExtensionFails() {
        assertError(() -> composite.extract(new byte[]{1, 2, 3}, "a.exe",
                "application/octet-stream"), MaterialExtractionError.UNSUPPORTED_TYPE);
    }

    private void assertError(Runnable action, MaterialExtractionError expected) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(MaterialExtractionException.class,
                        ex -> assertThat(ex.error()).isEqualTo(expected));
    }
}
