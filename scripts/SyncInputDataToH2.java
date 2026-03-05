import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import org.h2.tools.RunScript;

import java.io.FileReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.sql.Connection;
import java.sql.Date;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Arrays;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class SyncInputDataToH2 {
    private static final Pattern DATE_8_PATTERN = Pattern.compile("(20\\d{6})");
    private static final Pattern DATE_10_PATTERN = Pattern.compile("(20\\d{2})[-/](\\d{2})[-/](\\d{2})");
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final Connection conn;
    private final Path dataRoot;
    private final long now;

    private long insertedTrade = 0;
    private long updatedTrade = 0;
    private long insertedCurve = 0;
    private long updatedCurve = 0;
    private long skippedFiles = 0;

    private SyncInputDataToH2(Connection conn, Path dataRoot) {
        this.conn = conn;
        this.dataRoot = dataRoot;
        this.now = System.currentTimeMillis();
    }

    public static void main(String[] args) throws Exception {
        Path projectRoot = Paths.get("").toAbsolutePath().normalize();
        String dbUrl = args.length > 0 ? args[0] : "jdbc:h2:file:./data/mr_input_store;MODE=MySQL;AUTO_SERVER=TRUE;DB_CLOSE_DELAY=-1";
        String dbUser = args.length > 1 ? args[1] : "sa";
        String dbPassword = args.length > 2 ? args[2] : "";
        if ("__EMPTY__".equals(dbPassword)) {
            dbPassword = "";
        }
        String dataDir = args.length > 3 ? args[3] : projectRoot.resolve(Paths.get("src", "main", "resources", "data")).toString();
        String schemaPath = args.length > 4 ? args[4] : projectRoot.resolve(Paths.get("src", "main", "resources", "db", "mr_input_schema.sql")).toString();

        try (Connection conn = openConnection(dbUrl, dbUser, dbPassword)) {
            runSchema(conn, schemaPath);
            conn.setAutoCommit(false);
            SyncInputDataToH2 sync = new SyncInputDataToH2(conn, Paths.get(dataDir));
            System.out.println("数据目录: " + sync.dataRoot.toString() + "，exists=" + Files.exists(sync.dataRoot));
            sync.syncTradeFiles();
            sync.syncTopCurveFiles();
            conn.commit();
            sync.printStats();
            sync.printTableCount();
            sync.printSamples();
        }
    }

    private static Connection openConnection(String dbUrl, String dbUser, String dbPassword) throws SQLException {
        List<String[]> candidates = Arrays.asList(
                new String[]{dbUser, dbPassword},
                new String[]{"sa", ""},
                new String[]{"SA", ""},
                new String[]{"", ""},
                new String[]{"root", ""}
        );
        SQLException last = null;
        for (String[] pair : candidates) {
            String user = pair[0] == null ? "" : pair[0];
            String pass = pair[1] == null ? "" : pair[1];
            try {
                Connection conn = DriverManager.getConnection(dbUrl, user, pass);
                System.out.println("数据库连接成功: user=" + user);
                return conn;
            } catch (SQLException ex) {
                last = ex;
            }
        }
        throw last;
    }

    private static void runSchema(Connection conn, String schemaPath) throws Exception {
        try (Reader reader = new FileReader(schemaPath)) {
            RunScript.execute(conn, reader);
        }
    }

    private void syncTradeFiles() throws Exception {
        final Path tradeRoot = dataRoot.resolve("trade");
        if (!Files.exists(tradeRoot)) {
            System.out.println("交易目录不存在: " + tradeRoot.toString());
            return;
        }
        final long[] fileCount = new long[]{0L};
        Files.walkFileTree(tradeRoot, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws java.io.IOException {
                fileCount[0]++;
                String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
                if (!name.endsWith(".json")) {
                    return FileVisitResult.CONTINUE;
                }
                if (shouldSkipByName(name)) {
                    skippedFiles++;
                    return FileVisitResult.CONTINUE;
                }
                try {
                    processTradeJsonFile(file);
                } catch (Exception ex) {
                    skippedFiles++;
                    System.out.println("跳过文件: " + file.toString() + "，原因: " + ex.getMessage());
                }
                return FileVisitResult.CONTINUE;
            }
        });
        System.out.println("交易目录扫描文件数: " + fileCount[0]);
    }

    private void syncTopCurveFiles() throws Exception {
        if (!Files.exists(dataRoot)) {
            System.out.println("数据根目录不存在: " + dataRoot.toString());
            return;
        }
        List<Path> curveFiles = new ArrayList<Path>();
        Files.list(dataRoot).forEach(path -> {
            String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
            if (Files.isRegularFile(path) && name.endsWith(".json") && name.contains("curve")) {
                curveFiles.add(path);
            }
        });
        Collections.sort(curveFiles);
        System.out.println("顶层曲线文件数: " + curveFiles.size());
        for (Path file : curveFiles) {
            try {
                processCurveJsonFile(file);
            } catch (Exception ex) {
                skippedFiles++;
                System.out.println("跳过曲线文件: " + file.toString() + "，原因: " + ex.getMessage());
            }
        }
    }

    private void processTradeJsonFile(Path file) throws Exception {
        String raw = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        Object root = JSON.parse(raw);
        if (root == null) {
            skippedFiles++;
            return;
        }
        if (root instanceof JSONObject) {
            JSONObject obj = (JSONObject) root;
            String dataDate = normalizeDate(firstNonBlank(
                    obj.getString("data_date"),
                    obj.getString("DATA_DATE"),
                    extractDateFromText(file.getFileName().toString())
            ));

            JSONArray trades = toArray(obj.get("trade_data"));
            if (trades != null && !trades.isEmpty()) {
                for (int i = 0; i < trades.size(); i++) {
                    JSONObject trade = castObject(trades.get(i));
                    if (trade == null) {
                        continue;
                    }
                    upsertTradeRow(file, trade, dataDate, i + 1);
                }
            } else if (isTradeLikeObject(obj)) {
                upsertTradeRow(file, obj, dataDate, 1);
            }

            JSONArray marketArr = toArray(obj.get("market_data"));
            if (marketArr != null && !marketArr.isEmpty()) {
                for (int i = 0; i < marketArr.size(); i++) {
                    JSONObject curve = castObject(marketArr.get(i));
                    if (curve == null) {
                        continue;
                    }
                    String curveDate = normalizeDate(firstNonBlank(
                            curve.getString("DATA_DATE"),
                            curve.getString("dataDate"),
                            dataDate
                    ));
                    String curveType = firstNonBlank(
                            curve.getString("CURVE_TYPE"),
                            curve.getString("curveType"),
                            inferMarketTypeByFileName(file.getFileName().toString())
                    );
                    String curveId = firstNonBlank(
                            curve.getString("CURVE_ID"),
                            curve.getString("curveId"),
                            curve.getString("curveCode"),
                            buildDefaultCurveId(file, i + 1)
                    );
                    upsertCurveRow(curveDate, curveType, curveId, JSON.toJSONString(curve), "trade.market_data");
                }
            }
            return;
        }

        if (root instanceof JSONArray) {
            JSONArray arr = (JSONArray) root;
            if (arr.isEmpty()) {
                skippedFiles++;
                return;
            }
            JSONObject first = castObject(arr.get(0));
            if (first == null) {
                skippedFiles++;
                return;
            }

            if (isTradeLikeObject(first)) {
                String fileDate = normalizeDate(extractDateFromText(file.getFileName().toString()));
                for (int i = 0; i < arr.size(); i++) {
                    JSONObject trade = castObject(arr.get(i));
                    if (trade == null) {
                        continue;
                    }
                    String date = normalizeDate(firstNonBlank(
                            trade.getString("data_date"),
                            trade.getString("DATA_DATE"),
                            trade.getString("TRADE_DATE"),
                            fileDate
                    ));
                    upsertTradeRow(file, trade, date, i + 1);
                }
                return;
            }

            syncCurveArray(file, arr, inferMarketTypeByFileName(file.getFileName().toString()));
            return;
        }

        skippedFiles++;
    }

    private void processCurveJsonFile(Path file) throws Exception {
        String raw = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        Object root = JSON.parse(raw);
        if (root instanceof JSONObject) {
            JSONObject obj = (JSONObject) root;
            String dataDate = normalizeDate(firstNonBlank(
                    obj.getString("data_date"),
                    obj.getString("DATA_DATE"),
                    obj.getString("dataDate"),
                    extractDateFromText(file.getFileName().toString())
            ));
            String marketType = firstNonBlank(
                    obj.getString("CURVE_TYPE"),
                    obj.getString("curveType"),
                    inferMarketTypeByFileName(file.getFileName().toString())
            );
            String curveId = firstNonBlank(
                    obj.getString("CURVE_ID"),
                    obj.getString("curveId"),
                    obj.getString("curveCode"),
                    buildDefaultCurveId(file, 1)
            );
            upsertCurveRow(dataDate, marketType, curveId, JSON.toJSONString(obj), "curve.file");
            return;
        }
        if (root instanceof JSONArray) {
            JSONArray arr = (JSONArray) root;
            if (arr.isEmpty()) {
                skippedFiles++;
                return;
            }
            syncCurveArray(file, arr, inferMarketTypeByFileName(file.getFileName().toString()));
            return;
        }
        skippedFiles++;
    }

    private void syncCurveArray(Path file, JSONArray arr, String defaultMarketType) throws SQLException {
        Map<String, JSONArray> grouped = new LinkedHashMap<String, JSONArray>();
        Map<String, String> keyToType = new LinkedHashMap<String, String>();
        Map<String, String> keyToCurveId = new LinkedHashMap<String, String>();
        Map<String, String> keyToDate = new LinkedHashMap<String, String>();

        for (int i = 0; i < arr.size(); i++) {
            JSONObject item = castObject(arr.get(i));
            if (item == null) {
                continue;
            }
            String dataDate = normalizeDate(firstNonBlank(
                    item.getString("DATA_DATE"),
                    item.getString("dataDate"),
                    item.getString("data_date"),
                    extractDateFromText(file.getFileName().toString())
            ));
            String marketType = firstNonBlank(
                    item.getString("CURVE_TYPE"),
                    item.getString("curveType"),
                    defaultMarketType
            );
            String curveId = firstNonBlank(
                    item.getString("CURVE_ID"),
                    item.getString("curveId"),
                    item.getString("curveCode"),
                    buildDefaultCurveId(file, i + 1)
            );
            String key = dataDate + "|" + marketType + "|" + curveId;
            JSONArray bucket = grouped.get(key);
            if (bucket == null) {
                bucket = new JSONArray();
                grouped.put(key, bucket);
                keyToType.put(key, marketType);
                keyToCurveId.put(key, curveId);
                keyToDate.put(key, dataDate);
            }
            bucket.add(item);
        }

        for (Map.Entry<String, JSONArray> entry : grouped.entrySet()) {
            String key = entry.getKey();
            upsertCurveRow(
                    keyToDate.get(key),
                    keyToType.get(key),
                    keyToCurveId.get(key),
                    JSON.toJSONString(entry.getValue()),
                    "curve.array"
            );
        }
    }

    private void upsertTradeRow(Path file, JSONObject trade, String dataDate, int ordinal) throws SQLException {
        String tradeId = firstNonBlank(
                trade.getString("INSTRUMENT_ID"),
                trade.getString("tradeId"),
                trade.getString("trade_id"),
                trade.getString("id"),
                buildDefaultTradeId(file, ordinal)
        );
        String productType = firstNonBlank(
                trade.getString("PRODUCT_CODE"),
                trade.getString("productType"),
                trade.getString("product_type"),
                "UNKNOWN"
        );
        String content = JSON.toJSONString(trade);

        int updated = 0;
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE mr_trade_input SET product_type=?, trade_content_text=?, content_format='JSON', source_system=?, updated_at=? " +
                        "WHERE data_date=? AND trade_id=? AND version_no=1")) {
            ps.setString(1, productType);
            ps.setString(2, content);
            ps.setString(3, "resources.data.trade");
            ps.setLong(4, now);
            ps.setDate(5, Date.valueOf(dataDate));
            ps.setString(6, tradeId);
            updated = ps.executeUpdate();
        }
        if (updated > 0) {
            updatedTrade++;
            return;
        }

        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO mr_trade_input (data_date, trade_id, product_type, trade_content_text, content_format, version_no, source_system, created_at, updated_at) " +
                        "VALUES (?, ?, ?, ?, 'JSON', 1, ?, ?, ?)")) {
            ps.setDate(1, Date.valueOf(dataDate));
            ps.setString(2, tradeId);
            ps.setString(3, productType);
            ps.setString(4, content);
            ps.setString(5, "resources.data.trade");
            ps.setLong(6, now);
            ps.setLong(7, now);
            ps.executeUpdate();
            insertedTrade++;
        }
    }

    private void upsertCurveRow(String dataDate, String marketType, String curveId, String content, String source) throws SQLException {
        int updated = 0;
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE mr_market_curve_input SET curve_content_text=?, content_format='JSON', source_system=?, updated_at=? " +
                        "WHERE data_date=? AND market_data_type=? AND curve_id=? AND version_no=1")) {
            ps.setString(1, content);
            ps.setString(2, source);
            ps.setLong(3, now);
            ps.setDate(4, Date.valueOf(dataDate));
            ps.setString(5, firstNonBlank(marketType, "UNKNOWN"));
            ps.setString(6, curveId);
            updated = ps.executeUpdate();
        }
        if (updated > 0) {
            updatedCurve++;
            return;
        }

        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO mr_market_curve_input (data_date, market_data_type, curve_id, curve_content_text, content_format, version_no, source_system, created_at, updated_at) " +
                        "VALUES (?, ?, ?, ?, 'JSON', 1, ?, ?, ?)")) {
            ps.setDate(1, Date.valueOf(dataDate));
            ps.setString(2, firstNonBlank(marketType, "UNKNOWN"));
            ps.setString(3, curveId);
            ps.setString(4, content);
            ps.setString(5, source);
            ps.setLong(6, now);
            ps.setLong(7, now);
            ps.executeUpdate();
            insertedCurve++;
        }
    }

    private void printStats() {
        System.out.println("同步完成:");
        System.out.println("  trade inserted=" + insertedTrade + ", updated=" + updatedTrade);
        System.out.println("  curve inserted=" + insertedCurve + ", updated=" + updatedCurve);
        System.out.println("  skipped files=" + skippedFiles);
    }

    private void printTableCount() throws SQLException {
        try (Statement st = conn.createStatement()) {
            long tradeCount = queryCount(st, "SELECT COUNT(1) FROM mr_trade_input");
            long curveCount = queryCount(st, "SELECT COUNT(1) FROM mr_market_curve_input");
            System.out.println("表记录数:");
            System.out.println("  mr_trade_input=" + tradeCount);
            System.out.println("  mr_market_curve_input=" + curveCount);
        }
    }

    private void printSamples() throws SQLException {
        try (Statement st = conn.createStatement()) {
            System.out.println("交易样例:");
            try (ResultSet rs = st.executeQuery(
                    "SELECT data_date, trade_id, product_type FROM mr_trade_input ORDER BY updated_at DESC, id DESC LIMIT 5")) {
                while (rs.next()) {
                    System.out.println("  " + rs.getDate(1) + " | " + rs.getString(2) + " | " + rs.getString(3));
                }
            }
            System.out.println("曲线样例:");
            try (ResultSet rs = st.executeQuery(
                    "SELECT data_date, market_data_type, curve_id FROM mr_market_curve_input ORDER BY updated_at DESC, id DESC LIMIT 5")) {
                while (rs.next()) {
                    System.out.println("  " + rs.getDate(1) + " | " + rs.getString(2) + " | " + rs.getString(3));
                }
            }
        }
    }

    private static long queryCount(Statement st, String sql) throws SQLException {
        try (ResultSet rs = st.executeQuery(sql)) {
            if (rs.next()) {
                return rs.getLong(1);
            }
        }
        return 0;
    }

    private static boolean shouldSkipByName(String lowerFileName) {
        return lowerFileName.contains("result")
                || lowerFileName.contains("report")
                || lowerFileName.contains("testing_result");
    }

    private static JSONArray toArray(Object obj) {
        if (obj instanceof JSONArray) {
            return (JSONArray) obj;
        }
        return null;
    }

    private static JSONObject castObject(Object obj) {
        if (obj instanceof JSONObject) {
            return (JSONObject) obj;
        }
        return null;
    }

    private static boolean isTradeLikeObject(JSONObject obj) {
        return obj.containsKey("trade_data")
                || obj.containsKey("INSTRUMENT_ID")
                || obj.containsKey("tradeId")
                || obj.containsKey("trade_id")
                || obj.containsKey("PRODUCT_CODE")
                || obj.containsKey("productType")
                || obj.containsKey("product_type");
    }

    private static String buildDefaultTradeId(Path file, int ordinal) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        return base + "_" + ordinal;
    }

    private static String buildDefaultCurveId(Path file, int ordinal) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        return base + "_" + ordinal;
    }

    private static String inferMarketTypeByFileName(String fileName) {
        String lower = fileName.toLowerCase(Locale.ROOT);
        if (lower.contains("fx")) {
            return "FX_CURVE";
        }
        if (lower.contains("ir")) {
            return "IR_CURVE";
        }
        if (lower.contains("bond")) {
            return "BOND_CURVE";
        }
        if (lower.contains("vol")) {
            return "VOL_CURVE";
        }
        return "UNKNOWN";
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value == null) {
                continue;
            }
            String trimmed = value.trim();
            if (!trimmed.isEmpty()) {
                return trimmed;
            }
        }
        return null;
    }

    private static String normalizeDate(String raw) {
        if (raw == null) {
            return "1970-01-01";
        }
        String value = raw.trim();
        if (value.isEmpty()) {
            return "1970-01-01";
        }
        if (value.length() >= 10 && value.charAt(4) == '-' && value.charAt(7) == '-') {
            try {
                return LocalDate.parse(value.substring(0, 10), DATE_FMT).format(DATE_FMT);
            } catch (Exception ignore) {
                // 继续尝试其他格式
            }
        }
        Matcher m10 = DATE_10_PATTERN.matcher(value);
        if (m10.find()) {
            return m10.group(1) + "-" + m10.group(2) + "-" + m10.group(3);
        }
        Matcher m8 = DATE_8_PATTERN.matcher(value);
        if (m8.find()) {
            String d = m8.group(1);
            return d.substring(0, 4) + "-" + d.substring(4, 6) + "-" + d.substring(6, 8);
        }
        return "1970-01-01";
    }

    private static String extractDateFromText(String text) {
        if (text == null) {
            return null;
        }
        Matcher m10 = DATE_10_PATTERN.matcher(text);
        if (m10.find()) {
            return m10.group(1) + "-" + m10.group(2) + "-" + m10.group(3);
        }
        Matcher m8 = DATE_8_PATTERN.matcher(text);
        if (m8.find()) {
            String d = m8.group(1);
            return d.substring(0, 4) + "-" + d.substring(4, 6) + "-" + d.substring(6, 8);
        }
        return null;
    }
}
