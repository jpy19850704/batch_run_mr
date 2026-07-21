package com.zcyh.mr.springboot.input.market;

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
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Component
public class MarketExcelParser {
    private static final long MAX_FILE_SIZE = 20L * 1024L * 1024L;
    private static final int MAX_ROWS = 100_000;
    private static final Set<String> SYSTEM_FIELDS = new HashSet<String>(Arrays.asList(
            "ID", "DATA_DATE", "MARKET_DATA_TYPE", "CURVE_TYPE", "FIXING_ID", "CURVE_DATA",
            "CONTENT_FORMAT", "VERSION_NO", "SOURCE_SYSTEM", "CREATED_AT", "UPDATED_AT"));

    public List<MarketImportRow> parse(MultipartFile file, LocalDate dataDate, String marketDataType)
            throws IOException {
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
            requireHeader(headers, "CURVE_ID");
            Map<String, CurveBuilder> builders = new LinkedHashMap<String, CurveBuilder>();
            for (int rowIndex = sheet.getFirstRowNum() + 1; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
                Row row = sheet.getRow(rowIndex);
                if (row == null || isBlankRow(row, headers.size())) {
                    continue;
                }
                parsePointRow(row, headers, dataDate, marketDataType, builders);
            }
            if (builders.isEmpty()) {
                throw new IllegalArgumentException("Excel中不存在可导入的市场数据");
            }
            List<MarketImportRow> result = new ArrayList<MarketImportRow>();
            for (CurveBuilder builder : builders.values()) {
                result.add(builder.build());
            }
            return result;
        }
    }

    private static void parsePointRow(Row row, List<String> headers, LocalDate dataDate,
            String marketDataType, Map<String, CurveBuilder> builders) {
        int rowNumber = row.getRowNum() + 1;
        String curveId = null;
        Map<String, Object> meta = new LinkedHashMap<String, Object>();
        JSONObject point = new JSONObject();
        for (int i = 0; i < headers.size(); i++) {
            String header = headers.get(i);
            String normalized = header.toUpperCase(Locale.ROOT);
            Object value = readCell(row.getCell(i));
            if ("CURVE_ID".equals(normalized)) {
                curveId = text(value);
            } else if (SYSTEM_FIELDS.contains(normalized)) {
                if (value != null) {
                    throw rowError(rowNumber, header + "由系统生成，不允许在Excel中维护");
                }
            } else if (MarketImportSchema.isPointField(marketDataType, normalized) && value != null) {
                point.put(header, value);
            } else if (value != null) {
                meta.put(normalized, value);
            }
        }
        if (curveId == null || curveId.isEmpty()) {
            throw rowError(rowNumber, "CURVE_ID不能为空");
        }
        if (point.isEmpty()) {
            throw rowError(rowNumber, "曲线点位字段不能为空");
        }
        CurveBuilder builder = builders.get(curveId);
        if (builder == null) {
            builder = new CurveBuilder(rowNumber, dataDate, marketDataType, curveId, meta);
            builders.put(curveId, builder);
        } else {
            builder.requireSameMeta(meta, rowNumber);
        }
        builder.addPoint(point, rowNumber);
    }

    private static final class CurveBuilder {
        private final int rowNumber;
        private final LocalDate dataDate;
        private final String marketDataType;
        private final String curveId;
        private final Map<String, Object> meta;
        private final JSONArray points = new JSONArray();
        private final Set<String> pointKeys = new LinkedHashSet<String>();

        private CurveBuilder(int rowNumber, LocalDate dataDate, String marketDataType,
                String curveId, Map<String, Object> meta) {
            this.rowNumber = rowNumber;
            this.dataDate = dataDate;
            this.marketDataType = marketDataType;
            this.curveId = curveId;
            this.meta = new LinkedHashMap<String, Object>(meta);
        }

        private void requireSameMeta(Map<String, Object> current, int currentRowNumber) {
            if (!equalMaps(meta, current)) {
                throw rowError(currentRowNumber, "同一CURVE_ID的曲线属性不一致: " + curveId);
            }
        }

        private void addPoint(JSONObject point, int currentRowNumber) {
            String key = pointKey(marketDataType, point, meta);
            if (key != null && !pointKeys.add(key)) {
                throw rowError(currentRowNumber, "曲线点位重复: " + curveId + " / " + key);
            }
            points.add(point);
        }

        private MarketImportRow build() {
            MarketImportRow row = new MarketImportRow();
            row.rowNumber = rowNumber;
            row.pointCount = points.size();
            row.dataDate = dataDate;
            row.marketDataType = marketDataType;
            row.curveId = curveId;
            row.curveContent.put("CURVE_TYPE", marketDataType);
            row.curveContent.put("DATA_DATE", dataDate.format(DateTimeFormatter.BASIC_ISO_DATE));
            if ("FIXING".equals(marketDataType)) {
                row.curveContent.put("FIXING_ID", curveId);
            } else {
                row.curveContent.put("CURVE_ID", curveId);
            }
            for (Map.Entry<String, Object> entry : meta.entrySet()) {
                row.curveContent.put(entry.getKey(), entry.getValue());
            }
            row.curveContent.put("CURVE_DATA", points);
            return row;
        }
    }

    private static String pointKey(String marketDataType, JSONObject point, Map<String, Object> meta) {
        switch (marketDataType) {
            case "IR_SPOT":
            case "CREDIT_SPOT":
            case "EQ_SPOT":
            case "COMM_SPOT":
                return keyIfPresent(point, "TERM");
            case "FX_SPOT":
                return keyIfPresent(point, "CURRENCY");
            case "FIXING":
                return keyIfPresent(point, "TRADE_DATE");
            case "IR_VOL":
            case "FX_VOL":
            case "EQ_VOL":
            case "COMM_VOL":
                String axis = Objects.toString(meta.get("AXIS2_TYPE"),
                        "IR_VOL".equals(marketDataType) ? "UNDERLYING_TERM" : "DELTA")
                        .trim().toUpperCase(Locale.ROOT);
                if ("NONE".equals(axis)) {
                    return keyIfPresent(point, "OPTION_TERM");
                }
                return compositeKeyIfPresent(point, "OPTION_TERM", axis);
            default:
                return null;
        }
    }

    private static String keyIfPresent(JSONObject point, String field) {
        Object value = readIgnoreCase(point, field);
        return value == null ? null : field + "=" + value;
    }

    private static String compositeKeyIfPresent(JSONObject point, String left, String right) {
        Object leftValue = readIgnoreCase(point, left);
        Object rightValue = readIgnoreCase(point, right);
        return leftValue == null || rightValue == null ? null
                : left + "=" + leftValue + ";" + right + "=" + rightValue;
    }

    private static Object readIgnoreCase(JSONObject object, String field) {
        for (String key : object.keySet()) {
            if (field.equalsIgnoreCase(key)) {
                return object.get(key);
            }
        }
        return null;
    }

    private static boolean equalMaps(Map<String, Object> left, Map<String, Object> right) {
        if (!left.keySet().equals(right.keySet())) {
            return false;
        }
        for (String key : left.keySet()) {
            Object leftValue = left.get(key);
            Object rightValue = right.get(key);
            if (leftValue instanceof Number && rightValue instanceof Number) {
                if (new BigDecimal(leftValue.toString()).compareTo(new BigDecimal(rightValue.toString())) != 0) {
                    return false;
                }
            } else if (!Objects.equals(leftValue, rightValue)) {
                return false;
            }
        }
        return true;
    }

    private static List<String> readHeaders(Row row) {
        if (row == null) {
            throw new IllegalArgumentException("Excel缺少表头");
        }
        List<String> headers = new ArrayList<String>();
        Set<String> seen = new HashSet<String>();
        for (int i = 0; i < row.getLastCellNum(); i++) {
            Object raw = readCell(row.getCell(i));
            String header = text(raw);
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
                return cell.getLocalDateTimeCellValue().toLocalDate()
                        .format(DateTimeFormatter.BASIC_ISO_DATE);
            }
            return BigDecimal.valueOf(cell.getNumericCellValue()).stripTrailingZeros();
        }
        String value = cell.getStringCellValue().trim();
        return value.isEmpty() ? null : value;
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
        for (String header : headers) {
            if (name.equalsIgnoreCase(header)) {
                return;
            }
        }
        throw new IllegalArgumentException("Excel缺少必填列: " + name);
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
