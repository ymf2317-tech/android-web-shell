package io.dsh.shell;

import android.app.Activity;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.WebStorage;
import android.webkit.WebView;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

/** 只从桌面图标长按菜单进来，主界面里没有任何入口。 */
public class SettingsActivity extends Activity {

    private EditText input;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        int pad = Math.round(20 * getResources().getDisplayMetrics().density);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);

        TextView hint = new TextView(this);
        hint.setText(R.string.settings_hint);
        root.addView(hint);

        input = new EditText(this);
        input.setInputType(InputType.TYPE_TEXT_VARIATION_URI);
        input.setSingleLine(true);
        SharedPreferences sp = getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE);
        input.setText(sp.getString(MainActivity.KEY_URL, getString(R.string.site_url)));
        root.addView(input, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        Button save = new Button(this);
        save.setText(R.string.settings_save);
        save.setOnClickListener(v -> {
            String url = input.getText().toString().trim();
            if (!url.startsWith("http")) {
                url = "https://" + url;
            }
            sp.edit().putString(MainActivity.KEY_URL, url).apply();
            toastShort(R.string.settings_done);
            finish();
        });
        root.addView(save);

        Button clear = new Button(this);
        clear.setText(R.string.settings_clear);
        clear.setOnClickListener(v -> {
            WebStorage.getInstance().deleteAllData();
            CookieManager cm = CookieManager.getInstance();
            cm.removeAllCookies(null);
            cm.flush();
            toastShort(R.string.settings_cleared);
        });
        root.addView(clear);

        Button reset = new Button(this);
        reset.setText(R.string.settings_reset);
        reset.setOnClickListener(v -> {
            sp.edit().remove(MainActivity.KEY_URL).apply();
            input.setText(getString(R.string.site_url));
        });
        root.addView(reset);

        TextView note = new TextView(this);
        note.setGravity(Gravity.START);
        note.setText("清缓存后需要重新登录。改地址会立即生效。");
        root.addView(note);

        setContentView(root);
    }

    private void toastShort(int res) {
        android.widget.Toast.makeText(this, res, android.widget.Toast.LENGTH_SHORT).show();
    }
}
