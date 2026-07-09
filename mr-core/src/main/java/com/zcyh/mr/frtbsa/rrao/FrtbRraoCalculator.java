package com.zcyh.mr.frtbsa.rrao;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * FRTB RRAO 资本计算器。
 */
public class FrtbRraoCalculator {
    private static final BigDecimal EXOTIC_WEIGHT = new BigDecimal("0.01");
    private static final BigDecimal OTHER_WEIGHT = new BigDecimal("0.001");

    public List<Result> calculate(List<Input> inputs) {
        Map<GroupKey, Aggregate> grouped = new LinkedHashMap<GroupKey, Aggregate>();
        if (inputs == null || inputs.isEmpty()) {
            return new ArrayList<Result>();
        }
        for (Input input : inputs) {
            if (input == null || input.rraoType == null || input.notional == null) {
                continue;
            }
            BigDecimal weight = resolveWeight(input.rraoType);
            GroupKey key = new GroupKey(input.groupType, input.groupValue, input.rraoType);
            Aggregate aggregate = grouped.get(key);
            if (aggregate == null) {
                aggregate = new Aggregate(input.groupType, input.groupValue, input.rraoType);
                grouped.put(key, aggregate);
            }
            aggregate.tradeCount++;
            aggregate.notional = aggregate.notional.add(input.notional);
            aggregate.capital = aggregate.capital.add(input.notional.multiply(weight));
        }
        List<Result> results = new ArrayList<Result>();
        for (Aggregate aggregate : grouped.values()) {
            results.add(new Result(
                    aggregate.groupType,
                    aggregate.groupValue,
                    aggregate.rraoType,
                    aggregate.tradeCount,
                    aggregate.notional,
                    aggregate.capital));
        }
        return results;
    }

    private static BigDecimal resolveWeight(String rraoType) {
        if ("EXOTIC".equals(rraoType)) {
            return EXOTIC_WEIGHT;
        }
        if ("OTHER".equals(rraoType)) {
            return OTHER_WEIGHT;
        }
        throw new IllegalArgumentException("RRAO_TYPE 不支持: " + rraoType);
    }

    public static class Input {
        private final String groupType;
        private final String groupValue;
        private final String rraoType;
        private final BigDecimal notional;

        public Input(String groupType, String groupValue, String rraoType, BigDecimal notional) {
            this.groupType = groupType;
            this.groupValue = groupValue;
            this.rraoType = rraoType;
            this.notional = notional;
        }
    }

    public static class Result {
        private final String groupType;
        private final String groupValue;
        private final String rraoType;
        private final long tradeCount;
        private final BigDecimal notional;
        private final BigDecimal capital;

        public Result(String groupType,
                      String groupValue,
                      String rraoType,
                      long tradeCount,
                      BigDecimal notional,
                      BigDecimal capital) {
            this.groupType = groupType;
            this.groupValue = groupValue;
            this.rraoType = rraoType;
            this.tradeCount = tradeCount;
            this.notional = notional;
            this.capital = capital;
        }

        public String getGroupType() {
            return groupType;
        }

        public String getGroupValue() {
            return groupValue;
        }

        public String getRraoType() {
            return rraoType;
        }

        public long getTradeCount() {
            return tradeCount;
        }

        public BigDecimal getNotional() {
            return notional;
        }

        public BigDecimal getCapital() {
            return capital;
        }
    }

    private static class Aggregate {
        private final String groupType;
        private final String groupValue;
        private final String rraoType;
        private long tradeCount;
        private BigDecimal notional = BigDecimal.ZERO;
        private BigDecimal capital = BigDecimal.ZERO;

        private Aggregate(String groupType, String groupValue, String rraoType) {
            this.groupType = groupType;
            this.groupValue = groupValue;
            this.rraoType = rraoType;
        }
    }

    private static class GroupKey {
        private final String groupType;
        private final String groupValue;
        private final String rraoType;

        private GroupKey(String groupType, String groupValue, String rraoType) {
            this.groupType = groupType;
            this.groupValue = groupValue;
            this.rraoType = rraoType;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (!(obj instanceof GroupKey)) {
                return false;
            }
            GroupKey other = (GroupKey) obj;
            return groupType.equals(other.groupType)
                    && groupValue.equals(other.groupValue)
                    && rraoType.equals(other.rraoType);
        }

        @Override
        public int hashCode() {
            int result = groupType.hashCode();
            result = 31 * result + groupValue.hashCode();
            result = 31 * result + rraoType.hashCode();
            return result;
        }
    }
}
