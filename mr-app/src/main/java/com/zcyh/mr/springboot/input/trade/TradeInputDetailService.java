package com.zcyh.mr.springboot.input.trade;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.zcyh.mr.calc.ProductCalculatorRegistry;
import com.zcyh.mr.springboot.input.common.InputDetailSupport;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;

@Service
public class TradeInputDetailService {
    private final TradeImportRepository repository;
    private final TradeTemplateService templateService;

    public TradeInputDetailService(TradeImportRepository repository, TradeTemplateService templateService) {
        this.repository = repository;
        this.templateService = templateService;
    }

    public JSONObject detail(TradeDetailRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("交易详情查询条件不能为空");
        }
        LocalDate dataDate = parseDate(request.getDataDate());
        String instrumentId = required(request.getInstrumentId(), "instrumentId");
        String productCode = normalizeProductCode(request.getProductCode());
        int versionNo = positiveVersion(request.getVersionNo());
        JSONObject row = repository.findDetail(dataDate, instrumentId, productCode, versionNo);
        if (row == null) {
            throw new IllegalArgumentException("交易数据不存在或版本已发生变化");
        }
        String rawContent = row.getString("trade_content_text");
        row.remove("trade_content_text");
        JSONObject definition = templateService.definition(productCode);
        JSONObject content;
        try {
            content = JSON.parseObject(rawContent);
            if (content == null) {
                throw new IllegalArgumentException("JSON内容为空");
            }
        } catch (Exception e) {
            return InputDetailSupport.malformed("TRADE", row, rawContent, definition,
                    "原始JSON解析失败: " + message(e));
        }
        List<String> invalidPaths = templateService.invalidFieldPaths(productCode, content);
        List<String> validationErrors = templateService.validateFieldValues(productCode, content);
        return InputDetailSupport.build("TRADE", row, rawContent, content,
                definition, invalidPaths, validationErrors);
    }

    public JSONObject detailAfterEdit(TradeEditRequest request) {
        TradeDetailRequest detailRequest = new TradeDetailRequest();
        detailRequest.setDataDate(request.getDataDate());
        detailRequest.setInstrumentId(request.getInstrumentId());
        detailRequest.setProductCode(request.getProductCode());
        detailRequest.setVersionNo(request.getVersionNo() + 1);
        return detail(detailRequest);
    }

    private static LocalDate parseDate(String value) {
        try {
            return LocalDate.parse(required(value, "dataDate"));
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("dataDate格式必须为yyyy-MM-dd");
        }
    }

    private static String normalizeProductCode(String value) {
        String productCode = required(value, "productCode").toUpperCase(Locale.ROOT);
        if (!ProductCalculatorRegistry.supports(productCode)) {
            throw new IllegalArgumentException("不支持的产品类型: " + productCode);
        }
        return productCode;
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
