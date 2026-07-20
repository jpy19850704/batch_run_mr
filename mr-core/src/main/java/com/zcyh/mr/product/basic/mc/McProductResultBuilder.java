package com.zcyh.mr.product.basic.mc;

import com.zcyh.mr.product.basic.common.OptionMeasure;
import com.zcyh.mr.product.all.GenericMc.GenericMcTradeInfo;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 通用 MC 估值结果转换器。
 */
public final class McProductResultBuilder {

    private McProductResultBuilder() {
    }

    public static OptionMeasure success(GenericMcTradeInfo input, McPricingContext ctx,
            GenericMcEngine.PayoffResult payoffResult) {
        OptionMeasure measure = newMeasure(input, ctx == null ? null : ctx.dataDate);
        measure.position = ctx.position;
        measure.valuationCcy = ctx.valuationCcy;
        measure.valuationUnit = payoffResult.pv;
        measure.valuation = payoffResult.pv * ctx.position;
        measure.valuationCny = measure.valuation * ctx.fxToCny;
        measure.spotPrice = ctx.spot;
        measure.fwdPrice = ctx.spot;
        measure.impliedVol = avg(ctx.sigma);
        measure.delta = payoffResult.delta;
        measure.gamma = payoffResult.gamma;
        measure.vega = payoffResult.vega;
        measure.theta = payoffResult.theta;
        measure.status = "SUCCESS";
        measure.logs = new ArrayList<>();
        measure.cashFlowList = null;
        measure.sensitivityList = null;
        measure.detail = buildDetail(ctx, payoffResult);
        return measure;
    }

    public static OptionMeasure error(GenericMcTradeInfo input, LocalDate dataDate, List<String> errors) {
        OptionMeasure measure = newMeasure(input, dataDate);
        measure.status = "ERROR";
        measure.logs = OptionMeasure.errorLogs(errors);
        measure.cashFlowList = null;
        measure.sensitivityList = null;
        measure.detail = null;
        return measure;
    }

    private static OptionMeasure newMeasure(GenericMcTradeInfo input, LocalDate dataDate) {
        OptionMeasure measure = new OptionMeasure();
        if (input != null) {
            measure.instrumentId = input.instrumentId;
            measure.productCode = input.productCode;
            measure.valuationCcy = input.currencyCode;
            measure.position = "B".equalsIgnoreCase(input.buyOrSell) ? 1.0 : -1.0;
        }
        measure.dataDate = dataDate;
        return measure;
    }

    private static Map<String, Object> buildDetail(McPricingContext ctx, GenericMcEngine.PayoffResult payoffResult) {
        if (ctx == null || !ctx.pathFlag) {
            return null;
        }
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("PATH_FLAG", true);
        detail.put("UNDERLYING_TYPE", ctx.underlyingType);
        detail.put("MODEL_TYPE", ctx.modelType);
        detail.put("PAYOFF_TYPE", ctx.payoffType);
        detail.put("PATH_STATUS", "IN_MEMORY");
        if (payoffResult.detail != null) {
            detail.putAll(payoffResult.detail);
        }
        return detail;
    }

    private static double avg(double[] values) {
        if (values == null || values.length == 0) {
            return 0.0;
        }
        double sum = 0.0;
        for (double value : values) {
            sum += value;
        }
        return sum / values.length;
    }
}
