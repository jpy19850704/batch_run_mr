package com.zcyh.mr.product.basic.validation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface ProductInputField {
    boolean required() default false;

    String[] requiredFor() default {};

    String[] allowedValues() default {};

    boolean ignoreCase() default true;

    boolean finite() default false;

    int length() default -1;

    String min() default "";

    boolean minInclusive() default true;

    String max() default "";

    boolean maxInclusive() default true;
}
