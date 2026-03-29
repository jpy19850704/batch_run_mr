package com.zcyh.mr.springboot.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONWriter;
import com.zcyh.mr.frtbsa.drc.DRCModule;
import com.zcyh.mr.product.basic.frtb.DrcDetail;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * FRTB DRC 数据库输入执行服务。
 * 按 batch_id + data_date 读取明细输入，执行 DRC 计量，并返回核心结果模块。
 */
@Service
public class FrtbDrcDbRunnerService {
    private final FrtbDrcInputQueryService inputQueryService;

    public FrtbDrcDbRunnerService(FrtbDrcInputQueryService inputQueryService) {
        this.inputQueryService = inputQueryService;
    }

    /**
     * 入口参数 JSON 示例：
     * {"batch_id":"...","data_date":"yyyyMMdd"}
     */
    public String calculateByBatch(String payloadJson) {
        JSONObject req = JSON.parseObject(payloadJson);
        if (req == null) {
            throw new IllegalArgumentException("payload must be a json object");
        }
        String batchId = requireTopLevelString(req, "batch_id");
        String dataDate = requireTopLevelString(req, "data_date");
        JSONObject result = calculateByBatch(batchId, dataDate);
        return JSON.toJSONString(result, JSONWriter.Feature.WriteBigDecimalAsPlain);
    }

    /**
     * 按批次和估值日执行 DRC 计量，只保留 DRC_VALUE 与 DECOMP_LEGALENTITY 两类结果。
     */
    public JSONObject calculateByBatch(String batchId, String dataDate) {
        List<DrcDetail> inputList = inputQueryService.queryDrcDetails(batchId, dataDate);
        LocalDate valuationDate = parseDataDate(dataDate);
        JSONObject raw = DRCModule.calc(inputList, valuationDate);

        JSONObject result = new JSONObject();
        result.put("DRC_VALUE", raw.getJSONArray("DRC_VALUE"));
        result.put("DECOMP_LEGALENTITY", raw.getJSONArray("DECOMP_LEGALENTITY"));
        return result;
    }

    private static LocalDate parseDataDate(String dataDate) {
        String value = trimToNull(dataDate);
        if (value == null) {
            throw new IllegalArgumentException("data_date is required");
        }
        if (value.length() == 8 && value.chars().allMatch(Character::isDigit)) {
            return LocalDate.parse(value, DateTimeFormatter.BASIC_ISO_DATE);
        }
        try {
            return LocalDate.parse(value);
        } catch (Exception ex) {
            throw new IllegalArgumentException("data_date format must be yyyyMMdd or yyyy-MM-dd");
        }
    }

    private static String requireTopLevelString(JSONObject obj, String key) {
        String value = trimToNull(obj.getString(key));
        if (value == null) {
            throw new IllegalArgumentException(key + " is required");
        }
        return value;
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
