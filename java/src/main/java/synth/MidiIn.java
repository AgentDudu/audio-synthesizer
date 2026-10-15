package synth;

import java.io.File;
import javax.sound.midi.MidiMessage;
import javax.sound.midi.MidiSystem;
import javax.sound.midi.Receiver;
import javax.sound.midi.Sequencer;
import javax.sound.midi.ShortMessage;

/**
 * MIDI-in adapter (PLAN §6.1). Parsing lives in translate(), which is pure, so the synthetic
 * source (ShortMessages built in a test) and the MIDI-file source (Sequencer.getTransmitter) run
 * the same code. A hardware device in M4b would hand its Transmitter the same Receiver.
 */
final class MidiIn {
  static final int ALL_CHANNELS = -1;

  private MidiIn() {}

  /** vel >= 0 is a note-on, vel < 0 a note-off. null means "not a note event, or filtered out". */
  record Note(int note, int vel) {}

  static Note translate(MidiMessage m, int channelFilter) {
    if (!(m instanceof ShortMessage sm)) return null;
    if (channelFilter != ALL_CHANNELS && sm.getChannel() != channelFilter) return null;
    return switch (sm.getCommand()) {
      // A note-on with velocity 0 is the standard alternative encoding of note-off.
      case ShortMessage.NOTE_ON -> new Note(sm.getData1(), sm.getData2() > 0 ? sm.getData2() : -1);
      case ShortMessage.NOTE_OFF -> new Note(sm.getData1(), -1);
      default -> null;  // control change, program change, pitch bend, system: not in v1
    };
  }

  static Receiver receiver(int channelFilter, KeyboardPanel view) {
    return new Receiver() {
      @Override
      public void send(MidiMessage m, long timestamp) {
        Note n = translate(m, channelFilter);
        if (n == null) return;
        if (n.vel() < 0) {
          Engine.noteOff(n.note());
          if (view != null) view.showRemoteNote(n.note(), false);
        } else {
          Engine.noteOn(n.note(), n.vel());
          if (view != null) view.showRemoteNote(n.note(), true);
        }
      }

      @Override
      public void close() {}
    };
  }

  /**
   * Play a .mid file into the engine. The Sequencer's own thread is the producer; Engine.noteOn/Off
   * serialise it against the EDT. Caller stops it with closeQuietly().
   */
  static Sequencer playFile(String path, int channelFilter, KeyboardPanel view) throws Exception {
    Sequencer seq = MidiSystem.getSequencer(false);  // false: no hardware transmitter attached
    seq.open();
    seq.setSequence(MidiSystem.getSequence(new File(path)));
    seq.getTransmitter().setReceiver(receiver(channelFilter, view));
    seq.start();
    return seq;
  }

  static void closeQuietly(Sequencer seq) {
    if (seq == null) return;
    try {
      if (seq.isRunning()) seq.stop();
    } catch (Exception ignored) {
    }
    try {
      seq.close();
    } catch (Exception ignored) {
    }
  }
}
