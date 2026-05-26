package com.zcyh.mr.loader;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 自动生成产品字段主数据（CSV）并输出差异报告。
 *
 * 数据来源：
 * 1) validationRules.json（字段校验规则）
 * 2) productModel.json（默认值模型）
 * 3) product 目录 Java 源码中的 @JSONField(name="...") + Info 类字段
 *
 * 输出文件：
 * 1) product_input_fields_auto.csv
 * 2) product_input_fields_auto_diff.md
 */
public final class ProductInputFieldAutoGenerator {

    private static final String CSV_HEADER = "产品类型,资产大类,字段,Java字段名,字段类型,字段含义,值域,特殊规则";

    private static final Pattern JSON_FIELD_PATTERN = Pattern.compile(
            "@JSONField\\s*\\(\\s*name\\s*=\\s*\"([A-Z0-9_]+)\"");
    private static final Pattern CLASS_PATTERN = Pattern.compile("\\bclass\\s+([A-Za-z0-9_]+)\\b");
    private static final Pattern INFO_EXTENDS_PATTERN = Pattern.compile(
            "\\bclass\\s+([A-Za-z0-9_]+)\\s+extends\\s+([A-Za-z0-9_$.<>]+)");
    private static final Pattern PUBLIC_FIELD_PATTERN = Pattern.compile(
            "\\bpublic\\s+[A-Za-z0-9_$.<>\\[\\], ?]+\\s+([A-Za-z0-9_]+)\\s*(?:=[^;]*)?;");
    private static final Pattern REGISTRY_PRODUCT_PATTERN = Pattern.compile(
            "REGISTRY\\.put\\s*\\(\\s*Constants\\.PRODUCT_CODE\\.([A-Z0-9_]+)\\s*,");
    private static final Pattern PRODUCT_CODE_CONST_PATTERN = Pattern.compile(
            "public\\s+final\\s+static\\s+String\\s+([A-Z0-9_]+)\\s*=\\s*\"([A-Z0-9_]+)\"\\s*;");
    private static final Map<String, List<String>> PRODUCT_CLASS_KEY_ALIASES = buildProductClassKeyAliases();

    private ProductInputFieldAutoGenerator() {
    }

    public static void main(String[] args) throws Exception {
        Path engineRoot = resolveEngineRoot(args);
        Path csvPath = engineRoot.resolve("mr-core/src/main/resources/data/product_input_fields.csv");
        Path validationPath = engineRoot.resolve("mr-core/src/main/java/com/zcyh/mr/loader/validationRules.json");
        Path modelPath = engineRoot.resolve("mr-core/src/main/resources/data/model/productModel.json");
        Path productSrcRoot = engineRoot.resolve("mr-core/src/main/java/com/zcyh/mr/product");
        Path calcPath = engineRoot.resolve("mr-core/src/main/java/com/zcyh/mr/calc/Calc.java");
        Path constantsPath = engineRoot.resolve("mr-core/src/main/java/com/zcyh/mr/core/Constants.java");
        Path outputCsvPath = resolveOutputCsvPath(engineRoot, args);
        Path outputDiffPath = resolveOutputDiffPath(engineRoot, args);

        if (!Files.exists(csvPath)) {
            throw new IllegalStateException("未找到字段主数据文件: " + csvPath);
        }
        if (!Files.exists(validationPath)) {
            throw new IllegalStateException("未找到校验规则文件: " + validationPath);
        }
        if (!Files.exists(modelPath)) {
            throw new IllegalStateException("未找到默认值模型文件: " + modelPath);
        }
        if (!Files.exists(productSrcRoot)) {
            throw new IllegalStateException("未找到产品源码目录: " + productSrcRoot);
        }
        if (!Files.exists(calcPath)) {
            throw new IllegalStateException("未找到Calc注册文件: " + calcPath);
        }
        if (!Files.exists(constantsPath)) {
            throw new IllegalStateException("未找到产品码常量文件: " + constantsPath);
        }

        ExistingCsvSnapshot existing = readExistingCsv(csvPath);
        ValidationSnapshot validation = readValidation(validationPath);
        ModelSnapshot model = readModel(modelPath);
        JavaFieldSnapshot javaFields = scanJavaInfoFields(productSrcRoot);
        Set<String> calcProductCodes = readCalcRegistryProducts(calcPath, constantsPath);

        List<FieldRow> generatedRows = generateRows(calcProductCodes, validation, model, javaFields);
        writeCsv(outputCsvPath, generatedRows);
        writeDiffReport(outputDiffPath, existing, generatedRows);

        System.out.println("[OK] 自动字段主数据已生成:");
        System.out.println("  CSV : " + outputCsvPath);
        System.out.println("  DIFF: " + outputDiffPath);
        System.out.println("  行数: " + generatedRows.size());
    }

    private static Path resolveOutputCsvPath(Path engineRoot, String[] args) {
        if (args != null && args.length > 1 && !safe(args[1]).isEmpty()) {
            return Paths.get(args[1]).toAbsolutePath().normalize();
        }
        return engineRoot.resolve("mr-core/src/main/resources/data/product_input_fields_auto.csv");
    }

    private static Path resolveOutputDiffPath(Path engineRoot, String[] args) {
        if (args != null && args.length > 2 && !safe(args[2]).isEmpty()) {
            return Paths.get(args[2]).toAbsolutePath().normalize();
        }
        return engineRoot.resolve("mr-core/src/main/resources/data/product_input_fields_auto_diff.md");
    }

    private static Path resolveEngineRoot(String[] args) {
        if (args != null && args.length > 0) {
            Path argPath = Paths.get(args[0]).toAbsolutePath().normalize();
            if (Files.exists(argPath.resolve("mr-core"))) {
                return argPath;
            }
            if (Files.exists(argPath.resolve("engine").resolve("mr-core"))) {
                return argPath.resolve("engine");
            }
        }
        Path cwd = Paths.get("").toAbsolutePath().normalize();
        if (Files.exists(cwd.resolve("mr-core"))) {
            return cwd;
        }
        if (Files.exists(cwd.resolve("engine").resolve("mr-core"))) {
            return cwd.resolve("engine");
        }
        throw new IllegalStateException("无法识别 engine 根目录，请在 engine 目录运行，或传入 engine 根路径参数。");
    }

    private static ExistingCsvSnapshot readExistingCsv(Path csvPath) throws IOException {
        ExistingCsvSnapshot snapshot = new ExistingCsvSnapshot();
        try (BufferedReader reader = Files.newBufferedReader(csvPath, StandardCharsets.UTF_8)) {
            String line;
            boolean firstLine = true;
            while ((line = reader.readLine()) != null) {
                if (firstLine) {
                    firstLine = false;
                    continue;
                }
                if (line.trim().isEmpty()) {
                    continue;
                }
                List<String> cols = parseCsvLine(line);
                while (cols.size() < 8) {
                    cols.add("");
                }
                FieldRow row = new FieldRow();
                row.productCode = safe(cols.get(0));
                row.assetClass = safe(cols.get(1));
                row.fieldName = safe(cols.get(2));
                row.javaFieldName = safe(cols.get(3));
                row.fieldType = safe(cols.get(4));
                row.fieldMeaning = safe(cols.get(5));
                row.valueDomain = safe(cols.get(6));
                row.specialRule = safe(cols.get(7));
                if (row.productCode.isEmpty() || row.fieldName.isEmpty()) {
                    continue;
                }
                String key = row.key();
                snapshot.byKey.putIfAbsent(key, row);
                snapshot.existingKeys.add(key);
                snapshot.productCodes.add(row.productCode);
                if (!row.assetClass.isEmpty()) {
                    snapshot.productAssetClass.putIfAbsent(row.productCode, row.assetClass);
                }
            }
        }
        return snapshot;
    }

    private static ValidationSnapshot readValidation(Path validationPath) throws IOException {
        String text = Files.readString(validationPath, StandardCharsets.UTF_8);
        JSONObject root = JSON.parseObject(text);
        ValidationSnapshot snapshot = new ValidationSnapshot();
        if (root == null) {
            return snapshot;
        }

        JSONObject common = root.getJSONObject("_common");
        if (common != null) {
            JSONObject commonTrade = common.getJSONObject("TRADE");
            if (commonTrade != null) {
                snapshot.commonTradeRules.putAll(toStringMap(commonTrade));
            }
        }

        for (String product : root.keySet()) {
            if ("_common".equals(product)) {
                continue;
            }
            JSONObject productNode = root.getJSONObject(product);
            if (productNode == null) {
                continue;
            }
            ProductValidationRule rule = new ProductValidationRule();
            rule.tradeRules.putAll(toStringMap(productNode.getJSONObject("TRADE")));
            rule.underlyingRules.putAll(toStringMap(productNode.getJSONObject("UNDERLYING_DATA")));
            snapshot.productRules.put(product, rule);
            snapshot.productCodes.add(product);
        }
        return snapshot;
    }

    private static ModelSnapshot readModel(Path modelPath) throws IOException {
        String text = Files.readString(modelPath, StandardCharsets.UTF_8);
        JSONObject root = JSON.parseObject(text);
        ModelSnapshot snapshot = new ModelSnapshot();
        if (root == null) {
            return snapshot;
        }
        for (String product : root.keySet()) {
            JSONObject productNode = root.getJSONObject(product);
            if (productNode == null) {
                continue;
            }
            ProductModel model = new ProductModel();
            JSONObject tradeNode = productNode.getJSONObject("TRADE");
            JSONObject underlyingNode = productNode.getJSONObject("UNDERLYING_DATA");
            if (tradeNode != null) {
                for (String field : tradeNode.keySet()) {
                    model.tradeDefaults.put(field, tradeNode.get(field));
                }
            }
            if (underlyingNode != null) {
                for (String field : underlyingNode.keySet()) {
                    model.underlyingDefaults.put(field, underlyingNode.get(field));
                }
            }
            snapshot.productModels.put(product, model);
            snapshot.productCodes.add(product);
        }
        return snapshot;
    }

    private static JavaFieldSnapshot scanJavaInfoFields(Path productSrcRoot) throws IOException {
        JavaFieldSnapshot snapshot = new JavaFieldSnapshot();
        if (!Files.exists(productSrcRoot)) {
            return snapshot;
        }
        List<Path> javaFiles = new ArrayList<>();
        try (var stream = Files.walk(productSrcRoot)) {
            stream.filter(p -> Files.isRegularFile(p) && p.toString().endsWith(".java"))
                    .forEach(javaFiles::add);
        }

        for (Path javaFile : javaFiles) {
            String fileName = javaFile.getFileName().toString();
            String classBaseName = fileName.substring(0, fileName.length() - ".java".length());
            String classKey = normalizeKey(classBaseName);
            Map<String, String> fieldMap = parseInfoFieldsFromJava(javaFile);
            if (!fieldMap.isEmpty()) {
                snapshot.classFields.put(classKey, fieldMap);
            }
            Set<String> parentClassKeys = parseInfoParentClassKeys(javaFile, classKey);
            if (!parentClassKeys.isEmpty()) {
                snapshot.classParents.put(classKey, parentClassKeys);
            }
        }
        return snapshot;
    }

    private static Set<String> readCalcRegistryProducts(Path calcPath, Path constantsPath) throws IOException {
        String calcText = Files.readString(calcPath, StandardCharsets.UTF_8);
        String constantsText = Files.readString(constantsPath, StandardCharsets.UTF_8);

        Map<String, String> constToProductCode = new LinkedHashMap<>();
        Matcher constMatcher = PRODUCT_CODE_CONST_PATTERN.matcher(constantsText);
        while (constMatcher.find()) {
            constToProductCode.put(constMatcher.group(1), constMatcher.group(2));
        }

        Set<String> products = new LinkedHashSet<>();
        Matcher registryMatcher = REGISTRY_PRODUCT_PATTERN.matcher(calcText);
        while (registryMatcher.find()) {
            String constName = safe(registryMatcher.group(1));
            if (constName.isEmpty()) {
                continue;
            }
            String productCode = safe(constToProductCode.get(constName));
            if (!productCode.isEmpty()) {
                products.add(productCode);
            } else {
                products.add(constName);
            }
        }
        return products;
    }

    private static Set<String> parseInfoParentClassKeys(Path javaFile, String currentClassKey) throws IOException {
        List<String> lines = Files.readAllLines(javaFile, StandardCharsets.UTF_8);
        Set<String> parents = new LinkedHashSet<>();
        for (String line : lines) {
            String trimmed = line.trim();
            Matcher matcher = INFO_EXTENDS_PATTERN.matcher(trimmed);
            if (!matcher.find()) {
                continue;
            }
            String infoClassName = safe(matcher.group(1));
            if (!infoClassName.endsWith("Info")) {
                continue;
            }
            String parentKey = normalizeParentClassKey(matcher.group(2), currentClassKey);
            if (!parentKey.isEmpty() && !parentKey.equals(currentClassKey)) {
                parents.add(parentKey);
            }
        }
        return parents;
    }

    private static Map<String, String> parseInfoFieldsFromJava(Path javaFile) throws IOException {
        List<String> lines = Files.readAllLines(javaFile, StandardCharsets.UTF_8);
        Map<String, String> result = new LinkedHashMap<>();

        int braceBalance = 0;
        boolean inInfoClass = false;
        int infoClassDepth = -1;
        String pendingJsonField = null;

        for (String line : lines) {
            String trimmed = line.trim();
            int openCount = countChar(trimmed, '{');
            int closeCount = countChar(trimmed, '}');

            Matcher classMatcher = CLASS_PATTERN.matcher(trimmed);
            if (classMatcher.find()) {
                String className = classMatcher.group(1);
                if (className.endsWith("Info") && trimmed.contains("{")) {
                    inInfoClass = true;
                    infoClassDepth = braceBalance + openCount - closeCount;
                    pendingJsonField = null;
                }
            }

            if (inInfoClass) {
                Matcher jsonFieldMatcher = JSON_FIELD_PATTERN.matcher(trimmed);
                if (jsonFieldMatcher.find()) {
                    pendingJsonField = jsonFieldMatcher.group(1).trim();
                }

                if (pendingJsonField != null && !trimmed.contains("(") && trimmed.contains("public") && trimmed.endsWith(";")) {
                    Matcher fieldMatcher = PUBLIC_FIELD_PATTERN.matcher(trimmed);
                    if (fieldMatcher.find()) {
                        String javaField = fieldMatcher.group(1).trim();
                        result.putIfAbsent(pendingJsonField, javaField);
                        pendingJsonField = null;
                    }
                }
            }

            braceBalance += openCount;
            braceBalance -= closeCount;

            if (inInfoClass && braceBalance < infoClassDepth) {
                inInfoClass = false;
                infoClassDepth = -1;
                pendingJsonField = null;
            }
        }
        return result;
    }

    private static List<FieldRow> generateRows(
            Set<String> calcProductCodes,
            ValidationSnapshot validation,
            ModelSnapshot model,
            JavaFieldSnapshot javaFields) {

        Set<String> allProducts = new TreeSet<>();
        allProducts.addAll(calcProductCodes);

        List<FieldRow> rows = new ArrayList<>();
        Map<String, Map<String, String>> resolvedClassFields = resolveClassFields(
                javaFields.classFields,
                javaFields.classParents);

        for (String product : allProducts) {
            ProductValidationRule validationRule = validation.productRules.getOrDefault(product, new ProductValidationRule());
            ProductModel productModel = model.productModels.getOrDefault(product, new ProductModel());
            Map<String, String> classFieldMap = resolveJavaFieldMap(product, resolvedClassFields);

            Set<String> fields = new TreeSet<>();
            fields.addAll(classFieldMap.keySet());
            if (fields.isEmpty()) {
                continue;
            }

            for (String field : fields) {
                FieldRow row = new FieldRow();
                row.productCode = product;
                row.fieldName = field;
                row.assetClass = resolveAssetClass(product);
                row.javaFieldName = resolveJavaFieldName(field, classFieldMap);
                row.fieldType = resolveFieldType(field, validationRule, validation.commonTradeRules, productModel);
                row.fieldMeaning = "";
                row.valueDomain = resolveValueDomain(field, validationRule, validation.commonTradeRules);
                String fieldScope = resolveFieldScope(field, validationRule, validation.commonTradeRules, productModel);
                row.specialRule = resolveSpecialRule(field, validationRule, validation.commonTradeRules, productModel, fieldScope);
                rows.add(row);
            }
        }

        rows.sort(Comparator.comparing((FieldRow r) -> r.productCode).thenComparing(r -> r.fieldName));
        return rows;
    }

    private static Map<String, Map<String, String>> resolveClassFields(
            Map<String, Map<String, String>> classFields,
            Map<String, Set<String>> classParents) {
        Map<String, Map<String, String>> cache = new LinkedHashMap<>();
        for (String classKey : classFields.keySet()) {
            resolveClassFieldsRec(classKey, classFields, classParents, cache, new LinkedHashSet<>());
        }
        return cache;
    }

    private static Map<String, String> resolveClassFieldsRec(
            String classKey,
            Map<String, Map<String, String>> classFields,
            Map<String, Set<String>> classParents,
            Map<String, Map<String, String>> cache,
            Set<String> visiting) {
        if (cache.containsKey(classKey)) {
            return cache.get(classKey);
        }
        if (visiting.contains(classKey)) {
            return classFields.getOrDefault(classKey, Collections.emptyMap());
        }
        visiting.add(classKey);

        Map<String, String> merged = new LinkedHashMap<>();
        for (String parentKey : classParents.getOrDefault(classKey, Collections.emptySet())) {
            Map<String, String> parentMap = resolveClassFieldsRec(parentKey, classFields, classParents, cache, visiting);
            for (Map.Entry<String, String> entry : parentMap.entrySet()) {
                merged.putIfAbsent(entry.getKey(), entry.getValue());
            }
        }
        merged.putAll(classFields.getOrDefault(classKey, Collections.emptyMap()));

        visiting.remove(classKey);
        cache.put(classKey, merged);
        return merged;
    }

    private static Map<String, String> resolveJavaFieldMap(String productCode, Map<String, Map<String, String>> classFields) {
        String normalizedProduct = normalizeKey(productCode);
        if ("AUTOCALL".equals(normalizedProduct)) {
            return buildGenericMcFieldMap();
        }
        if (classFields.containsKey(normalizedProduct)) {
            return classFields.get(normalizedProduct);
        }

        List<String> aliases = PRODUCT_CLASS_KEY_ALIASES.get(normalizedProduct);
        if (aliases != null) {
            for (String alias : aliases) {
                if (classFields.containsKey(alias)) {
                    return classFields.get(alias);
                }
            }
        }

        String bestClassKey = null;
        int bestScore = Integer.MAX_VALUE;
        for (String classKey : classFields.keySet()) {
            if (classKey.contains(normalizedProduct) || normalizedProduct.contains(classKey)) {
                int score = Math.abs(classKey.length() - normalizedProduct.length());
                if (score < bestScore) {
                    bestScore = score;
                    bestClassKey = classKey;
                }
            }
        }
        if (bestClassKey != null) {
            return classFields.get(bestClassKey);
        }
        return Collections.emptyMap();
    }

    private static Map<String, String> buildGenericMcFieldMap() {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("INSTRUMENT_ID", "instrumentId");
        fields.put("PRODUCT_CODE", "productCode");
        fields.put("UNDERLYING_TYPE", "underlyingType");
        fields.put("PAYOFF_TYPE", "payoffType");
        fields.put("MODEL_TYPE", "modelType");
        fields.put("BUY_OR_SELL", "buyOrSell");
        fields.put("CURRENCY_CODE", "currencyCode");
        fields.put("DISCOUNT_CURVE", "discountCurve");
        fields.put("VOLATILITY_SURFACE", "volatilitySurface");
        fields.put("REFERENCE_CURVE", "referenceCurve");
        fields.put("BASE_CURRENCY_CODE", "baseCurrencyCode");
        fields.put("UNDERLYING_CURRENCY_CODE", "underlyingCurrencyCode");
        fields.put("BASE_DISCOUNT_CURVE", "baseDiscountCurve");
        fields.put("UNDERLYING_DISCOUNT_CURVE", "underlyingDiscountCurve");
        fields.put("FRTB_EQ_BUCKET", "frtbEqBucket");
        fields.put("FRTB_COMM_BUCKET", "frtbCommBucket");
        fields.put("FRTB_COMM_ASSET", "frtbCommAsset");
        fields.put("FRTB_COMM_LOCATION", "frtbCommLocation");
        fields.put("UNDERLYING_CODE", "underlyingCode");
        fields.put("OBS_DATES", "obsDates");
        fields.put("START_DATE", "startDate");
        fields.put("SETTLE_DATE", "settleDate");
        fields.put("MATURITY_DATE", "maturityDate");
        fields.put("PATH_NB", "pathNb");
        fields.put("PATH_FLAG", "pathFlag");
        fields.put("NOTIONAL", "notional");
        fields.put("BARRIER", "barrier");
        fields.put("BARRIER_DIRECTION", "barrierDirection");
        fields.put("PAYOFF_RATE", "payoffRate");
        fields.put("PREMIUM_RATE", "premiumRate");
        fields.put("MODEL_PARAMS", "modelParams");
        fields.put("PAYOFF_PARAMS", "payoffParams");
        return fields;
    }

    private static String resolveAssetClass(String productCode) {
        String code = safe(productCode).toUpperCase(Locale.ROOT);
        if ("AUTO_CALL".equals(code)) {
            return "综合";
        }
        if (code.startsWith("FX")) {
            return "外汇";
        }
        if (code.startsWith("EQ")) {
            return "权益";
        }
        if (code.startsWith("COMM")) {
            return "商品";
        }
        if (code.startsWith("CDS") || code.startsWith("TRS")) {
            return "信用";
        }
        return "利率";
    }

    private static String resolveJavaFieldName(String field, Map<String, String> classFieldMap) {
        String javaField = classFieldMap.get(field);
        if (!safe(javaField).isEmpty()) {
            return javaField;
        }
        return toCamelCase(field);
    }

    private static String resolveFieldType(
            String field,
            ProductValidationRule validationRule,
            Map<String, String> commonRules,
            ProductModel productModel) {
        String rule = firstNonBlank(validationRule.tradeRules.get(field), validationRule.underlyingRules.get(field));
        if (rule == null) {
            rule = commonRules.get(field);
        }
        if (rule != null) {
            if ("date".equalsIgnoreCase(rule)) {
                return "日期";
            }
            if ("number".equalsIgnoreCase(rule)) {
                return "浮点数";
            }
            if ("string".equalsIgnoreCase(rule) || rule.startsWith("domain:")) {
                return "字符串";
                }
        }

        String inferredType = inferFieldType(field);
        if (!inferredType.isEmpty()) {
            return inferredType;
        }

        Object defaultValue = firstNonNull(productModel.tradeDefaults.get(field), productModel.underlyingDefaults.get(field));
        if (defaultValue != null) {
            if (defaultValue instanceof Boolean) {
                return "布尔";
            }
            if (defaultValue instanceof Number) {
                return "浮点数";
            }
            if (defaultValue instanceof JSONObject || defaultValue instanceof JSONArray) {
                return "JSON";
            }
            return "字符串";
        }
        return "字符串";
    }

    private static String resolveValueDomain(
            String field,
            ProductValidationRule validationRule,
            Map<String, String> commonRules) {
        String rule = firstNonBlank(validationRule.tradeRules.get(field), validationRule.underlyingRules.get(field));
        if (rule == null) {
            rule = commonRules.get(field);
        }
        if (rule != null && rule.startsWith("domain:")) {
            return rule.substring("domain:".length());
        }
        String inferredDomain = inferValueDomain(field);
        if (!inferredDomain.isEmpty()) {
            return inferredDomain;
        }
        return "";
    }

    private static String inferFieldType(String field) {
        String key = safe(field).toUpperCase(Locale.ROOT);
        if (isTextField(key)) {
            return "字符串";
        }
        if (key.endsWith("_DATE") || "START_DATE".equals(key) || "SETTLE_DATE".equals(key)
                || "MATURITY_DATE".equals(key)) {
            return "日期";
        }
        if ("PATH_NB".equals(key)) {
            return "整数";
        }
        if ("PATH_FLAG".equals(key)) {
            return "布尔";
        }
        if ("MODEL_PARAMS".equals(key) || "PAYOFF_PARAMS".equals(key)) {
            return "JSON";
        }
        if (key.contains("RATE") || key.contains("BARRIER") || key.contains("NOTIONAL")
                || key.contains("STRIKE") || key.contains("PAYOFF")) {
            return "浮点数";
        }
        return "";
    }

    private static boolean isTextField(String key) {
        return Set.of(
                "INSTRUMENT_ID",
                "PRODUCT_CODE",
                "RISK_CLASS",
                "UNDERLYING_TYPE",
                "PAYOFF_TYPE",
                "MODEL_TYPE",
                "BUY_OR_SELL",
                "CURRENCY_CODE",
                "VALUATION_CCY",
                "DISCOUNT_CURVE",
                "VOLATILITY_SURFACE",
                "REFERENCE_CURVE",
                "BASE_CURRENCY_CODE",
                "UNDERLYING_CURRENCY_CODE",
                "BASE_DISCOUNT_CURVE",
                "UNDERLYING_DISCOUNT_CURVE",
                "FRTB_EQ_BUCKET",
                "FRTB_COMM_BUCKET",
                "FRTB_COMM_ASSET",
                "FRTB_COMM_LOCATION",
                "UNDERLYING_CODE",
                "GIRR_SECONDARY_VERTEX",
                "OBS_DATES",
                "BARRIER_DIRECTION").contains(key);
    }

    private static String inferValueDomain(String field) {
        String key = safe(field).toUpperCase(Locale.ROOT);
        if ("BUY_OR_SELL".equals(key)) {
            return "B|S";
        }
        if ("UNDERLYING_TYPE".equals(key) || "RISK_CLASS".equals(key)) {
            return "FX|EQ|COMM|IR";
        }
        if ("PAYOFF_TYPE".equals(key)) {
            return "AUTO_CALL";
        }
        if ("MODEL_TYPE".equals(key)) {
            return "CONST_VOL|LOCAL_VOL|HESTON";
        }
        if ("BARRIER_DIRECTION".equals(key)) {
            return "UP|DOWN";
        }
        return "";
    }

    private static String resolveFieldScope(
            String field,
            ProductValidationRule validationRule,
            Map<String, String> commonRules,
            ProductModel productModel) {
        boolean inTrade = commonRules.containsKey(field)
                || validationRule.tradeRules.containsKey(field)
                || productModel.tradeDefaults.containsKey(field);
        boolean inUnderlying = validationRule.underlyingRules.containsKey(field)
                || productModel.underlyingDefaults.containsKey(field);
        if (inTrade && inUnderlying) {
            return "TRADE/UNDERLYING_DATA";
        }
        if (inUnderlying) {
            return "UNDERLYING_DATA";
        }
        if (inTrade) {
            return "TRADE";
        }
        return "";
    }

    private static String resolveSpecialRule(
            String field,
            ProductValidationRule validationRule,
            Map<String, String> commonRules,
            ProductModel productModel,
            String fieldScope) {
        LinkedHashSet<String> rules = new LinkedHashSet<>();

        if (!safe(fieldScope).isEmpty()) {
            rules.add("数据域=" + fieldScope);
        }

        if (validationRule.tradeRules.containsKey(field) || validationRule.underlyingRules.containsKey(field)) {
            rules.add("必填(校验规则)");
        } else if (commonRules.containsKey(field)) {
            rules.add("通用校验字段(存在时校验)");
        }

        Object defaultValue = firstNonNull(productModel.tradeDefaults.get(field), productModel.underlyingDefaults.get(field));
        if (defaultValue != null && !(defaultValue instanceof JSONObject) && !(defaultValue instanceof JSONArray)) {
            String defaultText = String.valueOf(defaultValue);
            if (!defaultText.isBlank()) {
                rules.add("默认值=" + defaultText);
            }
        }

        return String.join("；", rules);
    }

    private static void writeCsv(Path outputCsvPath, List<FieldRow> rows) throws IOException {
        Files.createDirectories(outputCsvPath.getParent());
        try (BufferedWriter writer = Files.newBufferedWriter(outputCsvPath, StandardCharsets.UTF_8)) {
            writer.write('\uFEFF');
            writer.write(CSV_HEADER);
            writer.newLine();
            for (FieldRow row : rows) {
                writer.write(toCsvLine(List.of(
                        safe(row.productCode),
                        safe(row.assetClass),
                        safe(row.fieldName),
                        safe(row.javaFieldName),
                        safe(row.fieldType),
                        safe(row.fieldMeaning),
                        safe(row.valueDomain),
                        safe(row.specialRule))));
                writer.newLine();
            }
        }
    }

    private static void writeDiffReport(Path outputDiffPath, ExistingCsvSnapshot existing, List<FieldRow> generatedRows)
            throws IOException {
        Files.createDirectories(outputDiffPath.getParent());
        Map<String, FieldRow> generatedByKey = new LinkedHashMap<>();
        Set<String> generatedKeys = new HashSet<>();
        for (FieldRow row : generatedRows) {
            String key = row.key();
            generatedByKey.put(key, row);
            generatedKeys.add(key);
        }

        List<String> onlyInGenerated = new ArrayList<>();
        for (String key : generatedKeys) {
            if (!existing.existingKeys.contains(key)) {
                onlyInGenerated.add(key);
            }
        }
        Collections.sort(onlyInGenerated);

        List<String> onlyInExisting = new ArrayList<>();
        for (String key : existing.existingKeys) {
            if (!generatedKeys.contains(key)) {
                onlyInExisting.add(key);
            }
        }
        Collections.sort(onlyInExisting);

        List<String> changed = new ArrayList<>();
        for (String key : generatedKeys) {
            FieldRow oldRow = existing.byKey.get(key);
            FieldRow newRow = generatedByKey.get(key);
            if (oldRow == null || newRow == null) {
                continue;
            }
            if (!rowEquals(oldRow, newRow)) {
                changed.add(key);
            }
        }
        Collections.sort(changed);

        try (BufferedWriter writer = Files.newBufferedWriter(outputDiffPath, StandardCharsets.UTF_8)) {
            writer.write("# 自动字段主数据差异报告");
            writer.newLine();
            writer.newLine();
            writer.write("- 生成时间: " + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
            writer.newLine();
            writer.write("- 现有字段数: " + existing.existingKeys.size());
            writer.newLine();
            writer.write("- 自动生成字段数: " + generatedKeys.size());
            writer.newLine();
            writer.write("- 新增字段数(自动有、现有无): " + onlyInGenerated.size());
            writer.newLine();
            writer.write("- 遗留字段数(现有有、自动无): " + onlyInExisting.size());
            writer.newLine();
            writer.write("- 属性变化字段数: " + changed.size());
            writer.newLine();
            writer.newLine();

            writer.write("## 新增字段（自动有、现有无）");
            writer.newLine();
            writer.write("| 产品类型 | 字段 |");
            writer.newLine();
            writer.write("|---|---|");
            writer.newLine();
            for (String key : onlyInGenerated) {
                FieldRow row = generatedByKey.get(key);
                writer.write("| " + row.productCode + " | " + row.fieldName + " |");
                writer.newLine();
            }
            writer.newLine();

            writer.write("## 遗留字段（现有有、自动无）");
            writer.newLine();
            writer.write("| 产品类型 | 字段 |");
            writer.newLine();
            writer.write("|---|---|");
            writer.newLine();
            for (String key : onlyInExisting) {
                FieldRow row = existing.byKey.get(key);
                writer.write("| " + row.productCode + " | " + row.fieldName + " |");
                writer.newLine();
            }
            writer.newLine();

            writer.write("## 属性变化字段");
            writer.newLine();
            writer.write("| 产品类型 | 字段 | 变化项 |");
            writer.newLine();
            writer.write("|---|---|---|");
            writer.newLine();
            for (String key : changed) {
                FieldRow oldRow = existing.byKey.get(key);
                FieldRow newRow = generatedByKey.get(key);
                String diff = buildDiffText(oldRow, newRow);
                writer.write("| " + newRow.productCode + " | " + newRow.fieldName + " | " + escapeMarkdown(diff) + " |");
                writer.newLine();
            }
        }
    }

    private static String buildDiffText(FieldRow oldRow, FieldRow newRow) {
        List<String> changes = new ArrayList<>();
        appendChange(changes, "资产大类", oldRow.assetClass, newRow.assetClass);
        appendChange(changes, "Java字段名", oldRow.javaFieldName, newRow.javaFieldName);
        appendChange(changes, "字段类型", oldRow.fieldType, newRow.fieldType);
        appendChange(changes, "字段含义", oldRow.fieldMeaning, newRow.fieldMeaning);
        appendChange(changes, "值域", oldRow.valueDomain, newRow.valueDomain);
        appendChange(changes, "特殊规则", oldRow.specialRule, newRow.specialRule);
        return String.join("；", changes);
    }

    private static void appendChange(List<String> changes, String name, String oldVal, String newVal) {
        String a = safe(oldVal);
        String b = safe(newVal);
        if (!Objects.equals(a, b)) {
            changes.add(name + ": [" + a + "] -> [" + b + "]");
        }
    }

    private static boolean rowEquals(FieldRow a, FieldRow b) {
        return Objects.equals(safe(a.assetClass), safe(b.assetClass))
                && Objects.equals(safe(a.javaFieldName), safe(b.javaFieldName))
                && Objects.equals(safe(a.fieldType), safe(b.fieldType))
                && Objects.equals(safe(a.fieldMeaning), safe(b.fieldMeaning))
                && Objects.equals(safe(a.valueDomain), safe(b.valueDomain))
                && Objects.equals(safe(a.specialRule), safe(b.specialRule));
    }

    private static Map<String, String> toStringMap(JSONObject jsonObject) {
        if (jsonObject == null) {
            return Collections.emptyMap();
        }
        Map<String, String> map = new LinkedHashMap<>();
        for (String key : jsonObject.keySet()) {
            Object v = jsonObject.get(key);
            map.put(key, v == null ? "" : String.valueOf(v));
        }
        return map;
    }

    private static Object firstNonNull(Object... arr) {
        if (arr == null) {
            return null;
        }
        for (Object o : arr) {
            if (o != null) {
                return o;
            }
        }
        return null;
    }

    private static String firstNonBlank(String... arr) {
        if (arr == null) {
            return null;
        }
        for (String s : arr) {
            if (s != null && !s.trim().isEmpty()) {
                return s;
            }
        }
        return null;
    }

    private static String normalizeKey(String text) {
        return safe(text).replaceAll("[^A-Za-z0-9]", "").toUpperCase(Locale.ROOT);
    }

    private static String normalizeParentClassKey(String extendsType, String currentClassKey) {
        String parentType = safe(extendsType);
        int genericStart = parentType.indexOf('<');
        if (genericStart >= 0) {
            parentType = parentType.substring(0, genericStart);
        }
        if (parentType.isEmpty()) {
            return "";
        }
        if (parentType.contains(".")) {
            String outerClass = parentType.split("\\.")[0];
            return normalizeKey(outerClass);
        }
        if (parentType.endsWith("Info")) {
            return currentClassKey;
        }
        return "";
    }

    private static Map<String, List<String>> buildProductClassKeyAliases() {
        Map<String, List<String>> aliases = new HashMap<>();
        aliases.put("COMMOPT", List.of("COMMVANILLAOPT"));
        aliases.put("COMMBARRIER", List.of("COMMBAROPT"));
        aliases.put("COMMDIGITAL", List.of("COMMDIGOPT"));
        aliases.put("FXOPT", List.of("FXVANILLAOPT"));
        aliases.put("FXBARRIER", List.of("FXBAROPT"));
        aliases.put("FXDIGITAL", List.of("FXDIGOPT"));
        aliases.put("EQBARRIER", List.of("EQBAROPT"));
        aliases.put("EQDIGITAL", List.of("EQDIGOPT"));
        aliases.put("EQRANGEACCURE", List.of(
                "RANGEACCUREOPTBASE",
                "COMMRANGEACCUREOPT",
                "FXRANGEACCUREOPT",
                "IRRANGEACCUREOPT"));
        aliases.put("IRDIGITAL", List.of("IRDIGOPT"));
        aliases.put("IRBARRIER", List.of("IRBAROPT"));
        return aliases;
    }

    private static int countChar(String text, char c) {
        int count = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == c) {
                count++;
            }
        }
        return count;
    }

    private static String toCamelCase(String fieldName) {
        String input = safe(fieldName).toLowerCase(Locale.ROOT);
        if (input.isEmpty()) {
            return "";
        }
        String[] parts = input.split("_");
        if (parts.length == 1) {
            return parts[0];
        }
        StringBuilder sb = new StringBuilder(parts[0]);
        for (int i = 1; i < parts.length; i++) {
            String p = parts[i];
            if (p.isEmpty()) {
                continue;
            }
            sb.append(Character.toUpperCase(p.charAt(0)));
            if (p.length() > 1) {
                sb.append(p.substring(1));
            }
        }
        return sb.toString();
    }

    private static String safe(String text) {
        return text == null ? "" : text.trim();
    }

    private static List<String> parseCsvLine(String line) {
        List<String> cols = new ArrayList<>();
        if (line == null) {
            return cols;
        }
        StringBuilder sb = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (ch == '"') {
                if (inQuotes && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    sb.append('"');
                    i++;
                } else {
                    inQuotes = !inQuotes;
                }
            } else if (ch == ',' && !inQuotes) {
                cols.add(sb.toString());
                sb.setLength(0);
            } else {
                sb.append(ch);
            }
        }
        cols.add(sb.toString());
        return cols;
    }

    private static String toCsvLine(List<String> cols) {
        List<String> escaped = new ArrayList<>(cols.size());
        for (String col : cols) {
            String value = col == null ? "" : col;
            boolean needQuote = value.contains(",") || value.contains("\"") || value.contains("\n") || value.contains("\r");
            if (value.contains("\"")) {
                value = value.replace("\"", "\"\"");
            }
            escaped.add(needQuote ? "\"" + value + "\"" : value);
        }
        return String.join(",", escaped);
    }

    private static String escapeMarkdown(String text) {
        return safe(text).replace("|", "\\|").replace("\n", "<br/>");
    }

    private static final class ProductValidationRule {
        private final Map<String, String> tradeRules = new LinkedHashMap<>();
        private final Map<String, String> underlyingRules = new LinkedHashMap<>();
    }

    private static final class ProductModel {
        private final Map<String, Object> tradeDefaults = new LinkedHashMap<>();
        private final Map<String, Object> underlyingDefaults = new LinkedHashMap<>();
    }

    private static final class ValidationSnapshot {
        private final Set<String> productCodes = new LinkedHashSet<>();
        private final Map<String, String> commonTradeRules = new LinkedHashMap<>();
        private final Map<String, ProductValidationRule> productRules = new LinkedHashMap<>();
    }

    private static final class ModelSnapshot {
        private final Set<String> productCodes = new LinkedHashSet<>();
        private final Map<String, ProductModel> productModels = new LinkedHashMap<>();
    }

    private static final class JavaFieldSnapshot {
        private final Map<String, Map<String, String>> classFields = new LinkedHashMap<>();
        private final Map<String, Set<String>> classParents = new LinkedHashMap<>();
    }

    private static final class ExistingCsvSnapshot {
        private final Set<String> productCodes = new LinkedHashSet<>();
        private final Set<String> existingKeys = new LinkedHashSet<>();
        private final Map<String, String> productAssetClass = new LinkedHashMap<>();
        private final Map<String, FieldRow> byKey = new LinkedHashMap<>();
    }

    private static final class FieldRow {
        private String productCode;
        private String assetClass;
        private String fieldName;
        private String javaFieldName;
        private String fieldType;
        private String fieldMeaning;
        private String valueDomain;
        private String specialRule;

        private String key() {
            return productCode + "#" + fieldName;
        }
    }
}
