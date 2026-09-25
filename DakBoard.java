import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.stream.Stream;

/**
 * LIFE HOPE CENTER DAKboard - standalone Java server, no dependencies.
 *
 * Run from the dakboard-java folder:
 *   javac DakBoard.java
 *   java DakBoard [port, default 8080]
 *
 * Board:        http://localhost:8080/dakboard
 * Control page: http://localhost:8080/dakboard/admin
 */
public class DakBoard {

  static Path ROOT = Paths.get("").toAbsolutePath();
  static final long MAX_BODY = 32L * 1024 * 1024; // 32 MB (photo uploads)
  static final String[] PHOTO_EXTS = {".jpg", ".jpeg", ".png", ".webp", ".gif"};

  public static void main(String[] args) throws Exception {
    int port = 8080;
    if (args.length > 0) {
      port = Integer.parseInt(args[0]);
    }
    Files.createDirectories(ROOT.resolve("photos"));
    Path settings = ROOT.resolve("settings.json");
    if (!Files.exists(settings)) {
      Files.copy(ROOT.resolve("settings.default.json"), settings);
    }
    HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
    server.createContext("/dakboard/admin", DakBoard::handleAdmin);
    server.createContext("/dakboard/room", DakBoard::handleRoom);
    server.createContext("/dakboard", DakBoard::handleBoard);
    server.createContext("/api/dakboard/settings", DakBoard::handleSettings);
    server.createContext("/api/dakboard/photos/upload", DakBoard::handleUpload);
    server.createContext("/api/dakboard/photos/delete", DakBoard::handleDelete);
    server.createContext("/api/dakboard/photos", DakBoard::handlePhotos);
    server.createContext("/api/dakboard/events", DakBoard::handleEvents);
    server.createContext("/sample-events.ics", DakBoard::handleSampleEvents);
    server.createContext("/photos", DakBoard::handlePhotoFile);
    server.createContext("/", DakBoard::handleRoot);
    server.setExecutor(Executors.newFixedThreadPool(8));
    server.start();
    System.out.println("DAKboard running at http://localhost:" + port + "/dakboard");
    System.out.println("Control page at http://localhost:" + port + "/dakboard/admin");
  }

  // ---------------- routes ----------------

  static void handleRoot(HttpExchange ex) throws IOException {
    redirect(ex, "/dakboard");
  }

  static void handleBoard(HttpExchange ex) throws IOException {
    if (!method(ex, "GET")) { send(ex, 405, "Method not allowed", "text/plain"); return; }
    if (hasQueryParam(ex.getRequestURI().getRawQuery(), "room")) {
      handleRoom(ex);
      return;
    }
    sendFile(ex, ROOT.resolve("board.html"), "text/html; charset=utf-8");
  }

  static void handleRoom(HttpExchange ex) throws IOException {
    if (!method(ex, "GET")) { send(ex, 405, "Method not allowed", "text/plain"); return; }
    sendFile(ex, ROOT.resolve("room.html"), "text/html; charset=utf-8");
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
    sendFile(ex, ROOT.resolve("admin.html"), "text/html; charset=utf-8");
  }

  static void handleSettings(HttpExchange ex) throws IOException {
    Path file = ROOT.resolve("settings.json");
    if (method(ex, "GET")) {
      sendFile(ex, file, "application/json; charset=utf-8");
      return;
    }
    if (method(ex, "POST")) {
      String body = readBody(ex, 512 * 1024);
      if (body == null) { send(ex, 413, "{\"error\":\"too large\"}", "application/json"); return; }
      String t = body.trim();
      if (!t.startsWith("{") || !t.endsWith("}")) {
        send(ex, 400, "{\"error\":\"invalid json\"}", "application/json");
        return;
      }
      String query = ex.getRequestURI().getRawQuery();
      boolean clearOutlookIcs = query != null && query.contains("clearOutlookIcs=true");
      String submittedUrl = jsonField(body, "outlookIcsUrl");
      if(!clearOutlookIcs && (submittedUrl == null || submittedUrl.isEmpty())){
        String currentUrl = settingsField("outlookIcsUrl");
        if(!currentUrl.isEmpty()) body = putJsonStringField(body, "outlookIcsUrl", currentUrl);
      }
      Path tmp = ROOT.resolve("settings.json.tmp");
      Files.write(tmp, body.getBytes(StandardCharsets.UTF_8));
      Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING,
          StandardCopyOption.ATOMIC_MOVE);
      send(ex, 200, "{\"ok\":true}", "application/json");
      return;
    }
    send(ex, 405, "Method not allowed", "text/plain");
  }

  static void handlePhotos(HttpExchange ex) throws IOException {
    if (!method(ex, "GET")) { send(ex, 405, "Method not allowed", "text/plain"); return; }
    StringBuilder sb = new StringBuilder("[");
    boolean first = true;
    for (String name : photoNames()) {
      if (!first) sb.append(",");
      sb.append("\"/photos/").append(jsonEscape(name)).append("\"");
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
    String body = readBody(ex, MAX_BODY);
    if (body == null) { send(ex, 413, "{\"error\":\"too large\"}", "application/json"); return; }
    String filename = sanitize(jsonField(body, "filename"));
    String data = jsonField(body, "data");
    if (filename == null || filename.isEmpty() || data == null) {
      send(ex, 400, "{\"error\":\"filename and data required\"}", "application/json");
      return;
    }
    if (!hasPhotoExt(filename)) {
      send(ex, 400, "{\"error\":\"photo must be JPG, PNG, WEBP or GIF\"}", "application/json");
      return;
    }
    // strip data URL prefix if present ("data:image/png;base64,....")
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
    Files.write(ROOT.resolve("photos").resolve(filename), bytes);
    send(ex, 200, "{\"ok\":true,\"file\":" + quoted(filename) + "}", "application/json");
  }

  static void handleDelete(HttpExchange ex) throws IOException {
    if (!method(ex, "POST")) { send(ex, 405, "Method not allowed", "text/plain"); return; }
    String body = readBody(ex, 64 * 1024);
    if (body == null) { send(ex, 413, "{\"error\":\"too large\"}", "application/json"); return; }
    String filename = sanitize(jsonField(body, "filename"));
    Path file = ROOT.resolve("photos").resolve(filename == null ? "" : filename).normalize();
    if (filename == null || filename.isEmpty()
        || !file.startsWith(ROOT.resolve("photos")) || !Files.isRegularFile(file)) {
      send(ex, 404, "{\"error\":\"photo not found\"}", "application/json");
      return;
    }
    Files.delete(file);
    send(ex, 200, "{\"ok\":true}", "application/json");
  }

  // ---------------- Outlook calendar (ICS) events ----------------

  static final ZoneId MANILA = ZoneId.of("Asia/Manila");
  static final long EVENTS_CACHE_MS = 60L * 1000;
  static String eventsCacheIcs = null;
  static long eventsCacheAt = 0;
  static String eventsCacheUrl = "";

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
    String url = settingsField("outlookIcsUrl");
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
    try {
      String body = Files.readString(ROOT.resolve("settings.json"), StandardCharsets.UTF_8);
      String v = jsonField(body, key);
      return v == null ? "" : v.trim();
    } catch (IOException e) {
      return "";
    }
  }

  static synchronized String cachedIcs(String url) {
    long now = System.currentTimeMillis();
    if (url.equals(eventsCacheUrl) && eventsCacheIcs != null
        && now - eventsCacheAt < EVENTS_CACHE_MS) {
      return eventsCacheIcs;
    }
    String fresh = fetchIcs(url);
    if (fresh != null) {
      eventsCacheIcs = fresh;
      eventsCacheAt = now;
      eventsCacheUrl = url;
      return fresh;
    }
    if (url.equals(eventsCacheUrl) && eventsCacheIcs != null) return eventsCacheIcs;
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
          .followRedirects(HttpClient.Redirect.NORMAL).build();
      HttpRequest req = HttpRequest.newBuilder(URI.create(url))
          .timeout(Duration.ofSeconds(20))
          .header("User-Agent", "LHC-DAKboard/1.0").GET().build();
      HttpResponse<byte[]> res = client.send(req, HttpResponse.BodyHandlers.ofByteArray());
      if (res.statusCode() < 200 || res.statusCode() >= 300) return null;
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
    } catch (Exception e) {
      return null;
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

  static boolean method(HttpExchange ex, String m) {
    return m.equalsIgnoreCase(ex.getRequestMethod());
  }

  static void redirect(HttpExchange ex, String location) throws IOException {
    ex.getResponseHeaders().set("Location", location);
    ex.sendResponseHeaders(302, -1);
    ex.close();
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
    List<String> names = new ArrayList<>();
    Path dir = ROOT.resolve("photos");
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
    return "image/jpeg";
  }

  static String jsonEscape(String s) {
    return s.replace("\\", "\\\\").replace("\"", "\\\"");
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
   * Extracts a top-level string field from a flat JSON object.
   * Handles basic backslash escapes. Returns null if not found.
   */
  static String jsonField(String json, String key) {
    String needle = "\"" + key + "\"";
    int i = json.indexOf(needle);
    if (i < 0) return null;
    int colon = json.indexOf(':', i + needle.length());
    if (colon < 0) return null;
    int q1 = json.indexOf('"', colon + 1);
    if (q1 < 0) return null;
    StringBuilder sb = new StringBuilder();
    for (int j = q1 + 1; j < json.length(); j++) {
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
