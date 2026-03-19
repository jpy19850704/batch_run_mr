package com.zcyh.mr.springboot.model;

/**
 * 统一接口响应结构。
 *
 * @param <T> 响应数据类型
 */
public class ApiResponse<T> {
    private boolean success;
    private String code;
    private String message;
    private T data;
    private long timestamp;

    public ApiResponse() {
    }

    public static <T> ApiResponse<T> ok(T data) {
        ApiResponse<T> resp = new ApiResponse<T>();
        resp.success = true;
        resp.code = "OK";
        resp.message = "success";
        resp.data = data;
        resp.timestamp = System.currentTimeMillis();
        return resp;
    }

    public static <T> ApiResponse<T> fail(String code, String message) {
        ApiResponse<T> resp = new ApiResponse<T>();
        resp.success = false;
        resp.code = code;
        resp.message = message;
        resp.timestamp = System.currentTimeMillis();
        return resp;
    }

    public boolean isSuccess() {
        return success;
    }

    public void setSuccess(boolean success) {
        this.success = success;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public T getData() {
        return data;
    }

    public void setData(T data) {
        this.data = data;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(long timestamp) {
        this.timestamp = timestamp;
    }
}

