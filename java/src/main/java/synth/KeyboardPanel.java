package synth;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.LinkedHashSet;
import java.util.Set;
import javax.swing.JComponent;

/**
 * Full 88-key piano (A0-C8) that scales with the window: key width, key height, black-key size and
 * the hit-testing all derive from the component size, so clicking stays accurate at any size.
 * Mouse clicks, drags, and the computer keyboard drive the same noteOn/noteOff path. The computer
 * keys cover one 17-semitone window of the piano, moved with left/right arrows. Esc kills all notes.
 */
final class KeyboardPanel extends JComponent {
  static final int LOWEST_NOTE = 21;   // A0
  static final int HIGHEST_NOTE = 108; // C8
  static final int BASE_NOTE = 60;  // C4, where the computer-key row starts
  static final int PREFERRED_WIDTH = 936;
  static final int PREFERRED_HEIGHT = 150;
  static final int MIN_WIDTH = 320;   // below this the keys are unusably thin
  static final int MIN_HEIGHT = 60;

  private static final float BLACK_WIDTH_RATIO = 0.66f;   // black width / white width
  private static final float BLACK_HEIGHT_RATIO = 0.61f;  // black height / keyboard height

  // Tracker-style computer key map: position in this string is the semitone above BASE_NOTE.
  private static final String KEY_ROWS = "awsedftgyhujkolp;";
  private static final int[] WHITE_SEMITONES = {0, 2, 4, 5, 7, 9, 11};

  /** Every white-key note of the piano, low to high. Built once; the layout is derived from it. */
  private static final int[] WHITE_NOTES = buildWhiteNotes();

  static final int MIN_OCTAVE_SHIFT = -3;  // keeps the whole computer-key row inside the piano
  static final int MAX_OCTAVE_SHIFT = 2;

  private final Set<Integer> held = new LinkedHashSet<>();
  // Notes sounding from another source (the MIDI-file player). Lit on screen, but deliberately not
  // in `held`: focus loss and Esc must not silence a file that is still playing, and the mouse/key
  // bookkeeping must stay about this window's own input. Written from the sequencer thread.
  private final Set<Integer> remote = java.util.concurrent.ConcurrentHashMap.newKeySet();
  private int mouseNote = -1;
  private int octaveShift = 0;

  KeyboardPanel() {
    setFocusable(true);
    setPreferredSize(new Dimension(PREFERRED_WIDTH, PREFERRED_HEIGHT));
    setMinimumSize(new Dimension(MIN_WIDTH, MIN_HEIGHT));

    MouseAdapter mouse = new MouseAdapter() {
      @Override
      public void mousePressed(MouseEvent e) {
        setMouseNote(noteAt(e.getX(), e.getY(), getWidth(), getHeight()));
      }

      @Override
      public void mouseReleased(MouseEvent e) {
        setMouseNote(-1);
      }

      @Override
      public void mouseDragged(MouseEvent e) {
        setMouseNote(noteAt(e.getX(), e.getY(), getWidth(), getHeight()));
      }
    };
    addMouseListener(mouse);
    addMouseMotionListener(mouse);

    addKeyListener(new KeyAdapter() {
      @Override
      public void keyPressed(KeyEvent e) {
        switch (e.getKeyCode()) {
          case KeyEvent.VK_LEFT:
            setOctaveShift(octaveShift - 1);
            return;
          case KeyEvent.VK_RIGHT:
            setOctaveShift(octaveShift + 1);
            return;
          case KeyEvent.VK_ESCAPE:
            allNotesOff();
            return;
          default:
            break;
        }
        keyDown(e.getKeyChar());
      }

      @Override
      public void keyReleased(KeyEvent e) {
        keyUp(e.getKeyChar());
      }
    });

    // Stuck notes are a real hazard: focus loss, window switch, or a lost key-release.
    addFocusListener(new FocusAdapter() {
      @Override
      public void focusLost(FocusEvent e) {
        allNotesOff();
      }
    });
  }

  /** Computer-key input. Package-private so the self-test can drive it without a focus owner. */
  void keyDown(char keyChar) {
    int note = keyNote(keyChar, octaveShift);
    if (note >= 0) press(note);
  }

  void keyUp(char keyChar) {
    int note = keyNote(keyChar, octaveShift);
    if (note >= 0) release(note);
  }

  void setOctaveShift(int shift) {
    octaveShift = Math.max(Math.min(shift, MAX_OCTAVE_SHIFT), MIN_OCTAVE_SHIFT);
    repaint();
  }

  int octaveShift() {
    return octaveShift;
  }

  int heldCount() {
    return held.size();
  }

  java.util.Set<Integer> heldNotes() {
    return held;
  }

  /** Light or unlight a note owned by another source. Thread-safe (repaint is). */
  void showRemoteNote(int note, boolean on) {
    if (on) remote.add(note);
    else remote.remove(note);
    repaint();
  }

  void clearRemoteNotes() {
    remote.clear();
    repaint();
  }

  Set<Integer> remoteNotes() {
    return remote;
  }

  private boolean lit(int note) {
    return held.contains(note) || remote.contains(note);
  }

  private void setMouseNote(int note) {
    if (note == mouseNote) return;
    if (mouseNote >= 0) release(mouseNote);
    mouseNote = note;
    if (note >= 0) press(note);
  }

  private void press(int note) {
    if (note < 0 || !held.add(note)) return;  // key repeat must not retrigger
    Engine.noteOn(note, 100);
    repaint();
  }

  private void release(int note) {
    if (!held.remove(note)) return;
    Engine.noteOff(note);
    repaint();
  }

  private void allNotesOff() {
    for (int note : held) Engine.noteOff(note);
    held.clear();
    mouseNote = -1;
    repaint();
  }

  private static boolean isWhite(int note) {
    for (int semitone : WHITE_SEMITONES) {
      if (semitone == note % 12) return true;
    }
    return false;
  }

  static boolean isBlack(int note) { return !isWhite(note); }

  private static int[] buildWhiteNotes() {
    int count = 0;
    for (int note = LOWEST_NOTE; note <= HIGHEST_NOTE; ++note) {
      if (isWhite(note)) ++count;
    }
    int[] notes = new int[count];
    int i = 0;
    for (int note = LOWEST_NOTE; note <= HIGHEST_NOTE; ++note) {
      if (isWhite(note)) notes[i++] = note;
    }
    return notes;
  }

  static int whiteCount() {
    return WHITE_NOTES.length;
  }

  static int whiteNote(int whiteIndex) {
    return WHITE_NOTES[whiteIndex];
  }

  static boolean hasBlack(int whiteIndex) {
    return whiteIndex + 1 < WHITE_NOTES.length
        && WHITE_NOTES[whiteIndex + 1] - WHITE_NOTES[whiteIndex] == 2;
  }

  // Geometry is a function of the component size, so painting and hit-testing never disagree.
  static float whiteWidth(int width) {
    return width / (float) WHITE_NOTES.length;
  }

  static float blackWidth(int width) {
    return whiteWidth(width) * BLACK_WIDTH_RATIO;
  }

  static int blackHeight(int height) {
    return Math.round(height * BLACK_HEIGHT_RATIO);
  }

  static float blackLeft(int whiteIndex, int width) {
    return (whiteIndex + 1) * whiteWidth(width) - blackWidth(width) / 2f;
  }

  /** Index of the white key `note` sits on, or -1 below the keyboard. */
  static int whiteIndexOf(int note) {
    for (int w = 0; w < WHITE_NOTES.length; ++w) {
      if (WHITE_NOTES[w] <= note && (w + 1 == WHITE_NOTES.length || WHITE_NOTES[w + 1] > note)) {
        return w;
      }
    }
    return -1;
  }

  /** Left pixel edge of the white key holding `note`. */
  static float leftOfNote(int note, int width) {
    int w = whiteIndexOf(note);
    return w < 0 ? 0 : w * whiteWidth(width);
  }

  static int keyNote(char keyChar, int octaveShift) {
    int semitone = KEY_ROWS.indexOf(Character.toLowerCase(keyChar));
    return semitone < 0 ? -1 : BASE_NOTE + 12 * octaveShift + semitone;
  }

  /** Note under a pixel of a keyboard that size, or -1. Black keys win over the white behind them. */
  static int noteAt(int x, int y, int width, int height) {
    if (x < 0 || x >= width || y < 0 || y >= height) return -1;
    if (y < blackHeight(height)) {
      for (int w = 0; w + 1 < WHITE_NOTES.length; ++w) {
        if (!hasBlack(w)) continue;
        float left = blackLeft(w, width);
        if (x >= left && x < left + blackWidth(width)) return WHITE_NOTES[w] + 1;
      }
    }
    return WHITE_NOTES[Math.min((int) (x / whiteWidth(width)), WHITE_NOTES.length - 1)];
  }

  @Override
  protected void paintComponent(Graphics g0) {
    Graphics2D g = (Graphics2D) g0;
    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
    int width = getWidth();
    int height = getHeight();
    float whiteWidth = whiteWidth(width);
    int blackHeight = blackHeight(height);

    g.setColor(getBackground());
    g.fillRect(0, 0, width, height);

    int labelSize = Math.max(8, Math.min(14, Math.round(whiteWidth * 0.6f)));
    g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, labelSize));

    for (int w = 0; w < WHITE_NOTES.length; ++w) {
      int note = WHITE_NOTES[w];
      int x = Math.round(w * whiteWidth);
      int next = Math.round((w + 1) * whiteWidth);
      g.setColor(lit(note) ? new Color(150, 200, 255) : Color.WHITE);
      g.fillRect(x, 0, next - x - 1, height);
      g.setColor(new Color(90, 90, 90));
      g.drawRect(x, 0, next - x - 1, height - 1);
      if (note % 12 == 0 && next - x > labelSize * 2) {  // label the Cs while they still fit
        g.drawString("C" + (note / 12 - 1), x + 2, height - 8);
      }
    }

    for (int w = 0; w + 1 < WHITE_NOTES.length; ++w) {
      if (!hasBlack(w)) continue;
      int note = WHITE_NOTES[w] + 1;
      g.setColor(lit(note) ? new Color(40, 100, 190) : new Color(25, 25, 25));
      g.fillRect(Math.round(blackLeft(w, width)), 0, Math.round(blackWidth(width)), blackHeight);
    }

    // Show which slice of the piano the computer keys currently play.
    int base = BASE_NOTE + 12 * octaveShift;
    g.setColor(new Color(30, 130, 230));
    g.setStroke(new BasicStroke(Math.max(2f, height / 50f)));
    g.drawLine(Math.round(leftOfNote(base, width)), height - 1,
        Math.round(leftOfNote(base + KEY_ROWS.length() - 1, width) + whiteWidth), height - 1);
  }
}
