package com.zcyh.mr.product.basic.mc;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 通用 MC 估值流程骨架。
 */
public final class GenericMcEngine {
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final String MC_PATH_DIR = System.getProperty("mr.mc.path.dir",
            Paths.get(".", "data", "mc_path").toString());

    public PayoffResult price(McPricingContext ctx, Object modelParams, Object payoffParams) {
        ValidationCollector errors = new ValidationCollector();
        if (ctx == null) {
            errors.add("McPricingContext 未设置");
            return PayoffResult.error(errors);
        }

        McPathModelDefinitions.McPathModelDefinition modelDefinition =
                McPathModelDefinitions.get(ctx.modelType);
        McPayoffDefinitions.McPayoffDefinition payoffDefinition =
                McPayoffDefinitions.get(ctx.payoffType);
        if (modelDefinition == null) {
            errors.add("不支持的 MODEL_TYPE: " + ctx.modelType);
        }
        if (payoffDefinition == null) {
            errors.add("不支持的 PAYOFF_TYPE: " + ctx.payoffType);
        }
        if (errors.hasErrors()) {
            return PayoffResult.error(errors);
        }

        McPathModelDefinitions.McPathModelSpec modelSpec =
                (McPathModelDefinitions.McPathModelSpec) modelDefinition.parse(modelParams);
        McPayoffDefinitions.McPayoffSpec payoffSpec =
                (McPayoffDefinitions.McPayoffSpec) payoffDefinition.parse(payoffParams);
        modelSpec.normalize();
        payoffSpec.normalize();
        modelSpec.validate(errors);
        payoffSpec.validate(errors);
        McPricingContext.PathRequest request = ctx.toPathRequest(modelSpec);
        request.validate(errors);
        if (errors.hasErrors()) {
            return PayoffResult.error(errors);
        }

        try {
            McPricingContext.PathResult path = generatePath(modelDefinition, request, modelSpec, ctx);
            PayoffResult result = pricePayoff(payoffDefinition, path, payoffSpec, ctx);
            appendPathDetailIfNeeded(result, ctx, path);
            return result;
        } catch (Exception ex) {
            errors.add("通用 MC 估值异常: " + ex.getClass().getSimpleName() + ": " + ex.getMessage());
            return PayoffResult.error(errors);
        }
    }

    @SuppressWarnings({ "rawtypes", "unchecked" })
    private McPricingContext.PathResult generatePath(McPathModelDefinitions.McPathModelDefinition definition,
            McPricingContext.PathRequest request,
            McPathModelDefinitions.McPathModelSpec spec,
            McPricingContext ctx) {
        return definition.generate(request, spec, ctx);
    }

    @SuppressWarnings({ "rawtypes", "unchecked" })
    private PayoffResult pricePayoff(McPayoffDefinitions.McPayoffDefinition definition,
            McPricingContext.PathResult path,
            McPayoffDefinitions.McPayoffSpec spec,
            McPricingContext ctx) {
        return definition.price(path, spec, ctx);
    }

    private void appendPathDetailIfNeeded(PayoffResult result, McPricingContext ctx, McPricingContext.PathResult path) {
        if (result == null || ctx == null || !ctx.pathFlag) {
            return;
        }
        String filePath = writePathToFile(ctx, path);
        result.detail.put("PATH_FLAG", true);
        result.detail.put("PATH_STATUS", filePath == null ? "FAILED" : "WRITTEN");
        result.detail.put("PATH_FILE", filePath);
    }

    private String writePathToFile(McPricingContext ctx, McPricingContext.PathResult path) {
        if (path == null || path.spotPath == null) {
            return null;
        }
        try {
            Path dir = Paths.get(MC_PATH_DIR);
            Files.createDirectories(dir);
            String fileName = resolvePathFileName(ctx) + "_path.csv";
            Path filePath = dir.resolve(fileName);
            StringBuilder sb = new StringBuilder();
            sb.append("# DATA_DATE=").append(ctx.dataDate == null ? "" : ctx.dataDate.format(DATE_FMT)).append('\n');
            sb.append("# UNDERLYING_TYPE=").append(ctx.underlyingType).append('\n');
            sb.append("# MODEL_TYPE=").append(ctx.modelType).append('\n');
            sb.append("# PAYOFF_TYPE=").append(ctx.payoffType).append('\n');
            sb.append("# SPOT_PRICE=").append(ctx.spot).append('\n');
            sb.append("# PATH_NB=").append(ctx.pathNb).append('\n');
            appendDateRow(sb, "OBS_DATES", ctx.observationDates);
            appendDateRow(sb, "PAYMENT_DATES", ctx.paymentDates);
            appendArrayRow(sb, "dt1", ctx.dt1);
            appendArrayRow(sb, "dt2", ctx.dt2);
            appendArrayRow(sb, "rd", ctx.rd);
            appendArrayRow(sb, "rf", ctx.rf);
            appendArrayRow(sb, "sigma", ctx.sigma);
            sb.append("# PATHS\n");
            for (double[] row : path.spotPath) {
                for (int i = 0; i < row.length; i++) {
                    if (i > 0) {
                        sb.append(',');
                    }
                    sb.append(row[i]);
                }
                sb.append('\n');
            }
            Files.write(filePath, sb.toString().getBytes(StandardCharsets.UTF_8));
            return filePath.toAbsolutePath().toString();
        } catch (IOException ex) {
            return null;
        }
    }

    private String resolvePathFileName(McPricingContext ctx) {
        String base = "generic_mc";
        if (ctx != null && ctx.instrumentId != null && !ctx.instrumentId.trim().isEmpty()) {
            base = ctx.instrumentId;
        }
        return base.replaceAll("[^a-zA-Z0-9_\\-]", "_");
    }

    private static void appendDateRow(StringBuilder sb, String label, java.time.LocalDate[] dates) {
        sb.append("# ").append(label).append('=');
        if (dates != null) {
            for (int i = 0; i < dates.length; i++) {
                if (i > 0) {
                    sb.append(',');
                }
                sb.append(dates[i].format(DATE_FMT));
            }
        }
        sb.append('\n');
    }

    private static void appendArrayRow(StringBuilder sb, String label, double[] values) {
        sb.append(label);
        if (values != null) {
            for (double value : values) {
                sb.append(',').append(value);
            }
        }
        sb.append('\n');
    }

    public static final class PayoffResult {
        public double pv;
        public double pvUnit;
        public double delta;
        public double gamma;
        public double vega;
        public double theta;
        public Map<String, Object> detail = new LinkedHashMap<>();
        public List<String> errors = new ArrayList<>();

        public static PayoffResult error(ValidationCollector collector) {
            PayoffResult result = new PayoffResult();
            result.errors.addAll(collector.errors());
            result.detail.put("STATUS", "ERROR");
            return result;
        }
    }
}
