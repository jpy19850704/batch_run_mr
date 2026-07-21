package com.zcyh.mr.springboot.input.market;

import com.zcyh.mr.springboot.input.common.ExcelTemplateFile;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Locale;

@Service
public class MarketTemplateService {
    public ExcelTemplateFile generate(String marketDataType) {
        String type = normalize(marketDataType);
        List<String> columns = MarketImportSchema.templateColumns(type);
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            Sheet data = workbook.createSheet("DATA");
            Row header = data.createRow(0);
            for (int i = 0; i < columns.size(); i++) {
                header.createCell(i).setCellValue(columns.get(i));
                data.setColumnWidth(i, Math.max(14, columns.get(i).length() + 2) * 256);
            }
            data.createFreezePane(0, 1);
            Sheet help = workbook.createSheet("FIELD_HELP");
            Row helpHeader = help.createRow(0);
            helpHeader.createCell(0).setCellValue("字段");
            helpHeader.createCell(1).setCellValue("说明");
            for (int i = 0; i < columns.size(); i++) {
                Row row = help.createRow(i + 1);
                row.createCell(0).setCellValue(columns.get(i));
                row.createCell(1).setCellValue("CURVE_ID为曲线标识；其余字段按所选" + type + "类型填写");
            }
            help.setColumnWidth(0, 28 * 256);
            help.setColumnWidth(1, 60 * 256);
            workbook.write(output);
            return new ExcelTemplateFile("market_" + type + "_template.xlsx", output.toByteArray());
        } catch (java.io.IOException e) {
            throw new IllegalStateException("生成市场数据导入模板失败", e);
        }
    }

    private static String normalize(String marketDataType) {
        String value = marketDataType == null ? "" : marketDataType.trim().toUpperCase(Locale.ROOT);
        if (!MarketImportSchema.supportedTypes().contains(value)) {
            throw new IllegalArgumentException("不支持的市场数据类型: " + marketDataType);
        }
        return value;
    }
}
