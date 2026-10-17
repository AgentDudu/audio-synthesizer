package synth;

import java.awt.event.FocusEvent;
import java.awt.event.MouseEvent;
import java.util.Set;
import java.util.TreeSet;
import javax.sound.midi.MidiEvent;
import javax.sound.midi.MidiMessage;
import javax.sound.midi.MidiSystem;
import javax.sound.midi.Receiver;
import javax.sound.midi.Sequence;
import javax.sound.midi.Sequencer;
import javax.sound.midi.ShortMessage;
import javax.sound.midi.Track;
import javax.swing.SwingUtilities;

/**
 * Headless check of the UI layer: computer-key map, pixel-to-note geometry at several window
 * sizes (the keyboard scales with the frame), mouse drag, focus-loss note-off, the eight param
 * sliders, the scope ring and its paint, the MIDI parsing and the built-in MIDI-file source, and
 * the engine actually holding the right notes and voices. Run: java -cp build/classes synth.UiSelfTest [png]
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

    System.out.println("scope");
    final ScopePanel[] scopeBox = new ScopePanel[1];
    SwingUtilities.invokeAndWait(() -> scopeBox[0] = new ScopePanel());
    ScopePanel scope = scopeBox[0];
    check(scope.running(), "scope timer runs");
    check(scope.sample() == ScopePanel.SAMPLES, "scope copies the full " + ScopePanel.SAMPLES + "-sample ring");
    check(scope.peak() < 1e-3f, "scope reads silence after the drain, peak " + scope.peak());
    Engine.noteOn(60, 110);
    Thread.sleep(80);
    scope.sample();
    check(scope.peak() > 0.01f, "scope sees a sustained note, peak " + scope.peak());
    java.awt.image.BufferedImage wave =
        paintScope(scope, 900, ScopePanel.PREF_HEIGHT, "build/scope.png");
    check(bluePixels(wave) > 0, "wave mode paints, " + bluePixels(wave) + " px");
    paintScope(scope, KeyboardPanel.MIN_WIDTH, ScopePanel.MIN_HEIGHT, "build/scope-small.png");

    // M8: per-key reverse waterfall. Columns must sit exactly over their keys.
    scope.setMode(ScopePanel.Mode.WATERFALL);
    check(scope.mode() == ScopePanel.Mode.WATERFALL, "waterfall mode selected");
    scope.sample();
    check(scope.columnLevel(60) > 0.01f, "column for the held note, level " + scope.columnLevel(60));
    check(scope.columnLevel(61) == 0f, "no column for an unheld note");
    check(scope.historyRows() == scope.getHeight(),
        "one history row per pixel row, got " + scope.historyRows() + " of " + scope.getHeight());
    int[][] sizes = {{900, ScopePanel.PREF_HEIGHT}, {420, 80}, {KeyboardPanel.MIN_WIDTH, ScopePanel.MIN_HEIGHT}};
    for (int[] wh : sizes) {
      SwingUtilities.invokeAndWait(() -> scope.setSize(wh[0], wh[1]));
      for (int i = 0; i < 40; ++i) scope.sample();  // 40 rows of history at this size
      java.awt.image.BufferedImage wf =
          paintScope(scope, wh[0], wh[1], "build/waterfall-" + wh[0] + "x" + wh[1] + ".png");
      int keyX = Math.round(KeyboardPanel.leftOfNote(60, wh[0]) + KeyboardPanel.whiteWidth(wh[0]) / 2f);
      int otherX = Math.round(KeyboardPanel.leftOfNote(62, wh[0]) + KeyboardPanel.whiteWidth(wh[0]) / 2f);
      check(isBlue(wf, keyX, wh[1] - 1), "waterfall column sits over key 60 at " + wh[0] + "x" + wh[1] + ", x=" + keyX);
      check(!isBlue(wf, otherX, wh[1] - 1), "no waterfall column over the unheld key 62 at " + wh[0] + "x" + wh[1]);
      int minPx = 30 * Math.max(1, Math.round(KeyboardPanel.whiteWidth(wh[0])));
      check(bluePixels(wf) >= minPx,
          "waterfall column is 30+ rows tall at " + wh[0] + "x" + wh[1] + ", " + bluePixels(wf) + " px (min " + minPx + ")");
    }
    scope.setMode(ScopePanel.Mode.WAVE);
    check(scope.mode() == ScopePanel.Mode.WAVE, "wave mode selected again");
    for (int i = 0; i < 60; ++i) {
      scope.sample();
      Thread.sleep(16);
    }
    check(Engine.underrunCount() == 0, "60 scope polls cause no underruns, got " + Engine.underrunCount());
    check(Engine.activeVoices() == 1, "note still sounding while the scope polls, got " + Engine.activeVoices());
    Engine.noteOff(60);
    Thread.sleep(700);
    scope.sample();
    check(scope.columnLevel(60) == 0f, "column drains after the release, level " + scope.columnLevel(60));
    SwingUtilities.invokeAndWait(scope::stop);
    check(!scope.running(), "scope timer stops");

    System.out.println("output");
    String report = "rtaudio 6.0.1 api=7\ndevices=3\n"
        + "  id=129 24E3 (NVIDIA High Definition Audio) out=2 in=0 rate=48000\n"
        + "  id=137 Mic in at rear panel (Pink) (Realtek High Definition Audio) out=0 in=2 rate=192000\n"
        + "  id=132 Speakers (Realtek High Definition Audio) out=2 in=0 rate=192000 DEFAULT-OUT\n";
    java.util.List<Engine.Device> parsed = Engine.parseDevices(report);
    check(parsed.size() == 2, "parse drops the input-only endpoint, got " + parsed.size());
    check(parsed.get(0).id() == 129 && parsed.get(1).id() == 132, "device ids parse");
    check(parsed.get(0).name().equals("24E3 (NVIDIA High Definition Audio)"),
        "device name with spaces and parens parses, got " + parsed.get(0).name());
    check(parsed.get(1).defaultOut() && parsed.get(1).rate() == 192000 && !parsed.get(0).defaultOut(),
        "default flag and rate parse");

    final OutputPanel[] panelBox = new OutputPanel[1];
    SwingUtilities.invokeAndWait(
        () -> panelBox[0] = new OutputPanel(App.SAMPLE_RATE, App.BUFFER_FRAMES));
    OutputPanel panel = panelBox[0];
    Engine.Device def = panel.current();
    check(panel.devices().size() >= 2, "this machine exposes " + panel.devices().size() + " output endpoints");
    check(def != null && def.defaultOut(), "the picker starts on the default output device, id "
        + (def == null ? "?" : def.id()));
    long underrunsBefore = Engine.underrunCount();
    Engine.noteOn(60, 100);
    Thread.sleep(80);
    int applied = 0, refused = 0;
    for (Engine.Device d : panel.devices()) {
      if (d.equals(def)) continue;
      if (panel.apply(d)) {
        if (applied == 0) {  // prove the new stream really renders, once
          Engine.noteOff(60);
          Thread.sleep(700);
          Engine.noteOn(64, 100);
          Thread.sleep(120);
          check(Engine.activeVoices() == 1,
              "the stream on id " + d.id() + " renders notes, voices " + Engine.activeVoices());
          Engine.noteOff(64);
          Thread.sleep(700);
        }
        ++applied;
      } else {
        check(panel.current().equals(def), "id " + d.id() + " refused to open and the picker snapped back");
        ++refused;
      }
    }
    check(applied >= 1, applied + " other endpoints opened, " + refused + " refused");
    check(panel.apply(def), "switched back to the default endpoint");
    check(panel.current().equals(def), "the picker shows the live device, id " + panel.current().id());
    Engine.noteOn(60, 100);
    Thread.sleep(120);
    check(Engine.activeVoices() == 1, "the default endpoint still renders, voices " + Engine.activeVoices());
    Engine.noteOff(60);
    Thread.sleep(700);
    check(Engine.underrunCount() == underrunsBefore,
        "device switching causes no underruns, got " + Engine.underrunCount());

    System.out.println("buffer size");
    check(panel.currentFrames() == App.BUFFER_FRAMES,
        "the panel tracks the requested size, " + panel.currentFrames());
    for (int frames : OutputPanel.BUFFER_CHOICES) {
      long before = Engine.underrunCount();
      check(panel.setBuffer(frames), "asked for " + frames + " frames, stream opened "
          + Engine.bufferFrames() + " frames");
      Engine.noteOn(60, 100);
      Thread.sleep(120);
      check(Engine.activeVoices() == 1, frames + " frames renders notes, voices " + Engine.activeVoices());
      Engine.noteOff(60);
      Thread.sleep(700);
      check(Engine.underrunCount() == before,
          frames + " frames causes no underruns, got " + (Engine.underrunCount() - before));
    }
    check(panel.setBuffer(App.BUFFER_FRAMES), "back to " + App.BUFFER_FRAMES + " frames");
    check(panel.current().equals(def), "the buffer change kept the same endpoint, id " + panel.current().id());

    System.out.println("midi");
    check(MidiIn.translate(new ShortMessage(0x90, 60, 100), MidiIn.ALL_CHANNELS).equals(new MidiIn.Note(60, 100)),
        "note-on parses with velocity");
    check(MidiIn.translate(new ShortMessage(0x90, 60, 0), MidiIn.ALL_CHANNELS).equals(new MidiIn.Note(60, -1)),
        "note-on with velocity 0 means note-off");
    check(MidiIn.translate(new ShortMessage(0x80, 64, 40), MidiIn.ALL_CHANNELS).equals(new MidiIn.Note(64, -1)),
        "note-off parses");
    check(MidiIn.translate(new ShortMessage(0x91, 64, 90), 0) == null, "other channel filtered out");
    check(MidiIn.translate(new ShortMessage(0x91, 64, 90), 1).equals(new MidiIn.Note(64, 90)),
        "filtered channel passes when it matches");
    check(MidiIn.translate(new ShortMessage(0xB0, 7, 100), MidiIn.ALL_CHANNELS) == null,
        "control change ignored");

    Thread.sleep(600);  // let the scope section's release finish before counting voices
    Receiver rec = MidiIn.receiver(MidiIn.ALL_CHANNELS, keys);
    rec.send(new ShortMessage(0x90, 60, 100), -1);
    rec.send(new ShortMessage(0x90, 64, 100), -1);
    Thread.sleep(120);
    check(Engine.activeVoices() == 2, "synthetic receiver holds 2 voices, got " + Engine.activeVoices());
    check(keys.remoteNotes().equals(Set.of(60, 64)),
        "the on-screen keyboard lights for MIDI notes, got " + keys.remoteNotes());
    rec.send(new ShortMessage(0x80, 60, 0), -1);
    rec.send(new ShortMessage(0x80, 64, 0), -1);
    Thread.sleep(700);
    check(Engine.activeVoices() == 0, "synthetic receiver pairs every note-off, got " + Engine.activeVoices());
    check(keys.remoteNotes().isEmpty(), "MIDI note-offs unlight the keyboard, got " + keys.remoteNotes());

    // A file that is still playing must survive this window losing focus.
    rec.send(new ShortMessage(0x90, 67, 100), -1);
    SwingUtilities.invokeAndWait(() -> {
      for (java.awt.event.FocusListener l : keys.getFocusListeners()) {
        l.focusLost(new FocusEvent(keys, FocusEvent.FOCUS_LOST));
      }
    });
    Thread.sleep(80);
    check(keys.remoteNotes().equals(Set.of(67)), "focus loss leaves a MIDI note lit, got " + keys.remoteNotes());
    check(Engine.activeVoices() == 1, "focus loss does not silence a MIDI note, got " + Engine.activeVoices());
    rec.send(new ShortMessage(0x80, 67, 0), -1);
    Thread.sleep(600);
    check(litPixels(60) > litPixels(-1) + 500,
        "a MIDI note really paints lit: " + litPixels(60) + " vs " + litPixels(-1) + " lit pixels");
    keys.showRemoteNote(70, true);
    keys.clearRemoteNotes();
    check(keys.remoteNotes().isEmpty(), "clearRemoteNotes unlights everything (used by midi stop)");

    // Built-in MIDI-file source: write a sequence, read it back, play it.
    int[] seqNotes = {60, 64, 67, 72, 76};
    int[] seqVels = {100, 60, 127, 1, 90};
    java.io.File mid = new java.io.File("build/test.mid");
    Sequence file = new Sequence(Sequence.PPQ, 480);
    Track tr = file.createTrack();
    for (int i = 0; i < seqNotes.length; ++i) {
      tr.add(new MidiEvent(new ShortMessage(0x90, seqNotes[i], seqVels[i]), i * 480));
      tr.add(new MidiEvent(new ShortMessage(0x80, seqNotes[i], 0), i * 480 + 360));
    }
    MidiSystem.write(file, 1, mid);
    check(mid.length() > 0, "wrote build/test.mid (" + mid.length() + " bytes)");

    // Velocity and pairing, observed at the Receiver boundary where it is deterministic.
    java.util.List<MidiIn.Note> seen = new java.util.ArrayList<>();
    Sequencer probe = MidiSystem.getSequencer(false);
    probe.open();
    probe.setSequence(MidiSystem.getSequence(mid));
    probe.getTransmitter().setReceiver(new Receiver() {
      @Override
      public void send(MidiMessage m, long ts) {
        MidiIn.Note n = MidiIn.translate(m, MidiIn.ALL_CHANNELS);
        if (n != null) seen.add(n);
      }

      @Override
      public void close() {}
    });
    probe.start();
    while (probe.isRunning()) Thread.sleep(50);
    MidiIn.closeQuietly(probe);
    check(seen.size() == seqNotes.length * 2, "file source emits one note-off per note-on, got " + seen.size());
    boolean mapping = true;
    for (int i = 0; i < seqNotes.length && i < seen.size() / 2; ++i) {
      mapping &= seen.get(2 * i).equals(new MidiIn.Note(seqNotes[i], seqVels[i]));
      mapping &= seen.get(2 * i + 1).equals(new MidiIn.Note(seqNotes[i], -1));
    }
    check(mapping, "notes and velocities survive the file path, got " + seen);

    // Same file through the real engine path.
    Sequencer player = MidiIn.playFile(mid.getPath(), MidiIn.ALL_CHANNELS, keys);
    Thread.sleep(600);
    check(Engine.activeVoices() >= 1, "the .mid file drives the engine, voices " + Engine.activeVoices());
    check(!keys.remoteNotes().isEmpty(), "the .mid file lights the keyboard, got " + keys.remoteNotes());
    while (player.isRunning()) Thread.sleep(50);
    Thread.sleep(600);
    check(Engine.activeVoices() == 0, "the .mid file leaves no stuck voices, got " + Engine.activeVoices());
    check(keys.remoteNotes().isEmpty(), "the .mid file leaves no stuck lights, got " + keys.remoteNotes());
    check(Engine.underrunCount() == 0, "no underruns while the .mid plays, got " + Engine.underrunCount());
    MidiIn.closeQuietly(player);

    // Two producers at once (EDT + MIDI receiver). Paced so the 256-slot ring never fills.
    Runnable spam = () -> {
      for (int i = 0; i < 100; ++i) {
        Engine.noteOn(60, 90);
        Engine.noteOff(60);
        if (i % 8 == 0) {
          try {
            Thread.sleep(3);
          } catch (InterruptedException ignored) {
          }
        }
      }
    };
    Thread pa = new Thread(spam), pb = new Thread(spam);
    pa.start();
    pb.start();
    pa.join();
    pb.join();
    Thread.sleep(700);
    check(Engine.activeVoices() == 0, "200 events from each of two threads drain to 0, got " + Engine.activeVoices());
    check(Engine.underrunCount() == 0, "two producers cause no underruns, got " + Engine.underrunCount());

    Engine.stop();
    check(Engine.bufferFrames() == 0, "a closed stream reports no buffer size");

    System.out.println(failures == 0 ? "UI self-test OK" : "UI self-test FAILURES=" + failures);
    System.exit(failures == 0 ? 0 : 1);
  }

  private static void click(KeyboardPanel keys, int id, int x, int y) {
    keys.dispatchEvent(new MouseEvent(keys, id, 0, 0, x, y, 1, false));
  }

  /** Lit white-key pixels on a fresh keyboard, with one remote note lit (note < 0 = none). */
  private static int litPixels(int note) throws Exception {
    final int[] out = new int[1];
    SwingUtilities.invokeAndWait(() -> {
      KeyboardPanel p = new KeyboardPanel();
      p.setSize(BIG_W, BIG_H);
      if (note >= 0) p.showRemoteNote(note, true);
      java.awt.image.BufferedImage img =
          new java.awt.image.BufferedImage(BIG_W, BIG_H, java.awt.image.BufferedImage.TYPE_INT_RGB);
      p.paint(img.createGraphics());
      int n = 0;
      for (int y = 0; y < BIG_H; ++y) {
        for (int x = 0; x < BIG_W; ++x) {
          if ((img.getRGB(x, y) & 0xFFFFFF) == 0x96C8FF) ++n;
        }
      }
      out[0] = n;
    });
    return out[0];
  }

  private static java.awt.image.BufferedImage paintScope(ScopePanel scope, int width, int height,
                                                         String path) throws Exception {
    final java.awt.image.BufferedImage[] out = new java.awt.image.BufferedImage[1];
    SwingUtilities.invokeAndWait(() -> {
      scope.setSize(width, height);
      java.awt.image.BufferedImage img =
          new java.awt.image.BufferedImage(width, height, java.awt.image.BufferedImage.TYPE_INT_RGB);
      scope.paint(img.createGraphics());
      try {
        javax.imageio.ImageIO.write(img, "png", new java.io.File(path));
      } catch (java.io.IOException e) {
        throw new RuntimeException(e);
      }
      out[0] = img;
    });
    return out[0];
  }

  /** The blue used by both scope modes: blue clearly dominates red. Centre line and background do not. */
  private static boolean isBlue(java.awt.image.BufferedImage img, int x, int y) {
    int rgb = img.getRGB(x, y) & 0xFFFFFF;
    return (rgb & 255) > ((rgb >> 16) & 255) + 30;
  }

  private static int bluePixels(java.awt.image.BufferedImage img) {
    int n = 0;
    for (int y = 0; y < img.getHeight(); ++y)
      for (int x = 0; x < img.getWidth(); ++x)
        if (isBlue(img, x, y)) ++n;
    return n;
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
