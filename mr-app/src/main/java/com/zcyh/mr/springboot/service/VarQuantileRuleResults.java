package com.zcyh.mr.springboot.service;

import com.alibaba.fastjson2.JSONObject;

import java.util.ArrayList;
import java.util.List;

final class VarQuantileRuleResults {
    final int quantileIndex;
    final List<JSONObject> ruleResults;

    VarQuantileRuleResults(int quantileIndex, List<JSONObject> ruleResults) {
        this.quantileIndex = quantileIndex;
        this.ruleResults = ruleResults == null ? new ArrayList<>() : ruleResults;
    }
}
