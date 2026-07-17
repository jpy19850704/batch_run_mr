package com.zcyh.mr.springboot.input.db;

import java.util.ArrayList;
import java.util.List;

/**
 * 输入数据过滤表达式树节点。
 */
public class InputFilterExpression {
    private String logic;
    private List<InputFilterExpression> children = new ArrayList<InputFilterExpression>();
    private String field;
    private String operator;
    private Object value;

    public String getLogic() {
        return logic;
    }

    public void setLogic(String logic) {
        this.logic = logic;
    }

    public List<InputFilterExpression> getChildren() {
        return children;
    }

    public void setChildren(List<InputFilterExpression> children) {
        this.children = children == null ? new ArrayList<InputFilterExpression>() : children;
    }

    public String getField() {
        return field;
    }

    public void setField(String field) {
        this.field = field;
    }

    public String getOperator() {
        return operator;
    }

    public void setOperator(String operator) {
        this.operator = operator;
    }

    public Object getValue() {
        return value;
    }

    public void setValue(Object value) {
        this.value = value;
    }
}
