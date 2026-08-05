package com.zcyh.mr.springboot.api;

import com.alibaba.fastjson2.JSONObject;
import com.zcyh.mr.springboot.input.market.MarketImportService;
import com.zcyh.mr.springboot.input.market.MarketDeleteKey;
import com.zcyh.mr.springboot.input.market.MarketEditRequest;
import com.zcyh.mr.springboot.input.market.MarketTemplateService;
import com.zcyh.mr.springboot.input.market.MarketDetailRequest;
import com.zcyh.mr.springboot.input.market.MarketInputDetailService;
import com.zcyh.mr.springboot.input.common.ExcelTemplateFile;
import com.zcyh.mr.springboot.input.common.EngineInputQueryService;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/engine/input/market")
public class MarketImportController {
    private final MarketImportService marketImportService;
    private final MarketTemplateService marketTemplateService;
    private final MarketInputDetailService marketInputDetailService;
    private final EngineInputQueryService inputQueryService;

    public MarketImportController(MarketImportService marketImportService,
            MarketTemplateService marketTemplateService, MarketInputDetailService marketInputDetailService,
            EngineInputQueryService inputQueryService) {
        this.marketImportService = marketImportService;
        this.marketTemplateService = marketTemplateService;
        this.marketInputDetailService = marketInputDetailService;
        this.inputQueryService = inputQueryService;
    }

    @GetMapping("/query")
    public ApiResponse<JSONObject> query(@RequestParam(defaultValue = "MARKET") String dataKind,
            @RequestParam Map<String, String> params) {
        return ApiResponse.ok(inputQueryService.queryMarket(params, dataKind));
    }

    @GetMapping("/types")
    public ApiResponse<List<String>> types(@RequestParam(defaultValue = "MARKET") String dataKind) {
        return ApiResponse.ok(inputQueryService.marketTypes(dataKind));
    }

    @PostMapping("/import/preview")
    public ApiResponse<JSONObject> preview(@RequestParam String dataDate,
            @RequestParam String marketDataType, @RequestParam MultipartFile file) throws IOException {
        return ApiResponse.ok(marketImportService.preview(dataDate, marketDataType, file));
    }

    @PostMapping("/import/commit")
    public ApiResponse<JSONObject> commit(@RequestParam String dataDate,
            @RequestParam String marketDataType,
            @RequestParam(defaultValue = "false") boolean confirmUpdate,
            @RequestParam MultipartFile file) throws IOException {
        return ApiResponse.ok(marketImportService.commit(dataDate, marketDataType, confirmUpdate, file));
    }

    @PostMapping("/detail")
    public ApiResponse<JSONObject> detail(@RequestBody MarketDetailRequest request) {
        return ApiResponse.ok(marketInputDetailService.detail(request));
    }

    @PutMapping
    public ApiResponse<JSONObject> edit(@RequestBody MarketEditRequest request) {
        marketImportService.edit(request);
        return ApiResponse.ok(marketInputDetailService.detailAfterEdit(request));
    }

    @GetMapping("/import/template")
    public ResponseEntity<byte[]> template(@RequestParam String marketDataType) {
        ExcelTemplateFile file = marketTemplateService.generate(marketDataType);
        return download(file);
    }

    @DeleteMapping
    public ApiResponse<JSONObject> delete(@RequestBody List<MarketDeleteKey> rows) {
        return ApiResponse.ok(marketImportService.delete(rows));
    }

    private static ResponseEntity<byte[]> download(ExcelTemplateFile file) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType(
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"));
        headers.setContentDisposition(ContentDisposition.attachment()
                .filename(file.getFileName(), StandardCharsets.UTF_8).build());
        return ResponseEntity.ok().headers(headers).body(file.getContent());
    }
}
