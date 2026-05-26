package com.zcyh.mr.basic.util;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;

/**
 * 验证以及日志输出类
 *
 * @author lsd
 * @version 1.0
 * @date 2024/7/10 14:00
 */
public class QL {

    public static void require(
            final boolean condition,
            final String format,
            final Object... objects) throws RuntimeException {
        if (!condition)
            throw new LibraryException(String.format(format, objects));
    }

    public static void require(
            final boolean condition,
            final String message) throws RuntimeException {
        if (!condition)
            throw new LibraryException(message);
    }

    public static void require(
            final boolean condition,
            final Class<? extends RuntimeException> klass,
            final String message) throws RuntimeException {
        if (!condition) {
            try {
                final Constructor<? extends RuntimeException> c = klass.getConstructor(String.class);
                throw c.newInstance(message);
            } catch (final SecurityException e) {
                e.printStackTrace();
            } catch (final NoSuchMethodException e) {
                e.printStackTrace();
            } catch (final IllegalArgumentException e) {
                e.printStackTrace();
            } catch (final InstantiationException e) {
                e.printStackTrace();
            } catch (final IllegalAccessException e) {
                e.printStackTrace();
            } catch (final InvocationTargetException e) {
                e.printStackTrace();
            }
        }
    }


    public static void ensure(
            final boolean condition,
            final String format,
            final Object... objects) throws RuntimeException {
        if (!condition)
            throw new LibraryException(String.format(format, objects));
    }


    public static void ensure(
            final boolean condition,
            final String message) throws RuntimeException {
        if (!condition)
            throw new LibraryException(message);
    }


    public static void error(final String message) {
        if (MRLib.logger != null) {
            MRLib.logger.error(message);
        } else {
            System.err.printf("ERROR: %s\n", message);
        }
    }


    public static void error(final String message, final Throwable t) {
        if (MRLib.logger != null) {
            MRLib.logger.error(message, t);
        } else {
            System.err.printf("ERROR: %s : %s\n", message, t.getMessage());
            t.printStackTrace(System.err);
        }
    }


    public static void error(final Throwable t) {
        if (MRLib.logger != null) {
            MRLib.logger.error(t.getMessage(), t);
        } else {
            System.err.printf("ERROR: %s\n", t.getMessage());
            System.err.println(t.getMessage());
            t.printStackTrace(System.err);
        }
    }


    public static void warn(final String message) {
        if (MRLib.logger != null) {
            MRLib.logger.warn(message);
        } else {
            System.err.printf("WARN: %s\n", message);
        }
    }

    public static void warn(final String message, final Throwable t) {
        if (MRLib.logger != null) {
            MRLib.logger.warn(message, t);
        } else {
            System.err.printf("WARN: %s : %s\n", message, t.getMessage());
            t.printStackTrace(System.err);
        }
    }


    public static void warn(final Throwable t) {
        if (MRLib.logger != null) {
            MRLib.logger.warn(t.getMessage(), t);
        } else {
            System.err.printf("WARN: %s\n", t.getMessage());
            System.err.println(t.getMessage());
            t.printStackTrace(System.err);
        }
    }

    public static void info(final String message) {
        if (MRLib.logger != null) {
            MRLib.logger.info(message);
        } else {
            System.err.printf("INFO: %s\n", message);
        }
    }


    public static void info(final String message, final Throwable t) {
        if (MRLib.logger != null) {
            MRLib.logger.info(message, t);
        } else {
            System.err.printf("INFO: %s : %s\n", message, t.getMessage());
            t.printStackTrace(System.err);
        }
    }


    public static void info(final Throwable t) {
        if (MRLib.logger != null) {
            MRLib.logger.info(t.getMessage(), t);
        } else {
            System.err.printf("INFO: %s\n", t.getMessage());
            System.err.println(t.getMessage());
            t.printStackTrace(System.err);
        }
    }


    public static void debug(final String message) {
        if (MRLib.logger != null) {
            MRLib.logger.debug(message);
        } else {
            System.err.printf("DEBUG: %s\n", message);
        }
    }


    public static void debug(final String message, final Throwable t) {
        if (MRLib.logger != null) {
            MRLib.logger.debug(message, t);
        } else {
            System.err.printf("DEBUG: %s : %s\n", message, t.getMessage());
            t.printStackTrace(System.err);
        }
    }


    public static void debug(final Throwable t) {
        if (MRLib.logger != null) {
            MRLib.logger.debug(t.getMessage(), t);
        } else {
            System.err.printf("DEBUG: %s\n", t.getMessage());
            System.err.println(t.getMessage());
            t.printStackTrace(System.err);
        }
    }


    public static void trace(final String message) {
        if (MRLib.logger != null) {
            MRLib.logger.trace(message);
        } else {
            System.err.printf("TRACE: %s\n", message);
        }
    }


    public static void trace(final String message, final Throwable t) {
        if (MRLib.logger != null) {
            MRLib.logger.trace(message, t);
        } else {
            System.err.printf("TRACE: %s : %s\n", message, t.getMessage());
            t.printStackTrace(System.err);
        }
    }


    public static void trace(final Throwable t) {
        if (MRLib.logger != null) {
            MRLib.logger.trace(t.getMessage(), t);
        } else {
            System.err.printf("TRACE: %s\n", t.getMessage());
            System.err.println(t.getMessage());
            t.printStackTrace(System.err);
        }
    }


    public static void validateExperimentalMode() {
        if (System.getProperty("EXPERIMENTAL") == null)
            throw new UnsupportedOperationException("功能尚未完成");
    }

}
