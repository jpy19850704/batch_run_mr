package com.zcyh.mr.springboot.service;

import com.zcyh.mr.product.basic.frtb.DrcDetail;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * FRTB DRC 输入查询服务。
 * 从 engine_result_db 的 DRC 明细表按批次与估值日读取计量输入。
 */
@Service
public class FrtbDrcInputQueryService {
    private final JdbcTemplate engineResultDbJdbcTemplate;

    public FrtbDrcInputQueryService(@Qualifier("engineResultDbJdbcTemplate") JdbcTemplate engineResultDbJdbcTemplate) {
        this.engineResultDbJdbcTemplate = engineResultDbJdbcTemplate;
    }

    /**
     * 按 batch_id + data_date 读取 DRC 明细输入。
     */
    public List<DrcDetail> queryDrcDetails(String batchId, String dataDate) {
        String safeBatchId = trimToNull(batchId);
        String safeDataDate = trimToNull(dataDate);
        if (safeBatchId == null) {
            throw new IllegalArgumentException("batch_id 不能为空");
        }
        if (safeDataDate == null) {
            throw new IllegalArgumentException("data_date 不能为空");
        }

        String sql = "SELECT DATA_DATE, PORTFOLIO_CODE, PRODUCT_CODE, INSTRUMENT_ID, SECURITY_ID, "
                + "SECURITY_TYPE, LEGAL_ENTITY, DRC_BUCKET, JTD_TYPE, SENIORITY, "
                + "TERM_TO_MATURITY, MODIFIED_REMAIN_TERM, RISK_WEIGHT, JTD, JTD_CNY, "
                + "INSTRUMENT_VALUE, FRTB_LGD, NOTIONAL "
                + "FROM TB_OUT_TRADE_DRC_DETAIL "
                + "WHERE BATCH_ID=? AND DATA_DATE=? "
                + "ORDER BY SEQ_NO, ID";

        List<DrcDetail> rows = engineResultDbJdbcTemplate.query(
                sql,
                ps -> {
                    ps.setString(1, safeBatchId);
                    ps.setString(2, safeDataDate);
                },
                (rs, rowNum) -> {
                    DrcDetail detail = new DrcDetail();
                    detail.portfolioCode = trimToNull(rs.getString("PORTFOLIO_CODE"));
                    detail.productCode = trimToNull(rs.getString("PRODUCT_CODE"));
                    detail.instrumentId = trimToNull(rs.getString("INSTRUMENT_ID"));
                    detail.securityId = trimToNull(rs.getString("SECURITY_ID"));
                    detail.securityType = trimToNull(rs.getString("SECURITY_TYPE"));
                    detail.legalEntity = trimToNull(rs.getString("LEGAL_ENTITY"));
                    detail.drcBucket = trimToNull(rs.getString("DRC_BUCKET"));
                    detail.jtdType = trimToNull(rs.getString("JTD_TYPE"));
                    int seniority = rs.getInt("SENIORITY");
                    detail.seniority = rs.wasNull() ? null : seniority;
                    detail.termToMaturity = rs.getDouble("TERM_TO_MATURITY");
                    if (rs.wasNull()) {
                        detail.termToMaturity = null;
                    }
                    detail.modifiedRemainTerm = rs.getDouble("MODIFIED_REMAIN_TERM");
                    if (rs.wasNull()) {
                        detail.modifiedRemainTerm = null;
                    }
                    detail.riskWeight = rs.getDouble("RISK_WEIGHT");
                    if (rs.wasNull()) {
                        detail.riskWeight = null;
                    }
                    detail.jtd = rs.getDouble("JTD");
                    if (rs.wasNull()) {
                        detail.jtd = null;
                    }
                    detail.jtdCny = rs.getDouble("JTD_CNY");
                    if (rs.wasNull()) {
                        detail.jtdCny = null;
                    }
                    detail.instrumentValue = rs.getDouble("INSTRUMENT_VALUE");
                    if (rs.wasNull()) {
                        detail.instrumentValue = null;
                    }
                    detail.frtbLgd = rs.getDouble("FRTB_LGD");
                    if (rs.wasNull()) {
                        detail.frtbLgd = null;
                    }
                    detail.notional = rs.getDouble("NOTIONAL");
                    if (rs.wasNull()) {
                        detail.notional = null;
                    }
                    return detail;
                });

        if (rows == null || rows.isEmpty()) {
            throw new IllegalArgumentException("未查到可用于 DRC 计量的明细输入: batch_id=" + safeBatchId + ", data_date=" + safeDataDate);
        }
        return rows;
    }

    private static String trimToNull(String text) {
        if (text == null) {
            return null;
        }
        String value = text.trim();
        return value.isEmpty() ? null : value;
    }
}
