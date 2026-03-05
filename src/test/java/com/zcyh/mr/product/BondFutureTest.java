package com.zcyh.mr.product;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.zcyh.mr.core.Calendar;
import com.zcyh.mr.loader.FileUtils;
import com.zcyh.mr.loader.Loader;
import com.zcyh.mr.marketdata.CommSpot;
import com.zcyh.mr.marketdata.IrSpot;
import com.zcyh.mr.marketdata.MarketData;
import com.zcyh.mr.product.bond.Bond;
import com.zcyh.mr.product.ir_deri.BondFuture;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;

/**
 * @author xujg
 * @desc
 * @date 2024-11-01 09:10
 */
public class BondFutureTest {
    List<HashMap<String, Object>> trades;
    MarketData marketData;
    HashMap<String, IrSpot.IrSpotInfo> irSpot;
    HashMap<String, CommSpot.CommSpotInfo> commSpot;
    LocalDate dataDate;
    JSONObject otherData;

    @Test
    public void testCalc() {
        String data = FileUtils.loadData("data/trade/bondFuture.json");
        Loader loader = new Loader(data);
        this.trades = loader.getTrades();
        this.marketData = loader.getMarketData();
        this.irSpot = loader.getMarketData().irSpot;
        this.commSpot = loader.getMarketData().commSpot;
        this.dataDate = loader.getDataDate();
        this.otherData = loader.getOtherData();

        JSONObject trade = (JSONObject) trades.get(0);
        BondFuture.BondFutureInfo info = JSONObject.parseObject(trade.toString(), BondFuture.BondFutureInfo.class);
        JSONObject udData = (JSONObject) this.otherData.get("UNDERLYING_BOND_DATA");
        JSONArray array = (JSONArray) udData.get(info.futureId);
        if (array.isEmpty()) {
            JSONObject json = new JSONObject();
            json.put("error","no data for futureId:" + info.futureId);
            return;
        }
        info.bondInfos = JSON.parseArray(array.toString(), Bond.BondInfo.class);
        BondFuture opt = new BondFuture(dataDate,info,marketData,new Calendar(),otherData);
        BondFuture.BondFutureMeasure measure = opt.calc();
    }
}
