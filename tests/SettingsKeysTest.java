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

    String[] validObjects = {
        "{}", " { } ", "{\"a\":1}", "{\"a\":-1.5e+3,\"b\":0}", "{\"a\":[1,\"x\",{\"b\":null}],\"c\":true}",
        "{\"s\":\"esc \\\" \\\\ \\/ \\n \\u00e9\"}", "{\"c\":{\"a\":1,\"a\":2}}", "\n{\"a\":false}\n"
    };
    for (String json : validObjects) {
      expect("valid object accepted: " + json.trim(), DakBoard.isValidJsonObject(json));
    }

    String[] invalidObjects = {
        "", "{", "{\"a\":1", "{\"a\":}", "{\"a\":1,}", "{\"a\" 1}", "{\"a\":01}", "{}}",
        "{\"a\":1} {\"b\":2}", "{\"a\":\"x}", "[1,2]", "\"text\"", "{\"a\":tru}",
        "{\"a\":\"bad \\x escape\"}", "{\"a\":\"raw\ncontrol\"}", "{\"a\":\"\\u12G4\"}",
        "{\"a\":\"\\u" + (char) 0xFF11 + (char) 0xFF12 + (char) 0xFF13 + (char) 0xFF14 + "\"}",
        "{\"a\":\"\\u" + (char) 0x0661 + (char) 0x0662 + (char) 0x0663 + (char) 0x0664 + "\"}",
        "{\"a\":1 \"b\":2}", "{,}", "{\"a\":[1,]}", "{\"a\":+1}", "{\"a\":.5}"
    };
    for (String json : invalidObjects) {
      expect("invalid object rejected: " + json.replace("\n", "\\n"), !DakBoard.isValidJsonObject(json));
    }

    if (failures > 0) {
      System.out.println(failures + " check(s) failed");
      System.exit(1);
    }
    System.out.println("all checks passed");
  }
}
