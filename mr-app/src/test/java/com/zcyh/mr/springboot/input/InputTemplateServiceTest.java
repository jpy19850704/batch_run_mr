package com.zcyh.mr.springboot.input;

import com.zcyh.mr.calc.ProductCalculatorRegistry;
import com.zcyh.mr.springboot.input.market.MarketTemplateService;
import com.zcyh.mr.springboot.input.trade.TradeTemplateService;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;

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
}
