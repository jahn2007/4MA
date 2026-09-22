package io.github.jahn2007.fourma;

import java.util.Arrays;
import java.util.List;

public final class Prefs {
    public static final String GROUP = "fourma_settings";
    public static final String REPORT_STORE = "fourma_reports";
    public static final String KEY_CAPTURE_ENABLED = "capture_enabled";
    public static final String KEY_DEBUG_VERBOSE = "debug_verbose";
    public static final String KEY_HIDE_ICON = "hide_icon";
    public static final String KEY_LAST_UPDATE = "last_update";
    public static final String KEY_HOT_RELOAD_PREFIX = "hot_reload_";

    public static final List<String> LOG_KEYS = Arrays.asList(
            "report_main",
            "report_appbrand0",
            "report_appbrand1",
            "report_appbrand2",
            "report_appbrand3",
            "report_appbrand4",
            "report_appbrand5",
            "report_appbrand_other"
    );

    private Prefs() {
    }

    public static String reportKey(String processName) {
        if ("com.tencent.mm".equals(processName)) return "report_main";
        if (processName != null && processName.startsWith("com.tencent.mm:appbrand")) {
            String suffix = processName.substring("com.tencent.mm:appbrand".length());
            if (suffix.matches("[0-5]")) return "report_appbrand" + suffix;
            return "report_appbrand_other";
        }
        return "report_appbrand_other";
    }

    public static String hotReloadKey(String reportKey) {
        return KEY_HOT_RELOAD_PREFIX + reportKey;
    }
}
