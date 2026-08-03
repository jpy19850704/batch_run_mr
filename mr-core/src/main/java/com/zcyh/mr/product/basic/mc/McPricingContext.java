package com.zcyh.mr.product.basic.mc;

import com.zcyh.mr.support.EngineConfiguration;
import com.zcyh.mr.support.EngineConstants;
import com.zcyh.mr.marketdata.CommSpot;
import com.zcyh.mr.marketdata.CommVol;
import com.zcyh.mr.marketdata.EqSpot;
import com.zcyh.mr.marketdata.EqVol;
import com.zcyh.mr.marketdata.FxSpot;
import com.zcyh.mr.marketdata.FxVol;
import com.zcyh.mr.marketdata.IrSpot;
import com.zcyh.mr.marketdata.MarketData;
import com.zcyh.mr.product.all.GenericMc.GenericMcTradeInfo;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MC 估值上下文，保存路径生成前已解析好的公共要素。
 */
public final class McPricingContext {
    public LocalDate dataDate;
    public LocalDate startDate;
    public MarketData marketData;
    public double spot;
    public double fxToCny;
    public double position;
    public String valuationCcy;
    public IrSpot discountCurve;
    public LocalDate[] observationDates;
    public LocalDate[] paymentDates;
    public double[] dt1;
    public double[] dt2;
    public double[] rd;
    public double[] rf;
    public double[] sigma;
    public int[] termDays;
    public LocalVolResolver localVolResolver;
    public int pathNb;
    public boolean pathFlag;
    public String underlyingType;
    public String modelType;
    public String payoffType;
    public String instrumentId;

    public static McPricingContext fromInput(GenericMcTradeInfo input, MarketData marketData,
            LocalDate dataDate, ValidationCollector errors) {
        if (input == null) {
            errors.add("GenericMcTradeInfo 未设置");
            return null;
        }
        McPricingContext ctx = new McPricingContext();
        ctx.dataDate = dataDate;
        ctx.startDate = input.startDate == null ? dataDate : input.startDate;
        ctx.marketData = marketData;
        ctx.position = "B".equalsIgnoreCase(input.buyOrSell) ? 1.0 : -1.0;
        ctx.valuationCcy = input.currencyCode;
        ctx.pathNb = input.pathNb == null ? 10000 : input.pathNb;
        ctx.pathFlag = Boolean.TRUE.equals(input.pathFlag);
        ctx.underlyingType = input.underlyingType;
        ctx.modelType = input.modelType;
        ctx.payoffType = input.payoffType;
        ctx.instrumentId = input.instrumentId;

        if (marketData == null) {
            errors.add("marketData 未设置");
            return ctx;
        }
        if (input.discountCurve != null && marketData.irSpot != null
                && marketData.irSpot.containsKey(input.discountCurve)) {
            ctx.discountCurve = new IrSpot(marketData.irSpot.get(input.discountCurve));
        }
        ctx.observationDates = filterUnexpiredObsDates(parseObsDates(input.obsDates), dataDate);
        if (ctx.observationDates.length == 0) {
            errors.add("OBS_DATES 无未到期观察日");
            return ctx;
        }
        ctx.paymentDates = resolvePaymentDates(ctx.observationDates);
        initMarketFactors(ctx, input, marketData, dataDate, errors);
        return ctx;
    }

    public PathRequest toPathRequest(Object modelSpec) {
        PathRequest request = new PathRequest();
        request.spot = spot;
        request.dt1 = dt1;
        request.dt2 = dt2;
        request.rd = rd;
        request.rf = rf;
        request.sigma = sigma;
        request.termDays = termDays;
        request.localVolResolver = localVolResolver;
        request.pathNb = pathNb;
        request.modelType = modelType;
        request.modelSpec = modelSpec;
        return request;
    }

    private static void initMarketFactors(McPricingContext ctx, GenericMcTradeInfo input,
            MarketData marketData, LocalDate dataDate, ValidationCollector errors) {
        try {
            if ("FX".equals(ctx.underlyingType)) {
                initFxFactors(ctx, input, marketData, dataDate);
            } else if ("EQ".equals(ctx.underlyingType)) {
                initEqFactors(ctx, input, marketData, dataDate);
            } else if ("COMM".equals(ctx.underlyingType)) {
                initCommFactors(ctx, input, marketData, dataDate);
            } else if ("IR".equals(ctx.underlyingType)) {
                errors.add("IR 标的通用 MC 路径上下文第一阶段尚未接入");
            } else {
                errors.add("通用 MC 暂不支持的 UNDERLYING_TYPE: " + ctx.underlyingType);
            }
        } catch (Exception ex) {
            errors.add("路径上下文构建异常: " + ex.getClass().getSimpleName() + ": " + ex.getMessage());
        }
    }

    private static void initFxFactors(McPricingContext ctx, GenericMcTradeInfo input,
            MarketData marketData, LocalDate dataDate) {
        FxSpot fxSpot = new FxSpot(EngineConfiguration.getInstance().getValue(EngineConstants.CFG.FX_BASE_CODE), marketData.fxSpot);
        IrSpot baseIrSpot = new IrSpot(marketData.irSpot.get(input.baseDiscountCurve));
        IrSpot underlyingIrSpot = new IrSpot(marketData.irSpot.get(input.underlyingDiscountCurve));
        FxVol fxVol = new FxVol(marketData.fxVol.get(input.volatilitySurface));
        ctx.spot = fxSpot.getFxrate(input.baseCurrencyCode, input.underlyingCurrencyCode);
        ctx.fxToCny = fxSpot.getFxrate(input.currencyCode);
        fillTermArrays(ctx, dataDate, (date, obsT) -> baseIrSpot.spotRate(date),
                (date, spot, rd, obsT) -> underlyingIrSpot.spotRate(date),
                days -> pickVol(fxVol.getVolCur(days)));
        if (isLocalVol(ctx.modelType)) {
            ctx.localVolResolver = LocalVolSurfaceResolver.fromFx(marketData.fxVol.get(input.volatilitySurface),
                    ctx.spot, ctx.termDays, ctx.dt1, ctx.rd, ctx.rf);
        }
    }

    private static void initEqFactors(McPricingContext ctx, GenericMcTradeInfo input,
            MarketData marketData, LocalDate dataDate) {
        FxSpot fxSpot = new FxSpot(EngineConfiguration.getInstance().getValue(EngineConstants.CFG.FX_BASE_CODE), marketData.fxSpot);
        EqSpot eqSpot = new EqSpot(marketData.eqSpot.get(input.referenceCurve));
        EqVol eqVol = new EqVol(marketData.eqVol.get(input.volatilitySurface));
        ctx.spot = eqSpot.fwdPrice(dataDate);
        ctx.fxToCny = fxSpot.getFxrate(input.currencyCode);
        fillTermArrays(ctx, dataDate, (date, obsT) -> ctx.discountCurve.spotRate(date),
                (date, spot, rd, obsT) -> 0.0,
                days -> pickVol(eqVol.getVolCur(days)));
        if (isLocalVol(ctx.modelType)) {
            ctx.localVolResolver = LocalVolSurfaceResolver.fromEq(marketData.eqVol.get(input.volatilitySurface),
                    ctx.spot, ctx.termDays, ctx.dt1, ctx.rd, ctx.rf);
        }
    }

    private static void initCommFactors(McPricingContext ctx, GenericMcTradeInfo input,
            MarketData marketData, LocalDate dataDate) {
        FxSpot fxSpot = new FxSpot(EngineConfiguration.getInstance().getValue(EngineConstants.CFG.FX_BASE_CODE), marketData.fxSpot);
        CommSpot commSpot = new CommSpot(marketData.commSpot.get(input.referenceCurve));
        CommVol commVol = new CommVol(marketData.commVol.get(input.volatilitySurface));
        ctx.spot = commSpot.fwdPrice(dataDate);
        ctx.fxToCny = fxSpot.getFxrate(input.currencyCode);
        fillTermArrays(ctx, dataDate, (date, obsT) -> ctx.discountCurve.spotRate(date),
                (date, spot, rd, obsT) -> resolveCommCarry(commSpot, date, spot, rd, obsT),
                days -> pickVol(commVol.getVolCur(days)));
        if (isLocalVol(ctx.modelType)) {
            ctx.localVolResolver = LocalVolSurfaceResolver.fromComm(marketData.commVol.get(input.volatilitySurface),
                    ctx.spot, ctx.termDays, ctx.dt1, ctx.rd, ctx.rf);
        }
    }

    private static double resolveCommCarry(CommSpot commSpot, LocalDate obsDate, double spot, double rd, double obsT) {
        if (obsT <= 0.0) {
            return rd;
        }
        double forward = commSpot.fwdPrice(obsDate);
        if (spot <= 0.0 || forward <= 0.0) {
            throw new IllegalArgumentException("商品价格不能为零或负数");
        }
        return -Math.log(forward / spot) / obsT + rd;
    }

    private static void fillTermArrays(McPricingContext ctx, LocalDate dataDate,
            RdResolver rdResolver, RfResolver rfResolver, VolResolver volResolver) {
        int n = ctx.observationDates.length;
        ctx.dt1 = new double[n];
        ctx.dt2 = new double[n];
        ctx.rd = new double[n];
        ctx.rf = new double[n];
        ctx.sigma = new double[n];
        ctx.termDays = new int[n];
        for (int i = 0; i < n; i++) {
            LocalDate date = ctx.observationDates[i];
            int d1 = (int) ChronoUnit.DAYS.between(dataDate, date);
            int d2 = i == 0 ? d1 : (int) ChronoUnit.DAYS.between(ctx.observationDates[i - 1], date);
            double obsT = d1 / 365.0;
            double rd = rdResolver.resolve(date, obsT);
            ctx.termDays[i] = d1;
            ctx.dt1[i] = obsT;
            ctx.dt2[i] = d2 / 365.0;
            ctx.rd[i] = rd;
            ctx.rf[i] = rfResolver.resolve(date, ctx.spot, rd, obsT);
            ctx.sigma[i] = volResolver.resolve(d1);
        }
    }

    private static LocalDate[] parseObsDates(String value) {
        if (value == null || value.trim().isEmpty()) {
            return new LocalDate[0];
        }
        return Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(v -> !v.isEmpty())
                .map(LocalDate::parse)
                .sorted()
                .toArray(LocalDate[]::new);
    }

    private static LocalDate[] filterUnexpiredObsDates(LocalDate[] obsDates, LocalDate dataDate) {
        return Arrays.stream(obsDates)
                .filter(d -> dataDate == null || d.isAfter(dataDate))
                .toArray(LocalDate[]::new);
    }

    private static LocalDate[] resolvePaymentDates(LocalDate[] observationDates) {
        LocalDate[] paymentDates = new LocalDate[observationDates.length];
        System.arraycopy(observationDates, 0, paymentDates, 0, observationDates.length);
        return paymentDates;
    }

    private static double pickVol(List<Map<String, Object>> volCurve) {
        if (volCurve == null || volCurve.isEmpty()) {
            return 0.0;
        }
        double[] nearest = null;
        double bestDistance = Double.MAX_VALUE;
        double[] left = null;
        double[] right = null;
        for (Map<String, Object> row : volCurve) {
            double delta = toDouble(row.get("DELTA"));
            double vol = toDouble(row.get("VOLATILITY_RATE"));
            if (!Double.isFinite(delta) || !Double.isFinite(vol)) {
                continue;
            }
            double distance = Math.abs(delta - 0.5);
            if (distance < 1e-12) {
                return vol;
            }
            if (distance < bestDistance) {
                bestDistance = distance;
                nearest = new double[] { delta, vol };
            }
            if (delta < 0.5 && (left == null || delta > left[0])) {
                left = new double[] { delta, vol };
            }
            if (delta > 0.5 && (right == null || delta < right[0])) {
                right = new double[] { delta, vol };
            }
        }
        if (left != null && right != null && right[0] > left[0]) {
            double weight = (0.5 - left[0]) / (right[0] - left[0]);
            return left[1] + weight * (right[1] - left[1]);
        }
        return nearest == null ? 0.0 : nearest[1];
    }

    private static double toDouble(Object value) {
        if (value == null) {
            return Double.NaN;
        }
        if (value instanceof Number) {
            return ((Number) value).doubleValue();
        }
        return Double.parseDouble(String.valueOf(value));
    }

    private static boolean isLocalVol(String modelType) {
        return modelType != null && "LOCAL_VOL".equalsIgnoreCase(modelType.trim());
    }

    private interface RdResolver {
        double resolve(LocalDate date, double obsT);
    }

    private interface RfResolver {
        double resolve(LocalDate date, double spot, double rd, double obsT);
    }

    private interface VolResolver {
        double resolve(int days);
    }

    public interface LocalVolResolver {
        double resolve(int days, double currentSpot, double initialSpot);

        String axis2Type();
    }

    public static final class PathRequest {
        public double spot;
        public double[] dt1;
        public double[] dt2;
        public double[] rd;
        public double[] rf;
        public double[] sigma;
        public int[] termDays;
        public LocalVolResolver localVolResolver;
        public int pathNb;
        public String modelType;
        public Object modelSpec;

        public void validate(ValidationCollector errors) {
            if (!Double.isFinite(spot) || spot <= 0.0) {
                errors.add("SPOT_PRICE 必须为正数");
            }
            if (pathNb <= 0) {
                errors.add("PATH_NB 必须为正整数");
            }
            int n = dt1 == null ? -1 : dt1.length;
            if (n <= 0 || dt2 == null || rd == null || rf == null
                    || dt2.length != n || rd.length != n || rf.length != n) {
                errors.add("路径参数 dt1/dt2/rd/rf 长度不一致");
                return;
            }
            for (int i = 0; i < n; i++) {
                if (!Double.isFinite(dt1[i]) || !Double.isFinite(dt2[i]) || dt2[i] <= 0.0) {
                    errors.add("路径时间步长非法: index=" + i);
                    return;
                }
            }
        }
    }

    public static final class PathResult {
        public double[][] spotPath;
        public Map<String, double[][]> factorPaths = new LinkedHashMap<>();
        public Map<String, Object> detail = new LinkedHashMap<>();

        public double[][] factor(String name) {
            return factorPaths == null ? null : factorPaths.get(name);
        }
    }
}
