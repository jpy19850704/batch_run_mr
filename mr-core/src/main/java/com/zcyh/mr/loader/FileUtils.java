package com.zcyh.mr.loader;

import org.apache.commons.io.IOUtils;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

public class FileUtils {

    public static String loadData(String fn) {
        String data = "";
        try {
            InputStream is = FileUtils.class.getClassLoader().getResourceAsStream(fn);
            data = IOUtils.toString(is, "UTF-8");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return data;
    }

    public static void setData(String fn,String filePath) {

        try {
            FileOutputStream fo=new FileOutputStream(filePath);
            fo.write(fn.getBytes(StandardCharsets.UTF_8));
            fo.close();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }


}