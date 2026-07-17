package com.zcyh.mr.product.basic.mc;

import com.zcyh.mr.support.Convert;
import com.zcyh.mr.marketdata.FxVol;
import com.zcyh.mr.marketdata.VolUtil;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
        List<Map<String, Object>> result = VolUtil.getVolCur(45, unevenAxis2VertexVol(), "linear");

        assertEquals(3, result.size());
        assertEquals(0.9, Convert.toDouble(result.get(0).get("VERTEX2")), 1e-12);
        assertEquals(1.0, Convert.toDouble(result.get(1).get("VERTEX2")), 1e-12);
        assertEquals(1.1, Convert.toDouble(result.get(2).get("VERTEX2")), 1e-12);
        assertEquals(Math.sqrt((0.2 * 0.2 * 30 * 0.5 + 0.3 * 0.3 * 60 * 0.5) / 45),
                Convert.toDouble(result.get(1).get("VOLATILITY_RATE")), 1e-12);
    }

    @Test
    void shockTermInterpolationUsesAxis2Union() {
        List<Map<String, Object>> result = VolUtil.getShockVolCurByLinearOptionTerm(45, unevenAxis2VertexVol(), "linear");

        assertEquals(3, result.size());
        assertEquals(0.25, Convert.toDouble(result.get(1).get("VOLATILITY_RATE")), 1e-12);
    }

    @Test
    void fxVolUsesConfiguredAxis2Field() {
        FxVol.FxVolInfo info = new FxVol.FxVolInfo();
        info.curveCode = "FX_VOL_STRIKE";
        info.axis2Type = "STRIKE";
        info.termInterpolateType = "LINERVAR";
        info.axis2InterpolateType = "linear";
        info.curveData = strikeVolCurveData();

        List<Map<String, Object>> result = new FxVol(info).getVolCur(30);

        assertEquals(2, result.size());
        assertTrue(result.get(0).containsKey("STRIKE"));
        assertFalse(result.get(0).containsKey("DELTA"));
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

    private static List<Map<String, Object>> localVolCurveData() {
        List<Map<String, Object>> data = new ArrayList<>();
        data.add(point(30, 1.0, 0.20));
        data.add(point(30, 1.1, 0.30));
        data.add(point(60, 1.0, 0.22));
        data.add(point(60, 1.1, 0.32));
        return data;
    }

    private static List<Map<String, Object>> localVolCurveDataWithoutAxis2() {
        List<Map<String, Object>> data = new ArrayList<>();
        data.add(pointWithoutAxis2(30, 0.20));
        data.add(pointWithoutAxis2(60, 0.22));
        return data;
    }

    private static List<Map<String, Object>> deltaVolCurveData() {
        List<Map<String, Object>> data = new ArrayList<>();
        data.add(deltaPoint(30, 0.5, 0.20));
        return data;
    }

    private static List<Map<String, Object>> deltaVolCurveDataWithSmile() {
        List<Map<String, Object>> data = new ArrayList<>();
        data.add(deltaPoint(30, 0.25, 0.18));
        data.add(deltaPoint(30, 0.5, 0.20));
        data.add(deltaPoint(30, 0.75, 0.22));
        return data;
    }

    private static List<Map<String, Object>> strikeVolCurveData() {
        List<Map<String, Object>> data = new ArrayList<>();
        data.add(strikePoint(30, 90.0, 0.20));
        data.add(strikePoint(30, 110.0, 0.30));
        return data;
    }

    private static List<Map<String, Object>> unevenAxis2VertexVol() {
        List<Map<String, Object>> data = new ArrayList<>();
        data.add(vertexPoint(30, 0.9, 0.10));
        data.add(vertexPoint(30, 1.0, 0.20));
        data.add(vertexPoint(60, 1.0, 0.30));
        data.add(vertexPoint(60, 1.1, 0.40));
        return data;
    }

    private static Map<String, Object> point(int optionTerm, double moneyness, double vol) {
        Map<String, Object> point = new HashMap<>();
        point.put("OPTION_TERM", optionTerm);
        point.put("MONEYNESS", moneyness);
        point.put("VOLATILITY_RATE", vol);
        return point;
    }

    private static Map<String, Object> pointWithoutAxis2(int optionTerm, double vol) {
        Map<String, Object> point = new HashMap<>();
        point.put("OPTION_TERM", optionTerm);
        point.put("VOLATILITY_RATE", vol);
        return point;
    }

    private static Map<String, Object> deltaPoint(int optionTerm, double delta, double vol) {
        Map<String, Object> point = new HashMap<>();
        point.put("OPTION_TERM", optionTerm);
        point.put("DELTA", delta);
        point.put("VOLATILITY_RATE", vol);
        return point;
    }

    private static Map<String, Object> strikePoint(int optionTerm, double strike, double vol) {
        Map<String, Object> point = new HashMap<>();
        point.put("OPTION_TERM", optionTerm);
        point.put("STRIKE", strike);
        point.put("VOLATILITY_RATE", vol);
        return point;
    }

    private static Map<String, Object> vertexPoint(int term, double axis2, double vol) {
        Map<String, Object> point = new HashMap<>();
        point.put("VERTEX1", term);
        point.put("VERTEX2", axis2);
        point.put("VOLATILITY_RATE", vol);
        return point;
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
