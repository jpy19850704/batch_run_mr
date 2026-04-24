package com.zcyh.mr.springboot.scenario;

import com.zcyh.mr.scenario.model.ScenarioDefinition;
import com.zcyh.mr.scenario.model.ScenarioMarketSeries;
import com.zcyh.mr.springboot.scenario.mapper.ScenarioMapper;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 情景历史市场数据加载器。
 *
 * <p>
 * 该类负责综合全部情景定义推导查询窗口，并按曲线类型加载历史市场数据。
 */
public class ScenarioHistoricalMarketLoader {

    public ScenarioHistoricalMarketLoader(
            ScenarioMapper scenarioMapper,
            com.zcyh.mr.core.Calendar holidayCalendar) {
        if (scenarioMapper == null) {
            throw new IllegalArgumentException("scenarioMapper 不能为空");
        }
    }

    /**
     * 加载历史市场数据。
     */
    public Map<String, Map<LocalDate, List<ScenarioMarketSeries>>> load(
            String scenarioId,
            LocalDate valuationDate,
            List<ScenarioDefinition> definitions) {
        throw new UnsupportedOperationException("旧情景历史市场数据加载器已停用，请使用 MarketInputScenarioLoader");
    }

}
