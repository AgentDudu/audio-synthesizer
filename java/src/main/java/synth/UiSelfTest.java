package synth;

import java.awt.event.FocusEvent;
import java.awt.event.MouseEvent;
import javax.swing.SwingUtilities;

/**
 * Headless check of the keyboard layer: computer-key map, pixel-to-note geometry, mouse drag
 * behaviour, focus-loss note-off, and the engine actually holding the right number of voices.
 * Run with: java -cp build/classes synth.UiSelfTest
 */
public final class UiSelfTest {
  private static int failures;

  private static void check(boolean ok, String what) {
    System.out.println((ok ? "  ok   " : "  FAIL ") + what);
    if (!ok) ++failures;
  }

  public static void main(String[] args) throws Exception {
    if (args.length > 0 && args[0].equals("png")) {
      SwingUtilities.invokeAndWait(() -> {
        KeyboardPanel p = new KeyboardPanel();
        int w = KeyboardPanel.WHITE_KEYS * KeyboardPanel.WHITE_WIDTH;
        p.setSize(w, KeyboardPanel.KEY_HEIGHT);
        java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(
            w, KeyboardPanel.KEY_HEIGHT, java.awt.image.BufferedImage.TYPE_INT_RGB);
        p.paint(img.createGraphics());
        try {
          javax.imageio.ImageIO.write(img, "png", new java.io.File("build/keyboard.png"));
        } catch (java.io.IOException e) {
          throw new RuntimeException(e);
        }
      });
      System.out.println("wrote build/keyboard.png");
      System.exit(0);
    }

    System.out.println("key map");
    check(KeyboardPanel.keyNote('a', 0) == 60, "'a' -> C4 (60)");
    check(KeyboardPanel.keyNote('A', 0) == 60, "'A' is case-insensitive");
    check(KeyboardPanel.keyNote('w', 0) == 61, "'w' -> C#4 (61)");
    check(KeyboardPanel.keyNote('k', 0) == 72, "'k' -> C5 (72)");
    check(KeyboardPanel.keyNote(';', 0) == 76, "';' -> E5 (76)");
    check(KeyboardPanel.keyNote('a', 2) == 84, "'a' two octaves up -> C6 (84)");
    check(KeyboardPanel.keyNote('q', 0) == -1, "'q' unmapped");

    System.out.println("pixel -> note");
    check(KeyboardPanel.noteAt(2, 120, 0) == 60, "left edge white key -> C4");
    check(KeyboardPanel.noteAt(KeyboardPanel.blackLeft(0) + 5, 10, 0) == 61, "black key region -> C#4");
    check(KeyboardPanel.noteAt(KeyboardPanel.blackLeft(0) + 5, 120, 0) == 60, "below black key -> white C4");
    check(KeyboardPanel.noteAt(3 * KeyboardPanel.WHITE_WIDTH + 2, 120, 0) == 65, "4th white key -> F4");
    check(KeyboardPanel.noteAt(-1, 0, 0) == -1, "x < 0 -> no note");
    check(KeyboardPanel.noteAt(KeyboardPanel.WHITE_KEYS * KeyboardPanel.WHITE_WIDTH, 0, 0) == -1,
        "x past last key -> no note");
    check(!KeyboardPanel.hasBlack(2, 0), "no black key between E and F");
    check(KeyboardPanel.hasBlack(0, 0), "black key between C and D");

    String err = Engine.start(Engine.defaultOutputDeviceId(), 48000, 512);
    check(err == null, "engine start" + (err == null ? "" : ": " + err));
    if (err != null) System.exit(1);

    final KeyboardPanel[] box = new KeyboardPanel[1];
    SwingUtilities.invokeAndWait(() -> {
      box[0] = new KeyboardPanel();
      box[0].setSize(KeyboardPanel.WHITE_KEYS * KeyboardPanel.WHITE_WIDTH, KeyboardPanel.KEY_HEIGHT);
    });
    KeyboardPanel keys = box[0];

    System.out.println("computer keys vs engine");
    keys.keyDown('a');
    check(keys.heldCount() == 1, "one note held after keyDown");
    keys.keyDown('a');
    check(keys.heldCount() == 1, "key auto-repeat does not retrigger");
    keys.keyDown('k');
    check(keys.heldCount() == 2, "two notes held");
    Thread.sleep(150);
    check(Engine.activeVoices() == 2, "engine holds 2 voices, got " + Engine.activeVoices());
    keys.keyUp('a');
    check(keys.heldCount() == 1, "keyUp releases one");

    System.out.println("mouse vs engine");
    int dragX = 3 * KeyboardPanel.WHITE_WIDTH + 2;
    SwingUtilities.invokeAndWait(() -> {
      keys.dispatchEvent(new MouseEvent(keys, MouseEvent.MOUSE_PRESSED, 0, 0,
          KeyboardPanel.blackLeft(0) + 5, 10, 1, false));
      keys.dispatchEvent(new MouseEvent(keys, MouseEvent.MOUSE_DRAGGED, 0, 0, dragX, 120, 0, false));
    });
    check(keys.heldCount() == 2, "drag adds a note while the key is still held");
    SwingUtilities.invokeAndWait(() -> keys.dispatchEvent(
        new MouseEvent(keys, MouseEvent.MOUSE_RELEASED, 0, 0, dragX, 120, 1, false)));
    check(keys.heldCount() == 1, "mouse release clears only the mouse note");

    System.out.println("focus loss");
    // AWT swallows synthetic FocusEvents, so call the registered listener the way the toolkit would.
    SwingUtilities.invokeAndWait(() -> {
      for (java.awt.event.FocusListener l : keys.getFocusListeners()) {
        l.focusLost(new FocusEvent(keys, FocusEvent.FOCUS_LOST));
      }
    });
    check(keys.heldCount() == 0, "focus loss clears every held note");
    Thread.sleep(700);
    check(Engine.activeVoices() == 0, "engine drains to 0 voices, got " + Engine.activeVoices());
    check(Engine.underrunCount() == 0, "no underruns, got " + Engine.underrunCount());
    Engine.stop();

    System.out.println(failures == 0 ? "UI self-test OK" : "UI self-test FAILURES=" + failures);
    System.exit(failures == 0 ? 0 : 1);
  }
}
