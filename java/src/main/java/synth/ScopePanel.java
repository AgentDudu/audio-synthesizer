package synth;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.util.Arrays;
import javax.swing.JPanel;
import javax.swing.Timer;

/**
 * The strip above the keyboard, redrawn ~60 fps in one of two modes.
 *
 * WAVE: last 1024 output samples from the engine's lock-free scope ring, auto-scaled to the window;
 * below 1e-3 it is treated as silence and only the centre line is drawn.
 *
 * WATERFALL: one column per key, aligned with the key underneath it. The newest row sits at the
 * bottom edge (the keyboard) and history scrolls up and away. Brightness is that note's envelope
 * level, read from the engine's per-block voice snapshot. History length is the panel height in
 * pixel rows, so a taller window shows more of it (~1.9 s at 120 rows and 16 ms).
 *
 * Either way the poll is a copy out of the engine, so the audio thread never waits on the EDT (R9).
 */
final class ScopePanel extends JPanel {
  enum Mode { WAVE, WATERFALL }

  static final int SAMPLES = 1024;  // synth::AudioEngine::kScopeSize
  static final int VOICES = 16;     // synth::Synth::kVoices
  static final int PREF_HEIGHT = 120, MIN_HEIGHT = 40;
  static final int NOTE_COUNT = KeyboardPanel.HIGHEST_NOTE - KeyboardPanel.LOWEST_NOTE + 1;

  private final float[] buf = new float[SAMPLES];
  private final int[] voiceNote = new int[VOICES];
  private final float[] voiceLevel = new float[VOICES];
  private final float[] col = new float[NOTE_COUNT];  // index = note - LOWEST_NOTE
  private float[][] hist = new float[0][];             // ring of rows x NOTE_COUNT
  private int histPos, histRows;
  private Mode mode = Mode.WAVE;

  private int count;
  private final Timer timer;

  ScopePanel() {
    setPreferredSize(new Dimension(KeyboardPanel.MIN_WIDTH, PREF_HEIGHT));
    setMinimumSize(new Dimension(KeyboardPanel.MIN_WIDTH, MIN_HEIGHT));
    timer = new Timer(16, e -> sample());
    timer.start();
  }

  void stop() { timer.stop(); }

  boolean running() { return timer.isRunning(); }

  void setMode(Mode m) { mode = m; repaint(); }

  Mode mode() { return mode; }

  /** Current level shown in a key's column, 0 outside A0-C8. */
  float columnLevel(int note) {
    int i = note - KeyboardPanel.LOWEST_NOTE;
    return i >= 0 && i < col.length ? col[i] : 0f;
  }

  int historyRows() { return histRows; }

  /** One poll of the engine: scope ring + voice snapshot. Returns the number of samples copied. */
  int sample() {
    count = Engine.getScope(buf);
    int n = Engine.getVoiceLevels(voiceNote, voiceLevel);
    Arrays.fill(col, 0f);
    for (int i = 0; i < n; ++i) {
      int idx = voiceNote[i] - KeyboardPanel.LOWEST_NOTE;
      if (idx >= 0 && idx < col.length) col[idx] = Math.max(col[idx], voiceLevel[i]);
    }
    pushHistory();
    repaint();
    return count;
  }

  int count() { return count; }

  float peak() {
    float p = 0f;
    for (int i = 0; i < count; ++i) {
      float a = Math.abs(buf[i]);
      if (a > p) p = a;
    }
    return p;
  }

  /** Append the current columns as one row. Rows are pixels, so a resize restarts the history. */
  private void pushHistory() {
    int rows = Math.max(1, getHeight());
    if (histRows != rows) {
      hist = new float[rows][NOTE_COUNT];
      histRows = rows;
      histPos = 0;
    }
    float[] row = hist[histPos];
    histPos = (histPos + 1) % rows;
    System.arraycopy(col, 0, row, 0, NOTE_COUNT);
  }

  @Override
  protected void paintComponent(Graphics g0) {
    super.paintComponent(g0);
    Graphics2D g = (Graphics2D) g0;
    int w = getWidth(), h = getHeight();
    if (mode == Mode.WATERFALL) {
      paintWaterfall(g, w, h);
      return;
    }
    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
    int mid = h / 2;
    g.setColor(new Color(60, 60, 60));
    g.drawLine(0, mid, w, mid);
    if (count < 2) return;
    float peak = peak();
    if (peak < 1e-3f) return;
    float scale = (mid - 2) / peak;
    g.setColor(new Color(30, 130, 230));
    g.setStroke(new BasicStroke(1.2f));
    int px = -1, py = 0;
    for (int i = 0; i < count; ++i) {
      int x = (int) (i * (long) (w - 1) / (count - 1));
      int y = mid - (int) (buf[i] * scale);
      if (px >= 0) g.drawLine(px, py, x, y);
      px = x;
      py = y;
    }
  }

  /**
   * Column-major so a run of rows with the same level becomes one fillRect: a held note is a few
   * rects, not one per pixel row. White columns first, black columns on top, as the keyboard paints.
   */
  private void paintWaterfall(Graphics2D g, int w, int h) {
    if (histRows == 0) return;
    paintColumns(g, w, h, false);  // white first, then black on top, as the keyboard paints
    paintColumns(g, w, h, true);
  }

  private void paintColumns(Graphics2D g, int w, int h, boolean black) {
    for (int idx = 0; idx < NOTE_COUNT; ++idx) {
      int note = KeyboardPanel.LOWEST_NOTE + idx;
      if (KeyboardPanel.isBlack(note) != black) continue;
      int x, cw;
      if (black) {
        int wi = KeyboardPanel.whiteIndexOf(note);
        if (wi < 0) continue;
        x = Math.round(KeyboardPanel.blackLeft(wi, w));
        cw = Math.max(1, Math.round(KeyboardPanel.blackWidth(w)));
      } else {
        x = Math.round(KeyboardPanel.leftOfNote(note, w));
        cw = Math.max(1, Math.round(KeyboardPanel.whiteWidth(w)));
      }
      int runStart = -1;
      float runLevel = 0f;
      for (int r = 0; r < histRows; ++r) {
        float v = hist[(histPos - 1 - r + histRows) % histRows][idx];
        if (runStart >= 0 && Math.abs(v - runLevel) < 1e-3f) continue;  // extend the run
        if (runStart >= 0) paintRun(g, x, cw, h, runStart, r, runLevel);
        runStart = r;
        runLevel = v;
      }
      if (runStart >= 0) paintRun(g, x, cw, h, runStart, histRows, runLevel);
    }
  }

  /** Rows [from,to) of one column; row 0 is the newest, drawn at the bottom edge. */
  private void paintRun(Graphics2D g, int x, int w, int h, int from, int to, float level) {
    if (level <= 0.01f) return;
    g.setColor(levelColor(level));
    int y = h - to;
    g.fillRect(x, y, w, to - from);
  }

  private static Color levelColor(float v) {
    float t = Math.min(1f, v);
    return new Color(30 + (int) (120 * t), 130 + (int) (90 * t), 230 + (int) (25 * t));
  }
}
