package com.zcyh.mr.marketdata;

import com.zcyh.mr.core.CommUtils;
import com.zcyh.mr.core.Constants;
import com.zcyh.mr.core.Series;
import com.zcyh.mr.marketdata.support.CmtyShockSupport;
import com.zcyh.mr.marketdata.support.CsrShockSupport;
import com.zcyh.mr.marketdata.support.EqShockSupport;
import com.zcyh.mr.marketdata.support.FxShockSupport;
import com.zcyh.mr.marketdata.support.GirrShockSupport;
import com.zcyh.mr.marketdata.support.VegaShockSupport;

import java.io.Serializable;
import java.time.LocalDate;
import java.util.*;

/**
 * 市场数据类：包含多种类型的市场数据
 *
 * @author lsd
 * @version 1.0
 * @date 2024/7/10 14:00
 */
public class MarketData implements Serializable {
    public HashMap<String, IrSpot.IrSpotInfo> irSpot = new HashMap<>();
    public HashMap<String, IrVol.IrVolInfo> irVol = new HashMap<>();
    public HashMap<String, EqSpot.EqSpotInfo> eqSpot = new HashMap<>();
    public HashMap<String, EqVol.EqVolInfo> eqVol = new HashMap<>();
    public FxSpot.FxSpotInfo fxSpot;
    public HashMap<String, FxVol.FxVolInfo> fxVol = new HashMap<>();
    public HashMap<String, CommSpot.CommSpotInfo> commSpot = new HashMap<>();
    public HashMap<String, CommVol.CommVolInfo> commVol = new HashMap<>();
    public HashMap<String, Fixing.FixingInfo> fixingRate = new HashMap<>();

    public MarketData() {
        irSpot = new HashMap<>();
        irVol = new HashMap<>();
        eqSpot = new HashMap<>();
        eqVol = new HashMap<>();
        fxSpot = new FxSpot.FxSpotInfo();
        fxVol = new HashMap<>();
        commSpot = new HashMap<>();
        commVol = new HashMap<>();
        fixingRate = new HashMap<>();
    }

    /**
     * 合并基础市场数据和情景变动数据。
     * IR_SPOT/EQ_SPOT/COMM_SPOT/IR_VOL/EQ_VOL/COMM_VOL：情景中存在的曲线整条替换到基础数据。
     * FX_SPOT：基于基础汇率逐笔覆盖，仅替换情景中出现的货币对。
     * FX_VOL：整条曲线替换。
     * FIXING：不参与合并（已发生数据无需修改）。
     *
     * @param marketData     基础市场数据
     * @param scenMarketData 情景变动数据（只含需要变动的部分）
     * @return 合并后的市场数据（深拷贝，不修改原对象）
     */
    public static MarketData updateMarketData(MarketData marketData, MarketData scenMarketData) {
        MarketData baseData = marketData == null ? new MarketData() : marketData;
        MarketData scenData = scenMarketData == null ? new MarketData() : scenMarketData;

        /*
         * 场景估值场景下只需要“基准市场 + 增量覆盖”的只读视图。
         * 这里不再深拷贝整份市场数据，只复制顶层容器并覆盖发生变化的曲线，
         * 从而避免每个场景都进行一次全量深拷贝。
         */
        MarketData merged = new MarketData();
        merged.irSpot = copyMap(baseData.irSpot);
        merged.eqSpot = copyMap(baseData.eqSpot);
        merged.commSpot = copyMap(baseData.commSpot);
        merged.irVol = copyMap(baseData.irVol);
        merged.eqVol = copyMap(baseData.eqVol);
        merged.commVol = copyMap(baseData.commVol);
        merged.fxVol = copyMap(baseData.fxVol);
        merged.fixingRate = copyMap(baseData.fixingRate);

        // IR_SPOT：情景点位覆盖，曲线参数继承原始曲线
        merged.irSpot = mergeIrSpot(baseData.irSpot, scenData.irSpot);

        // EQ_SPOT：整条曲线替换
        merged.eqSpot.putAll(copyMap(scenData.eqSpot));

        // COMM_SPOT：整条曲线替换
        merged.commSpot.putAll(copyMap(scenData.commSpot));

        // FX_SPOT：保留基准全量货币对，仅覆盖情景中出现的汇率
        merged.fxSpot = resolveFxSpot(baseData.fxSpot, scenData.fxSpot);

        // IR_VOL：整条曲线替换
        merged.irVol.putAll(copyMap(scenData.irVol));

        // EQ_VOL：整条曲线替换
        merged.eqVol.putAll(copyMap(scenData.eqVol));

        // COMM_VOL：整条曲线替换
        merged.commVol.putAll(copyMap(scenData.commVol));

        // FX_VOL：整条曲线替换
        merged.fxVol.putAll(copyMap(scenData.fxVol));

        return merged;
    }

    /**
     * 复制顶层 Map，内部曲线对象沿用原引用。
     * 场景市场默认按只读方式使用，此处只避免顶层容器被覆盖。
     */
    private static <K, V> HashMap<K, V> copyMap(HashMap<K, V> source) {
        return source == null ? new HashMap<>() : new HashMap<>(source);
    }

    private static HashMap<String, IrSpot.IrSpotInfo> mergeIrSpot(
            HashMap<String, IrSpot.IrSpotInfo> baseIrSpot,
            HashMap<String, IrSpot.IrSpotInfo> scenIrSpot) {
        HashMap<String, IrSpot.IrSpotInfo> merged = new HashMap<String, IrSpot.IrSpotInfo>();
        if (baseIrSpot != null) {
            for (Map.Entry<String, IrSpot.IrSpotInfo> entry : baseIrSpot.entrySet()) {
                merged.put(entry.getKey(), copyIrSpotInfo(entry.getValue()));
            }
        }
        if (scenIrSpot != null) {
            for (Map.Entry<String, IrSpot.IrSpotInfo> entry : scenIrSpot.entrySet()) {
                String curveCode = entry.getKey();
                IrSpot.IrSpotInfo scenInfo = entry.getValue();
                IrSpot.IrSpotInfo baseInfo = merged.get(curveCode);
                if (baseInfo == null) {
                    // 情景曲线不存在于原始市场时直接丢弃
                    continue;
                }
                merged.put(curveCode, mergeSingleIrSpot(baseInfo, scenInfo));
            }
        }
        return merged;
    }

    private static IrSpot.IrSpotInfo mergeSingleIrSpot(IrSpot.IrSpotInfo baseInfo, IrSpot.IrSpotInfo scenInfo) {
        IrSpot.IrSpotInfo mergedInfo = copyIrSpotInfo(baseInfo);
        if (scenInfo == null) {
            return mergedInfo;
        }
        if (scenInfo.curveData != null && !scenInfo.curveData.isEmpty()) {
            mergedInfo.curveData = copyCurveData(scenInfo.curveData);
        }
        if (scenInfo.shockCurveData != null && !scenInfo.shockCurveData.isEmpty()) {
            mergedInfo.shockCurveData = copyCurveData(scenInfo.shockCurveData);
        }
        return mergedInfo;
    }

    private static IrSpot.IrSpotInfo copyIrSpotInfo(IrSpot.IrSpotInfo source) {
        IrSpot.IrSpotInfo copied = new IrSpot.IrSpotInfo();
        if (source == null) {
            copied.curveData = new Series<Integer, Double>(Integer.class, Double.class);
            return copied;
        }
        copied.curveType = source.curveType;
        copied.curveCode = source.curveCode;
        copied.dataDate = source.dataDate;
        copied.dayCount = source.dayCount;
        copied.freq = source.freq;
        copied.pDataDate = source.pDataDate;
        copied.interpolateType = source.interpolateType;
        copied.curveData = copyCurveData(source.curveData);
        copied.shockCurveData = copyCurveData(source.shockCurveData);
        return copied;
    }

    private static Series<Integer, Double> copyCurveData(Series<Integer, Double> source) {
        Series<Integer, Double> copied = new Series<Integer, Double>(Integer.class, Double.class);
        if (source != null && !source.isEmpty()) {
            copied.putAll(source);
        }
        return copied;
    }

    /**
     * 构造 FX 即期的浅复制视图，避免直接复用同一个 curveData 容器。
     */
    private static FxSpot.FxSpotInfo resolveFxSpot(FxSpot.FxSpotInfo baseFxSpot, FxSpot.FxSpotInfo scenFxSpot) {
        FxSpot.FxSpotInfo fxSpotInfo = new FxSpot.FxSpotInfo();
        FxSpot.FxSpotInfo source = baseFxSpot == null ? scenFxSpot : baseFxSpot;
        if (source != null) {
            fxSpotInfo.curveType = source.curveType;
            fxSpotInfo.dataDate = source.dataDate;
            fxSpotInfo.pDataDate = source.pDataDate;
            fxSpotInfo.curveData = source.curveData == null ? new HashMap<>() : new HashMap<>(source.curveData);
        } else {
            fxSpotInfo.curveData = new HashMap<>();
        }
        if (!hasScenarioFxSpot(scenFxSpot)) {
            return fxSpotInfo;
        }
        if (fxSpotInfo.curveType == null) {
            fxSpotInfo.curveType = scenFxSpot.curveType;
        }
        if (fxSpotInfo.dataDate == null) {
            fxSpotInfo.dataDate = scenFxSpot.dataDate;
        }
        if (scenFxSpot.pDataDate != null) {
            fxSpotInfo.pDataDate = scenFxSpot.pDataDate;
        }
        if (scenFxSpot.curveData != null) {
            fxSpotInfo.curveData.putAll(scenFxSpot.curveData);
        }
        return fxSpotInfo;
    }

    /**
     * 判断情景是否提供了 FX 即期覆盖数据。
     */
    private static boolean hasScenarioFxSpot(FxSpot.FxSpotInfo fxSpotInfo) {
        return fxSpotInfo != null
                && fxSpotInfo.curveData != null
                && !fxSpotInfo.curveData.isEmpty();
    }

    /**
     * GIRR Delta 敏感性
     * 
     * @param marketData
     * @param dataDate
     * @param map        HashMap<String,String> key：曲线代码 value:币种
     *                   一个market中可能会有很多个曲线而用到的仅仅只是部分，所以需要将用到的曲线作为参数传入
     * @return
     */
    public static List<FrtbMarketData> getFrtbMarketDataListGIRRDelta(MarketData marketData, LocalDate dataDate,
            HashMap<String, String> map) {
        return GirrShockSupport.buildDeltaShockMarkets(marketData, dataDate, map);
    }

    /**
     * 统一 Vega tenor shock。
     * 基础波动率曲面继续沿用原始插值方式，只对单一期限点构造相对 0.001 的 shock overlay，
     * overlay 在标准期限点之间按线性方式传播。
     */
    public static List<FrtbMarketData> getFrtbMarketDataListVegaTenor(
            MarketData marketData,
            LocalDate dataDate,
            String riskFactorClass,
            String volatilitySurface) {
        return VegaShockSupport.buildTenorShockMarkets(marketData, dataDate, riskFactorClass, volatilitySurface);
    }

    /**
     * GIRR Curvature 曲率风险
     * 
     * @param marketData
     * @param map        HashMap<String,List<String>> key:币种 value：曲线代码List
     *                   i.Curvature分为Curvature Up和Curvature
     *                   Down，曲线向上平移和向下平移，平移变动量按 FRTB 参数配置生成。
     *                   ii.根据利率曲线的币种进行情景遍历，如果多个曲线有相同的币种，则作为一个情景对多条曲线同时变动。
     * @return
     */
    public static List<FrtbMarketData> getFrtbMarketDataListGIRRCurvature(MarketData marketData,
            HashMap<String, List<String>> map) {
        return GirrShockSupport.buildCurvatureShockMarkets(marketData, map);
    }

    /**
     * FX Delta 敏感性
     *
     * @param marketData
     * @return
     */
    public static FrtbMarketData getFrtbMarketDataListFXDelta(MarketData marketData, String currency) {
        return FxShockSupport.buildDeltaShockMarket(marketData, currency);
    }


    /**
     * FX Curvature 曲率风险
     *
     * @param marketData
     *                   percent 变动量 暂定为 1+0.15/2^0.5= 1.10606601718 后续会作为参数传入
     * @return
     */
    public static List<FrtbMarketData> getFrtbMarketDataListFXCurvature(MarketData marketData, String currency) {
        return FxShockSupport.buildCurvatureShockMarkets(marketData, currency);
    }

    /**
     * EQ Delta（基于价格曲线做比例 shock）
     *
     * @param marketData 市场数据
     * @param priceCurve 价格曲线（复用 IR_SPOT 结构）
     * @param bucket     FRTB EQ 桶，空值默认 11
     * @return 情景市场数据
     */
    public static FrtbMarketData getFrtbMarketDataListEQDelta(MarketData marketData, String priceCurve, String bucket) {
        return EqShockSupport.buildDeltaShockMarket(marketData, priceCurve, bucket);
    }

    /**
     * EQ Curvature（基于价格曲线做上下行比例 shock）
     *
     * @param marketData 市场数据
     * @param priceCurve 价格曲线（复用 IR_SPOT 结构）
     * @param bucket     FRTB EQ 桶，空值默认 11
     * @return 上下行情景
     */
    public static List<FrtbMarketData> getFrtbMarketDataListEQCurvature(MarketData marketData, String priceCurve,
            String bucket) {
        return EqShockSupport.buildCurvatureShockMarkets(marketData, priceCurve, bucket);
    }

    /**
     * 根据 FRTB敏感性计量方案.docx文档 变更商品曲线
     * 
     * @param marketData
     * @return
     */
    public static List<FrtbMarketData> getFrtbMarketDataListCMTYDelta(MarketData marketData) {
        return getFrtbMarketDataListCMTYDelta(marketData, null);
    }

    /**
     * 根据交易依赖的商品价格曲线，按标准期限点逐点 shock 商品 Delta。
     * 基础曲线保持原插值，shock 增量曲线按线性传播。
     */
    public static List<FrtbMarketData> getFrtbMarketDataListCMTYDelta(MarketData marketData, String priceCurve) {
        return CmtyShockSupport.buildDeltaShockMarkets(marketData, priceCurve);
    }

    /**
     * 为商品曲线补齐标准期限点，保证 tenor shock 可以稳定叠加到基础线性曲线上。
     */
    public static List<FrtbMarketData> getFrtbMarketDataListCMTYCurvature(MarketData marketData, String bucket) {
        return CmtyShockSupport.buildCurvatureShockMarkets(marketData, bucket);
    }

    /**
     * CSR 信用利差风险
     * 
     * @param marketData
     * @param dataDate
     * @param map        key : 曲线代码
     *                   value : 币种
     * @return
     */
    public static List<FrtbMarketData> getFrtbMarketDataListCSR(MarketData marketData, LocalDate dataDate,
            HashMap<String, String> map) {
        return CsrShockSupport.buildDeltaShockMarkets(marketData, dataDate, map);
    }

    /**
     * CSR Curvature 信用利差曲率
     * @param marketData
     * @param bucket     入参的frtbCsrBucket
     * @param csrType    "CSR (non-sec)" or "CSR (non-ctp)"
     * @param map        key : 币种
     *                   value : 曲线代码list
     * @remark 根据传入的csrType在frtbParam中获取到对应的rw做绝对变动，返回变动后的市场数据
     * @date 2024-12-05 11:09:314
     * @author xujg
     */
    public static List<FrtbMarketData> getFrtbMarketDataListCSRCurvature(MarketData marketData, String csrType,
            Integer bucket,
            HashMap<String, List<String>> map) {
        return CsrShockSupport.buildCurvatureShockMarkets(marketData, csrType, bucket, map);
    }
}
