package io.dsh.shell;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.text.InputType;
import android.view.ViewGroup;
import android.webkit.HttpAuthHandler;
import android.webkit.WebSettings;
import android.widget.EditText;
import android.widget.LinearLayout;

/**
 * WebView 统一调优 —— 一个地方改，MainActivity（一层）和 InnerActivity（二层）都吃到。
 *
 * 两件事：
 *  1. 把 UA 里的 WebView 标记抹掉。
 *     Chromium WebView 的默认 UA 长这样：
 *       Mozilla/5.0 (Linux; Android 17; XXX; wv) AppleWebKit/537.36 (KHTML, like Gecko)
 *       Version/4.0 Chrome/159.0.8154.4 Mobile Safari/537.36
 *     里面的 "; wv" 和 "Version/4.0" 就是 Google / 微软登录页判断"这不是正经浏览器"的依据，
 *     命中就直接给 "This browser or app may not be secure / disallowed_useragent" 掐掉登录。
 *     去掉这两段之后，站方看到的就是一个普通 Chrome，登录流程能走完。
 *  2. HTTP 基本认证（401 + WWW-Authenticate: Basic）。
 *     WebView 默认把 401 直接掐掉，页面就白屏；SillyTavern 开了 basicAuthMode 的站点必须靠这里。
 *     第一次打会弹一个用户名/密码框，填完记在 SharedPreferences（按 host+realm 存），以后自动带。
 */
final class WebTuning {

    private static final String AUTH_PREFS = "httpauth";

    private WebTuning() {
    }

    static void normalizeUserAgent(WebSettings s) {
        try {
            String ua = s.getUserAgentString();
            if (ua == null || ua.isEmpty()) {
                return;
            }
            String clean = ua.replace("; wv", "").replace(" Version/4.0", "");
            if (!clean.equals(ua)) {
                s.setUserAgentString(clean);
            }
        } catch (Throwable ignored) {
            // UA 改不了也不该影响启动
        }
    }

    /**
     * 站点自带的账号（build.mjs 从 auth.local.json 生成 res/values/auth.xml，私用不进仓库）：
     * 有就直接 proceed，不弹框，用户看不到登录框。
     */
    static boolean tryBuiltinAuth(final Activity act, final HttpAuthHandler handler,
                                  final String host, final String siteHost) {
        if (act == null || handler == null) {
            return false;
        }
        String u = act.getString(R.string.site_auth_user);
        String p = act.getString(R.string.site_auth_pass);
        if (u == null || u.isEmpty()) {
            return false;
        }
        if (siteHost != null && !siteHost.isEmpty()
                && host != null && !host.isEmpty() && !host.equalsIgnoreCase(siteHost)) {
            return false;
        }
        handler.proceed(u, p);
        return true;
    }

    /**
     * 收到 401 Basic 认证请求时的统一处理：有记住的账号就直接过，没有就弹框问一次。
     * 必须在 UI 线程调用（WebViewClient 的回调本身就在 UI 线程）。
     */
    static void handleHttpAuth(final Activity act, final HttpAuthHandler handler,
                               final String host, final String realm) {
        if (act == null || act.isFinishing() || handler == null) {
            if (handler != null) {
                handler.cancel();
            }
            return;
        }
        final String key = (host == null ? "" : host) + "|" + (realm == null ? "" : realm);
        final SharedPreferences sp = act.getSharedPreferences(AUTH_PREFS, Activity.MODE_PRIVATE);
        String saved = sp.getString(key, null);
        if (saved != null) {
            int nl = saved.indexOf('\n');
            if (nl > 0) {
                String u = saved.substring(0, nl);
                String p = saved.substring(nl + 1);
                if (!u.isEmpty()) {
                    handler.proceed(u, p);
                    return;
                }
            }
        }

        final EditText user = new EditText(act);
        user.setSingleLine(true);
        user.setHint("用户名");
        final EditText pass = new EditText(act);
        pass.setSingleLine(true);
        pass.setHint("密码");
        pass.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);

        LinearLayout box = new LinearLayout(act);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (20 * act.getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad / 2, pad, 0);
        box.addView(user, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        box.addView(pass, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        try {
            new AlertDialog.Builder(act)
                    .setTitle(host == null ? "需要登录" : "需要登录 · " + host)
                    .setView(box)
                    .setPositiveButton("登录", (d, w) -> {
                        String u = user.getText().toString().trim();
                        String p = pass.getText().toString();
                        if (u.isEmpty()) {
                            handler.cancel();
                            return;
                        }
                        sp.edit().putString(key, u + "\n" + p).apply();
                        handler.proceed(u, p);
                    })
                    .setNegativeButton("取消", (d, w) -> handler.cancel())
                    .setOnCancelListener(d -> handler.cancel())
                    .show();
        } catch (Throwable t) {
            handler.cancel();
        }
    }
}
