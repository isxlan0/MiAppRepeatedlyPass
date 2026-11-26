package io.github.isxlan0.miappxspace;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/** Module information and project links; opening this page does not require root. */
public final class MainActivity extends Activity {
    private LinearLayout content;
    private int background, surface, ink, muted, accent, border;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        render();
    }
    private int dp(int size) { return Math.round(size * getResources().getDisplayMetrics().density); }
    private GradientDrawable panel(int color) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color); shape.setCornerRadius(dp(16)); shape.setStroke(dp(1), border);
        return shape;
    }
    private LinearLayout column() {
        LinearLayout layout = new LinearLayout(this); layout.setOrientation(LinearLayout.VERTICAL); return layout;
    }
    private TextView text(LinearLayout parent, String copy, int size, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(copy); view.setTextSize(size); view.setTextColor(color);
        view.setTypeface(Typeface.DEFAULT, bold ? Typeface.BOLD : Typeface.NORMAL);
        view.setLineSpacing(dp(4), 1f);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.bottomMargin = dp(8); parent.addView(view, params); return view;
    }
    private LinearLayout card(String heading) {
        LinearLayout card = column(); card.setPadding(dp(20), dp(20), dp(20), dp(16)); card.setBackground(panel(surface));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2); params.bottomMargin = dp(16);
        content.addView(card, params);
        if (heading != null) text(card, heading, 18, ink, true);
        return card;
    }
    private void link(LinearLayout parent, String title, String url) {
        Button button = new Button(this); button.setText(title); button.setTextSize(14); button.setAllCaps(false);
        button.setTextColor(accent);
        button.setBackgroundTintList(ColorStateList.valueOf(surface));
        button.setMinHeight(dp(48)); button.setPadding(dp(12), dp(8), dp(12), dp(8));
        button.setOnClickListener(view -> open(url)); parent.addView(button, new LinearLayout.LayoutParams(-1, -2));
    }
    private void open(String url) {
        try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); }
        catch (ActivityNotFoundException missing) { Toast.makeText(this, "未找到可打开链接的浏览器", Toast.LENGTH_LONG).show(); }
    }
    private void render() {
        boolean dark = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        background = Color.parseColor(dark ? "#181818" : "#F4F6FA"); surface = Color.parseColor(dark ? "#272727" : "#FFFFFF");
        ink = Color.parseColor(dark ? "#F5F5F5" : "#172132"); muted = Color.parseColor(dark ? "#B8BDC7" : "#5D687A");
        accent = Color.parseColor(dark ? "#88A8FF" : "#345BD6"); border = Color.parseColor(dark ? "#404040" : "#E2E7F0");
        LinearLayout root = column(); root.setBackgroundColor(background);
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            view.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(), insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            return insets.consumeSystemWindowInsets();
        });
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true);
        content = column(); content.setPadding(dp(24), dp(24), dp(24), dp(24));
        scroll.addView(content, new ScrollView.LayoutParams(-1, -2)); root.addView(scroll, new LinearLayout.LayoutParams(-1, -1));
        setContentView(root); root.requestApplyInsets();
        getWindow().getDecorView().setSystemUiVisibility(dark ? 0 : View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        text(content, "MiApp XSpace", 30, ink, true);
        text(content, "多分身模块  ·  " + BuildConfig.VERSION_NAME, 14, muted, false);
        renderAbout();
    }
    private void renderAbout() {
        LinearLayout project = card("关于模块");
        text(project, AppInfo.DESCRIPTION, 16, ink, false);
        text(project, "版本 " + BuildConfig.VERSION_NAME + "（" + BuildConfig.VERSION_CODE + "）\n作者 Xiao Lan / isxlan0\n模块 ID miapp_xspace\n包名 " + getPackageName(), 14, muted, false);
        text(project, "模块开源地址：\n" + AppInfo.SOURCE, 14, accent, false).setTextIsSelectable(true);
        link(project, "在 GitHub 查看源码", AppInfo.SOURCE);
        link(project, "反馈问题", AppInfo.SOURCE + "/issues");
        LinearLayout credits = card("致谢");
        text(credits, "感谢以下项目提供的接口、运行框架与实现参考。", 14, muted, false);
        for (String[] credit : AppInfo.CREDITS) {
            link(credits, credit[0], credit[2]); text(credits, credit[1], 14, muted, false);
        }
        text(credits, "原始转发库来自适配设备的 HyperOS ROM；小米 / HyperOS 系统组件并非本项目原创，其权利归相应权利人。", 14, muted, false);
        LinearLayout known = card("已知问题");
        text(known, "分身 3 和 4 暂时无法分别设置通知管理、权限管理。其他设备与系统版本仍需验证。", 14, muted, false);
        LinearLayout uninstall = card("卸载说明"); text(uninstall, AppInfo.UNINSTALL, 14, ink, false);
        LinearLayout disclaimer = card("免责声明"); text(disclaimer, AppInfo.DISCLAIMER, 14, muted, false);
        text(disclaimer, "本项目原创代码使用 MIT License；依赖项目与系统组件遵循各自许可证。", 14, muted, false);
        link(disclaimer, "查看项目许可证", AppInfo.SOURCE + "/blob/main/LICENSE");
        text(content, "MiApp XSpace · 开源项目", 12, muted, false).setGravity(Gravity.CENTER);
    }
}
