package io.dsh.shell;

import android.app.Activity;
import android.app.DownloadManager;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.DownloadListener;
import android.webkit.URLUtil;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/**
 * 二层网页：在壳内点开的外链落在这里，自带一条极简返回栏和自己的前进/后退栈。
 * 和 MainActivity 用同一套 Settings / 路由规则，只是多了一条返回栏——不为它单开一套逻辑。
 */
public class InnerActivity extends Activity {

    public static final String EXTRA_URL = "url";

    private WebView web;
    private TextView title;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        try {
            String url = getIntent() == null ? null : getIntent().getStringExtra(EXTRA_URL);
            if (url == null || url.isEmpty()) {
                finish();
                return;
            }
            applyEdgeToEdge();
            setContentView(buildUi());
            hideSystemBars();
            configureWeb();
            if (state != null) {
                web.restoreState(state);
            } else {
                web.loadUrl(url);
            }
        } catch (Throwable t) {
            Toast.makeText(this, "打开失败：" + t, Toast.LENGTH_LONG).show();
            finish();
        }
    }

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF151517);

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(0xFF151517);
        int pad = dp(8);
        bar.setPadding(pad, dp(4), pad, dp(4));

        TextView back = new TextView(this);
        back.setText("‹");
        back.setTextSize(26);
        back.setTextColor(0xFFE8E8EA);
        back.setGravity(Gravity.CENTER);
        back.setPadding(dp(10), 0, dp(14), dp(2));
        back.setOnClickListener(v -> goBack());
        bar.addView(back, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));

        title = new TextView(this);
        title.setTextColor(0xFFB9B9BF);
        title.setTextSize(13);
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        bar.addView(title, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView close = new TextView(this);
        close.setText("✕");
        close.setTextSize(16);
        close.setTextColor(0xFFB9B9BF);
        close.setGravity(Gravity.CENTER);
        close.setPadding(dp(12), 0, dp(12), 0);
        close.setOnClickListener(v -> finish());
        bar.addView(close, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));

        root.addView(bar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(44)));

        FrameLayout frame = new FrameLayout(this);
        web = new WebView(this);
        web.setBackgroundColor(Color.WHITE);
        frame.addView(web, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(frame, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        return root;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private void applyEdgeToEdge() {
        Window w = getWindow();
        if (Build.VERSION.SDK_INT >= 30) {
            w.setDecorFitsSystemWindows(false);
        }
        if (Build.VERSION.SDK_INT >= 28) {
            WindowManager.LayoutParams lp = w.getAttributes();
            lp.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            w.setAttributes(lp);
        }
        w.setStatusBarColor(0);
        w.setNavigationBarColor(0);
    }

    private void hideSystemBars() {
        try {
            Window w = getWindow();
            if (Build.VERSION.SDK_INT >= 30) {
                View decor = w.getDecorView();
                WindowInsetsController c = w.getInsetsController();
                if (c == null && decor != null) {
                    c = decor.getWindowInsetsController();
                }
                if (c != null) {
                    c.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                    c.setSystemBarsBehavior(
                            WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                }
            }
        } catch (Throwable ignored) {
            // 全屏不是必需品
        }
    }

    private void configureWeb() {
        WebSettings s = web.getSettings();
        // 二层页面同样去 WebView 标记：在这里点"用 Google 登录"能直接走完
        WebTuning.normalizeUserAgent(s);
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        if (Build.VERSION.SDK_INT >= 33) {
            s.setAlgorithmicDarkeningAllowed(true);
        } else if (Build.VERSION.SDK_INT >= 29) {
            s.setForceDark(WebSettings.FORCE_DARK_AUTO);
        }
        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(web, true);

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onReceivedTitle(WebView v, String t) {
                title.setText(t == null || t.isEmpty() ? "" : t);
            }
        });
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest req) {
                return route(req.getUrl());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView v, String url) {
                return route(Uri.parse(url));
            }
        });
        web.setDownloadListener(new DownloadListener() {
            @Override
            public void onDownloadStart(String url, String ua, String contentDisposition,
                                        String mimeType, long len) {
                try {
                    String name = URLUtil.guessFileName(url, contentDisposition, mimeType);
                    DownloadManager.Request req = new DownloadManager.Request(Uri.parse(url));
                    String ck = CookieManager.getInstance().getCookie(url);
                    if (ck != null) {
                        req.addRequestHeader("Cookie", ck);
                    }
                    req.setNotificationVisibility(
                            DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                    req.setDestinationInExternalPublicDir(
                            android.os.Environment.DIRECTORY_DOWNLOADS, name);
                    DownloadManager dm = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
                    dm.enqueue(req);
                } catch (Throwable t) {
                    try {
                        startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
                    } catch (ActivityNotFoundException ignored) {
                    }
                }
            }
        });
    }

    /** 二层里只有一档：显式 externalHosts 丢系统浏览器，其余原地跳（含登录域名），保证返回键可预期。 */
    private boolean route(Uri u) {
        String scheme = u.getScheme() == null ? "" : u.getScheme().toLowerCase();
        if (!scheme.startsWith("http")) {
            openOutside(u);
            return true;
        }
        String host = u.getHost() == null ? "" : u.getHost().toLowerCase();
        if (isExternalHost(host)) {
            openOutside(u);
            return true;
        }
        return false;
    }

    private boolean isExternalHost(String host) {
        try {
            for (String h : getResources().getStringArray(R.array.site_external_hosts)) {
                String t = h.trim().toLowerCase();
                if (!t.isEmpty() && (host.equals(t) || host.endsWith("." + t))) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private void openOutside(Uri u) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, u)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (ActivityNotFoundException ignored) {
        }
    }

    private void goBack() {
        if (web != null && web.canGoBack()) {
            web.goBack();
        } else {
            finish();
        }
    }

    @Override
    public void onBackPressed() {
        goBack();
    }

    @Override
    protected void onResume() {
        super.onResume();
        hideSystemBars();
    }

    @Override
    protected void onPause() {
        super.onPause();
        CookieManager.getInstance().flush();
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        if (web != null) {
            web.saveState(out);
        }
    }
}
