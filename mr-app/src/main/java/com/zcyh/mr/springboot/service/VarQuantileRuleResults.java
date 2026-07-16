package com.zcyh.mr.springboot.service;

import com.alibaba.fastjson2.JSONObject;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

final class VarQuantileRuleResults {
    final BigDecimal quantile;
    final List<JSONObject> ruleResults;

    VarQuantileRuleResults(BigDecimal quantile, List<JSONObject> ruleResults) {
        this.quantile = quantile;
        this.ruleResults = ruleResults == null ? new ArrayList<>() : ruleResults;
    }
}
