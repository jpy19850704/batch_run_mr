package com.zcyh.mr.springboot.api;

import com.alibaba.fastjson2.JSONObject;
import com.zcyh.mr.springboot.input.trade.TradeImportService;
import com.zcyh.mr.springboot.input.trade.TradeDeleteKey;
import com.zcyh.mr.springboot.input.trade.TradeTemplateService;
import com.zcyh.mr.springboot.input.common.ExcelTemplateFile;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

@RestController
@RequestMapping("/api/engine/input/trade/import")
public class TradeImportController {
    private final TradeImportService tradeImportService;
    private final TradeTemplateService tradeTemplateService;

    public TradeImportController(TradeImportService tradeImportService, TradeTemplateService tradeTemplateService) {
        this.tradeImportService = tradeImportService;
        this.tradeTemplateService = tradeTemplateService;
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

    @GetMapping("/template")
    public ResponseEntity<byte[]> template(@RequestParam String productCode) {
        ExcelTemplateFile file = tradeTemplateService.generate(productCode);
        return download(file);
    }

    @DeleteMapping
    public ApiResponse<JSONObject> delete(@RequestBody List<TradeDeleteKey> rows) {
        return ApiResponse.ok(tradeImportService.delete(rows));
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
