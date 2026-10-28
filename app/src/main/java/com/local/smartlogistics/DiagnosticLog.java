package com.local.smartlogistics;

import android.content.Context;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Deque;
import java.util.Locale;
import java.util.regex.Pattern;

/** App-private, bounded and sanitized diagnostics for user-triggered troubleshooting. */
final class DiagnosticLog {
    private static final String FILE_NAME = "diagnostic-v2.log";
    private static final String LEGACY_FILE_NAME = "diagnostic.log";
    private static final int MAX_LINES = 240;
    private static final int MAX_LINE_LENGTH = 360;
    private static final long MAX_FILE_BYTES = 384L * 1024L;
    private static final Object LOCK = new Object();
    private static final Deque<String> LINES = new ArrayDeque<>();
    private static final Pattern URL_QUERY = Pattern.compile("(https?://[^\\s?]+)\\?[^\\s]*", Pattern.CASE_INSENSITIVE);
    private static final Pattern NAMED_SECRET = Pattern.compile(
            "(?i)(cookie|set-cookie|phpsessid|password|passwd|mobile|telephone|studentid|code)\\s*[:=]\\s*[^\\s,;]+"
    );
    private static final Pattern PHONE = Pattern.compile("(?<!\\d)1[3-9]\\d{9}(?!\\d)");
    private static final Pattern LONG_NUMBER = Pattern.compile("(?<!\\d)\\d{6,20}(?!\\d)");
    private static Context appContext;
    private static boolean loaded;

    private DiagnosticLog() {
    }

    static void initialize(Context context) {
        synchronized (LOCK) {
            if (appContext == null) {
                appContext = context.getApplicationContext();
                deleteLegacyLogLocked();
            }
            loadLocked();
        }
    }

    static void info(String event) {
        append("信息", event);
    }

    static void success(String event) {
        append("成功", event);
    }

    static void warning(String event) {
        append("警告", event);
    }

    static void error(String event, Throwable throwable) {
        // Throwable messages can contain server response fragments or request data.
        append("错误", event + " · " + errorType(throwable));
        if (throwable == null) return;
        StackTraceElement[] stack = throwable.getStackTrace();
        int limit = Math.min(stack.length, 5);
        for (int index = 0; index < limit; index++) {
            append("堆栈", "at " + stack[index]);
        }
        Throwable cause = throwable.getCause();
        if (cause != null && cause != throwable) {
            append("原因", cause.getClass().getSimpleName());
        }
    }

    static String readText() {
        synchronized (LOCK) {
            loadLocked();
            if (LINES.isEmpty()) {
                return "暂无诊断日志。请关闭窗口后重试相关操作。";
            }
            StringBuilder output = new StringBuilder();
            for (String line : LINES) {
                if (output.length() > 0) output.append('\n');
                output.append(line);
            }
            return output.toString();
        }
    }

    static void clear() {
        synchronized (LOCK) {
            LINES.clear();
            loaded = true;
            File file = logFile();
            if (file != null && file.exists()) {
                //noinspection ResultOfMethodCallIgnored
                file.delete();
            }
        }
    }

    private static void append(String level, String event) {
        synchronized (LOCK) {
            loadLocked();
            String timestamp = new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.CHINA)
                    .format(new Date());
            String line = timestamp + "  [" + level + "]  " + sanitize(event);
            line = boundedLine(line);
            LINES.addLast(line);
            while (LINES.size() > MAX_LINES) LINES.removeFirst();
            persistLocked();
        }
    }

    private static void loadLocked() {
        if (loaded || appContext == null) return;
        loaded = true;
        File file = logFile();
        if (file == null || !file.isFile()) return;
        if (file.length() > MAX_FILE_BYTES) {
            //noinspection ResultOfMethodCallIgnored
            file.delete();
            return;
        }
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                LINES.addLast(boundedLine(sanitize(line)));
                while (LINES.size() > MAX_LINES) LINES.removeFirst();
            }
        } catch (Exception ignored) {
            LINES.clear();
        }
    }

    private static void persistLocked() {
        File file = logFile();
        if (file == null) return;
        try (FileOutputStream output = new FileOutputStream(file, false)) {
            for (String line : LINES) {
                output.write(line.getBytes(StandardCharsets.UTF_8));
                output.write('\n');
            }
        } catch (Exception ignored) {
            // Diagnostics must never interrupt login or querying.
        }
    }

    private static File logFile() {
        return appContext == null ? null : new File(appContext.getFilesDir(), FILE_NAME);
    }

    private static void deleteLegacyLogLocked() {
        if (appContext == null) return;
        File legacy = new File(appContext.getFilesDir(), LEGACY_FILE_NAME);
        if (legacy.isFile()) {
            // Old builds could persist server response fragments, so they are never migrated/exported.
            //noinspection ResultOfMethodCallIgnored
            legacy.delete();
        }
    }

    private static String boundedLine(String value) {
        if (value.length() <= MAX_LINE_LENGTH) return value;
        return value.substring(0, MAX_LINE_LENGTH - 1) + "…";
    }

    private static String errorType(Throwable throwable) {
        return throwable == null ? "UnknownException" : throwable.getClass().getSimpleName();
    }

    private static String sanitize(String value) {
        if (value == null) return "";
        String result = value.replace('\r', ' ').replace('\n', ' ').trim();
        result = URL_QUERY.matcher(result).replaceAll("$1?<已隐藏>");
        result = NAMED_SECRET.matcher(result).replaceAll("$1=<已隐藏>");
        result = PHONE.matcher(result).replaceAll("<手机号已隐藏>");
        result = LONG_NUMBER.matcher(result).replaceAll("<数字已隐藏>");
        return result;
    }
}
