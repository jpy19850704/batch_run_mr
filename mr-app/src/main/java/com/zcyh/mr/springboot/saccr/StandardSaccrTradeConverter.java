package com.zcyh.mr.springboot.saccr;

import com.alibaba.fastjson2.JSONObject;
import com.zcyh.mr.saccr.model.SaccrTrade;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * 标准字段 SACCR 交易输入转换器。
 */
class StandardSaccrTradeConverter implements SaccrTradeInputConverter {
    private final String productCode;
    private final String assetClass;
    private final boolean option;

    StandardSaccrTradeConverter(String productCode, String assetClass, boolean option) {
        this.productCode = SaccrTradeInputConverterRegistry.normalize(productCode);
        this.assetClass = assetClass;
        this.option = option;
    }

    String productCode() {
        return productCode;
    }

    @Override
    public boolean supports(String productCode) {
        return this.productCode.equals(SaccrTradeInputConverterRegistry.normalize(productCode));
    }

    @Override
    public SaccrTradeRow convert(SaccrTradeConvertContext context) {
        JSONObject input = context.tradeInput;
        if (input == null) {
            throw new IllegalArgumentException("交易 " + context.instrumentId + " 的 TRADE_INPUT_JSON 不能为空");
        }

        SaccrTrade trade = new SaccrTrade();
        trade.tradeId = context.instrumentId;
        trade.productType = productCode;
        trade.assetClass = assetClass;
        trade.direction = requireDirection(input, context.instrumentId);
        trade.notional = requireDouble(input, "NOTIONAL", context.instrumentId);
        trade.currency = requireText(input, "CURRENCY", context.instrumentId);
        trade.startDate = requireDate(input, "START_DATE", context.instrumentId);
        trade.endDate = requireDate(input, "END_DATE", context.instrumentId);
        trade.mtmValue = context.valuationCny;
        trade.isOption = option;

        if ("FX".equals(assetClass)) {
            trade.currencyPair = requireText(input, "CURRENCY_PAIR", context.instrumentId);
        } else if ("CREDIT".equals(assetClass)) {
            trade.referenceEntity = requireText(input, "REFERENCE_ENTITY", context.instrumentId);
            trade.creditRating = requireText(input, "CREDIT_RATING", context.instrumentId);
            trade.isIndex = requireBoolean(input, "IS_INDEX", context.instrumentId);
        } else if ("COMMODITY".equals(assetClass)) {
            trade.commodityBucket = requireText(input, "COMMODITY_BUCKET", context.instrumentId);
            trade.commodityType = requireText(input, "COMMODITY_TYPE", context.instrumentId);
            trade.underlyingPrice = requireDouble(input, "UNDERLYING_PRICE", context.instrumentId);
            trade.quantity = requireDouble(input, "QUANTITY", context.instrumentId);
        }

        if (option) {
            trade.optionType = requireText(input, "OPTION_TYPE", context.instrumentId).toUpperCase();
            trade.optionExpiry = requireDate(input, "OPTION_EXPIRY", context.instrumentId);
            trade.strikePrice = requireDouble(input, "STRIKE_PRICE", context.instrumentId);
            trade.underlyingPrice = requireDouble(input, "UNDERLYING_PRICE", context.instrumentId);
        }

        SaccrTradeRow row = new SaccrTradeRow();
        row.batchId = context.batchId;
        row.dataDate = context.dataDate;
        row.instrumentId = context.instrumentId;
        row.counterpartyId = context.counterpartyId;
        row.nettingMode = context.nettingMode;
        row.nettingSetId = context.nettingSetId;
        row.productCode = productCode;
        row.assetClass = assetClass;
        row.direction = trade.direction;
        row.mtmCny = context.valuationCny;
        row.notional = trade.notional;
        row.currency = trade.currency;
        row.startDate = trade.startDate;
        row.endDate = trade.endDate;
        row.referenceEntity = trade.referenceEntity;
        row.creditRating = trade.creditRating;
        row.isIndex = trade.isIndex;
        row.currencyPair = trade.currencyPair;
        row.commodityBucket = trade.commodityBucket;
        row.commodityType = trade.commodityType;
        row.isOption = trade.isOption;
        row.optionType = trade.optionType;
        row.optionExpiry = trade.optionExpiry;
        row.strikePrice = trade.strikePrice;
        row.underlyingPrice = trade.underlyingPrice;
        row.quantity = trade.quantity;
        row.trade = trade;
        return row;
    }

    private static int requireDirection(JSONObject input, String instrumentId) {
        double raw = requireDouble(input, "DIRECTION", instrumentId);
        if (raw != Math.rint(raw)) {
            throw new IllegalArgumentException("交易 " + instrumentId + " 的 DIRECTION 仅支持整数 1 或 -1");
        }
        int direction = (int) raw;
        if (direction != 1 && direction != -1) {
            throw new IllegalArgumentException("交易 " + instrumentId + " 的 DIRECTION 仅支持 1 或 -1");
        }
        return direction;
    }

    private static String requireText(JSONObject input, String field, String instrumentId) {
        String value = input.getString(field);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("交易 " + instrumentId + " 缺少字段 " + field);
        }
        return value.trim();
    }

    private static double requireDouble(JSONObject input, String field, String instrumentId) {
        Object value = input.get(field);
        if (value == null) {
            throw new IllegalArgumentException("交易 " + instrumentId + " 缺少字段 " + field);
        }
        double number = input.getDoubleValue(field);
        if (!Double.isFinite(number)) {
            throw new IllegalArgumentException("交易 " + instrumentId + " 字段 " + field + " 非法");
        }
        return number;
    }

    private static boolean requireBoolean(JSONObject input, String field, String instrumentId) {
        Object value = input.get(field);
        if (value == null) {
            throw new IllegalArgumentException("交易 " + instrumentId + " 缺少字段 " + field);
        }
        Boolean bool = input.getBoolean(field);
        if (bool == null) {
            throw new IllegalArgumentException("交易 " + instrumentId + " 字段 " + field + " 必须为布尔值");
        }
        return bool;
    }

    private static LocalDate requireDate(JSONObject input, String field, String instrumentId) {
        String value = requireText(input, field, instrumentId);
        if (value.length() == 8 && value.chars().allMatch(Character::isDigit)) {
            return LocalDate.parse(value, DateTimeFormatter.BASIC_ISO_DATE);
        }
        throw new IllegalArgumentException("交易 " + instrumentId + " 字段 " + field + " 日期格式必须为 yyyyMMdd");
    }
}
