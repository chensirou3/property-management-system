package com.propertyops.pms.report;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class ReportArtifactWriterTest {
    private final ReportArtifactWriter writer = new ReportArtifactWriter();

    @Test
    void createsDeterministicValidArtifactFamilies() {
        List<String> columns = List.of("name", "amount");
        List<Map<String, Object>> rows = List.of(Map.of("name", "合成客户", "amount", 12.34));

        var csv = writer.write("CSV", "报表", "合成水印", columns, rows);
        assertThat(csv.bytes()).startsWith(new byte[] {(byte) 0xef, (byte) 0xbb, (byte) 0xbf});
        assertThat(new String(csv.bytes(), StandardCharsets.UTF_8)).contains("合成客户", "12.34");

        var xlsx = writer.write("XLSX", "报表", "合成水印", columns, rows);
        assertThat(xlsx.bytes()).startsWith(new byte[] {'P', 'K'});

        var pdf = writer.write("PDF", "报表", "合成水印", columns, rows);
        assertThat(pdf.bytes()).startsWith("%PDF-1.4".getBytes(StandardCharsets.US_ASCII));
        assertThat(new String(pdf.bytes(), StandardCharsets.US_ASCII)).endsWith("%%EOF");

        var print = writer.write("PRINT", "报表", "合成水印", columns, rows);
        assertThat(new String(print.bytes(), StandardCharsets.UTF_8)).startsWith("<!doctype html>").contains("合成客户");
    }
}
