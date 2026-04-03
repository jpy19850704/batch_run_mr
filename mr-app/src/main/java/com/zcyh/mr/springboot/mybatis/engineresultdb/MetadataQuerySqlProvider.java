package com.zcyh.mr.springboot.mybatis.engineresultdb;

import com.zcyh.mr.springboot.metadata.MetadataDomainRegistry;

import java.util.Locale;
import java.util.Map;

/**
 * 元数据查询 SQL 生成器。
 */
public class MetadataQuerySqlProvider {

    public String listDomains(Map<String, Object> params) {
        String tableName = normalizeIdentifier(params.get("tableName"));
        String columnName = normalizeIdentifier(params.get("columnName"));
        validateWhitelist(tableName, columnName);
        return "SELECT DISTINCT " + columnName
                + " FROM " + tableName
                + " WHERE BATCH_ID = #{batchId} AND " + columnName + " IS NOT NULL"
                + " ORDER BY " + columnName;
    }

    private static String normalizeIdentifier(Object raw) {
        if (raw == null) {
            throw new IllegalArgumentException("元数据查询参数不能为空");
        }
        String value = String.valueOf(raw).trim();
        if (value.isEmpty()) {
            throw new IllegalArgumentException("元数据查询参数不能为空字符串");
        }
        return value.toUpperCase(Locale.ROOT);
    }

    private static void validateWhitelist(String tableName, String columnName) {
        if (!MetadataDomainRegistry.isAllowed(tableName, columnName)) {
            throw new IllegalArgumentException("不允许的元数据字段: " + tableName + "." + columnName);
        }
    }
}
