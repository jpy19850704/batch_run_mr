package com.zcyh.mr.frtbsa.drc;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.zcyh.mr.product.basic.frtb.DrcDetail;
import com.zcyh.mr.core.Constants;
import com.zcyh.mr.frtbsa.drc.DrcCalculator.*;

import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

/**
 * FRTB标准法 - 违约风险资本(DRC)模块入口
 *
 * <p>
 * 负责数据分发、结果合并和JSON格式化输出。
 * 具体计算逻辑委托给{@link DrcCalculator}。
 *
 * <p>
 * 输出包含三层结构：
 * <ul>
 * <li>DRC_VALUE — DRC资本结果，包含 LEGAL_ENTITY / BUCKET / DRC_TYPE 三层聚合</li>
 * <li>DECOMP_LEGALENTITY — 法人级分解结果，包含 LEGAL_ENTITY / BUCKET / DRC_TYPE 三层聚合</li>
 * <li>DECOMP_DETAIL — 风险因子明细分解</li>
 * </ul>
 *
 * @author xujg
 * @date 2024-12-23 14:48
 */
public class DRCModule {
    private static final String AGG_LEVEL_LEGAL_ENTITY = "LEGAL_ENTITY";
    private static final String AGG_LEVEL_BUCKET = "BUCKET";
    private static final String AGG_LEVEL_DRC_TYPE = "DRC_TYPE";
    private static final String TOTAL = "TOTAL";
    private static final int MAX_INVALID_INPUT_LOG = 10;

    /**
     * DRC资本计算主入口
     *
     * @param data     交易级JTD数据列表
     * @param dataDate 计算日期
     * @return 包含DRC_VALUE、DECOMP_LEGALENTITY、DECOMP_DETAIL的JSON对象
     */
    public static JSONObject calc(List<DrcDetail> data, LocalDate dataDate) {
        validateInput(data);

        // 按产品类型过滤
        List<DrcDetail> nsData = filterByType(data, Constants.FRTB.DRC.JTD_N);
        List<DrcDetail> nctpData = filterByType(data, Constants.FRTB.DRC.JTD_S_N_CTP);
        List<DrcDetail> ctpData = filterByType(data, Constants.FRTB.DRC.JTD_S_CTP);

        // 分别计算各类DRC
        TypeResult nsResult = DrcCalculator.calculate(Constants.FRTB.DRC.JTD_N, nsData);
        TypeResult nctpResult = DrcCalculator.calculate(Constants.FRTB.DRC.JTD_S_N_CTP, nctpData);
        TypeResult ctpResult = DrcCalculator.calculate(Constants.FRTB.DRC.JTD_S_CTP, ctpData);

        // 合并法人级分解
        List<LegalEntityContribution> allLegal = new ArrayList<>();
        allLegal.addAll(nsResult.legalEntityDecomp);
        allLegal.addAll(nctpResult.legalEntityDecomp);
        allLegal.addAll(ctpResult.legalEntityDecomp);

        // 合并明细级分解
        List<DetailContribution> allDetail = new ArrayList<>();
        allDetail.addAll(nsResult.detailDecomp);
        allDetail.addAll(nctpResult.detailDecomp);
        allDetail.addAll(ctpResult.detailDecomp);

        List<AggValueRow> drcRows = aggregateDrcFromLegal(allLegal);
        List<AggValueRow> legalRows = aggregateLegalEntityRows(allLegal);
        return formatResult(drcRows, legalRows, allDetail, dataDate);
    }

    private static List<DrcDetail> filterByType(List<DrcDetail> data, String type) {
        return data.stream().filter(d -> type.equalsIgnoreCase(d.securityType))
                .collect(Collectors.toList());
    }

    private static void validateInput(List<DrcDetail> data) {
        if (data == null || data.isEmpty()) {
            throw new IllegalArgumentException("DRC明细不能为空");
        }
        List<String> invalidRows = new ArrayList<>();
        int invalidCount = 0;
        for (int i = 0; i < data.size(); i++) {
            DrcDetail detail = data.get(i);
            String reason = validateRow(detail);
            if (reason == null) {
                continue;
            }
            invalidCount++;
            if (invalidRows.size() < MAX_INVALID_INPUT_LOG) {
                invalidRows.add("index=" + i
                        + ", instrumentId=" + (detail == null ? null : detail.instrumentId)
                        + ", securityType=" + (detail == null ? null : detail.securityType)
                        + ", reason=" + reason);
            }
        }
        if (invalidCount > 0) {
            throw new IllegalArgumentException("DRC输入明细校验失败: invalidRows=" + invalidCount
                    + ", firstRows=" + invalidRows);
        }
    }

    private static String validateRow(DrcDetail detail) {
        if (detail == null) {
            return "row_is_null";
        }
        List<String> missing = new ArrayList<>();
        if (trimToNull(detail.securityType) == null) {
            missing.add("SECURITY_TYPE");
        }
        if (trimToNull(detail.legalEntity) == null) {
            missing.add("LEGAL_ENTITY");
        }
        if (trimToNull(detail.drcBucket) == null) {
            missing.add("DRC_BUCKET");
        }
        if (detail.seniority == null) {
            missing.add("SENIORITY");
        }
        if (detail.termToMaturity == null) {
            missing.add("TERM_TO_MATURITY");
        }
        if (detail.riskWeight == null) {
            missing.add("RISK_WEIGHT");
        }
        if (detail.jtdCny == null) {
            missing.add("JTD_CNY");
        }
        if (!missing.isEmpty()) {
            return "missing:" + String.join(",", missing);
        }
        if (!isSupportedSecurityType(detail.securityType)) {
            return "unsupported_SECURITY_TYPE:" + detail.securityType;
        }
        if (Constants.FRTB.DRC.JTD_S_N_CTP.equalsIgnoreCase(detail.securityType)
                && trimToNull(detail.securityId) == null) {
            return "missing:SECURITY_ID";
        }
        if (!isFinite(detail.riskWeight) || detail.riskWeight < 0.0) {
            return "invalid_RISK_WEIGHT:" + detail.riskWeight;
        }
        if (!isFinite(detail.jtdCny)) {
            return "invalid_JTD_CNY:" + detail.jtdCny;
        }
        if (!isFinite(detail.termToMaturity) || detail.termToMaturity < 0.0) {
            return "invalid_TERM_TO_MATURITY:" + detail.termToMaturity;
        }
        return null;
    }

    private static boolean isSupportedSecurityType(String securityType) {
        return Constants.FRTB.DRC.JTD_N.equalsIgnoreCase(securityType)
                || Constants.FRTB.DRC.JTD_S_N_CTP.equalsIgnoreCase(securityType)
                || Constants.FRTB.DRC.JTD_S_CTP.equalsIgnoreCase(securityType);
    }

    private static boolean isFinite(Double value) {
        return value != null && !Double.isNaN(value) && !Double.isInfinite(value);
    }

    // ==================== DRC汇总与JSON格式化 ====================

    private static List<AggValueRow> aggregateDrcFromLegal(List<LegalEntityContribution> legalList) {
        Map<String, double[]> sums = new LinkedHashMap<>();
        Map<String, String[]> keys = new LinkedHashMap<>();
        for (LegalEntityContribution oc : legalList) {
            String gk = oc.securityType + "|" + oc.legalEntity + "|" + oc.drcBucket;
            sums.computeIfAbsent(gk, k -> new double[1])[0] += oc.contribution;
            keys.putIfAbsent(gk, new String[] {
                    oc.securityType, oc.legalEntity, oc.drcBucket });
        }
        List<AggValueRow> legalEntityRows = new ArrayList<>();
        sums.forEach((gk, s) -> {
            String[] k = keys.get(gk);
            legalEntityRows.add(new AggValueRow(k[0], k[1], k[2], s[0], AGG_LEVEL_LEGAL_ENTITY));
        });
        return appendUpperLevels(legalEntityRows);
    }

    private static List<AggValueRow> aggregateLegalEntityRows(List<LegalEntityContribution> decompLegal) {
        List<AggValueRow> legalEntityRows = new ArrayList<>();
        for (LegalEntityContribution row : decompLegal) {
            legalEntityRows.add(new AggValueRow(
                    row.securityType,
                    row.legalEntity,
                    row.drcBucket,
                    row.contribution,
                    AGG_LEVEL_LEGAL_ENTITY));
        }
        return appendUpperLevels(legalEntityRows);
    }

    private static List<AggValueRow> appendUpperLevels(List<AggValueRow> legalEntityRows) {
        List<AggValueRow> output = new ArrayList<>();
        Map<String, Double> bucketAgg = new LinkedHashMap<>();
        Map<String, Double> typeAgg = new LinkedHashMap<>();

        for (AggValueRow row : legalEntityRows) {
            output.add(row);
            String bucketKey = row.securityType + "|" + row.drcBucket;
            bucketAgg.put(bucketKey, bucketAgg.getOrDefault(bucketKey, 0.0) + row.value);
            typeAgg.put(row.securityType, typeAgg.getOrDefault(row.securityType, 0.0) + row.value);
        }

        bucketAgg.forEach((key, value) -> {
            String[] split = key.split("\\|", 2);
            String securityType = split[0];
            String drcBucket = split.length > 1 ? split[1] : TOTAL;
            output.add(new AggValueRow(
                    securityType,
                    TOTAL,
                    drcBucket,
                    value,
                    AGG_LEVEL_BUCKET));
        });

        typeAgg.forEach((securityType, value) -> output.add(new AggValueRow(
                securityType,
                TOTAL,
                TOTAL,
                value,
                AGG_LEVEL_DRC_TYPE)));

        return output;
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static JSONObject formatResult(List<AggValueRow> drcResult,
            List<AggValueRow> decompLegal,
            List<DetailContribution> decompDetail,
            LocalDate dataDate) {
        JSONObject result = new JSONObject();

        JSONArray drcValueModule = new JSONArray();
        for (AggValueRow m : drcResult) {
            JSONObject j = new JSONObject();
            j.put("AGG_LEVEL", m.aggLevel);
            j.put("LEGAL_ENTITY", m.legalEntity);
            j.put("DRC_TYPE", m.securityType);
            j.put("DRC_BUCKET", m.drcBucket);
            j.put("DRC_VALUE", m.value);
            j.put("DATA_DATE", dataDate);
            drcValueModule.add(j);
        }

        JSONArray legalEntityModule = new JSONArray();
        for (AggValueRow pc : decompLegal) {
            JSONObject j = new JSONObject();
            j.put("AGG_LEVEL", pc.aggLevel);
            j.put("DRC_TYPE", pc.securityType);
            j.put("LEGAL_ENTITY", pc.legalEntity);
            j.put("DRC_BUCKET", pc.drcBucket);
            j.put("CONTRIBUTION", pc.value);
            j.put("DATA_DATE", dataDate);
            legalEntityModule.add(j);
        }

        JSONArray detailModule = new JSONArray();
        for (DetailContribution oc : decompDetail) {
            JSONObject j = new JSONObject();
            j.put("SECURITY_TYPE", oc.securityType);
            j.put("LEGAL_ENTITY", oc.legalEntity);
            j.put("DRC_BUCKET", oc.drcBucket);
            j.put("JTD_TYPE", oc.jtdType);
            j.put("SENIORITY", oc.seniority);
            j.put("RISK_WEIGHT", oc.riskWeight);
            j.put("JTD_CNY", oc.jtd);
            j.put("CONTRIBUTION", oc.contribution);
            j.put("DATA_DATE", dataDate);
            detailModule.add(j);
        }

        result.put("DRC_VALUE", drcValueModule);
        result.put("DECOMP_LEGALENTITY", legalEntityModule);
        result.put("DECOMP_DETAIL", detailModule);
        return result;
    }

    /**
     * DRC 输出聚合行。
     */
    private static final class AggValueRow {
        final String securityType;
        final String legalEntity;
        final String drcBucket;
        final double value;
        final String aggLevel;

        AggValueRow(String securityType, String legalEntity, String drcBucket, double value, String aggLevel) {
            this.securityType = securityType;
            this.legalEntity = legalEntity;
            this.drcBucket = drcBucket;
            this.value = value;
            this.aggLevel = aggLevel;
        }
    }

}
