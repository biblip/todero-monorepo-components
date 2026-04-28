package com.shellaia.component.term.nativeffi;

import java.io.File;

public final class NativeLibraryLoader {
  private static volatile ToderoTermLibrary cached;

  private NativeLibraryLoader() {
  }

  public static ToderoTermLibrary loadOrThrow(String absoluteLibPath) {
    ToderoTermLibrary existing = cached;
    if (existing != null) return existing;
    synchronized (NativeLibraryLoader.class) {
      if (cached != null) return cached;
      if (absoluteLibPath == null || absoluteLibPath.isBlank()) {
        throw new IllegalStateException("TODERO_TERM_NATIVE_LIB_PATH is required (absolute path to libtodero_term)");
      }
      File f = new File(absoluteLibPath.trim());
      if (!f.isFile()) {
        throw new IllegalStateException("Native lib not found: " + f.getAbsolutePath());
      }
      cached = ToderoTermLibrary.load(f.getAbsolutePath());
      return cached;
    }
  }
}
