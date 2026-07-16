package com.zcyh.mr.springboot.service;

import com.zcyh.mr.springboot.model.AggregationRule;
import com.zcyh.mr.var.VarPickMethod;
import com.zcyh.mr.var.VarMeasure;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

final class VarRuleConfig {
    final AggregationRule rule;
    final boolean decompMode;
    final List<String> riskClasses;
    final VarPickMethod pickMethod;
    final List<BigDecimal> quantiles;
    final List<VarMeasure> measures;
    final boolean enabled;
    final int outputOrder;

    VarRuleConfig(AggregationRule rule,
                  boolean decompMode,
                  List<String> riskClasses,
                  VarPickMethod pickMethod,
                  List<BigDecimal> quantiles,
                  List<VarMeasure> measures,
                  boolean enabled,
                  int outputOrder) {
        this.rule = rule;
        this.decompMode = decompMode;
        this.riskClasses = riskClasses == null ? new ArrayList<>() : riskClasses;
        this.pickMethod = pickMethod;
        this.quantiles = quantiles == null ? new ArrayList<>() : quantiles;
        this.measures = measures == null ? new ArrayList<>() : measures;
        this.enabled = enabled;
        this.outputOrder = outputOrder;
    }
}
