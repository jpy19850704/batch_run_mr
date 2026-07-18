package com.zcyh.mr.springboot.measurement.saccr;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.zcyh.mr.saccr.model.SaccrNettingSet;
import com.zcyh.mr.saccr.model.SaccrTrade;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * SA-CCR 计量输入组装服务。
 */
@Service
public class SaccrInputQueryService {
    private final JdbcTemplate engineDbJdbcTemplate;
    private final JdbcTemplate engineResultDbJdbcTemplate;
    private final SaccrTradeInputConvertService tradeInputConvertService;

    public SaccrInputQueryService(
            @Qualifier("engineDbJdbcTemplate") JdbcTemplate engineDbJdbcTemplate,
            @Qualifier("engineResultDbJdbcTemplate") JdbcTemplate engineResultDbJdbcTemplate,
            SaccrTradeInputConvertService tradeInputConvertService) {
        this.engineDbJdbcTemplate = engineDbJdbcTemplate;
        this.engineResultDbJdbcTemplate = engineResultDbJdbcTemplate;
        this.tradeInputConvertService = tradeInputConvertService;
    }

    public SaccrRunInput build(String batchId, String dataDate) {
        String safeBatchId = requireText(batchId, "batch_id");
        String safeDataDate = normalizeDataDate(dataDate);

        List<TradeScopeRow> scopeRows = queryTradeScope(safeDataDate);
        Map<String, TradeResultRow> resultRows = queryTradeResults(safeBatchId, safeDataDate, scopeRows);
        Map<String, NettingSetInputRow> nettingSetRows = queryNettingSets(safeDataDate, scopeRows);

        List<SaccrTradeRow> tradeRows = new ArrayList<>();
        Map<String, SaccrNettingSet> nettingSets = new LinkedHashMap<>();
        Map<String, String> effectiveSetByInstrument = new LinkedHashMap<>();

        for (TradeScopeRow scope : scopeRows) {
            TradeResultRow result = resultRows.get(scope.instrumentId);
            if (result == null) {
                throw new IllegalArgumentException("未找到批次 " + safeBatchId + " 下交易 "
                        + scope.instrumentId + " 的估值结果");
            }
            String nettingMode;
            String effectiveNettingSetId;
            NettingSetInputRow nettingSetInput = null;
            if (scope.nettingSetId == null) {
                nettingMode = "TRADE";
                effectiveNettingSetId = "SINGLE_" + scope.instrumentId;
            } else {
                nettingMode = "NETTING_SET";
                effectiveNettingSetId = scope.nettingSetId;
                nettingSetInput = nettingSetRows.get(scope.nettingSetId);
                if (nettingSetInput == null) {
                    throw new IllegalArgumentException("SACCR_NETTING_SET 缺少 NETTING_SET_ID: " + scope.nettingSetId);
                }
                if (!scope.counterpartyId.equals(nettingSetInput.counterpartyId)) {
                    throw new IllegalArgumentException("交易 " + scope.instrumentId
                            + " 的 COUNTERPARTY_ID 与净额集合不一致: " + scope.nettingSetId);
                }
            }

            SaccrTradeConvertContext convertContext = new SaccrTradeConvertContext(
                    safeBatchId,
                    safeDataDate,
                    scope.instrumentId,
                    result.productCode,
                    result.valuationCny,
                    result.tradeInput,
                    scope.counterpartyId,
                    nettingMode,
                    effectiveNettingSetId);
            SaccrTradeRow tradeRow = tradeInputConvertService.convert(convertContext);
            tradeRows.add(tradeRow);
            effectiveSetByInstrument.put(scope.instrumentId, effectiveNettingSetId);

            SaccrNettingSet ns = nettingSets.get(effectiveNettingSetId);
            if (ns == null) {
                ns = createNettingSet(scope, nettingMode, effectiveNettingSetId, nettingSetInput);
                ns.trades = new ArrayList<>();
                nettingSets.put(effectiveNettingSetId, ns);
            }
            if (!ns.counterpartyId.equals(scope.counterpartyId)) {
                throw new IllegalArgumentException("净额集合 " + effectiveNettingSetId + " 下存在多个 COUNTERPARTY_ID");
            }
            ns.trades.add(tradeRow.trade);
        }

        List<SaccrCollateralOutputRow> collateralRows = queryCollateralRows(
                safeBatchId,
                safeDataDate,
                effectiveSetByInstrument,
                nettingSets.keySet());
        applyCollateral(nettingSets, collateralRows);

        return new SaccrRunInput(safeBatchId, safeDataDate,
                new ArrayList<>(nettingSets.values()), tradeRows, collateralRows);
    }

    private SaccrNettingSet createNettingSet(TradeScopeRow scope,
                                             String nettingMode,
                                             String effectiveNettingSetId,
                                             NettingSetInputRow nettingSetInput) {
        SaccrNettingSet ns = new SaccrNettingSet();
        ns.nettingMode = nettingMode;
        ns.nettingSetId = effectiveNettingSetId;
        ns.counterpartyId = scope.counterpartyId;
        if ("TRADE".equals(nettingMode)) {
            ns.isMargined = false;
            ns.marginType = "NONE";
            ns.threshold = 0.0;
            ns.mta = 0.0;
            ns.nica = 0.0;
            ns.mporDays = 0;
            return ns;
        }

        String marginType = nettingSetInput.marginType;
        ns.marginType = marginType;
        ns.isMargined = !"NONE".equals(marginType);
        ns.threshold = nettingSetInput.thresholdCny;
        ns.mta = nettingSetInput.mtaCny;
        ns.nica = nettingSetInput.nicaCny;
        ns.mporDays = ns.isMargined ? nettingSetInput.mporDays : 0;
        return ns;
    }

    private List<TradeScopeRow> queryTradeScope(String dataDate) {
        String sql = "SELECT DATA_DATE, INSTRUMENT_ID, COUNTERPARTY_ID, NETTING_SET_ID "
                + "FROM SACCR_TRADE_CP WHERE DATA_DATE=STR_TO_DATE(?, '%Y%m%d') ORDER BY INSTRUMENT_ID";
        try {
            List<Map<String, Object>> rows = engineDbJdbcTemplate.queryForList(sql, dataDate);
            if (rows.isEmpty()) {
                throw new IllegalArgumentException("SACCR_TRADE_CP 未找到 DATA_DATE=" + dataDate + " 的交易范围");
            }
            List<TradeScopeRow> result = new ArrayList<>();
            Set<String> instrumentIds = new LinkedHashSet<>();
            for (Map<String, Object> row : rows) {
                TradeScopeRow item = new TradeScopeRow();
                item.instrumentId = requireText(stringValue(row.get("INSTRUMENT_ID")), "INSTRUMENT_ID");
                item.counterpartyId = requireText(stringValue(row.get("COUNTERPARTY_ID")), "COUNTERPARTY_ID");
                item.nettingSetId = trimToNull(stringValue(row.get("NETTING_SET_ID")));
                if (!instrumentIds.add(item.instrumentId)) {
                    throw new IllegalArgumentException("SACCR_TRADE_CP 存在重复 INSTRUMENT_ID: " + item.instrumentId);
                }
                result.add(item);
            }
            return result;
        } catch (DataAccessException ex) {
            throw new IllegalStateException("读取 SACCR_TRADE_CP 失败: " + ex.getMessage(), ex);
        }
    }

    private Map<String, TradeResultRow> queryTradeResults(String batchId, String dataDate, List<TradeScopeRow> scopeRows) {
        List<String> instrumentIds = new ArrayList<>();
        for (TradeScopeRow row : scopeRows) {
            instrumentIds.add(row.instrumentId);
        }
        String sql = "SELECT INSTRUMENT_ID, PRODUCT_CODE, VALUATION_CNY, STATUS, TRADE_INPUT_JSON "
                + "FROM TB_OUT_TRADE_RESULT_DETAIL "
                + "WHERE BATCH_ID = ? AND DATA_DATE=STR_TO_DATE(?, '%Y%m%d') AND INSTRUMENT_ID IN (" + placeholders(instrumentIds.size()) + ")";
        List<Object> params = new ArrayList<>();
        params.add(batchId);
        params.add(dataDate);
        params.addAll(instrumentIds);
        try {
            List<Map<String, Object>> rows = engineResultDbJdbcTemplate.queryForList(sql, params.toArray());
            Map<String, TradeResultRow> result = new LinkedHashMap<>();
            for (Map<String, Object> row : rows) {
                String instrumentId = requireText(stringValue(row.get("INSTRUMENT_ID")), "INSTRUMENT_ID");
                String status = requireText(stringValue(row.get("STATUS")), "STATUS");
                if (!"SUCCESS".equalsIgnoreCase(status)) {
                    throw new IllegalArgumentException("交易 " + instrumentId + " 估值结果状态不是 SUCCESS: " + status);
                }
                BigDecimal valuation = requireDecimal(row.get("VALUATION_CNY"), "VALUATION_CNY", instrumentId);
                String tradeInputText = requireText(stringValue(row.get("TRADE_INPUT_JSON")), "TRADE_INPUT_JSON");
                Object parsed = JSON.parse(tradeInputText);
                if (!(parsed instanceof JSONObject)) {
                    throw new IllegalArgumentException("交易 " + instrumentId + " 的 TRADE_INPUT_JSON 必须是 JSON 对象");
                }
                TradeResultRow item = new TradeResultRow();
                item.instrumentId = instrumentId;
                item.productCode = requireText(stringValue(row.get("PRODUCT_CODE")), "PRODUCT_CODE");
                item.valuationCny = valuation.doubleValue();
                item.tradeInput = (JSONObject) parsed;
                if (result.put(instrumentId, item) != null) {
                    throw new IllegalArgumentException("TB_OUT_TRADE_RESULT_DETAIL 存在重复 INSTRUMENT_ID: " + instrumentId);
                }
            }
            return result;
        } catch (DataAccessException ex) {
            throw new IllegalStateException("读取 TB_OUT_TRADE_RESULT_DETAIL 失败: " + ex.getMessage(), ex);
        }
    }

    private Map<String, NettingSetInputRow> queryNettingSets(String dataDate, List<TradeScopeRow> scopeRows) {
        Set<String> nettingSetIds = new LinkedHashSet<>();
        for (TradeScopeRow row : scopeRows) {
            if (row.nettingSetId != null) {
                nettingSetIds.add(row.nettingSetId);
            }
        }
        if (nettingSetIds.isEmpty()) {
            return Collections.emptyMap();
        }

        String sql = "SELECT DATA_DATE, NETTING_SET_ID, COUNTERPARTY_ID, MARGIN_TYPE, MARGIN_CCY, "
                + "MARGIN_FX_RATE_TO_CNY, THRESHOLD, MTA, NICA, MPOR_DAYS "
                + "FROM SACCR_NETTING_SET WHERE DATA_DATE=STR_TO_DATE(?, '%Y%m%d') AND NETTING_SET_ID IN ("
                + placeholders(nettingSetIds.size()) + ")";
        List<Object> params = new ArrayList<>();
        params.add(dataDate);
        params.addAll(nettingSetIds);
        try {
            List<Map<String, Object>> rows = engineDbJdbcTemplate.queryForList(sql, params.toArray());
            Map<String, NettingSetInputRow> result = new LinkedHashMap<>();
            for (Map<String, Object> row : rows) {
                NettingSetInputRow item = new NettingSetInputRow();
                item.nettingSetId = requireText(stringValue(row.get("NETTING_SET_ID")), "NETTING_SET_ID");
                item.counterpartyId = requireText(stringValue(row.get("COUNTERPARTY_ID")), "COUNTERPARTY_ID");
                item.marginType = requireText(stringValue(row.get("MARGIN_TYPE")), "MARGIN_TYPE").toUpperCase();
                if (!"NONE".equals(item.marginType)
                        && !"BILATERAL".equals(item.marginType)
                        && !"ONE_WAY_BANK".equals(item.marginType)) {
                    throw new IllegalArgumentException("SACCR_NETTING_SET.MARGIN_TYPE 不支持: " + item.marginType);
                }
                if ("NONE".equals(item.marginType)) {
                    item.thresholdCny = 0.0;
                    item.mtaCny = 0.0;
                    item.nicaCny = 0.0;
                    item.mporDays = 0;
                } else {
                    requireText(stringValue(row.get("MARGIN_CCY")), "MARGIN_CCY");
                    double fxRate = requireDecimal(row.get("MARGIN_FX_RATE_TO_CNY"),
                            "MARGIN_FX_RATE_TO_CNY", item.nettingSetId).doubleValue();
                    if (fxRate <= 0) {
                        throw new IllegalArgumentException("SACCR_NETTING_SET.MARGIN_FX_RATE_TO_CNY 必须大于 0: "
                                + item.nettingSetId);
                    }
                    item.thresholdCny = requireDecimal(row.get("THRESHOLD"), "THRESHOLD", item.nettingSetId).doubleValue() * fxRate;
                    item.mtaCny = requireDecimal(row.get("MTA"), "MTA", item.nettingSetId).doubleValue() * fxRate;
                    item.nicaCny = requireDecimal(row.get("NICA"), "NICA", item.nettingSetId).doubleValue() * fxRate;
                    item.mporDays = requireInt(row.get("MPOR_DAYS"), "MPOR_DAYS", item.nettingSetId);
                    if (item.mporDays <= 0) {
                        throw new IllegalArgumentException("SACCR_NETTING_SET.MPOR_DAYS 必须大于 0: " + item.nettingSetId);
                    }
                }
                if (result.put(item.nettingSetId, item) != null) {
                    throw new IllegalArgumentException("SACCR_NETTING_SET 存在重复 NETTING_SET_ID: " + item.nettingSetId);
                }
            }
            for (String nettingSetId : nettingSetIds) {
                if (!result.containsKey(nettingSetId)) {
                    throw new IllegalArgumentException("SACCR_NETTING_SET 缺少 NETTING_SET_ID: " + nettingSetId);
                }
            }
            return result;
        } catch (DataAccessException ex) {
            throw new IllegalStateException("读取 SACCR_NETTING_SET 失败: " + ex.getMessage(), ex);
        }
    }

    private List<SaccrCollateralOutputRow> queryCollateralRows(String batchId,
                                                               String dataDate,
                                                               Map<String, String> effectiveSetByInstrument,
                                                               Set<String> effectiveNettingSetIds) {
        List<String> instrumentIds = new ArrayList<>(effectiveSetByInstrument.keySet());
        List<String> nettingSetIds = new ArrayList<>();
        for (String nettingSetId : effectiveNettingSetIds) {
            if (!nettingSetId.startsWith("SINGLE_")) {
                nettingSetIds.add(nettingSetId);
            }
        }
        if (instrumentIds.isEmpty() && nettingSetIds.isEmpty()) {
            return Collections.emptyList();
        }

        List<Object> params = new ArrayList<>();
        StringBuilder sql = new StringBuilder()
                .append("SELECT DATA_DATE, COLLATERAL_ID, COLLATERAL_SCOPE, NETTING_SET_ID, INSTRUMENT_ID, ")
                .append("COLLATERAL_TYPE, DIRECTION, COLLATERAL_CCY, MARKET_VALUE, FX_RATE_TO_CNY, ")
                .append("HAIRCUT_RATE, ELIGIBLE_FLAG FROM SACCR_COLLATERAL_DETAIL WHERE DATA_DATE=STR_TO_DATE(?, '%Y%m%d') AND (");
        params.add(dataDate);
        boolean appended = false;
        if (!nettingSetIds.isEmpty()) {
            sql.append("(COLLATERAL_SCOPE = 'NETTING_SET' AND NETTING_SET_ID IN (")
                    .append(placeholders(nettingSetIds.size())).append("))");
            params.addAll(nettingSetIds);
            appended = true;
        }
        if (!instrumentIds.isEmpty()) {
            if (appended) {
                sql.append(" OR ");
            }
            sql.append("(COLLATERAL_SCOPE = 'TRADE' AND INSTRUMENT_ID IN (")
                    .append(placeholders(instrumentIds.size())).append("))");
            params.addAll(instrumentIds);
        }
        sql.append(")");

        try {
            List<Map<String, Object>> rows = engineDbJdbcTemplate.queryForList(sql.toString(), params.toArray());
            List<SaccrCollateralOutputRow> result = new ArrayList<>();
            for (Map<String, Object> row : rows) {
                if (!parseEligible(row.get("ELIGIBLE_FLAG"), stringValue(row.get("COLLATERAL_ID")))) {
                    continue;
                }
                SaccrCollateralOutputRow item = new SaccrCollateralOutputRow();
                item.batchId = batchId;
                item.dataDate = dataDate;
                item.collateralId = requireText(stringValue(row.get("COLLATERAL_ID")), "COLLATERAL_ID");
                item.collateralScope = requireText(stringValue(row.get("COLLATERAL_SCOPE")), "COLLATERAL_SCOPE").toUpperCase();
                item.collateralType = requireText(stringValue(row.get("COLLATERAL_TYPE")), "COLLATERAL_TYPE").toUpperCase();
                validateCollateralType(item.collateralType, item.collateralId);
                item.direction = requireText(stringValue(row.get("DIRECTION")), "DIRECTION").toUpperCase();
                item.collateralCcy = requireText(stringValue(row.get("COLLATERAL_CCY")), "COLLATERAL_CCY");
                item.marketValue = requireDecimal(row.get("MARKET_VALUE"), "MARKET_VALUE", item.collateralId).doubleValue();
                item.fxRateToCny = requireDecimal(row.get("FX_RATE_TO_CNY"), "FX_RATE_TO_CNY", item.collateralId).doubleValue();
                item.haircutRate = requireDecimal(row.get("HAIRCUT_RATE"), "HAIRCUT_RATE", item.collateralId).doubleValue();
                if (item.marketValue < 0 || item.fxRateToCny <= 0 || item.haircutRate < 0 || item.haircutRate > 1) {
                    throw new IllegalArgumentException("押品 " + item.collateralId
                            + " 的 MARKET_VALUE、FX_RATE_TO_CNY 或 HAIRCUT_RATE 非法");
                }
                if ("NETTING_SET".equals(item.collateralScope)) {
                    item.nettingSetId = requireText(stringValue(row.get("NETTING_SET_ID")), "NETTING_SET_ID");
                } else if ("TRADE".equals(item.collateralScope)) {
                    item.instrumentId = requireText(stringValue(row.get("INSTRUMENT_ID")), "INSTRUMENT_ID");
                    item.nettingSetId = effectiveSetByInstrument.get(item.instrumentId);
                    if (item.nettingSetId == null) {
                        throw new IllegalArgumentException("交易押品找不到归属交易: " + item.instrumentId);
                    }
                } else {
                    throw new IllegalArgumentException("COLLATERAL_SCOPE 仅支持 NETTING_SET 或 TRADE: " + item.collateralId);
                }
                double signed = signedCollateralValue(item);
                item.adjustedValueCny = signed * item.marketValue * item.fxRateToCny * (1.0 - item.haircutRate);
                result.add(item);
            }
            return result;
        } catch (DataAccessException ex) {
            throw new IllegalStateException("读取 SACCR_COLLATERAL_DETAIL 失败: " + ex.getMessage(), ex);
        }
    }

    private void applyCollateral(Map<String, SaccrNettingSet> nettingSets,
                                 List<SaccrCollateralOutputRow> collateralRows) {
        for (SaccrCollateralOutputRow row : collateralRows) {
            SaccrNettingSet ns = nettingSets.get(row.nettingSetId);
            if (ns == null) {
                throw new IllegalArgumentException("押品归属的 NETTING_SET_ID 不在本次 SACCR 范围: " + row.nettingSetId);
            }
            ns.collateralC += row.adjustedValueCny;
        }
    }

    private static double signedCollateralValue(SaccrCollateralOutputRow row) {
        if ("RECEIVED".equals(row.direction)) {
            return 1.0;
        }
        if ("POSTED".equals(row.direction)) {
            return -1.0;
        }
        throw new IllegalArgumentException("COLLATERAL_DIRECTION 仅支持 RECEIVED 或 POSTED: " + row.collateralId);
    }

    private static boolean parseEligible(Object value, String collateralId) {
        if (value == null) {
            throw new IllegalArgumentException("押品 " + collateralId + " 缺少 ELIGIBLE_FLAG");
        }
        String text = String.valueOf(value).trim().toUpperCase();
        if ("1".equals(text) || "TRUE".equals(text) || "Y".equals(text)) {
            return true;
        }
        if ("0".equals(text) || "FALSE".equals(text) || "N".equals(text)) {
            return false;
        }
        throw new IllegalArgumentException("押品 " + collateralId + " 的 ELIGIBLE_FLAG 非法: " + value);
    }

    private static void validateCollateralType(String type, String collateralId) {
        if ("CASH".equals(type) || "BOND".equals(type) || "EQUITY".equals(type)
                || "FUND".equals(type) || "GOLD".equals(type) || "OTHER".equals(type)) {
            return;
        }
        throw new IllegalArgumentException("押品 " + collateralId + " 的 COLLATERAL_TYPE 不支持: " + type);
    }

    private static String placeholders(int size) {
        if (size <= 0) {
            throw new IllegalArgumentException("SQL IN 参数不能为空");
        }
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < size; i++) {
            if (i > 0) {
                builder.append(",");
            }
            builder.append("?");
        }
        return builder.toString();
    }

    private static String normalizeDataDate(String dataDate) {
        String value = requireText(dataDate, "data_date");
        try {
            return LocalDate.parse(value, DateTimeFormatter.BASIC_ISO_DATE)
                    .format(DateTimeFormatter.BASIC_ISO_DATE);
        } catch (DateTimeParseException ex) {
            throw new IllegalArgumentException("data_date 日期格式必须为 yyyyMMdd: " + dataDate, ex);
        }
    }

    private static BigDecimal requireDecimal(Object value, String field, String ownerId) {
        if (value == null) {
            throw new IllegalArgumentException(ownerId + " 缺少字段 " + field);
        }
        try {
            return value instanceof BigDecimal ? (BigDecimal) value : new BigDecimal(String.valueOf(value));
        } catch (Exception ex) {
            throw new IllegalArgumentException(ownerId + " 字段 " + field + " 不是合法数字: " + value, ex);
        }
    }

    private static int requireInt(Object value, String field, String ownerId) {
        if (value == null) {
            throw new IllegalArgumentException(ownerId + " 缺少字段 " + field);
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (Exception ex) {
            throw new IllegalArgumentException(ownerId + " 字段 " + field + " 不是合法整数: " + value, ex);
        }
    }

    private static String requireText(String value, String field) {
        String trimmed = trimToNull(value);
        if (trimmed == null) {
            throw new IllegalArgumentException(field + " 必填");
        }
        return trimmed;
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static final class TradeScopeRow {
        String instrumentId;
        String counterpartyId;
        String nettingSetId;
    }

    private static final class TradeResultRow {
        String instrumentId;
        String productCode;
        double valuationCny;
        JSONObject tradeInput;
    }

    private static final class NettingSetInputRow {
        String nettingSetId;
        String counterpartyId;
        String marginType;
        double thresholdCny;
        double mtaCny;
        double nicaCny;
        int mporDays;
    }
}
