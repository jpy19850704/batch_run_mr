package com.zcyh.mr.frtbima.scenariopnl;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.zcyh.mr.calc.Calc;
import com.zcyh.mr.scenario.ScenarioCache;
import com.zcyh.mr.core.Series;
import com.zcyh.mr.frtbima.common.ImaConstants;
import com.zcyh.mr.frtbima.model.NmrfPnlRecord;
import com.zcyh.mr.loader.Loader;
import com.zcyh.mr.marketdata.CommSpot;
import com.zcyh.mr.marketdata.EqSpot;
import com.zcyh.mr.marketdata.IrSpot;
import com.zcyh.mr.marketdata.MarketData;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * IMA 不可建模风险因子（NMRF）情景损益计算器（Phase1）。
 *
 * <p>每个 NMRF 桶独立计量，产生两行结果（UP/DOWN），供 Phase2 SES 聚合。
 *
 * <p>计算步骤（MAR33.16-17）：
 * <ol>
 *   <li>遍历全部压力情景，对该桶内每个 tenorDays 聚合：
 *       maxUpShock（最大正向变化）和 maxDownShock（最大负向变化）。
 *   <li>构造 stressed_up：base + maxUpShock，其余桶期限点保持 base 值。
 *   <li>构造 stressed_down：base + maxDownShock，同上。
 *   <li>各调用一次 Calc.run()，写入两条结果记录（subscenarioId = bucketId_UP / _DOWN）。
 * </ol>
 */
public class NmrfScenarioRunner {

    /**
     * NMRF 桶元数据，由调用方（mr-app）从 RFET 结果构建后传入。
     */
    public static class NmrfBucketMeta {
        /** 桶ID（curveId_tenorMin_tenorMax，如 CNY_SWAP_1826_999999） */
        public final String bucketId;
        /** 曲线ID（如 CNY_SWAP），对应 MarketData 中的 key */
        public final String curveId;
        /** 桶内期限天数集合（来自 RFET 结果的 tenorDays 或根据 tenorMin/Max 枚举） */
        public final Set<Integer> tenorDays;
        /**
         * MarketData 字段类型：ImaConstants.RF_TYPE_IR_SPOT / EQ_SPOT / COMM_SPOT /
         * FX_SPOT / IR_VOL / EQ_VOL / FX_VOL / COMM_VOL
         */
        public final String rfType;
        /** NMRF 分类：IDIO_CREDIT / IDIO_EQUITY / OTHER（MAR33.17） */
        public final String nmrfType;

        public NmrfBucketMeta(String bucketId, String curveId, Set<Integer> tenorDays,
                               String rfType, String nmrfType) {
            this.bucketId = bucketId;
            this.curveId = curveId;
            this.tenorDays = tenorDays;
            this.rfType = rfType;
            this.nmrfType = nmrfType;
        }
    }

    /**
     * 执行所有 NMRF 桶的 UP/DOWN 重定价。
     *
     * @param baseCalcJsonTemplate 基准 Calc JSON（trades + baseMarketData，无情景部分）
     * @param baseMarketData       基准市场数据（完整）
     * @param nmrfBuckets          NMRF 桶列表
     * @param stressScenarios      全量压力情景（用于聚合各桶的最大冲击）
     * @param batchId              批次ID
     * @param jobId                任务ID
     * @param requestId            请求ID
     * @param dataDate             估值日期字符串
     * @return 所有桶的 UP/DOWN 重定价结果（每桶每笔交易2行）
     */
    public List<NmrfPnlRecord> run(String baseCalcJsonTemplate,
                                   MarketData baseMarketData,
                                   List<NmrfBucketMeta> nmrfBuckets,
                                   List<Loader.ScenarioEntry> stressScenarios,
                                   String batchId,
                                   String jobId,
                                   String requestId,
                                   String dataDate) {
        List<NmrfPnlRecord> allRecords = new ArrayList<>();
        if (nmrfBuckets == null || nmrfBuckets.isEmpty()) {
            return allRecords;
        }

        for (NmrfBucketMeta bucket : nmrfBuckets) {
            allRecords.addAll(runBucket(
                    baseCalcJsonTemplate, baseMarketData,
                    bucket, stressScenarios,
                    batchId, jobId, requestId, dataDate));
        }
        return allRecords;
    }

    // ==================== 单桶处理 ====================

    private List<NmrfPnlRecord> runBucket(String baseCalcJsonTemplate,
                                           MarketData baseMarketData,
                                           NmrfBucketMeta bucket,
                                           List<Loader.ScenarioEntry> stressScenarios,
                                           String batchId,
                                           String jobId,
                                           String requestId,
                                           String dataDate) {
        List<NmrfPnlRecord> records = new ArrayList<>();
        if (bucket.tenorDays == null || bucket.tenorDays.isEmpty()) {
            return records;
        }

        // 步骤1: 聚合各 tenorDays 的最大 UP/DOWN 冲击
        Map<Integer, Double> maxUpShock = new HashMap<>();
        Map<Integer, Double> maxDownShock = new HashMap<>();
        aggregateShocks(bucket, baseMarketData, stressScenarios, maxUpShock, maxDownShock);

        // 步骤2: 构建 stressed_up MarketData（其他桶期限点保持 base）
        MarketData stressedUp = applyBucketShock(baseMarketData, bucket, maxUpShock);

        // 步骤3: 构建 stressed_down MarketData
        MarketData stressedDown = applyBucketShock(baseMarketData, bucket, maxDownShock);

        // 步骤4: 分别重定价
        records.addAll(repriceOnce(
                baseCalcJsonTemplate, baseMarketData, stressedUp,
                bucket, "UP", batchId, jobId, requestId, dataDate));
        records.addAll(repriceOnce(
                baseCalcJsonTemplate, baseMarketData, stressedDown,
                bucket, "DOWN", batchId, jobId, requestId, dataDate));

        return records;
    }

    /**
     * 遍历压力情景，计算每个 tenorDays 的 maxUpShock / maxDownShock。
     * shock = scenValue - baseValue（正值 = 价格上行，负值 = 价格下行）
     *
     * <p>以 0 为基线初始化：若所有情景 shock 均为负，maxUpShock 保持 0（无正向冲击）；
     * 若所有情景 shock 均为正，maxDownShock 保持 0（无负向冲击）。
     * 这确保 UP/DOWN 两个压力方向各自代表真正的极端方向。
     */
    private void aggregateShocks(NmrfBucketMeta bucket,
                                  MarketData baseMarketData,
                                  List<Loader.ScenarioEntry> stressScenarios,
                                  Map<Integer, Double> maxUpShock,
                                  Map<Integer, Double> maxDownShock) {
        if (stressScenarios == null) return;

        for (int tenor : bucket.tenorDays) {
            Double baseVal = getSpotValue(baseMarketData, bucket.rfType, bucket.curveId, tenor);
            if (baseVal == null) continue;

            // 以 0 为基线：UP 方向取最大正冲击（至少为 0），DOWN 方向取最大负冲击（至多为 0）
            maxUpShock.put(tenor, 0.0);
            maxDownShock.put(tenor, 0.0);

            for (Loader.ScenarioEntry entry : stressScenarios) {
                if (entry == null || entry.marketData == null) continue;
                Double scenVal = getSpotValue(entry.marketData, bucket.rfType, bucket.curveId, tenor);
                if (scenVal == null) continue;

                double shock = scenVal - baseVal;
                maxUpShock.merge(tenor, shock, Math::max);
                maxDownShock.merge(tenor, shock, Math::min);
            }
        }
    }

    /**
     * 在 base 深拷贝上，将本桶的 tenorDays 按 shockByTenor 修改，其余期限点保持 base 值。
     */
    private MarketData applyBucketShock(MarketData baseMarketData,
                                         NmrfBucketMeta bucket,
                                         Map<Integer, Double> shockByTenor) {
        MarketData stressed = deepCopyBase(baseMarketData);

        for (Map.Entry<Integer, Double> e : shockByTenor.entrySet()) {
            int tenor = e.getKey();
            double shock = e.getValue();

            Double baseVal = getSpotValue(baseMarketData, bucket.rfType, bucket.curveId, tenor);
            if (baseVal == null) continue;

            setSpotValue(stressed, bucket.rfType, bucket.curveId, tenor, baseVal + shock);
        }
        return stressed;
    }

    /**
     * 用给定的 stressed MarketData 重定价一次，返回该方向（UP/DOWN）的 NmrfPnlRecord。
     */
    private List<NmrfPnlRecord> repriceOnce(String baseCalcJsonTemplate,
                                              MarketData baseMarketData,
                                              MarketData stressedMarketData,
                                              NmrfBucketMeta bucket,
                                              String direction,
                                              String batchId,
                                              String jobId,
                                              String requestId,
                                              String dataDate) {
        // 构建单条 ScenarioEntry（包含完整 stressed market data）
        String subScenId = bucket.bucketId + "_" + direction;
        Loader.ScenarioEntry scenEntry = new Loader.ScenarioEntry(
                bucket.bucketId, subScenId,
                "NMRF_" + subScenId,
                stressedMarketData,
                null);

        List<Loader.ScenarioEntry> singleEntry = new ArrayList<>();
        singleEntry.add(scenEntry);

        String cacheKey = "ima-nmrf-" + bucket.bucketId.replaceAll("[^a-zA-Z0-9_]", "_")
                + "-" + direction + "-"
                + UUID.randomUUID().toString().replace("-", "").substring(0, 6);
        ScenarioCache.put(cacheKey, singleEntry);

        try {
            String calcJson = injectScenarioCacheKey(baseCalcJsonTemplate, cacheKey);
            String resultJson = new Calc(calcJson, null).run();
            return parseResult(resultJson, bucket, batchId, jobId, requestId, dataDate);
        } finally {
            ScenarioCache.evict(cacheKey);
        }
    }

    // ==================== MarketData 读写工具 ====================

    /**
     * 从指定 rfType 的 MarketData 字段读取 (curveId, tenorDays) 的价格/利率值。
     * 仅支持 Spot 类型（irSpot / eqSpot / commSpot / fxSpot）。
     */
    private Double getSpotValue(MarketData md, String rfType, String curveId, int tenorDays) {
        if (md == null) return null;
        switch (rfType) {
            case ImaConstants.RF_TYPE_IR_SPOT: {
                IrSpot.IrSpotInfo info = md.irSpot != null ? md.irSpot.get(curveId) : null;
                return info != null && info.curveData != null ? info.curveData.get(tenorDays) : null;
            }
            case ImaConstants.RF_TYPE_EQ_SPOT: {
                EqSpot.EqSpotInfo info = md.eqSpot != null ? md.eqSpot.get(curveId) : null;
                return info != null && info.curveData != null ? info.curveData.get(tenorDays) : null;
            }
            case ImaConstants.RF_TYPE_COMM_SPOT: {
                CommSpot.CommSpotInfo info = md.commSpot != null ? md.commSpot.get(curveId) : null;
                return info != null && info.curveData != null ? info.curveData.get(tenorDays) : null;
            }
            case ImaConstants.RF_TYPE_FX_SPOT: {
                // FxSpot: tenorDays 参数忽略，curveId 作为货币对 key
                return md.fxSpot != null && md.fxSpot.curveData != null
                        ? md.fxSpot.curveData.get(curveId) : null;
            }
            default:
                return null;
        }
    }

    /**
     * 将指定 rfType 的 (curveId, tenorDays) 设置为 value。
     * merged 必须是深拷贝，不得修改原始 baseMarketData。
     */
    private void setSpotValue(MarketData md, String rfType, String curveId,
                               int tenorDays, double value) {
        if (md == null) return;
        switch (rfType) {
            case ImaConstants.RF_TYPE_IR_SPOT: {
                IrSpot.IrSpotInfo info = md.irSpot != null ? md.irSpot.get(curveId) : null;
                if (info != null && info.curveData != null) {
                    info.curveData.put(tenorDays, value);
                }
                break;
            }
            case ImaConstants.RF_TYPE_EQ_SPOT: {
                EqSpot.EqSpotInfo info = md.eqSpot != null ? md.eqSpot.get(curveId) : null;
                if (info != null && info.curveData != null) {
                    info.curveData.put(tenorDays, value);
                }
                break;
            }
            case ImaConstants.RF_TYPE_COMM_SPOT: {
                CommSpot.CommSpotInfo info = md.commSpot != null ? md.commSpot.get(curveId) : null;
                if (info != null && info.curveData != null) {
                    info.curveData.put(tenorDays, value);
                }
                break;
            }
            case ImaConstants.RF_TYPE_FX_SPOT: {
                // curveId 作为货币对 key
                if (md.fxSpot != null && md.fxSpot.curveData != null) {
                    md.fxSpot.curveData.put(curveId, value);
                }
                break;
            }
            default:
                break;
        }
    }

    // ==================== 深拷贝（同 SubsetScenarioRunner）====================

    private MarketData deepCopyBase(MarketData base) {
        if (base == null) return new MarketData();
        MarketData copy = MarketData.updateMarketData(base, new MarketData());
        copy.eqSpot = deepCopyEqSpotMap(base.eqSpot);
        copy.commSpot = deepCopyCommSpotMap(base.commSpot);
        return copy;
    }

    private HashMap<String, EqSpot.EqSpotInfo> deepCopyEqSpotMap(
            HashMap<String, EqSpot.EqSpotInfo> src) {
        HashMap<String, EqSpot.EqSpotInfo> copy = new HashMap<>();
        if (src == null) return copy;
        for (Map.Entry<String, EqSpot.EqSpotInfo> e : src.entrySet()) {
            copy.put(e.getKey(), copyEqSpotInfo(e.getValue()));
        }
        return copy;
    }

    private EqSpot.EqSpotInfo copyEqSpotInfo(EqSpot.EqSpotInfo src) {
        EqSpot.EqSpotInfo copy = new EqSpot.EqSpotInfo();
        if (src == null) return copy;
        copy.curveType = src.curveType;
        copy.curveCode = src.curveCode;
        copy.dataDate = src.dataDate;
        copy.currency = src.currency;
        copy.pDataDate = src.pDataDate;
        copy.interpolateType = src.interpolateType;
        copy.curveData = new Series<>(Integer.class, Double.class);
        if (src.curveData != null) copy.curveData.putAll(src.curveData);
        return copy;
    }

    private HashMap<String, CommSpot.CommSpotInfo> deepCopyCommSpotMap(
            HashMap<String, CommSpot.CommSpotInfo> src) {
        HashMap<String, CommSpot.CommSpotInfo> copy = new HashMap<>();
        if (src == null) return copy;
        for (Map.Entry<String, CommSpot.CommSpotInfo> e : src.entrySet()) {
            copy.put(e.getKey(), copyCommSpotInfo(e.getValue()));
        }
        return copy;
    }

    private CommSpot.CommSpotInfo copyCommSpotInfo(CommSpot.CommSpotInfo src) {
        CommSpot.CommSpotInfo copy = new CommSpot.CommSpotInfo();
        if (src == null) return copy;
        copy.curveType = src.curveType;
        copy.curveCode = src.curveCode;
        copy.dataDate = src.dataDate;
        copy.currency = src.currency;
        copy.pDataDate = src.pDataDate;
        copy.interpolateType = src.interpolateType;
        copy.curveData = new Series<>(Integer.class, Double.class);
        if (src.curveData != null) copy.curveData.putAll(src.curveData);
        copy.shockCurveData = new Series<>(Integer.class, Double.class);
        if (src.shockCurveData != null) copy.shockCurveData.putAll(src.shockCurveData);
        return copy;
    }

    // ==================== Calc JSON 注入 ====================

    private String injectScenarioCacheKey(String baseCalcJson, String cacheKey) {
        JSONObject json = JSON.parseObject(baseCalcJson);
        JSONObject scenarioRef = json.getJSONObject("scenario_ref");
        if (scenarioRef == null) {
            scenarioRef = new JSONObject();
            json.put("scenario_ref", scenarioRef);
        }
        scenarioRef.put("cache_key", cacheKey);
        json.put("oper_code", "SCENARIO");
        return json.toJSONString();
    }

    // ==================== 结果解析 ====================

    private List<NmrfPnlRecord> parseResult(String resultJson,
                                              NmrfBucketMeta bucket,
                                              String batchId,
                                              String jobId,
                                              String requestId,
                                              String dataDate) {
        List<NmrfPnlRecord> records = new ArrayList<>();
        JSONObject root = JSON.parseObject(resultJson);
        if (root == null) return records;
        JSONObject data = root.getJSONObject("data");
        if (data == null) return records;
        JSONArray scenarioResults = data.getJSONArray("scenario_result");
        if (scenarioResults == null) return records;

        long seqNo = 0;
        for (int i = 0; i < scenarioResults.size(); i++) {
            JSONObject scenItem = scenarioResults.getJSONObject(i);
            if (scenItem == null) continue;

            String scenId = scenItem.getString("scenario_id");
            String subScenId = scenItem.getString("sub_scenario_id");
            String scenName = scenItem.getString("scenario_name");
            JSONArray tradePnls = scenItem.getJSONArray("trade_data");
            if (tradePnls == null) continue;

            for (int j = 0; j < tradePnls.size(); j++) {
                JSONObject tp = tradePnls.getJSONObject(j);
                if (tp == null) continue;

                NmrfPnlRecord rec = new NmrfPnlRecord();
                rec.setRequestId(requestId);
                rec.setJobId(jobId);
                rec.setBatchId(batchId);
                rec.setSeqNo(++seqNo);
                rec.setDataDate(dataDate);
                rec.setScenarioId(scenId != null ? scenId : bucket.bucketId);
                rec.setSubscenarioId(subScenId);
                rec.setScenarioName(scenName);
                rec.setInstrumentId(tp.getString("INSTRUMENT_ID"));
                rec.setProductCode(tp.getString("PRODUCT_CODE"));
                rec.setRiskFactorId(bucket.curveId);
                rec.setNmrfType(bucket.nmrfType);
                rec.setBaseValuationCny(tp.getBigDecimal("BASE_VALUATION_CNY"));
                rec.setStressValuationCny(tp.getBigDecimal("SCENARIO_VALUATION_CNY"));
                rec.setPnl(tp.getBigDecimal("PNL"));
                rec.setCreatedAt(System.currentTimeMillis());
                records.add(rec);
            }
        }
        return records;
    }
}

