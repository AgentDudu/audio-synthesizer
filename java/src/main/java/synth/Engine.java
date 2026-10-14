package synth;

/** The whole Java -> C++ boundary. Keep every native declaration here. */
public final class Engine {
  static { System.loadLibrary("synthjni"); }

  private Engine() {}

  /** WASAPI device report (id, name, channels, rate). Ids are RtAudio ids, not 0-based indices. */
  public static native String probeDevices();
}
