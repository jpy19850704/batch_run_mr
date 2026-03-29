package com.zcyh.mr.calc;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONWriter;
import com.zcyh.mr.core.Calendar;
import com.zcyh.mr.loader.FileUtils;
import com.zcyh.mr.loader.Loader;
import com.zcyh.mr.marketdata.MarketData;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;

/**
 * CDS 计量测试入口
 */
public class CdsCalcTest {
    public static void main(String[] args) {
        System.out.println("========== CDS TEST START ==========\n");

        String jsonFile = "data/trade/cds_test.json";
        if (args.length > 0) {
            jsonFile = args[0];
        }

        // 加载数据
        String data = FileUtils.loadData(jsonFile);
        if (data == null || data.isEmpty()) {
            System.err.println("[ERROR] 无法读取文件: " + jsonFile);
            return;
        }

        Loader loader = new Loader(data);
        LocalDate dataDate = loader.getDataDate();
        MarketData marketData = loader.getMarketData();
        Calendar calendar = loader.getCalendar();
        List<HashMap<String, Object>> trades = loader.getTrades();

        System.out.println("[INFO] 数据日期: " + dataDate);
        System.out.println("[INFO] 交易笔数: " + trades.size());
        System.out.println("[INFO] IR曲线数: " + marketData.irSpot.size());
        System.out.println("[INFO] FX数据: " + (marketData.fxSpot != null ? marketData.fxSpot.curveData : "null"));

        // 校验错误输出
        if (!loader.getValidationErrors().isEmpty()) {
            System.out.println("\n[WARN] 数据校验错误:");
            System.out.println(JSON.toJSONString(loader.getValidationErrors(), JSONWriter.Feature.PrettyFormat));
        }

        // 执行计算
        System.out.println("\n[INFO] 开始 CDS 计量...\n");
        CdsCalc cdsCalc = new CdsCalc(
                loader.getOperCode(), dataDate, trades,
                marketData, calendar, loader.getOtherData());
        String result = cdsCalc.calc();

        // 输出结果
        Object parsed = JSON.parse(result);
        System.out.println(
                JSON.toJSONString(parsed, JSONWriter.Feature.PrettyFormat, JSONWriter.Feature.WriteMapNullValue));

        System.out.println("\n========== CDS TEST END ==========");
    }
}
