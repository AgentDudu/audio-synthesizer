package synth;

/** The whole Java -> C++ boundary. Keep every native declaration here. */
public final class Engine {
  static { System.loadLibrary("synthjni"); }

  private Engine() {}

  // Param ids, mirrored by native/include/synth/params.hpp. Order matters.
  public static final int CUTOFF = 0, RESONANCE = 1, ATTACK = 2, DECAY = 3,
      SUSTAIN = 4, RELEASE = 5, OSC_MIX = 6, MASTER = 7;

  /** WASAPI device report (id, name, channels, rate). Ids are RtAudio ids, not 0-based indices. */
  public static native String probeDevices();

  /** Returns null on success, otherwise the RtAudio error text. */
  public static native String start(int deviceId, int sampleRate, int bufferFrames);

  public static native void stop();

  // The C++ event ring is single-producer. The EDT (keyboard) and the MIDI receiver are both
  // producers, so every note event serialises through these two wrappers (PLAN §9.4). The lock is
  // held only across a ring push - no blocking, no allocation.
  public static synchronized void noteOn(int note, int velocity) { nativeNoteOn(note, velocity); }
  public static synchronized void noteOff(int note) { nativeNoteOff(note); }

  private static native void nativeNoteOn(int note, int velocity);
  private static native void nativeNoteOff(int note);
  public static native void setParam(int paramId, float value);
  public static native float getParam(int paramId);

  /** Copies the last out.length output frames into out; returns how many were copied. */
  public static native int getScope(float[] out);

  /** Active voices as the audio thread saw them: note + envelope level per slot. Returns the count. */
  public static native int getVoiceLevels(int[] notes, float[] levels);

  /** Offline bounce of the given notes with the current params. Null on success, else error text. */
  public static native String renderWav(String path, double seconds, int[] notes, int[] velocities);

  public static native long underrunCount();
  public static native int activeVoices();

  /** Buffer size the stream actually opened with (RtAudio may adjust the request); 0 when closed. */
  public static native int bufferFrames();

  /** First device id marked DEFAULT-OUT in probeDevices(), or -1. */
  public static int defaultOutputDeviceId() {
    for (Device d : parseDevices(probeDevices())) {
      if (d.defaultOut()) return d.id();
    }
    return -1;
  }

  /** One playable output endpoint. `id` is an RtAudio id, not a 0-based index. */
  public record Device(int id, String name, int rate, boolean defaultOut) {
    @Override
    public String toString() {
      return "id " + id + "  " + name + "  (" + rate + " Hz)" + (defaultOut ? "  [default]" : "");
    }
  }

  /**
   * Output devices from a probeDevices() report, input-only endpoints dropped. Parsing lives here so
   * the format has one reader and the picker can be tested without touching a device (R10).
   */
  public static java.util.List<Device> parseDevices(String report) {
    java.util.List<Device> out = new java.util.ArrayList<>();
    for (String line : report.split("\n")) {
      String s = line.trim();
      if (!s.startsWith("id=")) continue;
      int space = s.indexOf(' ', 3);
      int id = Integer.parseInt(s.substring(3, space));
      int o = s.indexOf(" out="), r = s.indexOf(" rate=");
      if (o < 0 || r < 0) continue;
      if (Integer.parseInt(s.substring(o + 5, s.indexOf(' ', o + 5))) == 0) continue;
      int end = s.indexOf(' ', r + 6);
      int rate = Integer.parseInt(s.substring(r + 6, end < 0 ? s.length() : end));
      out.add(new Device(id, s.substring(space + 1, o).trim(), rate, s.contains("DEFAULT-OUT")));
    }
    return out;
  }
}
