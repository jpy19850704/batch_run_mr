package com.zcyh.mr.marketdata.input;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class MarketInputDefinitionRegistry {
    private static final Map<MarketDataType, MarketInputDefinition> DEFINITIONS = buildDefinitions();

    private MarketInputDefinitionRegistry() {
    }

    public static MarketInputDefinition get(MarketDataType marketDataType) {
        MarketInputDefinition definition = DEFINITIONS.get(marketDataType);
        if (definition == null) {
            throw new IllegalArgumentException("未定义市场数据类型: " + marketDataType);
        }
        return definition;
    }

    public static MarketInputDefinition get(String marketDataType) {
        return get(MarketDataType.parse(marketDataType));
    }

    public static Map<MarketDataType, MarketInputDefinition> all() {
        return DEFINITIONS;
    }

    private static Map<MarketDataType, MarketInputDefinition> buildDefinitions() {
        Map<MarketDataType, MarketInputDefinition> result =
                new EnumMap<MarketDataType, MarketInputDefinition>(MarketDataType.class);
        register(result, MarketDataType.IR_SPOT,
                MarketDataInputs.IrSpotInput.class, MarketDataInputs.IrSpotPointInput.class);
        register(result, MarketDataType.CREDIT_SPOT,
                MarketDataInputs.CreditSpotInput.class, MarketDataInputs.CreditSpotPointInput.class);
        register(result, MarketDataType.FX_SPOT,
                MarketDataInputs.FxSpotInput.class, MarketDataInputs.FxSpotPointInput.class);
        register(result, MarketDataType.EQ_SPOT,
                MarketDataInputs.EqSpotInput.class, MarketDataInputs.EqSpotPointInput.class);
        register(result, MarketDataType.COMM_SPOT,
                MarketDataInputs.CommSpotInput.class, MarketDataInputs.CommSpotPointInput.class);
        register(result, MarketDataType.FIXING,
                MarketDataInputs.FixingInput.class, MarketDataInputs.FixingPointInput.class);
        register(result, MarketDataType.IR_VOL,
                MarketDataInputs.IrVolInput.class, MarketDataInputs.IrVolPointInput.class);
        register(result, MarketDataType.FX_VOL,
                MarketDataInputs.FxVolInput.class, MarketDataInputs.FxVolPointInput.class);
        register(result, MarketDataType.EQ_VOL,
                MarketDataInputs.EqVolInput.class, MarketDataInputs.EqVolPointInput.class);
        register(result, MarketDataType.COMM_VOL,
                MarketDataInputs.CommVolInput.class, MarketDataInputs.CommVolPointInput.class);
        return Collections.unmodifiableMap(result);
    }

    private static void register(
            Map<MarketDataType, MarketInputDefinition> target,
            MarketDataType marketDataType,
            Class<?> inputType,
        Class<?> pointType) {
        List<MarketFieldDefinition> fields = describe(inputType);
        fields = bindDomains(fields, marketDataType);
        target.put(marketDataType, new MarketInputDefinition(
                marketDataType, inputType, pointType, fields, describe(pointType)));
    }

    private static List<MarketFieldDefinition> describe(Class<?> type) {
        Map<String, MarketFieldDefinition> definitions = new LinkedHashMap<String, MarketFieldDefinition>();
        List<Class<?>> hierarchy = new ArrayList<Class<?>>();
        for (Class<?> current = type; current != null && current != Object.class; current = current.getSuperclass()) {
            hierarchy.add(current);
        }
        Collections.reverse(hierarchy);
        for (Class<?> current : hierarchy) {
            for (Field field : current.getDeclaredFields()) {
                MarketInputField annotation = field.getAnnotation(MarketInputField.class);
                if (annotation == null) {
                    continue;
                }
                definitions.put(annotation.name(), new MarketFieldDefinition(
                        annotation.name(),
                        annotation.label(),
                        annotation.type(),
                        annotation.required(),
                        annotation.order(),
                        new ArrayList<String>(Arrays.asList(annotation.allowedValues()))));
            }
        }
        List<MarketFieldDefinition> result = new ArrayList<MarketFieldDefinition>(definitions.values());
        result.sort(Comparator.comparingInt(MarketFieldDefinition::getOrder));
        return result;
    }

    private static List<MarketFieldDefinition> bindDomains(
            List<MarketFieldDefinition> fields,
            MarketDataType marketDataType) {
        List<MarketFieldDefinition> result = new ArrayList<MarketFieldDefinition>(fields.size());
        for (MarketFieldDefinition field : fields) {
            List<String> allowedValues;
            if ("CURVE_TYPE".equals(field.getName())) {
                allowedValues = Collections.singletonList(marketDataType.name());
            } else if ("AXIS2_TYPE".equals(field.getName())) {
                allowedValues = Collections.singletonList(
                        marketDataType == MarketDataType.IR_VOL ? "UNDERLYING_TERM" : "DELTA");
            } else {
                result.add(field);
                continue;
            }
            result.add(new MarketFieldDefinition(
                    field.getName(),
                    field.getLabel(),
                    field.getType(),
                    field.isRequired(),
                    field.getOrder(),
                    allowedValues));
        }
        return result;
    }
}
