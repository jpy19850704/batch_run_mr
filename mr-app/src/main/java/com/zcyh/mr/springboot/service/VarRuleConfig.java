package com.zcyh.mr.springboot.service;

import com.zcyh.mr.springboot.model.AggregationRule;
import com.zcyh.mr.var.VarPickMethod;

import java.util.ArrayList;
import java.util.List;

final class VarRuleConfig {
    final AggregationRule rule;
    final boolean decompMode;
    final List<String> riskClasses;
    final VarPickMethod pickMethod;
    final boolean enabled;
    final int outputOrder;

    VarRuleConfig(AggregationRule rule,
                  boolean decompMode,
                  List<String> riskClasses,
                  VarPickMethod pickMethod,
                  boolean enabled,
                  int outputOrder) {
        this.rule = rule;
        this.decompMode = decompMode;
        this.riskClasses = riskClasses == null ? new ArrayList<>() : riskClasses;
        this.pickMethod = pickMethod;
        this.enabled = enabled;
        this.outputOrder = outputOrder;
    }
}
