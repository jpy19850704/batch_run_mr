package com.zcyh.mr.springboot.input.common;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InputDetailSupportTest {
    @Test
    void classifiesUnknownTypeAndDomainIssues() {
        JSONObject content = JSONObject.of(
                "INTEREST_TYPE", "Unknown",
                "UNDERLYING_DATA", JSONArray.of(JSONObject.of("DRC_LGD", "0.75")),
                "EXTRA_FIELD", 1);
        JSONObject definition = JSONObject.of("fields", JSONArray.of(
                field("INTEREST_TYPE", "String", true, "Fixed|Floating"),
                field("UNDERLYING_DATA[0].DRC_LGD", "BigDecimal", false, "")));

        JSONObject detail = InputDetailSupport.build("TRADE", new JSONObject(), content.toJSONString(), content,
                definition, List.of("EXTRA_FIELD"), List.of("UNDERLYING_DATA[0].DRC_LGD必须为BigDecimal类型"));
        JSONArray issues = detail.getJSONArray("issues");

        assertTrue(hasIssue(issues, "EXTRA_FIELD", "UNKNOWN_FIELD", true));
        assertTrue(hasIssue(issues, "INTEREST_TYPE", "OUT_OF_DOMAIN", false));
        assertTrue(hasIssue(issues, "UNDERLYING_DATA[0].DRC_LGD", "TYPE_MISMATCH", false));
        assertEquals("UNDERLYING_DATA[].DRC_LGD",
                detail.getJSONObject("schema").getJSONArray("fields").getJSONObject(1).getString("path"));
    }

    @Test
    void malformedContentKeepsRawJsonAndParsingIssue() {
        JSONObject detail = InputDetailSupport.malformed("TRADE", new JSONObject(), "{broken",
                JSONObject.of("fields", new JSONArray()), "原始JSON解析失败");

        assertEquals("{broken", detail.getString("rawContent"));
        assertFalse(detail.containsKey("content") && detail.get("content") != null);
        assertTrue(hasIssue(detail.getJSONArray("issues"), "", "JSON_PARSE_ERROR", false));
    }

    @Test
    void allowedValuesAreComparedCaseInsensitively() {
        JSONObject content = JSONObject.of("INTERPOLATE_TYPE", "linear");
        JSONObject definition = JSONObject.of("fields", JSONArray.of(
                field("INTERPOLATE_TYPE", "String", false, "LINEAR|FORWARD")));

        JSONObject detail = InputDetailSupport.build("MARKET", new JSONObject(),
                content.toJSONString(), content, definition, List.of(), List.of());

        assertFalse(hasIssue(detail.getJSONArray("issues"),
                "INTERPOLATE_TYPE", "OUT_OF_DOMAIN", false));
    }

    private static JSONObject field(String path, String type, boolean required, String allowedValues) {
        JSONObject field = new JSONObject();
        field.put("path", path);
        field.put("type", type);
        field.put("required", required);
        field.put("allowedValues", allowedValues);
        field.put("rule", "");
        return field;
    }

    private static boolean hasIssue(JSONArray issues, String path, String code, boolean clearable) {
        return issues.stream().map(JSONObject.class::cast).anyMatch(issue -> path.equals(issue.getString("path"))
                && code.equals(issue.getString("code")) && clearable == issue.getBooleanValue("clearable"));
    }
}
