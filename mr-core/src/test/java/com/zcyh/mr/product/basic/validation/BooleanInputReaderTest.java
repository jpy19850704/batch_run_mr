package com.zcyh.mr.product.basic.validation;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.annotation.JSONField;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class BooleanInputReaderTest {

    @Test
    void acceptsSupportedExternalValues() {
        Assertions.assertTrue(BooleanInputReader.parse(true, "FLAG"));
        Assertions.assertTrue(BooleanInputReader.parse(" y ", "FLAG"));
        Assertions.assertTrue(BooleanInputReader.parse("TRUE", "FLAG"));
        Assertions.assertTrue(BooleanInputReader.parse(1, "FLAG"));
        Assertions.assertFalse(BooleanInputReader.parse(false, "FLAG"));
        Assertions.assertFalse(BooleanInputReader.parse(" n ", "FLAG"));
        Assertions.assertFalse(BooleanInputReader.parse("FALSE", "FLAG"));
        Assertions.assertFalse(BooleanInputReader.parse("0", "FLAG"));
    }

    @Test
    void rejectsUnsupportedExternalValues() {
        IllegalArgumentException exception = Assertions.assertThrows(IllegalArgumentException.class,
                () -> BooleanInputReader.parse("YES", "FLAG"));

        Assertions.assertTrue(exception.getMessage().contains("FLAG"));
    }

    @Test
    void fastjsonReaderProducesCanonicalBoolean() {
        BooleanHolder holder = JSON.parseObject("{\"FLAG\":\"Y\"}", BooleanHolder.class);

        Assertions.assertEquals(Boolean.TRUE, holder.flag);
        Assertions.assertEquals("{\"FLAG\":true}", JSON.toJSONString(holder));
    }

    static class BooleanHolder {
        @JSONField(name = "FLAG", deserializeUsing = BooleanInputReader.class)
        public Boolean flag;
    }
}
