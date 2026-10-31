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

    if (failures > 0) {
      System.out.println(failures + " check(s) failed");
      System.exit(1);
    }
    System.out.println("all checks passed");
  }
}
