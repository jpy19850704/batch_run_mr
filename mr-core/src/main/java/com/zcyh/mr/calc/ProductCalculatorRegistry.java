package com.zcyh.mr.calc;

import com.alibaba.fastjson2.JSONObject;
import com.zcyh.mr.core.Calendar;
import com.zcyh.mr.core.Constants;
import com.zcyh.mr.marketdata.MarketData;

import java.time.LocalDate;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 产品计算器注册表。
 */
public final class ProductCalculatorRegistry {

    @FunctionalInterface
    private interface CalcFactory {
        ProductCalculator create(String operCode, LocalDate dataDate,
                List<HashMap<String, Object>> trades,
                MarketData marketData, Calendar calendar, JSONObject otherData);
    }

    private static final Map<String, CalcFactory> REGISTRY = buildRegistry();
    private static final Set<String> PRODUCT_CODES = Collections.unmodifiableSet(
            new LinkedHashSet<>(REGISTRY.keySet()));

    private ProductCalculatorRegistry() {
    }

    public static boolean supports(String productCode) {
        return REGISTRY.containsKey(productCode);
    }

    public static Set<String> productCodes() {
        return PRODUCT_CODES;
    }

    public static ProductCalculator create(String productCode, String operCode, LocalDate dataDate,
            List<HashMap<String, Object>> trades, MarketData marketData, Calendar calendar, JSONObject otherData) {
        CalcFactory factory = REGISTRY.get(productCode);
        if (factory == null) {
            throw new IllegalArgumentException("不支持的产品类型: " + productCode);
        }
        return factory.create(operCode, dataDate, trades, marketData, calendar, otherData);
    }

    private static Map<String, CalcFactory> buildRegistry() {
        Map<String, CalcFactory> registry = new LinkedHashMap<>();
        registry.put(Constants.PRODUCT_CODE.COMMFWD,
                (op, dt, tr, md, cal, oth) -> new CommFwdCalc(op, dt, tr, md));
        registry.put(Constants.PRODUCT_CODE.COMMSWAP,
                (op, dt, tr, md, cal, oth) -> new CommSwapCalc(op, dt, tr, md));
        registry.put(Constants.PRODUCT_CODE.BOND,
                (op, dt, tr, md, cal, oth) -> new BondCalc(op, dt, tr, md, cal));
        registry.put(Constants.PRODUCT_CODE.WILLOW_BOND,
                (op, dt, tr, md, cal, oth) -> new WillowBondCalc(op, dt, tr, md, cal));
        registry.put(Constants.PRODUCT_CODE.FXFWD,
                (op, dt, tr, md, cal, oth) -> new FxFwdCalc(op, dt, tr, md));
        registry.put(Constants.PRODUCT_CODE.FXSWAP,
                (op, dt, tr, md, cal, oth) -> new FxSwapCalc(op, dt, tr, md));
        registry.put(Constants.PRODUCT_CODE.COMMOPT,
                (op, dt, tr, md, cal, oth) -> new CommOptCalc(op, dt, tr, md));
        registry.put(Constants.PRODUCT_CODE.IRSCCS,
                (op, dt, tr, md, cal, oth) -> new IrsCcsCalc(op, dt, tr, md, cal));
        registry.put(Constants.PRODUCT_CODE.CAPFLOOR,
                (op, dt, tr, md, cal, oth) -> new CapFloorCalc(op, dt, tr, md, cal));
        registry.put(Constants.PRODUCT_CODE.FXOPT,
                (op, dt, tr, md, cal, oth) -> new FxVanillaOptCalc(op, dt, tr, md));
        registry.put(Constants.PRODUCT_CODE.FX_ASIAN,
                (op, dt, tr, md, cal, oth) -> new FxAsianCalc(op, dt, tr, md));
        registry.put(Constants.PRODUCT_CODE.EQ_ASIAN,
                (op, dt, tr, md, cal, oth) -> new EqAsianCalc(op, dt, tr, md));
        registry.put(Constants.PRODUCT_CODE.COMM_ASIAN,
                (op, dt, tr, md, cal, oth) -> new CommAsianCalc(op, dt, tr, md));
        registry.put(Constants.PRODUCT_CODE.AUTO_CALL,
                (op, dt, tr, md, cal, oth) -> new GenericMcCalc(op, dt, tr, md));
        registry.put(Constants.PRODUCT_CODE.COMPOSITE,
                (op, dt, tr, md, cal, oth) -> new CompositeCalc(op, dt, tr, md, cal, oth));
        registry.put(Constants.PRODUCT_CODE.FX_SPREADOPT,
                (op, dt, tr, md, cal, oth) -> new FxSpreadOptCalc(op, dt, tr, md));
        registry.put(Constants.PRODUCT_CODE.EQ_SPREADOPT,
                (op, dt, tr, md, cal, oth) -> new EqSpreadOptCalc(op, dt, tr, md));
        registry.put(Constants.PRODUCT_CODE.COMM_SPREADOPT,
                (op, dt, tr, md, cal, oth) -> new CommSpreadOptCalc(op, dt, tr, md));
        registry.put(Constants.PRODUCT_CODE.IR_SPREADOPT,
                (op, dt, tr, md, cal, oth) -> new IrSpreadOptCalc(op, dt, tr, md));
        registry.put(Constants.PRODUCT_CODE.IR_BARRIER,
                (op, dt, tr, md, cal, oth) -> new IrBarOptCalc(op, dt, tr, md));
        registry.put(Constants.PRODUCT_CODE.EQ_BARRIER,
                (op, dt, tr, md, cal, oth) -> new EqBarOptCalc(op, dt, tr, md));
        registry.put(Constants.PRODUCT_CODE.FX_BARRIER,
                (op, dt, tr, md, cal, oth) -> new FxBarOptCalc(op, dt, tr, md));
        registry.put(Constants.PRODUCT_CODE.COMM_BARRIER,
                (op, dt, tr, md, cal, oth) -> new CommBarOptCalc(op, dt, tr, md));
        registry.put(Constants.PRODUCT_CODE.IR_DIGITAL,
                (op, dt, tr, md, cal, oth) -> new IrDigOptCalc(op, dt, tr, md));
        registry.put(Constants.PRODUCT_CODE.EQ_DIGITAL,
                (op, dt, tr, md, cal, oth) -> new EqDigOptCalc(op, dt, tr, md));
        registry.put(Constants.PRODUCT_CODE.FX_DIGITAL,
                (op, dt, tr, md, cal, oth) -> new FxDigOptCalc(op, dt, tr, md));
        registry.put(Constants.PRODUCT_CODE.COMM_DIGITAL,
                (op, dt, tr, md, cal, oth) -> new CommDigOptCalc(op, dt, tr, md));
        registry.put(Constants.PRODUCT_CODE.FX_WEDDING_CAKE,
                (op, dt, tr, md, cal, oth) -> new FxWeddingCakeCalc(op, dt, tr, md));
        registry.put(Constants.PRODUCT_CODE.EQ_WEDDING_CAKE,
                (op, dt, tr, md, cal, oth) -> new EqWeddingCakeCalc(op, dt, tr, md));
        registry.put(Constants.PRODUCT_CODE.COMM_WEDDING_CAKE,
                (op, dt, tr, md, cal, oth) -> new CommWeddingCakeCalc(op, dt, tr, md));
        registry.put(Constants.PRODUCT_CODE.IR_WEDDING_CAKE,
                (op, dt, tr, md, cal, oth) -> new IrWeddingCakeCalc(op, dt, tr, md));
        registry.put(Constants.PRODUCT_CODE.EQ_SHARKFIN,
                (op, dt, tr, md, cal, oth) -> new EqSharkFinCalc(op, dt, tr, md));
        registry.put(Constants.PRODUCT_CODE.COMM_SHARKFIN,
                (op, dt, tr, md, cal, oth) -> new CommSharkFinCalc(op, dt, tr, md));
        registry.put(Constants.PRODUCT_CODE.IR_SHARKFIN,
                (op, dt, tr, md, cal, oth) -> new IrSharkFinCalc(op, dt, tr, md));
        registry.put(Constants.PRODUCT_CODE.SWAPTION,
                (op, dt, tr, md, cal, oth) -> new SwaptionCalc(op, dt, tr, md, cal));
        registry.put(Constants.PRODUCT_CODE.BOND_FUTURE,
                (op, dt, tr, md, cal, oth) -> new BondFutureCalc(op, dt, tr, md, cal, oth));
        registry.put(Constants.PRODUCT_CODE.CDS,
                (op, dt, tr, md, cal, oth) -> new CdsCalc(op, dt, tr, md, cal, oth));
        registry.put(Constants.PRODUCT_CODE.TRS,
                (op, dt, tr, md, cal, oth) -> new TrsCalc(op, dt, tr, md, cal, oth));
        registry.put(Constants.PRODUCT_CODE.IR_RA,
                (op, dt, tr, md, cal, oth) -> new IrRangeAccureOptCalc(op, dt, tr, md));
        registry.put(Constants.PRODUCT_CODE.IR_STEP_UP,
                (op, dt, tr, md, cal, oth) -> new IrStepUpOptCalc(op, dt, tr, md));
        registry.put(Constants.PRODUCT_CODE.EQ_RA,
                (op, dt, tr, md, cal, oth) -> new EqRangeAccureOptCalc(op, dt, tr, md));
        registry.put(Constants.PRODUCT_CODE.EQ_STEP_UP,
                (op, dt, tr, md, cal, oth) -> new EqStepUpOptCalc(op, dt, tr, md));
        registry.put(Constants.PRODUCT_CODE.COMM_RA,
                (op, dt, tr, md, cal, oth) -> new CommRangeAccureOptCalc(op, dt, tr, md));
        registry.put(Constants.PRODUCT_CODE.COMM_STEP_UP,
                (op, dt, tr, md, cal, oth) -> new CommStepUpOptCalc(op, dt, tr, md));
        registry.put(Constants.PRODUCT_CODE.FX_RA,
                (op, dt, tr, md, cal, oth) -> new FxRangeAccureOptCalc(op, dt, tr, md));
        registry.put(Constants.PRODUCT_CODE.FX_STEP_UP,
                (op, dt, tr, md, cal, oth) -> new FxStepUpOptCalc(op, dt, tr, md));
        registry.put(Constants.PRODUCT_CODE.STD_IRS,
                (op, dt, tr, md, cal, oth) -> new StdIrsCalc(op, dt, tr, md, cal));
        return Collections.unmodifiableMap(registry);
    }
}
