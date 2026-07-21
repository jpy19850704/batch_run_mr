package com.zcyh.mr.springboot.input.trade;

import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayOutputStream;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TradeExcelParserTest {
    @Test
    void parsesAttributesAndNestedProductFields() throws Exception {
        byte[] content;
        try (XSSFWorkbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            org.apache.poi.ss.usermodel.Sheet sheet = workbook.createSheet("DATA");
            org.apache.poi.ss.usermodel.Row header = sheet.createRow(0);
            String[] headers = {"DATA_DATE", "INSTRUMENT_ID", "PRODUCT_CODE", "DESK",
                    "selection", "COMPONENTS[0].DATA.NOTIONAL"};
            for (int i = 0; i < headers.length; i++) {
                header.createCell(i).setCellValue(headers[i]);
            }
            org.apache.poi.ss.usermodel.Row row = sheet.createRow(1);
            row.createCell(0).setCellValue("2025-12-31");
            row.createCell(1).setCellValue("TRADE_001");
            row.createCell(2).setCellValue("FXFWD");
            row.createCell(3).setCellValue("FX_DESK");
            row.createCell(4).setCellValue("A");
            row.createCell(5).setCellValue(100.0);
            workbook.write(output);
            content = output.toByteArray();
        }

        TradeExcelParser parser = new TradeExcelParser();
        List<TradeImportRow> rows = parser.parse(new MockMultipartFile(
                "file", "trade.xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", content),
                LocalDate.of(2025, 12, 31), "FXFWD");

        assertEquals(1, rows.size());
        assertEquals("FX_DESK", rows.get(0).attributes.get("DESK"));
        assertEquals("A", rows.get(0).tradeData.getString("selection"));
        assertEquals(100, rows.get(0).tradeData.getJSONArray("COMPONENTS")
                .getJSONObject(0).getJSONObject("DATA").getBigDecimal("NOTIONAL").intValue());
    }
}
