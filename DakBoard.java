import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.stream.Stream;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/**
 * Event Board (DAKboard) - standalone Java server, no dependencies.
 *
 * Run from the dakboard-java folder:
 *   javac DakBoard.java
 *   java DakBoard [port, default 8080]
 *
 * Board:        http://localhost:8080/event-board
 * Control page: http://localhost:8080/event-board/admin
 * Editor page:  http://localhost:8080/event-board/editor (limited content editing)
 */
public class DakBoard {

  static Path ROOT = Paths.get("").toAbsolutePath();
  static final long MAX_BODY = 128L * 1024 * 1024; // 128 MB (photo/video uploads)
  static final String[] PHOTO_EXTS = {".jpg", ".jpeg", ".png", ".webp", ".gif"};
  static final String[] VIDEO_EXTS = {".mp4", ".webm"};
  static final String SESSION_COOKIE = "DAKBOARD_SESSION";
  static final String CREDENTIAL_FILE = "admin-credential.json";
  static final String EDITOR_CREDENTIAL_FILE = "editor-credential.json";
  static final int MIN_PASSWORD_LENGTH = 12;
  static final int HASH_ITERATIONS = 210000;
  static final int HASH_BITS = 256;
  static final long SESSION_TTL_SECONDS = 8L * 60L * 60L;
  static final SecureRandom AUTH_RANDOM = new SecureRandom();
  static final Map<String, Session> SESSIONS = new ConcurrentHashMap<>();
  static volatile int SERVER_PORT = 8080;

  static class Session {
    final String csrf;
    final long expiresAt;
    final String role;
    final String client;
    Session(String tokenCsrf, String role) {
      this(tokenCsrf, role, null);
    }
    Session(String tokenCsrf, String role, String client) {
      csrf = tokenCsrf;
      expiresAt = System.currentTimeMillis() + SESSION_TTL_SECONDS * 1000L;
      this.role = role == null ? "editor" : role;
      this.client = client;
    }
  }

  public static void main(String[] args) throws Exception {
    int port = 8080;
    if (args.length > 0) {
      port = Integer.parseInt(args[0]);
    }
    SERVER_PORT = port;
    Files.createDirectories(ROOT.resolve("photos"));
    Files.createDirectories(ROOT.resolve("clients"));
    Path settings = ROOT.resolve("settings.json");
    if (!Files.exists(settings)) {
      Files.copy(ROOT.resolve("settings.default.json"), settings);
    }
    try {
      String body = Files.readString(settings, StandardCharsets.UTF_8);
      String fixed = insertJsonFieldIfMissing(body, "rooms", defaultJsonField("rooms"));
      fixed = insertJsonFieldIfMissing(fixed, "devices", defaultJsonField("devices"));
      if (!fixed.equals(body)) {
        Files.writeString(settings, fixed, StandardCharsets.UTF_8);
        System.out.println("Restored missing rooms list in settings.json");
      }
    } catch (IOException e) {
      System.out.println("Could not verify settings.json: " + e.getMessage());
    }
    String bind = envOrDefault("DAKBOARD_BIND", "0.0.0.0");
    HttpServer server = HttpServer.create(new InetSocketAddress(bind, port), 0);
    server.createContext("/event-board/login", DakBoard::handleLoginPage);
    server.createContext("/event-board/admin", DakBoard::handleAdmin);
    server.createContext("/event-board/editor", DakBoard::handleEditorPage);
    server.createContext("/event-board/room", DakBoard::handleRoom);
    server.createContext("/event-board/schedule", DakBoard::handleSchedule);
    server.createContext("/event-board/slides", DakBoard::handleSlides);
    server.createContext("/event-board/device", DakBoard::handleDevice);
    server.createContext("/api/event-board/device-route", DakBoard::handleDeviceRoute);
    server.createContext("/event-board", DakBoard::handleBoard);
    server.createContext("/c", DakBoard::handleClientPages);
    server.createContext("/api/event-board/clients/signup", DakBoard::handleClientSignup);
    server.createContext("/api/event-board/clients/delete", DakBoard::handleClientDelete);
    server.createContext("/api/event-board/clients", DakBoard::handleClientList);
    server.createContext("/api/event-board/login", DakBoard::handleLogin);
    server.createContext("/api/event-board/logout", DakBoard::handleLogout);
    server.createContext("/api/event-board/session", DakBoard::handleSession);
    server.createContext("/api/event-board/password", DakBoard::handleChangePassword);
    server.createContext("/api/event-board/editor-password", DakBoard::handleEditorPassword);
    server.createContext("/api/event-board/admin/settings", DakBoard::handleAdminSettings);
    server.createContext("/api/event-board/settings", DakBoard::handleSettings);
    server.createContext("/api/event-board/server-info", DakBoard::handleServerInfo);
    server.createContext("/api/event-board/photos/upload", DakBoard::handleUpload);
    server.createContext("/api/event-board/photos/delete", DakBoard::handleDelete);
    server.createContext("/api/event-board/photos", DakBoard::handlePhotos);
    server.createContext("/api/event-board/events", DakBoard::handleEvents);
    server.createContext("/sample-events.ics", DakBoard::handleSampleEvents);
    server.createContext("/photos", DakBoard::handlePhotoFile);
    server.createContext("/dakboard", DakBoard::handleLegacyRedirect);
    server.createContext("/api/dakboard", DakBoard::handleLegacyRedirect);
    server.createContext("/lhc-events-board", DakBoard::handleLegacyRedirect);
    server.createContext("/api/lhc-events-board", DakBoard::handleLegacyRedirect);
    server.createContext("/", DakBoard::handleRoot);
    server.setExecutor(Executors.newFixedThreadPool(8));
    server.start();
    System.out.println("DAKboard running at http://localhost:" + port + "/event-board");
    System.out.println("Admin login at http://localhost:" + port + "/event-board/login");
    System.out.println("Client boards: sign up at http://localhost:" + port + "/c"
        + " \u2014 each board lives at /c/<name>");
    if (!authConfigured()) {
      System.out.println("Set DAKBOARD_ADMIN_PASSWORD before using the admin login.");
    } else if (Files.isRegularFile(ROOT.resolve(CREDENTIAL_FILE))) {
      System.out.println("Admin password comes from " + CREDENTIAL_FILE
          + " (set from the admin page). Delete that file to fall back to the startup password.");
    }
    System.out.println("Editor page at http://localhost:" + port + "/event-board/editor"
        + " (username \"" + editorUsername() + "\")");
    if (!editorConfigured()) {
      System.out.println("Editor login is disabled. Set DAKBOARD_EDITOR_PASSWORD or set an editor"
          + " password from the admin page (Editor access).");
    } else if (Files.isRegularFile(ROOT.resolve(EDITOR_CREDENTIAL_FILE))) {
      System.out.println("Editor password comes from " + EDITOR_CREDENTIAL_FILE
          + " (set from the admin page).");
    }
  }

  // ---------------- routes ----------------

  static void handleRoot(HttpExchange ex) throws IOException {
    redirect(ex, "/event-board");
  }

  /** Redirects old /dakboard... and /lhc-events-board... URLs to /event-board.... */
  static void handleLegacyRedirect(HttpExchange ex) throws IOException {
    String path = ex.getRequestURI().getPath();
    String query = ex.getRequestURI().getRawQuery();
    String rest = "";
    for (String prefix : new String[]{"/api/dakboard", "/dakboard", "/api/lhc-events-board", "/lhc-events-board"}) {
      if (path.equals(prefix)) { rest = ""; break; }
      if (path.startsWith(prefix + "/")) { rest = path.substring(prefix.length()); break; }
    }
    String base = path.startsWith("/api/") ? "/api/event-board" : "/event-board";
    String target = base + rest;
    if (query != null && !query.isEmpty()) target += "?" + query;
    redirect(ex, target);
  }

  /**
   * Shared TV launcher: /event-board/device looks at the requesting device's IP
   * (settings.devices) and redirects it to its assigned output, so a TV only ever
   * needs to bookmark this one URL.
   */
  static void handleDevice(HttpExchange ex) throws IOException { handleDevice(ex, null); }

  /** GET /event-board/device (platform) or /c/<name>/device (client TV setup page). */
  static void handleDevice(HttpExchange ex, String client) throws IOException {
    if (!method(ex, "GET")) { send(ex, 405, "Method not allowed", "text/plain"); return; }
    String remote = clientIp(ex);
    String target = deviceTargetForIp(remote, client);
    if (target != null) { redirect(ex, target); return; }
    String page = "<!DOCTYPE html><html lang=\"en\"><head><meta charset=\"utf-8\">"
        + "<meta http-equiv=\"refresh\" content=\"5\">"
        + "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">"
        + "<title>Event Board \u2014 Device Setup</title>"
        + "<style>body{font-family:Segoe UI,Arial,sans-serif;margin:0;min-height:100vh;"
        + "display:flex;align-items:center;justify-content:center;"
        + "background:linear-gradient(135deg,#e9a36b 0%,#8fb69b 60%,#789fbd 100%)}"
        + ".card{background:#fff;border-radius:16px;padding:36px 40px;max-width:640px;margin:20px;"
        + "box-shadow:0 18px 50px rgba(0,0,0,.25)}h1{font-size:22px;margin:0 0 14px;color:#173b63}"
        + "p{font-size:15px;line-height:1.55;color:#315b7d;margin:10px 0}"
        + ".ip{font-family:Consolas,monospace;font-size:30px;font-weight:800;background:#eef4fb;"
        + "border:2px dashed #789fbd;border-radius:10px;padding:12px 18px;color:#1d5687;"
        + "text-align:center;user-select:all}</style></head><body><div class=\"card\">"
        + "<h1>Device not yet assigned</h1>"
        + "<p>This device has no assignment yet. In the Admin page, open "
        + "<b>DEVICES \u2014 IP \u2192 OUTPUT</b>, add this IP address, choose its output, then press "
        + "SAVE. This page refreshes itself every 5 seconds.</p>"
        + "<p>IP address of this device:</p><div class=\"ip\">" + remote + "</div>"
        + "</div></body></html>";
    send(ex, 200, page, "text/html; charset=utf-8");
  }

  /**
   * JSON used by every TV output page (polled every 2s): returns {"target":"..."}
   * for the requesting device's assigned output URL, or {"target":null}. Outputs
   * reload themselves when the assignment changes, so TVs follow the admin list.
   */
  static void handleDeviceRoute(HttpExchange ex) throws IOException {
    if (!method(ex, "GET")) { send(ex, 405, "Method not allowed", "text/plain"); return; }
    String target = deviceTargetForIp(clientIp(ex), clientFromQuery(ex));
    String json = target == null ? "{\"target\":null}"
        : "{\"target\":\"" + jsonEscape(target) + "\"}";
    send(ex, 200, json, "application/json; charset=utf-8");
  }

  /** Valid client slug from ?client=, or null for platform scope. */
  static String clientFromQuery(HttpExchange ex) {
    String client = queryParam(ex.getRequestURI().getRawQuery(), "client");
    if (client == null) return null;
    client = client.trim().toLowerCase(Locale.ROOT);
    return validClientSlug(client) && clientExists(client) ? client : null;
  }

  /** Normalized requesting IP (strips IPv6-mapped prefix; loopback becomes 127.0.0.1). */
  static String clientIp(HttpExchange ex) {
    String remote = "";
    try {
      remote = ex.getRemoteAddress().getAddress().getHostAddress();
    } catch (Exception e) { /* keep empty */ }
    if (remote.startsWith("::ffff:")) remote = remote.substring("::ffff:".length());
    if (remote.equals("::1") || remote.equals("0:0:0:0:0:0:0:1")) remote = "127.0.0.1";
    return remote;
  }

  /** Assigned output URL for an IP in the client's (or platform) devices, or null. */
  static String deviceTargetForIp(String ip, String client) {
    String settings = client == null ? readSettingsText() : readClientSettings(client);
    for (String device : splitJsonObjects(jsonRawField(settings, "devices"))) {
      String deviceIp = jsonField(device, "ip");
      if (deviceIp != null && deviceIp.equalsIgnoreCase(ip)) {
        return deviceTarget(device, client);
      }
    }
    return null;
  }

  /** Maps one settings.devices entry to the output URL it should open. */
  static String deviceTarget(String device, String client) {
    String root = client == null ? "/event-board" : "/c/" + client;
    String out = jsonField(device, "output");
    String room = jsonField(device, "room");
    if ("room-landscape".equals(out) || "room-portrait".equals(out)) {
      String orientation = "room-portrait".equals(out) ? "portrait" : "landscape";
      return root + "/room?orientation=" + orientation
          + "&room=" + urlEncodePath(room == null ? "" : room);
    }
    if ("board-portrait".equals(out)) return root + "?orientation=portrait";
    if ("board-landscape".equals(out)) return root + "?orientation=landscape";
    if ("schedule-portrait".equals(out)) return root + "/schedule?orientation=portrait";
    if ("solo".equals(out)) return root + "/schedule?orientation=portrait&design=solo";
    if ("slides-landscape".equals(out)) return root + "/slides?orientation=landscape";
    if ("slides-portrait".equals(out)) return root + "/slides?orientation=portrait";
    return root + "/schedule?orientation=landscape";
  }

  static String urlEncodePath(String value) {
    return value.trim().replace("&", "%26").replace("#", "%23")
        .replace("?", "%3F").replace("+", "%2B").replace(" ", "%20");
  }

  /** Splits a raw JSON array string into its top-level object fragments. */
  static List<String> splitJsonObjects(String arrayRaw) {
    List<String> out = new ArrayList<>();
    if (arrayRaw == null) return out;
    int depth = 0;
    int start = -1;
    boolean inString = false;
    boolean escaped = false;
    for (int i = 0; i < arrayRaw.length(); i++) {
      char c = arrayRaw.charAt(i);
      if (inString) {
        if (escaped) escaped = false;
        else if (c == '\\') escaped = true;
        else if (c == '"') inString = false;
        continue;
      }
      if (c == '"') { inString = true; continue; }
      if (c == '{') { if (depth == 0) start = i; depth++; }
      else if (c == '}') {
        depth--;
        if (depth == 0 && start >= 0) {
          out.add(arrayRaw.substring(start, i + 1));
          start = -1;
        }
      }
    }
    return out;
  }

  static void handleBoard(HttpExchange ex) throws IOException {
    if (!method(ex, "GET")) { send(ex, 405, "Method not allowed", "text/plain"); return; }
    if (hasQueryParam(ex.getRequestURI().getRawQuery(), "room")) {
      handleRoom(ex);
      return;
    }
    if ("schedule".equals(settingsField("mainOutput"))) {
      sendFile(ex, ROOT.resolve("schedule.html"), "text/html; charset=utf-8");
      return;
    }
    sendFile(ex, ROOT.resolve("board.html"), "text/html; charset=utf-8");
  }

  static void handleRoom(HttpExchange ex) throws IOException {
    if (!method(ex, "GET")) { send(ex, 405, "Method not allowed", "text/plain"); return; }
    sendFile(ex, ROOT.resolve("room.html"), "text/html; charset=utf-8");
  }

  static void handleSlides(HttpExchange ex) throws IOException {
    if (!method(ex, "GET")) { send(ex, 405, "Method not allowed", "text/plain"); return; }
    sendFile(ex, ROOT.resolve("slides.html"), "text/html; charset=utf-8");
  }

  static void handleSchedule(HttpExchange ex) throws IOException {
    if (!method(ex, "GET")) { send(ex, 405, "Method not allowed", "text/plain"); return; }
    sendFile(ex, ROOT.resolve("schedule.html"), "text/html; charset=utf-8");
  }

  static boolean hasQueryParam(String query, String name) {
    if (query == null) return false;
    for (String part : query.split("&")) {
      int equals = part.indexOf('=');
      String key = equals < 0 ? part : part.substring(0, equals);
      if (name.equals(key)) return true;
    }
    return false;
  }

  static void handleAdmin(HttpExchange ex) throws IOException {
    if (!method(ex, "GET")) { send(ex, 405, "Method not allowed", "text/plain"); return; }
    Session session = session(ex);
    if (session == null) {
      redirect(ex, "/event-board/login?next=/event-board/admin");
      return;
    }
    if (!"admin".equals(session.role)) {
      redirect(ex, "/event-board/editor");
      return;
    }
    sendFile(ex, ROOT.resolve("admin.html"), "text/html; charset=utf-8");
  }

  static void handleEditorPage(HttpExchange ex) throws IOException {
    if (!method(ex, "GET")) { send(ex, 405, "Method not allowed", "text/plain"); return; }
    if (session(ex) == null) {
      redirect(ex, "/event-board/login?next=/event-board/editor");
      return;
    }
    sendFile(ex, ROOT.resolve("editor.html"), "text/html; charset=utf-8");
  }

  static void handleLoginPage(HttpExchange ex) throws IOException {
    if (!method(ex, "GET")) { send(ex, 405, "Method not allowed", "text/plain"); return; }
    sendFile(ex, ROOT.resolve("login.html"), "text/html; charset=utf-8");
  }

  static void handleLogin(HttpExchange ex) throws IOException {
    if (!method(ex, "POST")) { send(ex, 405, "Method not allowed", "text/plain"); return; }
    String body = readBody(ex, 16 * 1024);
    if (body == null) { send(ex, 413, "{\"error\":\"request too large\"}", "application/json"); return; }
    Map<String, String> form = parseForm(body);
    if (!authConfigured() && !editorConfigured()) {
      send(ex, 503, "{\"error\":\"No login is configured on this server yet.\"}", "application/json");
      return;
    }
    String username = form.getOrDefault("username", "");
    String password = form.getOrDefault("password", "");
    String client = form.getOrDefault("client", "").trim();
    if (!client.isEmpty()) {
      if (!clientCredentialsMatch(client, username, password)) {
        send(ex, 401, "{\"error\":\"Invalid username or password.\"}", "application/json");
        return;
      }
      cleanupSessions();
      String token = randomToken();
      String csrf = randomToken();
      SESSIONS.put(token, new Session(csrf, "client", client));
      setSessionCookie(ex, token, false);
      String nextRaw = form.get("next");
      String next = nextRaw == null || nextRaw.isEmpty()
          ? "/c/" + client + "/admin" : safeNext(nextRaw);
      send(ex, 200, "{\"ok\":true,\"role\":\"client\",\"client\":" + quoted(client)
          + ",\"next\":" + quoted(next) + "}", "application/json");
      return;
    }
    String role = null;
    if (credentialsMatch(username, password)) {
      role = "admin";
    } else if (editorCredentialsMatch(username, password)) {
      role = "editor";
    } else {
      send(ex, 401, "{\"error\":\"Invalid username or password.\"}", "application/json");
      return;
    }
    cleanupSessions();
    String token = randomToken();
    String csrf = randomToken();
    SESSIONS.put(token, new Session(csrf, role));
    setSessionCookie(ex, token, false);
    String next = "editor".equals(role) ? "/event-board/editor" : safeNext(form.get("next"));
    send(ex, 200, "{\"ok\":true,\"role\":" + quoted(role) + ",\"next\":" + quoted(next) + "}", "application/json");
  }

  static void handleLogout(HttpExchange ex) throws IOException {
    if (!method(ex, "POST")) { send(ex, 405, "Method not allowed", "text/plain"); return; }
    Session session = session(ex);
    if (session == null) {
      setSessionCookie(ex, "", true);
      send(ex, 401, "{\"error\":\"Not authenticated\"}", "application/json");
      return;
    }
    if (!csrfMatches(ex, session)) {
      send(ex, 403, "{\"error\":\"Invalid CSRF token\"}", "application/json");
      return;
    }
    String token = cookieValue(ex, SESSION_COOKIE);
    if (token != null) SESSIONS.remove(token);
    setSessionCookie(ex, "", true);
    send(ex, 200, "{\"ok\":true}", "application/json");
  }

  static void handleSession(HttpExchange ex) throws IOException {
    if (!method(ex, "GET")) { send(ex, 405, "Method not allowed", "text/plain"); return; }
    Session session = session(ex);
    if (session == null) {
      send(ex, 401, "{\"error\":\"authentication required\"}", "application/json");
      return;
    }
    if ("client".equals(session.role)) {
      String client = session.client == null ? "" : session.client;
      String clientUser = clientUsername(client);
      send(ex, 200, "{\"authenticated\":true,\"username\":" + quoted(clientUser)
          + ",\"role\":\"client\",\"client\":" + quoted(client)
          + ",\"csrfToken\":" + quoted(session.csrf) + "}", "application/json");
      return;
    }
    String username = "admin".equals(session.role) ? adminUsername() : editorUsername();
    StringBuilder out = new StringBuilder("{\"authenticated\":true,\"username\":")
        .append(quoted(username))
        .append(",\"role\":").append(quoted(session.role))
        .append(",\"csrfToken\":").append(quoted(session.csrf));
    if ("admin".equals(session.role)) {
      out.append(",\"editorUsername\":").append(quoted(editorUsername()))
          .append(",\"editorEnabled\":").append(editorConfigured());
    }
    out.append("}");
    send(ex, 200, out.toString(), "application/json");
  }

  static void handleChangePassword(HttpExchange ex) throws IOException {
    if (!method(ex, "POST")) { send(ex, 405, "Method not allowed", "text/plain"); return; }
    String pwClient = queryParam(ex.getRequestURI().getRawQuery(), "client");
    if (pwClient != null) {
      handleChangeClientPassword(ex, pwClient);
      return;
    }
    if (!requireAdmin(ex)) return;
    Session session = session(ex);
    if (!csrfMatches(ex, session)) {
      send(ex, 403, "{\"error\":\"Invalid CSRF token\"}", "application/json");
      return;
    }
    String body = readBody(ex, 8 * 1024);
    if (body == null) { send(ex, 413, "{\"error\":\"request too large\"}", "application/json"); return; }
    String current = jsonField(body, "currentPassword");
    String next = jsonField(body, "newPassword");
    if (current == null || next == null) {
      send(ex, 400, "{\"error\":\"missing password field\"}", "application/json");
      return;
    }
    if (next.length() < MIN_PASSWORD_LENGTH) {
      send(ex, 400, "{\"error\":\"New password must be at least "
          + MIN_PASSWORD_LENGTH + " characters.\"}", "application/json");
      return;
    }
    if (next.equals(current)) {
      send(ex, 400, "{\"error\":\"New password must be different from the current one.\"}",
          "application/json");
      return;
    }
    if (!credentialsMatch(adminUsername(), current)) {
      send(ex, 400, "{\"error\":\"Current password is incorrect.\"}", "application/json");
      return;
    }
    try {
      savePassword(next);
    } catch (Exception e) {
      send(ex, 500, "{\"error\":\"Could not save the new password.\"}", "application/json");
      return;
    }
    String keep = cookieValue(ex, SESSION_COOKIE);
    String finalKeep = keep;
    SESSIONS.entrySet().removeIf(entry -> !entry.getKey().equals(finalKeep));
    cleanupSessions();
    send(ex, 200, "{\"ok\":true,\"message\":\"Password updated. Other sessions were signed out.\"}",
        "application/json");
  }

  static void handleAdminSettings(HttpExchange ex) throws IOException {
    if (!method(ex, "GET")) { send(ex, 405, "Method not allowed", "text/plain"); return; }
    if (!requireAdmin(ex)) return;
    ex.getResponseHeaders().set("Cache-Control", "no-store");
    sendFile(ex, ROOT.resolve("settings.json"), "application/json; charset=utf-8");
  }

  static void handleEditorPassword(HttpExchange ex) throws IOException {
    if (!method(ex, "POST")) { send(ex, 405, "Method not allowed", "text/plain"); return; }
    if (!requireAdmin(ex)) return;
    Session session = session(ex);
    if (!csrfMatches(ex, session)) {
      send(ex, 403, "{\"error\":\"Invalid CSRF token\"}", "application/json");
      return;
    }
    String body = readBody(ex, 8 * 1024);
    if (body == null) { send(ex, 413, "{\"error\":\"request too large\"}", "application/json"); return; }
    String rawName = jsonField(body, "newUsername");
    String next = jsonField(body, "newPassword");
    String name = rawName == null || rawName.trim().isEmpty() ? editorUsername() : rawName.trim();
    if (!validLoginName(name)) {
      send(ex, 400, "{\"error\":\"Username must be 3-32 characters: letters, numbers, dot, underscore, or hyphen.\"}",
          "application/json");
      return;
    }
    if (name.equalsIgnoreCase(adminUsername())) {
      send(ex, 400, "{\"error\":\"Username must be different from the admin username.\"}",
          "application/json");
      return;
    }
    if (next == null && rawName != null && !rawName.trim().isEmpty() && !hasEditorHash()) {
      send(ex, 400, "{\"error\":\"No editor password is set yet — type one (12+ characters).\"}",
          "application/json");
      return;
    }
    if (next != null && !next.isEmpty() && next.length() < MIN_PASSWORD_LENGTH) {
      send(ex, 400, "{\"error\":\"Editor password must be at least "
          + MIN_PASSWORD_LENGTH + " characters.\"}", "application/json");
      return;
    }
    Path file = ROOT.resolve(EDITOR_CREDENTIAL_FILE);
    if (next != null && next.isEmpty() && rawName == null) {
      try {
        Files.deleteIfExists(file);
      } catch (IOException e) {
        send(ex, 500, "{\"error\":\"Could not remove the editor account.\"}", "application/json");
        return;
      }
      SESSIONS.entrySet().removeIf(entry -> "editor".equals(entry.getValue().role));
      send(ex, 200, "{\"ok\":true,\"editorUsername\":" + quoted(editorUsername())
          + ",\"editorEnabled\":" + editorConfigured()
          + ",\"message\":\"Editor account removed. Editor login now follows DAKBOARD_EDITOR_PASSWORD, if set.\"}",
          "application/json");
      return;
    }
    try {
      if (next != null && !next.isEmpty()) {
        saveEditorCredential(name, next);
      } else if (hasEditorHash()) {
        String stored = Files.readString(file, StandardCharsets.UTF_8);
        String withName = putJsonStringField(stored, "username", name);
        Path tmp = ROOT.resolve(EDITOR_CREDENTIAL_FILE + ".tmp");
        Files.write(tmp, withName.getBytes(StandardCharsets.UTF_8));
        try {
          Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
          Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
      } else {
        send(ex, 400, "{\"error\":\"No editor password is set yet — type one (12+ characters).\"}",
            "application/json");
        return;
      }
    } catch (Exception e) {
      send(ex, 500, "{\"error\":\"Could not save the editor account.\"}", "application/json");
      return;
    }
    SESSIONS.entrySet().removeIf(entry -> "editor".equals(entry.getValue().role));
    send(ex, 200, "{\"ok\":true,\"editorUsername\":" + quoted(editorUsername())
        + ",\"editorEnabled\":" + editorConfigured()
        + ",\"message\":\"Editor account updated. Editors must log in again.\"}",
        "application/json");
  }

  static void handleSettings(HttpExchange ex) throws IOException {
    String query = ex.getRequestURI().getRawQuery();
    String client = queryParam(query, "client");
    if (method(ex, "GET")) {
      try {
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        if (client != null) {
          if (!validClientSlug(client) || !Files.isRegularFile(clientSettingsFile(client))) {
            send(ex, 404, "{\"error\":\"unknown board\"}", "application/json; charset=utf-8");
            return;
          }
          String body = readClientSettings(client);
          boolean owner = clientOwner(session(ex), client);
          send(ex, 200, owner ? body : publicSettingsJson(body), "application/json; charset=utf-8");
          return;
        }
        String body = Files.readString(ROOT.resolve("settings.json"), StandardCharsets.UTF_8);
        send(ex, 200, publicSettingsJson(body), "application/json; charset=utf-8");
      } catch (IOException e) {
        send(ex, 500, "{\"error\":\"settings unavailable\"}", "application/json");
      }
      return;
    }
    if (method(ex, "POST")) {
      if (!requireApiAuth(ex)) return;
      Session session = session(ex);
      if (!csrfMatches(ex, session)) {
        send(ex, 403, "{\"error\":\"invalid csrf token\"}", "application/json");
        return;
      }
      if (session != null && "client".equals(session.role) && client == null) {
        send(ex, 403, "{\"error\":\"client accounts can only edit their own board\"}",
            "application/json");
        return;
      }
      if (client != null) {
        if (!validClientSlug(client) || !Files.isRegularFile(clientSettingsFile(client))) {
          send(ex, 404, "{\"error\":\"unknown board\"}", "application/json");
          return;
        }
        if (!clientOwner(session, client)) {
          send(ex, 403, "{\"error\":\"forbidden\"}", "application/json");
          return;
        }
      }
      String body = readBody(ex, 512 * 1024);
      if (body == null) { send(ex, 413, "{\"error\":\"too large\"}", "application/json"); return; }
      String t = body.trim();
      if (!t.startsWith("{") || !t.endsWith("}")) {
        send(ex, 400, "{\"error\":\"invalid json\"}", "application/json");
        return;
      }
      boolean privileged = (session != null && "admin".equals(session.role))
          || (client != null && session != null && "client".equals(session.role));
      boolean clearOutlookIcs = privileged && query != null && query.contains("clearOutlookIcs=true");
      String submittedUrl = jsonField(body, "outlookIcsUrl");
      if(!clearOutlookIcs && (submittedUrl == null || submittedUrl.isEmpty())){
        String currentUrl = client != null
            ? clientSettingsField(client, "outlookIcsUrl") : settingsField("outlookIcsUrl");
        if(!currentUrl.isEmpty()) body = putJsonStringField(body, "outlookIcsUrl", currentUrl);
      }
      String current = client != null ? readClientSettings(client) : readSettingsText();
      body = insertJsonFieldIfMissing(body, "rooms",
          firstPresent(jsonRawField(current, "rooms"), defaultJsonField("rooms")));
      body = insertJsonFieldIfMissing(body, "devices",
          firstPresent(jsonRawField(current, "devices"), defaultJsonField("devices")));
      body = insertJsonFieldIfMissing(body, "photoOrder", jsonRawField(current, "photoOrder"));
      body = insertJsonFieldIfMissing(body, "photoAnnouncements",
          jsonRawField(current, "photoAnnouncements"));
      if(!privileged){
        for (String key : new String[] {"slideSeconds", "weatherLat", "weatherLon", "orientation",
            "mainOutput", "outputDesign", "outlookIcsUrl"}) {
          body = insertJsonFieldIfMissing(removeJsonField(body, key), key, jsonRawField(current, key));
        }
      }
      String previousIcsUrl = client != null
          ? clientSettingsField(client, "outlookIcsUrl") : settingsField("outlookIcsUrl");
      Path file = client != null ? clientSettingsFile(client) : ROOT.resolve("settings.json");
      Path tmp = file.resolveSibling(file.getFileName().toString() + ".tmp");
      Files.write(tmp, body.getBytes(StandardCharsets.UTF_8));
      Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING,
          StandardCopyOption.ATOMIC_MOVE);
      String savedIcsUrl = client != null
          ? clientSettingsField(client, "outlookIcsUrl") : settingsField("outlookIcsUrl");
      if (!savedIcsUrl.equals(previousIcsUrl)) forgetCalendar(previousIcsUrl);
      send(ex, 200, "{\"ok\":true}", "application/json");
      return;
    }
    send(ex, 405, "Method not allowed", "text/plain");
  }

  static void handlePhotos(HttpExchange ex) throws IOException {
    if (!method(ex, "GET")) { send(ex, 405, "Method not allowed", "text/plain"); return; }
    String client = queryParam(ex.getRequestURI().getRawQuery(), "client");
    if (client != null && !validClientSlug(client)) {
      send(ex, 400, "{\"error\":\"invalid client\"}", "application/json");
      return;
    }
    StringBuilder sb = new StringBuilder("[");
    boolean first = true;
    for (String name : photoNames(client)) {
      if (!first) sb.append(",");
      sb.append("\"/photos/");
      if (client != null) sb.append(jsonEscape(client)).append("/");
      sb.append(jsonEscape(name)).append("\"");
      first = false;
    }
    sb.append("]");
    send(ex, 200, sb.toString(), "application/json");
  }

  static void handlePhotoFile(HttpExchange ex) throws IOException {
    if (!method(ex, "GET")) { send(ex, 405, "Method not allowed", "text/plain"); return; }
    String path = ex.getRequestURI().getPath();
    String name = path.startsWith("/photos/") ? path.substring("/photos/".length()) : "";
    Path file = ROOT.resolve("photos").resolve(name).normalize();
    if (name.isEmpty() || !file.startsWith(ROOT.resolve("photos"))
        || !Files.isRegularFile(file)) {
      send(ex, 404, "Not found", "text/plain");
      return;
    }
    sendFile(ex, file, contentType(name));
  }

  static void handleUpload(HttpExchange ex) throws IOException {
    if (!method(ex, "POST")) { send(ex, 405, "Method not allowed", "text/plain"); return; }
    if (!requireApiAuth(ex)) return;
    Session upSession = session(ex);
    if (!csrfMatches(ex, upSession)) {
      send(ex, 403, "{\"error\":\"invalid csrf token\"}", "application/json");
      return;
    }
    String client = queryParam(ex.getRequestURI().getRawQuery(), "client");
    if (upSession != null && "client".equals(upSession.role) && client == null) {
      send(ex, 403, "{\"error\":\"client accounts can only edit their own board\"}",
          "application/json");
      return;
    }
    if (client != null) {
      if (!validClientSlug(client) || !Files.isRegularFile(clientSettingsFile(client))) {
        send(ex, 404, "{\"error\":\"unknown board\"}", "application/json");
        return;
      }
      if (!clientOwner(upSession, client)) {
        send(ex, 403, "{\"error\":\"forbidden\"}", "application/json");
        return;
      }
    }
    Path photoDir = ROOT.resolve("photos");
    if (client != null) {
      photoDir = photoDir.resolve(client);
      try {
        Files.createDirectories(photoDir);
      } catch (IOException e) {
        send(ex, 500, "{\"error\":\"could not create photo folder\"}", "application/json");
        return;
      }
    }
    String body = readBody(ex, MAX_BODY);
    if (body == null) { send(ex, 413, "{\"error\":\"too large\"}", "application/json"); return; }
    String filename = sanitize(jsonField(body, "filename"));
    String data = jsonField(body, "data");
    if (filename == null || filename.isEmpty() || data == null) {
      send(ex, 400, "{\"error\":\"filename and data required\"}", "application/json");
      return;
    }
    if (!hasPhotoExt(filename)) {
      send(ex, 400, "{\"error\":\"file must be JPG, PNG, WEBP, GIF, MP4 or WEBM\"}", "application/json");
      return;
    }
    // strip data URL prefix if present ("data:image/png;base64,...." or "data:video/mp4;base64,....")
    int comma = data.indexOf(',');
    if (data.startsWith("data:") && comma > 0) {
      data = data.substring(comma + 1);
    }
    byte[] bytes;
    try {
      bytes = Base64.getDecoder().decode(data);
    } catch (IllegalArgumentException e) {
      send(ex, 400, "{\"error\":\"invalid base64\"}", "application/json");
      return;
    }
    Files.write(photoDir.resolve(filename), bytes);
    send(ex, 200, "{\"ok\":true,\"file\":" + quoted(filename) + "}", "application/json");
  }

  static void handleDelete(HttpExchange ex) throws IOException {
    if (!method(ex, "POST")) { send(ex, 405, "Method not allowed", "text/plain"); return; }
    if (!requireApiAuth(ex)) return;
    Session delSession = session(ex);
    if (!csrfMatches(ex, delSession)) {
      send(ex, 403, "{\"error\":\"invalid csrf token\"}", "application/json");
      return;
    }
    String client = queryParam(ex.getRequestURI().getRawQuery(), "client");
    if (delSession != null && "client".equals(delSession.role) && client == null) {
      send(ex, 403, "{\"error\":\"client accounts can only edit their own board\"}",
          "application/json");
      return;
    }
    if (client != null) {
      if (!validClientSlug(client) || !Files.isRegularFile(clientSettingsFile(client))) {
        send(ex, 404, "{\"error\":\"unknown board\"}", "application/json");
        return;
      }
      if (!clientOwner(delSession, client)) {
        send(ex, 403, "{\"error\":\"forbidden\"}", "application/json");
        return;
      }
    }
    String body = readBody(ex, 64 * 1024);
    if (body == null) { send(ex, 413, "{\"error\":\"too large\"}", "application/json"); return; }
    String filename = sanitize(jsonField(body, "filename"));
    Path base = client == null ? ROOT.resolve("photos") : ROOT.resolve("photos").resolve(client);
    Path file = base.resolve(filename == null ? "" : filename).normalize();
    if (filename == null || filename.isEmpty()
        || !file.startsWith(base) || !Files.isRegularFile(file)) {
      send(ex, 404, "{\"error\":\"photo not found\"}", "application/json");
      return;
    }
    Files.delete(file);
    send(ex, 200, "{\"ok\":true}", "application/json");
  }

  // ---------------- Outlook calendar (ICS) events ----------------

  static final ZoneId MANILA = ZoneId.of("Asia/Manila");
  static final long EVENTS_CACHE_MS = 60L * 1000;
  static final Map<String, IcsEntry> ICS_CACHE = new ConcurrentHashMap<>();

  static class IcsEntry {
    final String ics;
    final long at;
    IcsEntry(String ics, long at) {
      this.ics = ics;
      this.at = at;
    }
  }

  static final Map<String, String> WINDOWS_TZ = new HashMap<>();
  static {
    WINDOWS_TZ.put("Singapore Standard Time", "Asia/Singapore");
    WINDOWS_TZ.put("Taipei Standard Time", "Asia/Taipei");
    WINDOWS_TZ.put("China Standard Time", "Asia/Shanghai");
    WINDOWS_TZ.put("Tokyo Standard Time", "Asia/Tokyo");
    WINDOWS_TZ.put("Korea Standard Time", "Asia/Seoul");
    WINDOWS_TZ.put("India Standard Time", "Asia/Kolkata");
    WINDOWS_TZ.put("Arabian Standard Time", "Asia/Dubai");
    WINDOWS_TZ.put("UTC", "UTC");
    WINDOWS_TZ.put("GMT Standard Time", "Europe/London");
    WINDOWS_TZ.put("W. Europe Standard Time", "Europe/Berlin");
    WINDOWS_TZ.put("Central European Standard Time", "Europe/Berlin");
    WINDOWS_TZ.put("Eastern Standard Time", "America/New_York");
    WINDOWS_TZ.put("Central Standard Time", "America/Chicago");
    WINDOWS_TZ.put("Mountain Standard Time", "America/Denver");
    WINDOWS_TZ.put("Pacific Standard Time", "America/Los_Angeles");
    WINDOWS_TZ.put("Hawaiian Standard Time", "Pacific/Honolulu");
    WINDOWS_TZ.put("AUS Eastern Standard Time", "Australia/Sydney");
  }

  static void handleEvents(HttpExchange ex) throws IOException {
    if (!method(ex, "GET")) { send(ex, 405, "Method not allowed", "text/plain"); return; }
    String client = queryParam(ex.getRequestURI().getRawQuery(), "client");
    String url = "";
    if (client != null) {
      if (validClientSlug(client) && Files.isRegularFile(clientSettingsFile(client))) {
        url = clientSettingsField(client, "outlookIcsUrl");
      }
    } else {
      url = settingsField("outlookIcsUrl");
    }
    if (url.isEmpty()) { send(ex, 200, "[]", "application/json"); return; }
    String ics = cachedIcs(url);
    if (ics == null) { send(ex, 200, "[]", "application/json"); return; }
    send(ex, 200, eventsJson(ics), "application/json");
  }

  static void handleSampleEvents(HttpExchange ex) throws IOException {
    if (!method(ex, "GET")) { send(ex, 405, "Method not allowed", "text/plain"); return; }
    sendFile(ex, ROOT.resolve("sample-events.ics"), "text/calendar; charset=utf-8");
  }

  static String settingsField(String key) {
    String v = jsonField(readSettingsText(), key);
    return v == null ? "" : v.trim();
  }

  static String readSettingsText() {
    try {
      return Files.readString(ROOT.resolve("settings.json"), StandardCharsets.UTF_8);
    } catch (IOException e) {
      return "";
    }
  }

  static String defaultJsonField(String key) {
    try {
      return jsonRawField(Files.readString(ROOT.resolve("settings.default.json"),
          StandardCharsets.UTF_8), key);
    } catch (IOException e) {
      return null;
    }
  }

  // ---------------- per-client boards (/c/<name>) ----------------

  static final Set<String> RESERVED_CLIENT_NAMES = Set.of(
      "admin", "api", "c", "login", "logout", "editor", "room", "schedule", "slides",
      "device", "devices", "photos", "events", "session", "password", "settings",
      "clients", "signup", "server-info", "sample-events.ics", "event-board",
      "lhc-events-board", "dakboard");

  /** Lowercase slug: 3-32 chars, letters/digits/hyphens, not a reserved path. */
  static boolean validClientSlug(String name) {
    if (name == null || !name.matches("[a-z0-9][a-z0-9-]{1,30}[a-z0-9]")) return false;
    return !RESERVED_CLIENT_NAMES.contains(name);
  }

  static Path clientSettingsFile(String name) {
    if (!validClientSlug(name)) return ROOT.resolve("clients").resolve("__invalid__");
    return ROOT.resolve("clients").resolve(name + ".json");
  }

  static Path clientLoginFile(String name) {
    if (!validClientSlug(name)) return ROOT.resolve("clients").resolve("__invalid__");
    return ROOT.resolve("clients").resolve(name + ".login");
  }

  static boolean clientExists(String name) {
    return validClientSlug(name) && Files.isRegularFile(clientSettingsFile(name));
  }

  static String readClientSettings(String name) {
    if (!validClientSlug(name)) return "";
    try {
      return Files.readString(clientSettingsFile(name), StandardCharsets.UTF_8);
    } catch (IOException e) {
      return "";
    }
  }

  static String clientSettingsField(String name, String key) {
    String v = jsonField(readClientSettings(name), key);
    return v == null ? "" : v.trim();
  }

  /** True when the session may edit this client's board (platform admin, or its own client). */
  static boolean clientOwner(Session s, String client) {
    if (s == null || client == null) return false;
    if ("admin".equals(s.role)) return true;
    return "client".equals(s.role) && client.equals(s.client);
  }

  static String clientUsername(String client) {
    try {
      if (validClientSlug(client) && Files.isRegularFile(clientLoginFile(client))) {
        String stored = Files.readString(clientLoginFile(client), StandardCharsets.UTF_8);
        String name = jsonField(stored, "username");
        if (name != null && !name.trim().isEmpty()) return name.trim();
      }
    } catch (IOException ignored) {
      // fall through
    }
    return "";
  }

  static boolean clientCredentialsMatch(String client, String username, String password) {
    if (!validClientSlug(client) || password == null || password.isEmpty()) return false;
    if (!Files.isRegularFile(clientLoginFile(client))) return false;
    try {
      String stored = Files.readString(clientLoginFile(client), StandardCharsets.UTF_8);
      String user = jsonField(stored, "username");
      String expectedName = (user == null ? "" : user).trim().toLowerCase(Locale.ROOT);
      String actualName = (username == null ? "" : username.trim()).toLowerCase(Locale.ROOT);
      if (!MessageDigest.isEqual(expectedName.getBytes(StandardCharsets.UTF_8),
          actualName.getBytes(StandardCharsets.UTF_8))) return false;
      String salt = jsonField(stored, "salt");
      String hash = jsonField(stored, "hash");
      String iterations = jsonField(stored, "iterations");
      if (salt == null || hash == null || iterations == null) return false;
      byte[] expected = Base64.getDecoder().decode(hash);
      byte[] actual = derive(password, Base64.getDecoder().decode(salt),
          Integer.parseInt(iterations.trim()));
      return MessageDigest.isEqual(expected, actual);
    } catch (Exception e) {
      return false;
    }
  }

  /** Value of a query parameter (decoded), or null when absent. */
  static String queryParam(String query, String name) {
    if (query == null) return null;
    for (String part : query.split("&")) {
      int eq = part.indexOf('=');
      String key = eq < 0 ? part : part.substring(0, eq);
      if (name.equals(key)) {
        if (eq < 0) return "";
        try {
          return URLDecoder.decode(part.substring(eq + 1), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
          return "";
        }
      }
    }
    return null;
  }

  /** Client password change: POST /api/event-board/password?client=<name>. */
  static void handleChangeClientPassword(HttpExchange ex, String client) throws IOException {
    if (!clientExists(client)) {
      send(ex, 404, "{\"error\":\"unknown board\"}", "application/json");
      return;
    }
    Session session = session(ex);
    if (session == null) {
      send(ex, 401, "{\"error\":\"authentication required\"}", "application/json");
      return;
    }
    if (!clientOwner(session, client)) {
      send(ex, 403, "{\"error\":\"forbidden\"}", "application/json");
      return;
    }
    if (!csrfMatches(ex, session)) {
      send(ex, 403, "{\"error\":\"invalid csrf token\"}", "application/json");
      return;
    }
    String body = readBody(ex, 8 * 1024);
    if (body == null) { send(ex, 413, "{\"error\":\"too large\"}", "application/json"); return; }
    String current = jsonField(body, "currentPassword");
    String next = jsonField(body, "newPassword");
    boolean platformReset = session.role != null && "admin".equals(session.role);
    if (next == null || (current == null && !platformReset)) {
      send(ex, 400, "{\"error\":\"missing password field\"}", "application/json");
      return;
    }
    if (next.length() < MIN_PASSWORD_LENGTH) {
      send(ex, 400, "{\"error\":\"Password must be at least " + MIN_PASSWORD_LENGTH
          + " characters.\"}", "application/json");
      return;
    }
    String user = clientUsername(client);
    if (!platformReset && !clientCredentialsMatch(client, user, current)) {
      send(ex, 400, "{\"error\":\"Current password is incorrect.\"}", "application/json");
      return;
    }
    try {
      saveCredentialJson("clients/" + client + ".login", user, next);
    } catch (Exception e) {
      send(ex, 500, "{\"error\":\"Could not save the password.\"}", "application/json");
      return;
    }
    String keepToken = cookieValue(ex, SESSION_COOKIE);
    SESSIONS.entrySet().removeIf(entry ->
        !entry.getKey().equals(keepToken)
            && "client".equals(entry.getValue().role)
            && client.equals(entry.getValue().client));
    send(ex, 200, "{\"ok\":true,\"message\":\"Password updated.\"}", "application/json");
  }

  /**
   * /c sign-up landing page and client board pages:
   *   GET /c                      -> sign-up page
   *   GET /c/<name>               -> board (or schedule, per client mainOutput)
   *   GET /c/<name>/schedule|room|slides -> the matching TV output
   *   GET /c/<name>/admin         -> client's own admin page (requires client login)
   */
  static void handleClientPages(HttpExchange ex) throws IOException {
    if (!method(ex, "GET")) { send(ex, 405, "Method not allowed", "text/plain"); return; }
    String path = ex.getRequestURI().getPath();
    if (path.equals("/c") || path.equals("/c/")) {
      sendFile(ex, ROOT.resolve("clients.html"), "text/html; charset=utf-8");
      return;
    }
    if (!path.startsWith("/c/")) {
      redirect(ex, "/c");
      return;
    }
    String rest = path.substring(3);
    String name = rest;
    String output = null;
    int slash = rest.indexOf('/');
    if (slash >= 0) {
      name = rest.substring(0, slash);
      output = rest.substring(slash + 1);
      while (output.endsWith("/")) output = output.substring(0, output.length() - 1);
      if (output.isEmpty()) output = null;
    }
    if (!clientExists(name)) {
      sendClientNotFound(ex, name);
      return;
    }
    if (output == null) {
      if (hasQueryParam(ex.getRequestURI().getRawQuery(), "room")) {
        sendFile(ex, ROOT.resolve("room.html"), "text/html; charset=utf-8");
        return;
      }
      if ("schedule".equals(clientSettingsField(name, "mainOutput"))) {
        sendFile(ex, ROOT.resolve("schedule.html"), "text/html; charset=utf-8");
      } else {
        sendFile(ex, ROOT.resolve("board.html"), "text/html; charset=utf-8");
      }
      return;
    }
    switch (output) {
      case "schedule":
        sendFile(ex, ROOT.resolve("schedule.html"), "text/html; charset=utf-8");
        return;
      case "room":
        sendFile(ex, ROOT.resolve("room.html"), "text/html; charset=utf-8");
        return;
      case "slides":
        sendFile(ex, ROOT.resolve("slides.html"), "text/html; charset=utf-8");
        return;
      case "admin": {
        Session s = session(ex);
        if (s == null || !clientOwner(s, name)) {
          redirect(ex, "/event-board/login?next=/c/" + name + "/admin&client=" + name);
          return;
        }
        sendFile(ex, ROOT.resolve("admin.html"), "text/html; charset=utf-8");
        return;
      }
      case "device":
        handleDevice(ex, name);
        return;
      default:
        sendClientNotFound(ex, name);
        return;
    }
  }

  static void sendClientNotFound(HttpExchange ex, String name) throws IOException {
    String shown = name != null && name.matches("[A-Za-z0-9][A-Za-z0-9 _-]{1,31}")
        ? name : "that board";
    String page = "<!DOCTYPE html><html lang=\"en\"><head><meta charset=\"utf-8\">"
        + "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">"
        + "<title>Event Board \u2014 Board not found</title>"
        + "<style>body{font-family:Segoe UI,Arial,sans-serif;margin:0;min-height:100vh;"
        + "display:flex;align-items:center;justify-content:center;color:#173b63;"
        + "background:linear-gradient(135deg,#e9a36b 0%,#8fb69b 60%,#789fbd 100%)"
        + ".card{background:#fff4e2;border:2px solid #2f8f55;border-radius:22px;"
        + "padding:36px 40px;max-width:520px;margin:20px;text-align:center;"
        + "box-shadow:0 18px 60px rgba(23,59,99,.2)}"
        + "h1{font-size:26px;margin:0 0 12px;color:#123c62}p{font-size:15px;line-height:1.55;"
        + "color:#315b7d;margin:10px 0}a{display:inline-block;margin-top:16px;padding:12px 22px;"
        + "background:#1d5687;color:#fff8ed;text-decoration:none;border-radius:10px;"
        + "font-weight:800}</style></head><body><div class=\"card\">"
        + "<h1>Board not found</h1><p>There is no board at <b>/c/" + shown + "</b> yet.</p>"
        + "<p>Create your own board \u2014 it only takes a minute.</p>"
        + "<a href=\"/c\">Create a board</a></div></body></html>";
    send(ex, 404, page, "text/html; charset=utf-8");
  }

  /** POST /api/event-board/clients/signup {name, username, password, title} */
  static void handleClientSignup(HttpExchange ex) throws IOException {
    if (!method(ex, "POST")) { send(ex, 405, "Method not allowed", "text/plain"); return; }
    String body = readBody(ex, 16 * 1024);
    if (body == null) {
      send(ex, 413, "{\"error\":\"request too large\"}", "application/json");
      return;
    }
    String rawName = jsonField(body, "name");
    String name = rawName == null ? "" : rawName.trim().toLowerCase(Locale.ROOT);
    name = name.replaceAll("[^a-z0-9-]+", "-").replaceAll("-{2,}", "-");
    if (name.startsWith("-")) name = name.substring(1);
    while (name.endsWith("-")) name = name.substring(0, name.length() - 1);
    if (!validClientSlug(name)) {
      send(ex, 400, "{\"error\":\"Board name must be 3-32 characters: lowercase letters, "
          + "numbers, and hyphens.\",\"code\":\"invalid-name\"}", "application/json");
      return;
    }
    String username = jsonField(body, "username");
    username = username == null ? "" : username.trim();
    if (!validLoginName(username)) {
      send(ex, 400, "{\"error\":\"Username must be 3-32 characters: letters, numbers, dot, "
          + "underscore, or hyphen.\"}", "application/json");
      return;
    }
    String password = jsonField(body, "password");
    if (password == null || password.length() < MIN_PASSWORD_LENGTH) {
      send(ex, 400, "{\"error\":\"Password must be at least " + MIN_PASSWORD_LENGTH
          + " characters.\"}", "application/json");
      return;
    }
    String title = jsonField(body, "title");
    title = title == null ? "" : title.trim();
    if (title.length() > 60) title = title.substring(0, 60);
    if (clientExists(name) || Files.exists(clientLoginFile(name))) {
      send(ex, 409, "{\"error\":\"That board name is already taken.\"}", "application/json");
      return;
    }
    try {
      Files.createDirectories(ROOT.resolve("clients"));
      Files.createDirectories(ROOT.resolve("photos").resolve(
          validClientSlug(name) ? name : "__invalid__"));
      String settings = Files.readString(ROOT.resolve("settings.default.json"),
          StandardCharsets.UTF_8);
      if (!title.isEmpty()) settings = putJsonStringField(settings, "brandName", title);
      Path settingsPath = clientSettingsFile(name);
      try {
        Files.write(settingsPath, settings.getBytes(StandardCharsets.UTF_8),
            java.nio.file.StandardOpenOption.CREATE_NEW);
      } catch (java.nio.file.FileAlreadyExistsException taken) {
        send(ex, 409, "{\"error\":\"That board name is already taken.\"}", "application/json");
        return;
      }
      try {
        saveCredentialJson("clients/" + name + ".login", username, password);
      } catch (Exception e) {
        Files.deleteIfExists(settingsPath);
        send(ex, 500, "{\"error\":\"Could not create the board.\"}", "application/json");
        return;
      }
    } catch (IOException e) {
      send(ex, 500, "{\"error\":\"Could not create the board.\"}", "application/json");
      return;
    }
    send(ex, 200, "{\"ok\":true,\"name\":" + quoted(name) + "}",
        "application/json; charset=utf-8");
  }

  /** POST /api/event-board/clients/delete {name} — owner or platform admin only. */
  static void handleClientDelete(HttpExchange ex) throws IOException {
    if (!method(ex, "POST")) { send(ex, 405, "Method not allowed", "text/plain"); return; }
    if (!requireApiAuth(ex)) return;
    Session delSession = session(ex);
    if (!csrfMatches(ex, delSession)) {
      send(ex, 403, "{\"error\":\"invalid csrf token\"}", "application/json");
      return;
    }
    String body = readBody(ex, 16 * 1024);
    if (body == null) {
      send(ex, 413, "{\"error\":\"request too large\"}", "application/json");
      return;
    }
    String name = jsonField(body, "name");
    name = name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
    if (!validClientSlug(name)) {
      send(ex, 400, "{\"error\":\"invalid board name\"}", "application/json");
      return;
    }
    if (!clientOwner(delSession, name)) {
      send(ex, 403, "{\"error\":\"forbidden\"}", "application/json");
      return;
    }
    Path settingsFile = clientSettingsFile(name);
    Path loginFile = clientLoginFile(name);
    if (!Files.isRegularFile(settingsFile) && !Files.isRegularFile(loginFile)) {
      send(ex, 404, "{\"error\":\"board not found\"}", "application/json");
      return;
    }
    try {
      Files.deleteIfExists(settingsFile);
      Files.deleteIfExists(loginFile);
      Path photos = ROOT.resolve("photos").resolve(name);
      if (Files.isDirectory(photos)) {
        try (Stream<Path> walk = Files.walk(photos)) {
          walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
            try { Files.deleteIfExists(p); } catch (IOException ignored) { }
          });
        }
      }
    } catch (IOException e) {
      send(ex, 500, "{\"error\":\"could not delete the board\"}", "application/json");
      return;
    }
    final String deletedName = name;
    SESSIONS.entrySet().removeIf(entry -> entry.getValue() != null
        && deletedName.equals(entry.getValue().client));
    send(ex, 200, "{\"ok\":true}", "application/json");
  }

  /** GET /api/event-board/clients — platform admin: every board (account) created. */
  static void handleClientList(HttpExchange ex) throws IOException {
    if (!method(ex, "GET")) { send(ex, 405, "Method not allowed", "text/plain"); return; }
    if (!"/api/event-board/clients".equals(ex.getRequestURI().getPath())) {
      send(ex, 404, "{\"error\":\"not found\"}", "application/json");
      return;
    }
    if (!requireAdmin(ex)) return;
    Set<String> names = new HashSet<>();
    Path dir = ROOT.resolve("clients");
    if (Files.isDirectory(dir)) {
      try (Stream<Path> files = Files.list(dir)) {
        files.forEach(p -> {
          String fn = p.getFileName().toString();
          if (fn.endsWith(".json")) names.add(fn.substring(0, fn.length() - 5));
          else if (fn.endsWith(".login")) names.add(fn.substring(0, fn.length() - 6));
        });
      } catch (IOException ignored) {
        // fall through with whatever was collected
      }
    }
    List<String> rows = new ArrayList<>();
    for (String name : names) {
      if (!validClientSlug(name)) continue;
      long created = 0L;
      Path settings = clientSettingsFile(name);
      try {
        if (Files.isRegularFile(settings)) created = Files.getLastModifiedTime(settings).toMillis();
      } catch (IOException ignored) { }
      rows.add("{\"name\":" + quoted(name)
          + ",\"title\":" + quoted(clientSettingsField(name, "brandName"))
          + ",\"username\":" + quoted(clientUsername(name))
          + ",\"created\":" + created + "}");
    }
    ex.getResponseHeaders().set("Cache-Control", "no-store");
    send(ex, 200, "[" + String.join(",", rows) + "]", "application/json; charset=utf-8");
  }

  static String firstPresent(String a, String b) {
    return a == null || a.isBlank() ? b : a;
  }

  /** Drops cached events of a calendar URL that was replaced, so its old events stop showing. */
  static void forgetCalendar(String url) {
    if (url != null && !url.isEmpty()) ICS_CACHE.remove(url);
    try {
      Files.deleteIfExists(ROOT.resolve("events.cache.json"));
    } catch (IOException ignored) {
      // best-effort; cached events are only used for the same URL anyway
    }
  }

  static String cachedIcs(String url) {
    long now = System.currentTimeMillis();
    IcsEntry hit = ICS_CACHE.get(url);
    if (hit != null && hit.ics != null && now - hit.at < EVENTS_CACHE_MS) {
      return hit.ics;
    }
    String fresh = fetchIcs(url);
    if (fresh != null) {
      ICS_CACHE.put(url, new IcsEntry(fresh, now));
      return fresh;
    }
    if (hit != null && hit.ics != null) return hit.ics;
    try {
      Path c = ROOT.resolve("events.cache.json");
      if (Files.isRegularFile(c)) {
        String saved = Files.readString(c, StandardCharsets.UTF_8);
        if (url.equals(jsonField(saved, "url"))) return jsonField(saved, "ics");
      }
    } catch (IOException ignored) {
      // fall through
    }
    return null;
  }

  static String fetchIcs(String url) {
    try {
      HttpClient client = HttpClient.newBuilder()
          .connectTimeout(Duration.ofSeconds(15))
          .followRedirects(HttpClient.Redirect.NEVER).build();
      URI target = URI.create(url);
      for (int hop = 0; hop <= 5; hop++) {
        if (!publicHttpUri(target)) return null;
        HttpRequest req = HttpRequest.newBuilder(target)
            .timeout(Duration.ofSeconds(20))
            .header("User-Agent", "Event-Board/1.0").GET().build();
        HttpResponse<byte[]> res = client.send(req, HttpResponse.BodyHandlers.ofByteArray());
        int status = res.statusCode();
        if (status >= 300 && status < 400) {
          String location = res.headers().firstValue("Location").orElse(null);
          if (location == null) return null;
          target = target.resolve(location);
          continue;
        }
        if (status < 200 || status >= 300) return null;
        byte[] b = res.body();
        if (b.length > 5 * 1024 * 1024) return null;
        String ics = new String(b, StandardCharsets.UTF_8);
        try {
          Files.writeString(ROOT.resolve("events.cache.json"),
              "{\"url\":" + quoted(url) + ",\"ics\":" + quoted(ics) + "}",
              StandardCharsets.UTF_8);
        } catch (IOException ignored) {
          // cache is best-effort
        }
        return ics;
      }
      return null;
    } catch (Exception e) {
      return null;
    }
  }

  /** Allows only http(s) URLs whose host resolves to public addresses (no loopback/LAN/link-local). */
  static boolean publicHttpUri(URI uri) {
    String scheme = uri.getScheme();
    if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) return false;
    String host = uri.getHost();
    if (host == null || host.isEmpty()) return false;
    try {
      for (InetAddress addr : InetAddress.getAllByName(host)) {
        if (addr.isLoopbackAddress() || addr.isSiteLocalAddress() || addr.isLinkLocalAddress()
            || addr.isAnyLocalAddress() || addr.isMulticastAddress()) return false;
        byte[] raw = addr.getAddress();
        if (raw.length == 16 && (raw[0] & 0xfe) == 0xfc) return false;
      }
      return true;
    } catch (Exception e) {
      return false;
    }
  }

  // ----- minimal ICS model -----

  static class Prop {
    String name, params, value;
    Prop(String n, String p, String v) { name = n; params = p; value = v; }
  }

  static class Ev {
    ZonedDateTime start, end;
    String title, location;
    boolean allDay;
    Ev(ZonedDateTime s, ZonedDateTime e, String t, String l, boolean a) {
      start = s; end = e; title = t; location = l; allDay = a;
    }
  }

  static Prop findProp(List<Prop> ev, String name) {
    for (Prop p : ev) {
      if (p.name.equalsIgnoreCase(name)) return p;
    }
    return null;
  }

  static String paramVal(String params, String key) {
    for (String part : params.split(";")) {
      int eq = part.indexOf('=');
      if (eq > 0 && part.substring(0, eq).trim().equalsIgnoreCase(key)) {
        String v = part.substring(eq + 1).trim();
        if (v.length() >= 2 && v.startsWith("\"") && v.endsWith("\"")) {
          v = v.substring(1, v.length() - 1);
        }
        return v;
      }
    }
    return null;
  }

  static String unescapeText(String s) {
    return s.replace("\\N", "\n").replace("\\n", "\n")
        .replace("\\,", ",").replace("\\;", ";").replace("\\\\", "\\");
  }

  static ZoneId zoneOf(String id) {
    if (id == null || id.isEmpty()) return MANILA;
    try {
      return ZoneId.of(id);
    } catch (Exception e) {
      String w = WINDOWS_TZ.get(id);
      if (w != null) {
        try {
          return ZoneId.of(w);
        } catch (Exception e2) {
          // fall through
        }
      }
      return MANILA;
    }
  }

  /** Parses DTSTART/DTEND/UNTIL values into Manila time. */
  static ZonedDateTime parseWhen(String value, String params, boolean[] allDayOut) {
    String v = value.trim();
    boolean isDate = (params != null && params.contains("VALUE=DATE"))
        || v.matches("\\d{8}");
    if (isDate) {
      allDayOut[0] = true;
      LocalDate d = LocalDate.parse(v.substring(0, 8), DateTimeFormatter.BASIC_ISO_DATE);
      return d.atStartOfDay(MANILA);
    }
    boolean utc = v.endsWith("Z") || v.endsWith("z");
    String core = utc ? v.substring(0, v.length() - 1) : v;
    LocalDateTime ldt = LocalDateTime.parse(core,
        DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss"));
    if (utc) return ldt.atZone(ZoneOffset.UTC).withZoneSameInstant(MANILA);
    String tz = params == null ? null : paramVal(params, "TZID");
    return ldt.atZone(zoneOf(tz));
  }

  static Map<String, String> parseRule(String rrule) {
    Map<String, String> m = new HashMap<>();
    for (String part : rrule.split(";")) {
      int eq = part.indexOf('=');
      if (eq > 0) m.put(part.substring(0, eq).trim().toUpperCase(),
          part.substring(eq + 1).trim());
    }
    return m;
  }

  static int intOf(String s, int def) {
    try {
      return Integer.parseInt(s);
    } catch (Exception e) {
      return def;
    }
  }

  static Set<DayOfWeek> parseByDay(String byday) {
    Set<DayOfWeek> days = new HashSet<>();
    if (byday == null) return days;
    for (String d : byday.split(",")) {
      switch (d.trim().toUpperCase()) {
        case "MO": days.add(DayOfWeek.MONDAY); break;
        case "TU": days.add(DayOfWeek.TUESDAY); break;
        case "WE": days.add(DayOfWeek.WEDNESDAY); break;
        case "TH": days.add(DayOfWeek.THURSDAY); break;
        case "FR": days.add(DayOfWeek.FRIDAY); break;
        case "SA": days.add(DayOfWeek.SATURDAY); break;
        case "SU": days.add(DayOfWeek.SUNDAY); break;
        default: break;
      }
    }
    return days;
  }

  static void addIfUpcoming(List<Ev> out, ZonedDateTime now,
      ZonedDateTime todayStart, ZonedDateTime horizon, String title,
      String location, boolean allDay, ZonedDateTime s, ZonedDateTime e) {
    if (out.size() >= 200) return;
    if (e.isBefore(now) || s.isAfter(horizon)) return;
    ZonedDateTime showStart = s.isBefore(todayStart) ? todayStart : s;
    out.add(new Ev(showStart, e, title, location, allDay));
  }

  static void expandEvent(List<Prop> ev, List<Ev> out,
      ZonedDateTime now, ZonedDateTime todayStart, ZonedDateTime horizon) {
    Prop status = findProp(ev, "STATUS");
    if (status != null && "CANCELLED".equalsIgnoreCase(status.value.trim())) return;
    Prop ds = findProp(ev, "DTSTART");
    if (ds == null) return;
    boolean[] ad = new boolean[1];
    ZonedDateTime s;
    try {
      s = parseWhen(ds.value, ds.params, ad);
    } catch (Exception e) {
      return;
    }
    Duration dur;
    Prop de = findProp(ev, "DTEND");
    if (de != null) {
      try {
        boolean[] ad2 = new boolean[1];
        ZonedDateTime en = parseWhen(de.value, de.params, ad2);
        dur = Duration.between(s, en);
        if (dur.isNegative() || dur.isZero()) {
          dur = ad[0] ? Duration.ofDays(1) : Duration.ofHours(1);
        }
      } catch (Exception e) {
        dur = ad[0] ? Duration.ofDays(1) : Duration.ofHours(1);
      }
    } else {
      dur = ad[0] ? Duration.ofDays(1) : Duration.ofHours(1);
    }
    Prop sm = findProp(ev, "SUMMARY");
    Prop lc = findProp(ev, "LOCATION");
    String title = sm == null ? "(No title)" : unescapeText(sm.value).trim();
    String location = lc == null ? "" : unescapeText(lc.value).trim();
    if (title.isEmpty()) title = "(No title)";

    Prop rr = findProp(ev, "RRULE");
    if (rr == null) {
      addIfUpcoming(out, now, todayStart, horizon, title, location, ad[0], s, s.plus(dur));
      return;
    }
    Map<String, String> rule = parseRule(rr.value);
    String freq = rule.getOrDefault("FREQ", "");
    int interval = Math.max(1, intOf(rule.get("INTERVAL"), 1));
    int count = rule.containsKey("COUNT") ? Math.max(1, intOf(rule.get("COUNT"), 1))
        : Integer.MAX_VALUE;
    ZonedDateTime until = null;
    if (rule.containsKey("UNTIL")) {
      try {
        until = parseWhen(rule.get("UNTIL"), "", new boolean[1]);
      } catch (Exception e) {
        until = null;
      }
    }
    int made = 0;
    if (freq.equals("DAILY")) {
      ZonedDateTime d = s;
      while (made < count) {
        if (until != null && d.isAfter(until)) break;
        if (d.isAfter(horizon)) break;
        made++;
        addIfUpcoming(out, now, todayStart, horizon, title, location, ad[0], d, d.plus(dur));
        d = d.plusDays(interval);
      }
    } else if (freq.equals("WEEKLY")) {
      Set<DayOfWeek> days = parseByDay(rule.get("BYDAY"));
      if (days.isEmpty()) days.add(s.getDayOfWeek());
      LocalDate anchor = s.toLocalDate().with(DayOfWeek.MONDAY);
      LocalDate d = s.toLocalDate();
      while (made < count) {
        ZonedDateTime occ = d.atTime(s.toLocalTime()).atZone(s.getZone());
        if (occ.isBefore(s)) {
          d = d.plusDays(1);
          continue;
        }
        if (until != null && occ.isAfter(until)) break;
        if (occ.isAfter(horizon)) break;
        long weeks = ChronoUnit.WEEKS.between(anchor, d.with(DayOfWeek.MONDAY));
        if (days.contains(d.getDayOfWeek()) && weeks % interval == 0) {
          made++;
          addIfUpcoming(out, now, todayStart, horizon, title, location, ad[0], occ, occ.plus(dur));
        }
        d = d.plusDays(1);
      }
    } else {
      // MONTHLY/YEARLY not expanded in v1: show the master occurrence only
      addIfUpcoming(out, now, todayStart, horizon, title, location, ad[0], s, s.plus(dur));
    }
  }

  static String eventsJson(String ics) {
    List<String> lines = new ArrayList<>();
    for (String raw : ics.split("\r\n|\r|\n")) {
      if ((raw.startsWith(" ") || raw.startsWith("\t")) && !lines.isEmpty()) {
        lines.set(lines.size() - 1, lines.get(lines.size() - 1) + raw.substring(1));
      } else {
        lines.add(raw);
      }
    }
    List<Ev> out = new ArrayList<>();
    ZonedDateTime now = ZonedDateTime.now(MANILA);
    ZonedDateTime todayStart = now.toLocalDate().atStartOfDay(MANILA);
    ZonedDateTime horizon = now.plusDays(60);
    List<Prop> cur = null;
    for (String line : lines) {
      if (line.equals("BEGIN:VEVENT")) {
        cur = new ArrayList<>();
      } else if (line.equals("END:VEVENT")) {
        if (cur != null) expandEvent(cur, out, now, todayStart, horizon);
        cur = null;
      } else if (cur != null) {
        int ci = line.indexOf(':');
        if (ci < 0) continue;
        String left = line.substring(0, ci);
        String value = line.substring(ci + 1);
        int sc = left.indexOf(';');
        String name = sc < 0 ? left : left.substring(0, sc);
        String params = sc < 0 ? "" : left.substring(sc + 1);
        cur.add(new Prop(name, params, value));
      }
    }
    out.sort((a, b) -> a.start.compareTo(b.start));
    DateTimeFormatter dayFmt = DateTimeFormatter.ofPattern("EEE, MMM d", Locale.ENGLISH);
    DateTimeFormatter timeFmt = DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH);
    StringBuilder sb = new StringBuilder("[");
    int n = 0;
    for (Ev e : out) {
      if (n >= 200) break;
      if (n > 0) sb.append(",");
      sb.append("{\"date\":").append(quoted(e.start.toLocalDate().toString()));
      sb.append(",\"day\":").append(quoted(e.start.format(dayFmt)));
      sb.append(",\"time\":").append(quoted(e.allDay ? "All day" : e.start.format(timeFmt)));
      sb.append(",\"title\":").append(quoted(e.title));
      sb.append(",\"location\":").append(quoted(e.location));
      sb.append(",\"allDay\":").append(e.allDay ? "true" : "false");
      sb.append(",\"start\":").append(quoted(e.start.toOffsetDateTime().toString()));
      sb.append(",\"end\":").append(quoted(e.end.toOffsetDateTime().toString()));
      sb.append("}");
      n++;
    }
    sb.append("]");
    return sb.toString();
  }

  // ---------------- helpers ----------------

  static String envOrDefault(String key, String fallback) {
    String value = System.getenv(key);
    return value == null || value.trim().isEmpty() ? fallback : value.trim();
  }

  static String adminUsername() {
    return envOrDefault("DAKBOARD_ADMIN_USERNAME", "admin");
  }

  static String editorUsername() {
    try {
      String stored = Files.readString(ROOT.resolve(EDITOR_CREDENTIAL_FILE), StandardCharsets.UTF_8);
      String name = jsonField(stored, "username");
      if (name != null && !name.trim().isEmpty()) return name.trim();
    } catch (IOException ignored) {
    }
    return envOrDefault("DAKBOARD_EDITOR_USERNAME", "editor");
  }

  static boolean authConfigured() {
    if (Files.isRegularFile(ROOT.resolve(CREDENTIAL_FILE))) return true;
    String password = System.getenv("DAKBOARD_ADMIN_PASSWORD");
    return password != null && password.length() >= MIN_PASSWORD_LENGTH;
  }

  static boolean editorConfigured() {
    try {
      String stored = Files.readString(ROOT.resolve(EDITOR_CREDENTIAL_FILE), StandardCharsets.UTF_8);
      if (jsonField(stored, "hash") != null) return true;
    } catch (IOException ignored) {
    }
    String password = System.getenv("DAKBOARD_EDITOR_PASSWORD");
    return password != null && password.length() >= MIN_PASSWORD_LENGTH;
  }

  static boolean credentialsMatch(String username, String password) {
    byte[] expectedUser = adminUsername().getBytes(StandardCharsets.UTF_8);
    byte[] actualUser = (username == null ? "" : username).getBytes(StandardCharsets.UTF_8);
    if (!MessageDigest.isEqual(expectedUser, actualUser)) return false;
    return passwordMatches(password == null ? "" : password);
  }

  static boolean editorCredentialsMatch(String username, String password) {
    byte[] expectedUser = editorUsername().getBytes(StandardCharsets.UTF_8);
    byte[] actualUser = (username == null ? "" : username).getBytes(StandardCharsets.UTF_8);
    if (!MessageDigest.isEqual(expectedUser, actualUser)) return false;
    return credentialMatches(ROOT.resolve(EDITOR_CREDENTIAL_FILE),
        "DAKBOARD_EDITOR_PASSWORD", password == null ? "" : password);
  }

  static boolean passwordMatches(String password) {
    return credentialMatches(ROOT.resolve(CREDENTIAL_FILE),
        "DAKBOARD_ADMIN_PASSWORD", password);
  }

  static boolean credentialMatches(Path file, String envVar, String password) {
    if (password == null || password.isEmpty()) return false;
    if (Files.isRegularFile(file)) {
      try {
        String stored = Files.readString(file, StandardCharsets.UTF_8);
        String salt = jsonField(stored, "salt");
        String hash = jsonField(stored, "hash");
        String iterations = jsonField(stored, "iterations");
        if (salt != null && hash != null && iterations != null) {
          byte[] expected = Base64.getDecoder().decode(hash);
          byte[] actual = derive(password, Base64.getDecoder().decode(salt),
              Integer.parseInt(iterations.trim()));
          return MessageDigest.isEqual(expected, actual);
        }
      } catch (Exception ignored) {
      }
    }
    String envPassword = System.getenv(envVar);
    if (envPassword == null || envPassword.isEmpty()) return false;
    return MessageDigest.isEqual(envPassword.getBytes(StandardCharsets.UTF_8),
        password.getBytes(StandardCharsets.UTF_8));
  }

  static byte[] derive(String password, byte[] salt, int iterations) throws Exception {
    PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, iterations, HASH_BITS);
    try {
      return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
    } finally {
      spec.clearPassword();
    }
  }

  static boolean savePassword(String password) throws Exception {
    return saveCredential(CREDENTIAL_FILE, password);
  }

  static boolean validLoginName(String name) {
    return name != null && name.matches("[A-Za-z0-9._-]{3,32}");
  }

  static boolean hasEditorHash() {
    try {
      return jsonField(Files.readString(ROOT.resolve(EDITOR_CREDENTIAL_FILE),
          StandardCharsets.UTF_8), "hash") != null;
    } catch (IOException e) {
      return false;
    }
  }

  static boolean saveEditorCredential(String username, String password) throws Exception {
    return saveCredentialJson(EDITOR_CREDENTIAL_FILE, username, password);
  }

  /** Writes {"username":...,"salt":...,"hash":...} JSON credentials to a file under ROOT. */
  static boolean saveCredentialJson(String fileName, String username, String password)
      throws Exception {
    StringBuilder json = new StringBuilder("{\"username\":")
        .append(quoted(username));
    if (password != null && !password.isEmpty()) {
      byte[] salt = new byte[16];
      AUTH_RANDOM.nextBytes(salt);
      byte[] hash = derive(password, salt, HASH_ITERATIONS);
      json.append(",\"algo\":\"PBKDF2WithHmacSHA256\",\"iterations\":").append(HASH_ITERATIONS)
          .append(",\"salt\":").append(quoted(Base64.getEncoder().encodeToString(salt)))
          .append(",\"hash\":").append(quoted(Base64.getEncoder().encodeToString(hash)));
    }
    json.append(",\"updated\":")
        .append(quoted(LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)))
        .append("}");
    Path target = ROOT.resolve(fileName);
    Path tmp = ROOT.resolve(fileName + ".tmp");
    Files.write(tmp, json.toString().getBytes(StandardCharsets.UTF_8));
    try {
      Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    } catch (IOException e) {
      Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
    }
    return true;
  }

  static boolean saveCredential(String fileName, String password) throws Exception {
    byte[] salt = new byte[16];
    AUTH_RANDOM.nextBytes(salt);
    byte[] hash = derive(password, salt, HASH_ITERATIONS);
    String json = "{\"algo\":\"PBKDF2WithHmacSHA256\",\"iterations\":" + HASH_ITERATIONS
        + ",\"salt\":" + quoted(Base64.getEncoder().encodeToString(salt))
        + ",\"hash\":" + quoted(Base64.getEncoder().encodeToString(hash))
        + ",\"updated\":" + quoted(LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME))
        + "}";
    Path target = ROOT.resolve(fileName);
    Path tmp = ROOT.resolve(fileName + ".tmp");
    Files.write(tmp, json.getBytes(StandardCharsets.UTF_8));
    try {
      Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    } catch (IOException e) {
      Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
    }
    return true;
  }

  static String randomToken() {
    byte[] bytes = new byte[32];
    AUTH_RANDOM.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  static String cookieValue(HttpExchange ex, String name) {
    List<String> values = ex.getRequestHeaders().get("Cookie");
    if (values == null) return null;
    for (String value : values) {
      for (String part : value.split(";")) {
        String item = part.trim();
        int eq = item.indexOf('=');
        if (eq > 0 && name.equals(item.substring(0, eq))) {
          return item.substring(eq + 1);
        }
      }
    }
    return null;
  }

  static Session session(HttpExchange ex) {
    String token = cookieValue(ex, SESSION_COOKIE);
    if (token == null) return null;
    Session session = SESSIONS.get(token);
    if (session == null) return null;
    if (session.expiresAt <= System.currentTimeMillis()) {
      SESSIONS.remove(token, session);
      return null;
    }
    return session;
  }

  static boolean requireApiAuth(HttpExchange ex) throws IOException {
    if (session(ex) != null) return true;
    send(ex, 401, "{\"error\":\"authentication required\"}", "application/json");
    return false;
  }

  static boolean requireAdmin(HttpExchange ex) throws IOException {
    Session session = session(ex);
    if (session == null) {
      send(ex, 401, "{\"error\":\"authentication required\"}", "application/json");
      return false;
    }
    if (!"admin".equals(session.role)) {
      send(ex, 403, "{\"error\":\"admin access required\"}", "application/json");
      return false;
    }
    return true;
  }

  static boolean csrfMatches(HttpExchange ex, Session session) {
    if (session == null) return false;
    String supplied = ex.getRequestHeaders().getFirst("X-CSRF-Token");
    return supplied != null && MessageDigest.isEqual(
        session.csrf.getBytes(StandardCharsets.UTF_8),
        supplied.getBytes(StandardCharsets.UTF_8));
  }

  static void cleanupSessions() {
    long now = System.currentTimeMillis();
    SESSIONS.entrySet().removeIf(entry -> entry.getValue().expiresAt <= now);
  }

  static boolean secureRequest(HttpExchange ex) {
    return "https".equalsIgnoreCase(ex.getRequestHeaders().getFirst("X-Forwarded-Proto"))
        || "https".equalsIgnoreCase(ex.getRequestHeaders().getFirst("X-Forwarded-Scheme"));
  }

  static void setSessionCookie(HttpExchange ex, String token, boolean clear) {
    StringBuilder cookie = new StringBuilder(SESSION_COOKIE).append("=");
    if (!clear) cookie.append(token);
    cookie.append("; Path=/; Max-Age=").append(clear ? 0 : SESSION_TTL_SECONDS);
    cookie.append("; HttpOnly; SameSite=Strict");
    if (secureRequest(ex)) cookie.append("; Secure");
    ex.getResponseHeaders().add("Set-Cookie", cookie.toString());
  }

  static String safeNext(String value) {
    if (value == null || value.contains("\r") || value.contains("\n")
        || value.startsWith("//")) {
      return "/event-board/admin";
    }
    if (value.startsWith("/event-board") || value.startsWith("/c/")) {
      return value;
    }
    return "/event-board/admin";
  }

  static Map<String, String> parseForm(String body) {
    Map<String, String> form = new HashMap<>();
    for (String part : body.split("&")) {
      String[] pair = part.split("=", 2);
      try {
        String key = URLDecoder.decode(pair[0], StandardCharsets.UTF_8);
        String value = pair.length > 1
            ? URLDecoder.decode(pair[1], StandardCharsets.UTF_8) : "";
        form.put(key, value);
      } catch (IllegalArgumentException ignored) {
      }
    }
    return form;
  }

  static String publicSettingsJson(String body) {
    return removeJsonField(body, "outlookIcsUrl");
  }

  /** Returns the raw text of a top-level JSON value, or null when absent. */
  static String jsonRawField(String json, String key) {
    if (json == null || json.isEmpty()) return null;
    String needle = "\"" + key + "\"";
    int depth = 0;
    boolean quoted = false;
    boolean escaped = false;
    for (int i = 0; i < json.length(); i++) {
      char c = json.charAt(i);
      if (quoted) {
        if (escaped) escaped = false;
        else if (c == '\\') escaped = true;
        else if (c == '"') quoted = false;
        continue;
      }
      if (c == '"') {
        if (depth == 1 && json.startsWith(needle, i)) {
          int colon = i + needle.length();
          while (colon < json.length() && Character.isWhitespace(json.charAt(colon))) colon++;
          if (colon >= json.length() || json.charAt(colon) != ':') continue;
          return scanJsonValue(json, colon + 1);
        }
        quoted = true;
        continue;
      }
      if (c == '{' || c == '[') depth++;
      else if (c == '}' || c == ']') depth--;
    }
    return null;
  }

  static String scanJsonValue(String json, int start) {
    int i = start;
    while (i < json.length() && Character.isWhitespace(json.charAt(i))) i++;
    if (i >= json.length()) return null;
    char c = json.charAt(i);
    if (c == '"') {
      i++;
      boolean escaped = false;
      while (i < json.length()) {
        char ch = json.charAt(i++);
        if (escaped) escaped = false;
        else if (ch == '\\') escaped = true;
        else if (ch == '"') return json.substring(start, i);
      }
      return null;
    }
    if (c == '{' || c == '[') {
      int depth = 0;
      boolean q = false;
      boolean esc = false;
      while (i < json.length()) {
        char ch = json.charAt(i++);
        if (q) {
          if (esc) esc = false;
          else if (ch == '\\') esc = true;
          else if (ch == '"') q = false;
          continue;
        }
        if (ch == '"') q = true;
        else if (ch == '{' || ch == '[') depth++;
        else if (ch == '}' || ch == ']') {
          depth--;
          if (depth == 0) return json.substring(start, i);
        }
      }
      return null;
    }
    int end = i;
    while (end < json.length() && ",}] \t\r\n".indexOf(json.charAt(end)) < 0) end++;
    return json.substring(start, end);
  }

  /** Adds a top-level field only when it is missing, so stale clients cannot drop data. */
  static String insertJsonFieldIfMissing(String json, String key, String raw) {
    if (raw == null || raw.isBlank()) return json;
    if (jsonRawField(json, key) != null) return json;
    int end = json.lastIndexOf('}');
    if (end < 0) return json;
    String prefix = json.substring(0, end).trim();
    String separator = prefix.endsWith("{") || prefix.endsWith(",") ? "" : ",";
    return prefix + separator + "\"" + key + "\":" + raw.trim() + json.substring(end);
  }

  static String removeJsonField(String json, String key) {
    String needle = "\"" + key + "\"";
    int depth = 0;
    boolean quoted = false;
    boolean escaped = false;
    for (int i = 0; i < json.length(); i++) {
      char c = json.charAt(i);
      if (quoted) {
        if (escaped) escaped = false;
        else if (c == '\\') escaped = true;
        else if (c == '"') quoted = false;
        continue;
      }
      if (c == '"') {
        if (depth == 1 && json.startsWith(needle, i)) {
          int colon = i + needle.length();
          while (colon < json.length() && Character.isWhitespace(json.charAt(colon))) colon++;
          if (colon >= json.length() || json.charAt(colon) != ':') continue;
          colon++;
          while (colon < json.length() && Character.isWhitespace(json.charAt(colon))) colon++;
          if (colon >= json.length()) continue;
          String rawValue = scanJsonValue(json, colon);
          if (rawValue == null) continue;
          int end = colon + rawValue.length();
          int start = i;
          int removeEnd = end;
          while (removeEnd < json.length() && Character.isWhitespace(json.charAt(removeEnd))) removeEnd++;
          if (removeEnd < json.length() && json.charAt(removeEnd) == ',') {
            removeEnd++;
          } else {
            while (start > 0 && Character.isWhitespace(json.charAt(start - 1))) start--;
            if (start > 0 && json.charAt(start - 1) == ',') start--;
          }
          return json.substring(0, start) + json.substring(removeEnd);
        }
        quoted = true;
        continue;
      }
      if (c == '{' || c == '[') depth++;
      else if (c == '}' || c == ']') depth--;
    }
    return json;
  }

  static boolean method(HttpExchange ex, String m) {
    return m.equalsIgnoreCase(ex.getRequestMethod());
  }

  static void redirect(HttpExchange ex, String location) throws IOException {
    ex.getResponseHeaders().set("Location", location);
    ex.sendResponseHeaders(302, -1);
    ex.close();
  }

  static void handleServerInfo(HttpExchange ex) throws IOException {
    if (!method(ex, "GET")) {
      send(ex, 405, "Method not allowed", "text/plain");
      return;
    }
    String json = "{\"lanIp\":\"" + primaryLanAddress() + "\",\"port\":" + SERVER_PORT + "}";
    send(ex, 200, json, "application/json; charset=utf-8");
  }

  static String primaryLanAddress() {
    String fallback = "";
    try {
      for (NetworkInterface nif : Collections.list(NetworkInterface.getNetworkInterfaces())) {
        if (!nif.isUp() || nif.isLoopback()) continue;
        for (InterfaceAddress ia : nif.getInterfaceAddresses()) {
          InetAddress addr = ia.getAddress();
          if (!(addr instanceof Inet4Address)) continue;
          if (addr.isLoopbackAddress() || addr.isLinkLocalAddress() || addr.isAnyLocalAddress()) continue;
          byte[] b = addr.getAddress();
          int a0 = b[0] & 0xff;
          int a1 = b[1] & 0xff;
          int a2 = b[2] & 0xff;
          if (a0 == 100 && a1 >= 64 && a1 <= 127) continue;
          boolean privateRange = a0 == 10 || (a0 == 192 && a1 == 168)
              || (a0 == 172 && a1 >= 16 && a1 <= 31);
          boolean icsHotspot = a0 == 192 && a1 == 168 && a2 == 137;
          if (privateRange && !icsHotspot) return addr.getHostAddress();
          if (fallback.isEmpty()) fallback = addr.getHostAddress();
        }
      }
    } catch (Exception e) {
      return "";
    }
    return fallback;
  }

  static void send(HttpExchange ex, int code, String body, String contentType)
      throws IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    ex.getResponseHeaders().set("Content-Type", contentType);
    ex.sendResponseHeaders(code, bytes.length);
    try (OutputStream os = ex.getResponseBody()) {
      os.write(bytes);
    }
  }

  static void sendFile(HttpExchange ex, Path file, String contentType)
      throws IOException {
    if (!Files.isRegularFile(file)) {
      send(ex, 404, "Not found", "text/plain");
      return;
    }
    byte[] bytes = Files.readAllBytes(file);
    ex.getResponseHeaders().set("Content-Type", contentType);
    if (contentType.startsWith("text/html")) {
      ex.getResponseHeaders().set("Cache-Control", "no-store");
    }
    ex.sendResponseHeaders(200, bytes.length);
    try (OutputStream os = ex.getResponseBody()) {
      os.write(bytes);
    }
  }

  /** Reads the request body as UTF-8, or null if it exceeds maxBytes. */
  static String readBody(HttpExchange ex, long maxBytes) throws IOException {
    try (java.io.InputStream in = ex.getRequestBody()) {
      java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
      byte[] buf = new byte[8192];
      long total = 0;
      int n;
      while ((n = in.read(buf)) != -1) {
        total += n;
        if (total > maxBytes) return null;
        out.write(buf, 0, n);
      }
      return out.toString(StandardCharsets.UTF_8);
    }
  }

  static List<String> photoNames() throws IOException {
    return photoNames(null);
  }

  static List<String> photoNames(String client) throws IOException {
    List<String> names = new ArrayList<>();
    Path dir = ROOT.resolve("photos");
    if (client != null) {
      if (!validClientSlug(client)) return names;
      dir = dir.resolve(client);
    }
    if (!Files.isDirectory(dir)) return names;
    try (Stream<Path> s = Files.list(dir)) {
      s.filter(Files::isRegularFile)
          .map(p -> p.getFileName().toString())
          .filter(DakBoard::hasPhotoExt)
          .sorted(String.CASE_INSENSITIVE_ORDER)
          .forEach(names::add);
    }
    return names;
  }

  static boolean hasPhotoExt(String name) {
    String lower = name.toLowerCase();
    for (String ext : PHOTO_EXTS) {
      if (lower.endsWith(ext)) return true;
    }
    return hasVideoExt(name);
  }

  static boolean hasVideoExt(String name) {
    String lower = name == null ? "" : name.toLowerCase();
    for (String ext : VIDEO_EXTS) {
      if (lower.endsWith(ext)) return true;
    }
    return false;
  }

  /** Keeps only safe filename characters. */
  static String sanitize(String name) {
    if (name == null) return null;
    String base = name.replace('\\', '/');
    int slash = base.lastIndexOf('/');
    if (slash >= 0) base = base.substring(slash + 1);
    return base.replaceAll("[^A-Za-z0-9._-]", "_");
  }

  static String contentType(String name) {
    String lower = name.toLowerCase();
    if (lower.endsWith(".html")) return "text/html; charset=utf-8";
    if (lower.endsWith(".css")) return "text/css; charset=utf-8";
    if (lower.endsWith(".js")) return "text/javascript; charset=utf-8";
    if (lower.endsWith(".png")) return "image/png";
    if (lower.endsWith(".gif")) return "image/gif";
    if (lower.endsWith(".webp")) return "image/webp";
    if (lower.endsWith(".mp4")) return "video/mp4";
    if (lower.endsWith(".webm")) return "video/webm";
    return "image/jpeg";
  }

  static String jsonEscape(String s) {
    StringBuilder sb = new StringBuilder(s.length());
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      switch (c) {
        case '\\': sb.append("\\\\"); break;
        case '"': sb.append("\\\""); break;
        case '\n': sb.append("\\n"); break;
        case '\r': sb.append("\\r"); break;
        case '\t': sb.append("\\t"); break;
        default:
          if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
          else sb.append(c);
      }
    }
    return sb.toString();
  }

  static String quoted(String s) {
    return "\"" + jsonEscape(s) + "\"";
  }

  static String putJsonStringField(String json, String key, String value) {
    String needle = "\"" + key + "\"";
    int i = json.indexOf(needle);
    if(i >= 0){
      int colon = json.indexOf(':', i + needle.length());
      if(colon >= 0){
        int q1 = json.indexOf('"', colon + 1);
        if(q1 >= 0){
          for(int j = q1 + 1; j < json.length(); j++){
            char c = json.charAt(j);
            if(c == '\\'){ j++; continue; }
            if(c == '"') return json.substring(0, q1) + quoted(value) + json.substring(j + 1);
          }
        }
      }
    }
    int end = json.lastIndexOf('}');
    if(end < 0) return json;
    String prefix = json.substring(0, end).trim();
    String separator = prefix.endsWith("{") ? "" : ",";
    return prefix + separator + "\"" + key + "\":" + quoted(value) + json.substring(end);
  }

  /**
   * Extracts a top-level field from a flat JSON object. String values are unescaped,
   * other values (numbers, booleans) are returned as raw text. Null when not found.
   */
  static String jsonField(String json, String key) {
    String needle = "\"" + key + "\"";
    int i = json.indexOf(needle);
    if (i < 0) return null;
    int colon = json.indexOf(':', i + needle.length());
    if (colon < 0) return null;
    int valueStart = colon + 1;
    while (valueStart < json.length() && Character.isWhitespace(json.charAt(valueStart))) {
      valueStart++;
    }
    if (valueStart >= json.length()) return null;
    if (json.charAt(valueStart) != '"') {
      int end = valueStart;
      while (end < json.length() && ",}] \t\r\n".indexOf(json.charAt(end)) < 0) end++;
      return end > valueStart ? json.substring(valueStart, end) : null;
    }
    StringBuilder sb = new StringBuilder();
    for (int j = valueStart + 1; j < json.length(); j++) {
      char c = json.charAt(j);
      if (c == '\\' && j + 1 < json.length()) {
        char e = json.charAt(++j);
        switch (e) {
          case 'n': sb.append('\n'); break;
          case 't': sb.append('\t'); break;
          case 'r': sb.append('\r'); break;
          case 'u':
            if (j + 4 < json.length()) {
              try {
                sb.append((char) Integer.parseInt(json.substring(j + 1, j + 5), 16));
                j += 4;
              } catch (NumberFormatException ex) {
                sb.append('u');
              }
            } else {
              sb.append('u');
            }
            break;
          default: sb.append(e); break;
        }
      } else if (c == '"') {
        return sb.toString();
      } else {
        sb.append(c);
      }
    }
    return null;
  }
}
