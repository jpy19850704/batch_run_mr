package com.zcyh.mr.springboot.input.market;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.zcyh.mr.springboot.input.common.InputDetailSupport;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;

@Service
public class MarketInputDetailService {
    private final MarketImportRepository repository;
    private final MarketTemplateService templateService;

    public MarketInputDetailService(MarketImportRepository repository, MarketTemplateService templateService) {
        this.repository = repository;
        this.templateService = templateService;
    }

    public JSONObject detail(MarketDetailRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("市场数据详情查询条件不能为空");
        }
        String dataKind = normalizeDataKind(request.getDataKind());
        LocalDate dataDate = parseDate(request.getDataDate());
        String marketDataType = normalizeMarketDataType(request.getMarketDataType(), dataKind);
        String curveId = required(request.getCurveId(), "curveId");
        int versionNo = positiveVersion(request.getVersionNo());
        String conversionType = "RAW".equals(dataKind)
                ? required(request.getConversionType(), "conversionType") : null;
        JSONObject row = "RAW".equals(dataKind)
                ? repository.findRawDetail(dataDate, marketDataType, curveId, versionNo, conversionType)
                : repository.findMarketDetail(dataDate, marketDataType, curveId, versionNo);
        if (row == null) {
            throw new IllegalArgumentException("市场数据不存在或版本已发生变化");
        }
        String rawContent = row.getString("curve_content_text");
        row.remove("curve_content_text");
        JSONObject definition = templateService.definition(marketDataType, conversionType);
        String kind = "RAW".equals(dataKind) ? "MARKET_RAW" : "MARKET";
        JSONObject content;
        try {
            content = JSON.parseObject(rawContent);
            if (content == null) {
                throw new IllegalArgumentException("JSON内容为空");
            }
        } catch (Exception e) {
            return InputDetailSupport.malformed(kind, row, rawContent, definition,
                    "原始JSON解析失败: " + message(e));
        }
        List<String> invalidPaths = templateService.invalidFieldPaths(marketDataType, content);
        List<String> validationErrors = templateService.validateFieldValues(marketDataType, content);
        return InputDetailSupport.build(kind, row, rawContent, content,
                definition, invalidPaths, validationErrors);
    }

    public JSONObject detailAfterEdit(MarketEditRequest request) {
        MarketDetailRequest detailRequest = new MarketDetailRequest();
        detailRequest.setDataKind(request.getDataKind());
        detailRequest.setDataDate(request.getDataDate());
        detailRequest.setMarketDataType(request.getMarketDataType());
        detailRequest.setCurveId(request.getCurveId());
        detailRequest.setConversionType(request.getConversionType());
        detailRequest.setVersionNo(request.getVersionNo() + 1);
        return detail(detailRequest);
    }

    private static String normalizeDataKind(String value) {
        String dataKind = required(value, "dataKind").toUpperCase(Locale.ROOT);
        if (!"MARKET".equals(dataKind) && !"RAW".equals(dataKind)) {
            throw new IllegalArgumentException("dataKind必须为MARKET或RAW");
        }
        return dataKind;
    }

    private static String normalizeMarketDataType(String value, String dataKind) {
        String type = required(value, "marketDataType").toUpperCase(Locale.ROOT);
        if ("RAW".equals(dataKind)) {
            if (!"CURVE_GENERATION".equals(type)) {
                throw new IllegalArgumentException("RAW数据的marketDataType必须为CURVE_GENERATION");
            }
            return type;
        }
        if (!MarketImportSchema.supportedTypes().contains(type)) {
            throw new IllegalArgumentException("不支持的市场数据类型: " + type);
        }
        return type;
    }

    private static LocalDate parseDate(String value) {
        try {
            return LocalDate.parse(required(value, "dataDate"));
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("dataDate格式必须为yyyy-MM-dd");
        }
    }

    private static int positiveVersion(Integer value) {
        if (value == null || value < 1) {
            throw new IllegalArgumentException("versionNo必须为正整数");
        }
        return value;
    }

    private static String required(String value, String field) {
        String result = value == null ? "" : value.trim();
        if (result.isEmpty()) {
            throw new IllegalArgumentException(field + "不能为空");
        }
        return result;
    }

    private static String message(Exception e) {
        return e.getMessage() == null || e.getMessage().trim().isEmpty()
                ? e.getClass().getSimpleName() : e.getMessage();
    }
}
