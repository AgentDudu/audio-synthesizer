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
 * Full 88-key piano (A0-C8). Mouse clicks, drags, and the computer keyboard all drive the same
 * noteOn/noteOff path. The whole keyboard is always on screen and clickable; the computer keys
 * cover one 17-semitone window of it, moved with left/right arrows. Esc kills every held note.
 */
final class KeyboardPanel extends JComponent {
  static final int LOWEST_NOTE = 21;   // A0
  static final int HIGHEST_NOTE = 108; // C8
  static final int WHITE_WIDTH = 18;
  static final int BLACK_WIDTH = 12;
  static final int KEY_HEIGHT = 150;
  static final int BLACK_HEIGHT = 92;
  static final int BASE_NOTE = 60;  // C4, where the computer-key row starts

  // Tracker-style computer key map: position in this string is the semitone above BASE_NOTE.
  private static final String KEY_ROWS = "awsedftgyhujkolp;";
  private static final int[] WHITE_SEMITONES = {0, 2, 4, 5, 7, 9, 11};

  /** Every white-key note of the piano, low to high. Built once; the layout is derived from it. */
  private static final int[] WHITE_NOTES = buildWhiteNotes();

  static final int MIN_OCTAVE_SHIFT = -3;  // keeps the whole computer-key row inside the piano
  static final int MAX_OCTAVE_SHIFT = 2;

  private final Set<Integer> held = new LinkedHashSet<>();
  private int mouseNote = -1;
  private int octaveShift = 0;

  KeyboardPanel() {
    setFocusable(true);
    setPreferredSize(new Dimension(WHITE_NOTES.length * WHITE_WIDTH, KEY_HEIGHT));

    MouseAdapter mouse = new MouseAdapter() {
      @Override
      public void mousePressed(MouseEvent e) {
        setMouseNote(noteAt(e.getX(), e.getY()));
      }

      @Override
      public void mouseReleased(MouseEvent e) {
        setMouseNote(-1);
      }

      @Override
      public void mouseDragged(MouseEvent e) {
        setMouseNote(noteAt(e.getX(), e.getY()));
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

  static int blackLeft(int whiteIndex) {
    return (whiteIndex + 1) * WHITE_WIDTH - BLACK_WIDTH / 2;
  }

  static boolean hasBlack(int whiteIndex) {
    return whiteIndex + 1 < WHITE_NOTES.length
        && WHITE_NOTES[whiteIndex + 1] - WHITE_NOTES[whiteIndex] == 2;
  }

  /** Left pixel edge of the white key holding `note`. */
  static int leftOfNote(int note) {
    for (int w = 0; w < WHITE_NOTES.length; ++w) {
      if (WHITE_NOTES[w] <= note && (w + 1 == WHITE_NOTES.length || WHITE_NOTES[w + 1] > note)) {
        return w * WHITE_WIDTH;
      }
    }
    return 0;
  }

  static int keyNote(char keyChar, int octaveShift) {
    int semitone = KEY_ROWS.indexOf(Character.toLowerCase(keyChar));
    return semitone < 0 ? -1 : BASE_NOTE + 12 * octaveShift + semitone;
  }

  /** Note under a pixel, or -1. Black keys win over the white key behind them. */
  static int noteAt(int x, int y) {
    if (x < 0 || x >= WHITE_NOTES.length * WHITE_WIDTH || y < 0 || y >= KEY_HEIGHT) return -1;
    if (y < BLACK_HEIGHT) {
      for (int w = 0; w < WHITE_NOTES.length; ++w) {
        if (hasBlack(w) && x >= blackLeft(w) && x < blackLeft(w) + BLACK_WIDTH) {
          return WHITE_NOTES[w] + 1;
        }
      }
    }
    return WHITE_NOTES[x / WHITE_WIDTH];
  }

  @Override
  protected void paintComponent(Graphics g0) {
    Graphics2D g = (Graphics2D) g0;
    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
    g.setColor(getBackground());
    g.fillRect(0, 0, getWidth(), getHeight());

    for (int w = 0; w < WHITE_NOTES.length; ++w) {
      int note = WHITE_NOTES[w];
      int x = w * WHITE_WIDTH;
      g.setColor(held.contains(note) ? new Color(150, 200, 255) : Color.WHITE);
      g.fillRect(x, 0, WHITE_WIDTH - 1, KEY_HEIGHT);
      g.setColor(new Color(90, 90, 90));
      g.drawRect(x, 0, WHITE_WIDTH - 1, KEY_HEIGHT - 1);
      if (note % 12 == 0) {  // mark the Cs so the keyboard is readable
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 10));
        g.drawString("C" + (note / 12 - 1), x + 2, KEY_HEIGHT - 16);
      }
    }

    for (int w = 0; w < WHITE_NOTES.length; ++w) {
      if (!hasBlack(w)) continue;
      int note = WHITE_NOTES[w] + 1;
      g.setColor(held.contains(note) ? new Color(40, 100, 190) : new Color(25, 25, 25));
      g.fillRect(blackLeft(w), 0, BLACK_WIDTH, BLACK_HEIGHT);
    }

    // Show which slice of the piano the computer keys currently play.
    int base = BASE_NOTE + 12 * octaveShift;
    g.setColor(new Color(30, 130, 230));
    g.setStroke(new BasicStroke(3f));
    g.drawLine(leftOfNote(base), KEY_HEIGHT - 3,
        leftOfNote(base + KEY_ROWS.length() - 1) + WHITE_WIDTH, KEY_HEIGHT - 3);
  }
}
