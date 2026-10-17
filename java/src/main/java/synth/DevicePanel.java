package synth;

import java.util.List;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;

/**
 * Output device picker (PLAN R10). This machine exposes nine WASAPI endpoints - HDMI, VB-Cable,
 * AudioRelay, Steam Streaming - so the live device is always visible and never picked silently.
 *
 * Switching closes the stream and opens one on the new device. A device that refuses to open is
 * reported and the selection snaps back to the device still sounding, so the picker can never leave
 * the app with no audio.
 */
final class DevicePanel extends JPanel {
  private final JComboBox<Engine.Device> box = new JComboBox<>();
  private final JLabel live = new JLabel();
  private final List<Engine.Device> devices;
  private final int sampleRate, bufferFrames;
  private Engine.Device current;
  private boolean applying;  // the combo fires this listener when the code snaps it back

  DevicePanel(int sampleRate, int bufferFrames) {
    this.sampleRate = sampleRate;
    this.bufferFrames = bufferFrames;
    devices = Engine.parseDevices(Engine.probeDevices());
    for (Engine.Device d : devices) box.addItem(d);
    current = find(Engine.defaultOutputDeviceId());
    box.setSelectedItem(current);
    live.setText("live: " + label(current));
    box.addActionListener(e -> {
      if (!applying) apply((Engine.Device) box.getSelectedItem());
    });
    add(box);
    add(live);
  }

  List<Engine.Device> devices() { return devices; }

  /** The device the stream is actually on, which is what the label says. */
  Engine.Device current() { return current; }

  /** Restart the stream on `d`. False if it refused to open, after reopening the previous one. */
  boolean apply(Engine.Device d) {
    if (d == null || d.equals(current)) return current != null;
    Engine.stop();
    String err = Engine.start(d.id(), sampleRate, bufferFrames);
    if (err == null) {
      current = d;
      select(d);
      live.setText("live: " + label(d));
      return true;
    }
    if (current == null) {  // nothing was sounding, so there is nothing to fall back to
      select(null);
      live.setText("id " + d.id() + " failed: " + err);
      return false;
    }
    String back = Engine.start(current.id(), sampleRate, bufferFrames);
    select(current);
    live.setText("id " + d.id() + " failed: " + err
        + (back == null ? "" : " - id " + current.id() + " failed too: " + back));
    return false;
  }

  private String label(Engine.Device d) {
    return (d == null ? "none" : d.name()) + " @ " + sampleRate + " Hz / " + bufferFrames + " frames";
  }

  private void select(Engine.Device d) {
    applying = true;
    box.setSelectedItem(d);
    applying = false;
  }

  private Engine.Device find(int id) {
    for (Engine.Device d : devices) {
      if (d.id() == id) return d;
    }
    return null;
  }
}
