package com.zcyh.mr.basic.util;

import org.junit.jupiter.api.Test;


class ConfigureTest {

    @Test
    public void configureTest() {
        Configure config = Configure.getInstance();
        String currency = config.getValue("FX.BASE_CURRENCY_CODE");
        System.out.println("currency: " + currency);
    }
}