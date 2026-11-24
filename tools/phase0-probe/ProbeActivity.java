package com.mihealthsync.probe;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import com.mihealthsync.cloud.MiCloud;

import java.net.SocketTimeoutException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class ProbeActivity extends Activity {
    private static final String TAG = "MiHealthProbe";
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss", Locale.CHINA)
            .withZone(ZoneId.of("Asia/Shanghai"));
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private TextView status;
    private ImageView image;
    private Button start;
    private Button match;
    private Button mismatch;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        ScrollView scroll = new ScrollView(this);
        scroll.setOnApplyWindowInsetsListener((view, insets) -> {
            view.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                    insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            return insets;
        });
        body.setPadding(dp(22), dp(22), dp(22), dp(22));
        scroll.addView(body);
        setContentView(scroll);

        TextView title = new TextView(this);
        title.setText("小米云心率验证");
        title.setTextSize(25);
        body.addView(title);
        status = new TextView(this);
        status.setText("用于核对你本人过去 24 小时的小米云心率。凭据只在本次验证期间使用。测试不会写入 Health Connect。");
        status.setTextSize(16);
        status.setPadding(0, dp(14), 0, dp(14));
        body.addView(status);
        start = addButton(body, "开始验证", this::begin);
        image = new ImageView(this);
        image.setContentDescription("用于小米账号本人确认的二维码");
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        image.setVisibility(View.GONE);
        body.addView(image, new LinearLayout.LayoutParams(-1, dp(240)));

        match = addButton(body, "与小米运动健康显示一致", () -> acknowledge(true));
        mismatch = addButton(body, "未能确认一致", () -> acknowledge(false));
        match.setVisibility(View.GONE);
        mismatch.setVisibility(View.GONE);
        Log.i(TAG, "ready");
    }

    private void begin() {
        start.setEnabled(false);
        match.setVisibility(View.GONE);
        mismatch.setVisibility(View.GONE);
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
        status.setText("正在向小米申请一次性登录确认…");
        worker.execute(() -> {
            try {
                MiCloud cloud = new MiCloud();
                MiCloud.Challenge requested = cloud.start();
                Bitmap bitmap = BitmapFactory.decodeByteArray(requested.qrImage, 0, requested.qrImage.length);
                if (bitmap == null) throw new MiCloud.ProbeFailure("小米返回的二维码无法显示。", 0, null);
                runOnUiThread(() -> showChallenge(bitmap));
                MiCloud.Result result = cloud.readAfterApproval(requested);
                runOnUiThread(() -> showResult(result));
            } catch (MiCloud.ProbeFailure failure) {
                runOnUiThread(() -> showFailure(failure.getMessage()));
            } catch (SocketTimeoutException timeout) {
                runOnUiThread(() -> showFailure("小米响应超时或未在有效期内确认，可以重新开始。"));
            } catch (Exception failure) {
                String type = failure.getClass().getSimpleName();
                runOnUiThread(() -> showFailure("通信或响应解析失败（" + type + "）。"));
            }
        });
    }

    private void showChallenge(Bitmap bitmap) {
        if (isFinishing() || isDestroyed()) return;
        image.setImageBitmap(bitmap);
        image.setVisibility(View.VISIBLE);
        status.setText("请用小米账号或小米运动健康扫码确认本人登录。确认后这里会自动读取一次。");
        Log.i(TAG, "challenge_ready");
    }

    private void showResult(MiCloud.Result result) {
        if (isFinishing() || isDestroyed()) return;
        clearChallenge();
        if (result.count == 0) {
            status.setText("云端请求成功，最近 24 小时没有可用心率。页数：" + result.pages);
            Log.i(TAG, "read_empty pages=" + result.pages + " more=" + result.more);
            return;
        }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        status.setText("已读取 " + result.count + " 条心率采样。\n"
                + "最新一条（北京时间）" + TIME.format(Instant.ofEpochSecond(result.latest)) + "，"
                + result.latestBpm + " 次/分。\n\n请在小米运动健康中查看这个时间的心率并确认。"
                + (result.more ? "\n云端还有余页，本次读取范围可能不完整。" : ""));
        match.setVisibility(View.VISIBLE);
        mismatch.setVisibility(View.VISIBLE);
        Log.i(TAG, "read_records count=" + result.count + " pages=" + result.pages + " more=" + result.more
                + " earliest_epoch_s=" + result.earliest + " latest_epoch_s=" + result.latest + " fields=time,bpm");
    }

    private void acknowledge(boolean verified) {
        status.setText(verified ? "已记录本人核对一致。" : "已记录尚未核对一致。");
        match.setVisibility(View.GONE);
        mismatch.setVisibility(View.GONE);
        Log.i(TAG, "user_match=" + verified);
    }

    private void showFailure(String message) {
        if (isFinishing() || isDestroyed()) return;
        clearChallenge();
        status.setText(message);
        Log.i(TAG, "read_stopped: " + message);
    }

    private void clearChallenge() {
        image.setImageDrawable(null);
        image.setVisibility(View.GONE);
        start.setEnabled(true);
    }

    private Button addButton(LinearLayout body, String label, Runnable onClick) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setOnClickListener(view -> onClick.run());
        body.addView(button, new LinearLayout.LayoutParams(-1, -2));
        return button;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override protected void onDestroy() {
        worker.shutdownNow();
        super.onDestroy();
    }
}
