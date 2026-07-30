package com.zcyh.mr.springboot.input.trade;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class TradeExcelParser {
    private static final long MAX_FILE_SIZE = 20L * 1024L * 1024L;
    private static final int MAX_ROWS = 100_000;
    private static final Pattern PATH_SEGMENT = Pattern.compile("^([^\\[]+)(?:\\[(\\d+)])?$");

    public List<TradeImportRow> parse(MultipartFile file, LocalDate dataDate, String productCode) throws IOException {
        validateFile(file);
        try (XSSFWorkbook workbook = new XSSFWorkbook(file.getInputStream())) {
            Sheet sheet = workbook.getSheet("DATA");
            if (sheet == null && workbook.getNumberOfSheets() > 0) {
                sheet = workbook.getSheetAt(0);
            }
            if (sheet == null || sheet.getPhysicalNumberOfRows() == 0) {
                throw new IllegalArgumentException("Excel中不存在DATA工作表或数据为空");
            }
            if (sheet.getLastRowNum() > MAX_ROWS) {
                throw new IllegalArgumentException("单次导入不能超过" + MAX_ROWS + "行");
            }
            List<String> headers = readHeaders(sheet.getRow(sheet.getFirstRowNum()));
            requireHeader(headers, "INSTRUMENT_ID");
            requireHeader(headers, "PRODUCT_CODE");
            List<TradeImportRow> result = new ArrayList<>();
            for (int rowIndex = sheet.getFirstRowNum() + 1; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
                Row excelRow = sheet.getRow(rowIndex);
                if (excelRow == null || isBlankRow(excelRow, headers.size())) {
                    continue;
                }
                result.add(parseRow(excelRow, headers, dataDate, productCode));
            }
            if (result.isEmpty()) {
                throw new IllegalArgumentException("Excel中不存在可导入的交易数据");
            }
            return result;
        }
    }

    private TradeImportRow parseRow(Row row, List<String> headers, LocalDate dataDate, String productCode) {
        TradeImportRow result = new TradeImportRow();
        result.rowNumber = row.getRowNum() + 1;
        result.dataDate = dataDate;
        for (int i = 0; i < headers.size(); i++) {
            String header = headers.get(i);
            String systemHeader = header.toUpperCase(Locale.ROOT);
            Object value = readCell(row.getCell(i));
            if (value == null) {
                continue;
            }
            if ("DATA_DATE".equals(systemHeader)) {
                LocalDate rowDate = parseDate(value, result.rowNumber);
                if (!dataDate.equals(rowDate)) {
                    throw rowError(result.rowNumber, "DATA_DATE与所选数据日期不一致");
                }
                continue;
            }
            if ("INSTRUMENT_ID".equals(systemHeader)) {
                result.instrumentId = text(value);
                continue;
            }
            if ("PRODUCT_CODE".equals(systemHeader)) {
                result.productCode = text(value).toUpperCase(Locale.ROOT);
                continue;
            }
            TradeAttributeDefinition attribute = TradeAttributeRegistry.findByField(systemHeader);
            if (attribute != null) {
                result.attributes.put(attribute.getFieldName(), convertAttribute(value, attribute, result.rowNumber));
                continue;
            }
            setPath(result.tradeData, header, value, result.rowNumber);
        }
        if (result.instrumentId == null || result.instrumentId.isEmpty()) {
            throw rowError(result.rowNumber, "INSTRUMENT_ID不能为空");
        }
        if (result.productCode == null || result.productCode.isEmpty()) {
            throw rowError(result.rowNumber, "PRODUCT_CODE不能为空");
        }
        if (!productCode.equalsIgnoreCase(result.productCode)) {
            throw rowError(result.rowNumber, "PRODUCT_CODE与所选产品类型不一致");
        }
        result.productCode = productCode.toUpperCase(Locale.ROOT);
        result.tradeData.put("INSTRUMENT_ID", result.instrumentId);
        result.tradeData.put("PRODUCT_CODE", result.productCode);
        return result;
    }

    private static List<String> readHeaders(Row row) {
        if (row == null) {
            throw new IllegalArgumentException("Excel缺少表头");
        }
        List<String> headers = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < row.getLastCellNum(); i++) {
            Object raw = readCell(row.getCell(i));
            String header = raw == null ? "" : text(raw);
            if (header.isEmpty()) {
                throw new IllegalArgumentException("Excel第" + (i + 1) + "列表头为空");
            }
            if (!seen.add(header.toUpperCase(Locale.ROOT))) {
                throw new IllegalArgumentException("Excel表头重复: " + header);
            }
            headers.add(header);
        }
        return headers;
    }

    private static void setPath(JSONObject root, String path, Object value, int rowNumber) {
        String[] segments = path.split("\\.");
        Object current = root;
        for (int i = 0; i < segments.length; i++) {
            Matcher matcher = PATH_SEGMENT.matcher(segments[i]);
            if (!matcher.matches()) {
                throw rowError(rowNumber, "字段路径格式错误: " + path);
            }
            String name = matcher.group(1);
            String indexText = matcher.group(2);
            boolean last = i == segments.length - 1;
            if (!(current instanceof JSONObject)) {
                throw rowError(rowNumber, "字段路径节点冲突: " + path);
            }
            JSONObject object = (JSONObject) current;
            if (indexText == null) {
                if (last) {
                    object.put(name, value);
                } else {
                    Object next = object.get(name);
                    if (next == null) {
                        next = new JSONObject();
                        object.put(name, next);
                    }
                    current = next;
                }
                continue;
            }
            int index = Integer.parseInt(indexText);
            JSONArray array = object.getJSONArray(name);
            if (array == null) {
                array = new JSONArray();
                object.put(name, array);
            }
            while (array.size() <= index) {
                array.add(null);
            }
            if (last) {
                array.set(index, value);
            } else {
                Object next = array.get(index);
                if (next == null) {
                    next = new JSONObject();
                    array.set(index, next);
                }
                current = next;
            }
        }
    }

    private static Object readCell(Cell cell) {
        if (cell == null || cell.getCellType() == CellType.BLANK) {
            return null;
        }
        if (cell.getCellType() == CellType.FORMULA) {
            throw new IllegalArgumentException("导入文件不允许使用公式单元格");
        }
        if (cell.getCellType() == CellType.BOOLEAN) {
            return cell.getBooleanCellValue();
        }
        if (cell.getCellType() == CellType.NUMERIC) {
            if (DateUtil.isCellDateFormatted(cell)) {
                return cell.getLocalDateTimeCellValue().toLocalDate().format(DateTimeFormatter.ISO_LOCAL_DATE);
            }
            return BigDecimal.valueOf(cell.getNumericCellValue()).stripTrailingZeros();
        }
        String value = cell.getStringCellValue().trim();
        return value.isEmpty() ? null : value;
    }

    private static Object convertAttribute(Object value, TradeAttributeDefinition definition, int rowNumber) {
        if (definition.getValueType() == BigDecimal.class) {
            try {
                return value instanceof BigDecimal ? value : new BigDecimal(value.toString());
            } catch (NumberFormatException e) {
                throw rowError(rowNumber, definition.getFieldName() + "必须为数字");
            }
        }
        return text(value);
    }

    private static LocalDate parseDate(Object value, int rowNumber) {
        String text = text(value);
        try {
            return LocalDate.parse(text, DateTimeFormatter.ISO_LOCAL_DATE);
        } catch (Exception e) {
            throw rowError(rowNumber, "DATA_DATE日期格式必须为yyyy-MM-dd: " + text);
        }
    }

    private static boolean isBlankRow(Row row, int columnCount) {
        for (int i = 0; i < columnCount; i++) {
            if (readCell(row.getCell(i)) != null) {
                return false;
            }
        }
        return true;
    }

    private static void requireHeader(List<String> headers, String name) {
        if (headers.stream().noneMatch(header -> name.equalsIgnoreCase(header))) {
            throw new IllegalArgumentException("Excel缺少必填列: " + name);
        }
    }

    private static String text(Object value) {
        return value == null ? "" : value.toString().trim();
    }

    private static IllegalArgumentException rowError(int rowNumber, String message) {
        return new IllegalArgumentException("Excel第" + rowNumber + "行: " + message);
    }

    private static void validateFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("请选择Excel文件");
        }
        if (file.getSize() > MAX_FILE_SIZE) {
            throw new IllegalArgumentException("Excel文件不能超过20MB");
        }
        String name = file.getOriginalFilename();
        if (name == null || !name.toLowerCase(Locale.ROOT).endsWith(".xlsx")) {
            throw new IllegalArgumentException("仅支持xlsx格式");
        }
    }
}
