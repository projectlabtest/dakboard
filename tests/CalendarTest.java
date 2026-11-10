import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;

/**
 * Calendar recurrence checks for eventsJson(): override exclusions, RANGE=THISANDFUTURE, and the
 * 200-event limit. Run from the project folder:
 *   javac --release 11 DakBoard.java
 *   javac --release 11 -cp . -d . tests/CalendarTest.java
 *   java -cp . CalendarTest
 */
public class CalendarTest {
  static int failures = 0;
  static final DateTimeFormatter STAMP =
      DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);

  static void expect(String name, boolean ok) {
    System.out.println((ok ? "PASS " : "FAIL ") + name);
    if (!ok) failures++;
  }

  static String ts(Instant i) {
    return STAMP.format(i);
  }

  static String ics(String... vevents) {
    StringBuilder sb = new StringBuilder("BEGIN:VCALENDAR\r\n");
    for (String v : vevents) {
      sb.append("BEGIN:VEVENT\r\n").append(v).append("END:VEVENT\r\n");
    }
    return sb.append("END:VCALENDAR\r\n").toString();
  }

  static int count(String json, String title) {
    String needle = "\"title\":\"" + title + "\"";
    int n = 0;
    for (int i = json.indexOf(needle); i >= 0; i = json.indexOf(needle, i + 1)) n++;
    return n;
  }

  static String event(String uid, String summary, Instant start, String extra) {
    return "UID:" + uid + "\r\nDTSTART:" + ts(start) + "\r\nDTEND:" + ts(start.plus(1, ChronoUnit.HOURS))
        + "\r\nSUMMARY:" + summary + "\r\n" + extra;
  }

  public static void main(String[] args) {
    Instant day0 = Instant.now().plus(3, ChronoUnit.DAYS).truncatedTo(ChronoUnit.DAYS)
        .plus(2, ChronoUnit.HOURS);
    Instant d1 = day0.plus(1, ChronoUnit.DAYS);
    Instant d2 = day0.plus(2, ChronoUnit.DAYS);

    String monthly = event("m1", "Monthly", day0, "RRULE:FREQ=MONTHLY;COUNT=3\r\n");
    String monthlyCancel = event("m1", "Cancelled slot", day0,
        "RECURRENCE-ID:" + ts(day0) + "\r\nSTATUS:CANCELLED\r\n");
    String json = DakBoard.eventsJson(ics(monthly, monthlyCancel));
    expect("monthly fallback omits a cancelled first slot", count(json, "Monthly") == 0);

    String monthlyMove = event("m2", "Monthly moved", day0, "RRULE:FREQ=MONTHLY;COUNT=3\r\n");
    String monthlyMoveOverride = event("m2", "Moved slot", d1,
        "RECURRENCE-ID:" + ts(day0) + "\r\n");
    json = DakBoard.eventsJson(ics(monthlyMove, monthlyMoveOverride));
    expect("monthly fallback omits a moved first slot", count(json, "Monthly moved") == 0);
    expect("moved monthly slot is shown once at its new time", count(json, "Moved slot") == 1);

    String daily = event("d1", "Daily", day0, "RRULE:FREQ=DAILY;COUNT=3\r\n");
    String rangeCancel = event("d1", "Range cancel", d1,
        "RECURRENCE-ID;RANGE=THISANDFUTURE:" + ts(d1) + "\r\nSTATUS:CANCELLED\r\n");
    json = DakBoard.eventsJson(ics(daily, rangeCancel));
    expect("THISANDFUTURE cancellation keeps the earlier occurrence", count(json, "Daily") == 1);

    String dailyShift = event("d2", "Daily shifted", day0, "RRULE:FREQ=DAILY;COUNT=3\r\n");
    String rangeMove = event("d2", "Shifted", d1.plus(1, ChronoUnit.HOURS),
        "RECURRENCE-ID;RANGE=THISANDFUTURE:" + ts(d1) + "\r\n");
    json = DakBoard.eventsJson(ics(dailyShift, rangeMove));
    expect("THISANDFUTURE move keeps the first occurrence and the shifted later one",
        count(json, "Daily shifted") == 2);
    String shiftedStart = java.time.OffsetDateTime.ofInstant(d2.plus(1, ChronoUnit.HOURS),
        java.time.ZoneId.of("Asia/Manila")).toString();
    expect("THISANDFUTURE move shifts the later occurrence by the override change",
        json.contains("\"start\":\"" + shiftedStart + "\""));
    expect("THISANDFUTURE move shows the replaced occurrence once", count(json, "Shifted") == 1);

    String tomorrow = event("t1", "Tomorrow", d1, "RRULE:FREQ=DAILY;COUNT=1\r\n");
    String[] filler = new String[200];
    for (int i = 0; i < filler.length; i++) {
      filler[i] = event("f" + i, "Filler " + i, day0.plus(5, ChronoUnit.DAYS).plus(i, ChronoUnit.HOURS), "");
    }
    String replacement = event("t1", "Replacement", d1.plus(1, ChronoUnit.HOURS),
        "RECURRENCE-ID:" + ts(d1) + "\r\n");
    String[] all = new String[filler.length + 2];
    all[0] = tomorrow;
    System.arraycopy(filler, 0, all, 1, filler.length);
    all[all.length - 1] = replacement;
    json = DakBoard.eventsJson(ics(all));
    expect("late-listed replacement survives the 200-event limit", count(json, "Replacement") == 1);
    expect("the replaced occurrence is not shown", count(json, "Tomorrow") == 0);

    String durMaster = event("d3", "Long series", day0, "RRULE:FREQ=DAILY;COUNT=3\r\n");
    String durOverride = "UID:d3\r\nRECURRENCE-ID;RANGE=THISANDFUTURE:" + ts(d1) + "\r\nDTSTART:"
        + ts(d1.plus(1, ChronoUnit.HOURS)) + "\r\nDTEND:" + ts(d1.plus(3, ChronoUnit.HOURS))
        + "\r\nSUMMARY:Long moved\r\n";
    json = DakBoard.eventsJson(ics(durMaster, durOverride));
    String d2Start = java.time.OffsetDateTime.ofInstant(d2.plus(1, ChronoUnit.HOURS),
        java.time.ZoneId.of("Asia/Manila")).toString();
    String d2End = java.time.OffsetDateTime.ofInstant(d2.plus(3, ChronoUnit.HOURS),
        java.time.ZoneId.of("Asia/Manila")).toString();
    expect("THISANDFUTURE duration change applies to later occurrences: start",
        json.contains("\"start\":\"" + d2Start + "\""));
    expect("THISANDFUTURE duration change applies to later occurrences: end",
        json.contains("\"end\":\"" + d2End + "\""));

    String early = event("e1", "Early master", d1, "RRULE:FREQ=DAILY;COUNT=1\r\n");
    String[] bulk = new String[2500];
    for (int i = 0; i < bulk.length; i++) {
      bulk[i] = event("b" + i, "Bulk " + i, day0.plus(5, ChronoUnit.DAYS).plus(i, ChronoUnit.MINUTES), "");
    }
    String lateReplacement = event("e1", "Late replacement", d1.plus(2, ChronoUnit.HOURS),
        "RECURRENCE-ID:" + ts(d1) + "\r\n");
    String[] big = new String[bulk.length + 2];
    big[0] = early;
    System.arraycopy(bulk, 0, big, 1, bulk.length);
    big[big.length - 1] = lateReplacement;
    json = DakBoard.eventsJson(ics(big));
    expect("replacement survives a collection far above the bound", count(json, "Late replacement") == 1);
    expect("early master stays suppressed above the bound", count(json, "Early master") == 0);

    if (failures > 0) {
      System.out.println(failures + " check(s) failed");
      System.exit(1);
    }
    System.out.println("all checks passed");
  }
}
