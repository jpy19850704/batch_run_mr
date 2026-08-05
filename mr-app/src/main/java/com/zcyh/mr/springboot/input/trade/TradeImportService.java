package com.zcyh.mr.springboot.input.trade;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.zcyh.mr.calc.ProductCalculatorRegistry;
import com.zcyh.mr.springboot.input.db.TradeInputRow;
import com.zcyh.mr.springboot.input.common.InputJsonSupport;
import com.zcyh.mr.support.TradeJsonUtil;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Service
public class TradeImportService {
    private final TradeExcelParser parser;
    private final TradeImportRepository repository;
    private final TradeTemplateService templateService;

    public TradeImportService(TradeExcelParser parser, TradeImportRepository repository,
            TradeTemplateService templateService) {
        this.parser = parser;
        this.repository = repository;
        this.templateService = templateService;
    }

    public JSONObject preview(String dataDate, String productCode, MultipartFile file) throws IOException {
        return buildPlan(parseDate(dataDate), normalizeProductCode(productCode), file).toResponse(false);
    }

    @Transactional(transactionManager = "engineDbTransactionManager")
    public JSONObject commit(String dataDate, String productCode, boolean confirmUpdate, MultipartFile file)
            throws IOException {
        ImportPlan plan = buildPlan(parseDate(dataDate), normalizeProductCode(productCode), file);
        if (plan.invalidCount > 0 || plan.conflictCount > 0) {
            throw new IllegalArgumentException("导入文件存在校验错误或交易ID冲突，禁止提交");
        }
        if (plan.updateCount > 0 && !confirmUpdate) {
            throw new IllegalArgumentException("存在" + plan.updateCount + "笔更新交易，必须显式确认更新");
        }
        repository.insert(plan.insertRows);
        repository.update(plan.updateRows);
        return plan.toResponse(true);
    }

    @Transactional(transactionManager = "engineDbTransactionManager")
    public JSONObject delete(List<TradeDeleteKey> rows) {
        if (rows == null || rows.isEmpty()) throw new IllegalArgumentException("删除交易不能为空");
        if (rows.size() > 1000) throw new IllegalArgumentException("单次最多删除1000笔交易");
        Set<String> keys = new HashSet<>();
        for (TradeDeleteKey row : rows) {
            LocalDate date = parseDate(row.getDataDate());
            String instrumentId = required(row.getInstrumentId(), "instrumentId");
            String productCode = normalizeProductCode(row.getProductCode());
            row.setDataDate(date.toString());
            row.setInstrumentId(instrumentId);
            row.setProductCode(productCode);
            if (!keys.add(date + "|" + instrumentId + "|" + productCode)) {
                throw new IllegalArgumentException("删除交易业务键重复: " + instrumentId);
            }
        }
        int deleted = repository.delete(rows);
        if (deleted != rows.size()) {
            throw new IllegalArgumentException("部分交易不存在，删除已回滚");
        }
        JSONObject response = new JSONObject();
        response.put("deletedCount", deleted);
        return response;
    }

    @Transactional(transactionManager = "engineDbTransactionManager")
    public JSONObject edit(TradeEditRequest request) {
        if (request == null) throw new IllegalArgumentException("编辑交易不能为空");
        LocalDate dataDate = parseDate(request.getDataDate());
        String instrumentId = required(request.getInstrumentId(), "instrumentId");
        String productCode = normalizeProductCode(request.getProductCode());
        if (request.getVersionNo() == null || request.getVersionNo() < 1) {
            throw new IllegalArgumentException("versionNo必须为正整数");
        }
        if (!ProductCalculatorRegistry.supports(productCode)) {
            throw new IllegalArgumentException("不支持的产品类型: " + productCode);
        }
        if (request.getContent() == null) {
            throw new IllegalArgumentException("content不能为空");
        }
        JSONObject tradeData = JSON.parseObject(request.getContent().toJSONString());
        String contentInstrumentId = required(tradeData.getString("INSTRUMENT_ID"), "content.INSTRUMENT_ID");
        String contentProductCode = normalizeProductCode(tradeData.getString("PRODUCT_CODE"));
        if (!instrumentId.equals(contentInstrumentId)) {
            throw new IllegalArgumentException("交易ID与content.INSTRUMENT_ID不一致");
        }
        if (!productCode.equals(contentProductCode)) {
            throw new IllegalArgumentException("产品类型与content.PRODUCT_CODE不一致");
        }
        List<String> invalidPaths = templateService.invalidFieldPaths(productCode, tradeData);
        if (!invalidPaths.isEmpty()) {
            throw new IllegalArgumentException("存在无效字段: " + String.join(", ", invalidPaths));
        }
        List<String> fieldErrors = templateService.validateFieldValues(productCode, tradeData);
        if (!fieldErrors.isEmpty()) {
            throw new IllegalArgumentException(String.join("; ", fieldErrors));
        }
        validateEditedTrade(dataDate, productCode, tradeData);
        Map<String, Object> attributes = normalizeEditedAttributes(request.getAttributes());
        int updated = repository.updateEdited(dataDate, instrumentId, productCode,
                request.getVersionNo(), tradeData.toJSONString(), attributes);
        if (updated != 1) {
            throw new IllegalArgumentException("交易不存在或已发生变化，保存失败");
        }
        JSONObject response = new JSONObject();
        response.put("updatedCount", updated);
        response.put("dataDate", dataDate.toString());
        response.put("instrumentId", instrumentId);
        response.put("productCode", productCode);
        response.put("versionNo", request.getVersionNo() + 1);
        return response;
    }

    private ImportPlan buildPlan(LocalDate dataDate, String productCode, MultipartFile file) throws IOException {
        if (!ProductCalculatorRegistry.supports(productCode)) {
            throw new IllegalArgumentException("不支持的产品类型: " + productCode);
        }
        List<TradeImportRow> rows = parser.parse(file, dataDate, productCode);
        ImportPlan plan = new ImportPlan(rows.size());
        Set<String> fileIds = new HashSet<>();
        List<String> ids = new ArrayList<>();
        for (TradeImportRow row : rows) {
            if (!fileIds.add(row.instrumentId)) {
                plan.add(row, "INVALID", "Excel内交易ID重复", null);
            } else {
                ids.add(row.instrumentId);
            }
        }
        Map<String, TradeInputRow> existingById = new HashMap<>();
        for (TradeInputRow existing : repository.findExisting(dataDate, ids)) {
            existingById.put(existing.instrumentId, existing);
        }
        for (TradeImportRow row : rows) {
            if (plan.processedRows.contains(row.rowNumber)) {
                continue;
            }
            List<String> errors = validateProductInput(row);
            if (!errors.isEmpty()) {
                plan.add(row, "INVALID", String.join("; ", errors), null);
                continue;
            }
            TradeInputRow existing = existingById.get(row.instrumentId);
            if (existing == null) {
                plan.insertRows.add(row);
                plan.add(row, "INSERT", null, null);
                continue;
            }
            if (!row.productCode.equals(existing.productCode)) {
                plan.add(row, "CONFLICT", "同数据日期和交易ID已属于产品" + existing.productCode, null);
                continue;
            }
            List<String> changedFields = changedFields(existing, row);
            if (changedFields.isEmpty()) {
                plan.add(row, "UNCHANGED", null, changedFields);
            } else {
                plan.updateRows.add(row);
                plan.add(row, "UPDATE", null, changedFields);
            }
        }
        return plan;
    }

    private static List<String> validateProductInput(TradeImportRow row) {
        try {
            JSONObject normalized = JSON.parseObject(row.tradeData.toJSONString());
            TradeJsonUtil.mergeTrade(normalized, row.productCode, "TRADE");
            return ProductCalculatorRegistry.validateTradeInput(row.productCode, row.dataDate, normalized);
        } catch (Exception e) {
            return java.util.Collections.singletonList(resolveMessage(e));
        }
    }

    private static void validateEditedTrade(LocalDate dataDate, String productCode, JSONObject tradeData) {
        try {
            JSONObject normalized = JSON.parseObject(tradeData.toJSONString());
            TradeJsonUtil.mergeTrade(normalized, productCode, "TRADE");
            List<String> errors = ProductCalculatorRegistry.validateTradeInput(productCode, dataDate, normalized);
            if (!errors.isEmpty()) {
                throw new IllegalArgumentException(String.join("; ", errors));
            }
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException(resolveMessage(e), e);
        }
    }

    private static Map<String, Object> normalizeEditedAttributes(Map<String, Object> input) {
        if (input == null) {
            return null;
        }
        for (String fieldName : input.keySet()) {
            if (TradeAttributeRegistry.findByField(fieldName) == null) {
                throw new IllegalArgumentException("不支持的交易辅助字段: " + fieldName);
            }
        }
        Map<String, Object> normalized = new HashMap<>();
        for (TradeAttributeDefinition definition : TradeAttributeRegistry.definitions()) {
            Object value = input.get(definition.getFieldName());
            if (value == null || String.valueOf(value).trim().isEmpty()) {
                normalized.put(definition.getFieldName(), null);
            } else if (BigDecimal.class == definition.getValueType()) {
                try {
                    normalized.put(definition.getFieldName(), new BigDecimal(String.valueOf(value).trim()));
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException(definition.getFieldName() + "必须为数字");
                }
            } else {
                normalized.put(definition.getFieldName(), String.valueOf(value).trim());
            }
        }
        return normalized;
    }

    private static List<String> changedFields(TradeInputRow existing, TradeImportRow imported) {
        List<String> changed = new ArrayList<>();
        JSONObject oldTrade = JSON.parseObject(existing.tradeContentText);
        if (!InputJsonSupport.deepEquals(oldTrade, imported.tradeData)) {
            changed.add("TRADE_DATA");
        }
        for (TradeAttributeDefinition definition : TradeAttributeRegistry.definitions()) {
            Object oldValue = existing.attributes.get(definition.getFieldName());
            Object newValue = imported.attributes.get(definition.getFieldName());
            if (!equalValue(oldValue, newValue)) {
                changed.add(definition.getFieldName());
            }
        }
        return changed;
    }

    private static boolean equalValue(Object left, Object right) {
        if (left == null || right == null) {
            return left == right;
        }
        if (left instanceof Number && right instanceof Number) {
            return new java.math.BigDecimal(left.toString()).compareTo(new java.math.BigDecimal(right.toString())) == 0;
        }
        return Objects.equals(left.toString(), right.toString());
    }

    private static String normalizeProductCode(String productCode) {
        String value = productCode == null ? "" : productCode.trim().toUpperCase(java.util.Locale.ROOT);
        if (value.isEmpty()) {
            throw new IllegalArgumentException("productCode不能为空");
        }
        return value;
    }

    private static LocalDate parseDate(String dataDate) {
        String value = dataDate == null ? "" : dataDate.trim();
        try {
            return LocalDate.parse(value, DateTimeFormatter.ISO_LOCAL_DATE);
        } catch (Exception e) {
            throw new IllegalArgumentException("dataDate格式必须为yyyy-MM-dd: " + dataDate);
        }
    }

    private static String required(String value, String field) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(field + "不能为空");
        return normalized;
    }

    private static String resolveMessage(Exception e) {
        String message = e.getMessage();
        return message == null || message.trim().isEmpty() ? e.getClass().getSimpleName() : message;
    }

    private static final class ImportPlan {
        final int totalCount;
        int insertCount;
        int updateCount;
        int unchangedCount;
        int invalidCount;
        int conflictCount;
        final List<TradeImportRow> insertRows = new ArrayList<>();
        final List<TradeImportRow> updateRows = new ArrayList<>();
        final Set<Integer> processedRows = new HashSet<>();
        final JSONArray details = new JSONArray();

        ImportPlan(int totalCount) {
            this.totalCount = totalCount;
        }

        void add(TradeImportRow row, String action, String message, List<String> changedFields) {
            processedRows.add(row.rowNumber);
            switch (action) {
                case "INSERT": insertCount++; break;
                case "UPDATE": updateCount++; break;
                case "UNCHANGED": unchangedCount++; break;
                case "CONFLICT": conflictCount++; break;
                default: invalidCount++; break;
            }
            JSONObject detail = new JSONObject();
            detail.put("rowNumber", row.rowNumber);
            detail.put("instrumentId", row.instrumentId);
            detail.put("productCode", row.productCode);
            detail.put("action", action);
            if (message != null) {
                detail.put("message", message);
            }
            if (changedFields != null && !changedFields.isEmpty()) {
                detail.put("changedFields", changedFields);
            }
            details.add(detail);
        }

        JSONObject toResponse(boolean committed) {
            JSONObject response = new JSONObject();
            response.put("committed", committed);
            response.put("totalCount", totalCount);
            response.put("insertCount", insertCount);
            response.put("updateCount", updateCount);
            response.put("unchangedCount", unchangedCount);
            response.put("invalidCount", invalidCount);
            response.put("conflictCount", conflictCount);
            response.put("requiresUpdateConfirmation", updateCount > 0);
            response.put("details", details);
            return response;
        }
    }
}
