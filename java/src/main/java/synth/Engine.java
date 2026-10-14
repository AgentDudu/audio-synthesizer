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
  public static native void noteOn(int note, int velocity);
  public static native void noteOff(int note);
  public static native void setParam(int paramId, float value);
  public static native float getParam(int paramId);

  /** Copies the last out.length output frames into out; returns how many were copied. */
  public static native int getScope(float[] out);

  /** Offline bounce of the given notes with the current params. Null on success, else error text. */
  public static native String renderWav(String path, double seconds, int[] notes, int[] velocities);

  public static native long underrunCount();
  public static native int activeVoices();

  /** First device id marked DEFAULT-OUT in probeDevices(), or -1. */
  public static int defaultOutputDeviceId() {
    for (String line : probeDevices().split("\n")) {
      if (line.contains("DEFAULT-OUT")) return Integer.parseInt(line.trim().split(" ")[0].substring(3));
    }
    return -1;
  }
}
