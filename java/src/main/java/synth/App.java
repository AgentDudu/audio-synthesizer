package synth;

import java.awt.BorderLayout;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import javax.swing.JButton;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;

public final class App {
  public static void main(String[] args) throws Exception {
    String mode = args.length > 0 ? args[0] : "ui";
    if (mode.equals("probe")) {
      probe();
      return;
    }
    if (mode.equals("smoke") || mode.equals("--smoke")) {
      smoke();
      return;
    }
    ui();
  }

  private static void probe() {
    System.out.println("default output device id = " + Engine.defaultOutputDeviceId());
    System.out.println(Engine.probeDevices());
  }

  private static void ui() {
    int device = Engine.defaultOutputDeviceId();
    if (device < 0) {
      System.err.println("no default output device");
      System.exit(1);
    }
    String err = Engine.start(device, 48000, 512);
    if (err != null) {
      System.err.println("audio start failed: " + err);
      System.exit(1);
    }

    SwingUtilities.invokeLater(() -> {
      JFrame frame = new JFrame("Audio Synthesizer");
      KeyboardPanel keys = new KeyboardPanel();
      JLabel status = new JLabel("device " + device + " @ 48000 Hz / 512 frames"
          + "   A0-C8 on screen   computer keys: a w s e d f t g y h u j k   arrows: octave"
          + "   esc: all notes off");
      KnobPanel knobs = new KnobPanel();
      ScopePanel scope = new ScopePanel();
      JPanel center = new JPanel(new BorderLayout());
      center.add(scope, BorderLayout.NORTH);
      center.add(keys, BorderLayout.CENTER);
      JPanel south = new JPanel(new BorderLayout());
      south.add(knobs, BorderLayout.CENTER);
      south.add(status, BorderLayout.SOUTH);

      // MIDI-file source (M4a): no hardware, the Sequencer's transmitter feeds our Receiver.
      JButton midiOpen = new JButton("midi file\u2026");
      JButton midiStop = new JButton("midi stop");
      final javax.sound.midi.Sequencer[] player = new javax.sound.midi.Sequencer[1];
      midiOpen.addActionListener(e -> {
        JFileChooser chooser = new JFileChooser();
        if (chooser.showOpenDialog(frame) != JFileChooser.APPROVE_OPTION) return;
        MidiIn.closeQuietly(player[0]);
        keys.clearRemoteNotes();
        try {
          player[0] = MidiIn.playFile(chooser.getSelectedFile().getPath(), MidiIn.ALL_CHANNELS, keys);
          status.setText("playing " + chooser.getSelectedFile().getName());
        } catch (Exception ex) {
          status.setText("midi failed: " + ex);
        }
      });
      midiStop.addActionListener(e -> {
        MidiIn.closeQuietly(player[0]);
        player[0] = null;
        keys.clearRemoteNotes();  // a stopped file must not leave keys lit
      });
      JPanel midiRow = new JPanel();
      midiRow.add(midiOpen);
      midiRow.add(midiStop);
      south.add(midiRow, BorderLayout.WEST);

      frame.setLayout(new BorderLayout());
      frame.add(center, BorderLayout.CENTER);
      frame.add(south, BorderLayout.SOUTH);
      frame.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
      frame.addWindowListener(new WindowAdapter() {
        @Override
        public void windowClosing(WindowEvent e) {
          MidiIn.closeQuietly(player[0]);
          scope.stop();  // otherwise the 60 fps timer keeps the EDT alive after dispose
          Engine.stop();
        }
      });
      frame.pack();
      frame.setLocationByPlatform(true);
      frame.setVisible(true);
      keys.requestFocusInWindow();
    });
  }

  /** Headless self-check: start the stream, play four notes, report underruns, bounce a WAV. */
  private static void smoke() throws Exception {
    System.out.println("default output device id = " + Engine.defaultOutputDeviceId());
    String err = Engine.start(Engine.defaultOutputDeviceId(), 48000, 512);
    if (err != null) {
      System.err.println("start failed: " + err);
      System.exit(1);
    }
    int[] notes = {60, 64, 67, 72};
    for (int n : notes) {
      Engine.noteOn(n, 100);
      Thread.sleep(150);
      Engine.noteOff(n);
      Thread.sleep(50);
    }
    Thread.sleep(400);
    System.out.println("underruns=" + Engine.underrunCount() + " activeVoices=" + Engine.activeVoices());
    Engine.stop();

    Engine.setParam(Engine.CUTOFF, 9000f);
    Engine.setParam(Engine.RELEASE, 0.4f);
    int[] velocities = new int[notes.length];
    java.util.Arrays.fill(velocities, 110);
    System.out.println("renderWav -> " + Engine.renderWav("build/out.wav", 2.0, notes, velocities));
  }
}
