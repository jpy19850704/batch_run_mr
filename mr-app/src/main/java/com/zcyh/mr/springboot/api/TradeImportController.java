package com.zcyh.mr.springboot.api;

import com.alibaba.fastjson2.JSONObject;
import com.zcyh.mr.springboot.input.trade.TradeImportService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

@RestController
@RequestMapping("/api/engine/input/trade/import")
public class TradeImportController {
    private final TradeImportService tradeImportService;

    public TradeImportController(TradeImportService tradeImportService) {
        this.tradeImportService = tradeImportService;
    }

    @PostMapping("/preview")
    public ApiResponse<JSONObject> preview(@RequestParam String dataDate,
            @RequestParam String productCode, @RequestParam MultipartFile file) throws IOException {
        return ApiResponse.ok(tradeImportService.preview(dataDate, productCode, file));
    }

    @PostMapping("/commit")
    public ApiResponse<JSONObject> commit(@RequestParam String dataDate,
            @RequestParam String productCode, @RequestParam(defaultValue = "false") boolean confirmUpdate,
            @RequestParam MultipartFile file) throws IOException {
        return ApiResponse.ok(tradeImportService.commit(dataDate, productCode, confirmUpdate, file));
    }
}
