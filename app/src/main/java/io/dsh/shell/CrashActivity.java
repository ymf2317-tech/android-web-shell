package io.dsh.shell;

import android.app.Activity;
import android.os.Bundle;
import android.widget.ScrollView;
import android.widget.TextView;

/** 崩溃时把栈直接摊在屏幕上，方便截图。 */
public class CrashActivity extends Activity {

    static final String EXTRA_TRACE = "trace";

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        String trace = getIntent() == null ? null : getIntent().getStringExtra(EXTRA_TRACE);
        if (trace == null) {
            trace = "（没有拿到栈信息）";
        }
        TextView tv = new TextView(this);
        tv.setText(trace);
        tv.setTextIsSelectable(true);
        int pad = Math.round(16 * getResources().getDisplayMetrics().density);
        tv.setPadding(pad, pad, pad, pad);
        ScrollView sv = new ScrollView(this);
        sv.addView(tv);
        setContentView(sv);
    }
}
