package com.zcyh.mr.product.basic.mc;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.annotation.JSONField;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * MC payoff 定义与注册中心。
 */
public final class McPayoffDefinitions {
    private static final Map<String, McPayoffDefinition<?>> MAP = new LinkedHashMap<>();
    private static final double EPS = 0.001;

    static {
        register(new AutoCallDefinition());
    }

    private McPayoffDefinitions() {
    }

    public static void register(McPayoffDefinition<?> definition) {
        MAP.put(normalizeCode(definition.payoffType()), definition);
    }

    public static McPayoffDefinition<?> get(String payoffType) {
        return MAP.get(normalizeCode(payoffType));
    }

    public interface McPayoffSpec {
        void normalize();

        void validate(ValidationCollector errors);
    }

    public interface McPayoffDefinition<P extends McPayoffSpec> {
        String payoffType();

        P parse(Object rawParams);

        GenericMcEngine.PayoffResult price(McPricingContext.PathResult path, P spec, McPricingContext ctx);
    }

    public static final class AutoCallSpec implements McPayoffSpec {
        @JSONField(name = "BARRIER")
        public Double barrier;
        @JSONField(name = "BARRIER_DIRECTION")
        public String barrierDirection;
        @JSONField(name = "PAYOFF_RATE")
        public Double payoffRate;
        @JSONField(name = "PREMIUM_RATE")
        public Double premiumRate;
        @JSONField(name = "NOTIONAL")
        public Double notional;

        @Override
        public void normalize() {
            barrierDirection = normalizeCode(barrierDirection);
        }

        @Override
        public void validate(ValidationCollector errors) {
            errors.requirePositive("BARRIER", barrier);
            errors.requireText("BARRIER_DIRECTION", barrierDirection);
            if (!"UP".equals(barrierDirection) && !"DOWN".equals(barrierDirection)) {
                errors.add("BARRIER_DIRECTION 仅支持 UP/DOWN");
            }
            errors.requireFinite("PAYOFF_RATE", payoffRate);
            errors.requireFinite("PREMIUM_RATE", premiumRate);
            errors.requireNonNegative("NOTIONAL", notional);
        }

        public boolean isUpBarrier() {
            return !"DOWN".equals(barrierDirection);
        }
    }

    private static final class AutoCallDefinition implements McPayoffDefinition<AutoCallSpec> {
        @Override
        public String payoffType() {
            return "AUTO_CALL";
        }

        @Override
        public AutoCallSpec parse(Object rawParams) {
            return parseObject(rawParams, AutoCallSpec.class);
        }

        @Override
        public GenericMcEngine.PayoffResult price(McPricingContext.PathResult path, AutoCallSpec spec,
                McPricingContext ctx) {
            GenericMcEngine.PayoffResult result = new GenericMcEngine.PayoffResult();
            double[][] growth = resolveGrowth(path, ctx);
            if (growth == null || growth.length == 0 || growth[0].length == 0) {
                result.errors.add("AUTO_CALL 路径为空");
                result.detail.put("STATUS", "ERROR");
                return result;
            }
            double baseValue = valuationUnitWithGrowth(growth, ctx.spot, spec, ctx);
            result.pvUnit = baseValue;
            result.pv = baseValue * spec.notional;
            result.delta = calcDelta(growth, spec, ctx) * spec.notional;
            result.gamma = calcGamma(growth, spec, ctx) * spec.notional;
            result.vega = calcVega(path, growth, spec, ctx) * spec.notional;
            result.theta = calcTheta(path, growth, spec, ctx) * spec.notional;
            result.detail.put("PAYOFF_TYPE", payoffType());
            return result;
        }

        private double valuationUnitWithGrowth(double[][] growth, double spot, AutoCallSpec spec,
                McPricingContext ctx) {
            double value = 0.0;
            int steps = growth.length;
            int paths = growth[0].length;
            int last = steps - 1;
            LocalDate accrualStart = ctx.startDate == null ? ctx.dataDate : ctx.startDate;
            for (int pathIndex = 0; pathIndex < paths; pathIndex++) {
                boolean knockedOut = false;
                for (int step = 0; step < steps; step++) {
                    if (isBarrierHit(spot * growth[step][pathIndex], spec)) {
                        LocalDate fixingDate = ctx.observationDates[step];
                        LocalDate paymentDate = ctx.paymentDates[step];
                        double accrual = ChronoUnit.DAYS.between(accrualStart, fixingDate) / 365.0;
                        value += accrual * (spec.payoffRate - spec.premiumRate)
                                * ctx.discountCurve.discount(paymentDate);
                        knockedOut = true;
                        break;
                    }
                }
                if (!knockedOut) {
                    LocalDate fixingDate = ctx.observationDates[last];
                    LocalDate paymentDate = ctx.paymentDates[last];
                    double accrual = ChronoUnit.DAYS.between(accrualStart, fixingDate) / 365.0;
                    value += accrual * (-spec.premiumRate) * ctx.discountCurve.discount(paymentDate);
                }
            }
            return value / paths;
        }

        private boolean isBarrierHit(double spot, AutoCallSpec spec) {
            return spec.isUpBarrier() ? spot >= spec.barrier : spot <= spec.barrier;
        }

        private double calcDelta(double[][] growth, AutoCallSpec spec, McPricingContext ctx) {
            double shift = spotShift(ctx.spot);
            if (shift <= 0.0) {
                return 0.0;
            }
            double up = valuationUnitWithGrowth(growth, ctx.spot + shift, spec, ctx);
            if (ctx.spot - shift <= 0.0) {
                double base = valuationUnitWithGrowth(growth, ctx.spot, spec, ctx);
                return (up - base) / shift;
            }
            double down = valuationUnitWithGrowth(growth, ctx.spot - shift, spec, ctx);
            return (up - down) / (2.0 * shift);
        }

        private double calcGamma(double[][] growth, AutoCallSpec spec, McPricingContext ctx) {
            double shift = spotShift(ctx.spot);
            if (shift <= 0.0) {
                return 0.0;
            }
            double base = valuationUnitWithGrowth(growth, ctx.spot, spec, ctx);
            double up = valuationUnitWithGrowth(growth, ctx.spot + shift, spec, ctx);
            if (ctx.spot - shift <= 0.0) {
                double up2 = valuationUnitWithGrowth(growth, ctx.spot + 2.0 * shift, spec, ctx);
                return (up2 - 2.0 * up + base) / (shift * shift);
            }
            double down = valuationUnitWithGrowth(growth, ctx.spot - shift, spec, ctx);
            return (up - 2.0 * base + down) / (shift * shift);
        }

        private double calcVega(McPricingContext.PathResult path, double[][] growth,
                AutoCallSpec spec, McPricingContext ctx) {
            double[][] random = path == null ? null : path.factor("RANDOM");
            if (random == null) {
                return 0.0;
            }
            double[] shifted = new double[ctx.sigma.length];
            for (int i = 0; i < ctx.sigma.length; i++) {
                shifted[i] = Math.max(1e-8, ctx.sigma[i] + EPS);
            }
            double[][] shiftedGrowth = McUtil.createGrowthWithRandom(ctx.dt1, ctx.dt2, ctx.rd, ctx.rf, shifted, random);
            double base = valuationUnitWithGrowth(growth, ctx.spot, spec, ctx);
            double up = valuationUnitWithGrowth(shiftedGrowth, ctx.spot, spec, ctx);
            return (up - base) / (EPS * 100.0);
        }

        private double calcTheta(McPricingContext.PathResult path, double[][] growth,
                AutoCallSpec spec, McPricingContext ctx) {
            double[][] random = path == null ? null : path.factor("RANDOM");
            if (random == null) {
                return 0.0;
            }
            double shift = 1.0 / 365.0;
            double[] dtAdj = new double[ctx.dt1.length];
            double[] dt2Adj = new double[ctx.dt2.length];
            for (int i = 0; i < ctx.dt1.length; i++) {
                dtAdj[i] = Math.max(1e-8, ctx.dt1[i] - shift);
                dt2Adj[i] = Math.max(1e-8, ctx.dt2[i] + (i == 0 ? -shift : 0.0));
            }
            double[][] shiftedGrowth = McUtil.createGrowthWithRandom(dtAdj, dt2Adj, ctx.rd, ctx.rf, ctx.sigma, random);
            double base = valuationUnitWithGrowth(growth, ctx.spot, spec, ctx);
            double shifted = valuationUnitWithGrowth(shiftedGrowth, ctx.spot, spec, ctx);
            return shifted - base;
        }

        private double spotShift(double spot) {
            return EPS * Math.max(Math.abs(spot), 1.0);
        }

        private double[][] resolveGrowth(McPricingContext.PathResult path, McPricingContext ctx) {
            if (path == null) {
                return null;
            }
            double[][] growth = path.factor("GROWTH");
            if (growth != null) {
                return growth;
            }
            if (path.spotPath == null || ctx.spot == 0.0) {
                return null;
            }
            double[][] derived = new double[path.spotPath.length][path.spotPath[0].length];
            for (int i = 0; i < path.spotPath.length; i++) {
                for (int j = 0; j < path.spotPath[i].length; j++) {
                    derived[i][j] = path.spotPath[i][j] / ctx.spot;
                }
            }
            return derived;
        }
    }

    private static <T> T parseObject(Object rawParams, Class<T> clazz) {
        if (rawParams == null) {
            return JSON.parseObject("{}", clazz);
        }
        if (rawParams instanceof String) {
            String text = ((String) rawParams).trim();
            if (text.startsWith("{") && text.endsWith("}")) {
                return JSON.parseObject(text, clazz);
            }
        }
        return JSON.parseObject(JSON.toJSONString(rawParams), clazz);
    }

    private static String normalizeCode(String value) {
        return value == null ? "" : value.trim().toUpperCase();
    }
}
