package com.zcyh.mr.support;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.time.LocalDate;
import java.time.Period;
import java.time.temporal.ChronoUnit;
import java.util.Map;

/**
 * @author xujg
 * @date 2024-07-25 08:52
 */
public class CommUtils {
    private static final Logger log = LoggerFactory.getLogger(CommUtils.class);

    /**
     * 将日期按照固定 term 转换为对应天数
     * @date 2024-07-16 09:09:974
     * @author xujg
     */
    public static int[] tranfToDays(LocalDate date, String[] term) {
        int[] res = new int[term.length];
        for (int i = 0; i < term.length; i++) {
            res[i] = (int) ChronoUnit.DAYS.between(date, periodPlus(date, term[i]));
        }
        return res;
    }

    /**
     * 按年月日增加日期，当前仅覆盖已有场景
     * @date 2024-07-16 09:08:18
     * @updatelog 2024-10-29 : 针对出现0.5Y的现象做出调整
     * @author xujg
     */
    public static LocalDate periodPlus(LocalDate date, String s) {
        LocalDate dt = date;
        int m, d, days;
        try {
            char u = s.charAt(s.length() - 1);
            String str = s.substring(0, s.length() - 1);
            double t = Double.parseDouble(str);
            switch (u) {
                case 'Y':
                    days = (int) (t * 360);
                    m = days / 30;
                    d = days % 30;
                    dt = date.plusMonths(m).plusDays(d);
                    break;
                case 'M':
                    days = (int) (t * 30);
                    m = days / 30;
                    d = days % 30;
                    dt = date.plusMonths(m).plusDays(d);
                    break;
                case 'D':
                    dt = date.plusDays(Long.parseLong(str));
                    break;
                default:
                    throw new Exception("periodPlus 输入错误:" + s);
            }

        } catch (Exception e) {
            log.warn("periodPlus 解析失败: period={}", s, e);
        }
        return dt;
    }

    public static <T> T deepCopy(T src) {
        if (src == null) {
            return null;
        }
        try {
            ByteArrayOutputStream byteOut = new ByteArrayOutputStream();
            ObjectOutputStream objectOut = new ObjectOutputStream(byteOut);
            objectOut.writeObject(src);

            ByteArrayInputStream byteIn = new ByteArrayInputStream(byteOut.toByteArray());
            ObjectInputStream objectIn = new ObjectInputStream(byteIn);
            T dest = (T) objectIn.readObject();
            return dest;
        } catch (Exception e) {
            throw new RuntimeException("深拷贝失败: " + src.getClass().getSimpleName(), e);
        }
    }

    public static int periodCompare(Period left, Period right) {
        if (left == null || right == null) {
            throw new IllegalArgumentException("频率参数不能为null: left=" + left + ", right=" + right);
        }
        int ln = left.getYears() * 360 + left.getMonths() * 30 + left.getDays();
        int rn = right.getYears() * 360 + right.getMonths() * 30 + right.getDays();

        return ln - rn;
    }

    /**
     * 根据rules对key重命名，如果新key之前就存在，会覆盖
     * 
     * @date 2024-12-18 10:38:723
     * @author xujg
     */
    public static Map<String, Object> mapKeyRename(Map<String, Object> map, Map<String, String> rules) {
        for (String key : rules.keySet()) {
            if (map.containsKey(key)) {
                Object val = map.get(key);
                map.put(rules.get(key), val);
            }
        }
        for (String key : rules.keySet()) {
            map.remove(key);
        }
        return map;
    }
}
