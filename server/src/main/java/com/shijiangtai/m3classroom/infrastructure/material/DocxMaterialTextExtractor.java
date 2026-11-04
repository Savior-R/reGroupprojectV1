package com.shijiangtai.m3classroom.infrastructure.material;

import com.shijiangtai.m3classroom.application.port.MaterialExtractionError;
import com.shijiangtai.m3classroom.application.port.MaterialExtractionException;
import com.shijiangtai.m3classroom.application.port.MaterialTextExtractor;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;

public class DocxMaterialTextExtractor implements MaterialTextExtractor {

    @Override
    public boolean supports(String filename) {
        return filename != null && filename.toLowerCase().endsWith(".docx");
    }

    @Override
    public String extract(byte[] content, String filename, String mime) {
        try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(content));
                XWPFWordExtractor extractor = new XWPFWordExtractor(document)) {
            String text = extractor.getText();
            if (text == null || text.isBlank()) {
                throw new MaterialExtractionException(
                        MaterialExtractionError.NO_TEXT, "DOCX 中没有可提取的文字");
            }
            return text;
        } catch (MaterialExtractionException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new MaterialExtractionException(
                    MaterialExtractionError.CORRUPT, "DOCX 解析失败", exception);
        }
    }
}
