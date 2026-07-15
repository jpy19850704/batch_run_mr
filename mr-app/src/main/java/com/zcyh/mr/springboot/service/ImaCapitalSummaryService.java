package com.zcyh.mr.springboot.service;

import com.alibaba.fastjson2.JSONObject;
import com.zcyh.mr.frtbima.model.ImaCapitalResult;
import com.zcyh.mr.frtbima.model.ImaEsResultDetail;
import com.zcyh.mr.frtbima.model.ImaNmrfResult;
import com.zcyh.mr.frtbima.model.NmrfPnlRecord;
import com.zcyh.mr.frtbima.model.SubsetPnlRecord;
import com.zcyh.mr.springboot.ima.ImaCapitalCalculationResult;
import com.zcyh.mr.springboot.ima.ImaCapitalCalculationService;
import com.zcyh.mr.springboot.ima.ImaCapitalDimensionService;
import com.zcyh.mr.springboot.ima.ImaCapitalPnlRepository;
import com.zcyh.mr.springboot.ima.ImaCapitalRuleRepository;
import com.zcyh.mr.springboot.model.RuleSummaryRequest;
import com.zcyh.mr.springboot.model.SummaryCleanupMode;
import com.zcyh.mr.springboot.out.db.CalcRuleMetaPersistService;
import com.zcyh.mr.springboot.out.db.ImaCapitalResultPersistService;
import com.zcyh.mr.springboot.out.db.ImaEsResultDetailPersistService;
import com.zcyh.mr.springboot.out.db.ImaNmrfResultPersistService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * IMA资本汇总编排服务。
 */
@Service
public class ImaCapitalSummaryService {
    private static final Logger log = LoggerFactory.getLogger(ImaCapitalSummaryService.class);
    private static final String RULE_TYPE_IMA = "IMA";

    private final ImaCapitalPnlRepository pnlRepository;
    private final ImaCapitalRuleRepository ruleRepository;
    private final ImaCapitalDimensionService capitalDimensionService;
    private final ImaCapitalCalculationService calculationService;
    private final CalcRuleMetaPersistService calcRuleMetaPersistService;
    private final ImaCapitalResultPersistService capitalPersistService;
    private final ImaEsResultDetailPersistService esResultDetailPersistService;
    private final ImaNmrfResultPersistService nmrfResultPersistService;

    public ImaCapitalSummaryService(
            ImaCapitalPnlRepository pnlRepository,
            ImaCapitalRuleRepository ruleRepository,
            ImaCapitalDimensionService capitalDimensionService,
            ImaCapitalCalculationService calculationService,
            CalcRuleMetaPersistService calcRuleMetaPersistService,
            ImaCapitalResultPersistService capitalPersistService,
            ImaEsResultDetailPersistService esResultDetailPersistService,
            ImaNmrfResultPersistService nmrfResultPersistService) {
        this.pnlRepository = pnlRepository;
        this.ruleRepository = ruleRepository;
        this.capitalDimensionService = capitalDimensionService;
        this.calculationService = calculationService;
        this.calcRuleMetaPersistService = calcRuleMetaPersistService;
        this.capitalPersistService = capitalPersistService;
        this.esResultDetailPersistService = esResultDetailPersistService;
        this.nmrfResultPersistService = nmrfResultPersistService;
    }

    public JSONObject summarize(RuleSummaryRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("request 不能为空");
        }
        String batchId = request.getBatchId();
        String dataDate = request.getDataDate();
        List<SubsetPnlRecord> subsetPnls = pnlRepository.queryModellablePnl(batchId, dataDate);
        List<NmrfPnlRecord> nmrfPnls = pnlRepository.queryNmrfPnl(batchId, dataDate);

        log.info("IMA 资本汇总开始: batchId={}, modellableRows={}, nmrfRows={}",
                batchId, subsetPnls.size(), nmrfPnls.size());

        List<ImaCapitalResult> capitalResults = new ArrayList<ImaCapitalResult>();
        List<ImaEsResultDetail> esResultDetails = new ArrayList<ImaEsResultDetail>();
        List<ImaNmrfResult> nmrfResults = new ArrayList<ImaNmrfResult>();
        List<ImaCapitalRuleRepository.LoadedRule> loadedRules =
                new ArrayList<ImaCapitalRuleRepository.LoadedRule>();
        LocalDate localDataDate = LocalDate.parse(dataDate, DateTimeFormatter.BASIC_ISO_DATE);
        for (String ruleId : request.getRuleIds()) {
            ImaCapitalRuleRepository.LoadedRule loadedRule = ruleRepository.loadImaRule(ruleId);
            calculationService.validateImaRule(loadedRule.getRule());
            loadedRules.add(loadedRule);
            ImaCapitalCalculationResult result = calculationService.calculateRule(
                    loadedRule.getRule(),
                    capitalDimensionService.buildDimensionRows(localDataDate, loadedRule.getRule()),
                    subsetPnls,
                    nmrfPnls,
                    Collections.emptyMap(),
                    Collections.emptySet(),
                    Collections.emptySet(),
                    dataDate,
                    batchId);
            capitalResults.addAll(result.getCapitalResults());
            esResultDetails.addAll(result.getEsResultDetails());
            nmrfResults.addAll(result.getNmrfResults());
        }

        if (request.isPersistResult()) {
            persistResults(request, loadedRules, capitalResults, esResultDetails, nmrfResults);
        }
        log.info("IMA Phase2 完成: batchId={}, ruleCount={}, resultRows={}",
                batchId, request.getRuleIds().size(), capitalResults.size());

        JSONObject response = new JSONObject();
        response.put("batch_id", batchId);
        response.put("data_date", dataDate);
        response.put("results", capitalResults);
        return response;
    }

    private void persistResults(
            RuleSummaryRequest request,
            List<ImaCapitalRuleRepository.LoadedRule> loadedRules,
            List<ImaCapitalResult> capitalResults,
            List<ImaEsResultDetail> esResultDetails,
            List<ImaNmrfResult> nmrfResults) {
        String batchId = request.getBatchId();
        String dataDate = request.getDataDate();
        cleanupResults(request);
        capitalPersistService.persist(capitalResults);
        esResultDetailPersistService.persist(esResultDetails);
        nmrfResultPersistService.persist(nmrfResults);
        for (ImaCapitalRuleRepository.LoadedRule loadedRule : loadedRules) {
            calcRuleMetaPersistService.persist(
                    batchId,
                    dataDate,
                    RULE_TYPE_IMA,
                    loadedRule.getRule().getRuleId(),
                    loadedRule.getRuleJson());
        }
    }

    private void cleanupResults(RuleSummaryRequest request) {
        String batchId = request.getBatchId();
        String dataDate = request.getDataDate();
        if (request.getCleanupMode() == SummaryCleanupMode.FULL) {
            capitalPersistService.deleteByBatchAndDataDate(batchId, dataDate);
            esResultDetailPersistService.deleteByBatchAndDataDate(batchId, dataDate);
            nmrfResultPersistService.deleteByBatchAndDataDate(batchId, dataDate);
            calcRuleMetaPersistService.deleteByBatchAndCalcType(batchId, dataDate, RULE_TYPE_IMA);
            return;
        }
        if (request.getCleanupMode() != SummaryCleanupMode.RULE) {
            throw new IllegalArgumentException("cleanupMode 不能为空");
        }
        capitalPersistService.deleteByBatchDataDateAndRuleIds(batchId, dataDate, request.getRuleIds());
        esResultDetailPersistService.deleteByBatchDataDateAndRuleIds(batchId, dataDate, request.getRuleIds());
        nmrfResultPersistService.deleteByBatchDataDateAndRuleIds(batchId, dataDate, request.getRuleIds());
        calcRuleMetaPersistService.deleteByBatchCalcTypeAndRuleIds(
                batchId, dataDate, RULE_TYPE_IMA, request.getRuleIds());
    }
}
