package com.zcyh.mr.product.basic.option;

import org.apache.commons.math3.distribution.NormalDistribution;

public class OptUtil {
    private static final ThreadLocal<NormalDistribution> STANDARD_NORMAL =
            ThreadLocal.withInitial(NormalDistribution::new);

    public static double cdf(double x) {
        return STANDARD_NORMAL.get().cumulativeProbability(x);
    }
    public static double pdf(double x) {
        return STANDARD_NORMAL.get().density(x);
    }
    public static double ppf(double x) {
        return STANDARD_NORMAL.get().inverseCumulativeProbability(x);
    }
    public static double BS(boolean call,boolean cash, double s,double k, double rd, double rf, double sigma, double maturityT, double settleT,String model) {
        double w = call ? 1 : -1;
        double f = cash ? s * Math.exp((rd - rf) * maturityT) : s * Math.exp((rd - rf) * settleT);
        if (maturityT <= 0)
            return Math.max(w * (f - k) * Math.exp(-rd * settleT),0);

        double variableT = cash ? maturityT : settleT;
        double d = (f - k) / (sigma * Math.sqrt(maturityT));
        switch(model.toLowerCase()){
            case "black" :
                double d1 = (Math.log(f / k) + Math.pow(sigma, 2) / 2 * maturityT) / (sigma * Math.sqrt(maturityT));
                double d2 = d1 - sigma * Math.sqrt(maturityT);
                return w * (s * Math.exp(-rf * variableT) * cdf(w * d1) - k * Math.exp(-rd * variableT) * cdf(w * d2));

            case "bachelier" :
                return Math.exp(-rd * variableT) * (w * (f - k) * cdf(w*d) + sigma * Math.sqrt(maturityT) * pdf(d));
            default:
                return 0.0;
        }
    }

    public static double BS(double s,double k, double rd, double rf, double sigma, double maturityT){
        return BS(true,true,s,k,rd,rf,sigma,maturityT,maturityT,"black");
    }

    public static double BS(double s,double k,double maturityT, double rd, double rf, double sigma,boolean call){
        return BS(call,true,s,k,rd,rf,sigma,maturityT,maturityT,"black");
    }
    public static double getStrike(double f,double sigma,double t,double rf,double delta,boolean call ){
        if(call)
            return f * Math.exp(-sigma * Math.sqrt(t) * ppf(delta*Math.exp(rf*t)) + 0.5 * sigma*sigma * t);
        else
            return f * Math.exp(-sigma * Math.sqrt(t) * ppf(delta*Math.exp(rf*t)+1) + 0.5 * sigma*sigma * t);
    }
}
