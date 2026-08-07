package com.zcyh.mr.springboot.input;

import com.alibaba.fastjson2.JSONObject;
import com.zcyh.mr.calc.ProductCalculatorRegistry;
import com.zcyh.mr.springboot.input.market.MarketTemplateService;
import com.zcyh.mr.springboot.input.trade.TradeTemplateService;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InputTemplateServiceTest {
    @Test
    void allRegisteredProductsCanGenerateTemplate() {
        TradeTemplateService service = new TradeTemplateService();
        for (String productCode : ProductCalculatorRegistry.productCodes()) {
            assertTrue(service.generate(productCode).getContent().length > 0, productCode);
        }
    }

    @Test
    void tradeTemplateUsesConcreteTradeInfoFields() throws Exception {
        byte[] content = new TradeTemplateService().generate("FXFWD").getContent();
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(content))) {
            List<String> headers = headers(workbook);
            assertTrue(headers.contains("INSTRUMENT_ID"));
            assertTrue(headers.contains("PRODUCT_CODE"));
            assertTrue(headers.contains("BASE_CURRENCY_CODE"));
            assertTrue(headers.contains("DESK"));
            assertTrue(workbook.getSheet("FIELD_HELP").getPhysicalNumberOfRows() > 1);
        }
    }

    @Test
    void cdsTemplateAndDetailDefinitionExposeSettlementFields() throws Exception {
        TradeTemplateService service = new TradeTemplateService();
        byte[] content = service.generate("CDS").getContent();
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(content))) {
            List<String> headers = headers(workbook);
            assertTrue(headers.contains("SETTLE_RULE"));
            assertTrue(headers.contains("SETTLE_DAYOFF"));
        }

        JSONObject definition = service.definition("CDS");
        JSONObject settleRule = field(definition, "SETTLE_RULE");
        JSONObject settleDayoff = field(definition, "SETTLE_DAYOFF");
        assertTrue(settleRule.getString("allowedValues").contains("Regular_Following"));
        assertTrue("Integer".equals(settleDayoff.getString("type")));
        assertTrue(settleDayoff.getString("rule").contains("最小值=0"));
    }

    @Test
    void updatedProductsExposeCurrentTradeFields() {
        TradeTemplateService service = new TradeTemplateService();

        JSONObject asian = service.definition("EQ_ASIAN");
        assertTrue(hasField(asian, "OBS_DATES"));
        assertFalse(hasField(asian, "OBS_START_DATE"));
        assertFalse(hasField(asian, "OBS_END_DATE"));

        JSONObject trs = service.definition("TRS");
        assertTrue(hasField(trs, "UNDERLYING_FIXING_ID"));
        assertTrue(hasField(trs, "FX_FIXING_ID"));
        assertTrue(hasField(trs, "INTEREST_AGGREGATION_METHOD"));
        assertTrue(field(trs, "SETTLE_RULE").getString("allowedValues").contains("Modified_Following"));

        JSONObject capFloor = service.definition("CAPFLOOR");
        assertTrue(hasField(capFloor, "INTEREST_AGGREGATION_METHOD"));
        assertTrue(field(capFloor, "FIXING_DAYOFF").getString("rule").contains("最小值=0"));

        JSONObject swaption = service.definition("SWAPTION");
        assertTrue(hasField(swaption, "FIXING_ID"));
        assertTrue(hasField(swaption, "SETTLE_TYPE"));
        assertFalse(hasField(swaption, "UNDERLYING_NOTIONAL"));
    }

    @Test
    void compositeTemplateUsesFlatIndexedComponentPath() throws Exception {
        byte[] content = new TradeTemplateService().generate("COMPOSITE").getContent();
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(content))) {
            List<String> headers = headers(workbook);
            assertTrue(headers.contains("COMPONENTS[0].COMPONENT_ID"));
            assertTrue(headers.contains("COMPONENTS[0].WEIGHT"));
            assertTrue(headers.contains("COMPONENTS[0].DATA.PRODUCT_CODE"));
        }
    }

    @Test
    void marketTemplateUsesSharedTypeSchema() throws Exception {
        byte[] content = new MarketTemplateService().generate("IR_SPOT").getContent();
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(content))) {
            List<String> headers = headers(workbook);
            assertTrue(headers.contains("CURVE_ID"));
            assertTrue(headers.contains("TERM"));
            assertTrue(headers.contains("RATE"));
        }
    }

    private static List<String> headers(XSSFWorkbook workbook) {
        List<String> result = new ArrayList<>();
        workbook.getSheet("DATA").getRow(0).forEach(cell -> result.add(cell.getStringCellValue()));
        return result;
    }

    private static JSONObject field(JSONObject definition, String path) {
        return definition.getJSONArray("fields").stream()
                .map(JSONObject.class::cast)
                .filter(item -> path.equals(item.getString("path")))
                .findFirst()
                .orElseThrow();
    }

    private static boolean hasField(JSONObject definition, String path) {
        return definition.getJSONArray("fields").stream()
                .map(JSONObject.class::cast)
                .anyMatch(item -> path.equals(item.getString("path")));
    }
}
