package synth;

import java.awt.Dimension;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSlider;

/**
 * One vertical slider per engine param, applied live through Engine.setParam. Slider ticks are an
 * integer 0..1000; the mapping to engine units is the pure pair toEngine/toTick below, so the
 * self-test can check ranges without touching audio.
 */
final class KnobPanel extends JPanel {
  record Spec(int id, String label, float min, float max, boolean exponential) {}

  static final int TICKS = 1000;

  // Ranges chosen around the engine defaults (synth.cpp): cutoff 8000, reso 0.2, att 0.003,
  // dec 0.2, sus 0.7, rel 0.25, mix 0, master 0.2. Time and frequency params get a log scale
  // because that is where the ear actually lives.
  static final Spec[] SPECS = {
      new Spec(Engine.CUTOFF, "cutoff", 40f, 20000f, true),
      new Spec(Engine.RESONANCE, "reso", 0f, 1f, false),
      new Spec(Engine.ATTACK, "attack", 0.001f, 2f, true),
      new Spec(Engine.DECAY, "decay", 0.005f, 4f, true),
      new Spec(Engine.SUSTAIN, "sustain", 0f, 1f, false),
      new Spec(Engine.RELEASE, "release", 0.01f, 8f, true),
      new Spec(Engine.OSC_MIX, "sine mix", 0f, 1f, false),
      new Spec(Engine.MASTER, "out", 0f, 1f, false),
  };

  private final JSlider[] sliders = new JSlider[SPECS.length];
  private final JLabel[] readouts = new JLabel[SPECS.length];

  KnobPanel() {
    setLayout(new BoxLayout(this, BoxLayout.X_AXIS));
    setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
    for (int i = 0; i < SPECS.length; ++i) {
      final Spec spec = SPECS[i];
      final int index = i;
      JSlider slider = new JSlider(JSlider.VERTICAL, 0, TICKS, toTick(spec, Engine.getParam(spec.id())));
      slider.setPreferredSize(new Dimension(64, 110));
      JLabel readout = new JLabel(format(spec, toEngine(spec, slider.getValue())), JLabel.CENTER);
      slider.addChangeListener(e -> {
        float value = toEngine(spec, slider.getValue());
        Engine.setParam(spec.id(), value);
        readout.setText(format(spec, value));
      });
      sliders[index] = slider;
      readouts[index] = readout;

      JPanel column = new JPanel();
      column.setLayout(new BoxLayout(column, BoxLayout.Y_AXIS));
      column.add(new JLabel(spec.label()));
      column.add(slider);
      column.add(readout);
      add(column);
    }
  }

  /** Drive a slider by param id (used by the self-test, and by any future preset loader). */
  void setTick(int paramId, int tick) {
    for (int i = 0; i < SPECS.length; ++i) {
      if (SPECS[i].id == paramId) sliders[i].setValue(Math.max(0, Math.min(TICKS, tick)));
    }
  }

  int tickOf(int paramId) {
    for (int i = 0; i < SPECS.length; ++i) {
      if (SPECS[i].id == paramId) return sliders[i].getValue();
    }
    return -1;
  }

  static float toEngine(Spec spec, int tick) {
    double t = tick / (double) TICKS;
    return spec.exponential
        ? (float) (spec.min() * Math.pow(spec.max() / spec.min(), t))
        : (float) (spec.min() + (spec.max() - spec.min()) * t);
  }

  static int toTick(Spec spec, float value) {
    double t = spec.exponential
        ? Math.log(value / spec.min()) / Math.log(spec.max() / spec.min())
        : (value - spec.min()) / (spec.max() - spec.min());
    return (int) Math.round(Math.max(0, Math.min(1, t)) * TICKS);
  }

  static String format(Spec spec, float value) {
    if (spec.max() >= 1000) return Math.round(value) + " Hz";
    if (spec.max() <= 10) return String.format("%.3f s", value);
    return String.format("%.2f", value);
  }
}
