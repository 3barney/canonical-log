package com.github.barney.canonicallog.lib.masking;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

// Mask sensitive values in log output
public class SensitiveMasker {

    private static final Set<String> SENSITIVE_KEYS = Set.of("msisdn", "phone", "mobile", "phoneNumber", "phone_number", "password",  "authorization");

    // use simple phone and email pattern :: phone assumption is it starts with +COUNTRY_CODE
    private static final Pattern PHONE_PATTERN = Pattern.compile("(\\+?\\d{1,4})(\\d+)(\\d{4})");
    private SensitiveMasker() {}

    public static boolean isSensitive(String key) {
        if (key == null) return false;
        String lower = key.toLowerCase();
        return SENSITIVE_KEYS.stream().anyMatch(s -> lower.contains(s.toLowerCase()));
    }

    public static Object maskIfSensitive(String key, Object value) {
        if (value == null) return null;
        if (!isSensitive(key)) return value;
        return maskValue(value.toString());
    }

    // mask a msisdn +254700000000 → +254*****0000, with a fallback show first 3 and last 4
    public static String maskMsisdn(String msisdn) {
        if (msisdn == null || msisdn.length() < 6) return "***";
        var matcher = PHONE_PATTERN.matcher(msisdn);
        if (matcher.matches()) {
            return matcher.group(1) + "*".repeat(matcher.group(2).length()) + matcher.group(3);
        }
        return msisdn.substring(0, 3) + "*".repeat(msisdn.length() - 7) + msisdn.substring(msisdn.length() - 4);
    }

    // mask a sensitive value show first 2 and last 2 chars
    public static String maskValue(String value) {
        if (value == null) return null;
        if (value.length() <= 4) return "****";
        return value.substring(0, 2) + "*".repeat(value.length() - 4) + value.substring(value.length() - 2);
    }

    // Mask sensitive fields within a string representation (patterns msisdn=254712345678 or "msisdn":"254712345678").
    public static String maskInString(String input) {
        if (input == null) return null;
        String result = input;
        for (String key : SENSITIVE_KEYS) {
            result = result.replaceAll("(?i)(" + key + "=)(\\S+)", "$1****");
            result = result.replaceAll("(?i)(\"" + key + "\"\\s*:\\s*\")(.*?)(\")", "$1****$3");
        }
        return result;
    }

    // Mask all sensitive keys in a map. Returns a new map
    public static Map<String, Object> maskMap(Map<String, ?> input) {
        if (input == null) return Map.of();
        Map<String, Object> masked = new LinkedHashMap<>();
        input.forEach((k, v) -> masked.put(k, maskIfSensitive(k, v)));
        return masked;
    }
}
