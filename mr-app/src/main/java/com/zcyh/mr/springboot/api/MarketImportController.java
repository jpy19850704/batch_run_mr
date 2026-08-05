package com.zcyh.mr.springboot.api;

import com.alibaba.fastjson2.JSONObject;
import com.zcyh.mr.springboot.input.market.MarketImportService;
import com.zcyh.mr.springboot.input.market.MarketDeleteKey;
import com.zcyh.mr.springboot.input.market.MarketEditRequest;
import com.zcyh.mr.springboot.input.market.MarketTemplateService;
import com.zcyh.mr.springboot.input.common.ExcelTemplateFile;
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

@RestController
@RequestMapping("/api/engine/input/market/import")
public class MarketImportController {
    private final MarketImportService marketImportService;
    private final MarketTemplateService marketTemplateService;

    public MarketImportController(MarketImportService marketImportService,
            MarketTemplateService marketTemplateService) {
        this.marketImportService = marketImportService;
        this.marketTemplateService = marketTemplateService;
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

    @PostMapping("/template-definition")
    public ApiResponse<JSONObject> templateDefinition(@RequestBody JSONObject request) {
        return ApiResponse.ok(marketTemplateService.definition(request == null
                ? null : request.getString("marketDataType"), request == null
                ? null : request.getString("conversionType")));
    }

    @PutMapping("/edit")
    public ApiResponse<JSONObject> edit(@RequestBody MarketEditRequest request) {
        return ApiResponse.ok(marketImportService.edit(request));
    }

    @GetMapping("/template")
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
