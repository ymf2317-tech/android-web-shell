package io.dsh.shell;

import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Process;
import android.util.Log;

import java.io.File;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.FileWriter;

/** 兜底：任何未捕获异常都写日志并把栈显示出来，免得只看到"闪退"两个字。 */
public class ShellApp extends Application {

    public static final String CRASH_FILE = "crash.txt";
    private static final String TAG = "DshShell";

    @Override
    public void onCreate() {
        super.onCreate();
        final Context ctx = getApplicationContext();
        final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            String trace = describe(error);
            Log.e(TAG, trace);
            writeCrash(ctx, trace);
            try {
                Intent i = new Intent(ctx, CrashActivity.class);
                i.putExtra(CrashActivity.EXTRA_TRACE, trace);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                ctx.startActivity(i);
                // 给新界面一点时间起来，再退出
                Thread.sleep(600);
            } catch (Throwable ignored) {
            }
            if (previous != null) {
                previous.uncaughtException(thread, error);
            } else {
                Process.killProcess(Process.myPid());
                System.exit(10);
            }
        });
    }

    static String describe(Throwable error) {
        StringWriter sw = new StringWriter();
        sw.write("机型: " + Build.MANUFACTURER + " " + Build.MODEL + "\n");
        sw.write("系统: Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")\n");
        sw.write("时间: " + new java.util.Date() + "\n\n");
        PrintWriter pw = new PrintWriter(sw);
        error.printStackTrace(pw);
        pw.flush();
        return sw.toString();
    }

    static void writeCrash(Context ctx, String trace) {
        try {
            File dir = ctx.getExternalFilesDir(null);
            if (dir == null) {
                dir = ctx.getFilesDir();
            }
            if (!dir.exists()) {
                dir.mkdirs();
            }
            try (FileWriter w = new FileWriter(new File(dir, CRASH_FILE), false)) {
                w.write(trace);
            }
        } catch (Throwable ignored) {
        }
    }
}
