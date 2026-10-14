package synth;

import java.awt.Color;
import java.awt.Dimension;
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
 * Two-octave piano. Mouse clicks, drags, and the computer keyboard all drive the same
 * noteOn/noteOff path. Left/right arrows shift octave, Esc kills every held note.
 */
final class KeyboardPanel extends JComponent {
  static final int WHITE_KEYS = 14;  // two octaves
  static final int WHITE_WIDTH = 34;
  static final int BLACK_WIDTH = 22;
  static final int KEY_HEIGHT = 150;
  static final int BLACK_HEIGHT = 92;
  static final int BASE_NOTE = 60;  // C4

  // Tracker-style computer key map: position in this string is the semitone above the base note.
  private static final String KEY_ROWS = "awsedftgyhujkolp;";
  private static final int[] WHITE_SEMITONES = {0, 2, 4, 5, 7, 9, 11};

  private final Set<Integer> held = new LinkedHashSet<>();
  private int mouseNote = -1;
  private int octaveShift = 0;

  KeyboardPanel() {
    setFocusable(true);
    setPreferredSize(new Dimension(WHITE_KEYS * WHITE_WIDTH, KEY_HEIGHT));

    MouseAdapter mouse = new MouseAdapter() {
      @Override
      public void mousePressed(MouseEvent e) {
        setMouseNote(noteAt(e.getX(), e.getY(), octaveShift));
      }

      @Override
      public void mouseReleased(MouseEvent e) {
        setMouseNote(-1);
      }

      @Override
      public void mouseDragged(MouseEvent e) {
        setMouseNote(noteAt(e.getX(), e.getY(), octaveShift));
      }
    };
    addMouseListener(mouse);
    addMouseMotionListener(mouse);

    addKeyListener(new KeyAdapter() {
      @Override
      public void keyPressed(KeyEvent e) {
        switch (e.getKeyCode()) {
          case KeyEvent.VK_LEFT:
            octaveShift = Math.max(octaveShift - 1, -3);
            repaint();
            return;
          case KeyEvent.VK_RIGHT:
            octaveShift = Math.min(octaveShift + 1, 3);
            repaint();
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
    octaveShift = Math.max(Math.min(shift, 3), -3);
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

  static int keyNote(char keyChar, int octaveShift) {
    int semitone = KEY_ROWS.indexOf(Character.toLowerCase(keyChar));
    return semitone < 0 ? -1 : BASE_NOTE + 12 * octaveShift + semitone;
  }

  static int whiteNote(int whiteIndex, int octaveShift) {
    return BASE_NOTE + 12 * octaveShift + 12 * (whiteIndex / 7) + WHITE_SEMITONES[whiteIndex % 7];
  }

  static int blackLeft(int whiteIndex) {
    return (whiteIndex + 1) * WHITE_WIDTH - BLACK_WIDTH / 2;
  }

  static boolean hasBlack(int whiteIndex, int octaveShift) {
    return whiteIndex + 1 < WHITE_KEYS
        && whiteNote(whiteIndex + 1, octaveShift) - whiteNote(whiteIndex, octaveShift) == 2;
  }

  /** Note under a pixel, or -1. Black keys win over the white key behind them. */
  static int noteAt(int x, int y, int octaveShift) {
    if (x < 0 || x >= WHITE_KEYS * WHITE_WIDTH || y < 0 || y >= KEY_HEIGHT) return -1;
    if (y < BLACK_HEIGHT) {
      for (int w = 0; w < WHITE_KEYS; ++w) {
        if (hasBlack(w, octaveShift) && x >= blackLeft(w) && x < blackLeft(w) + BLACK_WIDTH) {
          return whiteNote(w, octaveShift) + 1;
        }
      }
    }
    return whiteNote(x / WHITE_WIDTH, octaveShift);
  }

  @Override
  protected void paintComponent(Graphics g0) {
    Graphics2D g = (Graphics2D) g0;
    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
    g.setColor(getBackground());
    g.fillRect(0, 0, getWidth(), getHeight());

    for (int w = 0; w < WHITE_KEYS; ++w) {
      int note = whiteNote(w, octaveShift);
      int x = w * WHITE_WIDTH;
      g.setColor(held.contains(note) ? new Color(150, 200, 255) : Color.WHITE);
      g.fillRect(x, 0, WHITE_WIDTH - 1, KEY_HEIGHT);
      g.setColor(new Color(90, 90, 90));
      g.drawRect(x, 0, WHITE_WIDTH - 1, KEY_HEIGHT - 1);
      if (note % 12 == 0) {  // mark the Cs so the keyboard is readable
        g.drawString("C" + (note / 12 - 1), x + 12, KEY_HEIGHT - 12);
      }
    }

    for (int w = 0; w < WHITE_KEYS; ++w) {
      if (!hasBlack(w, octaveShift)) continue;
      int note = whiteNote(w, octaveShift) + 1;
      g.setColor(held.contains(note) ? new Color(40, 100, 190) : new Color(25, 25, 25));
      g.fillRect(blackLeft(w), 0, BLACK_WIDTH, BLACK_HEIGHT);
    }
  }
}
