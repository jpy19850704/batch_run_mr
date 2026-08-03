package com.zcyh.mr.support;

import com.alibaba.fastjson2.annotation.JSONField;

import java.lang.reflect.Field;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Objects;

/**
 * 通用公共类
 *
 * @author lsd
 * @version 1.0
 * @date 2024/7/11 14:42
 */

public class ReflectionUtils {

    /**
     * 通过反射的方式将HashMap的数据映射到实体类，中间使用到JSONField,通过JSONField的name和HashMap中的key关联起来
     *
     * @param map: 输入的数据
     * @param c: 目标实体类
     * @return T
     * @author lsd
     * @date 2024/7/11 16:08
     */
    public static <T> T map2Bean(HashMap<String, Object> map, Class<T> c) {

        try {
            T t = c.getDeclaredConstructor()
                    .newInstance();
            Field[] fields = c.getFields();
            // 给具有JSONField注解的属性进行赋值
            for (Field field : fields) {
                JSONField annotation = field.getAnnotation(JSONField.class);
                if (annotation != null) {
                    String name = annotation.name();
                    field.setAccessible(true);
                    if (field.getType() == LocalDate.class) {
                        if (map.get(name) instanceof LocalDate) {
                            field.set(t, map.get(name));
                        } else {
                            String date = (String) map.get(name);
                            if (date != null) {
                                field.set(t, LocalDate.parse(date));
                            }
                        }
                    } else if (field.getType() == Double.class && map.get(name) != null) {
                        field.set(t, Double.valueOf(map.get(name)
                                .toString()));
                    } else if (field.getType() == Integer.class && map.get(name) != null) {
                        field.set(t, Double.valueOf(map.get(name)
                                .toString()).intValue());
                    } else {
                        field.set(t, map.get(name));
                    }

                }
            }
            return t;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }



    /**
     * 通过反射的方式将HashMap的数据映射到实体类，中间使用到JSONField,通过JSONField的name和HashMap中的key关联起来
     *
     * @param source: 输入的数据
     * @param c: 目标实体类
     * @return T
     * @author lsd
     * @date 2024/7/11 16:08
     */
    public static <T> T bean2Bean(Object source, Class<T> c)  {
        Class<?> sourceClass = source.getClass();
        Field[] sourceFields = sourceClass.getFields();
        HashMap<String, Object> kv = new HashMap<>();

        for(Field field: sourceFields) {
            JSONField annotation = field.getAnnotation(JSONField.class);
            if (annotation != null) {
                String name = annotation.name();
                Object value = null;
                try {
                    value = field.get(source);
                } catch (IllegalAccessException e) {
                    throw new RuntimeException(e);
                }
                kv.put(name, value);
            }
        }

        return  map2Bean(kv,c);
    }

    public static <T> T bean2Bean(Object source, Class<T> c, HashSet<String> excludeFields)  {
        Class<?> sourceClass = source.getClass();
        Field[] sourceFields = sourceClass.getFields();
        HashMap<String, Object> kv = new HashMap<>();

        for(Field field: sourceFields) {
            JSONField annotation = field.getAnnotation(JSONField.class);
            if (annotation != null) {
                String name = annotation.name();
                if (excludeFields.contains(name)) continue;
                Object value = null;
                try {
                    value = field.get(source);
                } catch (IllegalAccessException e) {
                    throw new RuntimeException(e);
                }
                kv.put(name, value);
            }
        }

        return  map2Bean(kv,c);
    }

    public static void setStringDefaults(String defaultValue, Object object) throws Exception {
        if (Objects.isNull(object)) return;
        Class<?> clazz = object.getClass();
        while (clazz != Object.class && clazz != null) {
            for (Field field : clazz.getDeclaredFields()) {
                if (field.getType() == String.class) {
                    field.setAccessible(true);
                    String value = (String) field.get(object);
                    if (Objects.isNull(value)) field.set(object, defaultValue);
                }
            }
            clazz = clazz.getSuperclass();
        }
    }

}
