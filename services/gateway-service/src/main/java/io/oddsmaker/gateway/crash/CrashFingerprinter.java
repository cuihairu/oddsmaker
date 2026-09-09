package io.oddsmaker.gateway.crash;

import io.oddsmaker.common.model.Event;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 崩溃指纹（crash_hash）计算：供 ClickHouse v_crash_top_groups 聚合分组。
 * SDK 未附带 crash_hash 时由 Gateway 对 error 类型事件注入：
 * 取堆栈前 {@value #MAX_FRAMES} 帧规范化（去地址/行号/路径/数字）后 SHA-256 取 16 位十六进制，
 * 保证同一崩溃源（不同设备/地址/路径）聚合到同一分组；无堆栈时回退事件名。
 */
public final class CrashFingerprinter {

    public static final String PROP_STACK = "error_stack_trace";
    public static final String PROP_MESSAGE = "error_message";
    public static final String PROP_HASH = "crash_hash";
    public static final String PROP_CRASH_MESSAGE = "crash_message";

    /** 参与指纹的堆栈帧数（顶部帧最具区分度，限制数量防超长栈漂移） */
    public static final int MAX_FRAMES = 8;

    private CrashFingerprinter() {
    }

    /**
     * 对 error 类型事件注入 crash_hash / crash_message（幂等：SDK 已提供则保留）。
     */
    public static void enrich(Event event) {
        if (event == null || event.props == null || !"error".equals(event.eventType)) {
            return;
        }
        Object existing = event.props.get(PROP_HASH);
        if (existing == null || String.valueOf(existing).isBlank()) {
            event.props.put(PROP_HASH, fingerprint(event.eventName, stringOf(event.props.get(PROP_STACK))));
        }
        if (event.props.get(PROP_CRASH_MESSAGE) == null) {
            Object message = event.props.get(PROP_MESSAGE);
            if (message != null && !String.valueOf(message).isBlank()) {
                event.props.put(PROP_CRASH_MESSAGE, String.valueOf(message));
            }
        }
    }

    /** 指纹：规范化签名 → SHA-256 前 16 位 hex */
    public static String fingerprint(String eventName, String stackTrace) {
        return sha256Hex16(signature(eventName, stackTrace));
    }

    /** 规范化签名：无堆栈回退事件名；有堆栈取前 N 帧逐帧归一 */
    static String signature(String eventName, String stackTrace) {
        String name = eventName == null ? "" : eventName.trim().toLowerCase(Locale.ROOT);
        if (stackTrace == null || stackTrace.isBlank()) {
            return "name:" + name;
        }
        List<String> frames = new ArrayList<>();
        for (String rawLine : stackTrace.split("\\r?\\n")) {
            String line = rawLine.trim();
            if (line.isEmpty() || line.startsWith("...") || line.endsWith("more frames")) {
                continue;
            }
            if (line.startsWith("at ")) {
                line = line.substring(3);
            }
            String normalized = normalizeFrame(line);
            if (!normalized.isEmpty()) {
                frames.add(normalized);
                if (frames.size() >= MAX_FRAMES) {
                    break;
                }
            }
        }
        if (frames.isEmpty()) {
            return "name:" + name;
        }
        return name + "|" + String.join("|", frames);
    }

    /** 帧归一：剥离 "at " 前缀、十六进制地址与十进制数字归一、文件路径取 basename、压缩空白 */
    static String normalizeFrame(String frame) {
        String s = frame;
        if (s.startsWith("at ")) {
            s = s.substring(3);
        }
        // hex 地址先归一为占位符（防其中的 0 被十进制归一误伤），数字归一后还原
        s = s.replaceAll("0x[0-9a-fA-F]+", "\u0001");
        // 路径 basename：(...) 参数段或空白 token 中的目录前缀去除（兼容 / 与 \）
        s = s.replaceAll("([^\\s(]*[/\\\\])([A-Za-z0-9_.+\\-]+)", "$2");
        s = s.replaceAll("\\d+", "N");
        s = s.replace("\u0001", "0xX");
        s = s.replaceAll("\\s+", "");
        return s;
    }

    private static String stringOf(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static String sha256Hex16(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 8; i++) {
                hex.append(String.format("%02x", hash[i]));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
