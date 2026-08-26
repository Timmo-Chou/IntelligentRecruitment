package com.intelligentrecruitment.candidates.application;

import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@Component
public class ResumeTextExtractor {

    public String extract(byte[] bytes, String filename) {
        if (filename.toLowerCase(Locale.ROOT).endsWith(".docx")) return docx(bytes);
        String raw = new String(bytes, StandardCharsets.ISO_8859_1)
                .replaceAll("[^\\p{L}\\p{N}@.+#\\-，。；：、\\s]", " ")
                .replaceAll("\\s+", " ").trim();
        return raw.substring(0, Math.min(raw.length(), 20_000));
    }

    private String docx(byte[] bytes) {
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if ("word/document.xml".equals(entry.getName())) {
                    String xml = new String(zip.readAllBytes(), StandardCharsets.UTF_8);
                    String text = xml.replaceAll("<w:tab[^>]*/>", " ")
                            .replaceAll("</w:p>", "\n").replaceAll("<[^>]+>", "")
                            .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">");
                    String clean = text.replaceAll("[ \\t]+", " ").trim();
                    return clean.substring(0, Math.min(clean.length(), 20_000));
                }
            }
            return "";
        } catch (Exception exception) {
            return "";
        }
    }
}
