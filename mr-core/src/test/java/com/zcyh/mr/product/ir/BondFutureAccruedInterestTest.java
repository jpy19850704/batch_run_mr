package com.zcyh.mr.product.ir;

import com.zcyh.mr.marketdata.CurveFunc;
import com.zcyh.mr.product.basic.scf.StructuredCashflow;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

class BondFutureAccruedInterestTest {

    @Test
    void calculatesAccruedInterestAfterCrossingCouponDate() {
        StructuredCashflow.Cashflow firstCoupon = coupon(
                LocalDate.of(2025, 1, 1), LocalDate.of(2025, 7, 1), 3.0);
        StructuredCashflow.Cashflow nextCoupon = coupon(
                LocalDate.of(2025, 7, 1), LocalDate.of(2026, 1, 1), 3.0);
        LocalDate deliveryDate = LocalDate.of(2025, 9, 30);

        double actual = BondFuture.calculateDeliveryAccruedInterest(
                List.of(firstCoupon, nextCoupon), deliveryDate, "actual/365");
        double expected = 3.0
                * CurveFunc.daysBetweenDCB(nextCoupon.prePaymentDate, deliveryDate, "actual/365")
                / CurveFunc.daysBetweenDCB(nextCoupon.prePaymentDate, nextCoupon.paymentDate, "actual/365");

        Assertions.assertEquals(expected, actual, 1e-12);
    }

    @Test
    void returnsZeroOnCouponDate() {
        StructuredCashflow.Cashflow nextCoupon = coupon(
                LocalDate.of(2025, 7, 1), LocalDate.of(2026, 1, 1), 3.0);

        double actual = BondFuture.calculateDeliveryAccruedInterest(
                List.of(nextCoupon), LocalDate.of(2025, 7, 1), "actual/365");

        Assertions.assertEquals(0.0, actual);
    }

    @Test
    void ignoresPrincipalCashflow() {
        StructuredCashflow.Cashflow principal = coupon(
                LocalDate.of(2025, 7, 1), LocalDate.of(2026, 1, 1), 100.0);
        principal.cashType = "PRINCIPAL";

        double actual = BondFuture.calculateDeliveryAccruedInterest(
                List.of(principal), LocalDate.of(2025, 9, 30), "actual/365");

        Assertions.assertEquals(0.0, actual);
    }

    private StructuredCashflow.Cashflow coupon(LocalDate start, LocalDate end, double amount) {
        StructuredCashflow.Cashflow cashflow = new StructuredCashflow.Cashflow();
        cashflow.cashType = "interest";
        cashflow.prePaymentDate = start;
        cashflow.paymentDate = end;
        cashflow.cf = amount;
        return cashflow;
    }
}
