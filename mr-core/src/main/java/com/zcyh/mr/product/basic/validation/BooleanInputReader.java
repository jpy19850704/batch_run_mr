package com.zcyh.mr.product.basic.validation;

import com.alibaba.fastjson2.JSONReader;
import com.alibaba.fastjson2.reader.ObjectReader;

import java.lang.reflect.Type;
import java.math.BigDecimal;

/**
 * 将外部布尔输入转换为引擎内部 Boolean 标准值。
 */
public final class BooleanInputReader implements ObjectReader<Boolean> {

    @Override
    public Boolean readObject(JSONReader jsonReader, Type fieldType, Object fieldName, long features) {
        return parse(jsonReader.readAny(), String.valueOf(fieldName));
    }

    public static Boolean parse(Object value, String fieldName) {
        if (value == null) {
            return null;
        }
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        if (value instanceof Number) {
            BigDecimal number = new BigDecimal(value.toString());
            if (BigDecimal.ONE.compareTo(number) == 0) {
                return true;
            }
            if (BigDecimal.ZERO.compareTo(number) == 0) {
                return false;
            }
            throw invalidValue(fieldName, value);
        }
        if (value instanceof String) {
            String text = ((String) value).trim();
            if ("TRUE".equalsIgnoreCase(text) || "Y".equalsIgnoreCase(text) || "1".equals(text)) {
                return true;
            }
            if ("FALSE".equalsIgnoreCase(text) || "N".equalsIgnoreCase(text) || "0".equals(text)) {
                return false;
            }
        }
        throw invalidValue(fieldName, value);
    }

    private static IllegalArgumentException invalidValue(String fieldName, Object value) {
        return new IllegalArgumentException(fieldName + " 仅支持 true/false、Y/N、1/0: " + value);
    }
}
