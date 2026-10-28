package com.local.smartlogistics;

import android.content.Context;
import android.os.Build;
import android.text.Html;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.HttpCookie;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.URLEncoder;
import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.net.ssl.HttpsURLConnection;

final class SmartLogisticsClient {
    private static final String BASE_URL = "https://hqwx.fjut.edu.cn/agent/app/index.php";
    private static final String HOST = "hqwx.fjut.edu.cn";
    private static final String CAMPUS_VPN_HOST = "vpnsite.fjut.edu.cn";
    private static final String SITE_ID = "67";
    private static final String PORTAL = "maihu_portal";
    private static final String UTILITY = "maihu_yktservice";
    private static final int TIMEOUT_MS = 30_000;
    private static final int DEFAULT_DATA_ATTEMPTS = 2;
    private static final int RATE_LIMIT_DATA_ATTEMPTS = 3;
    private static final long DEFAULT_RETRY_DELAY_MS = 650L;
    private static final long RATE_LIMIT_RETRY_DELAY_MS = 1_500L;
    private static final Pattern DIRECT_REDIRECT = Pattern.compile(
            "location\\.replace\\(\"([^\"]+)\"\\)");
    private static final Pattern FINGER_REDIRECT = Pattern.compile(
            "mh_Urlto\\s*=\\s*\"([^\"]+)\"\\s*\\+\\s*mh_Finger");
    private static final Pattern ROOM_TREE = Pattern.compile(
            "var\\s+yd_roominfo\\s*=\\s*'((?:\\\\.|[^'])*)';", Pattern.DOTALL);
    private static final Pattern NUMBER = Pattern.compile("-?\\d+(?:\\.\\d+)?");

    private final SecureStore store;
    private final Map<String, String> cookies = new LinkedHashMap<>();
    private String deviceId;
    private JSONArray roomCache;
    private boolean authenticatedChecked;

    SmartLogisticsClient(Context context) {
        DiagnosticLog.initialize(context);
        store = new SecureStore(context.getApplicationContext());
        deviceId = store.get("device_id");
        loadCookies();
    }

    void initializeSession(boolean resetCookies) throws Exception {
        DiagnosticLog.info("初始化服务端会话");
        if (resetCookies) {
            cookies.clear();
            // Persist the reset before network I/O so a failed recovery cannot reload the
            // same stale cookie on the next process start.
            store.clear("cookies");
        }
        roomCache = null;
        authenticatedChecked = false;
        if (deviceId.isEmpty()) {
            deviceId = "finger-" + UUID.randomUUID().toString().replace("-", "")
                    + "-" + System.currentTimeMillis();
        }
        followLoading(get(withBaseParams(Collections.emptyMap()), null));
        saveState();
        DiagnosticLog.success("服务端会话初始化完成");
    }

    void clearLoginState() {
        cookies.clear();
        roomCache = null;
        authenticatedChecked = false;
        deviceId = "";
        store.clearAll();
        DiagnosticLog.success("加密会话数据已清除");
    }

    boolean isAuthenticated() throws Exception {
        String page = profilePage();
        return page.contains("id=\"profile-page\"") || page.contains("id='profile-page'");
    }

    boolean hasValidStoredLogin() throws Exception {
        return !cookies.isEmpty() && isAuthenticated();
    }

    void ensureAuthenticated() throws Exception {
        if (authenticatedChecked) {
            return;
        }
        if (!cookies.isEmpty() && isAuthenticated()) {
            authenticatedChecked = true;
            return;
        }
        initializeSession(true);
        if (!isAuthenticated()) {
            throw new ClientException("当前设备尚未绑定，请先发送验证码并登录");
        }
        authenticatedChecked = true;
    }

    void sendSms(String phone) throws Exception {
        if (!phone.matches("1[3-9]\\d{9}")) {
            throw new ClientException("手机号格式不正确");
        }
        Map<String, String> query = entryParams("sendsms", PORTAL);
        String result = post(query, pairs("telephone", phone), "register").body.trim();
        if (!"success".equals(result)) {
            throw new ClientException("验证码发送失败：" + concise(result));
        }
        DiagnosticLog.success("验证码接口返回成功");
    }

    String bind(String mobile, String code, String studentId, String password) throws Exception {
        Map<String, String> query = entryParams("register", PORTAL);
        query.put("op", "save");
        List<Pair> form = new ArrayList<>();
        form.add(new Pair("mobile", mobile));
        form.add(new Pair("mobile2", ""));
        form.add(new Pair("code", code));
        form.add(new Pair("studentid", studentId));
        form.add(new Pair("password", password));
        form.add(new Pair("realname", ""));
        form.add(new Pair("idcard", ""));
        form.add(new Pair("images", ""));
        form.add(new Pair("type", ""));
        JSONObject result = objectResponse(post(query, form, "register"));
        if (result.optInt("status", 0) != 1) {
            throw new ClientException(result.optString("msg", "账户绑定失败"));
        }
        authenticatedChecked = false;
        DiagnosticLog.success("账户绑定接口返回成功");
        return result.optString("msg", "登录成功");
    }

    UsageSummary queryRoom(String campus, String building, String room) throws Exception {
        List<Target> targets = resolveTargets(campus, building, room);
        DiagnosticLog.info("宿舍表计解析完成 · 共 " + targets.size() + " 个表计");
        UsageSummary summary = new UsageSummary();
        Map<String, UsageSummary.DailyRow> daily = new LinkedHashMap<>();
        for (Target target : targets) {
            JSONObject remaining = roomDataPost("getelec", target);
            boolean success = remaining.optBoolean("success", false)
                    || remaining.optBoolean("sucess", false);
            if (!success) {
                throw new ClientException(target.kind() + "剩余量查询失败");
            }
            BigDecimal left = number(remaining.opt("elec"), target.name + "剩余量");
            if (target.water) {
                summary.remainingWater = summary.remainingWater.add(left);
            } else {
                summary.remainingElectric = summary.remainingElectric.add(left);
            }

            JSONObject usage = roomDataPost("getelecday", target);
            JSONArray list = usage.optJSONArray("list");
            if (usage.optInt("code", -1) != 0 || list == null) {
                int businessCode = usage.optInt("code", -1);
                if (businessCode == 400) {
                    throw new ClientException(target.kind()
                            + "用量查询繁忙，请稍后再试（业务码：400）");
                }
                throw new ClientException(target.kind() + "用量查询失败（业务码："
                        + businessCode + "）");
            }
            BigDecimal meterTotal = BigDecimal.ZERO;
            for (int index = 0; index < list.length(); index++) {
                JSONObject item = list.optJSONObject(index);
                if (item == null) {
                    throw new ClientException(target.name + " 用量记录格式错误");
                }
                String date = item.optString("elecdate", "");
                validateDate(date, target.name);
                BigDecimal value = number(item.opt("useelec"), target.name + "每日用量");
                meterTotal = meterTotal.add(value);
                UsageSummary.DailyRow row = daily.get(date);
                if (row == null) {
                    row = new UsageSummary.DailyRow(date);
                    daily.put(date, row);
                }
                if (target.water) {
                    row.water = row.water == null ? value : row.water.add(value);
                } else {
                    row.electric = row.electric == null ? value : row.electric.add(value);
                }
            }
            if (target.water) {
                summary.usedWater = summary.usedWater.add(meterTotal);
            } else {
                summary.usedElectric = summary.usedElectric.add(meterTotal);
                summary.electricMeters.add(new UsageSummary.MeterTotal(target.name, meterTotal));
            }
        }
        List<String> dates = new ArrayList<>(daily.keySet());
        Collections.sort(dates, Collections.reverseOrder());
        for (String date : dates) {
            summary.dailyRows.add(daily.get(date));
        }
        if (!dates.isEmpty()) {
            summary.dateTo = dates.get(0);
            summary.dateFrom = dates.get(dates.size() - 1);
        }
        return summary;
    }

    private String profilePage() throws Exception {
        Map<String, String> query = entryParams("profile", PORTAL);
        query.put("mh_skipLoading", "1");
        Response response = followLoading(get(url(query), null));
        saveState();
        return response.body;
    }

    private JSONArray roomTree() throws Exception {
        if (roomCache != null) {
            return roomCache;
        }
        ensureAuthenticated();
        Response response = fetchRoomPage();
        Matcher matcher = ROOM_TREE.matcher(response.body);
        if (!matcher.find()) {
            initializeSession(true);
            if (!isAuthenticated()) {
                throw new ClientException("会话失效且设备未绑定，请重新登录");
            }
            response = fetchRoomPage();
            matcher = ROOM_TREE.matcher(response.body);
            if (!matcher.find()) {
                throw new ClientException("页面中没有找到宿舍列表，网站结构可能已变化");
            }
        }
        String encoded = matcher.group(1);
        try {
            roomCache = new JSONArray(decodeJavaScriptString(encoded));
        } catch (JSONException exception) {
            throw new ClientException("宿舍列表解析失败", exception);
        }
        if (roomCache.length() == 0) {
            throw new ClientException("宿舍列表为空");
        }
        saveState();
        return roomCache;
    }

    private Response fetchRoomPage() throws Exception {
        Map<String, String> query = entryParams("rechargeelec", UTILITY);
        query.put("mh_skipLoading", "1");
        return followLoading(get(url(query), null));
    }

    private List<Target> resolveTargets(String campusKey, String buildingKey, String roomKey)
            throws Exception {
        JSONObject campus = findUnique(roomTree(), campusKey, "校区");
        String normalizedBuilding = "J1".equalsIgnoreCase(buildingKey) ? "雅园1"
                : "J2".equalsIgnoreCase(buildingKey) ? "雅园2"
                : "J3".equalsIgnoreCase(buildingKey) ? "研究生" : buildingKey;
        JSONObject building = findUnique(campus.optJSONArray("subMenus"), normalizedBuilding, "楼栋");
        JSONArray rooms = building.optJSONArray("subMenus");
        if (rooms == null) {
            throw new ClientException("楼栋没有宿舍数据");
        }
        List<JSONObject> matches = findAll(rooms, roomKey, true);
        if (matches.isEmpty() && "研究生".equals(normalizedBuilding)) {
            String researchRoom = "研-" + roomKey;
            for (int i = 0; i < rooms.length(); i++) {
                JSONObject item = rooms.optJSONObject(i);
                if (item == null) continue;
                String name = item.optString("name");
                if (researchRoom.equals(name) || (researchRoom + "水表").equals(name)) {
                    matches.add(item);
                }
            }
        }
        if (matches.isEmpty()) {
            String prefix = roomKey + "-";
            for (int i = 0; i < rooms.length(); i++) {
                JSONObject item = rooms.optJSONObject(i);
                if (item != null && item.optString("name").startsWith(prefix)) {
                    matches.add(item);
                }
            }
        }
        if (matches.isEmpty()) {
            throw new ClientException("找不到房间或表计：" + roomKey);
        }
        List<Target> targets = new ArrayList<>();
        for (JSONObject item : matches) {
            List<String> ids = new ArrayList<>();
            ids.add(String.valueOf(campus.opt("id")));
            ids.add(String.valueOf(building.opt("id")));
            ids.add(String.valueOf(item.opt("id")));
            targets.add(new Target(ids, item.optString("name")));
        }
        return targets;
    }

    private JSONObject roomPost(String operation, List<String> ids) throws Exception {
        Map<String, String> query = entryParams("rechargeelec", UTILITY);
        query.put("op", operation);
        List<Pair> form = new ArrayList<>();
        for (String id : ids) {
            form.add(new Pair("room_id[]", id));
        }
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                return objectResponse(post(query, form, "rechargeelec"));
            } catch (SocketTimeoutException timeout) {
                if (attempt == 1) {
                    throw timeout;
                }
            }
        }
        throw new AssertionError("unreachable");
    }

    private JSONObject roomDataPost(String operation, Target target) throws Exception {
        JSONObject response = null;
        for (int attempt = 1; attempt <= RATE_LIMIT_DATA_ATTEMPTS; attempt++) {
            response = roomPost(operation, target.ids);
            if (isUsableRoomResponse(operation, response)) {
                if (attempt > 1) {
                    DiagnosticLog.success("接口重试成功 · " + target.kind()
                            + " · " + operation + " · 第 " + attempt + " 次");
                }
                return response;
            }
            String result = "getelec".equals(operation)
                    ? "success=" + (response.optBoolean("success", false)
                    || response.optBoolean("sucess", false))
                    : "business=" + response.optInt("code", -1)
                    + ", list=" + (response.optJSONArray("list") != null);
            DiagnosticLog.warning("接口返回暂不可用 · " + target.kind()
                    + " · " + operation + " · 第 " + attempt + " 次 · " + result);
            boolean rateLimited = "getelecday".equals(operation)
                    && response.optInt("code", -1) == 400;
            int maxAttempts = rateLimited
                    ? RATE_LIMIT_DATA_ATTEMPTS : DEFAULT_DATA_ATTEMPTS;
            if (attempt >= maxAttempts) break;
            long delay = rateLimited ? RATE_LIMIT_RETRY_DELAY_MS : DEFAULT_RETRY_DELAY_MS;
            Thread.sleep(delay * attempt);
        }
        if (!isAuthenticated()) {
            authenticatedChecked = false;
            throw new ClientException("会话失效，请重新登录");
        }
        return response;
    }

    private static boolean isUsableRoomResponse(String operation, JSONObject response) {
        if ("getelec".equals(operation)) {
            return response.optBoolean("success", false)
                    || response.optBoolean("sucess", false);
        }
        return response.optInt("code", -1) == 0 && response.optJSONArray("list") != null;
    }

    private Response followLoading(Response response) throws Exception {
        for (int count = 0; count < 6; count++) {
            Matcher direct = DIRECT_REDIRECT.matcher(response.body);
            if (direct.find()) {
                response = get(resolve(response.url, htmlDecode(direct.group(1)), "页面跳转"), null);
                continue;
            }
            Matcher finger = FINGER_REDIRECT.matcher(response.body);
            if (finger.find()) {
                response = get(resolve(response.url,
                        htmlDecode(finger.group(1)) + deviceId, "设备验证跳转"), null);
                continue;
            }
            return response;
        }
        throw new ClientException("加载页跳转次数过多，网站流程可能已变化");
    }

    private Response get(String target, Map<String, String> extraHeaders) throws Exception {
        String current = safeUrl(target, "GET 请求");
        for (int redirect = 0; redirect < 6; redirect++) {
            Response response = request("GET", current, null, extraHeaders);
            if (response.status >= 300 && response.status < 400) {
                if (response.location == null || response.location.isEmpty()) {
                    throw new ClientException("服务器返回了无地址重定向");
                }
                current = resolve(current, response.location, "HTTP 重定向");
                continue;
            }
            requireSuccess(response);
            return response;
        }
        throw new ClientException("HTTP 重定向次数过多");
    }

    private Response post(Map<String, String> query, List<Pair> form, String refererModule)
            throws Exception {
        String body = encodePairs(form);
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
        headers.put("X-Requested-With", "XMLHttpRequest");
        headers.put("Origin", "https://" + HOST);
        headers.put("Referer", BASE_URL + "?i=" + SITE_ID + "&c=entry&do=" + refererModule
                + "&m=" + ("register".equals(refererModule) ? PORTAL : UTILITY));
        Response response = request("POST", url(query), body.getBytes(StandardCharsets.UTF_8), headers);
        if (response.status >= 300 && response.status < 400) {
            throw new ClientException("POST 接口意外返回重定向，已停止发送认证数据");
        }
        requireSuccess(response);
        saveState();
        return response;
    }

    private Response request(String method, String target, byte[] body,
                             Map<String, String> extraHeaders) throws Exception {
        long startedAt = System.nanoTime();
        URI uri = URI.create(safeUrl(target, method + " 请求"));
        HttpsURLConnection connection = (HttpsURLConnection) uri.toURL().openConnection();
        connection.setConnectTimeout(TIMEOUT_MS);
        connection.setReadTimeout(TIMEOUT_MS);
        connection.setInstanceFollowRedirects(false);
        connection.setRequestMethod(method);
        connection.setRequestProperty("User-Agent",
                "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 Chrome/131 Mobile Safari/537.36");
        connection.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9");
        if (!cookies.isEmpty()) {
            connection.setRequestProperty("Cookie", cookieHeader());
        }
        if (extraHeaders != null) {
            for (Map.Entry<String, String> header : extraHeaders.entrySet()) {
                connection.setRequestProperty(header.getKey(), header.getValue());
            }
        }
        if (body != null) {
            connection.setDoOutput(true);
            connection.setFixedLengthStreamingMode(body.length);
            try (OutputStream output = connection.getOutputStream()) {
                output.write(body);
            }
        }
        int status = connection.getResponseCode();
        captureCookies(connection);
        InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
        String responseBody = read(stream);
        String location = connection.getHeaderField("Location");
        String finalUrl = connection.getURL().toString();
        connection.disconnect();
        long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L;
        int responseBytes = responseBody.getBytes(StandardCharsets.UTF_8).length;
        DiagnosticLog.info("HTTP " + method + " · 状态 " + status
                + " · " + elapsedMs + " ms · 响应 " + responseBytes + " 字节");
        return new Response(status, responseBody, location,
                safeUrl(finalUrl, "连接最终地址"));
    }

    private void captureCookies(HttpURLConnection connection) {
        Map<String, List<String>> headers = connection.getHeaderFields();
        for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
            if (entry.getKey() == null || !"set-cookie".equalsIgnoreCase(entry.getKey())) {
                continue;
            }
            for (String value : entry.getValue()) {
                try {
                    for (HttpCookie cookie : HttpCookie.parse(value)) {
                        if (cookie.hasExpired() || "deleted".equalsIgnoreCase(cookie.getValue())) {
                            cookies.remove(cookie.getName());
                        } else {
                            cookies.put(cookie.getName(), cookie.getValue());
                        }
                    }
                } catch (IllegalArgumentException ignored) {
                    // Ignore a malformed unrelated cookie while retaining valid session cookies.
                }
            }
        }
    }

    private void loadCookies() {
        String raw = store.get("cookies");
        if (raw.isEmpty()) {
            return;
        }
        try {
            JSONObject object = new JSONObject(raw);
            JSONArray names = object.names();
            if (names != null) {
                for (int i = 0; i < names.length(); i++) {
                    String name = names.getString(i);
                    cookies.put(name, object.getString(name));
                }
            }
        } catch (JSONException ignored) {
            store.clear("cookies");
        }
    }

    private void saveState() {
        JSONObject object = new JSONObject(cookies);
        store.put("cookies", object.toString());
        store.put("device_id", deviceId);
    }

    private static JSONObject objectResponse(Response response) throws ClientException {
        try {
            return new JSONObject(response.body);
        } catch (JSONException exception) {
            throw new ClientException("接口没有返回 JSON，会话可能已失效；响应："
                    + concise(response.body), exception);
        }
    }

    private static JSONObject findUnique(JSONArray items, String key, String label)
            throws ClientException {
        if (items == null) {
            throw new ClientException(label + "列表为空");
        }
        List<JSONObject> found = findAll(items, key, false);
        if (found.isEmpty()) {
            throw new ClientException("找不到" + label + "：" + key);
        }
        if (found.size() > 1) {
            throw new ClientException(label + "名称不唯一，请使用 ID");
        }
        return found.get(0);
    }

    private static List<JSONObject> findAll(JSONArray items, String key, boolean room) {
        List<JSONObject> found = new ArrayList<>();
        if (items == null) {
            return found;
        }
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.optJSONObject(i);
            if (item == null) {
                continue;
            }
            if (key.equals(String.valueOf(item.opt("id"))) || key.equals(item.optString("name"))) {
                found.add(item);
            }
        }
        return found;
    }

    private static BigDecimal number(Object raw, String label) throws ClientException {
        Matcher matcher = NUMBER.matcher(String.valueOf(raw));
        if (!matcher.find()) {
            throw new ClientException(label + "中没有可识别数值");
        }
        try {
            return new BigDecimal(matcher.group());
        } catch (NumberFormatException exception) {
            throw new ClientException(label + "数值格式错误", exception);
        }
    }

    private static void validateDate(String value, String meter) throws ClientException {
        if (!value.matches("\\d{4}-\\d{2}-\\d{2}")) {
            throw new ClientException(meter + " 用量日期格式错误");
        }
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT);
        format.setLenient(false);
        try {
            format.parse(value);
        } catch (ParseException exception) {
            throw new ClientException(meter + " 用量日期不存在", exception);
        }
    }

    private static String decodeJavaScriptString(String value) throws ClientException {
        StringBuilder result = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char current = value.charAt(i);
            if (current != '\\' || i + 1 >= value.length()) {
                result.append(current);
                continue;
            }
            char escape = value.charAt(++i);
            switch (escape) {
                case 'n': result.append('\n'); break;
                case 'r': result.append('\r'); break;
                case 't': result.append('\t'); break;
                case 'b': result.append('\b'); break;
                case 'f': result.append('\f'); break;
                case '\\': result.append('\\'); break;
                case '\'': result.append('\''); break;
                case '"': result.append('"'); break;
                case '/': result.append('/'); break;
                case 'u':
                    if (i + 4 >= value.length()) {
                        throw new ClientException("宿舍列表包含不完整 Unicode 转义");
                    }
                    try {
                        result.append((char) Integer.parseInt(value.substring(i + 1, i + 5), 16));
                    } catch (NumberFormatException exception) {
                        throw new ClientException("宿舍列表 Unicode 转义无效", exception);
                    }
                    i += 4;
                    break;
                case 'x':
                    if (i + 2 >= value.length()) {
                        throw new ClientException("宿舍列表包含不完整十六进制转义");
                    }
                    try {
                        result.append((char) Integer.parseInt(value.substring(i + 1, i + 3), 16));
                    } catch (NumberFormatException exception) {
                        throw new ClientException("宿舍列表十六进制转义无效", exception);
                    }
                    i += 2;
                    break;
                default: result.append(escape);
            }
        }
        return result.toString();
    }

    private static String htmlDecode(String value) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            return Html.fromHtml(value, Html.FROM_HTML_MODE_LEGACY).toString();
        }
        //noinspection deprecation
        return Html.fromHtml(value).toString();
    }

    private static String read(InputStream stream) throws IOException {
        if (stream == null) {
            return "";
        }
        StringBuilder result = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            char[] buffer = new char[4096];
            int count;
            while ((count = reader.read(buffer)) >= 0) {
                result.append(buffer, 0, count);
            }
        }
        return result.toString();
    }

    private static void requireSuccess(Response response) throws ClientException {
        if (response.status < 200 || response.status >= 300) {
            throw new ClientException("服务器返回 HTTP " + response.status + "："
                    + concise(response.body));
        }
    }

    private static String safeUrl(String value, String source) throws ClientException {
        try {
            URI uri = URI.create(value.trim());
            int port = uri.getPort();
            String host = normalizeHost(uri.getHost());
            if (!"https".equalsIgnoreCase(uri.getScheme())
                    || !HOST.equalsIgnoreCase(host)
                    || (port != -1 && port != 443)
                    || uri.getUserInfo() != null) {
                logRejectedOrigin(source, uri);
                if (CAMPUS_VPN_HOST.equalsIgnoreCase(host)) {
                    throw new UnexpectedAddressException(
                            "当前网络被学校系统重定向到校园 VPN 入口，请连接校内网络或先登录学校 VPN 后重试");
                }
                throw new UnexpectedAddressException(
                        "拒绝访问非预期地址，请关闭 VPN/代理或切换网络后重试");
            }
            return canonicalSameOriginUrl(uri, port);
        } catch (IllegalArgumentException exception) {
            throw new ClientException("服务器跳转地址无效", exception);
        }
    }

    private static String resolve(String base, String location, String source)
            throws ClientException {
        try {
            return safeUrl(URI.create(base).resolve(location.trim()).toString(), source);
        } catch (IllegalArgumentException exception) {
            throw new ClientException("无法解析服务器跳转地址", exception);
        }
    }

    private static String normalizeHost(String host) {
        if (host == null) return "";
        String normalized = host.trim();
        while (normalized.endsWith(".")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private static String canonicalSameOriginUrl(URI uri, int port) {
        StringBuilder result = new StringBuilder("https://").append(HOST);
        if (port == 443) result.append(":443");
        String path = uri.getRawPath();
        result.append(path == null || path.isEmpty() ? "/" : path);
        if (uri.getRawQuery() != null) result.append('?').append(uri.getRawQuery());
        if (uri.getRawFragment() != null) result.append('#').append(uri.getRawFragment());
        return result.toString();
    }

    private static void logRejectedOrigin(String source, URI uri) {
        String scheme = uri.getScheme() == null ? "缺失" : uri.getScheme();
        String host = uri.getHost() == null ? "无法解析" : uri.getHost();
        String port = uri.getPort() == -1 ? "默认" : String.valueOf(uri.getPort());
        DiagnosticLog.warning("已拦截非预期地址 · 来源=" + source
                + " · 协议=" + scheme + " · 主机=" + host + " · 端口=" + port);
    }

    private static String withBaseParams(Map<String, String> additions) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("i", SITE_ID);
        values.putAll(additions);
        return url(values);
    }

    private static Map<String, String> entryParams(String action, String module) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("i", SITE_ID);
        values.put("c", "entry");
        values.put("do", action);
        values.put("m", module);
        return values;
    }

    private static String url(Map<String, String> query) {
        StringBuilder result = new StringBuilder(BASE_URL);
        if (!query.isEmpty()) {
            result.append('?');
            boolean first = true;
            for (Map.Entry<String, String> entry : query.entrySet()) {
                if (!first) result.append('&');
                first = false;
                result.append(encode(entry.getKey())).append('=').append(encode(entry.getValue()));
            }
        }
        return result.toString();
    }

    private static String encodePairs(List<Pair> values) {
        StringBuilder result = new StringBuilder();
        for (Pair value : values) {
            if (result.length() > 0) result.append('&');
            result.append(encode(value.name)).append('=').append(encode(value.value));
        }
        return result.toString();
    }

    private static String encode(String value) {
        try {
            return URLEncoder.encode(value, "UTF-8");
        } catch (UnsupportedEncodingException impossible) {
            throw new AssertionError(impossible);
        }
    }

    private static List<Pair> pairs(String name, String value) {
        List<Pair> result = new ArrayList<>();
        result.add(new Pair(name, value));
        return result;
    }

    private String cookieHeader() {
        StringBuilder result = new StringBuilder();
        for (Map.Entry<String, String> cookie : cookies.entrySet()) {
            if (result.length() > 0) result.append("; ");
            result.append(cookie.getKey()).append('=').append(cookie.getValue());
        }
        return result.toString();
    }

    private static String concise(String value) {
        String normalized = value == null ? "" : value.trim().replaceAll("\\s+", " ");
        return normalized.length() > 120 ? normalized.substring(0, 120) + "…" : normalized;
    }

    static class ClientException extends Exception {
        ClientException(String message) { super(message); }
        ClientException(String message, Throwable cause) { super(message, cause); }
    }

    private static final class UnexpectedAddressException extends ClientException {
        UnexpectedAddressException(String message) { super(message); }
    }

    private static final class Pair {
        final String name;
        final String value;
        Pair(String name, String value) { this.name = name; this.value = value; }
    }

    private static final class Response {
        final int status;
        final String body;
        final String location;
        final String url;
        Response(int status, String body, String location, String url) {
            this.status = status;
            this.body = body;
            this.location = location;
            this.url = url;
        }
    }

    private static final class Target {
        final List<String> ids;
        final String name;
        final boolean water;
        Target(List<String> ids, String name) {
            this.ids = ids;
            this.name = name;
            this.water = name.contains("水");
        }

        String kind() {
            if (water) return "水表";
            if (name.contains("表1") || name.contains("表一")) return "电表一";
            if (name.contains("表2") || name.contains("表二")) return "电表二";
            return "电表";
        }
    }
}
