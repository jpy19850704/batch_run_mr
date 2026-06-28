package com.zcyh.mr.springboot.mybatis.engineresultdb;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.SelectProvider;

import java.util.List;
import java.util.Map;

/**
 * 元数据查询 Mapper。
 */
@Mapper
public interface MetadataQueryMapper {

    @Select("SELECT BATCH_ID, DATA_DATE, COUNT(*) AS ROW_COUNT FROM TB_OUT_TRADE_FRTB_SENSITIVITY_DETAIL GROUP BY BATCH_ID, DATA_DATE ORDER BY BATCH_ID DESC")
    List<Map<String, Object>> listBatches();

    @SelectProvider(type = MetadataQuerySqlProvider.class, method = "listDomains")
    List<String> listDomains(
            @Param("tableName") String tableName,
            @Param("columnName") String columnName,
            @Param("batchId") String batchId,
            @Param("dataDate") String dataDate
    );

    @Select("SELECT SCENARIO_ID, SCENARIO_NAME, COUNT(*) AS ROW_COUNT FROM TB_OUT_TRADE_SCENARIO_RESULT_DETAIL WHERE BATCH_ID = #{batchId} AND DATA_DATE = #{dataDate} GROUP BY SCENARIO_ID, SCENARIO_NAME ORDER BY SCENARIO_ID")
    List<Map<String, Object>> listScenarios(@Param("batchId") String batchId, @Param("dataDate") String dataDate);

    @Select("SELECT DISTINCT INSTRUMENT_ID, PRODUCT_CODE FROM TB_OUT_TRADE_RESULT_DETAIL WHERE BATCH_ID = #{batchId} AND DATA_DATE = #{dataDate} ORDER BY INSTRUMENT_ID")
    List<Map<String, Object>> listInstrumentIds(@Param("batchId") String batchId, @Param("dataDate") String dataDate);
}
