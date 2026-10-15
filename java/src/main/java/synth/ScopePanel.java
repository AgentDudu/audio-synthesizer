package synth;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import javax.swing.JPanel;
import javax.swing.Timer;

/**
 * Last 1024 output samples, redrawn ~60 fps. The poll is a copy out of the engine's lock-free
 * scope ring, so the audio thread never waits on the EDT (R9). Amplitude is auto-scaled to the
 * window; below 1e-3 it is treated as silence and only the centre line is drawn.
 */
final class ScopePanel extends JPanel {
  static final int SAMPLES = 1024;  // synth::AudioEngine::kScopeSize
  static final int PREF_HEIGHT = 120, MIN_HEIGHT = 40;

  private final float[] buf = new float[SAMPLES];
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

  /** One poll of the engine ring. Returns the number of samples copied. */
  int sample() {
    count = Engine.getScope(buf);
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

  @Override
  protected void paintComponent(Graphics g0) {
    super.paintComponent(g0);
    Graphics2D g = (Graphics2D) g0;
    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
    int w = getWidth(), h = getHeight(), mid = h / 2;
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
}
