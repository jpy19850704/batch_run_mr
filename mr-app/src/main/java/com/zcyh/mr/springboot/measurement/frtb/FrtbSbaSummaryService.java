package com.zcyh.mr.springboot.measurement.frtb;

import static com.zcyh.mr.springboot.support.RequestParseSupport.trimToNull;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONWriter;
import com.zcyh.mr.frtbsa.sba.core.FrtbResultMapper;
import com.zcyh.mr.frtbsa.sba.pojo.FRTBClassResult;
import com.zcyh.mr.frtbsa.sba.pojo.FRTBPosResult;
import com.zcyh.mr.springboot.measurement.frtb.FrtbSbaSummaryRequest;
import com.zcyh.mr.springboot.measurement.SummaryCleanupMode;
import com.zcyh.mr.springboot.output.db.CalcRuleMetaPersistService;
import com.zcyh.mr.springboot.output.db.FrtbSbaResultPersistService;
import com.zcyh.mr.springboot.output.db.RuleScopedDeleteSupport;
import com.zcyh.mr.springboot.support.DorisCsvStreamLoadBuffer;
import com.zcyh.mr.springboot.support.DorisStreamLoadService;
import com.zcyh.mr.springboot.support.ResultPersistTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * FRTB SBA 汇总服务。
 * 从批次敏感性明细生成 SBA 汇总结果，并按需要执行结果落库。
 */
@Service
public class FrtbSbaSummaryService {
    private static final Logger log = LoggerFactory.getLogger(FrtbSbaSummaryService.class);
    private static final String CALC_TYPE_FRTB_SBA = "FRTB_SBA";
    private static final int DECOMP_DETAIL_BATCH_SIZE = 10000;
    private static final String DECOMP_DETAIL_TABLE = "TB_OUT_FRTB_SBA_DECOMP_DETAIL";
    private static final String DECOMP_DETAIL_COLUMNS =
            "BATCH_ID,DATA_DATE,RULE_ID,GROUP_TYPE,GROUP_VALUE,"
                    + "RISK_FACTOR_CLASS,RISK_FACTOR_BUCKET,RISK_FACTOR_ID,"
                    + "RISK_FACTOR_VERTEX_1,RISK_FACTOR_VERTEX_2,RISK_FACTOR_TYPE,"
                    + "SENSITIVITY_TYPE,UNIT_CONTRIBUTION,CREATED_AT,UPDATED_AT";

    private final FrtbSbaDbRunnerService frtbSbaDbRunnerService;
    private final FrtbSbaResultPersistService frtbSbaResultPersistService;
    private final FrtbResultMapper frtbResultMapper;
    private final CalcRuleMetaPersistService calcRuleMetaPersistService;
    private final JdbcTemplate engineResultDbJdbcTemplate;
    private final DorisStreamLoadService dorisStreamLoadService;

    public FrtbSbaSummaryService(FrtbSbaDbRunnerService frtbSbaDbRunnerService,
                                 FrtbSbaResultPersistService frtbSbaResultPersistService,
                                 FrtbResultMapper frtbResultMapper,
                                 CalcRuleMetaPersistService calcRuleMetaPersistService,
                                 @Qualifier("engineResultDbJdbcTemplate") JdbcTemplate engineResultDbJdbcTemplate,
                                 DorisStreamLoadService dorisStreamLoadService) {
        this.frtbSbaDbRunnerService = frtbSbaDbRunnerService;
        this.frtbSbaResultPersistService = frtbSbaResultPersistService;
        this.frtbResultMapper = frtbResultMapper;
        this.calcRuleMetaPersistService = calcRuleMetaPersistService;
        this.engineResultDbJdbcTemplate = engineResultDbJdbcTemplate;
        this.dorisStreamLoadService = dorisStreamLoadService;
    }

    @SuppressWarnings("unchecked")
    public JSONObject summarize(FrtbSbaSummaryRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("request 不能为空");
        }
        String batchId = request.getBatchId();
        String dataDate = request.getDataDate();
        boolean needDecompose = request.isNeedDecompose();
        int threadCount = request.getThreadCount();
        boolean persistResult = request.isPersistResult();

        JSONArray results = new JSONArray();
        List<RuleOutput> ruleOutputs = new ArrayList<RuleOutput>();
        for (String ruleId : request.getRuleIds()) {
            com.zcyh.mr.springboot.measurement.aggregation.AggregationRule ruleDefinition =
                    frtbSbaDbRunnerService.loadRuleDefinition(ruleId);
            JSONObject ruleJson = JSON.parseObject(JSON.toJSONString(
                    ruleDefinition, JSONWriter.Feature.WriteBigDecimalAsPlain));
            String raw = JSON.toJSONString(
                    frtbSbaDbRunnerService.calculate(
                            batchId,
                            dataDate,
                            Collections.singletonList(ruleDefinition),
                            needDecompose,
                            threadCount),
                    JSONWriter.Feature.WriteBigDecimalAsPlain);
            Object parsed = JSON.parse(raw);

            if (persistResult) {
                Map<String, Object> batchResult = JSON.parseObject(raw, Map.class);
                ruleOutputs.add(new RuleOutput(ruleId, ruleJson, batchResult));
            }

            JSONObject resultItem = new JSONObject();
            resultItem.put("rule_id", ruleId);
            resultItem.put("source_type", "db");
            resultItem.put("summary", parsed);
            results.add(resultItem);
        }
        if (persistResult) {
            cleanup(batchId, dataDate, request);
            for (RuleOutput output : ruleOutputs) {
                persistRuleResult(batchId, dataDate, output.ruleId, output.batchResult);
                persistRuleMeta(batchId, dataDate, output.ruleId, output.ruleJson);
            }
        }

        JSONObject response = new JSONObject();
        response.put("batch_id", batchId);
        response.put("data_date", dataDate);
        response.put("results", results);
        return response;
    }

    @SuppressWarnings("unchecked")
    private void persistRuleResult(String batchId,
                                   String dataDate,
                                   String ruleId,
                                   Map<String, Object> batchResult) {
        if (batchResult == null || batchResult.isEmpty()) {
            return;
        }
        Map<String, Object> rawDetails = requireRawDetails(batchResult.get("__raw_details"));
        List<FRTBPosResult> decompDetails = new ArrayList<FRTBPosResult>();
        for (Map.Entry<String, Object> entry : batchResult.entrySet()) {
            String taskKey = entry.getKey();
            if ("__raw_details".equals(taskKey) || "__raw_detail_schema".equals(taskKey)) {
                continue;
            }
            Map<String, Object> rawDetail = requireRawDetail(rawDetails, taskKey);
            String detailRuleId = requireRawDetailText(rawDetail, "ruleId", taskKey);
            String groupType = requireRawDetailText(rawDetail, "groupType", taskKey);
            String groupValue = requireRawDetailText(rawDetail, "groupValue", taskKey);
            Object calcResult = entry.getValue();
            if (!(calcResult instanceof Map)) {
                throw new IllegalArgumentException("FRTB SBA 任务结果格式异常: taskKey=" + taskKey);
            }
            Map<String, List<?>> pojoResult = frtbResultMapper.buildResults(
                    (Map<String, Object>) calcResult, detailRuleId, groupType, groupValue);
            List<?> classResults = pojoResult.get("classResults");
            if (classResults != null && !classResults.isEmpty()) {
                frtbSbaResultPersistService.persist(
                        (List<FRTBClassResult>) classResults, batchId, dataDate, ruleId);
            }
            List<?> posResults = pojoResult.get("posResults");
            if (posResults != null && !posResults.isEmpty()) {
                decompDetails.addAll((List<FRTBPosResult>) posResults);
            }
        }
        if (!decompDetails.isEmpty()) {
            persistDecompDetails(decompDetails, batchId, dataDate, ruleId);
        }
    }

    private void persistDecompDetails(List<FRTBPosResult> posResults, String batchId, String dataDate, String ruleId) {
        if (posResults == null || posResults.isEmpty()) {
            log.warn("FRTB SBA Decomp 明细为空，跳过落库: batchId={}, ruleId={}", batchId, ruleId);
            return;
        }

        String now = ResultPersistTime.nowText();
        DorisCsvStreamLoadBuffer buffer = new DorisCsvStreamLoadBuffer(
                dorisStreamLoadService,
                DECOMP_DETAIL_TABLE,
                DECOMP_DETAIL_COLUMNS,
                "frtb_sba_decomp_" + batchId,
                DECOMP_DETAIL_BATCH_SIZE);

        int rows = 0;
        for (FRTBPosResult pr : posResults) {
            if (pr == null) {
                continue;
            }
            if ("ALL".equalsIgnoreCase(pr.getRiskFactorClass())) {
                continue;
            }
            buffer.appendRow(
                    batchId, dataDate, ruleId,
                    pr.getGroupType(), pr.getGroupValue(),
                    pr.getRiskFactorClass(), pr.getRiskFactorBucket(), pr.getRiskFactorId(),
                    pr.getRiskFactorVertex1(), pr.getRiskFactorVertex2(), pr.getRiskFactorType(),
                    pr.getSensitivityType(),
                    DorisCsvStreamLoadBuffer.decimalText(decVal(pr.getUnitContribution())),
                    now, now);
            rows++;
        }
        buffer.flush();
        log.info("FRTB SBA Decomp 明细落库完成: batchId={}, ruleId={}, rows={}", batchId, ruleId, rows);
    }

    private void deleteDecompDetailByBatchAndDataDate(String batchId, String dataDate) {
        int deleted = engineResultDbJdbcTemplate.update(
                "DELETE FROM TB_OUT_FRTB_SBA_DECOMP_DETAIL WHERE BATCH_ID = ? AND DATA_DATE=?",
                batchId, com.zcyh.mr.springboot.support.ResultDbDateSupport.sqlDate(dataDate));
        if (deleted > 0) {
            log.info("清理 FRTB SBA Decomp 历史结果: batchId={}, dataDate={}, deleted={}", batchId, dataDate, deleted);
        }
    }

    private void deleteDecompDetailByRuleIds(String batchId, String dataDate, List<String> ruleIds) {
        int deleted = RuleScopedDeleteSupport.deleteByRuleIds(
                engineResultDbJdbcTemplate, DECOMP_DETAIL_TABLE, batchId, dataDate, ruleIds);
        if (deleted > 0) {
            log.info("按规则清理 FRTB SBA Decomp 历史结果: batchId={}, dataDate={}, ruleIds={}, deleted={}",
                    batchId, dataDate, ruleIds, deleted);
        }
    }

    private void cleanup(String batchId, String dataDate, FrtbSbaSummaryRequest request) {
        if (request.getCleanupMode() == SummaryCleanupMode.FULL) {
            frtbSbaResultPersistService.deleteByBatchAndDataDate(batchId, dataDate);
            deleteDecompDetailByBatchAndDataDate(batchId, dataDate);
            calcRuleMetaPersistService.deleteByBatchAndCalcType(batchId, dataDate, CALC_TYPE_FRTB_SBA);
            return;
        }
        if (request.getCleanupMode() != SummaryCleanupMode.RULE) {
            throw new IllegalArgumentException("cleanupMode 不能为空");
        }
        frtbSbaResultPersistService.deleteByBatchDataDateAndRuleIds(
                batchId, dataDate, request.getRuleIds());
        deleteDecompDetailByRuleIds(batchId, dataDate, request.getRuleIds());
        calcRuleMetaPersistService.deleteByBatchCalcTypeAndRuleIds(
                batchId, dataDate, CALC_TYPE_FRTB_SBA, request.getRuleIds());
    }

    private static BigDecimal decVal(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> requireRawDetails(Object rawDetails) {
        if (!(rawDetails instanceof Map)) {
            throw new IllegalArgumentException("FRTB SBA 结果缺少 __raw_details，无法确定真实维度类型");
        }
        return (Map<String, Object>) rawDetails;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> requireRawDetail(Map<String, Object> rawDetails, String taskKey) {
        if (rawDetails == null || rawDetails.isEmpty()) {
            throw new IllegalArgumentException("FRTB SBA 结果缺少 __raw_details，无法确定真实维度类型");
        }
        Object detail = rawDetails.get(taskKey);
        if (!(detail instanceof Map)) {
            throw new IllegalArgumentException("FRTB SBA 结果缺少任务维度信息: taskKey=" + taskKey);
        }
        return (Map<String, Object>) detail;
    }

    private static String requireRawDetailText(Map<String, Object> rawDetail, String field, String taskKey) {
        Object value = rawDetail.get(field);
        String text = value == null ? null : trimToNull(String.valueOf(value));
        if (text == null) {
            throw new IllegalArgumentException("FRTB SBA 任务维度字段缺失: taskKey=" + taskKey + ", field=" + field);
        }
        return text;
    }

    /**
     * 将 FRTB SBA 规则的完整 JSON 写入规则元数据表。
     */
    private void persistRuleMeta(String batchId, String dataDate, String ruleId, JSONObject ruleJson) {
        try {
            String ruleJsonStr = ruleJson.toJSONString(JSONWriter.Feature.WriteBigDecimalAsPlain);
            calcRuleMetaPersistService.persist(batchId, dataDate, CALC_TYPE_FRTB_SBA, ruleId, ruleJsonStr);
        } catch (Exception e) {
            log.warn("FRTB SBA 规则元数据落库失败（不影响主流程）: {}", e.getMessage(), e);
        }
    }

    private static class RuleOutput {
        private final String ruleId;
        private final JSONObject ruleJson;
        private final Map<String, Object> batchResult;

        private RuleOutput(String ruleId, JSONObject ruleJson, Map<String, Object> batchResult) {
            this.ruleId = ruleId;
            this.ruleJson = ruleJson;
            this.batchResult = batchResult;
        }
    }
}
