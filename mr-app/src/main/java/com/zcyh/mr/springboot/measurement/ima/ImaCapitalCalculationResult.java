package com.zcyh.mr.springboot.measurement.ima;

import com.zcyh.mr.frtbima.model.ImaCapitalResult;
import com.zcyh.mr.frtbima.model.ImaEsResultDetail;
import com.zcyh.mr.frtbima.model.ImaNmrfResult;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 单条IMA规则的资本计算结果。
 */
public final class ImaCapitalCalculationResult {
    private final List<ImaCapitalResult> capitalResults;
    private final List<ImaEsResultDetail> esResultDetails;
    private final List<ImaNmrfResult> nmrfResults;

    ImaCapitalCalculationResult(
            List<ImaCapitalResult> capitalResults,
            List<ImaEsResultDetail> esResultDetails,
            List<ImaNmrfResult> nmrfResults) {
        this.capitalResults = immutableCopy(capitalResults);
        this.esResultDetails = immutableCopy(esResultDetails);
        this.nmrfResults = immutableCopy(nmrfResults);
    }

    public List<ImaCapitalResult> getCapitalResults() {
        return capitalResults;
    }

    public List<ImaEsResultDetail> getEsResultDetails() {
        return esResultDetails;
    }

    public List<ImaNmrfResult> getNmrfResults() {
        return nmrfResults;
    }

    private static <T> List<T> immutableCopy(List<T> values) {
        return Collections.unmodifiableList(new ArrayList<T>(values));
    }
}
