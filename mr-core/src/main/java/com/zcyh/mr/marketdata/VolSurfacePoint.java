package com.zcyh.mr.marketdata;

import java.io.Serializable;

public final class VolSurfacePoint implements Serializable {
    private final int optionTerm;
    private final double axis2Value;
    private final double volatilityRate;
    private final String axis2InterpolateType;
    private final boolean shockApplied;

    public VolSurfacePoint(int optionTerm, double axis2Value, double volatilityRate) {
        this(optionTerm, axis2Value, volatilityRate, null, false);
    }

    public VolSurfacePoint(
            int optionTerm,
            double axis2Value,
            double volatilityRate,
            String axis2InterpolateType,
            boolean shockApplied) {
        this.optionTerm = optionTerm;
        this.axis2Value = axis2Value;
        this.volatilityRate = volatilityRate;
        this.axis2InterpolateType = axis2InterpolateType;
        this.shockApplied = shockApplied;
    }

    public int getOptionTerm() {
        return optionTerm;
    }

    public double getAxis2Value() {
        return axis2Value;
    }

    public double getVolatilityRate() {
        return volatilityRate;
    }

    public String getAxis2InterpolateType() {
        return axis2InterpolateType;
    }

    public boolean isShockApplied() {
        return shockApplied;
    }

    public VolSurfacePoint withOptionTerm(int value) {
        return new VolSurfacePoint(
                value, axis2Value, volatilityRate, axis2InterpolateType, shockApplied);
    }

    public VolSurfacePoint withVolatilityRate(double value) {
        return new VolSurfacePoint(
                optionTerm, axis2Value, value, axis2InterpolateType, shockApplied);
    }

    public VolSurfacePoint withAxis2InterpolateType(String value) {
        return new VolSurfacePoint(optionTerm, axis2Value, volatilityRate, value, shockApplied);
    }

    public VolSurfacePoint markShockApplied() {
        return new VolSurfacePoint(
                optionTerm, axis2Value, volatilityRate, axis2InterpolateType, true);
    }
}
