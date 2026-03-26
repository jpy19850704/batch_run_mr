package com.zcyh.mr.springboot.scenario;

import com.zcyh.mr.scenario.ScenarioRangeResolver;
import com.zcyh.mr.scenario.model.ScenarioDefinition;

import java.sql.Date;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 情景市场查询计划器。
 *
 * <p>
 * 第一阶段按 {@code curveType + curveCode} 生成查询计划，并为每个计划保留覆盖区间，
 * 以便后续继续演进为多段区间策略。
 */
public class ScenarioMarketQueryPlanner {

    private final ScenarioRangeResolver rangeResolver;

    public ScenarioMarketQueryPlanner() {
        this(null);
    }

    public ScenarioMarketQueryPlanner(com.zcyh.mr.core.Calendar holidayCalendar) {
        this.rangeResolver = new ScenarioRangeResolver(holidayCalendar);
    }

    /**
     * 生成历史市场查询计划。
     */
    public List<QueryPlan> planHistorical(List<ScenarioDefinition> definitions, LocalDate valuationDate) {
        Map<QueryKey, QueryPlan> plans = new LinkedHashMap<QueryKey, QueryPlan>();
        if (definitions == null || definitions.isEmpty() || valuationDate == null) {
            return new ArrayList<QueryPlan>();
        }

        for (ScenarioDefinition definition : definitions) {
            DateRange range = resolveHistoricalRange(definition, valuationDate);
            if (range == null) {
                continue;
            }

            QueryKey key = new QueryKey(definition.getCurveType(), definition.getCurveCode());
            QueryPlan plan = plans.computeIfAbsent(key, QueryPlan::new);
            plan.addRiskGroupId(definition.getRiskGroupId());
            plan.mergeRange(range);
        }

        return new ArrayList<QueryPlan>(plans.values());
    }

    private DateRange resolveHistoricalRange(ScenarioDefinition definition, LocalDate valuationDate) {
        ScenarioRangeResolver.ResolvedRange resolvedRange = rangeResolver.resolve(definition, valuationDate);
        if (resolvedRange == null) {
            return null;
        }
        return new DateRange(
                toSqlDate(resolvedRange.getDataSearchStartDate()),
                toSqlDate(resolvedRange.getDataSearchEndDate()));
    }

    /**
     * 查询键。
     */
    public static class QueryKey {
        private final String curveType;
        private final String curveCode;

        public QueryKey(String curveType, String curveCode) {
            this.curveType = normalize(curveType);
            this.curveCode = normalize(curveCode);
        }

        public String getCurveType() {
            return curveType;
        }

        public String getCurveCode() {
            return curveCode;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (!(obj instanceof QueryKey)) {
                return false;
            }
            QueryKey other = (QueryKey) obj;
            return Objects.equals(curveType, other.curveType)
                    && Objects.equals(curveCode, other.curveCode);
        }

        @Override
        public int hashCode() {
            return Objects.hash(curveType, curveCode);
        }
    }

    /**
     * 查询计划。
     */
    public static class QueryPlan {
        private final QueryKey key;
        private final Set<String> riskGroupIds = new LinkedHashSet<String>();
        private final List<DateRange> ranges = new ArrayList<DateRange>();

        public QueryPlan(QueryKey key) {
            this.key = key;
        }

        public QueryKey getKey() {
            return key;
        }

        public Set<String> getRiskGroupIds() {
            return riskGroupIds;
        }

        public List<DateRange> getRanges() {
            return ranges;
        }

        public void addRiskGroupId(String riskGroupId) {
            String normalized = normalize(riskGroupId);
            if (normalized != null && !normalized.isEmpty()) {
                riskGroupIds.add(normalized);
            }
        }

        public void mergeRange(DateRange range) {
            if (range == null) {
                return;
            }
            if (ranges.isEmpty()) {
                ranges.add(range);
                return;
            }
            DateRange current = ranges.get(0);
            Date startDate = current.getStartDate().before(range.getStartDate()) ? current.getStartDate() : range.getStartDate();
            Date endDate = current.getEndDate().after(range.getEndDate()) ? current.getEndDate() : range.getEndDate();
            ranges.set(0, new DateRange(startDate, endDate));
        }
    }

    /**
     * 查询时间区间。
     */
    public static class DateRange {
        private final Date startDate;
        private final Date endDate;

        public DateRange(Date startDate, Date endDate) {
            this.startDate = startDate;
            this.endDate = endDate;
        }

        public Date getStartDate() {
            return startDate;
        }

        public Date getEndDate() {
            return endDate;
        }
    }

    private static String normalize(String value) {
        return value == null ? null : value.trim();
    }

    private Date toSqlDate(LocalDate value) {
        return value == null ? null : Date.valueOf(value);
    }
}
