package com.propertyops.pms.report;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.springframework.stereotype.Component;

@Component
public class ReportArtifactWriter {
    public Artifact write(String format, String title, String watermark, List<String> columns,
                          List<Map<String, Object>> rows) {
        return switch (format) {
            case "CSV" -> new Artifact("csv", "text/csv; charset=utf-8", csv(columns, rows));
            case "XLSX" -> new Artifact("xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                    xlsx(title, watermark, columns, rows));
            case "PDF" -> new Artifact("pdf", "application/pdf", pdf(title, watermark, rows.size()));
            case "PRINT" -> new Artifact("html", "text/html; charset=utf-8", html(title, watermark, columns, rows));
            default -> throw new IllegalArgumentException("Unsupported format " + format);
        };
    }

    private byte[] csv(List<String> columns, List<Map<String, Object>> rows) {
        StringBuilder value = new StringBuilder("\ufeff");
        value.append(columns.stream().map(this::csvCell).reduce((a, b) -> a + "," + b).orElse("")).append("\r\n");
        for (Map<String, Object> row : rows) {
            value.append(columns.stream().map(column -> csvCell(row.get(column)))
                    .reduce((a, b) -> a + "," + b).orElse("")).append("\r\n");
        }
        return value.toString().getBytes(StandardCharsets.UTF_8);
    }

    private String csvCell(Object raw) {
        String value = raw == null ? "" : String.valueOf(raw);
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }

    private byte[] html(String title, String watermark, List<String> columns, List<Map<String, Object>> rows) {
        StringBuilder value = new StringBuilder("<!doctype html><html><head><meta charset=\"utf-8\"><title>")
                .append(xml(title)).append("</title><style>body{font-family:sans-serif;color:#172033}table{border-collapse:collapse;width:100%}th,td{border:1px solid #ccd3dc;padding:6px}small{color:#667085}</style></head><body><h1>")
                .append(xml(title)).append("</h1><small>").append(xml(watermark)).append("</small><table><thead><tr>");
        columns.forEach(column -> value.append("<th>").append(xml(column)).append("</th>"));
        value.append("</tr></thead><tbody>");
        for (Map<String, Object> row : rows) {
            value.append("<tr>");
            columns.forEach(column -> value.append("<td>").append(xml(row.get(column))).append("</td>"));
            value.append("</tr>");
        }
        value.append("</tbody></table></body></html>");
        return value.toString().getBytes(StandardCharsets.UTF_8);
    }

    private byte[] xlsx(String title, String watermark, List<String> columns, List<Map<String, Object>> rows) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (ZipOutputStream zip = new ZipOutputStream(bytes, StandardCharsets.UTF_8)) {
                entry(zip, "[Content_Types].xml", """
                        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                        <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
                          <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
                          <Default Extension="xml" ContentType="application/xml"/>
                          <Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>
                          <Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>
                        </Types>
                        """);
                entry(zip, "_rels/.rels", """
                        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                        <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                          <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>
                        </Relationships>
                        """);
                entry(zip, "xl/workbook.xml", """
                        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                        <workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
                          <sheets><sheet name="Report" sheetId="1" r:id="rId1"/></sheets>
                        </workbook>
                        """);
                entry(zip, "xl/_rels/workbook.xml.rels", """
                        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                        <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                          <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/>
                        </Relationships>
                        """);
                List<List<Object>> table = new ArrayList<>();
                table.add(List.of(title)); table.add(List.of(watermark)); table.add(new ArrayList<>(columns));
                for (Map<String, Object> row : rows) table.add(columns.stream().map(row::get).toList());
                StringBuilder sheet = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><sheetData>");
                for (int r = 0; r < table.size(); r++) {
                    sheet.append("<row r=\"").append(r + 1).append("\">");
                    for (int c = 0; c < table.get(r).size(); c++) {
                        Object raw = table.get(r).get(c);
                        String reference = column(c) + (r + 1);
                        if (raw instanceof Number) sheet.append("<c r=\"").append(reference).append("\"><v>").append(raw).append("</v></c>");
                        else sheet.append("<c r=\"").append(reference).append("\" t=\"inlineStr\"><is><t>").append(xml(raw)).append("</t></is></c>");
                    }
                    sheet.append("</row>");
                }
                sheet.append("</sheetData></worksheet>");
                entry(zip, "xl/worksheets/sheet1.xml", sheet.toString());
            }
            return bytes.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException("Could not create XLSX", exception);
        }
    }

    private byte[] pdf(String title, String watermark, int rowCount) {
        String visible = "PROPERTY MANAGEMENT SYSTEM SYNTHETIC REPORT | " + ascii(title) + " | rows=" + rowCount + " | " + ascii(watermark);
        String stream = "BT /F1 10 Tf 36 800 Td (" + visible.replace("\\", "\\\\").replace("(", "\\(").replace(")", "\\)") + ") Tj ET";
        List<String> objects = List.of(
                "<< /Type /Catalog /Pages 2 0 R >>",
                "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
                "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] /Resources << /Font << /F1 5 0 R >> >> /Contents 4 0 R >>",
                "<< /Length " + stream.getBytes(StandardCharsets.US_ASCII).length + " >>\nstream\n" + stream + "\nendstream",
                "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>");
        StringBuilder pdf = new StringBuilder("%PDF-1.4\n");
        List<Integer> offsets = new ArrayList<>();
        for (int index = 0; index < objects.size(); index++) {
            offsets.add(pdf.toString().getBytes(StandardCharsets.US_ASCII).length);
            pdf.append(index + 1).append(" 0 obj\n").append(objects.get(index)).append("\nendobj\n");
        }
        int xref = pdf.toString().getBytes(StandardCharsets.US_ASCII).length;
        pdf.append("xref\n0 ").append(objects.size() + 1).append("\n0000000000 65535 f \n");
        offsets.forEach(offset -> pdf.append(String.format("%010d 00000 n \n", offset)));
        pdf.append("trailer << /Size ").append(objects.size() + 1).append(" /Root 1 0 R >>\nstartxref\n")
                .append(xref).append("\n%%EOF");
        return pdf.toString().getBytes(StandardCharsets.US_ASCII);
    }

    private void entry(ZipOutputStream zip, String name, String content) throws IOException {
        zip.putNextEntry(new ZipEntry(name)); zip.write(content.getBytes(StandardCharsets.UTF_8)); zip.closeEntry();
    }

    private String column(int zeroBased) {
        StringBuilder value = new StringBuilder();
        int number = zeroBased + 1;
        while (number > 0) { value.insert(0, (char) ('A' + (number - 1) % 26)); number = (number - 1) / 26; }
        return value.toString();
    }

    private String xml(Object raw) {
        String value = raw == null ? "" : String.valueOf(raw);
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private String ascii(String value) { return value == null ? "" : value.replaceAll("[^\\x20-\\x7E]", "_"); }

    public record Artifact(String extension, String mime, byte[] bytes) {}
}
