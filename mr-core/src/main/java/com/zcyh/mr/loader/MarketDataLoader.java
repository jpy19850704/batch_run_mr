package com.zcyh.mr.loader;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.zcyh.mr.support.EngineConfiguration;
import com.zcyh.mr.support.EngineConstants;
import com.zcyh.mr.marketdata.IrSpot;
import com.zcyh.mr.marketdata.MarketData;
import com.zcyh.mr.marketdata.input.MarketInputValidator;

import java.time.LocalDate;
import java.util.Locale;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * 市场数据加载器。
 * 负责基础和情景市场数据的入口路由。
 */
public class MarketDataLoader {
    private static final String DEFAULT_IR_FREQ = "cont";
    private static final String DEFAULT_IR_DAY_COUNT = "actual/365";

    private final MarketDataValidationCollector validationCollector;
    private final SpotMarketDataProcessor spotProcessor;
    private final VolMarketDataProcessor volProcessor;
    private final FixingMarketDataProcessor fixingProcessor;

    public MarketDataLoader(LocalDate dataDate, JSONArray validationErrors) {
        this(dataDate, validationErrors, resolveDefaultFxSpotBaseCurrency());
    }

    public MarketDataLoader(LocalDate dataDate, JSONArray validationErrors, String fxSpotBaseCurrency) {
        this.validationCollector = new MarketDataValidationCollector(validationErrors);
        this.spotProcessor = new SpotMarketDataProcessor(
                dataDate, normalizeCurrency(fxSpotBaseCurrency, "USD"), validationCollector);
        this.volProcessor = new VolMarketDataProcessor(dataDate, validationCollector);
        this.fixingProcessor = new FixingMarketDataProcessor(dataDate, validationCollector);
    }

    /**
     * 加载基础市场数据，并补齐基础利率曲线默认元信息。
     */
    public MarketData loadBaseMarketData(JSONArray marketDataArray) {
        MarketData marketData = new MarketData();
        parseMarketData(marketDataArray, marketData);
        normalizeBaseIrSpotMeta(marketData);
        validateLoadedTypes(marketDataArray, marketData);
        return marketData;
    }

    /**
     * 加载情景市场数据，不执行基础市场数据的默认元信息补齐。
     */
    public MarketData loadScenarioMarketData(JSONArray marketDataArray) {
        MarketData marketData = new MarketData();
        parseMarketData(marketDataArray, marketData);
        return marketData;
    }

    /**
     * 解析市场数据数组，填充到指定的 MarketData 对象中。
     */
    public void parseMarketData(JSONArray marketDataArray, MarketData target) {
        if (marketDataArray == null || target == null) {
            return;
        }
        boolean firstFxContainer = true;
        for (int index = 0; index < marketDataArray.size(); index++) {
            JSONObject marketJson = (JSONObject) marketDataArray.get(index);
            String curveType = marketJson.getString("CURVE_TYPE");
            String curveId = marketJson.getString("CURVE_ID");
            if (curveId == null) {
                curveId = marketJson.getString("FIXING_ID");
            }
            if (curveType == null || curveType.isEmpty()) {
                validationCollector.error("UNKNOWN", "", "CURVE_TYPE 为空");
                continue;
            }

            MarketInputValidator.LoadValidationResult validation =
                    MarketInputValidator.validateForLoading(marketJson);
            for (String error : validation.getPointErrors()) {
                validationCollector.error(curveType, curveId == null ? "" : curveId,
                        error + ", 点位被剔除");
            }
            if (!validation.getOuterErrors().isEmpty()) {
                for (String error : validation.getOuterErrors()) {
                    validationCollector.error(curveType, curveId == null ? "" : curveId, error);
                }
                continue;
            }
            JSONObject validMarketJson = validation.getValidInput();

            if (EngineConstants.RF_TYPE.FX_SPOT.equals(curveType)) {
                firstFxContainer = spotProcessor.processFxSpot(
                        target, firstFxContainer, validMarketJson, curveType);
                continue;
            }
            processNonFxMarketData(target, validMarketJson, curveType, curveId);
        }
    }

    private void processNonFxMarketData(
            MarketData target,
            JSONObject marketJson,
            String curveType,
            String curveId) {
        if (EngineConstants.RF_TYPE.IR_SPOT.equals(curveType)
                || EngineConstants.RF_TYPE.CREDIT_SPOT.equals(curveType)) {
            spotProcessor.processIrSpot(target, marketJson, curveType);
        } else if (EngineConstants.RF_TYPE.FIXING.equals(curveType)) {
            fixingProcessor.process(target, marketJson, curveType);
        } else if (EngineConstants.RF_TYPE.COMM_SPOT.equals(curveType)) {
            spotProcessor.processCommSpot(target, marketJson, curveType);
        } else if (EngineConstants.RF_TYPE.EQ_SPOT.equals(curveType)) {
            spotProcessor.processEqSpot(target, marketJson, curveType);
        } else if (EngineConstants.RF_TYPE.COMM_VOL.equals(curveType)) {
            volProcessor.processCommVol(target, marketJson, curveType);
        } else if (EngineConstants.RF_TYPE.FX_VOL.equals(curveType)) {
            volProcessor.processFxVol(target, marketJson, curveType);
        } else if (EngineConstants.RF_TYPE.IR_VOL.equals(curveType)) {
            volProcessor.processIrVol(target, marketJson, curveType);
        } else if (EngineConstants.RF_TYPE.EQ_VOL.equals(curveType)) {
            volProcessor.processEqVol(target, marketJson, curveType);
        } else {
            validationCollector.error(curveType, curveId == null ? "" : curveId, "不支持的 CURVE_TYPE");
        }
    }

    private void validateLoadedTypes(JSONArray marketDataArray, MarketData marketData) {
        if (marketDataArray == null || marketDataArray.isEmpty()) {
            return;
        }
        Set<String> marketDataTypes = new LinkedHashSet<String>();
        for (Object item : marketDataArray) {
            if (item instanceof JSONObject) {
                String marketDataType = ((JSONObject) item).getString("CURVE_TYPE");
                if (marketDataType != null && !marketDataType.trim().isEmpty()) {
                    marketDataTypes.add(marketDataType);
                }
            }
        }
        for (String marketDataType : marketDataTypes) {
            for (String error : marketData.validateInput(marketDataType)) {
                validationCollector.error(marketDataType, "", error);
            }
        }
    }

    /**
     * 仅对原始 market_data 的 IR 曲线补齐默认频率与日计数。
     * 默认值是基础市场数据的标准运行口径，不作为字段兼容回退使用。
     */
    private static void normalizeBaseIrSpotMeta(MarketData baseMarketData) {
        if (baseMarketData == null || baseMarketData.irSpot == null || baseMarketData.irSpot.isEmpty()) {
            return;
        }
        for (Map.Entry<String, IrSpot.IrSpotInfo> entry : baseMarketData.irSpot.entrySet()) {
            IrSpot.IrSpotInfo info = entry.getValue();
            if (info == null) {
                continue;
            }
            if (MarketDataInputMapper.isBlank(info.freq)) {
                info.freq = DEFAULT_IR_FREQ;
            }
            if (MarketDataInputMapper.isBlank(info.dayCount)) {
                info.dayCount = DEFAULT_IR_DAY_COUNT;
            }
        }
    }

    private static String resolveDefaultFxSpotBaseCurrency() {
        String value = EngineConfiguration.getInstance().getValue(EngineConstants.CFG.FX_SPOT_BASE_CODE);
        if (value == null || value.trim().isEmpty()) {
            return "USD";
        }
        return value.trim().toUpperCase(Locale.ROOT);
    }

    private static String normalizeCurrency(String currency, String defaultCurrency) {
        if (currency == null || currency.trim().isEmpty()) {
            return defaultCurrency;
        }
        return currency.trim().toUpperCase(Locale.ROOT);
    }
}
