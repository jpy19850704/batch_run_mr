package com.zcyh.mr.springboot.input.market;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.zcyh.mr.loader.MarketDataLoader;
import com.zcyh.mr.springboot.input.common.InputJsonSupport;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
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
    private final MarketTemplateService templateService;

    public MarketImportService(MarketExcelParser parser, MarketImportRepository repository,
            MarketTemplateService templateService) {
        this.parser = parser;
        this.repository = repository;
        this.templateService = templateService;
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
    public JSONObject edit(MarketEditRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("编辑市场数据不能为空");
        }
        LocalDate dataDate = parseDate(request.getDataDate());
        String marketDataType = normalizeEditType(request.getMarketDataType());
        String curveId = required(request.getCurveId(), "curveId");
        if (request.getVersionNo() == null || request.getVersionNo() < 1) {
            throw new IllegalArgumentException("versionNo必须为正整数");
        }
        String dataKind = normalizeDataKind(request.getDataKind());
        if ("RAW".equals(dataKind) && !"CURVE_GENERATION".equals(marketDataType)) {
            throw new IllegalArgumentException("RAW数据的marketDataType必须为CURVE_GENERATION");
        }
        if ("MARKET".equals(dataKind) && "CURVE_GENERATION".equals(marketDataType)) {
            throw new IllegalArgumentException("CURVE_GENERATION必须使用RAW数据类型");
        }
        if (request.getContent() == null) {
            throw new IllegalArgumentException("content不能为空");
        }
        JSONObject marketData = JSON.parseObject(request.getContent().toJSONString());
        String identifierField = "FIXING".equals(marketDataType) ? "FIXING_ID" : "CURVE_ID";
        String contentCurveId = required(marketData.getString(identifierField), "content." + identifierField);
        String conversionType = null;
        if ("MARKET".equals(dataKind) && !curveId.equals(contentCurveId)) {
            throw new IllegalArgumentException("曲线ID与content." + identifierField + "不一致");
        }
        if ("RAW".equals(dataKind)) {
            conversionType = required(request.getConversionType(), "conversionType");
            String contentConversionType = required(marketData.getString("CONVERSION_TYPE"),
                    "content.CONVERSION_TYPE");
            if (!conversionType.equals(contentConversionType)) {
                throw new IllegalArgumentException("转换类型与content.CONVERSION_TYPE不一致");
            }
        }
        List<String> invalidPaths = templateService.invalidFieldPaths(marketDataType, marketData);
        if (!invalidPaths.isEmpty()) {
            throw new IllegalArgumentException("存在无效字段: " + String.join(", ", invalidPaths));
        }
        List<String> fieldErrors = templateService.validateFieldValues(marketDataType, marketData);
        if (!fieldErrors.isEmpty()) {
            throw new IllegalArgumentException(String.join("; ", fieldErrors));
        }
        validateContentDate(dataDate, dataKind, marketData);
        validateEditedMarket(dataDate, dataKind, marketData);
        int updated = "RAW".equals(dataKind)
                ? repository.updateEditedRaw(dataDate, marketDataType, conversionType, curveId,
                        request.getVersionNo(), marketData.toJSONString())
                : repository.updateEditedMarket(dataDate, marketDataType, curveId,
                        request.getVersionNo(), marketData.toJSONString());
        if (updated != 1) {
            throw new IllegalArgumentException("市场数据不存在或已发生变化，保存失败");
        }
        JSONObject response = new JSONObject();
        response.put("updatedCount", updated);
        response.put("dataDate", dataDate.toString());
        response.put("marketDataType", marketDataType);
        response.put("curveId", curveId);
        response.put("dataKind", dataKind);
        response.put("versionNo", request.getVersionNo() + 1);
        return response;
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
            if (InputJsonSupport.deepEquals(oldJson, row.curveContent)) {
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

    private static void validateEditedMarket(LocalDate dataDate, String dataKind, JSONObject marketData) {
        if ("RAW".equals(dataKind)) {
            return;
        }
        JSONArray input = new JSONArray();
        input.add(marketData);
        JSONArray messages = new JSONArray();
        try {
            new MarketDataLoader(dataDate, messages).loadBaseMarketData(input);
        } catch (Exception e) {
            throw new IllegalArgumentException(resolveMessage(e), e);
        }
        for (Object item : messages) {
            if (!(item instanceof JSONObject)) {
                continue;
            }
            JSONObject message = (JSONObject) item;
            if (!"WARNING".equalsIgnoreCase(message.getString("level"))) {
                throw new IllegalArgumentException(message.getString("info") == null
                        ? "市场数据校验失败" : message.getString("info"));
            }
        }
    }

    private static void validateContentDate(LocalDate dataDate, String dataKind, JSONObject marketData) {
        String value = required(marketData.getString("DATA_DATE"), "content.DATA_DATE");
        String expected = "RAW".equals(dataKind)
                ? dataDate.format(DateTimeFormatter.BASIC_ISO_DATE)
                : dataDate.format(DateTimeFormatter.ISO_LOCAL_DATE);
        if (!expected.equals(value)) {
            throw new IllegalArgumentException("数据日期与content.DATA_DATE不一致，要求格式为"
                    + ("RAW".equals(dataKind) ? "yyyyMMdd" : "yyyy-MM-dd"));
        }
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

    private static String normalizeType(String marketDataType) {
        String value = marketDataType == null ? "" : marketDataType.trim().toUpperCase(Locale.ROOT);
        if (!MarketImportSchema.supportedTypes().contains(value)) {
            throw new IllegalArgumentException("不支持的市场数据类型: " + marketDataType);
        }
        return value;
    }

    private static String normalizeEditType(String marketDataType) {
        String value = marketDataType == null ? "" : marketDataType.trim().toUpperCase(Locale.ROOT);
        if (!"CURVE_GENERATION".equals(value) && !MarketImportSchema.supportedTypes().contains(value)) {
            throw new IllegalArgumentException("不支持的市场数据类型: " + marketDataType);
        }
        return value;
    }

    private static String normalizeDataKind(String dataKind) {
        String value = dataKind == null ? "" : dataKind.trim().toUpperCase(Locale.ROOT);
        if (!"MARKET".equals(value) && !"RAW".equals(value)) {
            throw new IllegalArgumentException("dataKind必须为MARKET或RAW");
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
            return LocalDate.parse(value, DateTimeFormatter.ISO_LOCAL_DATE);
        } catch (Exception e) {
            throw new IllegalArgumentException("dataDate格式必须为yyyy-MM-dd: " + dataDate);
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
