package gg.nomi.adb;

final class Spake2 {
    static {
        System.loadLibrary("nomiadb");
    }

    private Spake2() {}

    static native long start(byte[] password, byte[] random);

    static native byte[] message(long handle);

    static native byte[] finish(long handle, byte[] theirs);
}
