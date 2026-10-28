package com.local.smartlogistics;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Bundle;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.net.Uri;
import android.text.InputType;
import android.text.method.ScrollingMovementMethod;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@SuppressLint("SetTextI18n")
public final class MainActivity extends Activity {
    private static final int REQUEST_EXPORT_DIAGNOSTIC_LOG = 7101;
    private static final long QUERY_COOLDOWN_MS = 1_000L;
    private static final long RECENT_RESULT_CACHE_MS = 8_000L;
    private static final String DEFAULT_CAMPUS = "旗山校区";
    private static final int BLUE = Color.rgb(10, 92, 158);
    private static final int ELECTRIC = Color.rgb(217, 119, 6);
    private static final int WATER = Color.rgb(11, 127, 140);
    private static final int SUCCESS = Color.rgb(46, 125, 50);
    private static final int TEXT = Color.rgb(23, 33, 47);
    private static final int MUTED = Color.rgb(102, 117, 134);
    private static final int DIVIDER = Color.rgb(230, 235, 240);
    private static final int BACKGROUND = Color.rgb(243, 246, 248);

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final LinkedHashMap<String, List<String>> dormZones = new LinkedHashMap<>();
    private final LinkedHashMap<String, List<String>> roomSuggestions = new LinkedHashMap<>();

    private SharedPreferences preferences;
    private ScrollView pageScroll;
    private EditText phoneInput;
    private EditText studentInput;
    private EditText codeInput;
    private AutoCompleteTextView roomInput;
    private Spinner zoneSpinner;
    private Spinner floorSpinner;
    private Button sendCodeButton;
    private Button loginButton;
    private Button clearLoginButton;
    private Button queryButton;
    private ProgressBar progress;
    private ProgressBar authProgress;
    private TextView statusText;
    private TextView authFeedback;
    private TextView periodText;
    private TextView remainingElectric;
    private TextView remainingWater;
    private TextView remainingElectricCost;
    private TextView remainingWaterCost;
    private TextView usedElectric;
    private TextView usedWater;
    private TextView electricCost;
    private TextView waterCost;
    private TextView meterOneUsage;
    private TextView meterTwoUsage;
    private TextView meterOneCost;
    private TextView meterTwoCost;
    private LinearLayout dailyRows;
    private LinearLayout authBody;
    private LinearLayout authCard;
    private TextView authAction;
    private TextView sessionStatus;
    private TextView targetSummary;
    private SmartLogisticsClient authClient;
    private String smsPhone = "";
    private boolean queryBusy;
    private boolean authBusy;
    private volatile boolean sessionClearRequested;
    private volatile boolean destroyed;
    private int diagnosticTapCount;
    private long lastDiagnosticTapAt;
    private long lastQueryFinishedAt;
    private long lastSuccessfulQueryAt;
    private String lastSuccessfulTargetKey = "";
    private UsageSummary lastSuccessfulSummary;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        DiagnosticLog.initialize(this);
        DiagnosticLog.info("应用启动 · 版本 " + appVersion());
        preferences = getSharedPreferences("user_settings", MODE_PRIVATE);
        loadDormZones();
        boolean pendingSms = savedInstanceState != null
                && savedInstanceState.getBoolean("sms_pending", false);
        if (pendingSms) {
            setContentView(buildLoginScreen());
            smsPhone = savedInstanceState.getString("sms_phone", "");
            authClient = new SmartLogisticsClient(this);
            if (value(phoneInput).isEmpty()) {
                phoneInput.setText(smsPhone);
            }
            setSessionStatus("待验证", ELECTRIC, Color.rgb(252, 247, 238));
            setAuthBusy(false, "验证码会话已恢复，请重新输入验证码");
        } else {
            setContentView(buildCheckingScreen());
            checkStoredSession();
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        outState.putBoolean("sms_pending", authClient != null && !smsPhone.isEmpty());
        outState.putString("sms_phone", smsPhone);
        super.onSaveInstanceState(outState);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_EXPORT_DIAGNOSTIC_LOG || resultCode != RESULT_OK
                || data == null || data.getData() == null) {
            return;
        }
        writeDiagnosticExport(data.getData());
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        main.removeCallbacksAndMessages(null);
        executor.shutdownNow();
        super.onDestroy();
    }

    private View buildCheckingScreen() {
        LinearLayout page = vertical();
        page.setGravity(Gravity.CENTER_HORIZONTAL);
        page.setPadding(dp(14), 0, dp(14), dp(28));
        page.setBackgroundColor(BACKGROUND);

        LinearLayout header = vertical();
        header.setPadding(dp(24), dp(42), dp(24), dp(76));
        header.setBackground(headerGradient());
        LinearLayout top = horizontal();
        top.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout brand = vertical();
        TextView checkingBrandTitle = text("智慧后勤", 30, Color.WHITE, true);
        checkingBrandTitle.setOnClickListener(view -> handleDiagnosticTitleTap());
        brand.addView(checkingBrandTitle);
        top.addView(brand, weighted());
        sessionStatus = pill("●  检查中", Color.argb(235, 242, 252, 247), BLUE);
        top.addView(sessionStatus);
        header.addView(top);
        LinearLayout.LayoutParams headerParams = matchWrap();
        headerParams.setMargins(-dp(14), 0, -dp(14), -dp(32));
        page.addView(header, headerParams);

        LinearLayout checkingCard = card();
        checkingCard.setGravity(Gravity.CENTER);
        checkingCard.setElevation(dp(5));
        ProgressBar checkingProgress = new ProgressBar(this);
        checkingCard.addView(checkingProgress,
                new LinearLayout.LayoutParams(dp(42), dp(42)));
        TextView checkingTitle = text("正在检查登录状态", 17, TEXT, true);
        checkingTitle.setGravity(Gravity.CENTER);
        checkingCard.addView(checkingTitle, verticalGap(14, 0));
        TextView checkingCaption = text("正在安全验证本机保存的 Cookie", 12, MUTED, false);
        checkingCaption.setGravity(Gravity.CENTER);
        checkingCard.addView(checkingCaption, verticalGap(5, 2));
        page.addView(checkingCard, cardParams());
        return page;
    }

    private View buildLoginScreen() {
        pageScroll = new ScrollView(this);
        pageScroll.setFillViewport(true);
        pageScroll.setBackgroundColor(Color.WHITE);

        LinearLayout page = vertical();
        page.setBackgroundColor(Color.WHITE);
        pageScroll.addView(page, matchWrap());

        page.addView(brandHero("", false), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(182)));

        LinearLayout content = vertical();
        content.setPadding(dp(18), dp(14), dp(18), dp(28));
        page.addView(content, matchWrap());

        authCard = vertical();
        LinearLayout authHeader = horizontal();
        authHeader.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout authHeading = vertical();
        authHeading.addView(sectionBadge("♙", "登录账户"));
        authHeader.addView(authHeading, weighted());
        authAction = text("?  帮助", 13, BLUE, true);
        authAction.setPadding(dp(16), dp(10), 0, dp(10));
        authHeader.addView(authAction);
        authCard.addView(authHeader);

        authBody = vertical();
        authBody.setPadding(0, dp(14), 0, 0);
        phoneInput = input("手机号", InputType.TYPE_CLASS_PHONE);
        studentInput = input("学号", InputType.TYPE_CLASS_NUMBER);
        codeInput = input("短信验证码", InputType.TYPE_CLASS_NUMBER);
        phoneInput.setText(preferences.getString("phone", ""));
        studentInput.setText(preferences.getString("student_id", ""));
        LinearLayout identityRow = horizontal();
        identityRow.addView(labeled("手机号", phoneInput), weightedRight(5));
        identityRow.addView(labeled("学号", studentInput), weightedLeft(5));
        authBody.addView(identityRow, fieldParams());
        LinearLayout captchaRow = horizontal();
        captchaRow.addView(labeled("验证码", codeInput), weightedRight(5));
        sendCodeButton = button("发送验证码", false);
        LinearLayout.LayoutParams sendParams = weightedLeft(5);
        sendParams.setMargins(dp(5), dp(19), 0, 0);
        captchaRow.addView(sendCodeButton, sendParams);
        authBody.addView(captchaRow, fieldParams());
        loginButton = button("登录", true);
        loginButton.setBackground(new RippleDrawable(
                ColorStateList.valueOf(Color.argb(48, 255, 255, 255)),
                gradient(Color.rgb(34, 178, 246), Color.rgb(5, 92, 232), 10), null));
        authBody.addView(loginButton, matchWrap());
        LinearLayout authStatusRow = horizontal();
        authStatusRow.setGravity(Gravity.CENTER_VERTICAL);
        authProgress = new ProgressBar(this, null, android.R.attr.progressBarStyleSmall);
        authProgress.setVisibility(View.GONE);
        authStatusRow.addView(authProgress, new LinearLayout.LayoutParams(dp(20), dp(20)));
        authFeedback = text("正在检查登录状态…", 11, MUTED, false);
        authFeedback.setPadding(dp(7), 0, 0, 0);
        authStatusRow.addView(authFeedback, weighted());
        authBody.addView(authStatusRow, verticalGap(9, 0));
        authCard.addView(authBody);
        authAction.setOnClickListener(view -> showLoginHelp());
        content.addView(authCard, matchWrap());

        TextView footer = text(appVersion() + "  ·  登录信息由 Android Keystore 保护", 11,
                Color.rgb(135, 148, 162), false);
        footer.setGravity(Gravity.CENTER);
        content.addView(footer, verticalGap(18, 0));

        sendCodeButton.setOnClickListener(view -> sendSms());
        loginButton.setOnClickListener(view -> login());
        return pageScroll;
    }

    private void checkStoredSession() {
        DiagnosticLog.info("开始检查本机登录状态");
        executor.execute(() -> {
            try {
                SmartLogisticsClient client = new SmartLogisticsClient(this);
                if (client.hasValidStoredLogin()) {
                    DiagnosticLog.success("本机登录状态有效");
                    postUi(this::showMainScreen);
                } else {
                    DiagnosticLog.warning("本机登录状态无效，需要重新登录");
                    postUi(() -> showLoginScreen("登录已失效，请获取验证码后登录"));
                }
            } catch (Exception exception) {
                DiagnosticLog.error("检查本机登录状态失败", exception);
                String detail = exception instanceof SmartLogisticsClient.ClientException
                        ? exception.getMessage() : null;
                String feedback = detail == null || detail.trim().isEmpty()
                        ? "暂时无法验证 Cookie，请检查网络后重试" : detail;
                postUi(() -> showLoginScreen(feedback));
            }
        });
    }

    private void showMainScreen() {
        sessionClearRequested = false;
        authBusy = false;
        queryBusy = false;
        lastQueryFinishedAt = 0L;
        setContentView(buildMainScreen());
        main.postDelayed(this::query, 180);
    }

    private void showLoginScreen(String feedback) {
        authBusy = false;
        queryBusy = false;
        clearQueryCache();
        setContentView(buildLoginScreen());
        setSessionStatus("待登录", ELECTRIC, Color.rgb(252, 247, 238));
        setAuthBusy(false, feedback);
    }

    private View buildMainScreen() {
        pageScroll = new ScrollView(this);
        pageScroll.setFillViewport(true);
        pageScroll.setBackgroundColor(Color.WHITE);
        pageScroll.setClipToPadding(false);

        LinearLayout page = vertical();
        page.setBackgroundColor(Color.WHITE);
        pageScroll.addView(page, matchWrap());

        page.addView(brandHero("", true), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(182)));

        LinearLayout content = vertical();
        content.setPadding(dp(18), dp(14), dp(18), dp(28));
        page.addView(content, matchWrap());

        LinearLayout targetCard = vertical();
        LinearLayout targetTitle = horizontal();
        targetTitle.setGravity(Gravity.CENTER_VERTICAL);
        targetTitle.addView(accentedTitle("当前宿舍"), weighted());
        String savedBuilding = normalizedBuilding(
                preferences.getString("building", "J1"));
        String savedRoom = preferences.getString("room", "1305");
        targetSummary = text(DEFAULT_CAMPUS + " · " + savedBuilding + " · " + savedRoom,
                12, MUTED, false);
        targetTitle.addView(targetSummary);
        targetCard.addView(targetTitle);
        zoneSpinner = spinner();
        floorSpinner = spinner();
        roomInput = roomKeywordInput();
        roomInput.setText(savedRoom);
        LinearLayout targetFields = horizontal();
        targetFields.addView(targetTile("宿舍区号", zoneSpinner), weightedRight(5));
        targetFields.addView(targetTile("楼号", floorSpinner), weightedSides(3, 3));
        targetFields.addView(targetTile("宿舍号", roomInput), weightedLeft(5));
        targetCard.addView(targetFields, verticalGap(8, 14));
        configureDormSpinners(savedBuilding);
        queryButton = button("⌕  立即查询", true);
        queryButton.setTextSize(15);
        queryButton.setMinHeight(dp(46));
        queryButton.setBackground(new RippleDrawable(
                ColorStateList.valueOf(Color.argb(48, 255, 255, 255)),
                gradient(Color.rgb(34, 178, 246), Color.rgb(5, 92, 232), 12), null));
        targetCard.addView(queryButton, matchWrap());
        LinearLayout statusRow = horizontal();
        statusRow.setGravity(Gravity.CENTER_VERTICAL);
        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleSmall);
        progress.setVisibility(View.GONE);
        statusRow.addView(progress, new LinearLayout.LayoutParams(dp(24), dp(24)));
        statusText = text("准备查询", 13, MUTED, false);
        statusText.setPadding(dp(8), 0, 0, 0);
        statusRow.addView(statusText, weighted());
        targetCard.addView(statusRow, verticalGap(9, 10));
        targetCard.addView(divider(), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));
        content.addView(targetCard, matchWrap());

        LinearLayout overview = vertical();
        overview.setPadding(0, dp(14), 0, dp(12));
        LinearLayout overviewTitle = horizontal();
        overviewTitle.setGravity(Gravity.CENTER_VERTICAL);
        overviewTitle.addView(sectionBadge("▥", "消费概览"), weighted());
        periodText = text("统计周期：--", 11, MUTED, false);
        overviewTitle.addView(periodText);
        overview.addView(overviewTitle);

        LinearLayout balances = horizontal();
        TextView[] remainingElectricViews = overviewMetric(
                balances, "⚡", "剩余电量", "度", ELECTRIC);
        remainingElectric = remainingElectricViews[0];
        remainingElectricCost = remainingElectricViews[1];
        balances.addView(verticalDivider(78));
        TextView[] remainingWaterViews = overviewMetric(
                balances, "●", "剩余水量", "吨", Color.rgb(10, 90, 175));
        remainingWater = remainingWaterViews[0];
        remainingWaterCost = remainingWaterViews[1];
        overview.addView(balances, verticalGap(8, 6));
        overview.addView(divider(), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));

        LinearLayout totals = horizontal();
        TextView[] electricTotal = usageTotal(totals, "30天用电 / 费用", "度", ELECTRIC);
        usedElectric = electricTotal[0];
        electricCost = electricTotal[1];
        totals.addView(verticalDivider(78));
        TextView[] waterTotal = usageTotal(totals, "30天用水 / 费用", "吨", Color.rgb(10, 90, 175));
        usedWater = waterTotal[0];
        waterCost = waterTotal[1];
        overview.addView(totals, verticalGap(10, 0));

        overview.addView(divider(), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));
        LinearLayout meterTotals = horizontal();
        TextView[] meterOne = meterUsageTotal(meterTotals,
                "电表一（阳台、卫生间、插座）", ELECTRIC);
        meterOneUsage = meterOne[0];
        meterOneCost = meterOne[1];
        meterTotals.addView(verticalDivider(78));
        TextView[] meterTwo = meterUsageTotal(meterTotals,
                "电表二（房间照明和空调）", BLUE);
        meterTwoUsage = meterTwo[0];
        meterTwoCost = meterTwo[1];
        overview.addView(meterTotals, verticalGap(9, 0));
        content.addView(overview, matchWrap());
        content.addView(divider(), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));

        LinearLayout dailyCard = vertical();
        dailyCard.setPadding(0, dp(18), 0, dp(18));
        LinearLayout dailyHeader = horizontal();
        dailyHeader.setGravity(Gravity.CENTER_VERTICAL);
        dailyHeader.addView(sectionBadge("▥", "最近30天每日用量"));
        dailyCard.addView(dailyHeader);
        dailyCard.addView(tableRow("日期", "用电（度/元）", "用水（吨/元）", true),
                verticalGap(8, 0));
        dailyRows = vertical();
        dailyRows.addView(text("查询后显示每日记录", 13, MUTED, false));
        dailyCard.addView(dailyRows);
        content.addView(dailyCard, matchWrap());
        content.addView(divider(), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));

        LinearLayout pricingCard = vertical();
        pricingCard.setPadding(0, dp(18), 0, dp(8));
        pricingCard.addView(sectionBadge("¥", "计价标准"));
        LinearLayout priceRow = horizontal();
        TextView electricPrice = priceTile("电价", "0.5333 元/度", ELECTRIC,
                Color.rgb(255, 248, 238));
        TextView waterPrice = priceTile("水价", "2.6 元/吨", WATER,
                Color.rgb(239, 248, 255));
        priceRow.addView(electricPrice, weightedRight(5));
        priceRow.addView(waterPrice, weightedLeft(5));
        pricingCard.addView(priceRow, verticalGap(10, 0));
        content.addView(pricingCard, matchWrap());

        TextView footer = text(appVersion() + "  ·  数据仅供宿舍日常查询", 11,
                Color.rgb(135, 148, 162), false);
        footer.setGravity(Gravity.CENTER);
        content.addView(footer, verticalGap(8, 0));

        queryButton.setOnClickListener(view -> query());
        return pageScroll;
    }

    private void sendSms() {
        if (destroyed || authBusy) return;
        String phone = value(phoneInput);
        if (!phone.matches("1[3-9]\\d{9}")) {
            authMessage("请输入正确的 11 位手机号");
            return;
        }
        codeInput.setText("");
        authClient = null;
        smsPhone = "";
        DiagnosticLog.info("开始建立验证码会话");
        setAuthBusy(true, "正在建立会话并发送验证码…");
        executor.execute(() -> {
            try {
                SmartLogisticsClient client = new SmartLogisticsClient(this);
                client.initializeSession(true);
                client.sendSms(phone);
                DiagnosticLog.success("验证码已发送");
                postUi(() -> {
                    authClient = client;
                    smsPhone = phone;
                    setSessionStatus("待验证", ELECTRIC, Color.rgb(252, 247, 238));
                    setAuthBusy(false, "验证码已发送，请查看短信并输入验证码");
                });
            } catch (Exception exception) {
                failAuth("验证码发送失败", exception);
            }
        });
    }

    private void login() {
        if (destroyed || authBusy) return;
        String phone = value(phoneInput);
        String student = value(studentInput);
        String code = value(codeInput);
        if (authClient == null) {
            authMessage("请先发送验证码");
            return;
        }
        if (!phone.equals(smsPhone)) {
            authMessage("手机号已改变，请重新发送验证码");
            return;
        }
        if (!student.matches("\\d{6,20}")) {
            authMessage("请输入至少 6 位的数字学号");
            return;
        }
        if (!code.matches("\\d{4,8}")) {
            authMessage("验证码应为 4 到 8 位数字");
            return;
        }
        String password = student.substring(student.length() - 6);
        SmartLogisticsClient client = authClient;
        DiagnosticLog.info("开始登录并验证会话");
        setAuthBusy(true, "正在登录并验证 Cookie…");
        executor.execute(() -> {
            try {
                client.bind(phone, code, student, password);
                client.ensureAuthenticated();
                DiagnosticLog.success("登录及会话验证成功");
                preferences.edit().putString("phone", phone)
                        .putString("student_id", student).apply();
                postUi(() -> {
                    authClient = null;
                    smsPhone = "";
                    codeInput.setText("");
                    setAuthBusy(false, "登录成功，Cookie 已安全保存");
                    message("登录成功，正在进入主界面");
                    showMainScreen();
                });
            } catch (Exception exception) {
                failAuth("登录失败", exception);
            }
        });
    }

    private void confirmClearLogin() {
        if (destroyed || authBusy) return;
        new AlertDialog.Builder(this)
                .setTitle("清除登录状态")
                .setMessage("将清空手机号、学号以及本机保存的 Cookie，需要重新获取验证码后才能登录。")
                .setNegativeButton("取消", null)
                .setPositiveButton("确认清除", (dialog, which) -> clearLoginState())
                .show();
    }

    private void clearLoginState() {
        DiagnosticLog.info("开始清除本机登录状态");
        clearQueryCache();
        sessionClearRequested = true;
        authClient = null;
        smsPhone = "";
        showLoginScreen("正在清除本机登录状态…");
        setAuthBusy(true, "正在清除本机登录状态…");
        executor.execute(() -> {
            try {
                new SmartLogisticsClient(this).clearLoginState();
                if (!preferences.edit().remove("phone").remove("student_id").commit()) {
                    throw new IllegalStateException("无法清除账户信息");
                }
                DiagnosticLog.success("本机登录状态已清除");
                postUi(() -> {
                    phoneInput.setText("");
                    studentInput.setText("");
                    codeInput.setText("");
                    setAuthBusy(false, "登录状态已清除");
                    message("手机号、学号和登录 Cookie 已清除");
                });
            } catch (Exception exception) {
                failAuth("清除失败", exception);
            }
        });
    }

    private void clearQueryCache() {
        lastSuccessfulSummary = null;
        lastSuccessfulTargetKey = "";
        lastSuccessfulQueryAt = 0L;
    }

    private void query() {
        if (destroyed) return;
        if (queryBusy) {
            DiagnosticLog.warning("忽略重复查询 · 前一次查询尚未完成");
            return;
        }
        if (authBusy) {
            message("登录操作进行中，请稍候");
            return;
        }
        long sinceLastQuery = SystemClock.elapsedRealtime() - lastQueryFinishedAt;
        if (lastQueryFinishedAt > 0L && sinceLastQuery < QUERY_COOLDOWN_MS) {
            DiagnosticLog.warning("忽略重复查询 · 查询间隔过短");
            message("查询刚刚完成，请稍候再试");
            return;
        }
        if (dormZones.isEmpty()) {
            setSessionStatus("配置错误", Color.rgb(179, 38, 30),
                    Color.rgb(252, 232, 230));
            statusText.setText("宿舍楼数据加载失败，请重新安装应用");
            message("宿舍楼数据加载失败，请重新安装应用");
            return;
        }
        String campus = DEFAULT_CAMPUS;
        String zone = selected(zoneSpinner);
        String floor = selected(floorSpinner);
        String building = zone + floor;
        String room = value(roomInput);
        if (zone.isEmpty() || floor.isEmpty() || room.isEmpty()) {
            message("请选择宿舍区号、楼号并填写宿舍号");
            return;
        }
        preferences.edit().putString("campus", campus)
                .putString("building", building)
                .putString("room", room).apply();
        targetSummary.setText(campus + " · " + building + " · " + room);
        String targetKey = campus + "\n" + building + "\n" + room;
        long cacheAge = SystemClock.elapsedRealtime() - lastSuccessfulQueryAt;
        if (lastSuccessfulSummary != null && targetKey.equals(lastSuccessfulTargetKey)
                && cacheAge >= 0L && cacheAge < RECENT_RESULT_CACHE_MS) {
            DiagnosticLog.info("使用最近一次成功查询结果 · ageMs=" + cacheAge);
            showResults(lastSuccessfulSummary);
            setBusy(false, "已显示刚刚查询的最新数据");
            return;
        }
        DiagnosticLog.info("开始查询水电费数据");
        clearResults();
        setSessionStatus("查询中", BLUE, Color.rgb(226, 238, 248));
        setBusy(true, "正在查询水电费数据");
        executor.execute(() -> {
            try {
                SmartLogisticsClient client = new SmartLogisticsClient(this);
                client.ensureAuthenticated();
                UsageSummary summary = client.queryRoom(campus, building, room);
                DiagnosticLog.success("水电费查询完成 · 每日记录 "
                        + summary.dailyRows.size() + " 条");
                postUi(() -> {
                    if (!sessionClearRequested) {
                        lastSuccessfulSummary = summary;
                        lastSuccessfulTargetKey = targetKey;
                        lastSuccessfulQueryAt = SystemClock.elapsedRealtime();
                        showResults(summary);
                    }
                });
            } catch (Exception exception) {
                fail("查询失败", exception);
            }
        });
    }

    private void showResults(UsageSummary summary) {
        lastQueryFinishedAt = SystemClock.elapsedRealtime();
        setSessionStatus("已连接", SUCCESS, Color.rgb(232, 245, 233));
        remainingElectric.setText(UsageSummary.amount(summary.remainingElectric));
        remainingWater.setText(UsageSummary.amount(summary.remainingWater));
        remainingElectricCost.setText("约 "
                + UsageSummary.amount(summary.remainingElectricCost()) + " 元");
        remainingWaterCost.setText("约 "
                + UsageSummary.amount(summary.remainingWaterCost()) + " 元");
        usedElectric.setText(UsageSummary.amount(summary.usedElectric));
        usedWater.setText(UsageSummary.amount(summary.usedWater));
        electricCost.setText("约 " + UsageSummary.amount(summary.electricCost()) + " 元");
        waterCost.setText("约 " + UsageSummary.amount(summary.waterCost()) + " 元");
        setMeterUsage(summary, "表1", meterOneUsage, meterOneCost);
        setMeterUsage(summary, "表2", meterTwoUsage, meterTwoCost);
        periodText.setText(summary.dateFrom == null ? "统计区间：暂无数据"
                : "统计区间：" + summary.dateFrom + " 至 " + summary.dateTo);
        renderDailyRows(summary);
        setBusy(false, "查询完成，共 " + summary.dailyRows.size() + " 天记录");
    }

    private void fail(String title, Exception exception) {
        DiagnosticLog.error(title, exception);
        String message = exception.getMessage();
        if (message == null || message.trim().isEmpty()) {
            message = exception.getClass().getSimpleName();
        }
        String finalMessage = message;
        postUi(() -> {
            if (sessionClearRequested) return;
            lastQueryFinishedAt = SystemClock.elapsedRealtime();
            setBusy(false, title);
            if (finalMessage.contains("尚未绑定") || finalMessage.contains("会话失效")
                    || finalMessage.contains("Cookie")) {
                showLoginScreen("登录已失效，请重新获取验证码");
            } else {
                setSessionStatus("连接异常", Color.rgb(179, 38, 30),
                        Color.rgb(252, 232, 230));
                new AlertDialog.Builder(this).setTitle(title).setMessage(finalMessage)
                        .setPositiveButton("确定", null).show();
            }
        });
    }

    private void failAuth(String title, Exception exception) {
        DiagnosticLog.error(title, exception);
        String message = exception.getMessage();
        if (message == null || message.trim().isEmpty()) {
            message = exception.getClass().getSimpleName();
        }
        String finalMessage = message;
        postUi(() -> {
            setAuthBusy(false, title + "：" + finalMessage);
            setSessionStatus("待登录", ELECTRIC, Color.rgb(252, 247, 238));
            new AlertDialog.Builder(this).setTitle(title).setMessage(finalMessage)
                    .setPositiveButton("确定", null).show();
        });
    }

    private void setBusy(boolean value, String status) {
        queryBusy = value;
        progress.setVisibility(value ? View.VISIBLE : View.GONE);
        statusText.setText(status);
        refreshControls();
    }

    private void setAuthBusy(boolean value, String status) {
        authBusy = value;
        authProgress.setVisibility(value ? View.VISIBLE : View.GONE);
        authFeedback.setText(status);
        refreshControls();
    }

    private void refreshControls() {
        if (sendCodeButton != null) sendCodeButton.setEnabled(!authBusy);
        if (loginButton != null) loginButton.setEnabled(!authBusy);
        if (clearLoginButton != null) clearLoginButton.setEnabled(!authBusy);
        if (phoneInput != null) phoneInput.setEnabled(!authBusy);
        if (studentInput != null) studentInput.setEnabled(!authBusy);
        if (codeInput != null) codeInput.setEnabled(!authBusy);
        if (queryButton != null) queryButton.setEnabled(!queryBusy && !authBusy);
        if (zoneSpinner != null) zoneSpinner.setEnabled(!queryBusy && !authBusy);
        if (floorSpinner != null) floorSpinner.setEnabled(!queryBusy && !authBusy);
        if (roomInput != null) roomInput.setEnabled(!queryBusy && !authBusy);
    }

    private void setSessionStatus(String value, int textColor, int backgroundColor) {
        sessionStatus.setText("●  " + value);
        sessionStatus.setTextColor(textColor);
        sessionStatus.setBackground(rounded(backgroundColor, 99));
    }

    private void clearResults() {
        for (TextView view : new TextView[]{remainingElectric, remainingWater, usedElectric,
                usedWater, electricCost, waterCost}) {
            view.setText("--");
        }
        meterOneUsage.setText("--");
        meterTwoUsage.setText("--");
        remainingElectricCost.setText("约 -- 元");
        remainingWaterCost.setText("约 -- 元");
        meterOneCost.setText("约 -- 元");
        meterTwoCost.setText("约 -- 元");
        periodText.setText("统计区间：查询中");
        dailyRows.removeAllViews();
        dailyRows.addView(text("正在读取…", 13, MUTED, false));
    }

    private void loadDormZones() {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                getAssets().open("room_list.json"), StandardCharsets.UTF_8))) {
            StringBuilder json = new StringBuilder();
            char[] buffer = new char[8192];
            int count;
            while ((count = reader.read(buffer)) >= 0) json.append(buffer, 0, count);
            JSONArray campuses = new JSONArray(json.toString());
            for (int i = 0; i < campuses.length(); i++) {
                JSONObject campus = campuses.getJSONObject(i);
                if (!DEFAULT_CAMPUS.equals(campus.optString("name"))) continue;
                JSONArray items = campus.optJSONArray("subMenus");
                if (items != null) {
                    for (int j = 0; j < items.length(); j++) {
                        JSONObject buildingItem = items.getJSONObject(j);
                        String building = normalizedBuilding(buildingItem.optString("name"));
                        if (!building.matches("[A-Z]\\d+")) continue;
                        String zone = building.substring(0, 1);
                        String floor = building.substring(1);
                        List<String> floors = dormZones.get(zone);
                        if (floors == null) {
                            floors = new ArrayList<>();
                            dormZones.put(zone, floors);
                        }
                        if (!floors.contains(floor)) floors.add(floor);
                        List<String> suggestions = new ArrayList<>();
                        JSONArray rooms = buildingItem.optJSONArray("subMenus");
                        if (rooms != null) {
                            for (int roomIndex = 0; roomIndex < rooms.length(); roomIndex++) {
                                JSONObject room = rooms.optJSONObject(roomIndex);
                                if (room == null) continue;
                                String keyword = roomKeyword(building, room.optString("name"));
                                if (!keyword.isEmpty() && !suggestions.contains(keyword)) {
                                    suggestions.add(keyword);
                                }
                            }
                        }
            Collections.sort(suggestions, MainActivity::compareRoomKeywords);
            roomSuggestions.put(building, suggestions);
                    }
                }
            }
        } catch (Exception exception) {
            dormZones.clear();
        }
    }

    private void configureDormSpinners(String savedBuilding) {
        String preferredZone = savedBuilding.substring(0, 1);
        String preferredFloor = savedBuilding.substring(1);
        List<String> zones = new ArrayList<>(dormZones.keySet());
        zoneSpinner.setAdapter(adapter(zones));
        select(zoneSpinner, preferredZone);
        zoneSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                String zone = zones.get(position);
                List<String> floors = dormZones.get(zone);
                floorSpinner.setAdapter(adapter(floors == null ? new ArrayList<>() : floors));
                if (zone.equals(preferredZone)) select(floorSpinner, preferredFloor);
                Object initialFloor = floorSpinner.getSelectedItem();
                if (initialFloor != null) {
                    updateRoomSuggestions(zone + initialFloor);
                }
                floorSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                    @Override public void onItemSelected(AdapterView<?> parent, View view,
                                                         int floorPosition, long floorId) {
                        Object selectedFloor = floorSpinner.getSelectedItem();
                        if (selectedFloor != null) {
                            updateRoomSuggestions(zone + selectedFloor);
                        }
                    }

                    @Override public void onNothingSelected(AdapterView<?> parent) { }
                });
            }
            @Override public void onNothingSelected(AdapterView<?> parent) { }
        });
    }

    private static String normalizedBuilding(String value) {
        if ("雅园1".equals(value)) return "J1";
        if ("雅园2".equals(value)) return "J2";
        if ("研究生".equals(value)) return "J3";
        if (value == null || !value.matches("[A-Z]\\d+")) return "J1";
        return value;
    }

    private static String roomKeyword(String building, String rawName) {
        if (rawName == null || rawName.trim().isEmpty()) return "";
        String name = rawName.trim();
        if ("J3".equals(building) && name.startsWith("研-")) {
            String keyword = name.substring(2);
            if (keyword.endsWith("水表")) {
                keyword = keyword.substring(0, keyword.length() - 2);
            }
            return keyword;
        }
        int separator = name.indexOf('-');
        return separator > 0 ? name.substring(0, separator) : name;
    }

    private static int compareRoomKeywords(String left, String right) {
        if (left.matches("\\d+") && right.matches("\\d+")) {
            try {
                return Integer.compare(Integer.parseInt(left), Integer.parseInt(right));
            } catch (NumberFormatException ignored) {
                // Extremely long numeric labels fall back to deterministic text ordering.
            }
        }
        return left.compareToIgnoreCase(right);
    }

    private void updateRoomSuggestions(String building) {
        List<String> rooms = roomSuggestions.get(building);
        roomInput.setAdapter(roomAdapter(rooms == null ? new ArrayList<>() : rooms));
    }

    private ArrayAdapter<String> adapter(List<String> values) {
        ArrayAdapter<String> adapter = new ArrayAdapter<String>(this,
                android.R.layout.simple_spinner_item, values) {
            @Override public View getView(int position, View convertView, ViewGroup parent) {
                TextView view = (TextView) super.getView(position, convertView, parent);
                view.setTextColor(TEXT);
                view.setTextSize(16);
                view.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
                view.setSingleLine(true);
                view.setGravity(Gravity.CENTER_VERTICAL);
                return view;
            }

            @Override public View getDropDownView(int position, View convertView, ViewGroup parent) {
                TextView view = (TextView) super.getDropDownView(position, convertView, parent);
                view.setTextColor(TEXT);
                view.setTextSize(15);
                view.setGravity(Gravity.CENTER_VERTICAL);
                view.setPadding(dp(18), dp(12), dp(18), dp(12));
                view.setMinHeight(dp(48));
                view.setBackgroundColor(Color.WHITE);
                return view;
            }
        };
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        return adapter;
    }

    private ArrayAdapter<String> roomAdapter(List<String> values) {
        return new ArrayAdapter<String>(this, android.R.layout.simple_dropdown_item_1line, values) {
            @Override public View getView(int position, View convertView, ViewGroup parent) {
                TextView view = (TextView) super.getView(position, convertView, parent);
                view.setTextColor(TEXT);
                view.setTextSize(15);
                view.setGravity(Gravity.CENTER_VERTICAL);
                view.setPadding(dp(16), dp(11), dp(16), dp(11));
                view.setMinHeight(dp(48));
                view.setBackgroundColor(Color.WHITE);
                return view;
            }
        };
    }

    private static void select(Spinner spinner, String value) {
        if (spinner.getAdapter() == null) return;
        for (int i = 0; i < spinner.getAdapter().getCount(); i++) {
            if (value.equals(String.valueOf(spinner.getAdapter().getItem(i)))) {
                spinner.setSelection(i);
                return;
            }
        }
    }

    private TextView metric(LinearLayout parent, String label, String unit, int color,
                            int valueSize, boolean first) {
        LinearLayout block = vertical();
        TextView labelView = text(label, 12, MUTED, false);
        LinearLayout valueLine = horizontal();
        valueLine.setGravity(Gravity.BOTTOM);
        TextView value = text("--", valueSize, color, true);
        value.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        TextView unitView = text(unit, 11, MUTED, false);
        unitView.setPadding(dp(4), 0, 0, dp(4));
        valueLine.addView(value);
        valueLine.addView(unitView);
        block.addView(labelView);
        block.addView(valueLine);
        LinearLayout.LayoutParams params = weighted();
        params.setMargins(first ? 0 : dp(10), 0, first ? dp(10) : 0, 0);
        parent.addView(block, params);
        return value;
    }

    private TextView summaryTile(LinearLayout parent, String symbol, String label, String unit,
                                 int color, int background, boolean first) {
        LinearLayout tile = vertical();
        tile.setPadding(dp(14), dp(13), dp(14), dp(13));
        tile.setBackground(rounded(background, 14));
        LinearLayout labelLine = horizontal();
        labelLine.setGravity(Gravity.CENTER_VERTICAL);
        TextView icon = text(symbol, 15, color, true);
        icon.setGravity(Gravity.CENTER);
        icon.setBackground(rounded(Color.argb(28, Color.red(color), Color.green(color),
                Color.blue(color)), 99));
        icon.setPadding(dp(7), dp(4), dp(7), dp(4));
        labelLine.addView(icon);
        TextView labelView = text(label, 12, MUTED, false);
        labelView.setPadding(dp(8), 0, 0, 0);
        labelLine.addView(labelView);
        tile.addView(labelLine);
        LinearLayout valueLine = horizontal();
        valueLine.setGravity(Gravity.BOTTOM);
        valueLine.setPadding(0, dp(8), 0, 0);
        TextView value = text("--", 28, color, true);
        valueLine.addView(value);
        TextView unitView = text(unit, 11, MUTED, false);
        unitView.setPadding(dp(5), 0, 0, dp(4));
        valueLine.addView(unitView);
        tile.addView(valueLine);
        LinearLayout.LayoutParams params = weighted();
        params.setMargins(first ? 0 : dp(5), 0, first ? dp(5) : 0, 0);
        parent.addView(tile, params);
        return value;
    }

    private LinearLayout tableRow(String date, String electric, String water, boolean header) {
        LinearLayout row = horizontal();
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(2), dp(header ? 8 : 10), dp(2), dp(header ? 8 : 10));
        int color = header ? MUTED : TEXT;
        int size = header ? 10 : 11;
        TextView dateView = text(date, size, color, header);
        TextView electricView = text(electric, size, color, header);
        electricView.setGravity(Gravity.END);
        TextView waterView = text(water, size, color, header);
        waterView.setGravity(Gravity.END);
        row.addView(dateView, weighted(0.82f));
        row.addView(electricView, weighted(1.25f));
        row.addView(waterView, weighted(1.25f));
        return row;
    }

    private void renderDailyRows(UsageSummary summary) {
        dailyRows.removeAllViews();
        if (summary.dailyRows.isEmpty()) {
            dailyRows.addView(text("暂无每日用量数据", 13, MUTED, false));
            return;
        }
        for (int i = 0; i < summary.dailyRows.size(); i++) {
            UsageSummary.DailyRow row = summary.dailyRows.get(i);
            dailyRows.addView(divider(), new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));
            String electric = UsageSummary.amount(row.electric) + " 度 / "
                    + UsageSummary.amount(row.electricCost()) + " 元";
            String water = UsageSummary.amount(row.water) + " 吨 / "
                    + UsageSummary.amount(row.waterCost()) + " 元";
            dailyRows.addView(tableRow(row.date, electric, water, false));
        }
    }

    private LinearLayout targetTile(String label, View field) {
        LinearLayout tile = vertical();
        tile.setPadding(dp(6), dp(5), dp(6), 0);
        TextView labelView = text(label, 12, MUTED, false);
        labelView.setPadding(dp(2), 0, 0, dp(3));
        tile.addView(labelView);
        field.setBackgroundColor(Color.TRANSPARENT);
        if (field instanceof Spinner) {
            Spinner selector = (Spinner) field;
            selector.setPadding(0, 0, dp(24), 0);
            FrameLayout frame = new FrameLayout(this);
            frame.addView(selector, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(46)));
            TextView arrow = text("⌄", 16, BLUE, true);
            arrow.setGravity(Gravity.CENTER);
            arrow.setClickable(false);
            FrameLayout.LayoutParams arrowParams = new FrameLayout.LayoutParams(
                    dp(24), dp(46), Gravity.END | Gravity.CENTER_VERTICAL);
            frame.addView(arrow, arrowParams);
            tile.addView(frame, matchWrap());
        } else if (field instanceof AutoCompleteTextView) {
            AutoCompleteTextView selector = (AutoCompleteTextView) field;
            selector.setPadding(0, 0, dp(24), 0);
            FrameLayout frame = new FrameLayout(this);
            frame.addView(selector, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(46)));
            TextView arrow = text("⌄", 16, BLUE, true);
            arrow.setGravity(Gravity.CENTER);
            arrow.setOnClickListener(view -> selector.showDropDown());
            FrameLayout.LayoutParams arrowParams = new FrameLayout.LayoutParams(
                    dp(24), dp(46), Gravity.END | Gravity.CENTER_VERTICAL);
            frame.addView(arrow, arrowParams);
            tile.addView(frame, matchWrap());
        } else {
            field.setPadding(0, 0, 0, 0);
            tile.addView(field, matchWrap());
        }
        View underline = divider();
        underline.setBackgroundColor(Color.rgb(205, 218, 235));
        tile.addView(underline, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));
        return tile;
    }

    private View brandHero(String subtitleValue, boolean allowLogout) {
        FrameLayout hero = new FrameLayout(this);
        ImageView image = new ImageView(this);
        image.setImageResource(R.drawable.campus_hero_v2);
        image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        hero.addView(image, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        View shade = new View(this);
        shade.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{Color.argb(128, 0, 66, 182), Color.argb(16, 0, 91, 220)}));
        hero.addView(shade, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout overlay = vertical();
        overlay.setPadding(dp(20), dp(22), dp(16), dp(14));
        LinearLayout top = horizontal();
        top.setGravity(Gravity.TOP);
        LinearLayout brand = vertical();
        TextView title = text("智慧后勤", 28, Color.WHITE, true);
        title.setShadowLayer(2f, 0, dp(1), Color.argb(70, 0, 32, 92));
        title.setContentDescription("智慧后勤");
        title.setOnClickListener(view -> handleDiagnosticTitleTap());
        brand.addView(title);
        if (!subtitleValue.isEmpty()) {
            TextView subtitle = text(subtitleValue, 12, Color.rgb(237, 248, 255), false);
            subtitle.setPadding(0, dp(7), 0, 0);
            brand.addView(subtitle);
        }
        top.addView(brand, weighted());
        TextView noticeButton = pill("📢  注意事项",
                Color.argb(226, 240, 249, 255), BLUE);
        noticeButton.setTextSize(9);
        noticeButton.setPadding(dp(8), dp(4), dp(8), dp(4));
        noticeButton.setContentDescription("查看J区宿舍水电计费通知");
        noticeButton.setOnClickListener(view -> showBillingNotice());
        LinearLayout.LayoutParams noticeParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        noticeParams.setMargins(dp(5), 0, dp(5), 0);
        top.addView(noticeButton, noticeParams);
        sessionStatus = pill("●  检查中", Color.argb(238, 248, 255, 250), SUCCESS);
        sessionStatus.setTextSize(9);
        sessionStatus.setPadding(dp(9), dp(4), dp(9), dp(4));
        if (allowLogout) {
            sessionStatus.setContentDescription("账户与退出登录");
            sessionStatus.setOnClickListener(view -> confirmClearLogin());
        }
        top.addView(sessionStatus);
        overlay.addView(top);
        hero.addView(overlay, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        return hero;
    }

    private void handleDiagnosticTitleTap() {
        long now = SystemClock.elapsedRealtime();
        if (now - lastDiagnosticTapAt > 1_200L) diagnosticTapCount = 0;
        lastDiagnosticTapAt = now;
        diagnosticTapCount++;
        if (diagnosticTapCount < 6) return;
        diagnosticTapCount = 0;
        lastDiagnosticTapAt = 0L;
        DiagnosticLog.info("用户打开诊断日志");
        showDiagnosticLog();
    }

    private void showDiagnosticLog() {
        LinearLayout content = vertical();
        content.setPadding(dp(18), dp(8), dp(18), dp(4));

        String device = Build.MANUFACTURER + " " + Build.MODEL;
        TextView deviceInfo = text("应用版本：" + appVersion()
                + "\nAndroid：" + Build.VERSION.RELEASE + "（API " + Build.VERSION.SDK_INT + "）"
                + "\n设备：" + device
                + "\n\n日志仅保存在本应用私有目录，敏感信息会自动隐藏。",
                12, MUTED, false);
        deviceInfo.setLineSpacing(0, 1.18f);
        content.addView(deviceInfo);

        TextView logView = text(DiagnosticLog.readText(), 11, TEXT, false);
        logView.setTextIsSelectable(true);
        logView.setMovementMethod(new ScrollingMovementMethod());
        logView.setTypeface(Typeface.MONOSPACE);
        logView.setPadding(dp(10), dp(10), dp(10), dp(10));
        logView.setBackground(rounded(Color.rgb(245, 247, 250), 8));
        content.addView(logView, verticalGap(12, 0));

        ScrollView scroll = new ScrollView(this);
        scroll.addView(content, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("诊断日志")
                .setView(scroll)
                .setNeutralButton("清空日志", null)
                .setNegativeButton("导出日志", (ignored, which) -> exportDiagnosticLog())
                .setPositiveButton("关闭", null)
                .create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_NEUTRAL)
                .setOnClickListener(view -> {
                    DiagnosticLog.clear();
                    logView.setText("日志已清空。关闭窗口后重试相关操作即可生成新日志。");
                }));
        dialog.show();
    }

    @SuppressWarnings("deprecation")
    private void exportDiagnosticLog() {
        DiagnosticLog.info("用户选择导出诊断日志");
        String timestamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.CHINA)
                .format(new Date());
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("text/plain");
        intent.putExtra(Intent.EXTRA_TITLE, "智慧后勤-诊断日志-" + timestamp + ".txt");
        try {
            startActivityForResult(intent, REQUEST_EXPORT_DIAGNOSTIC_LOG);
        } catch (Exception exception) {
            DiagnosticLog.error("无法打开系统文件保存界面", exception);
            message("无法打开系统文件保存界面");
        }
    }

    private void writeDiagnosticExport(Uri target) {
        String exportText = buildDiagnosticExportText();
        Context application = getApplicationContext();
        Thread exportThread = new Thread(() -> {
            try (OutputStream output = application.getContentResolver()
                    .openOutputStream(target, "w")) {
                if (output == null) throw new java.io.IOException("Output stream unavailable");
                output.write(exportText.getBytes(StandardCharsets.UTF_8));
                output.flush();
                DiagnosticLog.success("诊断日志导出成功");
                postUi(() -> message("诊断日志已导出"));
            } catch (Exception exception) {
                DiagnosticLog.error("诊断日志导出失败", exception);
                postUi(() -> message("诊断日志导出失败，请重试"));
            }
        }, "diagnostic-log-export");
        exportThread.start();
    }

    private String buildDiagnosticExportText() {
        String exportedAt = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA)
                .format(new Date());
        return "智慧后勤诊断日志\n"
                + "应用版本：" + appVersion() + "\n"
                + "Android：" + Build.VERSION.RELEASE + "（API " + Build.VERSION.SDK_INT + "）\n"
                + "设备：" + Build.MANUFACTURER + " " + Build.MODEL + "\n"
                + "导出时间：" + exportedAt + "\n"
                + "说明：日志已自动隐藏敏感信息。\n\n"
                + DiagnosticLog.readText() + "\n";
    }

    @SuppressWarnings("deprecation")
    private String appVersion() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception ignored) {
            return "未知";
        }
    }

    private TextView[] overviewMetric(LinearLayout parent, String symbol, String label,
                                      String unit, int color) {
        LinearLayout block = vertical();
        block.setPadding(dp(2), 0, dp(8), 0);
        LinearLayout labelLine = horizontal();
        labelLine.setGravity(Gravity.CENTER_VERTICAL);
        TextView icon = text(symbol, 12, color, true);
        icon.setGravity(Gravity.CENTER);
        icon.setBackground(rounded(Color.argb(34, Color.red(color), Color.green(color),
                Color.blue(color)), 99));
        icon.setPadding(dp(6), dp(4), dp(6), dp(4));
        labelLine.addView(icon);
        TextView labelView = text(label, 10, MUTED, false);
        labelView.setPadding(dp(7), 0, 0, 0);
        labelLine.addView(labelView);
        block.addView(labelLine);
        LinearLayout valueLine = horizontal();
        valueLine.setGravity(Gravity.BOTTOM);
        valueLine.setPadding(0, dp(4), 0, 0);
        TextView value = text("--", 23, color, true);
        valueLine.addView(value);
        TextView unitView = text(unit, 10, MUTED, true);
        unitView.setPadding(dp(4), 0, 0, dp(3));
        valueLine.addView(unitView);
        block.addView(valueLine);
        TextView cost = text("约 -- 元", 13, MUTED, false);
        block.addView(cost, verticalGap(2, 0));
        parent.addView(block, weighted());
        return new TextView[]{value, cost};
    }

    private TextView[] usageTotal(LinearLayout parent, String label, String unit, int color) {
        LinearLayout block = vertical();
        block.setPadding(dp(2), 0, dp(8), 0);
        block.addView(text(label, 10, MUTED, false));
        LinearLayout amountLine = horizontal();
        amountLine.setGravity(Gravity.BOTTOM);
        TextView amount = text("--", 23, color, true);
        amountLine.addView(amount);
        TextView unitView = text(" " + unit, 10, MUTED, true);
        unitView.setPadding(dp(3), 0, 0, dp(3));
        amountLine.addView(unitView);
        block.addView(amountLine, verticalGap(4, 0));
        TextView cost = text("约 -- 元", 13, MUTED, false);
        block.addView(cost, verticalGap(2, 0));
        parent.addView(block, weighted());
        return new TextView[]{amount, cost};
    }

    private TextView[] meterUsageTotal(LinearLayout parent, String label, int color) {
        LinearLayout block = vertical();
        block.setPadding(dp(2), 0, dp(8), 0);
        TextView labelView = text(label, 10, MUTED, false);
        labelView.setMaxLines(2);
        block.addView(labelView);
        LinearLayout amountLine = horizontal();
        amountLine.setGravity(Gravity.BOTTOM);
        TextView amount = text("--", 23, color, true);
        amountLine.addView(amount);
        TextView unit = text(" 度", 10, MUTED, true);
        unit.setPadding(dp(3), 0, 0, dp(3));
        amountLine.addView(unit);
        block.addView(amountLine, verticalGap(4, 0));
        TextView cost = text("约 -- 元", 13, MUTED, false);
        block.addView(cost, verticalGap(2, 0));
        parent.addView(block, weighted());
        return new TextView[]{amount, cost};
    }

    private void setMeterUsage(UsageSummary summary, String meterKey,
                               TextView usageView, TextView costView) {
        for (UsageSummary.MeterTotal meter : summary.electricMeters) {
            if (meter.name.contains(meterKey)) {
                usageView.setText(UsageSummary.amount(meter.usage));
                costView.setText("约 " + UsageSummary.amount(meter.cost()) + " 元");
                return;
            }
        }
        usageView.setText("--");
        costView.setText("暂无数据");
    }

    private View verticalDivider(int heightDp) {
        View view = divider();
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(1), dp(heightDp));
        params.setMargins(dp(12), 0, dp(12), 0);
        view.setLayoutParams(params);
        return view;
    }

    private TextView priceTile(String label, String price, int color, int background) {
        TextView view = text(label + "\n" + price, 13, color, true);
        view.setLineSpacing(dp(5), 1f);
        view.setPadding(dp(14), dp(12), dp(14), dp(12));
        view.setBackground(rounded(background, 13));
        return view;
    }

    private LinearLayout sectionBadge(String symbol, String title) {
        LinearLayout row = horizontal();
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView badge = text(symbol, 14, BLUE, true);
        badge.setGravity(Gravity.CENTER);
        badge.setPadding(dp(8), dp(5), dp(8), dp(5));
        badge.setBackground(rounded(Color.rgb(229, 244, 255), 99));
        row.addView(badge);
        TextView titleView = text(title, 16, TEXT, true);
        titleView.setPadding(dp(9), 0, 0, 0);
        row.addView(titleView);
        return row;
    }

    private LinearLayout accentedTitle(String title) {
        LinearLayout row = horizontal();
        row.setGravity(Gravity.CENTER_VERTICAL);
        View bar = new View(this);
        bar.setBackground(rounded(Color.rgb(20, 137, 231), 99));
        row.addView(bar, new LinearLayout.LayoutParams(dp(4), dp(22)));
        TextView view = text(title, 17, TEXT, true);
        view.setPadding(dp(9), 0, 0, 0);
        row.addView(view);
        row.setPadding(0, 0, 0, dp(10));
        return row;
    }

    private void showLoginHelp() {
        new AlertDialog.Builder(this)
                .setTitle("说明")
                .setMessage("先填写学号和手机号(须和绑定的学号对应)，点击发送验证码;收到短信后输入验证码，再点击登录。")
                .setPositiveButton("知道了", null)
                .show();
    }

    private void showBillingNotice() {
        new AlertDialog.Builder(this)
                .setTitle("📢 J区宿舍水电计费通知")
                .setMessage("各位同学：\n"
                        + "J区宿舍现已完成水电表清零，正式开始计量收费！\n"
                        + "每间宿舍安装2块电表、1块水表，独立计费：\n"
                        + "✅ 电表1：阳台、卫生间照明、宿舍普通插座\n"
                        + "✅ 电表2：房间照明、空调\n"
                        + "✅ 水表：用水单独计费\n\n"
                        + "⚠️重要提醒：水表欠费，电表1将会自动断电！\n"
                        + "宿舍一旦没电，请检查电表1、电表2、水表三块表是否欠费。\n\n"
                        + "💰充值方式：学校企业号 → 智慧后勤 → 选择对应房间充值\n"
                        + "请大家及时充值，避免因欠费造成停电，望互相转告！")
                .setPositiveButton("知道了", null)
                .show();
    }

    private TextView pill(String value, int backgroundColor, int textColor) {
        TextView pill = text(value, 11, textColor, true);
        pill.setGravity(Gravity.CENTER);
        pill.setPadding(dp(11), dp(6), dp(11), dp(6));
        pill.setBackground(rounded(backgroundColor, 99));
        return pill;
    }

    private View divider() {
        View view = new View(this);
        view.setBackgroundColor(DIVIDER);
        return view;
    }

    private LinearLayout labeled(String label, View field) {
        LinearLayout wrapper = vertical();
        TextView title = text(label, 12, MUTED, false);
        title.setPadding(dp(2), 0, 0, dp(3));
        wrapper.addView(title);
        wrapper.addView(field, matchWrap());
        return wrapper;
    }

    private EditText input(String hint, int inputType) {
        EditText input = new EditText(this);
        input.setHint(hint);
        input.setTextColor(TEXT);
        input.setHintTextColor(Color.rgb(145, 157, 168));
        input.setTextSize(15);
        input.setSingleLine(true);
        input.setInputType(inputType);
        input.setPadding(dp(12), 0, dp(12), 0);
        input.setBackground(rounded(Color.rgb(244, 247, 249), 10));
        input.setMinHeight(dp(46));
        return input;
    }

    private AutoCompleteTextView roomKeywordInput() {
        AutoCompleteTextView input = new AutoCompleteTextView(this);
        input.setHint("输入或选择");
        input.setTextColor(TEXT);
        input.setHintTextColor(Color.rgb(145, 157, 168));
        input.setTextSize(15);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setThreshold(0);
        input.setDropDownBackgroundDrawable(roundedStroke(Color.WHITE,
                Color.rgb(224, 233, 242), 14, 1));
        input.setDropDownVerticalOffset(dp(6));
        input.setOnClickListener(view -> input.showDropDown());
        input.setOnFocusChangeListener((view, hasFocus) -> {
            if (hasFocus) input.showDropDown();
        });
        input.setMinHeight(dp(46));
        return input;
    }

    private Spinner spinner() {
        Spinner spinner = new Spinner(this);
        spinner.setPadding(0, 0, 0, 0);
        spinner.setBackgroundColor(Color.TRANSPARENT);
        spinner.setPopupBackgroundDrawable(roundedStroke(Color.WHITE,
                Color.rgb(224, 233, 242), 14, 1));
        spinner.setDropDownVerticalOffset(dp(6));
        spinner.setMinimumHeight(dp(46));
        return spinner;
    }

    private Button button(String label, boolean primary) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextSize(14);
        button.setAllCaps(false);
        button.setTextColor(primary ? Color.WHITE : BLUE);
        button.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        int fill = primary ? BLUE : Color.rgb(229, 240, 249);
        button.setBackground(new RippleDrawable(
                ColorStateList.valueOf(primary
                        ? Color.argb(48, 255, 255, 255) : Color.argb(36, 10, 92, 158)),
                rounded(fill, 10), null));
        button.setMinHeight(dp(48));
        return button;
    }

    private LinearLayout card() {
        LinearLayout card = vertical();
        card.setPadding(dp(16), dp(16), dp(16), dp(16));
        card.setBackground(rounded(Color.WHITE, 20));
        card.setElevation(dp(1));
        return card;
    }

    private TextView sectionTitle(String value) {
        TextView view = text(value, 16, TEXT, true);
        view.setPadding(0, 0, 0, dp(10));
        return view;
    }

    private TextView text(String value, int sp, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sp);
        view.setTextColor(color);
        if (bold) view.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return view;
    }

    private GradientDrawable rounded(int color, int radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radiusDp));
        return drawable;
    }

    private GradientDrawable roundedStroke(int color, int strokeColor, int radiusDp,
                                           int strokeWidthDp) {
        GradientDrawable drawable = rounded(color, radiusDp);
        drawable.setStroke(dp(strokeWidthDp), strokeColor);
        return drawable;
    }

    private GradientDrawable gradient(int startColor, int endColor, int radiusDp) {
        GradientDrawable drawable = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR, new int[]{startColor, endColor});
        drawable.setCornerRadius(dp(radiusDp));
        return drawable;
    }

    private GradientDrawable headerGradient() {
        GradientDrawable drawable = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[]{Color.rgb(5, 79, 176), Color.rgb(25, 151, 240)});
        float radius = dp(30);
        drawable.setCornerRadii(new float[]{0, 0, 0, 0, radius, radius, radius, radius});
        return drawable;
    }

    private LinearLayout vertical() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    private LinearLayout horizontal() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.HORIZONTAL);
        return layout;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams weighted() {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
    }

    private LinearLayout.LayoutParams weighted(float weight) {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, weight);
    }

    private LinearLayout.LayoutParams weightedRight(int gap) {
        LinearLayout.LayoutParams params = weighted();
        params.setMargins(0, 0, dp(gap), 0);
        return params;
    }

    private LinearLayout.LayoutParams weightedLeft(int gap) {
        LinearLayout.LayoutParams params = weighted();
        params.setMargins(dp(gap), 0, 0, 0);
        return params;
    }

    private LinearLayout.LayoutParams weightedSides(int left, int right) {
        LinearLayout.LayoutParams params = weighted();
        params.setMargins(dp(left), 0, dp(right), 0);
        return params;
    }

    private LinearLayout.LayoutParams fieldParams() {
        LinearLayout.LayoutParams params = matchWrap();
        params.setMargins(0, 0, 0, dp(9));
        return params;
    }

    private LinearLayout.LayoutParams cardParams() {
        LinearLayout.LayoutParams params = matchWrap();
        params.setMargins(0, 0, 0, dp(12));
        return params;
    }

    private LinearLayout.LayoutParams verticalGap(int top, int bottom) {
        LinearLayout.LayoutParams params = matchWrap();
        params.setMargins(0, dp(top), 0, dp(bottom));
        return params;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static String value(EditText input) {
        return input.getText().toString().trim();
    }

    private static String selected(Spinner spinner) {
        Object selected = spinner.getSelectedItem();
        return selected == null ? "" : selected.toString().trim();
    }

    private void message(String value) {
        Toast.makeText(this, value, Toast.LENGTH_SHORT).show();
    }

    private void authMessage(String value) {
        authFeedback.setText(value);
        message(value);
    }

    private void postUi(Runnable action) {
        main.post(() -> {
            if (!destroyed && !isFinishing() && !isDestroyed()) {
                action.run();
            }
        });
    }
}
