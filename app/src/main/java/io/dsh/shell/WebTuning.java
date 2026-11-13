package io.dsh.shell;

import android.webkit.WebSettings;

/**
 * WebView 统一调优 —— 一个地方改，MainActivity（一层）和 InnerActivity（二层）都吃到。
 *
 * 只做一件事：把 UA 里的 WebView 标记抹掉。
 * Chromium WebView 的默认 UA 长这样：
 *   Mozilla/5.0 (Linux; Android 17; XXX; wv) AppleWebKit/537.36 (KHTML, like Gecko)
 *   Version/4.0 Chrome/159.0.8154.4 Mobile Safari/537.36
 * 里面的 "; wv" 和 "Version/4.0" 就是 Google / 微软登录页判断"这不是正经浏览器"的依据，
 * 命中就直接给 "This browser or app may not be secure / disallowed_useragent" 掐掉登录。
 * 去掉这两段之后，站方看到的就是一个普通 Chrome，登录流程能走完。
 */
final class WebTuning {

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
}
