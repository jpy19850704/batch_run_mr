package com.zcyh.mr.frtbima.capital;

import com.zcyh.mr.frtbima.aggregation.ImccAggregator;
import com.zcyh.mr.frtbima.common.ImaConstants;
import com.zcyh.mr.frtbima.es.EsCalculator;
import com.zcyh.mr.frtbima.es.LiquidityAdjustedEs;
import com.zcyh.mr.frtbima.model.EsResult;
import com.zcyh.mr.frtbima.model.ImaCapitalResult;
import com.zcyh.mr.frtbima.model.ImccResult;
import com.zcyh.mr.frtbima.model.NmrfStressResult;
import com.zcyh.mr.frtbima.model.SesResult;
import com.zcyh.mr.frtbima.model.SubsetPnlRecord;
import com.zcyh.mr.frtbima.nmrf.NmrfStressAggregator;
import com.zcyh.mr.frtbima.nmrf.SesCalculator;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * IMA 最终资本计算器（Phase2 主入口，MAR33.43）。
 *
 * <p>依赖数据（均从 Doris 查询后传入）：
 * <ul>
 *   <li>subsetPnlRecords：TB_OUT_IMA_MODELLABLE_SCENARIO_PNL
 *   <li>nmrfPnlRecords：TB_OUT_IMA_NMRF_SCENARIO_PNL
 * </ul>
 *
 * <p>采用间接法（MAR33.5），仅需 3 种 scenarioType：
 * <ul>
 *   <li>STRESS_REDUCED → 压力期缩减集 ES（ES_{R,S}）</li>
 *   <li>NORMAL_FULL    → 当期全量 ES（ES_{F,C}）</li>
 *   <li>NORMAL_REDUCED → 当期缩减集 ES（ES_{R,C}）</li>
 * </ul>
 *
 * <p>计算流程：
 * <ol>
 *   <li>EsCalculator → 各 (scenType, lhDays, riskClass) 的 ES</li>
 *   <li>LiquidityAdjustedEs → 流动性调整后的 ES</li>
 *   <li>ImccAggregator → IMCC（MAR33.15）</li>
 *   <li>NmrfStressAggregator + SesCalculator → SES（MAR33.17）</li>
 *   <li>AmberCapitalAdjuster → Amber 附加系数 k</li>
 *   <li>总资本 = IMCC + SES + (IMCC + SES) × k</li>
 * </ol>
 */
public class ImaCapitalCalculator {

    private final EsCalculator esCalculator = new EsCalculator();
    private final LiquidityAdjustedEs lhEs = new LiquidityAdjustedEs();
    private final ImccAggregator imccAggregator = new ImccAggregator();
    private final NmrfStressAggregator nmrfAggregator = new NmrfStressAggregator();
    private final SesCalculator sesCalculator = new SesCalculator();
    private final AmberCapitalAdjuster amberAdjuster = new AmberCapitalAdjuster();

    /**
     * 执行全量 IMA 资本计算。
     *
     * @param subsetPnlRecords   可建模情景 PnL 列表（来自 Doris）
     * @param nmrfPnlRecords     NMRF 情景 PnL 列表（来自 Doris）
     * @param saByDesk           各交易台标准法资本（deskId → SA）
     * @param amberDesks         Amber 区交易台 ID 集合
     * @param greenDesks         Green 区交易台 ID 集合
     * @param dataDate           估值日期字符串
     * @param batchId            批次ID
     * @return ImaCapitalResult
     */
    public ImaCapitalResult calculate(List<SubsetPnlRecord> subsetPnlRecords,
                                       List<com.zcyh.mr.frtbima.model.NmrfPnlRecord> nmrfPnlRecords,
                                       Map<String, BigDecimal> saByDesk,
                                       Set<String> amberDesks,
                                       Set<String> greenDesks,
                                       String dataDate,
                                       String batchId) {
        ImaCapitalResult result = new ImaCapitalResult();
        result.setDataDate(dataDate);
        result.setBatchId(batchId);

        // 步骤1-2: ES 计算 + 流动性调整（间接法仅需3种scenarioType）
        List<EsResult> esResults = esCalculator.calculate(subsetPnlRecords);

        Map<String, BigDecimal> esCurrent        = lhEs.compute(esResults, ImaConstants.SCENARIO_TYPE_NORMAL_FULL);
        Map<String, BigDecimal> esStressReduced  = lhEs.compute(esResults, ImaConstants.SCENARIO_TYPE_STRESS_REDUCED);
        Map<String, BigDecimal> esCurrentReduced = lhEs.compute(esResults, ImaConstants.SCENARIO_TYPE_NORMAL_REDUCED);

        // 步骤3: IMCC（MAR33.15，间接法）
        ImccResult imccResult = imccAggregator.aggregate(
                esCurrent, esStressReduced, esCurrentReduced);
        result.setImccResult(imccResult);

        // 步骤4: SES（MAR33.17）
        List<NmrfStressResult> stressResults = nmrfAggregator.aggregate(nmrfPnlRecords);
        SesResult sesResult = sesCalculator.calculate(stressResults);
        result.setSesResult(sesResult);

        // 步骤5: Amber 附加系数
        BigDecimal k = amberAdjuster.computeSurchargeRatio(saByDesk, amberDesks, greenDesks);
        result.setAmberSurchargeRatio(k);

        // 步骤6: 总资本 = IMCC + SES + (IMCC + SES) × k（MAR33.45）
        BigDecimal imcc = imccResult.getImcc() != null ? imccResult.getImcc() : BigDecimal.ZERO;
        BigDecimal ses  = sesResult.getSes()   != null ? sesResult.getSes()   : BigDecimal.ZERO;
        BigDecimal imccPlusSes = imcc.add(ses);
        BigDecimal amberCharge = amberAdjuster.computeSurcharge(imccPlusSes, k);
        BigDecimal acrTotal = imccPlusSes.add(amberCharge)
                .setScale(10, RoundingMode.HALF_UP);
        result.setAcrTotal(acrTotal);

        return result;
    }
}
