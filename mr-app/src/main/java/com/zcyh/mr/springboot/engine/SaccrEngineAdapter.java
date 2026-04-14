package com.zcyh.mr.springboot.engine;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONWriter;
import com.zcyh.mr.saccr.SaccrCalculator;
import com.zcyh.mr.saccr.model.SaccrNettingSet;
import com.zcyh.mr.saccr.model.SaccrResult;
import com.zcyh.mr.saccr.model.SaccrTrade;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * SA-CCR 引擎适配器（engineCode = "sa_ccr"）。
 *
 * <p>输入 JSON 结构：
 * <pre>
 * {
 *   "data_date": "20260408",          // 计算基准日（yyyyMMdd）
 *   "calc_capital": true,             // 是否计算第五层资本（可选，默认 false）
 *   "netting_sets": [                 // 净额结算集合列表
 *     {
 *       "netting_set_id": "NS001",
 *       "counterparty_id": "CP001",
 *       "counterparty_type": "Bank",
 *       "counterparty_rating": "A+",
 *       "is_margined": true,
 *       "is_cleared": false,
 *       "is_qccp": false,
 *       "is_client_clearing": false,
 *       "meets_portability_conditions": false,
 *       "margin_type": "Bilateral",   // Bilateral / OneWayBank / None
 *       "threshold": 0,
 *       "mta": 0,
 *       "nica": 0,
 *       "collateral_c": 100000,
 *       "mpor_days": 0,               // 0 = 自动推算
 *       "has_dispute_history": false,
 *       "trades": [
 *         {
 *           "instrument_id": "T001",
 *           "asset_class": "IR",
 *           "direction": 1,
 *           "notional": 10000000,
 *           "currency": "CNY",
 *           "start_date": "20260408",
 *           "end_date": "20310408",
 *           "product_code": "Swap",
 *           "mtm_value": 50000,
 *           "is_option": false
 *         }
 *       ]
 *     }
 *   ]
 * }
 * </pre>
 */
public class SaccrEngineAdapter implements EngineAdapter {

    public static final String CODE = "sa_ccr";

    @Override
    public String code() {
        return CODE;
    }

    @Override
    public String description() {
        return "SA-CCR counterparty credit risk EAD calculator (BCBS 279)";
    }

    @Override
    public String calculate(String inputJson) {
        JSONObject req = JSON.parseObject(inputJson);
        if (req == null) {
            throw new IllegalArgumentException("payload 必须是 JSON 对象");
        }

        String dataDateRaw = req.getString("data_date");
        if (dataDateRaw == null || dataDateRaw.isBlank()) {
            throw new IllegalArgumentException("data_date 必填，格式 yyyyMMdd");
        }
        LocalDate dataDate = parseDate(dataDateRaw.trim());

        boolean calcCapital = Boolean.TRUE.equals(req.getBoolean("calc_capital"));

        JSONArray nsArray = req.getJSONArray("netting_sets");
        if (nsArray == null || nsArray.isEmpty()) {
            throw new IllegalArgumentException("netting_sets 必填且不能为空");
        }

        List<SaccrNettingSet> nettingSets = parseNettingSets(nsArray);
        List<SaccrResult> results = SaccrCalculator.calculate(nettingSets, dataDate, calcCapital);

        JSONObject output = new JSONObject();
        output.put("data_date", dataDateRaw);
        output.put("results", JSON.parseArray(JSON.toJSONString(results)));
        return output.toJSONString(JSONWriter.Feature.WriteBigDecimalAsPlain);
    }

    // ==================== 解析方法 ====================

    private static List<SaccrNettingSet> parseNettingSets(JSONArray array) {
        List<SaccrNettingSet> list = new ArrayList<>();
        for (int i = 0; i < array.size(); i++) {
            JSONObject obj = array.getJSONObject(i);
            if (obj == null) {
                throw new IllegalArgumentException("netting_sets[" + i + "] 必须是 JSON 对象");
            }
            list.add(parseNettingSet(obj, i));
        }
        return list;
    }

    private static SaccrNettingSet parseNettingSet(JSONObject obj, int index) {
        SaccrNettingSet ns = new SaccrNettingSet();
        ns.nettingSetId = requireString(obj, "netting_set_id", "netting_sets", index);
        ns.counterpartyId = obj.getString("counterparty_id");
        ns.counterpartyType = obj.getString("counterparty_type");
        ns.counterpartyRating = obj.getString("counterparty_rating");
        ns.isMargined = Boolean.TRUE.equals(obj.getBoolean("is_margined"));
        ns.isCleared = Boolean.TRUE.equals(obj.getBoolean("is_cleared"));
        ns.isQccp = Boolean.TRUE.equals(obj.getBoolean("is_qccp"));
        ns.isClientClearing = Boolean.TRUE.equals(obj.getBoolean("is_client_clearing"));
        ns.meetsPortabilityConditions = Boolean.TRUE.equals(obj.getBoolean("meets_portability_conditions"));
        ns.marginType = obj.getString("margin_type");
        ns.threshold = obj.getDoubleValue("threshold");
        ns.mta = obj.getDoubleValue("mta");
        ns.nica = obj.getDoubleValue("nica");
        ns.collateralC = obj.getDoubleValue("collateral_c");
        ns.mporDays = obj.getIntValue("mpor_days");
        ns.hasDisputeHistory = Boolean.TRUE.equals(obj.getBoolean("has_dispute_history"));

        JSONArray tradesArray = obj.getJSONArray("trades");
        if (tradesArray == null || tradesArray.isEmpty()) {
            throw new IllegalArgumentException("netting_sets[" + index + "].trades 必填且不能为空");
        }
        ns.trades = parseTrades(tradesArray, index);
        return ns;
    }

    private static List<SaccrTrade> parseTrades(JSONArray array, int nsIndex) {
        List<SaccrTrade> list = new ArrayList<>();
        for (int i = 0; i < array.size(); i++) {
            JSONObject obj = array.getJSONObject(i);
            if (obj == null) {
                throw new IllegalArgumentException(
                        "netting_sets[" + nsIndex + "].trades[" + i + "] 必须是 JSON 对象");
            }
            list.add(parseTrade(obj, nsIndex, i));
        }
        return list;
    }

    private static SaccrTrade parseTrade(JSONObject obj, int nsIndex, int index) {
        String path = "netting_sets[" + nsIndex + "].trades";
        SaccrTrade trade = new SaccrTrade();
        trade.tradeId = requireString(obj, "instrument_id", path, index);
        trade.assetClass = requireString(obj, "asset_class", path, index);
        trade.direction = obj.getIntValue("direction");
        trade.notional = obj.getDoubleValue("notional");
        trade.currency = obj.getString("currency");
        trade.startDate = parseOptionalDate(obj.getString("start_date"));
        trade.endDate = parseOptionalDate(obj.getString("end_date"));
        trade.optionExpiry = parseOptionalDate(obj.getString("option_expiry"));
        trade.productType = obj.getString("product_code");
        trade.mtmValue = obj.getDoubleValue("mtm_value");
        trade.isOption = Boolean.TRUE.equals(obj.getBoolean("is_option"));
        trade.optionLong = Boolean.TRUE.equals(obj.getBoolean("option_long"));
        trade.optionType = obj.getString("option_type");   // "CALL" 或 "PUT"，null 视为 CALL
        trade.strikePrice = obj.getDoubleValue("strike_price");
        trade.underlyingPrice = obj.getDoubleValue("underlying_price");
        trade.referenceEntity = obj.getString("reference_entity");
        trade.creditRating = obj.getString("credit_rating");
        trade.isIndex = Boolean.TRUE.equals(obj.getBoolean("is_index"));
        trade.commodityBucket = obj.getString("commodity_bucket");
        trade.commodityType = obj.getString("commodity_type");
        trade.quantity = obj.getDoubleValue("quantity");
        trade.currencyPair = obj.getString("currency_pair");
        return trade;
    }

    // ==================== 工具方法 ====================

    private static LocalDate parseDate(String raw) {
        if (raw.length() == 8 && raw.chars().allMatch(Character::isDigit)) {
            return LocalDate.parse(raw, DateTimeFormatter.BASIC_ISO_DATE);
        }
        throw new IllegalArgumentException("日期格式必须为 yyyyMMdd，实际值: " + raw);
    }

    private static LocalDate parseOptionalDate(String raw) {
        if (raw == null || raw.isBlank()) return null;
        return parseDate(raw.trim());
    }

    private static String requireString(JSONObject obj, String key, String path, int index) {
        String val = obj.getString(key);
        if (val == null || val.isBlank()) {
            throw new IllegalArgumentException(path + "[" + index + "]." + key + " 必填");
        }
        return val.trim();
    }
}
