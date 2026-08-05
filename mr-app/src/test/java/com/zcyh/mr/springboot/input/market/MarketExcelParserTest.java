package com.zcyh.mr.springboot.input.market;

import com.alibaba.fastjson2.JSONArray;
import com.zcyh.mr.loader.MarketDataLoader;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayOutputStream;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MarketExcelParserTest {
    @Test
    void groupsFlatPointRowsAndPassesExistingMarketValidation() throws Exception {
        byte[] content = workbook(
                new String[]{"CURVE_ID", "FREQ", "DAYCOUNT", "TERM", "RATE"},
                new Object[][]{
                        {"IR_CURVE_CNY", "cont", "actual/365", 30, 0.015},
                        {"IR_CURVE_CNY", "cont", "actual/365", 90, 0.016}
                });

        List<MarketImportRow> rows = new MarketExcelParser().parse(file(content),
                LocalDate.of(2025, 12, 31), "IR_SPOT");

        assertEquals(1, rows.size());
        assertEquals(2, rows.get(0).pointCount);
        assertEquals("IR_SPOT", rows.get(0).curveContent.getString("CURVE_TYPE"));
        JSONArray validationErrors = new JSONArray();
        new MarketDataLoader(LocalDate.of(2025, 12, 31), validationErrors)
                .loadBaseMarketData(new JSONArray(rows.stream().map(row -> row.curveContent).toList()));
        assertTrue(validationErrors.isEmpty());
    }

    @Test
    void rejectsDuplicatePointAndInconsistentCurveMeta() throws Exception {
        byte[] duplicate = workbook(
                new String[]{"CURVE_ID", "FREQ", "TERM", "RATE"},
                new Object[][]{
                        {"IR_CURVE_CNY", "cont", 30, 0.015},
                        {"IR_CURVE_CNY", "cont", 30, 0.016}
                });
        IllegalArgumentException duplicateError = assertThrows(IllegalArgumentException.class,
                () -> new MarketExcelParser().parse(file(duplicate),
                        LocalDate.of(2025, 12, 31), "IR_SPOT"));
        assertTrue(duplicateError.getMessage().contains("曲线点位重复"));

        byte[] inconsistent = workbook(
                new String[]{"CURVE_ID", "FREQ", "TERM", "RATE"},
                new Object[][]{
                        {"IR_CURVE_CNY", "cont", 30, 0.015},
                        {"IR_CURVE_CNY", "annu", 90, 0.016}
                });
        IllegalArgumentException metaError = assertThrows(IllegalArgumentException.class,
                () -> new MarketExcelParser().parse(file(inconsistent),
                        LocalDate.of(2025, 12, 31), "IR_SPOT"));
        assertTrue(metaError.getMessage().contains("曲线属性不一致"));
    }

    @Test
    void importsFixingWithFixingId() throws Exception {
        byte[] content = workbook(
                new String[]{"FIXING_ID", "TRADE_DATE", "FIXING_VALUE"},
                new Object[][]{{"FIXING_IR_USD", "2025-12-30", 0.015}});

        List<MarketImportRow> rows = new MarketExcelParser().parse(file(content),
                LocalDate.of(2025, 12, 31), "FIXING");

        assertEquals("FIXING_IR_USD", rows.get(0).curveContent.getString("FIXING_ID"));
        assertNull(rows.get(0).curveContent.get("CURVE_ID"));
    }

    private static MockMultipartFile file(byte[] content) {
        return new MockMultipartFile("file", "market.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", content);
    }

    private static byte[] workbook(String[] headers, Object[][] values) throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            org.apache.poi.ss.usermodel.Sheet sheet = workbook.createSheet("DATA");
            org.apache.poi.ss.usermodel.Row header = sheet.createRow(0);
            for (int i = 0; i < headers.length; i++) {
                header.createCell(i).setCellValue(headers[i]);
            }
            for (int rowIndex = 0; rowIndex < values.length; rowIndex++) {
                org.apache.poi.ss.usermodel.Row row = sheet.createRow(rowIndex + 1);
                for (int columnIndex = 0; columnIndex < values[rowIndex].length; columnIndex++) {
                    Object value = values[rowIndex][columnIndex];
                    if (value instanceof Number) {
                        row.createCell(columnIndex).setCellValue(((Number) value).doubleValue());
                    } else {
                        row.createCell(columnIndex).setCellValue(String.valueOf(value));
                    }
                }
            }
            workbook.write(output);
            return output.toByteArray();
        }
    }
}
