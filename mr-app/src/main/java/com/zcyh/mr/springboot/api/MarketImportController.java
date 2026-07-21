package com.zcyh.mr.springboot.api;

import com.alibaba.fastjson2.JSONObject;
import com.zcyh.mr.springboot.input.market.MarketImportService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

@RestController
@RequestMapping("/api/engine/input/market/import")
public class MarketImportController {
    private final MarketImportService marketImportService;

    public MarketImportController(MarketImportService marketImportService) {
        this.marketImportService = marketImportService;
    }

    @PostMapping("/preview")
    public ApiResponse<JSONObject> preview(@RequestParam String dataDate,
            @RequestParam String marketDataType, @RequestParam MultipartFile file) throws IOException {
        return ApiResponse.ok(marketImportService.preview(dataDate, marketDataType, file));
    }

    @PostMapping("/commit")
    public ApiResponse<JSONObject> commit(@RequestParam String dataDate,
            @RequestParam String marketDataType,
            @RequestParam(defaultValue = "false") boolean confirmUpdate,
            @RequestParam MultipartFile file) throws IOException {
        return ApiResponse.ok(marketImportService.commit(dataDate, marketDataType, confirmUpdate, file));
    }
}
