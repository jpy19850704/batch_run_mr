package com.zcyh.mr.calc;

import com.zcyh.mr.core.Constants;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProductCalculatorRegistryTest {

    @Test
    void exposesAllRegisteredProductCodes() {
        Set<String> productCodes = ProductCalculatorRegistry.productCodes();

        assertEquals(47, productCodes.size());
        assertTrue(productCodes.contains(Constants.PRODUCT_CODE.COMMFWD));
        assertTrue(productCodes.contains(Constants.PRODUCT_CODE.COMPOSITE));
        assertTrue(productCodes.contains(Constants.PRODUCT_CODE.STD_IRS));
    }

    @Test
    void productCodesCannotBeModified() {
        Set<String> productCodes = ProductCalculatorRegistry.productCodes();

        assertThrows(UnsupportedOperationException.class, () -> productCodes.add("UNKNOWN"));
    }

    @Test
    void rejectsUnsupportedProductCode() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> ProductCalculatorRegistry.create("UNKNOWN", null, null, null, null, null, null));

        assertEquals("不支持的产品类型: UNKNOWN", error.getMessage());
    }
}
