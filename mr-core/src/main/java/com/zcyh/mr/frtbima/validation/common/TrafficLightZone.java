package com.zcyh.mr.frtbima.validation.common;

/**
 * 回测交通灯区间枚举。
 * 规则依据：MAR32.4（回测例外次数对应的区间划分）
 */
public enum TrafficLightZone {

    /** 绿区：例外次数 0-4，模型表现良好 */
    GREEN,

    /** 黄区：例外次数 5-9，模型需要关注，需计提附加资本 */
    AMBER,

    /** 红区：例外次数 ≥ 10，模型不合格，禁止使用内模法 */
    RED;

    /**
     * 根据回测例外次数判断所属区间。
     *
     * @param exceptionCount 250天窗口内的例外次数
     * @return 交通灯区间
     */
    public static TrafficLightZone fromExceptions(int exceptionCount) {
        if (exceptionCount <= ValidationConstants.GREEN_ZONE_MAX_EXCEPTIONS) {
            return GREEN;
        }
        if (exceptionCount < ValidationConstants.RED_ZONE_MIN_EXCEPTIONS) {
            return AMBER;
        }
        return RED;
    }
}
