package io.github.jahn2007.fourma;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.text.DateFormat;
import java.util.Date;

import io.github.libxposed.service.XposedService;
import io.github.libxposed.service.XposedServiceHelper;

public class SettingsActivity extends Activity implements XposedServiceHelper.OnServiceListener {
    private XposedService service;
    private SharedPreferences remotePrefs;
    private SharedPreferences reportPrefs;
    private Switch captureSwitch;
    private Switch verboseSwitch;
    private Switch hideIconSwitch;
    private TextView statusView;
    private boolean binding;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        reportPrefs = getSharedPreferences(Prefs.REPORT_STORE, MODE_PRIVATE);
        setTitle("4MA 设置");
        setContentView(buildContent());
    }

    private View buildContent() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), systemTopInset() + dp(20), dp(20), dp(40));
        scroll.addView(root, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView title = text("4MA", 28, Typeface.BOLD);
        root.addView(title);

        TextView subtitle = text("7MA 小程序只读诊断模块 · v" + versionName(), 13, Typeface.NORMAL);
        subtitle.setTextColor(secondaryColor());
        subtitle.setPadding(0, dp(4), 0, dp(18));
        root.addView(subtitle);

        TextView notice = text(
                "当前版本只采集经过白名单过滤的运行信息，不执行借车、锁车或还车，"
                        + "不记录 Token、Cookie、请求头、手机号或完整网络正文。",
                15, Typeface.NORMAL);
        notice.setPadding(dp(14), dp(14), dp(14), dp(14));
        notice.setBackgroundColor(cardColor());
        root.addView(notice, matchWrap(dp(12)));

        captureSwitch = addSwitch(root, "启用诊断采集", "默认开启；修改后重启微信生效");
        verboseSwitch = addSwitch(root, "记录视图类名", "增加报告信息量，仍不记录普通文本内容");
        hideIconSwitch = addSwitch(root, "隐藏桌面图标", "隐藏后从 LSPosed 模块详情中的“模块设置”进入");

        statusView = text("正在连接 LSPosed 服务…", 13, Typeface.NORMAL);
        statusView.setTypeface(Typeface.MONOSPACE);
        statusView.setTextIsSelectable(true);
        statusView.setPadding(dp(14), dp(14), dp(14), dp(14));
        statusView.setBackgroundColor(cardColor());
        root.addView(statusView, matchWrap(dp(12)));

        Button refresh = button("刷新报告状态");
        refresh.setOnClickListener(v -> refreshStatus());
        root.addView(refresh, matchWrap(dp(8)));

        Button export = button("导出诊断报告");
        export.setOnClickListener(v -> exportReport());
        root.addView(export, matchWrap(dp(8)));

        Button clear = button("清空诊断报告");
        clear.setOnClickListener(v -> confirmClear());
        root.addView(clear, matchWrap(dp(8)));

        bindUiListeners();
        return scroll;
    }

    private void bindUiListeners() {
        captureSwitch.setOnCheckedChangeListener((button, checked) -> {
            if (binding) return;
            if (remotePrefs == null) {
                button.setChecked(!checked);
                toast("尚未连接 LSPosed 服务");
                return;
            }
            remotePrefs.edit().putBoolean(Prefs.KEY_CAPTURE_ENABLED, checked).apply();
            refreshStatus();
        });

        verboseSwitch.setOnCheckedChangeListener((button, checked) -> {
            if (binding) return;
            if (remotePrefs == null) {
                button.setChecked(!checked);
                toast("尚未连接 LSPosed 服务");
                return;
            }
            remotePrefs.edit().putBoolean(Prefs.KEY_DEBUG_VERBOSE, checked).apply();
        });

        hideIconSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean checked) {
                if (binding) return;
                if (!checked) {
                    applyLauncherVisibility(true);
                    return;
                }
                binding = true;
                hideIconSwitch.setChecked(false);
                binding = false;
                new AlertDialog.Builder(SettingsActivity.this)
                        .setTitle("隐藏桌面图标")
                        .setMessage("隐藏后，本设置页只能从 LSPosed 的 4MA 模块详情中打开。"
                                + "如果你的 LSPosed 没有“模块设置”入口，请不要隐藏。")
                        .setPositiveButton("确认隐藏", (dialog, which) -> {
                            applyLauncherVisibility(false);
                            binding = true;
                            hideIconSwitch.setChecked(true);
                            binding = false;
                        })
                        .setNegativeButton("取消", null)
                        .show();
            }
        });
    }

    private void bindRemoteValues() {
        if (remotePrefs == null) return;
        binding = true;
        captureSwitch.setChecked(remotePrefs.getBoolean(Prefs.KEY_CAPTURE_ENABLED, true));
        verboseSwitch.setChecked(remotePrefs.getBoolean(Prefs.KEY_DEBUG_VERBOSE, false));
        hideIconSwitch.setChecked(!isLauncherVisible());
        binding = false;
        refreshStatus();
    }

    private void refreshStatus() {
        int reports = 0;
        int chars = 0;
        StringBuilder processes = new StringBuilder();
        for (String key : Prefs.LOG_KEYS) {
            String value = reportPrefs.getString(key, "");
            if (!value.isEmpty()) {
                reports++;
                chars += value.length();
                if (processes.length() > 0) processes.append(", ");
                processes.append(key.substring("report_".length()));
            }
        }
        long updated = reportPrefs.getLong(Prefs.KEY_LAST_UPDATE, 0L);
        String updatedText = updated == 0L ? "尚无" : DateFormat.getDateTimeInstance().format(new Date(updated));
        String framework = service == null ? "未连接"
                : service.getFrameworkName() + " API " + service.getApiVersion();
        String capture = remotePrefs == null ? "未知"
                : (remotePrefs.getBoolean(Prefs.KEY_CAPTURE_ENABLED, true) ? "开启" : "关闭");
        statusView.setText("框架：" + framework
                + "\n采集：" + capture
                + "\n已有报告：" + reports + " 个进程，约 " + chars + " 字符"
                + "\n进程槽：" + (processes.length() == 0 ? "无" : processes)
                + "\n最后更新：" + updatedText);
    }

    private String buildReport() {
        StringBuilder out = new StringBuilder();
        out.append("4MA diagnostic report\n");
        out.append("moduleVersion=").append(versionName()).append('\n');
        out.append("exportedAt=").append(System.currentTimeMillis()).append("\n\n");
        if (remotePrefs == null) out.append("LSPosed service is not connected.\n\n");
        for (String key : Prefs.LOG_KEYS) {
            String value = reportPrefs.getString(key, "");
            if (value.isEmpty()) continue;
            out.append("=== ").append(key).append(" ===\n");
            out.append(value).append("\n\n");
        }
        if (out.indexOf("=== report_") < 0) out.append("No process report yet.\n");
        return out.toString();
    }

    private void exportReport() {
        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType("text/plain");
        intent.putExtra(Intent.EXTRA_SUBJECT, "4MA 诊断报告");
        intent.putExtra(Intent.EXTRA_TEXT, buildReport());
        startActivity(Intent.createChooser(intent, "导出 4MA 诊断报告"));
    }

    private void confirmClear() {
        new AlertDialog.Builder(this)
                .setTitle("清空诊断报告")
                .setMessage("删除模块保存的所有进程报告？")
                .setPositiveButton("清空", (dialog, which) -> {
                    SharedPreferences.Editor editor = reportPrefs.edit();
                    for (String key : Prefs.LOG_KEYS) editor.remove(key);
                    editor.remove(Prefs.KEY_LAST_UPDATE).apply();
                    refreshStatus();
                    toast("报告已清空");
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private boolean isLauncherVisible() {
        ComponentName alias = new ComponentName(this, getPackageName() + ".LauncherAlias");
        int state = getPackageManager().getComponentEnabledSetting(alias);
        return state != PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                && state != PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER;
    }

    private void applyLauncherVisibility(boolean visible) {
        try {
            ComponentName alias = new ComponentName(this, getPackageName() + ".LauncherAlias");
            getPackageManager().setComponentEnabledSetting(
                    alias,
                    visible ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                            : PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP);
            toast(visible ? "桌面图标已显示" : "桌面图标已隐藏");
        } catch (Throwable error) {
            toast("切换图标失败：" + error.getClass().getSimpleName());
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        ((FourMaApp) getApplication()).addListener(this, true);
    }

    @Override
    protected void onStop() {
        ((FourMaApp) getApplication()).removeListener(this);
        super.onStop();
    }

    @Override
    public void onServiceBind(XposedService service) {
        this.service = service;
        this.remotePrefs = service.getRemotePreferences(Prefs.GROUP);
        runOnUiThread(this::bindRemoteValues);
    }

    @Override
    public void onServiceDied(XposedService service) {
        this.service = null;
        this.remotePrefs = null;
        runOnUiThread(this::refreshStatus);
    }

    private Switch addSwitch(LinearLayout root, String title, String summary) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(dp(14), dp(10), dp(14), dp(10));
        row.setBackgroundColor(cardColor());

        Switch item = new Switch(this);
        item.setText(title);
        item.setTextSize(16);
        row.addView(item, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView hint = text(summary, 12, Typeface.NORMAL);
        hint.setTextColor(secondaryColor());
        hint.setPadding(0, dp(4), 0, 0);
        row.addView(hint);
        root.addView(row, matchWrap(dp(10)));
        return item;
    }

    private Button button(String label) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        return button;
    }

    private TextView text(String value, float size, int style) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(primaryColor());
        view.setTypeface(Typeface.DEFAULT, style);
        return view;
    }

    private LinearLayout.LayoutParams matchWrap(int bottomMargin) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.bottomMargin = bottomMargin;
        return params;
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private int systemTopInset() {
        int id = getResources().getIdentifier("status_bar_height", "dimen", "android");
        return id == 0 ? 0 : getResources().getDimensionPixelSize(id);
    }

    private boolean dark() {
        int mode = getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
        return mode == android.content.res.Configuration.UI_MODE_NIGHT_YES;
    }

    private int primaryColor() {
        return dark() ? Color.WHITE : 0xFF1F2328;
    }

    private int secondaryColor() {
        return dark() ? 0xFFB0B5BD : 0xFF667085;
    }

    private int cardColor() {
        return dark() ? 0xFF22252A : 0xFFF2F4F7;
    }

    private String versionName() {
        try {
            PackageInfo info = getPackageManager().getPackageInfo(getPackageName(), 0);
            return info.versionName == null ? "?" : info.versionName;
        } catch (Throwable ignored) {
            return "?";
        }
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }
}
