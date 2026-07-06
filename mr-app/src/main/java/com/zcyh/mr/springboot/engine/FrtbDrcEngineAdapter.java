package com.zcyh.mr.springboot.engine;

import static com.zcyh.mr.springboot.support.RequestParseSupport.trimToNull;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONWriter;
import com.zcyh.mr.frtbsa.drc.DRCModule;
import com.zcyh.mr.product.basic.frtb.DrcDetail;
import com.zcyh.mr.springboot.service.FrtbDrcDbRunnerService;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * FRTB DRC 引擎适配器。
 */
public class FrtbDrcEngineAdapter implements EngineAdapter {
    public static final String CODE = "frtb_drc";
    private final FrtbDrcDbRunnerService dbRunnerService;

    public FrtbDrcEngineAdapter() {
        this(null);
    }

    public FrtbDrcEngineAdapter(FrtbDrcDbRunnerService dbRunnerService) {
        this.dbRunnerService = dbRunnerService;
    }

    @Override
    public String code() {
        return CODE;
    }

    @Override
    public String description() {
        return "FRTB DRC engine adapter based on DRCModule";
    }

    @Override
    public String calculate(String inputJson) {
        JSONObject req = JSON.parseObject(inputJson);
        if (req == null) {
            throw new IllegalArgumentException("payload 必须是 JSON 对象");
        }
        String sourceType = trimToNull(req.getString("source_type"));
        if ("db".equalsIgnoreCase(sourceType)) {
            return requireDbRunner().calculateByBatch(inputJson);
        }
        if (sourceType != null) {
            throw new IllegalArgumentException("frtb_drc 不支持的 source_type: " + sourceType);
        }

        JSONArray detailListJson = req.getJSONArray("drc_detail_list");
        if (detailListJson == null || detailListJson.isEmpty()) {
            throw new IllegalArgumentException("drc_detail_list 必填且不能为空");
        }
        List<DrcDetail> detailList = parseDrcDetailList(detailListJson, "drc_detail_list");

        String dataDateRaw = req.getString("data_date");
        if (dataDateRaw == null || dataDateRaw.trim().isEmpty()) {
            throw new IllegalArgumentException("data_date 必填，格式 yyyyMMdd");
        }
        LocalDate dataDate = parseDate(dataDateRaw.trim());

        JSONObject result = DRCModule.calc(detailList, dataDate);
        return result.toJSONString(JSONWriter.Feature.WriteBigDecimalAsPlain);
    }

    private FrtbDrcDbRunnerService requireDbRunner() {
        if (dbRunnerService == null) {
            throw new IllegalStateException("frtb_drc 数据库执行服务未配置");
        }
        return dbRunnerService;
    }

    private static LocalDate parseDate(String raw) {
        if (raw.length() == 8 && raw.chars().allMatch(Character::isDigit)) {
            return LocalDate.parse(raw, DateTimeFormatter.BASIC_ISO_DATE);
        }
        throw new IllegalArgumentException("data_date 格式必须为 yyyyMMdd");
    }

    private static List<DrcDetail> parseDrcDetailList(JSONArray array, String path) {
        List<DrcDetail> list = new ArrayList<>();
        for (int i = 0; i < array.size(); i++) {
            Object item = array.get(i);
            if (!(item instanceof JSONObject)) {
                throw new IllegalArgumentException(path + "[" + i + "] 必须是 JSON 对象");
            }
            list.add(parseDrcDetail((JSONObject) item, path, i));
        }
        return list;
    }

    private static DrcDetail parseDrcDetail(JSONObject obj, String path, int index) {
        DrcDetail detail = new DrcDetail();
        detail.dataDate = parseOptionalDate(obj.getString("data_date"));
        detail.portfolioCode = trimToNull(obj.getString("portfolio_code"));
        detail.productCode = trimToNull(obj.getString("product_code"));
        detail.instrumentId = trimToNull(obj.getString("instrument_id"));
        detail.securityId = trimToNull(obj.getString("security_id"));
        detail.securityType = requireString(obj, "security_type", path, index);
        detail.legalEntity = requireString(obj, "legal_entity", path, index);
        detail.drcBucket = requireString(obj, "drc_bucket", path, index);
        detail.jtdType = trimToNull(obj.getString("jtd_type"));
        detail.seniority = obj.getInteger("seniority");
        detail.termToMaturity = obj.getDouble("term_to_maturity");
        detail.modifiedRemainTerm = obj.getDouble("modified_remain_term");
        detail.riskWeight = requireDouble(obj, "risk_weight", path, index);
        detail.jtd = requireDouble(obj, "jtd", path, index);
        detail.jtdCny = requireDouble(obj, "jtd_cny", path, index);
        detail.instrumentValue = obj.getDouble("instrument_value");
        detail.frtbLgd = obj.getDouble("frtb_lgd");
        detail.notional = obj.getDouble("notional");
        return detail;
    }

    private static LocalDate parseOptionalDate(String raw) {
        String value = trimToNull(raw);
        if (value == null) {
            return null;
        }
        return parseDate(value);
    }

    private static String requireString(JSONObject obj, String key, String path, int index) {
        String value = trimToNull(obj.getString(key));
        if (value == null) {
            throw new IllegalArgumentException(path + "[" + index + "]." + key + " 必填");
        }
        return value;
    }

    private static Double requireDouble(JSONObject obj, String key, String path, int index) {
        Double value = obj.getDouble(key);
        if (value == null) {
            throw new IllegalArgumentException(path + "[" + index + "]." + key + " 必填");
        }
        return value;
    }
}

