package com.zcyh.mr.frtbsa.sba.common;

import org.apache.commons.io.IOUtils;

import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * 用于加载 classpath 资源的工具类。
 */
public class FileUtils {

    public static String loadData(String fn) {
        String data = "";
        try {
            InputStream is = FileUtils.class.getClassLoader().getResourceAsStream(fn);
            if (is == null) {
                throw new RuntimeException("资源不存在: " + fn);
            }
            data = IOUtils.toString(is, "UTF-8");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return data;
    }

    public static void setData(String fn, String filePath) {
        try {
            FileOutputStream fo = new FileOutputStream(filePath);
            fo.write(fn.getBytes(StandardCharsets.UTF_8));
            fo.close();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}