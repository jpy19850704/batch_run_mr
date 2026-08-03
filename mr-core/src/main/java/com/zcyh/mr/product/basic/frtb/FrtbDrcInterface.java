package com.zcyh.mr.product.basic.frtb;

import com.alibaba.fastjson2.annotation.JSONField;
import com.zcyh.mr.support.EngineConstants;
import com.zcyh.mr.frtbsa.sba.common.FrtbParamsCache;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

/**
 * @author xujg
 * @date 2024-12-25 10:46
 */
public interface FrtbDrcInterface {

    double jtd();

    default DrcDetail getDrc(Param param, LocalDate dataDate, double baseValue) {
        DrcDetail drcDetail = new DrcDetail();

        String drcType, drcBucket;
        double rw = 0;
        if (param.absFlag) {
            drcType = EngineConstants.FRTB.DRC.JTD_S_N_CTP;
            drcBucket = param.frtbSecnctpDrcType;
            rw = Objects.isNull(param.frtbSecnctpDrcRw)
                    ? FrtbParamsCache.getDrcSecnctpDefaultRiskWeight()
                    : param.frtbSecnctpDrcRw;
        } else {
            drcType = EngineConstants.FRTB.DRC.JTD_N;
            drcBucket = param.frtbNsecDrcBucket;
            rw = FrtbParamsCache.getDrcNonSecRiskWeight(param.issuerRating);
        }

        double remainTerm = ChronoUnit.DAYS.between(dataDate, param.maturityDate) / 365.0;
        double modRemainTerm = Math.min(Math.max(0.25, remainTerm), 1);
        drcDetail.dataDate = dataDate;
        drcDetail.productCode = param.productCode;
        drcDetail.instrumentId = param.instrumentId;
        drcDetail.securityId = param.bondId;
        drcDetail.securityType = drcType;
        drcDetail.legalEntity = param.issuer;
        drcDetail.drcBucket = drcBucket;
        drcDetail.jtdType = "BOND";
        drcDetail.seniority = param.absFlag ? 2 : 1;
        drcDetail.termToMaturity = remainTerm;
        drcDetail.riskWeight = rw;
        // MAR22.14：剩余期限不足1年的头寸，JTD 乘以期限调整因子
        drcDetail.jtd = jtd() * modRemainTerm;
        // 默认按输入口径透传，具体产品可再覆盖为CNY口径
        drcDetail.jtdCny = drcDetail.jtd;
        drcDetail.modifiedRemainTerm = modRemainTerm;
        drcDetail.instrumentValue = baseValue;
        drcDetail.frtbLgd = param.lgd;
        drcDetail.notional = param.notional;
        return drcDetail;
    }

    class Param {
        @JSONField(name = "PRODUCT_CODE")
        public String productCode;
        @JSONField(name = "INSTRUMENT_ID")
        public String instrumentId;
        @JSONField(name = "BOND_ID")
        public String bondId;
        @JSONField(name = "ISSUER")
        public String issuer;
        @JSONField(name = "MATURITY_DATE", format = "yyyy-MM-dd")
        public LocalDate maturityDate;
        @JSONField(name = "ABS_FLAG")
        public boolean absFlag;
        @JSONField(name = "NOTIONAL")
        public Double notional;
        @JSONField(name = "POSITION_TRADE")
        public Double positionTrade;
        @JSONField(name = "ISSUER_RATING")
        public String issuerRating;
        @JSONField(name = "LGD")
        public Double lgd;
        @JSONField(name = "FRTB_SECNCTP_DRC_TYPE")
        public String frtbSecnctpDrcType;
        @JSONField(name = "FRTB_SECNCTP_DRC_RW")
        public Double frtbSecnctpDrcRw;
        @JSONField(name = "FRTB_NSEC_DRC_BUCKET")
        public String frtbNsecDrcBucket;
    }
}
