package com.zcyh.mr.springboot.service;

import com.zcyh.mr.frtbsa.sba.common.FrtbConstants;
import com.zcyh.mr.frtbsa.sba.common.FrtbParamsCache;
import com.zcyh.mr.frtbsa.sba.common.CmtyRiskFactorId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * FRTB SBA 明细输入校验与上层标准化。
 */
final class FrtbSbaInputValidator {
    private static final Logger log = LoggerFactory.getLogger(FrtbSbaInputValidator.class);
    private static final int LOG_SAMPLE_LIMIT = 10;
    private static final List<String> VALID_SENS_TYPES = Arrays.asList(
            FrtbConstants.SENS_DELTA,
            FrtbConstants.SENS_VEGA,
            FrtbConstants.SENS_CURVATURE_UP,
            FrtbConstants.SENS_CURVATURE_DOWN);
    private static final Set<String> GIRR_TENORS = new LinkedHashSet<String>(
            Arrays.asList("0.25", "0.5", "1", "2", "3", "5", "10", "15", "20", "30"));
    private static final Set<String> CSR_TENORS = new LinkedHashSet<String>(
            Arrays.asList("0.5", "1", "3", "5", "10"));
    private static final Set<String> CMTY_TENORS = new LinkedHashSet<String>(
            Arrays.asList("0", "0.25", "0.5", "1", "2", "3", "5", "10", "15", "20", "30"));

    private FrtbSbaInputValidator() {
    }

    static List<Map<String, Object>> validateAndNormalizeSbaRows(List<Map<String, Object>> rows) {
        List<Map<String, Object>> validRows = new ArrayList<Map<String, Object>>();
        Map<String, ValidationStat> stats = new LinkedHashMap<String, ValidationStat>();
        ValidationStat missingCsrncType = stat(stats, "CSRNC 明细缺少 RISK_FACTOR_TYPE，已默认按 BOND 处理");

        if (rows == null || rows.isEmpty()) {
            return validRows;
        }

        for (Map<String, Object> row : rows) {
            if (row == null) {
                stat(stats, "明细记录为空，已过滤").add(null);
                continue;
            }

            String riskClass = trimToNull(stringValue(row.get("RISK_FACTOR_CLASS")));
            String sensType = trimToNull(stringValue(row.get("SENSITIVITY_TYPE")));
            String bucket = FrtbConstants.normalizeBucketForRiskClass(
                    riskClass, trimToNull(stringValue(row.get("RISK_FACTOR_BUCKET"))));
            if (bucket != null) {
                row.put("RISK_FACTOR_BUCKET", bucket);
            }
            boolean invalid = false;

            if (!FrtbConstants.isValidRiskClass(riskClass)) {
                stat(stats, "RISK_FACTOR_CLASS 缺失或非法，已过滤").add(row.get("INSTRUMENT_ID"));
                invalid = true;
            }
            if (!VALID_SENS_TYPES.contains(sensType)) {
                stat(stats, "SENSITIVITY_TYPE 缺失或非法，已过滤").add(row.get("INSTRUMENT_ID"));
                invalid = true;
            }
            if (trimToNull(stringValue(row.get("RISK_FACTOR_ID"))) == null) {
                stat(stats, "RISK_FACTOR_ID 缺失，已过滤").add(row.get("INSTRUMENT_ID"));
                invalid = true;
            }
            if (bucket == null) {
                stat(stats, "RISK_FACTOR_BUCKET 缺失，已过滤").add(row.get("INSTRUMENT_ID"));
                invalid = true;
            }
            if (!isNumeric(row.get("SENSITIVITY_VAL_INST_CURR_CNY"))) {
                stat(stats, "SENSITIVITY_VAL_INST_CURR_CNY 缺失或非法，已过滤").add(row.get("INSTRUMENT_ID"));
                invalid = true;
            }
            if (hasInvalidOptionalVertex(row.get("RISK_FACTOR_VERTEX_1"))) {
                stat(stats, "RISK_FACTOR_VERTEX_1 非数字年，已过滤").add(row.get("INSTRUMENT_ID"));
                invalid = true;
            }
            if (hasInvalidOptionalVertex(row.get("RISK_FACTOR_VERTEX_2"))) {
                stat(stats, "RISK_FACTOR_VERTEX_2 非数字年，已过滤").add(row.get("INSTRUMENT_ID"));
                invalid = true;
            }

            if (!invalid && FrtbConstants.RISK_CLASS_CSRNC.equals(riskClass)
                    && trimToNull(stringValue(row.get("RISK_FACTOR_TYPE"))) == null) {
                missingCsrncType.add(row.get("INSTRUMENT_ID"));
                row.put("RISK_FACTOR_TYPE", "BOND");
            }

            if (!invalid && FrtbConstants.RISK_CLASS_GIRR.equals(riskClass)
                    && FrtbConstants.SENS_DELTA.equals(sensType)
                    && trimToNull(stringValue(row.get("RISK_FACTOR_TYPE"))) == null) {
                stat(stats, "GIRR Delta RISK_FACTOR_TYPE 缺失，已过滤").add(row.get("INSTRUMENT_ID"));
                invalid = true;
            }

            if (!invalid && FrtbConstants.RISK_CLASS_CMTY.equals(riskClass)
                    && !FrtbConstants.SENS_VEGA.equals(sensType)
                    && CmtyRiskFactorId.parse(row.get("RISK_FACTOR_ID")) == null) {
                stat(stats, "CMTY RISK_FACTOR_ID 必须为 type&location 格式，已过滤").add(row.get("INSTRUMENT_ID"));
                invalid = true;
            }

            if (!invalid && requiresPositiveVertex1(row, riskClass, sensType)
                    && !isPositiveStandardVertex(row.get("RISK_FACTOR_VERTEX_1"))) {
                stat(stats, "RISK_FACTOR_VERTEX_1 缺失或非正数，已过滤").add(row.get("INSTRUMENT_ID"));
                invalid = true;
            }

            if (!invalid && FrtbConstants.RISK_CLASS_GIRR.equals(riskClass)
                    && FrtbConstants.SENS_VEGA.equals(sensType)
                    && !isPositiveStandardVertex(row.get("RISK_FACTOR_VERTEX_2"))) {
                stat(stats, "GIRR Vega RISK_FACTOR_VERTEX_2 缺失或非正数，已过滤").add(row.get("INSTRUMENT_ID"));
                invalid = true;
            }

            if (!invalid && !validateConfiguredBucket(row, riskClass, bucket, stats)) {
                invalid = true;
            }

            if (!invalid && !validateRiskClassTenor(row, riskClass, sensType, stats)) {
                invalid = true;
            }

            if (!invalid) {
                validRows.add(row);
            }
        }

        logStats(stats);
        return validRows;
    }

    private static boolean requiresPositiveVertex1(Map<String, Object> row, String riskClass, String sensType) {
        if (FrtbConstants.SENS_VEGA.equals(sensType)) {
            return true;
        }
        if (FrtbConstants.SENS_DELTA.equals(sensType)
                && (FrtbConstants.RISK_CLASS_CSRNS.equals(riskClass)
                || FrtbConstants.RISK_CLASS_CSRNC.equals(riskClass)
                || FrtbConstants.RISK_CLASS_CSRCTP.equals(riskClass))) {
            return true;
        }
        if (FrtbConstants.SENS_DELTA.equals(sensType) && FrtbConstants.RISK_CLASS_GIRR.equals(riskClass)) {
            String riskType = trimToNull(stringValue(row.get("RISK_FACTOR_TYPE")));
            String upper = riskType.toUpperCase();
            return !upper.contains("INFLA") && !upper.contains("BASIS");
        }
        return false;
    }

    private static boolean validateConfiguredBucket(
            Map<String, Object> row,
            String riskClass,
            String bucket,
            Map<String, ValidationStat> stats) {
        if (bucket == null) {
            return false;
        }
        boolean configured = true;
        if (FrtbConstants.RISK_CLASS_EQ.equals(riskClass)) {
            configured = FrtbParamsCache.getEQWeights().containsKey(bucket);
        } else if (FrtbConstants.RISK_CLASS_CMTY.equals(riskClass)) {
            configured = FrtbParamsCache.getCmtyWeights().containsKey(bucket);
        } else if (FrtbConstants.RISK_CLASS_CSRNS.equals(riskClass)) {
            configured = FrtbParamsCache.getCsrnsWeights().containsKey(bucket);
        } else if (FrtbConstants.RISK_CLASS_CSRNC.equals(riskClass)) {
            configured = FrtbParamsCache.getCsrncWeights().containsKey(bucket);
        } else if (FrtbConstants.RISK_CLASS_CSRCTP.equals(riskClass)) {
            configured = FrtbParamsCache.getCtpWeights().containsKey(bucket);
        }
        if (!configured) {
            stat(stats, riskClass + " RISK_FACTOR_BUCKET 未配置监管风险权重，已过滤")
                    .add(row.get("INSTRUMENT_ID"));
        }
        return configured;
    }

    private static boolean validateRiskClassTenor(
            Map<String, Object> row,
            String riskClass,
            String sensType,
            Map<String, ValidationStat> stats) {
        if (FrtbConstants.RISK_CLASS_GIRR.equals(riskClass)) {
            String riskType = trimToNull(stringValue(row.get("RISK_FACTOR_TYPE")));
            String upper = riskType == null ? "" : riskType.toUpperCase();
            boolean needsVertex1 = FrtbConstants.SENS_VEGA.equals(sensType)
                    || (FrtbConstants.SENS_DELTA.equals(sensType)
                    && !upper.contains("INFLA") && !upper.contains("BASIS"));
            if (needsVertex1 && !isStandardTenor(row.get("RISK_FACTOR_VERTEX_1"), GIRR_TENORS)) {
                stat(stats, "GIRR RISK_FACTOR_VERTEX_1 不在监管标准期限，已过滤").add(row.get("INSTRUMENT_ID"));
                return false;
            }
            if (FrtbConstants.SENS_VEGA.equals(sensType)
                    && !isStandardTenor(row.get("RISK_FACTOR_VERTEX_2"), GIRR_TENORS)) {
                stat(stats, "GIRR Vega RISK_FACTOR_VERTEX_2 不在监管标准期限，已过滤").add(row.get("INSTRUMENT_ID"));
                return false;
            }
        } else if (FrtbConstants.SENS_DELTA.equals(sensType)
                && (FrtbConstants.RISK_CLASS_CSRNS.equals(riskClass)
                || FrtbConstants.RISK_CLASS_CSRNC.equals(riskClass)
                || FrtbConstants.RISK_CLASS_CSRCTP.equals(riskClass))
                && !isStandardTenor(row.get("RISK_FACTOR_VERTEX_1"), CSR_TENORS)) {
            stat(stats, "CSR Delta RISK_FACTOR_VERTEX_1 不在监管标准期限，已过滤").add(row.get("INSTRUMENT_ID"));
            return false;
        } else if (FrtbConstants.SENS_DELTA.equals(sensType)
                && FrtbConstants.RISK_CLASS_CMTY.equals(riskClass)
                && !isStandardTenor(row.get("RISK_FACTOR_VERTEX_1"), CMTY_TENORS)) {
            stat(stats, "CMTY Delta RISK_FACTOR_VERTEX_1 缺失或不在监管标准期限，已过滤").add(row.get("INSTRUMENT_ID"));
            return false;
        }
        return true;
    }

    private static boolean isStandardTenor(Object value, Set<String> standardTenors) {
        Double parsed = parseStandardVertex(trimToNull(stringValue(value)));
        return parsed != null && standardTenors.contains(tenorKey(parsed));
    }

    private static boolean hasInvalidOptionalVertex(Object value) {
        String text = trimToNull(stringValue(value));
        return text != null && parseStandardVertex(text) == null;
    }

    private static boolean isPositiveStandardVertex(Object value) {
        Double parsed = parseStandardVertex(trimToNull(stringValue(value)));
        return parsed != null && parsed > 0;
    }

    private static Double parseStandardVertex(String value) {
        String text = trimToNull(value);
        if (text == null || !text.matches("\\d+(\\.\\d+)?")) {
            return null;
        }
        try {
            double parsed = Double.parseDouble(text);
            return Double.isFinite(parsed) && parsed >= 0 ? parsed : null;
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static boolean isNumeric(Object value) {
        if (value instanceof BigDecimal || value instanceof Number) {
            return true;
        }
        String text = trimToNull(stringValue(value));
        if (text == null) {
            return false;
        }
        try {
            new BigDecimal(text);
            return true;
        } catch (NumberFormatException ex) {
            return false;
        }
    }

    private static String tenorKey(double tenor) {
        if (tenor == (long) tenor) {
            return String.valueOf((long) tenor);
        }
        return String.valueOf(tenor);
    }

    private static ValidationStat stat(Map<String, ValidationStat> stats, String message) {
        ValidationStat stat = stats.get(message);
        if (stat == null) {
            stat = new ValidationStat(message);
            stats.put(message, stat);
        }
        return stat;
    }

    private static void logStats(Map<String, ValidationStat> stats) {
        for (ValidationStat stat : stats.values()) {
            if (stat.count > 0) {
                log.warn("FRTB SBA {}: total={}, sampledInstrumentIds={}",
                        stat.message, stat.count, stat.samples);
            }
        }
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

    private static final class ValidationStat {
        private final String message;
        private int count;
        private final Set<String> samples = new LinkedHashSet<String>();

        private ValidationStat(String message) {
            this.message = message;
        }

        private void add(Object instrumentId) {
            count++;
            if (samples.size() >= LOG_SAMPLE_LIMIT) {
                return;
            }
            String safeInstrumentId = trimToNull(stringValue(instrumentId));
            samples.add(safeInstrumentId == null ? "<EMPTY_INSTRUMENT_ID>" : safeInstrumentId);
        }
    }

}
