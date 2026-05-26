package com.zcyh.mr.scenario.util;

import com.zcyh.mr.scenario.model.ScenarioMarketSeries;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;

/**
 * 冲击工具类。
 */
public class ShockUtils {

    public static final String SPLITSTR = "@@";

    public static final String ABSOLUTE = "ABSOLUTE";
    public static final String RELATIVE = "RELATIVE";

    private static final String IR_SPOT = "IR_SPOT";
    private static final String COMM_SPOT = "COMM_SPOT";
    private static final String FX_SPOT = "FX_SPOT";
    private static final String EQ_SPOT = "EQ_SPOT";
    private static final String FX_VOL = "FX_VOL";
    private static final String IR_VOL = "IR_VOL";
    private static final String COMM_VOL = "COMM_VOL";
    private static final String EQ_VOL = "EQ_VOL";

    /**
     * 波动率曲面的第二维。
     */
    public static String getVolAxis2(ScenarioMarketSeries point) {
        if (point == null || point.getDimension2() == null || point.getDimension2().trim().isEmpty()) {
            return "";
        }
        return point.getDimension2().trim();
    }

    /**
     * 期限代码转天数。
     */
    public static int termCodeToInt(String termCode) {
        if (termCode == null || termCode.isEmpty()) {
            return 0;
        }

        String upperCode = termCode.toUpperCase().trim();

        try {
            if (upperCode.endsWith("D")) {
                return Integer.parseInt(upperCode.substring(0, upperCode.length() - 1));
            }
            if (upperCode.endsWith("W")) {
                return Integer.parseInt(upperCode.substring(0, upperCode.length() - 1)) * 7;
            }
            if (upperCode.endsWith("M")) {
                return Integer.parseInt(upperCode.substring(0, upperCode.length() - 1)) * 30;
            }
            if (upperCode.endsWith("Y")) {
                return Integer.parseInt(upperCode.substring(0, upperCode.length() - 1)) * 365;
            }
            return Integer.parseInt(upperCode);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * 获取工作日列表。
     */
    public static List<Date> getWeekday(
            Set<Date> holidaySet,
            Date startDate,
            Date endDate,
            Integer dayNo) {
        List<Date> weekday = new LinkedList<Date>();
        Set<Date> holidays = holidaySet == null ? Collections.<Date>emptySet() : holidaySet;

        int amount = 0;
        Date stDate = null;

        if (startDate != null) {
            stDate = startDate;
            amount = 1;
        }
        if (endDate != null) {
            stDate = endDate;
            amount = -1;
        }

        Calendar calendar = Calendar.getInstance();
        calendar.setTime(stDate);
        calendar.add(Calendar.DATE, 1);

        int count = 0;
        while (dayNo == null || count < dayNo) {
            Date currentDate = calendar.getTime();
            boolean isHoliday = holidays.contains(currentDate);

            if (!isHoliday) {
                weekday.add(currentDate);
                count++;
            }

            calendar.add(Calendar.DATE, amount);

            if (dayNo == null && startDate != null && endDate != null) {
                if ((amount > 0 && currentDate.after(endDate))
                        || (amount < 0 && currentDate.before(startDate))) {
                    break;
                }
            }
        }

        if (amount < 0) {
            Collections.reverse(weekday);
        }
        return weekday;
    }

    /**
     * 获取标准对象比较器。
     */
    public static Comparator<ScenarioMarketSeries> getComparatorForPoint() {
        return ShockUtils::getSortCompare;
    }

    /**
     * 获取标准对象唯一标识（用于行映射）。
     */
    public static String getUniqueCode(ScenarioMarketSeries point) {
        String curveType = point.getCurveType() == null ? "" : point.getCurveType();
        String curveCode = point.getCurveCode() == null ? "" : point.getCurveCode();
        String termCode = point.getTermCode() == null ? "" : point.getTermCode();

        switch (curveType) {
            case IR_SPOT:
            case COMM_SPOT:
            case EQ_SPOT:
                return curveCode + SPLITSTR + termCode;
            case FX_SPOT:
                return curveCode;
            case FX_VOL:
            case IR_VOL:
            case COMM_VOL:
            case EQ_VOL:
                return curveCode + SPLITSTR + getVolAxis2(point) + SPLITSTR + termCode;
            default:
                return curveCode + SPLITSTR + termCode;
        }
    }

    /**
     * 获取风险因子唯一标识（用于分组）。
     */
    public static String getUnique(ScenarioMarketSeries point) {
        String curveType = point.getCurveType() == null ? "" : point.getCurveType();
        String curveCode = point.getCurveCode() == null ? "" : point.getCurveCode();

        switch (curveType) {
            case IR_SPOT:
            case COMM_SPOT:
            case FX_SPOT:
            case EQ_SPOT:
                return curveCode;
            case FX_VOL:
            case COMM_VOL:
            case IR_VOL:
            case EQ_VOL:
                return curveCode + SPLITSTR + getVolAxis2(point);
            default:
                return curveCode;
        }
    }

    /**
     * 排序比较逻辑。
     */
    public static int getSortCompare(ScenarioMarketSeries p1, ScenarioMarketSeries p2) {
        String c1 = p1.getCurveCode() == null ? "" : p1.getCurveCode();
        String c2 = p2.getCurveCode() == null ? "" : p2.getCurveCode();
        int compare = c1.compareTo(c2);
        if (compare != 0) {
            return compare;
        }

        String axis21 = getVolAxis2(p1);
        String axis22 = getVolAxis2(p2);
        if (!axis21.isEmpty() && !axis22.isEmpty()) {
            compare = axis21.compareTo(axis22);
            if (compare != 0) {
                return compare;
            }
        }

        return resolveTermDays(p1) - resolveTermDays(p2);
    }

    /**
     * 计算情景冲击。
     */
    public static BigDecimal calculateShock(BigDecimal historyVal, BigDecimal currentVal, String scenarioShiftRule) {
        if (historyVal == null || currentVal == null) {
            return BigDecimal.ZERO;
        }

        if (scenarioShiftRule == null || ABSOLUTE.equalsIgnoreCase(scenarioShiftRule)) {
            return historyVal.subtract(currentVal);
        }
        if (RELATIVE.equalsIgnoreCase(scenarioShiftRule)) {
            if (currentVal.compareTo(BigDecimal.ZERO) == 0) {
                return BigDecimal.ZERO;
            }
            return historyVal.subtract(currentVal)
                    .divide(currentVal, 8, RoundingMode.HALF_UP);
        }
        return historyVal.subtract(currentVal);
    }

    /**
     * 标准对象期限天数解析。
     */
    public static int resolveTermDays(ScenarioMarketSeries point) {
        if (point == null) {
            return 0;
        }
        if (point.getTermDays() != null && point.getTermDays() > 0) {
            return point.getTermDays();
        }
        if (point.getTermCode() != null) {
            return termCodeToInt(point.getTermCode());
        }
        return 0;
    }
}
