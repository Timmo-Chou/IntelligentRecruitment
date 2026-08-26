package com.intelligentrecruitment.candidates;

import com.intelligentrecruitment.candidates.application.ResumeTextExtractor;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

class ResumeTextExtractorTest {

    private final ResumeTextExtractor extractor = new ResumeTextExtractor();

    @Test
    void extractsParagraphsAndEscapedTextFromDocx() throws Exception {
        byte[] docx;
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream();
             ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("word/document.xml"));
            zip.write(("<w:document><w:body><w:p><w:r><w:t>Java &amp; Spring Boot</w:t></w:r></w:p>"
                    + "<w:p><w:r><w:t>5年招聘系统经验</w:t></w:r></w:p></w:body></w:document>")
                    .getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.finish();
            docx = bytes.toByteArray();
        }

        assertThat(extractor.extract(docx, "candidate.docx"))
                .contains("Java & Spring Boot")
                .contains("5年招聘系统经验");
    }

    @Test
    void extractsReadableTokensFromPdfBytes() {
        byte[] pdf = "%PDF-1.4 Candidate Java Spring Boot Redis".getBytes(StandardCharsets.ISO_8859_1);

        assertThat(extractor.extract(pdf, "candidate.pdf"))
                .contains("Candidate Java Spring Boot Redis");
    }
}
