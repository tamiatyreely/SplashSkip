
package io.github.ferstar.wpsroottoast;

import java.util.regex.Pattern;

/**
 * 精确匹配 WPS Root 风险提醒。
 * 不影响其他 Toast。
 */
final class RootWarningMatcher {

    private static final Pattern SPACES =
            Pattern.compile("[\\s\\u00a0\\u3000]");

    private static final String WARNING =
            "设备已Root，使用WPSOffice可能存在安全风险。";

    private RootWarningMatcher() {
    }

    static boolean matches(CharSequence text) {
        return text != null
                && WARNING.equals(
                        SPACES.matcher(text).replaceAll("")
                );
    }
}
