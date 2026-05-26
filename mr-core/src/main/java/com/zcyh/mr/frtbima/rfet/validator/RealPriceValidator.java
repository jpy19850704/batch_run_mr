package com.zcyh.mr.frtbima.rfet.validator;

import com.zcyh.mr.frtbima.rfet.common.RfetConstants;
import com.zcyh.mr.frtbima.rfet.model.RealPriceObservation;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 实际价格观测有效性过滤器。
 * 过滤规则：
 * 1. observationDate 在 [cutoff-365, cutoff] 窗口内
 * 2. price 非空且非零
 */
public class RealPriceValidator {

    /**
     * 从原始观测列表中过滤出有效观测。
     *
     * @param raw    原始观测列表
     * @param cutoff 截止日期（通常为估值日）
     * @return 有效观测列表
     */
    public List<RealPriceObservation> filterValid(List<RealPriceObservation> raw, LocalDate cutoff) {
        if (raw == null || raw.isEmpty() || cutoff == null) {
            return new ArrayList<>();
        }
        LocalDate windowStart = cutoff.minusDays(RfetConstants.OBSERVATION_WINDOW_DAYS);
        List<RealPriceObservation> valid = new ArrayList<>();
        for (RealPriceObservation obs : raw) {
            if (obs == null || obs.getCurveId() == null || obs.getObservationDate() == null) {
                continue;
            }
            LocalDate date = obs.getObservationDate();
            if (date.isBefore(windowStart) || date.isAfter(cutoff)) {
                continue;
            }
            BigDecimal price = obs.getPrice();
            if (price == null || price.compareTo(BigDecimal.ZERO) == 0) {
                continue;
            }
            valid.add(obs);
        }
        return valid;
    }
}
