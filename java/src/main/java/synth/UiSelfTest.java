package synth;

import java.awt.event.FocusEvent;
import java.awt.event.MouseEvent;
import java.util.Set;
import java.util.TreeSet;
import javax.swing.SwingUtilities;

/**
 * Headless check of the keyboard layer: computer-key map, pixel-to-note geometry at several window
 * sizes (the keyboard scales with the frame), mouse drag, focus-loss note-off, and the engine
 * actually holding the right notes and voices. Run: java -cp build/classes synth.UiSelfTest [png]
 */
public final class UiSelfTest {
  private static final int BIG_W = 936, BIG_H = 150;
  private static final int SMALL_W = 420, SMALL_H = 80;
  private static final int TINY_W = KeyboardPanel.MIN_WIDTH, TINY_H = KeyboardPanel.MIN_HEIGHT;
  // synth.cpp defaults; the knob section puts the engine back here before the drain test.
  private static final float[] DEFAULTS = {8000f, 0.2f, 0.003f, 0.2f, 0.7f, 0.25f, 0f, 0.2f};

  private static int failures;

  private static void check(boolean ok, String what) {
    System.out.println((ok ? "  ok   " : "  FAIL ") + what);
    if (!ok) ++failures;
  }

  private static int indexOf(int note) {
    for (int w = 0; w < KeyboardPanel.whiteCount(); ++w) {
      if (KeyboardPanel.whiteNote(w) == note) return w;
    }
    return -1;
  }

  private static int whiteX(int whiteIndex, int width) {
    return Math.round(whiteIndex * KeyboardPanel.whiteWidth(width)
        + KeyboardPanel.whiteWidth(width) / 2f);
  }

  private static int blackX(int whiteIndex, int width) {
    return Math.round(KeyboardPanel.blackLeft(whiteIndex, width)
        + KeyboardPanel.blackWidth(width) / 2f);
  }

  /** Hit-testing must be complete and correct at every window size. */
  private static void checkGeometry(int width, int height) {
    System.out.println("pixel -> note @ " + width + "x" + height);
    int c4 = indexOf(60);
    check(Math.abs(KeyboardPanel.whiteWidth(width) - width / 52f) < 0.01f,
        "white key width tracks the window (" + String.format("%.2f", KeyboardPanel.whiteWidth(width))
            + " px at " + width + ")");
    check(KeyboardPanel.noteAt(whiteX(c4, width), height - 10, width, height) == 60, "white key -> C4");
    check(KeyboardPanel.hasBlack(c4)
        && KeyboardPanel.noteAt(blackX(c4, width), 5, width, height) == 61, "black key above C4 -> C#4");
    int blackCentre = blackX(c4, width);
    int off = Math.max(1, Math.round(KeyboardPanel.blackWidth(width) / 4f));
    check(KeyboardPanel.noteAt(blackCentre - off, height - 10, width, height) == 60,
        "left half under the black key -> white C4");
    check(KeyboardPanel.noteAt(blackCentre + off, height - 10, width, height) == 62,
        "right half under the black key -> white D4");
    check(KeyboardPanel.noteAt(0, height - 10, width, height) == 21, "left edge -> A0");
    check(KeyboardPanel.noteAt(width - 1, height - 10, width, height) == 108, "right edge -> C8");
    check(!KeyboardPanel.hasBlack(indexOf(64)), "no black key between E and F");
    check(KeyboardPanel.hasBlack(indexOf(67)), "black key between G and A");
    check(KeyboardPanel.noteAt(-1, 0, width, height) == -1, "x < 0 -> no note");
    check(KeyboardPanel.noteAt(width, 0, width, height) == -1, "x past last key -> no note");
    check(KeyboardPanel.noteAt(0, height, width, height) == -1, "y below keys -> no note");

    TreeSet<Integer> reachable = new TreeSet<>();
    for (int w = 0; w < KeyboardPanel.whiteCount(); ++w) {
      reachable.add(KeyboardPanel.noteAt(whiteX(w, width), height - 10, width, height));
      if (KeyboardPanel.hasBlack(w)) {
        reachable.add(KeyboardPanel.noteAt(blackX(w, width), 5, width, height));
      }
    }
    check(reachable.size() == 88 && reachable.first() == 21 && reachable.last() == 108,
        "every note A0-C8 clickable at " + width + "x" + height + ", got " + reachable.size()
            + " (" + reachable.first() + ".." + reachable.last() + ")");
  }

  public static void main(String[] args) throws Exception {
    if (args.length > 0 && args[0].equals("png")) {
      renderPng(BIG_W, BIG_H, "build/keyboard.png");
      renderPng(SMALL_W, SMALL_H, "build/keyboard-small.png");
      System.out.println("wrote build/keyboard.png and build/keyboard-small.png");
      System.exit(0);
    }

    System.out.println("key map");
    check(KeyboardPanel.keyNote('a', 0) == 60, "'a' -> C4 (60)");
    check(KeyboardPanel.keyNote('A', 0) == 60, "'A' is case-insensitive");
    check(KeyboardPanel.keyNote('w', 0) == 61, "'w' -> C#4 (61)");
    check(KeyboardPanel.keyNote('k', 0) == 72, "'k' -> C5 (72)");
    check(KeyboardPanel.keyNote(';', 0) == 76, "';' -> E5 (76)");
    check(KeyboardPanel.keyNote('q', 0) == -1, "'q' unmapped");
    check(KeyboardPanel.keyNote('a', KeyboardPanel.MIN_OCTAVE_SHIFT) >= KeyboardPanel.LOWEST_NOTE
        && KeyboardPanel.keyNote(';', KeyboardPanel.MAX_OCTAVE_SHIFT) <= KeyboardPanel.HIGHEST_NOTE,
        "the extreme shifts stay inside the piano");

    checkGeometry(BIG_W, BIG_H);
    checkGeometry(SMALL_W, SMALL_H);
    checkGeometry(TINY_W, TINY_H);

    String err = Engine.start(Engine.defaultOutputDeviceId(), 48000, 512);
    check(err == null, "engine start" + (err == null ? "" : ": " + err));
    if (err != null) System.exit(1);

    final KeyboardPanel[] box = new KeyboardPanel[1];
    SwingUtilities.invokeAndWait(() -> box[0] = new KeyboardPanel());
    KeyboardPanel keys = box[0];

    System.out.println("computer keys vs engine");
    keys.keyDown('a');
    check(keys.heldCount() == 1, "one note held after keyDown");
    keys.keyDown('a');
    check(keys.heldCount() == 1, "key auto-repeat does not retrigger");
    keys.keyDown('k');
    check(keys.heldNotes().equals(Set.of(60, 72)), "engine gets C4 + C5, got " + keys.heldNotes());
    Thread.sleep(150);
    check(Engine.activeVoices() == 2, "engine holds 2 voices, got " + Engine.activeVoices());
    keys.keyUp('a');
    check(keys.heldCount() == 1, "keyUp releases one");

    System.out.println("mouse at two window sizes");
    final int c4 = indexOf(60);
    for (int[] size : new int[][]{{BIG_W, BIG_H}, {SMALL_W, SMALL_H}}) {
      int w = size[0], h = size[1];
      SwingUtilities.invokeAndWait(() -> {
        keys.setSize(w, h);
        click(keys, MouseEvent.MOUSE_PRESSED, blackX(c4, w), 5);
      });
      check(keys.heldNotes().equals(Set.of(72, 61)),
          "black key click at " + w + "x" + h + " plays C#4, got " + keys.heldNotes());
      SwingUtilities.invokeAndWait(() -> click(keys, MouseEvent.MOUSE_PRESSED, w - 2, h - 10));
      check(keys.heldNotes().equals(Set.of(72, 108)),
          "right-edge click at " + w + "x" + h + " plays C8, got " + keys.heldNotes());
      SwingUtilities.invokeAndWait(() -> click(keys, MouseEvent.MOUSE_RELEASED, w - 2, h - 10));
      check(keys.heldCount() == 1, "mouse release clears only the mouse note");
    }

    System.out.println("mouse drag");
    SwingUtilities.invokeAndWait(() -> {
      click(keys, MouseEvent.MOUSE_PRESSED, whiteX(indexOf(65), SMALL_W), SMALL_H - 10);
      click(keys, MouseEvent.MOUSE_DRAGGED, whiteX(indexOf(67), SMALL_W), SMALL_H - 10);
    });
    check(keys.heldNotes().equals(Set.of(72, 67)), "drag follows the mouse to G4, got " + keys.heldNotes());
    SwingUtilities.invokeAndWait(() -> click(keys, MouseEvent.MOUSE_RELEASED,
        whiteX(indexOf(67), SMALL_W), SMALL_H - 10));
    check(keys.heldCount() == 1, "release clears the dragged note");

    System.out.println("octave shift");
    keys.setOctaveShift(KeyboardPanel.MAX_OCTAVE_SHIFT + 5);
    check(keys.octaveShift() == KeyboardPanel.MAX_OCTAVE_SHIFT, "shift clamps high");
    keys.keyDown(';');
    check(keys.heldNotes().contains(KeyboardPanel.keyNote(';', KeyboardPanel.MAX_OCTAVE_SHIFT)),
        "top shift still plays a note");
    keys.keyUp(';');
    keys.setOctaveShift(KeyboardPanel.MIN_OCTAVE_SHIFT - 5);
    check(keys.octaveShift() == KeyboardPanel.MIN_OCTAVE_SHIFT, "shift clamps low");
    keys.keyDown('a');
    check(keys.heldNotes().contains(KeyboardPanel.keyNote('a', KeyboardPanel.MIN_OCTAVE_SHIFT)),
        "bottom shift still plays a note");
    keys.keyUp('a');
    keys.setOctaveShift(0);

    System.out.println("knobs");
    // Engine is still at its C++ defaults here, which is what the sliders must start from.
    final KnobPanel[] knobBox = new KnobPanel[1];
    SwingUtilities.invokeAndWait(() -> knobBox[0] = new KnobPanel());
    KnobPanel knobs = knobBox[0];
    for (KnobPanel.Spec spec : KnobPanel.SPECS) {
      check(KnobPanel.toEngine(spec, 0) == spec.min()
              && Math.abs(KnobPanel.toEngine(spec, KnobPanel.TICKS) - spec.max()) < spec.max() * 0.01,
          spec.label() + " slider spans " + spec.min() + ".." + spec.max());
      float value = KnobPanel.toEngine(spec, 640);
      check(Math.abs(KnobPanel.toTick(spec, value) - 640) <= 1, spec.label() + " slider round-trips");
      check(knobs.tickOf(spec.id()) == KnobPanel.toTick(spec, Engine.getParam(spec.id())),
          spec.label() + " slider starts at the engine value " + Engine.getParam(spec.id()));
    }
    for (KnobPanel.Spec spec : KnobPanel.SPECS) {
      knobs.setTick(spec.id(), 700);
      float expected = KnobPanel.toEngine(spec, 700);
      check(Math.abs(Engine.getParam(spec.id()) - expected) <= Math.abs(expected) * 1e-3 + 1e-6,
          spec.label() + " reaches the engine: set " + expected + ", got " + Engine.getParam(spec.id()));
    }
    // 'k' is still held from the mouse section; clear it and let older releases finish so the
    // voice count below means exactly the two notes pressed here.
    keys.keyUp('k');
    Thread.sleep(600);
    keys.keyDown('a');
    keys.keyDown('e');
    for (int tick = 0; tick <= KnobPanel.TICKS; tick += 40) {
      knobs.setTick(Engine.CUTOFF, tick);
      knobs.setTick(Engine.MASTER, tick);
    }
    Thread.sleep(150);
    check(Engine.activeVoices() == 2, "notes survive a full cutoff+level sweep, got " + Engine.activeVoices() + " held " + keys.heldNotes());
    check(Engine.underrunCount() == 0, "no underruns during the sweep, got " + Engine.underrunCount());
    keys.keyUp('a');
    keys.keyUp('e');
    // These two notes captured the long release set above, so give them time to finish.
    Thread.sleep(2200);
    check(Engine.activeVoices() == 0, "long releases drain, got " + Engine.activeVoices());
    for (int id = 0; id < DEFAULTS.length; ++id) {
      for (KnobPanel.Spec spec : KnobPanel.SPECS) {
        if (spec.id() == id) knobs.setTick(id, KnobPanel.toTick(spec, DEFAULTS[id]));
      }
      check(Math.abs(Engine.getParam(id) - DEFAULTS[id]) <= Math.abs(DEFAULTS[id]) * 0.02 + 1e-4,
          "param " + id + " restored for the drain test");
    }

    System.out.println("focus loss");
    keys.keyDown('a');
    check(keys.heldCount() == 1, "one note held before focus loss");
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

  private static void click(KeyboardPanel keys, int id, int x, int y) {
    keys.dispatchEvent(new MouseEvent(keys, id, 0, 0, x, y, 1, false));
  }

  private static void renderPng(int width, int height, String path) throws Exception {
    SwingUtilities.invokeAndWait(() -> {
      KeyboardPanel p = new KeyboardPanel();
      p.setSize(width, height);
      java.awt.image.BufferedImage img =
          new java.awt.image.BufferedImage(width, height, java.awt.image.BufferedImage.TYPE_INT_RGB);
      p.paint(img.createGraphics());
      try {
        javax.imageio.ImageIO.write(img, "png", new java.io.File(path));
      } catch (java.io.IOException e) {
        throw new RuntimeException(e);
      }
    });
  }
}
