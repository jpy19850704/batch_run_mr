package com.zcyh.mr.marketdata.input;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface MarketInputField {
    String name();

    String label();

    MarketFieldType type();

    int order();

    boolean required() default false;

    String[] allowedValues() default {};
}
