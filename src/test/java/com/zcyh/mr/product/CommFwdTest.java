package com.zcyh.mr.product;

import com.alibaba.fastjson2.JSONObject;
import com.zcyh.mr.core.Calendar;
import com.zcyh.mr.core.CurveFunc;
import com.zcyh.mr.core.Interpolation;
import com.zcyh.mr.loader.FileUtils;
import com.zcyh.mr.loader.Loader;
import com.zcyh.mr.marketdata.CommSpot;
import com.zcyh.mr.marketdata.IrSpot;
import com.zcyh.mr.marketdata.MarketData;
import com.zcyh.mr.product.comm.CommFwd;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.HashMap;
import java.util.List;

class CommFwdTest {
    List<HashMap<String, Object>> trades;
    MarketData marketData;
    HashMap<String, IrSpot.IrSpotInfo> irSpot;
    HashMap<String, CommSpot.CommSpotInfo> commSpot;
    LocalDate dataDate;

    @Test
    public void calc() {
        String data = FileUtils.loadData("data/trade/commfwd.json");
        Loader loader = new Loader(data);
        this.trades = loader.getTrades();
        this.marketData = loader.getMarketData();
        this.irSpot = loader.getMarketData().irSpot;
        this.commSpot = loader.getMarketData().commSpot;
        this.dataDate = loader.getDataDate();
        System.out.println(trades);

        JSONObject trade = (JSONObject) trades.get(0);
        CommFwd.CommFwdInfo commFwdInfo = JSONObject.parseObject(trade.toString(), CommFwd.CommFwdInfo.class);
        CommFwd commFwd = new CommFwd(dataDate,commFwdInfo, marketData);


        LocalDate dataDate = LocalDate.of(2021, 12, 31);
        LocalDate settleDate = commFwdInfo.settleDate;

        IrSpot.IrSpotInfo irSpotInfo = irSpot.get(commFwdInfo.discountCurve);
        int days = (int) ChronoUnit.DAYS.between(dataDate, settleDate);
        double rate = Interpolation.interpolate(irSpotInfo.curveData, days, irSpotInfo.interpolateType);

        double disc = CurveFunc.discountFactor(dataDate, settleDate, rate, irSpotInfo.freq, irSpotInfo.dayCount);
        System.out.println(disc);

        CommSpot.CommSpotInfo commSpotInfo = commSpot.get(commFwdInfo.priceCurve);
        double fwdPrice = Interpolation.interpolate(commSpotInfo.curveData, days, irSpotInfo.interpolateType);
        System.out.println(fwdPrice);


        double value = (fwdPrice - commFwdInfo.strikePrice) * disc * commFwdInfo.contractSize * (commFwdInfo.buyOrSell.equals(
                "B") ? 1 : -1);
    }

    @Test
    public void test() {
        LocalDate refDate = LocalDate.of(2021,12,30);
        LocalDate refDateOri = LocalDate.from(refDate);
        LocalDate firstDay = refDate.with(TemporalAdjusters.firstDayOfMonth());
        System.out.println(refDate.getMonthValue());
        System.out.println();


        Calendar calendar = new Calendar();

        String data = FileUtils.loadData("data/cal.csv");

        String[] lines = data.split("\n");
        for (String line: lines) {
            String[] elems = line.split(",");
            String calName = elems[0].trim();
            String holDateStr = elems[1].trim();
            calendar.addHoliday(calName,LocalDate.parse(holDateStr, DateTimeFormatter.ofPattern("yyyy-MM-dd")));

        }



        LocalDate busDay = calendar.getBusinessDay("PEK", LocalDate.of(2024,3,31),"MF",2);
        System.out.println("---------");

        LocalDate refDate1 = LocalDate.of(2021,12,30);
        LocalDate ref2 = refDate1.plusDays(2);
        System.out.println();

    }
}