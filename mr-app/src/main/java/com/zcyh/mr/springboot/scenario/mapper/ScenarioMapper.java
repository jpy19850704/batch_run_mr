package com.zcyh.mr.springboot.scenario.mapper;

import org.apache.ibatis.annotations.Param;

import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * 情景业务库 Mapper。
 */
public interface ScenarioMapper {

    List<Map<String, Object>> selectScenario(@Param("scenarioIdList") String scenarioIdList);

    List<Map<String, Object>> selectScenarioMpByScenarioIdList(@Param("scenarioIdList") String scenarioIdList);

    List<Map<String, Object>> selectMcScenarioMpByScenarioIdList(@Param("scenarioIdList") String scenarioIdList);

    List<Map<String, Object>> selectHistoryScenarioMpByScenarioIdList(@Param("scenarioIdList") String scenarioIdList);

    List<Map<String, Object>> selectIrData(@Param("scenarioId") String scenarioId, @Param("startDate") Date startDate, @Param("endDate") Date endDate);

    List<Map<String, Object>> selectFxData(@Param("scenarioId") String scenarioId, @Param("startDate") Date startDate, @Param("endDate") Date endDate);

    List<Map<String, Object>> selectCommData(@Param("scenarioId") String scenarioId, @Param("startDate") Date startDate, @Param("endDate") Date endDate);

    List<Map<String, Object>> selectEqData(@Param("scenarioId") String scenarioId, @Param("startDate") Date startDate, @Param("endDate") Date endDate);

    List<Map<String, Object>> selectFxVolData(@Param("scenarioId") String scenarioId, @Param("startDate") Date startDate, @Param("endDate") Date endDate);

    List<Map<String, Object>> selectIrVolData(@Param("scenarioId") String scenarioId, @Param("startDate") Date startDate, @Param("endDate") Date endDate);

    List<Map<String, Object>> selectCommVolData(@Param("scenarioId") String scenarioId, @Param("startDate") Date startDate, @Param("endDate") Date endDate);

    List<Map<String, Object>> selectEqVolData(@Param("scenarioId") String scenarioId, @Param("startDate") Date startDate, @Param("endDate") Date endDate);

    List<Map<String, Object>> getHolidayDate(@Param("calPEK") String calPEK);

    int deleteScenario(@Param("scenarioIdList") String scenarioIdList, @Param("user") String user, @Param("dataDate") Date dataDate);

    int insertScenario(@Param("list") List<Map<String, Object>> list);
}
