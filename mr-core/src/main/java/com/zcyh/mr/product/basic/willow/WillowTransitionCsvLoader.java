package com.zcyh.mr.product.basic.willow;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public final class WillowTransitionCsvLoader {
    public static final String DEFAULT_RESOURCE = "/willow/willow_transition_probabilities.csv";
    public static final String EXCEL_SAMPLE_RESOURCE = "/willow/IRILN_Probabilities1F.csv";

    private WillowTransitionCsvLoader() {
    }

    public static WillowTransitionSet loadDefault() {
        return loadResource(DEFAULT_RESOURCE);
    }

    public static WillowTransitionSet loadExcelSample() {
        return loadResource(EXCEL_SAMPLE_RESOURCE, true);
    }

    public static WillowTransitionSet loadResource(String resourcePath) {
        return loadResource(resourcePath, false);
    }

    private static WillowTransitionSet loadResource(String resourcePath, boolean expandSymmetricHalf) {
        InputStream input = WillowTransitionCsvLoader.class.getResourceAsStream(resourcePath);
        if (input == null) {
            throw new IllegalArgumentException("Willow转移概率资源不存在: " + resourcePath);
        }
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            List<WillowTransition> rows = loadRows(reader);
            if (expandSymmetricHalf) {
                rows = expandSymmetricHalf(rows);
            }
            return new WillowTransitionSet(rows);
        } catch (IOException e) {
            throw new IllegalArgumentException("读取Willow转移概率失败: " + resourcePath, e);
        }
    }

    static WillowTransitionSet load(BufferedReader reader) throws IOException {
        return new WillowTransitionSet(loadRows(reader));
    }

    private static List<WillowTransition> loadRows(BufferedReader reader) throws IOException {
        List<WillowTransition> rows = new ArrayList<>();
        String line = reader.readLine();
        int lineNo = 1;
        while ((line = reader.readLine()) != null) {
            lineNo++;
            if (line.trim().isEmpty()) {
                continue;
            }
            String[] parts = line.split(",", -1);
            if (parts.length != 5) {
                throw new IllegalArgumentException("Willow转移概率CSV列数错误: line=" + lineNo);
            }
            rows.add(new WillowTransition(
                    parseInt(parts[0], lineNo, "time_index"),
                    parseInt(parts[1], lineNo, "origin_node_index"),
                    parseInt(parts[2], lineNo, "dest_node_index"),
                    parseInt(parts[3], lineNo, "non_zero_prob_count"),
                    parseDouble(parts[4], lineNo, "transition_probability")));
        }
        return rows;
    }

    private static List<WillowTransition> expandSymmetricHalf(List<WillowTransition> rows) {
        List<WillowTransition> expanded = new ArrayList<>(rows);
        for (WillowTransition row : rows) {
            int mirroredOrigin = WillowNodeDefinition.NODE_COUNT - 1 - row.originNode;
            int mirroredDest = WillowNodeDefinition.NODE_COUNT - 1 - row.destNode;
            if (mirroredOrigin != row.originNode) {
                expanded.add(new WillowTransition(row.timeIndex, mirroredOrigin, mirroredDest,
                        row.nonZeroProbCount, row.probability));
            }
        }
        return expanded;
    }

    private static int parseInt(String text, int lineNo, String fieldName) {
        try {
            return Integer.parseInt(text.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Willow转移概率CSV整数解析失败: line="
                    + lineNo + ", field=" + fieldName + ", value=" + text, e);
        }
    }

    private static double parseDouble(String text, int lineNo, String fieldName) {
        try {
            return Double.parseDouble(text.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Willow转移概率CSV数值解析失败: line="
                    + lineNo + ", field=" + fieldName + ", value=" + text, e);
        }
    }
}
