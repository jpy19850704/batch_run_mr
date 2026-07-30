package com.zcyh.mr.springboot.scenario;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.zcyh.mr.calc.scenario.CalcScenarioInputCache;
import com.zcyh.mr.calc.scenario.ScenarioProcessConstants;
import com.zcyh.mr.springboot.output.file.ScenarioSetPathResolver;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 在 MR_CALC 工作实例中从共享情景文件补充进程内缓存。
 */
@Service
public class SharedScenarioInputLoader {
    public static final String META_SCENARIO_MARKET_KEYS = "scenario_market_keys";
    private static final String[] SCENARIO_REF_FIELDS = {
            ScenarioProcessConstants.REGULAR_SCENARIO_REF_LIST,
            ScenarioProcessConstants.VAR_SCENARIO_REF_LIST,
            ScenarioProcessConstants.IMA_MODELLABLE_SCENARIO_REF_LIST,
            ScenarioProcessConstants.IMA_NMRF_SCENARIO_REF_LIST
    };

    private final ScenarioSetPathResolver pathResolver;

    public SharedScenarioInputLoader(ScenarioSetPathResolver pathResolver) {
        this.pathResolver = pathResolver;
    }

    public void ensureLoaded(JSONObject payload) {
        if (payload == null) {
            return;
        }
        LocalDate dataDate = null;
        String batchId = null;
        Set<String> marketKeys = resolveMarketKeys(payload);
        for (String fieldName : SCENARIO_REF_FIELDS) {
            JSONArray items = payload.getJSONArray(fieldName);
            if (items == null || items.isEmpty()) {
                continue;
            }
            if (dataDate == null) {
                dataDate = LocalDate.parse(requireText(payload.getString("data_date"), "data_date 必填"),
                        DateTimeFormatter.ISO_LOCAL_DATE);
                JSONObject batchMeta = payload.getJSONObject("batch_meta");
                batchId = requireText(batchMeta == null ? null : batchMeta.getString("batch_id"),
                        "batch_meta.batch_id 必填");
            }
            ensureReferenceListLoaded(items, fieldName, dataDate, batchId, marketKeys);
        }
    }

    private void ensureReferenceListLoaded(JSONArray items,
                                           String fieldName,
                                           LocalDate dataDate,
                                           String batchId,
                                           Set<String> marketKeys) {
        for (int i = 0; i < items.size(); i++) {
            JSONObject item = items.getJSONObject(i);
            if (item == null) {
                throw new IllegalArgumentException(fieldName + "[" + i + "] 必须是 JSON 对象");
            }
            String cacheKey = requireText(item.getString("cache_key"),
                    fieldName + "[" + i + "].cache_key 必填");
            if (CalcScenarioInputCache.contains(cacheKey)) {
                continue;
            }
            List<String> paths = resolveScenarioPaths(
                    requireText(item.getString("scenario_set_id"),
                            fieldName + "[" + i + "].scenario_set_id 必填"),
                    dataDate,
                    batchId);
            CalcScenarioInputCache.loadFromFiles(cacheKey, paths, dataDate, marketKeys);
        }
    }

    private List<String> resolveScenarioPaths(String scenarioIdList,
                                              LocalDate dataDate,
                                              String batchId) {
        List<String> paths = new ArrayList<String>();
        Set<String> scenarioIds = new LinkedHashSet<String>();
        for (String part : scenarioIdList.split(",")) {
            String scenarioId = trimToNull(part);
            if (scenarioId != null) {
                scenarioIds.add(scenarioId);
            }
        }
        for (String scenarioId : scenarioIds) {
            Path path = pathResolver.resolveScenarioFile(
                    dataDate.format(DateTimeFormatter.BASIC_ISO_DATE), batchId, scenarioId);
            if (!Files.isRegularFile(path)) {
                throw new IllegalArgumentException("共享scenario文件不存在: " + path);
            }
            paths.add(path.toString());
        }
        if (paths.isEmpty()) {
            throw new IllegalArgumentException("scenario_set_id 不能为空");
        }
        return paths;
    }

    private static Set<String> resolveMarketKeys(JSONObject payload) {
        Set<String> result = new LinkedHashSet<String>();
        JSONObject batchMeta = payload.getJSONObject("batch_meta");
        JSONArray values = batchMeta == null ? null : batchMeta.getJSONArray(META_SCENARIO_MARKET_KEYS);
        if (values == null || values.isEmpty()) {
            return null;
        }
        for (int i = 0; i < values.size(); i++) {
            String value = trimToNull(values.getString(i));
            if (value != null) {
                result.add(value.toUpperCase(Locale.ROOT));
            }
        }
        return result.isEmpty() ? null : result;
    }

    private static String requireText(String value, String message) {
        String result = trimToNull(value);
        if (result == null) {
            throw new IllegalArgumentException(message);
        }
        return result;
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String result = value.trim();
        return result.isEmpty() ? null : result;
    }
}
