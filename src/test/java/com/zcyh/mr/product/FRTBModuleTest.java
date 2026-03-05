package com.zcyh.mr.product;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.zcyh.mr.loader.FileUtils;
import com.zcyh.mr.module.frtb.sa.core.*;
import com.zcyh.mr.module.frtb.sa.pojo.*;
import com.zcyh.mr.module.frtb.sa.util.*;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * FRTBModuleTest
 *
 * @author cmh
 * @date 2024/10/28
 */
public class FRTBModuleTest {
    private static String getChange(String term){
        if(term.endsWith("M")){
            String xx=term.substring(0,term.length()-1);
            double x=Integer.valueOf(xx)/12.0;
            return String.valueOf(Integer.valueOf(xx)/12.0);
        }else{
            return term.substring(0,term.length()-1);
        }

    }
    public static void main(String[] args) {
/*        String data = FileUtils.loadData("data/frtb/param.json");
        FileUtils.setData(data,"src\\main\\resources\\data\\frtb\\param1.json");*/
        long start =System.currentTimeMillis();
        String data = FileUtils.loadData("data/Frtb.json");
        JSONObject jo = JSON.parseObject(data);
        JSONArray rows = jo.getJSONArray("rows");

        List <FRTBModel> rawList=JSON.parseArray(rows.toJSONString(),FRTBModel.class);

/*testCalc(rawList);*/

        String xxxx=new FRTBAnalyse().calc(rawList);
        System.out.println("returns:" + xxxx);
        long times =System.currentTimeMillis()-start;

        System.out.println("times:" + times);

    }


    public static void testCalc(List <FRTBModel> rawList) {
       /* String data = FileUtils.loadData("data/Frtb.json");
        JSONObject jo = JSON.parseObject(data);
        JSONArray rows = jo.getJSONArray("rows");

        List <FRTBModel> rawList=JSONArray.parseArray(rows.toJSONString(),FRTBModel.class);*/

        Map <String ,List<FRTBModel>> checkList=Utils.moduleCheck(rawList);
        List <FRTBModel> list=checkList.get("checked");

        /**
         * 根据 groupType groupValue riskFactorClass 对List 进行分组
         */

        Function<FRTBModel,String> compkeys=fRTBModel -> (fRTBModel.treeId+"@"+fRTBModel.groupType+"@"+fRTBModel.groupValue);
        //分组
        Map<String,List<FRTBModel>> dss=list.stream().collect(Collectors.groupingBy(compkeys,Collectors.toList()));




        List <Map<String ,Object>> returns=new ArrayList<>();

        for(String key:dss.keySet()){
            System.out.println(key);
/*            if(!"PORTFOLIO@L8@PSB_TB_FMD_AT_ER_FED_EOC".equals(key)){
                continue;
            }*/
            String treeId=key.split("@")[0];
            String groupType=key.split("@")[1];
            String groupValue=key.split("@")[2];

            List<FRTBClassResult> calssList =new ArrayList<>();
            List <FRTBDecomp> riskfactorList=new ArrayList<>();
            Map<String,List<FRTBModel>> riskFactorClassMap=dss.get(key).stream().collect(Collectors.groupingBy(FRTBModel::getRiskFactorClass));

            //已验证 Delta Vega Curvature
            List <FRTBModel> girrList=riskFactorClassMap.get("GIRR");
            if(null !=girrList){

                String GIRRReturns=new FRTBModule().calc(girrList);
                FRTBClassResult girrClass=getFrtbClassResult(GIRRReturns,"GIRR",treeId,groupType,groupValue);
                calssList.add(girrClass);

                List <FRTBDecomp> riskfactor=getFRTBRiskfactor(GIRRReturns,girrClass.getMaxSign(),treeId,groupType,groupValue);
                riskfactorList.addAll(riskfactor);
            }

            List <FRTBModel> CSRList=riskFactorClassMap.get("CSR (non-sec)");
            if(null !=CSRList){
                //Delta #Vega:无 Curvature
                String CSRReturns=new CSRModule().calc(CSRList);
                FRTBClassResult csrClass=getFrtbClassResult(CSRReturns,"CSR (non-sec)",treeId,groupType,groupValue);
                calssList.add(csrClass);
                List <FRTBDecomp> riskfactor=getFRTBRiskfactor(CSRReturns,csrClass.getMaxSign(),treeId,groupType,groupValue);
                riskfactorList.addAll(riskfactor);
            }

            List <FRTBModel> CSRNCList=riskFactorClassMap.get("CSR (non-ctp)");
            if(null !=CSRNCList){
                // Delta #Vega:无 #Curvature:无
                String CSRNCreturns=new CSRNCModule().calc(CSRNCList);

                FRTBClassResult csrncClass=getFrtbClassResult(CSRNCreturns,"CSR (non-ctp)",treeId,groupType,groupValue);
                calssList.add(csrncClass);

                //riskfactor
                List <FRTBDecomp> riskfactor=getFRTBRiskfactor(CSRNCreturns,csrncClass.getMaxSign(),treeId,groupType,groupValue);
                riskfactorList.addAll(riskfactor);
            }



            List <FRTBModel> EQList=riskFactorClassMap.get("EQ");
            if(null !=EQList){
                //Delta Vega Curvature
                String EQReturns=new EQModule().calc(EQList);
                FRTBClassResult eqClass=getFrtbClassResult(EQReturns,"EQ",treeId,groupType,groupValue);
                calssList.add(eqClass);
                List <FRTBDecomp> riskfactor=getFRTBRiskfactor(EQReturns,eqClass.getMaxSign(),treeId,groupType,groupValue);
                riskfactorList.addAll(riskfactor);

            }

            //已验证 Delta  (Vega Curvature)无测试数据
            List <FRTBModel> CMTYList=riskFactorClassMap.get("CMTY");
            if(null !=CMTYList){

                String CMTYReturns=new CMTYModule().calc(CMTYList);
                FRTBClassResult cmtyClass=getFrtbClassResult(CMTYReturns,"CMTY",treeId,groupType,groupValue);
                calssList.add(cmtyClass);

                List <FRTBDecomp> riskfactor=getFRTBRiskfactor(CMTYReturns,cmtyClass.getMaxSign(),treeId,groupType,groupValue);
                riskfactorList.addAll(riskfactor);
            }

            //已验证 Delta (Vega Curvature)无测试数据
            List <FRTBModel> FXList=riskFactorClassMap.get("FX");
            if(null !=FXList){
                //Delta Vega Curvature
                    String FXReturns=new FXModule().calc(FXList);
                FRTBClassResult fxClass=getFrtbClassResult(FXReturns,"FX",treeId,groupType,groupValue);
                    calssList.add(fxClass);
                List <FRTBDecomp> riskfactor=getFRTBRiskfactor(FXReturns,fxClass.getMaxSign(),treeId,groupType,groupValue);
                riskfactorList.addAll(riskfactor);
            }


            FRTBClassResult frtbClassResultSUM=getFrtbClassResultSUM(calssList,treeId,groupType,groupValue);
            calssList.add(frtbClassResultSUM);

            Map<String ,Object> re=new HashMap<>();
            re.put("pos",null);
            re.put("bucket",null);
            re.put("calss",calssList);
            re.put("riskfactor",riskfactorList);

/*         riskfactor=decomp_rslt.groupby(['RISK_FACTOR_ID',
                'RISK_FACTOR_VERTEX_1', 'RISK_FACTOR_VERTEX_2', 'RISK_FACTOR_CLASS',
                        'RISK_FACTOR_BUCKET', 'RISK_FACTOR_TYPE', 'SENSITIVITY_TYPE'],dropna=False
                                       ).agg({'WEIGHTED_SENSITIVITY':'sum', 'CONTRIBUTION':'sum'}).reset_index()

         riskfactor=riskfactor[['RISK_FACTOR_ID', 'RISK_FACTOR_VERTEX_1', 'RISK_FACTOR_VERTEX_2',
                        'RISK_FACTOR_CLASS', 'RISK_FACTOR_BUCKET', 'RISK_FACTOR_TYPE',
                        'SENSITIVITY_TYPE', 'WEIGHTED_SENSITIVITY', 'CONTRIBUTION']]

         level3=decomp_rslt.groupby(['RISK_FACTOR_CLASS','SENSITIVITY_TYPE'
                                     , 'RISK_FACTOR_ID', 'RISK_FACTOR_VERTEX_1', 'RISK_FACTOR_VERTEX_2'
                                     , 'RISK_FACTOR_BUCKET', 'RISK_FACTOR_TYPE'],dropna=False
                                   ).agg({'WEIGHTED_SENSITIVITY':'sum','CONTRIBUTION':'sum'}).reset_index()

         level3.loc[:,'GROUP_VALUE']=np.nan
         level3.loc[:,'GROUP_TYPE']=np.nan

         level3=level3[['GROUP_TYPE', 'GROUP_VALUE', 'RISK_FACTOR_CLASS', 'SENSITIVITY_TYPE',
                        'RISK_FACTOR_ID', 'RISK_FACTOR_VERTEX_1', 'RISK_FACTOR_VERTEX_2',
                        'RISK_FACTOR_BUCKET', 'RISK_FACTOR_TYPE', 'WEIGHTED_SENSITIVITY',
                        'CONTRIBUTION']]*/

            returns.add(re);
        }

        //执行sql
/*        delete from TB_26_FRTB_SENSITIVITY_RESULT_RISKCLASS where DATA_DATE = %s
        insert into " + "TB_26_FRTB_SENSITIVITY_RESULT_RISKCLASS"

        delete from TB_26_FRTB_SENSITIVITY_DECOMP_RISKFACTOR where DATA_DATE = %s
        insert into " + "TB_26_FRTB_SENSITIVITY_DECOMP_RISKFACTOR

        delete from TB_26_FRTB_SENSITIVITY_DECOMP_PORTFOLIO where DATA_DATE = %s
        insert into " + "TB_26_FRTB_SENSITIVITY_DECOMP_PORTFOLIO*/

        String jsString = JSON.toJSONString(returns);
        System.out.println(jsString);
    }

    public static FRTBClassResult getFrtbClassResult(String returns, String riskFactoryClass,
                                                     String treeId,String groupType,String groupValue){

        FRTBClassResult frtbClassResult=new FRTBClassResult();
        frtbClassResult.setRiskFactorClass(riskFactoryClass);
        frtbClassResult.setTreeId(treeId);
        frtbClassResult.setGroupType(groupType);
        frtbClassResult.setGroupValue(groupValue);

        JSONObject js=JSON.parseObject(returns);
        BigDecimal NORMAL=new BigDecimal(0);
        BigDecimal HIGH=new BigDecimal(0);
        BigDecimal LOW=new BigDecimal(0);
        if(null!=js.get("Delta")){
            JSONObject jsd= (JSONObject) js.get("Delta");
            FRTBClass delta=JSON.parseObject(jsd.get("class").toString(), FRTBClass.class);
            frtbClassResult.setLowDelta(delta.getLow());
            frtbClassResult.setNormalDelta(delta.getNormal());
            frtbClassResult.setHighDelta(delta.getHigh());

            NORMAL=NORMAL.add(delta.getNormal());
            HIGH=HIGH.add(delta.getHigh());
            LOW=LOW.add(delta.getLow());
        }else{
            frtbClassResult.setLowDelta(new BigDecimal(0));
            frtbClassResult.setNormalDelta(new BigDecimal(0));
            frtbClassResult.setHighDelta(new BigDecimal(0));
        }

        if(null!=js.get("Vega")){
            JSONObject jsv= (JSONObject) js.get("Vega");
            FRTBClass vega=JSON.parseObject(jsv.get("class").toString(), FRTBClass.class);
            frtbClassResult.setLowVega(vega.getLow());
            frtbClassResult.setNormalVega(vega.getNormal());
            frtbClassResult.setHighVega(vega.getHigh());

            NORMAL=NORMAL.add(vega.getNormal());
            HIGH=HIGH.add(vega.getHigh());
            LOW=LOW.add(vega.getLow());
        }else{
            frtbClassResult.setLowVega(new BigDecimal(0));
            frtbClassResult.setNormalVega(new BigDecimal(0));
            frtbClassResult.setHighVega(new BigDecimal(0));
        }


        if(null!=js.get("Curvature")){
            JSONObject jsc= (JSONObject) js.get("Curvature");
            FRTBClass curvature=JSON.parseObject(jsc.get("class").toString(), FRTBClass.class);
            frtbClassResult.setLowCurvature(curvature.getLow());
            frtbClassResult.setNormalCurvature(curvature.getNormal());
            frtbClassResult.setHighCurvature(curvature.getHigh());

            NORMAL=NORMAL.add(curvature.getNormal());
            HIGH=HIGH.add(curvature.getHigh());
            LOW=LOW.add(curvature.getLow());
        }else{
            frtbClassResult.setLowCurvature(new BigDecimal(0));
            frtbClassResult.setNormalCurvature(new BigDecimal(0));
            frtbClassResult.setHighCurvature(new BigDecimal(0));
        }

        if(NORMAL.compareTo(HIGH) >=0 && NORMAL.compareTo(LOW) >=0){

            frtbClassResult.setMaxSign("NORMAL");
            frtbClassResult.setRiskCharge(NORMAL);

        }else if(HIGH.compareTo(NORMAL) >=0 && HIGH.compareTo(LOW) >=0){
            frtbClassResult.setMaxSign("HIGH");
            frtbClassResult.setRiskCharge(HIGH);

        }else if(LOW.compareTo(NORMAL) >=0 && LOW.compareTo(HIGH) >=0) {
            frtbClassResult.setMaxSign("LOW");
            frtbClassResult.setRiskCharge(LOW);
        }

        return frtbClassResult;
    }

    public static FRTBClassResult getFrtbClassResultSUM(List<FRTBClassResult> calssList,
                                                        String treeId,String groupType,String groupValue){

        FRTBClassResult frtbClassResult=new FRTBClassResult();
        frtbClassResult.setRiskFactorClass("ALL");
        frtbClassResult.setTreeId(treeId);
        frtbClassResult.setGroupType(groupType);
        frtbClassResult.setGroupValue(groupValue);
        //     calssList.stream().map(aa -> aa.getHigh_Curvature()).reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal highDelta = calssList.stream().map(FRTBClassResult::getHighDelta).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal lowDelta = calssList.stream().map(FRTBClassResult::getLowDelta).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal normalDelta = calssList.stream().map(FRTBClassResult::getNormalDelta).reduce(BigDecimal.ZERO, BigDecimal::add);
        frtbClassResult.setHighDelta(highDelta);
        frtbClassResult.setLowDelta(lowDelta);
        frtbClassResult.setNormalDelta(normalDelta);

        BigDecimal highVega = calssList.stream().map(FRTBClassResult::getHighVega).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal lowVega = calssList.stream().map(FRTBClassResult::getLowVega).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal normalVega = calssList.stream().map(FRTBClassResult::getNormalVega).reduce(BigDecimal.ZERO, BigDecimal::add);
        frtbClassResult.setHighVega(highVega);
        frtbClassResult.setLowVega(lowVega);
        frtbClassResult.setNormalVega(normalVega);

        BigDecimal highCurvature = calssList.stream().map(FRTBClassResult::getHighCurvature).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal lowCurvature = calssList.stream().map(FRTBClassResult::getLowCurvature).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal normalCurvature = calssList.stream().map(FRTBClassResult::getNormalCurvature).reduce(BigDecimal.ZERO, BigDecimal::add);
        frtbClassResult.setHighCurvature(highCurvature);
        frtbClassResult.setLowCurvature(lowCurvature);
        frtbClassResult.setNormalCurvature(normalCurvature);

        BigDecimal NORMAL=normalDelta.add(normalVega).add(normalCurvature);
        BigDecimal HIGH=highDelta.add(highVega).add(highCurvature);
        BigDecimal LOW=lowDelta.add(lowVega).add(lowCurvature);


        if(NORMAL.compareTo(HIGH) >=0 && NORMAL.compareTo(LOW) >=0){

            frtbClassResult.setMaxSign("NORMAL");
            frtbClassResult.setRiskCharge(NORMAL);

        }else if(HIGH.compareTo(NORMAL) >=0 && HIGH.compareTo(LOW) >=0){
            frtbClassResult.setMaxSign("HIGH");
            frtbClassResult.setRiskCharge(HIGH);

        }else if(LOW.compareTo(NORMAL) >=0 && LOW.compareTo(HIGH) >=0) {
            frtbClassResult.setMaxSign("LOW");
            frtbClassResult.setRiskCharge(LOW);
        }

        return frtbClassResult;
    }

    public static void getPos(String returns){

/*      pos=pd.concat([GIRR_delta,GIRR_vega,GIRR_curvature
                ,CSR_delta,CSR_vega,CSR_curvature
                ,CSRNC_delta,CSRNC_vega,CSRNC_curvature
                ,CSRC_delta,CSRC_vega,CSRC_curvature
                ,EQ_delta,EQ_vega,EQ_curvature
                ,CMTY_delta,CMTY_vega,CMTY_curvature
                ,FX_delta,FX_vega,FX_curvature]
                  ,join="outer",ignore_index=True)

        pos_col = pd.DataFrame(columns=['RISK_FACTOR_ID', 'RISK_FACTOR_VERTEX_1', 'RISK_FACTOR_VERTEX_2'
                , 'RISK_FACTOR_CLASS', 'RISK_FACTOR_BUCKET', 'RISK_FACTOR_TYPE'
                , 'SENSITIVITY_TYPE', 'SENSITIVITY_VAL_RPT_CURR_CNY', 'RISKWEIGHT'])

        pos=pd.concat([pos_col,pos],join="outer",ignore_index=True)

        pos=pos[['RISK_FACTOR_ID', 'RISK_FACTOR_VERTEX_1', 'RISK_FACTOR_VERTEX_2'
                , 'RISK_FACTOR_CLASS', 'RISK_FACTOR_BUCKET', 'RISK_FACTOR_TYPE'
                , 'SENSITIVITY_TYPE', 'SENSITIVITY_VAL_RPT_CURR_CNY', 'RISKWEIGHT']]*/

        JSONObject js=JSON.parseObject(returns);
        JSONObject jsd= (JSONObject) js.get("Delta");
        JSONObject jsv= (JSONObject) js.get("Vega");
        JSONObject jsc= (JSONObject) js.get("Curvature");






    }

    public static List <FRTBDecomp> getFRTBRiskfactor(String returns,String maxSign,
                                                      String treeId,String groupType,String groupValue){


        List <FRTBDecomp> xxx=new ArrayList<>();
        List <FRTBDecomp> riskfactor=new ArrayList<>();
        JSONObject js=JSON.parseObject(returns);

        if(null!=js.get("Delta")){
            JSONObject jsd= (JSONObject) js.get("Delta");
            List <FRTBDecomp> delta=JSON.parseArray(jsd.get("decompRslt").toString(), FRTBDecomp.class);
            xxx.addAll(delta);
        }

        if(null!=js.get("Vega")){
            JSONObject jsv= (JSONObject) js.get("Vega");
            List <FRTBDecomp>  vega=JSON.parseArray(jsv.get("decompRslt").toString(), FRTBDecomp.class);
            xxx.addAll(vega);
        }

        if(null!=js.get("Curvature")){
            JSONObject jsc= (JSONObject) js.get("Curvature");
            List <FRTBDecomp>  curvature=JSON.parseArray(jsc.get("decompRslt").toString(), FRTBDecomp.class);
            xxx.addAll(curvature);
        }

        for(FRTBDecomp frtbDecomp:xxx){

            switch(maxSign){
                case "NORMAL": frtbDecomp.setContribution(frtbDecomp.getWeightedSensitivity().multiply(frtbDecomp.getPderM()));
                    break;
                case "HIGH": frtbDecomp.setContribution(frtbDecomp.getWeightedSensitivity().multiply(frtbDecomp.getPderH()));
                    break;
                case "LOW": frtbDecomp.setContribution(frtbDecomp.getWeightedSensitivity().multiply(frtbDecomp.getPderL()));
                    break;

            }
            frtbDecomp.setTreeId(treeId);
            frtbDecomp.setGroupType(groupType);
            frtbDecomp.setGroupValue(groupValue);
            riskfactor.add(frtbDecomp);
        }


        return riskfactor;
    }

}
