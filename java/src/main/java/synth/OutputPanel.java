package synth;

import java.awt.event.ActionListener;
import java.util.ArrayList;
import java.util.List;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;

/**
 * Output stream settings: which endpoint to play on and how big a buffer to ask for (PLAN R10, M6).
 * Both controls restart the stream, so they share one restart path and one label naming what is
 * actually live. Nine WASAPI endpoints on this machine - the live one is never picked silently.
 *
 * A restart that refuses to open is reported and both controls snap back to the settings sounding
 * again, so the panel can never leave the app with no audio.
 *
 * The state tracks the buffer size we *asked* for; the label prints the size the stream really
 * opened with (Engine.bufferFrames()), which RtAudio may adjust.
 */
final class OutputPanel extends JPanel {
  static final int[] BUFFER_CHOICES = {128, 256, 512, 1024};

  private final List<Engine.Device> devices = new ArrayList<>();
  private final JComboBox<Engine.Device> deviceBox = new JComboBox<>();
  private final JComboBox<Integer> bufferBox = new JComboBox<>();
  private final JLabel live = new JLabel();
  private final int sampleRate;
  private Engine.Device current;
  private int currentFrames;
  private boolean applying;  // the combos fire their listener when the code snaps them back

  OutputPanel(int sampleRate, int initialFrames) {
    this.sampleRate = sampleRate;
    devices.addAll(Engine.parseDevices(Engine.probeDevices()));
    for (Engine.Device d : devices) deviceBox.addItem(d);
    for (int frames : BUFFER_CHOICES) bufferBox.addItem(frames);
    bufferBox.setSelectedItem(initialFrames);
    if (bufferBox.getSelectedItem() == null) bufferBox.setSelectedIndex(0);
    currentFrames = (Integer) bufferBox.getSelectedItem();
    current = find(Engine.defaultOutputDeviceId());
    deviceBox.setSelectedItem(current);
    live.setText("live: " + describe());
    ActionListener restart = e -> {
      if (!applying) restart();
    };
    deviceBox.addActionListener(restart);
    bufferBox.addActionListener(restart);
    add(deviceBox);
    add(bufferBox);
    add(live);
  }

  List<Engine.Device> devices() { return devices; }

  /** The settings the stream is actually on, which is what the label says. */
  Engine.Device current() { return current; }

  int currentFrames() { return currentFrames; }

  /** Pick an endpoint and restart. False if it refused to open, after reopening the previous one. */
  boolean apply(Engine.Device d) {
    if (d == null) return false;
    select(deviceBox, d);
    return restart();
  }

  /** Ask for a different buffer size and restart. False if the stream refused to reopen. */
  boolean setBuffer(int frames) {
    select(bufferBox, frames);
    return restart();
  }

  /**
   * Close the stream and open it with whatever the two controls show. On failure both controls go
   * back to the settings sounding again, so the UI never claims a stream that is not there.
   */
  private boolean restart() {
    Engine.Device want = (Engine.Device) deviceBox.getSelectedItem();
    int frames = (Integer) bufferBox.getSelectedItem();
    if (want == null) return false;
    Engine.stop();
    String err = Engine.start(want.id(), sampleRate, frames);
    if (err == null) {
      current = want;
      currentFrames = frames;
      live.setText("live: " + describe());
      return true;
    }
    if (current == null) {  // nothing was sounding, so there is nothing to fall back to
      live.setText("id " + want.id() + " / " + frames + " failed: " + err);
      return false;
    }
    String back = Engine.start(current.id(), sampleRate, currentFrames);
    select(deviceBox, current);
    select(bufferBox, currentFrames);
    live.setText("id " + want.id() + " / " + frames + " failed: " + err
        + (back == null ? "" : " - id " + current.id() + " failed too: " + back));
    return false;
  }

  private String describe() {
    return (current == null ? "none" : current.name()) + " @ " + sampleRate + " Hz / "
        + Engine.bufferFrames() + " frames";
  }

  private <T> void select(JComboBox<T> box, T value) {
    applying = true;
    box.setSelectedItem(value);
    applying = false;
  }

  private Engine.Device find(int id) {
    for (Engine.Device d : devices) {
      if (d.id() == id) return d;
    }
    return null;
  }
}
