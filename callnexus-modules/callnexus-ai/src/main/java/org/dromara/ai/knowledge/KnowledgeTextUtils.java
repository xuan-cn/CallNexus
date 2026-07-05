package org.dromara.ai.knowledge;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.Normalizer;
import java.util.HexFormat;
import java.util.Locale;

public final class KnowledgeTextUtils {
    private KnowledgeTextUtils() {}
    public static String normalizeQuestion(String value) {
        if (value == null) return "";
        String text = Normalizer.normalize(value, Normalizer.Form.NFKC).trim().toLowerCase(Locale.ROOT);
        return text.replaceAll("[\\p{P}\\p{S}\\s]+", "");
    }
    public static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) { throw new IllegalStateException(e); }
    }
}
