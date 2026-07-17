package com.zcyh.mr.product.basic.mc;

import com.zcyh.mr.math.SobolRandomEngine;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * MC 路径模型定义与注册中心。
 */
public final class McPathModelDefinitions {
    private static final Map<String, McPathModelDefinition<?>> MAP = new LinkedHashMap<>();

    static {
        register(new ConstVolPathModel());
        register(new LocalVolPathModel());
        register(new HestonPathModel());
    }

    private McPathModelDefinitions() {
    }

    public static void register(McPathModelDefinition<?> definition) {
        MAP.put(normalize(definition.modelType()), definition);
    }

    public static McPathModelDefinition<?> get(String modelType) {
        return MAP.get(normalize(modelType));
    }

    public interface McPathModelSpec {
        void normalize();

        void validate(ValidationCollector errors);
    }

    public interface McPathModelDefinition<P extends McPathModelSpec> {
        String modelType();

        P parse(Object rawParams);

        McPricingContext.PathResult generate(McPricingContext.PathRequest request, P spec, McPricingContext ctx);
    }

    public static final class ConstVolSpec implements McPathModelSpec {
        @Override
        public void normalize() {
        }

        @Override
        public void validate(ValidationCollector errors) {
        }
    }

    private static final class ConstVolPathModel implements McPathModelDefinition<ConstVolSpec> {
        @Override
        public String modelType() {
            return "CONST_VOL";
        }

        @Override
        public ConstVolSpec parse(Object rawParams) {
            return new ConstVolSpec();
        }

        @Override
        public McPricingContext.PathResult generate(McPricingContext.PathRequest request, ConstVolSpec spec,
                McPricingContext ctx) {
            ValidationCollector errors = new ValidationCollector();
            request.validate(errors);
            validateSigma(request, errors);
            if (errors.hasErrors()) {
                throw new IllegalArgumentException(String.join("; ", errors.errors()));
            }
            double[][] random = SobolRandomEngine.generateNormalMatrix(request.dt1.length, request.pathNb);
            double[][] growth = McUtil.createGrowthWithRandom(
                    request.dt1, request.dt2, request.rd, request.rf, request.sigma, random);
            McPricingContext.PathResult result = new McPricingContext.PathResult();
            result.spotPath = McUtil.scalePath(growth, request.spot);
            result.factorPaths.put("RANDOM", random);
            result.factorPaths.put("GROWTH", growth);
            result.detail.put("MODEL_TYPE", modelType());
            return result;
        }
    }

    public static final class LocalVolSpec implements McPathModelSpec {
        public String processType = "LOG_NORMAL";

        @Override
        public void normalize() {
            processType = "LOG_NORMAL";
        }

        @Override
        public void validate(ValidationCollector errors) {
        }
    }

    private static final class LocalVolPathModel implements McPathModelDefinition<LocalVolSpec> {
        @Override
        public String modelType() {
            return "LOCAL_VOL";
        }

        @Override
        public LocalVolSpec parse(Object rawParams) {
            return new LocalVolSpec();
        }

        @Override
        public McPricingContext.PathResult generate(McPricingContext.PathRequest request, LocalVolSpec spec,
                McPricingContext ctx) {
            ValidationCollector errors = new ValidationCollector();
            request.validate(errors);
            validateLocalVolRequest(request, errors);
            if (errors.hasErrors()) {
                throw new IllegalArgumentException(String.join("; ", errors.errors()));
            }

            double[][] random = SobolRandomEngine.generateNormalMatrix(request.dt1.length, request.pathNb);
            double[][] spotPath = new double[request.dt1.length][request.pathNb];
            double[][] sigmaPath = new double[request.dt1.length][request.pathNb];
            for (int sim = 0; sim < request.pathNb; sim++) {
                double currentSpot = request.spot;
                for (int obs = 0; obs < request.dt1.length; obs++) {
                    double sigma = request.localVolResolver.resolve(request.termDays[obs], currentSpot, request.spot);
                    if (!Double.isFinite(sigma) || sigma <= 0.0) {
                        throw new IllegalArgumentException("LOCAL_VOL VOLATILITY_RATE 非法: index=" + obs);
                    }
                    double drift = (request.rd[obs] - request.rf[obs] - 0.5 * sigma * sigma) * request.dt2[obs];
                    double diffusion = sigma * Math.sqrt(request.dt2[obs]) * random[obs][sim];
                    currentSpot = currentSpot * Math.exp(drift + diffusion);
                    spotPath[obs][sim] = currentSpot;
                    sigmaPath[obs][sim] = sigma;
                }
            }

            McPricingContext.PathResult result = new McPricingContext.PathResult();
            result.spotPath = spotPath;
            result.factorPaths.put("RANDOM", random);
            result.factorPaths.put("LOCAL_VOL", sigmaPath);
            result.detail.put("MODEL_TYPE", modelType());
            result.detail.put("PROCESS_TYPE", spec.processType);
            result.detail.put("AXIS2_TYPE", request.localVolResolver.axis2Type());
            return result;
        }
    }

    public static final class HestonSpec implements McPathModelSpec {
        public Double kappa;
        public Double theta;
        public Double volOfVol;
        public Double rho;
        public Double v0;

        @Override
        public void normalize() {
        }

        @Override
        public void validate(ValidationCollector errors) {
            errors.add("HESTON 路径模型第一步仅保留接口，尚未接入实现");
        }
    }

    private static final class HestonPathModel implements McPathModelDefinition<HestonSpec> {
        @Override
        public String modelType() {
            return "HESTON";
        }

        @Override
        public HestonSpec parse(Object rawParams) {
            return new HestonSpec();
        }

        @Override
        public McPricingContext.PathResult generate(McPricingContext.PathRequest request, HestonSpec spec,
                McPricingContext ctx) {
            throw new UnsupportedOperationException("HESTON 路径模型尚未实现");
        }
    }

    private static void validateSigma(McPricingContext.PathRequest request, ValidationCollector errors) {
        if (request.sigma == null || request.sigma.length != request.dt1.length) {
            errors.add("普通 MC 路径模型要求 sigma 与观察日长度一致");
            return;
        }
        for (int i = 0; i < request.sigma.length; i++) {
            if (!Double.isFinite(request.sigma[i]) || request.sigma[i] <= 0.0) {
                errors.add("VOLATILITY_RATE 非法: index=" + i);
                return;
            }
        }
    }

    private static void validateLocalVolRequest(McPricingContext.PathRequest request, ValidationCollector errors) {
        if (request.localVolResolver == null) {
            errors.add("LOCAL_VOL 缺少波动率曲面解析器");
        }
        int n = request.dt1 == null ? -1 : request.dt1.length;
        if (request.termDays == null || request.termDays.length != n) {
            errors.add("LOCAL_VOL 期限数组与观察日长度不一致");
            return;
        }
        for (int i = 0; i < request.termDays.length; i++) {
            if (request.termDays[i] <= 0) {
                errors.add("LOCAL_VOL 期限非法: index=" + i);
                return;
            }
        }
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase();
    }
}
