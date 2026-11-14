package io.dsh.shell;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.ActivityNotFoundException;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Message;
import android.provider.MediaStore;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.PermissionRequest;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.Toast;

/**
 * 极简外壳：一个 WebView 铺满整屏（含摄像头挖孔区），没有地址栏、没有按钮。
 * 设置页通过桌面图标长按菜单进入，主界面保持"什么都没有"。
 */
public class MainActivity extends Activity {

    private static final int REQ_FILES = 4001;
    private static final int REQ_CAMERA = 4002;
    private static final int REQ_IMAGES = 4003;
    static final String PREFS = "shell";
    static final String KEY_URL = "url";

    private WebView web;
    private ValueCallback<Uri[]> pendingFiles;
    /** 拍照时预插入 MediaStore 得到的可写 URI；取消或失败要删掉这条空记录。 */
    private Uri cameraOut;
    private String homeUrl;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        try {
            homeUrl = resolveHomeUrl();
            applyEdgeToEdge();
            web = new WebView(this);
            web.setLayoutParams(new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            web.setBackgroundColor(0x00000000);
            setContentView(web);
            hideSystemBars();
            web.post(this::hideSystemBars);
            configureWeb();
            if (state != null) {
                web.restoreState(state);
            } else if (!homeUrl.isEmpty()) {
                web.loadUrl(homeUrl);
            } else {
                // 通用壳（地址留空）：首启直接进设置页，让用户填自己的站点
                startActivity(new Intent(this, SettingsActivity.class));
                android.widget.Toast.makeText(this, R.string.need_url,
                        android.widget.Toast.LENGTH_LONG).show();
            }
        } catch (Throwable t) {
            showFatal(t);
        }
    }

    /** 起不来也算有个说法：把栈摊在屏幕上，别再只看到"闪退"。 */
    private void showFatal(Throwable t) {
        String trace = ShellApp.describe(t);
        ShellApp.writeCrash(this, trace);
        android.widget.TextView tv = new android.widget.TextView(this);
        tv.setTextIsSelectable(true);
        tv.setText(trace);
        int pad = Math.round(16 * getResources().getDisplayMetrics().density);
        tv.setPadding(pad, pad, pad, pad);
        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        sv.addView(tv);
        setContentView(sv);
    }

    private String resolveHomeUrl() {
        SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
        String saved = sp.getString(KEY_URL, null);
        return (saved == null || saved.trim().isEmpty()) ? getString(R.string.site_url) : saved;
    }

    /** 铺到挖孔区：这是 Chrome/PWA 做不到、必须由 App 自己声明的那一步。 */
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
        // 注意：这里不能碰 InsetsController。Android 17 上 DecorView 还没建，
        // PhoneWindow.getInsetsController() 会直接 NPE（1.0/1.0.1 闪退就是这个）。
    }

    private void hideSystemBars() {
        try {
            Window w = getWindow();
            if (Build.VERSION.SDK_INT >= 30) {
                View decor = w.getDecorView();
                if (decor == null) {
                    return;
                }
                WindowInsetsController c = w.getInsetsController();
                if (c == null) {
                    c = decor.getWindowInsetsController();
                }
                if (c != null) {
                    c.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                    c.setSystemBarsBehavior(
                            WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                    return;
                }
            }
            w.getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                            | View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        } catch (Throwable ignored) {
            // 全屏是锦上添花，任何情况下都不该把 App 弄崩
        }
    }

    private void configureWeb() {
        WebSettings s = web.getSettings();
        // 先去 WebView 标记，登录页才不会判定"浏览器不安全"（Google / 微软都看这个）
        WebTuning.normalizeUserAgent(s);
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setSupportMultipleWindows(false);
        s.setJavaScriptCanOpenWindowsAutomatically(false);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);
        if (Build.VERSION.SDK_INT >= 33) {
            s.setAlgorithmicDarkeningAllowed(true);
        } else if (Build.VERSION.SDK_INT >= 29) {
            s.setForceDark(WebSettings.FORCE_DARK_AUTO);
        }

        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(web, true);

        web.setWebChromeClient(new ShellChromeClient());
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
        web.setDownloadListener(this::download);
    }

    /**
     * 路由（统一模板，没有按站点分支）：
     *   1. 站点自己域名 → 当前页原地跳（DSH 内部路由不能开新层，否则返回栈乱）
     *   2. sites.json 显式写了 externalHosts 才甩系统浏览器（默认空 = 全部壳内处理）
     *   3. 其它 http(s) → 二层网页（InnerActivity），带着自己的返回栈
     */
    private boolean route(Uri u) {
        String scheme = u.getScheme() == null ? "" : u.getScheme().toLowerCase();
        if (!scheme.startsWith("http")) {
            openOutside(u);
            return true;
        }
        String host = u.getHost() == null ? "" : u.getHost().toLowerCase();
        String site = siteHost();
        if (!site.isEmpty() && (host.equals(site) || host.endsWith("." + site))) {
            return false;
        }
        // 登录域名一律留在当前 WebView：另开一层（或另开窗口）登录成功了，站点本体也拿不到
        // session —— OAuth 回来靠的是同一个 JS 环境（同窗口 redirect / window.opener）。
        if (isInlineHost(host)) {
            return false;
        }
        if (isExternalHost(host)) {
            openOutside(u);
            return true;
        }
        openInner(u);
        return true;
    }

    /** sites.json / build.mjs 的 inlineHosts：命中就原地加载，不开二层、不甩浏览器。 */
    private boolean isInlineHost(String host) {
        try {
            for (String h : getResources().getStringArray(R.array.site_inline_hosts)) {
                String t = h.trim().toLowerCase();
                if (!t.isEmpty() && (host.equals(t) || host.endsWith("." + t))) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    /** sites.json 的 externalHosts：命中就走系统浏览器，不进二层。 */
    private boolean isExternalHost(String host) {
        try {
            for (String h : getResources().getStringArray(R.array.site_external_hosts)) {
                String t = h.trim().toLowerCase();
                if (t.isEmpty()) {
                    continue;
                }
                if (host.equals(t) || host.endsWith("." + t)) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
            // 资源缺失就当没有白名单
        }
        return false;
    }

    /**
     * 站点主机名。写死地址的包由 build.mjs 生成 site_host；
     * 通用壳（地址留空）则退回运行时用户填的那个地址的 host。
     */
    private String siteHost() {
        String h = getString(R.string.site_host);
        if (h != null && !h.trim().isEmpty()) {
            return h.trim().toLowerCase();
        }
        try {
            String u = homeUrl;
            if (u == null || u.trim().isEmpty()) {
                u = resolveHomeUrl();
            }
            if (u != null && !u.trim().isEmpty()) {
                String host = Uri.parse(u).getHost();
                if (host != null) {
                    return host.toLowerCase();
                }
            }
        } catch (Throwable ignored) {
        }
        return "";
    }

    /** window.open / target=_blank：站内还是当前页，站外走二层或系统浏览器。 */
    private void followPopup(Uri u) {
        if (!route(u)) {
            web.loadUrl(u.toString());
        }
    }

    /** 二层网页：DSH 里点外链不再顶掉当前页。 */
    private void openInner(Uri u) {
        try {
            startActivity(new Intent(this, InnerActivity.class)
                    .putExtra(InnerActivity.EXTRA_URL, u.toString()));
        } catch (Throwable t) {
            openOutside(u);
        }
    }

    private void openOutside(Uri u) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, u).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (ActivityNotFoundException ignored) {
            // 没有可用 App，静默放弃
        }
    }

    /** 下载走系统下载器，并把 WebView 当前的 Cookie 带上（需要登录的站点才不会 401）。 */
    private void download(String url, String userAgent, String contentDisposition,
                          String mimeType, long contentLength) {
        try {
            String name = URLUtil.guessFileName(url, contentDisposition, mimeType);
            DownloadManager.Request req = new DownloadManager.Request(Uri.parse(url));
            String ck = CookieManager.getInstance().getCookie(url);
            if (ck != null && !ck.isEmpty()) {
                req.addRequestHeader("Cookie", ck);
            }
            if (userAgent != null) {
                req.addRequestHeader("User-Agent", userAgent);
            }
            req.setTitle(name);
            if (mimeType != null) {
                req.setMimeType(mimeType);
            }
            req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            req.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "DSH-Shell/" + name);
            DownloadManager dm = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
            if (dm == null) {
                throw new IllegalStateException("DownloadManager unavailable");
            }
            dm.enqueue(req);
            toast(R.string.download_started);
        } catch (Throwable t) {
            toast(R.string.download_failed);
            openOutside(Uri.parse(url));
        }
    }

    private void toast(int res) {
        Toast.makeText(this, res, Toast.LENGTH_SHORT).show();
    }

    private class ShellChromeClient extends WebChromeClient {

        /**
         * 上传：ColorOS 的 ACTION_GET_CONTENT 只会甩出文件管理器，没有「相机」那一项，
         * 所以自己弹一个三选：拍照 / 照片和视频 / 文件。
         */
        @Override
        public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> cb,
                                         FileChooserParams params) {
            if (pendingFiles != null) {
                pendingFiles.onReceiveValue(null);
            }
            pendingFiles = cb;
            final String type = acceptType(params);
            final boolean multi = params != null
                    && params.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE;
            final boolean imageOk = type.startsWith("image") || type.startsWith("*/*")
                    || type.startsWith("image/*") || type.equals("application/octet-stream");
            if (!imageOk) {
                launchFiles(type, multi);
                return true;
            }
            try {
                new AlertDialog.Builder(MainActivity.this)
                        .setTitle(R.string.pick_source)
                        .setItems(new CharSequence[]{
                                getString(R.string.pick_camera),
                                getString(R.string.pick_images),
                                getString(R.string.pick_file)
                        }, (d, which) -> {
                            if (which == 0) {
                                launchCamera();
                            } else if (which == 1) {
                                launchImages(type, multi);
                            } else {
                                launchFiles(type, multi);
                            }
                        })
                        .setOnCancelListener(d -> answerFiles(null))
                        .show();
            } catch (Throwable t) {
                answerFiles(null);
                return false;
            }
            return true;
        }

        /** target=_blank / window.open 一律在当前 WebView 里打开，不另开窗口。 */
        @Override
        public boolean onCreateWindow(WebView view, boolean isDialog, boolean isUserGesture,
                                      Message resultMsg) {
            final WebView tmp = new WebView(MainActivity.this);
            tmp.setWebViewClient(new WebViewClient() {
                @Override
                public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest req) {
                    tmp.destroy();
                    followPopup(req.getUrl());
                    return true;
                }

                @Override
                public boolean shouldOverrideUrlLoading(WebView v, String url) {
                    tmp.destroy();
                    followPopup(Uri.parse(url));
                    return true;
                }
            });
            WebView.WebViewTransport transport = (WebView.WebViewTransport) resultMsg.obj;
            transport.setWebView(tmp);
            resultMsg.sendToTarget();
            return true;
        }

        /** 摄像头 / 麦克风一律先拒绝（不申请运行时权限，保持"零权限"）。 */
        @Override
        public void onPermissionRequest(final PermissionRequest request) {
            runOnUiThread(() -> {
                request.deny();
                toast(R.string.need_camera);
            });
        }
    }

    private String acceptType(WebChromeClient.FileChooserParams params) {
        if (params == null) {
            return "*/*";
        }
        String[] types = params.getAcceptTypes();
        if (types != null) {
            for (String t : types) {
                if (t == null) {
                    continue;
                }
                String first = t.split(",")[0].trim();
                if (first.contains("/")) {
                    return first;
                }
            }
        }
        return "*/*";
    }

    /** 拍照：先往 MediaStore 插一条空记录拿到可写 content URI，免 FileProvider、免 CAMERA 权限。 */
    private void launchCamera() {
        try {
            ContentValues cv = new ContentValues();
            cv.put(MediaStore.Images.Media.DISPLAY_NAME, "dsh_" + System.currentTimeMillis() + ".jpg");
            cv.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
            cameraOut = getContentResolver()
                    .insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv);
            if (cameraOut == null) {
                throw new IllegalStateException("MediaStore insert returned null");
            }
            Intent i = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
            i.putExtra(MediaStore.EXTRA_OUTPUT, cameraOut);
            i.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivityForResult(i, REQ_CAMERA);
        } catch (Throwable t) {
            dropCameraOut();
            toast(R.string.camera_failed);
            answerFiles(null);
        }
    }

    /** 照片和视频：API 33+ 用系统相册选择器，老系统退回 ACTION_GET_CONTENT。 */
    private void launchImages(String type, boolean multi) {
        try {
            Intent i;
            if (Build.VERSION.SDK_INT >= 33) {
                i = new Intent(MediaStore.ACTION_PICK_IMAGES);
                if (multi) {
                    i.putExtra(MediaStore.EXTRA_PICK_IMAGES_MAX, 20);
                }
            } else {
                i = new Intent(Intent.ACTION_GET_CONTENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("image/*");
                if (multi) {
                    i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                }
            }
            startActivityForResult(i, REQ_IMAGES);
        } catch (Throwable t) {
            launchFiles(type, multi);
        }
    }

    /** 文件：系统文件管理器。 */
    private void launchFiles(String type, boolean multi) {
        try {
            Intent i = new Intent(Intent.ACTION_GET_CONTENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType(type);
            if (multi) {
                i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
            }
            startActivityForResult(Intent.createChooser(i, getString(R.string.pick_file)), REQ_FILES);
        } catch (Throwable t) {
            answerFiles(null);
        }
    }

    /** 只有第一次调用会生效：三路选择器都可能回来，必须避免重复回调把网页弄死。 */
    private void answerFiles(Uri[] uris) {
        ValueCallback<Uri[]> cb = pendingFiles;
        pendingFiles = null;
        if (cb != null) {
            cb.onReceiveValue(uris);
        }
    }

    private void dropCameraOut() {
        if (cameraOut != null) {
            try {
                getContentResolver().delete(cameraOut, null, null);
            } catch (Throwable ignored) {
            }
            cameraOut = null;
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQ_CAMERA) {
            if (resultCode == RESULT_OK && cameraOut != null) {
                Uri shot = cameraOut;
                cameraOut = null;
                answerFiles(new Uri[]{shot});
            } else {
                dropCameraOut();
                answerFiles(null);
            }
            return;
        }
        if (requestCode == REQ_FILES || requestCode == REQ_IMAGES) {
            Uri[] out = null;
            if (resultCode == RESULT_OK && data != null) {
                if (data.getClipData() != null) {
                    int n = data.getClipData().getItemCount();
                    out = new Uri[n];
                    for (int i = 0; i < n; i++) {
                        out[i] = data.getClipData().getItemAt(i).getUri();
                    }
                } else if (data.getData() != null) {
                    out = new Uri[]{data.getData()};
                }
            }
            answerFiles(out);
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    @Override
    public void onBackPressed() {
        if (web != null && web.canGoBack()) {
            web.goBack();
            return;
        }
        super.onBackPressed();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            hideSystemBars();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (web != null) {
            web.onResume();
            hideSystemBars();
            String now = resolveHomeUrl();
            if (!now.equals(homeUrl)) {
                homeUrl = now;
                web.loadUrl(now);
            }
        }
    }

    @Override
    protected void onPause() {
        if (web != null) {
            web.onPause();
        }
        CookieManager.getInstance().flush();
        super.onPause();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        if (web != null) {
            web.saveState(outState);
        }
    }

    @Override
    protected void onDestroy() {
        if (web != null) {
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }
}
