package com.zcyh.mr.saccr.addon;

import com.zcyh.mr.saccr.params.SaccrSupervisoryParams;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * 信用（Credit）资产类别 AddOn 计算器（对应文档 4.3 节）。
 *
 * <p>分组规则：每个参考主体（Reference Entity）为一个对冲集合。
 *
 * <p>单因子模型公式：
 * <pre>
 *   WEN_k = SF_k × ΣD_i(k)
 *   AddOn(Credit) = sqrt( (Σ ρ_k × WEN_k)² + Σ (1 - ρ_k²) × WEN_k² )
 * </pre>
 * 第一项为系统性项，第二项为特质性项。
 */
public final class CreditAddOnCalc {

    private static final Logger log = LoggerFactory.getLogger(CreditAddOnCalc.class);

    private CreditAddOnCalc() {
    }

    /**
     * 参考主体描述信息，用于确定 SF 和 ρ。
     */
    public static class EntityInfo {
        /** 信用质量输入：单一主体可传外部评级；指数仅接受 IG/SG */
        public String creditQuality;
        /** 是否指数产品 */
        public boolean isIndex;

        public EntityInfo(String creditQuality, boolean isIndex) {
            this.creditQuality = creditQuality;
            this.isIndex = isIndex;
        }
    }

    /**
     * 计算信用 AddOn。
     *
     * @param netDiPerEntity  key = 参考主体标识，value = 该主体内 ΣD_i（已带符号）
     * @param entityInfoMap   key = 参考主体标识，value = EntityInfo（SF/ρ 查询依据）
     * @return AddOn(Credit)
     */
    public static double calc(Map<String, Double> netDiPerEntity,
                              Map<String, EntityInfo> entityInfoMap) {
        double systemicSum = 0.0;   // Σ ρ_k × WEN_k
        double idioSum = 0.0;        // Σ (1-ρ_k²) × WEN_k²

        for (Map.Entry<String, Double> entry : netDiPerEntity.entrySet()) {
            String entity = entry.getKey();
            double netDi = entry.getValue();
            EntityInfo info = entityInfoMap.get(entity);
            if (info == null) {
                // 缺少主体信息时按保守口径降级为 SG，并记录告警。
                log.warn("信用主体 {} 缺少 EntityInfo，已按 SG 口径降级（single=BB 档）", entity);
                info = new EntityInfo("SG", false);
            }

            double sf;
            double rho;
            if (info.isIndex) {
                Boolean isIg = SaccrSupervisoryParams.resolveCreditIndexIgFlag(info.creditQuality);
                if (isIg == null) {
                    log.warn("信用指数主体 {} 的信用档位无法识别（value={}），已按 SG 降级",
                            entity, info.creditQuality);
                    isIg = Boolean.FALSE;
                }
                sf = SaccrSupervisoryParams.getCreditIndexSf(isIg);
                rho = SaccrSupervisoryParams.RHO_CREDIT_INDEX;
            } else {
                SaccrSupervisoryParams.CreditSingleBucket bucket =
                        SaccrSupervisoryParams.resolveCreditSingleBucket(info.creditQuality);
                if (bucket == null) {
                    // 单一主体评级无法识别时，按 SG 基准（BB 档）保守退化。
                    log.warn("信用单一主体 {} 的评级无法识别（value={}），已按 SG 基准（BB 档）降级",
                            entity, info.creditQuality);
                    bucket = SaccrSupervisoryParams.CreditSingleBucket.BB;
                }
                sf = SaccrSupervisoryParams.getCreditSingleSf(bucket);
                rho = SaccrSupervisoryParams.RHO_CREDIT_SINGLE;
            }

            double wen = sf * netDi;
            systemicSum += rho * wen;
            idioSum += (1.0 - rho * rho) * wen * wen;
        }

        return Math.sqrt(systemicSum * systemicSum + idioSum);
    }
}
