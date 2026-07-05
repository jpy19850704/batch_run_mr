package com.zcyh.mr.product.basic.common;

import com.alibaba.fastjson2.annotation.JSONField;
import com.zcyh.mr.product.basic.frtb.FrtbSenes;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 所有产品计量结果的公共基类
 * 包含通用估值字段、状态信息、错误记录和计算明细
 */
public class Measure {
    @JSONField(name = "INSTRUMENT_ID")
    public String instrumentId;
    @JSONField(name = "PRODUCT_CODE")
    public String productCode;
    @JSONField(name = "DATA_DATE")
    public LocalDate dataDate;

    @JSONField(name = "POSITION")
    public double position;
    @JSONField(name = "VALUATION_UNIT", format = "0.##########")
    public double valuationUnit;
    @JSONField(name = "VALUATION", format = "0.######")
    public double valuation;
    @JSONField(name = "VALUATION_CCY")
    public String valuationCcy;
    @JSONField(name = "VALUATION_CNY", format = "0.######")
    public double valuationCny;
    @JSONField(name = "PV01", format = "0.########")
    public double pv01;
    @JSONField(name = "FRTB_SENSITIVITY")
    public List<FrtbSenes> sensitivityList = new ArrayList<>();
    @JSONField(name = "CASH_FLOW")
    public List<BaseCashFlow> cashFlowList;

    /** 计量状态：SUCCESS / ERROR */
    @JSONField(name = "STATUS")
    public String status;

    /** 交易级日志，按 level 区分 ERROR / WARNING。 */
    @JSONField(name = "LOGS")
    public List<MeasureLog> logs;

    /** 中间计算明细，仅在首次完整计量时填充 */
    @JSONField(name = "DETAIL")
    public Map<String, Object> detail;

    public static MeasureLog errorLog(String message) {
        return new MeasureLog("ERROR", message);
    }

    public static MeasureLog warningLog(String message) {
        return new MeasureLog("WARNING", message);
    }

    public static List<MeasureLog> errorLogs(List<String> messages) {
        if (messages == null || messages.isEmpty()) {
            return new ArrayList<>();
        }
        List<MeasureLog> result = new ArrayList<>();
        for (String message : messages) {
            result.add(errorLog(message));
        }
        return result;
    }

    public static List<MeasureLog> errorLogs(String message) {
        if (message == null) {
            return new ArrayList<>();
        }
        return errorLogs(Collections.singletonList(message));
    }

    public void addWarningLog(String message) {
        ensureMutableLogs();
        logs.add(warningLog(message));
    }

    public void addErrorLog(String message) {
        ensureMutableLogs();
        logs.add(errorLog(message));
    }

    private void ensureMutableLogs() {
        if (logs == null) {
            logs = new ArrayList<>();
            return;
        }
        if (!(logs instanceof ArrayList)) {
            logs = new ArrayList<>(logs);
        }
    }

    public static class MeasureLog {
        @JSONField(name = "level")
        public String level;
        @JSONField(name = "message")
        public String message;

        public MeasureLog() {
        }

        public MeasureLog(String level, String message) {
            this.level = level;
            this.message = message;
        }
    }
}
