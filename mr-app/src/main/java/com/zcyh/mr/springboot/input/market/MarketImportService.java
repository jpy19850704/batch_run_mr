package com.zcyh.mr.springboot.input.market;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.zcyh.mr.loader.MarketDataLoader;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Service
public class MarketImportService {
    private final MarketExcelParser parser;
    private final MarketImportRepository repository;

    public MarketImportService(MarketExcelParser parser, MarketImportRepository repository) {
        this.parser = parser;
        this.repository = repository;
    }

    public JSONObject preview(String dataDate, String marketDataType, MultipartFile file) throws IOException {
        return buildPlan(parseDate(dataDate), normalizeType(marketDataType), file).toResponse(false);
    }

    @Transactional(transactionManager = "engineDbTransactionManager")
    public JSONObject commit(String dataDate, String marketDataType, boolean confirmUpdate, MultipartFile file)
            throws IOException {
        ImportPlan plan = buildPlan(parseDate(dataDate), normalizeType(marketDataType), file);
        if (plan.invalidCount > 0 || plan.conflictCount > 0) {
            throw new IllegalArgumentException("导入文件存在校验错误或版本冲突，禁止提交");
        }
        if (plan.updateCount > 0 && !confirmUpdate) {
            throw new IllegalArgumentException("存在" + plan.updateCount + "条更新曲线，必须显式确认更新");
        }
        repository.insert(plan.insertRows);
        repository.update(plan.updateRows);
        return plan.toResponse(true);
    }

    @Transactional(transactionManager = "engineDbTransactionManager")
    public JSONObject delete(List<MarketDeleteKey> rows) {
        if (rows == null || rows.isEmpty()) throw new IllegalArgumentException("删除市场数据不能为空");
        if (rows.size() > 1000) throw new IllegalArgumentException("单次最多删除1000条市场数据");
        Set<String> keys = new HashSet<>();
        for (MarketDeleteKey row : rows) {
            LocalDate date = parseDate(row.getDataDate());
            String type = normalizeType(row.getMarketDataType());
            String curveId = required(row.getCurveId(), "curveId");
            if (row.getVersionNo() == null || row.getVersionNo() < 1) {
                throw new IllegalArgumentException("versionNo必须为正整数");
            }
            row.setDataDate(date.toString());
            row.setMarketDataType(type);
            row.setCurveId(curveId);
            String key = date + "|" + type + "|" + curveId + "|" + row.getVersionNo();
            if (!keys.add(key)) throw new IllegalArgumentException("删除市场数据业务键重复: " + curveId);
        }
        int deleted = repository.delete(rows);
        if (deleted != rows.size()) throw new IllegalArgumentException("部分市场数据不存在，删除已回滚");
        JSONObject response = new JSONObject();
        response.put("deletedCount", deleted);
        return response;
    }

    private ImportPlan buildPlan(LocalDate dataDate, String marketDataType, MultipartFile file) throws IOException {
        List<MarketImportRow> rows = parser.parse(file, dataDate, marketDataType);
        ImportPlan plan = new ImportPlan(rows.size(), rows.stream().mapToInt(row -> row.pointCount).sum());
        ValidationResult validation = validateMarketData(dataDate, rows);
        List<String> curveIds = new ArrayList<String>();
        for (MarketImportRow row : rows) {
            curveIds.add(row.curveId);
        }
        Map<String, String> existing = repository.findExisting(dataDate, marketDataType, curveIds);
        Set<String> nonPrimaryVersionCurveIds = repository.findNonPrimaryVersionCurveIds(
                dataDate, marketDataType, curveIds);
        for (MarketImportRow row : rows) {
            List<String> errors = messagesFor(validation.errors, row.curveId);
            List<String> warnings = messagesFor(validation.warnings, row.curveId);
            if (!errors.isEmpty()) {
                plan.add(row, "INVALID", String.join("; ", errors), null, warnings);
                continue;
            }
            if (nonPrimaryVersionCurveIds.contains(row.curveId)) {
                plan.add(row, "CONFLICT", "同日同类型曲线存在非1版本，禁止导入", null, warnings);
                continue;
            }
            String oldContent = existing.get(row.curveId);
            if (oldContent == null) {
                plan.insertRows.add(row);
                plan.add(row, "INSERT", null, null, warnings);
                continue;
            }
            JSONObject oldJson;
            try {
                oldJson = JSON.parseObject(oldContent);
            } catch (Exception e) {
                plan.add(row, "CONFLICT", "数据库现有曲线JSON格式错误", null, warnings);
                continue;
            }
            if (deepEquals(oldJson, row.curveContent)) {
                plan.add(row, "UNCHANGED", null, new ArrayList<String>(), warnings);
            } else {
                plan.updateRows.add(row);
                plan.add(row, "UPDATE", null,
                        Arrays.asList("CURVE_CONTENT_TEXT"), warnings);
            }
        }
        return plan;
    }

    private static ValidationResult validateMarketData(LocalDate dataDate, List<MarketImportRow> rows) {
        JSONArray input = new JSONArray();
        for (MarketImportRow row : rows) {
            input.add(row.curveContent);
        }
        JSONArray messages = new JSONArray();
        try {
            new MarketDataLoader(dataDate, messages).loadBaseMarketData(input);
        } catch (Exception e) {
            ValidationResult result = new ValidationResult();
            result.errors.computeIfAbsent("", key -> new ArrayList<String>())
                    .add(resolveMessage(e));
            return result;
        }
        ValidationResult result = new ValidationResult();
        for (Object item : messages) {
            if (!(item instanceof JSONObject)) {
                continue;
            }
            JSONObject message = (JSONObject) item;
            String curveId = Objects.toString(message.get("CURVE_ID"), "");
            String info = Objects.toString(message.get("info"), "市场数据校验失败");
            Map<String, List<String>> target = "WARNING".equalsIgnoreCase(message.getString("level"))
                    ? result.warnings : result.errors;
            target.computeIfAbsent(curveId, key -> new ArrayList<String>()).add(info);
        }
        return result;
    }

    private static List<String> messagesFor(Map<String, List<String>> messages, String curveId) {
        List<String> result = new ArrayList<String>();
        List<String> global = messages.get("");
        if (global != null) {
            result.addAll(global);
        }
        List<String> specific = messages.get(curveId);
        if (specific != null) {
            result.addAll(specific);
        }
        return result;
    }

    private static boolean deepEquals(Object left, Object right) {
        if (left == null || right == null) {
            return left == right;
        }
        if (left instanceof Number && right instanceof Number) {
            return new BigDecimal(left.toString()).compareTo(new BigDecimal(right.toString())) == 0;
        }
        if (left instanceof Map && right instanceof Map) {
            Map<?, ?> leftMap = (Map<?, ?>) left;
            Map<?, ?> rightMap = (Map<?, ?>) right;
            if (!leftMap.keySet().equals(rightMap.keySet())) {
                return false;
            }
            for (Object key : leftMap.keySet()) {
                if (!deepEquals(leftMap.get(key), rightMap.get(key))) {
                    return false;
                }
            }
            return true;
        }
        if (left instanceof List && right instanceof List) {
            List<?> leftList = (List<?>) left;
            List<?> rightList = (List<?>) right;
            if (leftList.size() != rightList.size()) {
                return false;
            }
            for (int i = 0; i < leftList.size(); i++) {
                if (!deepEquals(leftList.get(i), rightList.get(i))) {
                    return false;
                }
            }
            return true;
        }
        return Objects.equals(left, right);
    }

    private static String normalizeType(String marketDataType) {
        String value = marketDataType == null ? "" : marketDataType.trim().toUpperCase(Locale.ROOT);
        if (!MarketImportSchema.supportedTypes().contains(value)) {
            throw new IllegalArgumentException("不支持的市场数据类型: " + marketDataType);
        }
        return value;
    }

    private static String required(String value, String field) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(field + "不能为空");
        return normalized;
    }

    private static LocalDate parseDate(String dataDate) {
        String value = dataDate == null ? "" : dataDate.trim();
        try {
            return value.matches("\\d{8}")
                    ? LocalDate.parse(value, DateTimeFormatter.BASIC_ISO_DATE)
                    : LocalDate.parse(value, DateTimeFormatter.ISO_LOCAL_DATE);
        } catch (Exception e) {
            throw new IllegalArgumentException("dataDate格式错误: " + dataDate);
        }
    }

    private static String resolveMessage(Exception e) {
        String message = e.getMessage();
        return message == null || message.trim().isEmpty() ? e.getClass().getSimpleName() : message;
    }

    private static final class ValidationResult {
        final Map<String, List<String>> errors = new HashMap<String, List<String>>();
        final Map<String, List<String>> warnings = new HashMap<String, List<String>>();
    }

    private static final class ImportPlan {
        final int totalCount;
        final int totalPointCount;
        int insertCount;
        int updateCount;
        int unchangedCount;
        int invalidCount;
        int conflictCount;
        int warningCount;
        final List<MarketImportRow> insertRows = new ArrayList<MarketImportRow>();
        final List<MarketImportRow> updateRows = new ArrayList<MarketImportRow>();
        final JSONArray details = new JSONArray();

        ImportPlan(int totalCount, int totalPointCount) {
            this.totalCount = totalCount;
            this.totalPointCount = totalPointCount;
        }

        void add(MarketImportRow row, String action, String message,
                List<String> changedFields, List<String> warnings) {
            switch (action) {
                case "INSERT": insertCount++; break;
                case "UPDATE": updateCount++; break;
                case "UNCHANGED": unchangedCount++; break;
                case "CONFLICT": conflictCount++; break;
                default: invalidCount++; break;
            }
            warningCount += warnings == null ? 0 : warnings.size();
            JSONObject detail = new JSONObject();
            detail.put("rowNumber", row.rowNumber);
            detail.put("curveId", row.curveId);
            detail.put("marketDataType", row.marketDataType);
            detail.put("pointCount", row.pointCount);
            detail.put("action", action);
            if (message != null) {
                detail.put("message", message);
            }
            if (changedFields != null && !changedFields.isEmpty()) {
                detail.put("changedFields", changedFields);
            }
            if (warnings != null && !warnings.isEmpty()) {
                detail.put("warnings", warnings);
            }
            details.add(detail);
        }

        JSONObject toResponse(boolean committed) {
            JSONObject response = new JSONObject();
            response.put("committed", committed);
            response.put("totalCount", totalCount);
            response.put("totalPointCount", totalPointCount);
            response.put("insertCount", insertCount);
            response.put("updateCount", updateCount);
            response.put("unchangedCount", unchangedCount);
            response.put("invalidCount", invalidCount);
            response.put("conflictCount", conflictCount);
            response.put("warningCount", warningCount);
            response.put("requiresUpdateConfirmation", updateCount > 0);
            response.put("details", details);
            return response;
        }
    }
}
