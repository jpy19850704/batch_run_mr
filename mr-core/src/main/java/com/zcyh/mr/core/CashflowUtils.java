package com.zcyh.mr.core;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;
import java.time.Period;

/**
 * 现金流公共函数类
 * 
 * @author lsd
 * @version 1.0
 * @date 2024/7/15 10:25
 */

public class CashflowUtils {
    private static final Logger log = LoggerFactory.getLogger(CashflowUtils.class);
    public static final String FREQ_ZC = "ZC";

    public static Period convertFreq(String freq) {
        if (freq == null || freq.trim().isEmpty()) {
            log.warn("现金流频率为空，按现有逻辑生成起息日到到期日的一次性现金流");
            return null;
        }
        freq = freq.trim();
        Period rst = null;
        if (FREQ_ZC.equals(freq)) {
            rst = Period.ZERO;
        } else {
            if (freq.length() < 2) {
                throw new IllegalArgumentException("频率格式不合法: " + freq);
            }
            Integer term = Integer.valueOf(freq.substring(0, freq.length() - 1));
            String unit = freq.substring(freq.length() - 1);

            if ("D".equals(unit)) {
                rst = Period.ofDays(term);
            } else if ("W".equals(unit)) {
                rst = Period.ofWeeks(term);
            } else if ("M".equals(unit)) {
                rst = Period.ofMonths(term);
            } else if ("Y".equals(unit)) {
                rst = Period.ofYears(term);
            }
        }
        return rst;
    }

    public static String att2DateGenerationRule(String value) {
        String dateRule = null;
        if ("ShortStart".equalsIgnoreCase(value) || "LongStart".equalsIgnoreCase(value)) {
            dateRule = Constants.DateGeneration.BACKWARD;
        } else {
            dateRule = Constants.DateGeneration.FOWARD;
        }
        return dateRule;
    }

    public static String attr2BusinessDayConvention(String value) {
        if (StringUtils.isBlank(value))
            return "";

        String bdc = null;
        switch (value) {
            case "Regular_Preceding":
                bdc = "P";
                break;
            case "Modified_Preceding":
                bdc = "MP";
                break;
            case "Regular_Following":
                bdc = "F";
                break;
            case "Modified_Following":
                bdc = "MF";
                break;
            default:
                bdc = "F";
        }
        return bdc;
    }

    public static double getInt(boolean couponProrated, double r, Integer mcf, String frq, String dcb, LocalDate t1,
            LocalDate t2) {
        int m = 0;
        if (couponProrated) {
            return -1 + 1 / CurveFunc.discountFactor(t1, t2, r, frq, dcb);
        }
        switch (frq.toLowerCase()) {
            case "annu":
                m = 1;
                break;
            case "semi":
                m = 2;
                break;
            case "quart":
                m = 4;
                break;
            case "mon":
                m = 12;
                break;
            case "smp":
                return r * CurveFunc.timeFactor(t1, t2, dcb);
        }
        if (m == 0) {
            throw new IllegalArgumentException("不支持的付息频率: " + frq);
        }
        return -1 + Math.pow(1 + r / m, (double) m / mcf);
    }
}
