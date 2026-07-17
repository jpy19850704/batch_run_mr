package com.zcyh.mr.springboot.input.db;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 计量输入维度注册表。
 * 统一维护可查询维度定义与 SQL 白名单。
 */
public final class MeasurementInputDomainRegistry {

    private static final List<MeasurementInputDomainDef> DOMAIN_DEFS = Collections.unmodifiableList(Arrays.asList(
            new MeasurementInputDomainDef("TB_OUT_TRADE_RESULT_DETAIL", "PRODUCT_CODE", "产品类型"),
            new MeasurementInputDomainDef("TB_OUT_TRADE_RESULT_DETAIL", "INSTRUMENT_ID", "交易编号"),
            new MeasurementInputDomainDef("TB_OUT_TRADE_RESULT_DETAIL", "PORTFOLIO", "组合"),
            new MeasurementInputDomainDef("TB_OUT_TRADE_RESULT_DETAIL", "DESK", "交易台"),
            new MeasurementInputDomainDef("TB_OUT_TRADE_RESULT_DETAIL", "TRADER", "交易员"),
            new MeasurementInputDomainDef("TB_OUT_TRADE_RESULT_DETAIL", "VALUATION_CCY", "估值币种"),
            new MeasurementInputDomainDef("TB_OUT_TRADE_FRTB_SENSITIVITY_DETAIL", "RISK_FACTOR_CLASS", "风险类别"),
            new MeasurementInputDomainDef("TB_OUT_TRADE_FRTB_SENSITIVITY_DETAIL", "RISK_FACTOR_TYPE", "风险因子类型"),
            new MeasurementInputDomainDef("TB_OUT_TRADE_FRTB_SENSITIVITY_DETAIL", "SENSITIVITY_TYPE", "敏感性类型")
    ));

    private static final Map<String, Set<String>> TABLE_COLUMN_INDEX;

    static {
        Map<String, Set<String>> index = new LinkedHashMap<String, Set<String>>();
        for (MeasurementInputDomainDef def : DOMAIN_DEFS) {
            Set<String> columns = index.get(def.tableName);
            if (columns == null) {
                columns = new LinkedHashSet<String>();
                index.put(def.tableName, columns);
            }
            columns.add(def.columnName);
        }
        Map<String, Set<String>> immutable = new LinkedHashMap<String, Set<String>>();
        for (Map.Entry<String, Set<String>> entry : index.entrySet()) {
            immutable.put(entry.getKey(), Collections.unmodifiableSet(new LinkedHashSet<String>(entry.getValue())));
        }
        TABLE_COLUMN_INDEX = Collections.unmodifiableMap(immutable);
    }

    private MeasurementInputDomainRegistry() {
    }

    public static List<MeasurementInputDomainDef> listDomainDefs() {
        return DOMAIN_DEFS;
    }

    public static boolean isAllowed(String tableName, String columnName) {
        Set<String> columns = TABLE_COLUMN_INDEX.get(tableName);
        return columns != null && columns.contains(columnName);
    }

    /**
     * 计量输入维度定义。
     */
    public static final class MeasurementInputDomainDef {
        private final String tableName;
        private final String columnName;
        private final String label;

        public MeasurementInputDomainDef(String tableName, String columnName, String label) {
            this.tableName = tableName;
            this.columnName = columnName;
            this.label = label;
        }

        public String getTableName() {
            return tableName;
        }

        public String getColumnName() {
            return columnName;
        }

        public String getLabel() {
            return label;
        }
    }
}
