package synth;

/** M0: prove the toolchain end to end — C++ DLL loaded by the JVM, WASAPI enumerated. */
public final class App {
  public static void main(String[] args) {
    System.out.print(Engine.probeDevices());
  }
}
