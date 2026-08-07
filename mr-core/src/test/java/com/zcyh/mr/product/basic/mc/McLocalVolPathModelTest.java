package com.zcyh.mr.product.basic.mc;

import com.zcyh.mr.marketdata.FxVol;
import com.zcyh.mr.marketdata.VolSurfacePoint;
import com.zcyh.mr.marketdata.VolUtil;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McLocalVolPathModelTest {

    @Test
    void localVolSurfaceUsesConfiguredAxisInterpolation() {
        LocalVolSurfaceResolver resolver = LocalVolSurfaceResolver.createForTest("TEST_VOL",
                "MONEYNESS", "linear", localVolCurveData(), 100.0, termDays(), termTimes(), rates(), rates());
        double vol = resolver.resolve(30, 105.0, 100.0);

        assertEquals(0.25, vol, 1e-12);
    }

    @Test
    void termInterpolationUsesAxis2UnionBeforeVarianceInterpolation() {
        List<VolSurfacePoint> result = VolUtil.getVolCur(45, unevenAxis2VertexVol(), "linear");

        assertEquals(3, result.size());
        assertEquals(0.9, result.get(0).getAxis2Value(), 1e-12);
        assertEquals(1.0, result.get(1).getAxis2Value(), 1e-12);
        assertEquals(1.1, result.get(2).getAxis2Value(), 1e-12);
        assertEquals(Math.sqrt((0.2 * 0.2 * 30 * 0.5 + 0.3 * 0.3 * 60 * 0.5) / 45),
                result.get(1).getVolatilityRate(), 1e-12);
    }

    @Test
    void shockTermInterpolationUsesAxis2Union() {
        List<VolSurfacePoint> result = VolUtil.getShockVolCurByLinearOptionTerm(
                45, unevenAxis2VertexVol(), "linear");

        assertEquals(3, result.size());
        assertEquals(0.25, result.get(1).getVolatilityRate(), 1e-12);
    }

    @Test
    void fxVolUsesConfiguredAxis2Field() {
        FxVol.FxVolInfo info = new FxVol.FxVolInfo();
        info.curveCode = "FX_VOL_STRIKE";
        info.axis2Type = "STRIKE";
        info.termInterpolateType = "LINERVAR";
        info.axis2InterpolateType = "linear";
        info.curveData = strikeVolCurveData();

        List<VolSurfacePoint> result = new FxVol(info).getVolCur(30);

        assertEquals(2, result.size());
        assertEquals("STRIKE", info.axis2Type);
        assertEquals(90.0, result.get(0).getAxis2Value(), 1e-12);
    }

    @Test
    void invalidAxis2InterpolateTypeIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> VolUtil.normalizeAxis2InterpolateType("BAD_TYPE"));
    }

    @Test
    void localVolSurfaceSupportsNoneAxis() {
        LocalVolSurfaceResolver resolver = LocalVolSurfaceResolver.createForTest("TEST_VOL",
                "NONE", "linear", localVolCurveDataWithoutAxis2(), 100.0, termDays(), termTimes(), rates(), rates());
        double vol = resolver.resolve(30, 100.0, 100.0);

        assertEquals(0.20, vol, 1e-12);
    }

    @Test
    void localVolDeltaSurfaceConvertsDeltaToStrikeInModelLayer() {
        LocalVolSurfaceResolver resolver = LocalVolSurfaceResolver.createForTest("TEST_DELTA_VOL",
                "DELTA", "linear", deltaVolCurveData(), 100.0, new int[] { 30 },
                new double[] { 30.0 / 365.0 }, new double[] { 0.0 }, new double[] { 0.02 });

        assertEquals(0.20, resolver.resolve(30, 100.0, 100.0), 1e-12);
        assertEquals(100.0, LocalVolSurfaceResolver.deltaToStrike(
                100.0 * Math.exp(-0.02 * 30.0 / 365.0),
                0.20,
                30.0 / 365.0,
                0.02,
                0.5), 1e-12);
    }

    @Test
    void localVolDeltaSurfaceUsesFixedStrikeSurface() {
        LocalVolSurfaceResolver resolver = LocalVolSurfaceResolver.createForTest("TEST_DELTA_VOL",
                "DELTA", "linear", deltaVolCurveDataWithSmile(), 100.0, new int[] { 30 },
                new double[] { 30.0 / 365.0 }, new double[] { 0.0 }, new double[] { 0.02 });

        double lowSpotVol = resolver.resolve(30, 95.0, 100.0);
        double highSpotVol = resolver.resolve(30, 105.0, 100.0);

        assertTrue(lowSpotVol > highSpotVol);
    }

    @Test
    @SuppressWarnings({ "rawtypes", "unchecked" })
    void localVolPathUsesLogNormalProcess() {
        McPathModelDefinitions.McPathModelDefinition definition = McPathModelDefinitions.get("LOCAL_VOL");
        McPathModelDefinitions.McPathModelSpec spec =
                (McPathModelDefinitions.McPathModelSpec) definition.parse(null);
        spec.normalize();

        McPricingContext.PathRequest request = new McPricingContext.PathRequest();
        request.spot = 100.0;
        request.dt1 = new double[] { 30.0 / 365.0, 60.0 / 365.0 };
        request.dt2 = new double[] { 30.0 / 365.0, 30.0 / 365.0 };
        request.rd = new double[] { 0.02, 0.02 };
        request.rf = new double[] { 0.01, 0.01 };
        request.termDays = new int[] { 30, 60 };
        request.pathNb = 16;
        request.localVolResolver = new McPricingContext.LocalVolResolver() {
            @Override
            public double resolve(int days, double currentSpot, double initialSpot) {
                return 0.2;
            }

            @Override
            public String axis2Type() {
                return "MONEYNESS";
            }
        };

        McPricingContext.PathResult result = definition.generate(request, spec, null);

        assertNotNull(result.spotPath);
        assertEquals(2, result.spotPath.length);
        assertEquals(16, result.spotPath[0].length);
        assertTrue(result.spotPath[0][0] > 0.0);
        assertEquals("LOG_NORMAL", result.detail.get("PROCESS_TYPE"));
        assertEquals("MONEYNESS", result.detail.get("AXIS2_TYPE"));
        assertEquals(0.2, result.factor("LOCAL_VOL")[1][0], 1e-12);
    }

    private static List<VolSurfacePoint> localVolCurveData() {
        return List.of(
                point(30, 1.0, 0.20),
                point(30, 1.1, 0.30),
                point(60, 1.0, 0.22),
                point(60, 1.1, 0.32));
    }

    private static List<VolSurfacePoint> localVolCurveDataWithoutAxis2() {
        return List.of(pointWithoutAxis2(30, 0.20), pointWithoutAxis2(60, 0.22));
    }

    private static List<VolSurfacePoint> deltaVolCurveData() {
        return List.of(deltaPoint(30, 0.5, 0.20));
    }

    private static List<VolSurfacePoint> deltaVolCurveDataWithSmile() {
        return List.of(
                deltaPoint(30, 0.25, 0.18),
                deltaPoint(30, 0.5, 0.20),
                deltaPoint(30, 0.75, 0.22));
    }

    private static List<VolSurfacePoint> strikeVolCurveData() {
        return List.of(strikePoint(30, 90.0, 0.20), strikePoint(30, 110.0, 0.30));
    }

    private static List<VolSurfacePoint> unevenAxis2VertexVol() {
        return List.of(
                vertexPoint(30, 0.9, 0.10),
                vertexPoint(30, 1.0, 0.20),
                vertexPoint(60, 1.0, 0.30),
                vertexPoint(60, 1.1, 0.40));
    }

    private static VolSurfacePoint point(int optionTerm, double moneyness, double vol) {
        return new VolSurfacePoint(optionTerm, moneyness, vol);
    }

    private static VolSurfacePoint pointWithoutAxis2(int optionTerm, double vol) {
        return new VolSurfacePoint(optionTerm, 0.0, vol);
    }

    private static VolSurfacePoint deltaPoint(int optionTerm, double delta, double vol) {
        return new VolSurfacePoint(optionTerm, delta, vol);
    }

    private static VolSurfacePoint strikePoint(int optionTerm, double strike, double vol) {
        return new VolSurfacePoint(optionTerm, strike, vol);
    }

    private static VolSurfacePoint vertexPoint(int term, double axis2, double vol) {
        return new VolSurfacePoint(term, axis2, vol);
    }

    private static int[] termDays() {
        return new int[] { 30, 60 };
    }

    private static double[] termTimes() {
        return new double[] { 30.0 / 365.0, 60.0 / 365.0 };
    }

    private static double[] rates() {
        return new double[] { 0.0, 0.0 };
    }
}
