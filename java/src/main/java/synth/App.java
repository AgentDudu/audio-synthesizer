package synth;

/** M1: live WASAPI stream driven from Java, plus an offline WAV bounce. */
public final class App {
  public static void main(String[] args) throws Exception {
    System.out.print(Engine.probeDevices());
    if (args.length > 0 && args[0].equals("probe")) return;

    int device = Engine.defaultOutputDeviceId();
    if (device < 0) {
      System.err.println("no default output device");
      System.exit(1);
    }

    String err = Engine.start(device, 48000, 512);
    if (err != null) {
      System.err.println("start failed: " + err);
      System.exit(1);
    }
    System.out.println("stream open: device=" + device + " rate=48000 frames=512");

    for (int note : new int[] {60, 64, 67, 72}) {
      Engine.noteOn(note, 100);
      Thread.sleep(400);
      Engine.noteOff(note);
    }
    Thread.sleep(2500);  // >5 s of stream time total, for the underrun check
    System.out.println("activeVoices=" + Engine.activeVoices()
        + " underruns=" + Engine.underrunCount());

    String wavErr = Engine.renderWav("build/out.wav", 2.0,
        new int[] {60, 64, 67}, new int[] {100, 90, 80});
    System.out.println(wavErr == null ? "wrote build/out.wav" : "renderWav failed: " + wavErr);

    Engine.stop();
  }
}
