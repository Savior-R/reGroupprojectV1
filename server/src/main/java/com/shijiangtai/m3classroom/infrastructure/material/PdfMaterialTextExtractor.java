package com.shijiangtai.m3classroom.infrastructure.material;

import com.shijiangtai.m3classroom.application.port.MaterialExtractionError;
import com.shijiangtai.m3classroom.application.port.MaterialExtractionException;
import com.shijiangtai.m3classroom.application.port.MaterialTextExtractor;
import java.io.IOException;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;

public class PdfMaterialTextExtractor implements MaterialTextExtractor {

    @Override
    public boolean supports(String filename) {
        return filename != null && filename.toLowerCase().endsWith(".pdf");
    }

    @Override
    public String extract(byte[] content, String filename, String mime) {
        try (PDDocument document = Loader.loadPDF(content)) {
            String text = new PDFTextStripper().getText(document);
            if (text == null || text.isBlank()) {
                throw new MaterialExtractionException(
                        MaterialExtractionError.NO_TEXT, "PDF 中没有可提取的文字");
            }
            return text;
        } catch (MaterialExtractionException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new MaterialExtractionException(
                    MaterialExtractionError.CORRUPT, "PDF 解析失败", exception);
        }
    }
}
