package com.zcyh.mr.springboot.measurement.cva;

import com.zcyh.mr.cva.CvaCalculator;
import com.zcyh.mr.cva.CvaCounterparty;
import com.zcyh.mr.cva.CvaHedge;
import com.zcyh.mr.cva.CvaNettingSet;
import com.zcyh.mr.cva.CvaPortfolioResult;
import com.zcyh.mr.saccr.SaccrCalculator;
import com.zcyh.mr.saccr.model.SaccrResult;
import com.zcyh.mr.springboot.measurement.saccr.SaccrInputQueryService;
import com.zcyh.mr.springboot.measurement.saccr.SaccrRunInput;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class CvaInputQueryService {
    private final JdbcTemplate engineDbJdbcTemplate;
    private final SaccrInputQueryService saccrInputQueryService;

    public CvaInputQueryService(
            @Qualifier("engineDbJdbcTemplate") JdbcTemplate engineDbJdbcTemplate,
            SaccrInputQueryService saccrInputQueryService) {
        this.engineDbJdbcTemplate = engineDbJdbcTemplate;
        this.saccrInputQueryService = saccrInputQueryService;
    }

    public CvaRunInput build(String batchId, LocalDate dataDate) {
        SaccrRunInput saccrInput = saccrInputQueryService.build(batchId, dataDate);
        List<SaccrResult> saccrResults = SaccrCalculator.calculate(saccrInput.nettingSets, dataDate);
        Map<String, SaccrResult> resultByNettingSet = indexSaccrResults(saccrResults);

        Map<String, CvaNettingSetRow> scopeRows = queryNettingSets(dataDate, resultByNettingSet.keySet());
        List<CvaNettingSet> nettingSets = new ArrayList<>();
        Set<String> inScopeCounterparties = new LinkedHashSet<>();
        Set<String> inScopeNettingSets = new LinkedHashSet<>();
        for (SaccrResult saccrResult : saccrResults) {
            CvaNettingSetRow scope = scopeRows.get(saccrResult.nettingSetId);
            if (scope == null) {
                throw new IllegalArgumentException("CVA_NETTING_SET 缺少净额集合: " + saccrResult.nettingSetId);
            }
            if (!"IN_SCOPE".equals(scope.scopeFlag)) {
                if (!"OUT_OF_SCOPE".equals(scope.scopeFlag)) {
                    throw new IllegalArgumentException("CVA_SCOPE_FLAG 仅支持 IN_SCOPE/OUT_OF_SCOPE: "
                            + scope.scopeFlag);
                }
                continue;
            }
            if (!saccrResult.counterpartyId.equals(scope.counterpartyId)) {
                throw new IllegalArgumentException("CVA_NETTING_SET 交易对手与SA-CCR结果不一致: "
                        + saccrResult.nettingSetId);
            }
            CvaNettingSet value = new CvaNettingSet();
            value.nettingSetId = saccrResult.nettingSetId;
            value.counterpartyId = scope.counterpartyId;
            value.effectiveMaturity = scope.effectiveMaturity;
            value.ead = saccrResult.ead;
            nettingSets.add(value);
            inScopeNettingSets.add(saccrResult.nettingSetId);
            inScopeCounterparties.add(scope.counterpartyId);
        }
        if (nettingSets.isEmpty()) {
            throw new IllegalArgumentException("没有纳入CVA计量范围的净额集合");
        }

        List<CvaCounterparty> counterparties = queryCounterparties(dataDate, inScopeCounterparties);
        List<CvaHedge> hedges = queryHedges(dataDate);
        double derivativeNotionalCny = saccrInput.tradeRows.stream()
                .filter(row -> inScopeNettingSets.contains(row.nettingSetId))
                .mapToDouble(row -> Math.abs(row.notional))
                .sum();
        CvaPortfolioResult result = CvaCalculator.calculate(
                nettingSets, counterparties, hedges, derivativeNotionalCny);
        return new CvaRunInput(batchId, dataDate, result);
    }

    private Map<String, SaccrResult> indexSaccrResults(List<SaccrResult> results) {
        Map<String, SaccrResult> result = new LinkedHashMap<>();
        for (SaccrResult value : results) {
            if (result.put(value.nettingSetId, value) != null) {
                throw new IllegalArgumentException("SA-CCR净额集合结果重复: " + value.nettingSetId);
            }
        }
        return result;
    }

    private Map<String, CvaNettingSetRow> queryNettingSets(LocalDate dataDate, Set<String> nettingSetIds) {
        String sql = "SELECT NETTING_SET_ID, COUNTERPARTY_ID, EFFECTIVE_MATURITY_YEARS, CVA_SCOPE_FLAG "
                + "FROM CVA_NETTING_SET WHERE DATA_DATE=? AND NETTING_SET_ID IN ("
                + placeholders(nettingSetIds.size()) + ")";
        List<Object> params = new ArrayList<>();
        params.add(com.zcyh.mr.springboot.support.ResultDbDateSupport.sqlDate(dataDate));
        params.addAll(nettingSetIds);
        try {
            Map<String, CvaNettingSetRow> result = new LinkedHashMap<>();
            for (Map<String, Object> row : engineDbJdbcTemplate.queryForList(sql, params.toArray())) {
                CvaNettingSetRow value = new CvaNettingSetRow();
                value.nettingSetId = text(row.get("NETTING_SET_ID"), "NETTING_SET_ID");
                value.counterpartyId = text(row.get("COUNTERPARTY_ID"), "COUNTERPARTY_ID");
                value.effectiveMaturity = decimal(row.get("EFFECTIVE_MATURITY_YEARS"), "EFFECTIVE_MATURITY_YEARS");
                value.scopeFlag = text(row.get("CVA_SCOPE_FLAG"), "CVA_SCOPE_FLAG");
                if (result.put(value.nettingSetId, value) != null) {
                    throw new IllegalArgumentException("CVA_NETTING_SET 存在重复: " + value.nettingSetId);
                }
            }
            return result;
        } catch (DataAccessException ex) {
            throw new IllegalStateException("读取 CVA_NETTING_SET 失败: " + ex.getMessage(), ex);
        }
    }

    private List<CvaCounterparty> queryCounterparties(LocalDate dataDate, Set<String> counterpartyIds) {
        String sql = "SELECT COUNTERPARTY_ID, INDUSTRY, CREDIT_QUALITY FROM CVA_COUNTERPARTY "
                + "WHERE DATA_DATE=? AND COUNTERPARTY_ID IN (" + placeholders(counterpartyIds.size()) + ")";
        List<Object> params = new ArrayList<>();
        params.add(com.zcyh.mr.springboot.support.ResultDbDateSupport.sqlDate(dataDate));
        params.addAll(counterpartyIds);
        try {
            List<CvaCounterparty> result = new ArrayList<>();
            Set<String> seen = new LinkedHashSet<>();
            for (Map<String, Object> row : engineDbJdbcTemplate.queryForList(sql, params.toArray())) {
                CvaCounterparty value = new CvaCounterparty();
                value.counterpartyId = text(row.get("COUNTERPARTY_ID"), "COUNTERPARTY_ID");
                value.industry = text(row.get("INDUSTRY"), "INDUSTRY");
                value.creditQuality = text(row.get("CREDIT_QUALITY"), "CREDIT_QUALITY");
                if (!seen.add(value.counterpartyId)) {
                    throw new IllegalArgumentException("CVA_COUNTERPARTY 存在重复: " + value.counterpartyId);
                }
                result.add(value);
            }
            if (result.size() != counterpartyIds.size()) {
                throw new IllegalArgumentException("CVA_COUNTERPARTY 未覆盖全部CVA交易对手");
            }
            return result;
        } catch (DataAccessException ex) {
            throw new IllegalStateException("读取 CVA_COUNTERPARTY 失败: " + ex.getMessage(), ex);
        }
    }

    private List<CvaHedge> queryHedges(LocalDate dataDate) {
        String sql = "SELECT HEDGE_ID, HEDGE_TYPE, COUNTERPARTY_ID, RELATION_TYPE, REFERENCE_INDUSTRY, "
                + "REFERENCE_CREDIT_QUALITY, INDEX_BASE_RISK_WEIGHT, INDEX_DIVERSIFIED_FLAG, "
                + "REMAINING_MATURITY_YEARS, NOTIONAL_CNY FROM CVA_HEDGE WHERE DATA_DATE=? ORDER BY HEDGE_ID";
        try {
            List<CvaHedge> result = new ArrayList<>();
            for (Map<String, Object> row : engineDbJdbcTemplate.queryForList(sql,
                    com.zcyh.mr.springboot.support.ResultDbDateSupport.sqlDate(dataDate))) {
                CvaHedge value = new CvaHedge();
                value.hedgeId = text(row.get("HEDGE_ID"), "HEDGE_ID");
                value.hedgeType = text(row.get("HEDGE_TYPE"), "HEDGE_TYPE");
                value.counterpartyId = nullableText(row.get("COUNTERPARTY_ID"));
                value.relationType = nullableText(row.get("RELATION_TYPE"));
                value.referenceIndustry = nullableText(row.get("REFERENCE_INDUSTRY"));
                value.referenceCreditQuality = nullableText(row.get("REFERENCE_CREDIT_QUALITY"));
                value.indexBaseRiskWeight = decimalOrZero(row.get("INDEX_BASE_RISK_WEIGHT"));
                value.indexDiversified = "INDEX_CDS".equalsIgnoreCase(value.hedgeType)
                        && flag(row.get("INDEX_DIVERSIFIED_FLAG"), "INDEX_DIVERSIFIED_FLAG");
                value.remainingMaturity = decimal(row.get("REMAINING_MATURITY_YEARS"), "REMAINING_MATURITY_YEARS");
                value.notional = decimal(row.get("NOTIONAL_CNY"), "NOTIONAL_CNY");
                result.add(value);
            }
            return result;
        } catch (DataAccessException ex) {
            throw new IllegalStateException("读取 CVA_HEDGE 失败: " + ex.getMessage(), ex);
        }
    }

    private static String placeholders(int size) {
        if (size <= 0) {
            throw new IllegalArgumentException("SQL IN 条件不能为空");
        }
        return String.join(",", java.util.Collections.nCopies(size, "?"));
    }

    private static String text(Object value, String field) {
        String text = value == null ? null : value.toString().trim();
        if (text == null || text.isEmpty()) {
            throw new IllegalArgumentException(field + "不能为空");
        }
        return text;
    }

    private static String nullableText(Object value) {
        if (value == null) {
            return null;
        }
        String text = value.toString().trim();
        return text.isEmpty() ? null : text;
    }

    private static double decimal(Object value, String field) {
        if (value == null) {
            throw new IllegalArgumentException(field + "不能为空");
        }
        return Double.parseDouble(value.toString());
    }

    private static double decimalOrZero(Object value) {
        return value == null ? 0.0 : Double.parseDouble(value.toString());
    }

    private static boolean flag(Object value, String field) {
        String text = text(value, field).toUpperCase();
        if ("Y".equals(text)) {
            return true;
        }
        if ("N".equals(text)) {
            return false;
        }
        throw new IllegalArgumentException(field + " 仅支持 Y/N: " + value);
    }

    private static class CvaNettingSetRow {
        String nettingSetId;
        String counterpartyId;
        double effectiveMaturity;
        String scopeFlag;
    }
}
