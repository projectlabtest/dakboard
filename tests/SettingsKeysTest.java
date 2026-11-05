import java.util.List;

/**
 * Unit checks for the settings key scanner. Run from the project folder:
 *   javac --release 11 DakBoard.java
 *   javac --release 11 -cp . -d . tests/SettingsKeysTest.java
 *   java -cp . SettingsKeysTest
 */
public class SettingsKeysTest {
  static int failures = 0;

  static void expect(String name, boolean ok) {
    System.out.println((ok ? "PASS " : "FAIL ") + name);
    if (!ok) failures++;
  }

  public static void main(String[] args) {
    List<String> normal = DakBoard.topLevelKeys("{\"a\":1,\"b\":\"x\",\"c\":{\"a\":2},\"d\":[{\"a\":3}]}");
    expect("normal keys are listed", normal != null && normal.equals(List.of("a", "b", "c", "d")));

    expect("nested duplicate names are allowed",
        DakBoard.topLevelKeys("{\"c\":{\"a\":1,\"a\":2}}") != null);

    expect("repeated top-level key is rejected",
        DakBoard.topLevelKeys("{\"outlookIcsUrl\":\"x\",\"outlookIcsUrl\":\"y\"}") == null);

    expect("escaped key name is rejected",
        DakBoard.topLevelKeys("{\"outlook\\u0049csUrl\":\"x\"}") == null);

    expect("string value containing a colon is not a key",
        DakBoard.topLevelKeys("{\"ticker\":\"a\\\"b\",\"ticker2\":\"c:d\"}") != null);

    expect("unterminated string is rejected", DakBoard.topLevelKeys("{\"a\":\"x}") == null);

    String nested = "{\"rooms\":[{\"name\":\"Hall\",\"outlookIcsUrl\":\"https://evil.example/x\"}],"
        + "\"outlookIcsUrl\":\"https://good.example/feed\"}";
    expect("top-level read ignores nested copy",
        "https://good.example/feed".equals(DakBoard.jsonField(nested, "outlookIcsUrl")));
    expect("nested-only key is not read as top-level",
        DakBoard.jsonField("{\"rooms\":[{\"outlookIcsUrl\":\"https://evil.example/x\"}]}", "outlookIcsUrl") == null);
    expect("top-level put leaves nested copy untouched",
        DakBoard.putJsonStringField("{\"rooms\":[{\"outlookIcsUrl\":\"\"}]}", "outlookIcsUrl", "https://good.example/feed")
            .equals("{\"rooms\":[{\"outlookIcsUrl\":\"\"}],\"outlookIcsUrl\":\"https://good.example/feed\"}"));
    expect("top-level put replaces existing top-level value",
        DakBoard.putJsonStringField(nested, "outlookIcsUrl", "https://new.example/f")
            .endsWith("\"outlookIcsUrl\":\"https://new.example/f\"}"));
    expect("nested outlookIcsUrl is detected", DakBoard.hasNestedSettingName(nested, "outlookIcsUrl"));
    expect("nested escaped name is detected",
        DakBoard.hasNestedSettingName("{\"rooms\":[{\"outlook\\u0049csUrl\":\"x\"}]}", "outlookIcsUrl"));
    expect("top-level outlookIcsUrl is not a nested name",
        !DakBoard.hasNestedSettingName("{\"outlookIcsUrl\":\"https://good.example/feed\"}", "outlookIcsUrl"));
    expect("other nested names are allowed",
        !DakBoard.hasNestedSettingName("{\"rooms\":[{\"name\":\"Hall\"}]}", "outlookIcsUrl"));

    if (failures > 0) {
      System.out.println(failures + " check(s) failed");
      System.exit(1);
    }
    System.out.println("all checks passed");
  }
}
