package com.shellaia.component.term.nativeffi;

import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.LongByReference;
import com.sun.jna.ptr.PointerByReference;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

public final class NativeTerm {
  public static final int OK = 0;

  private final ToderoTermLibrary lib;

  public NativeTerm(ToderoTermLibrary lib) {
    this.lib = lib;
  }

  public Pointer createFromJson(String jsonUtf8) {
    byte[] bytes = jsonUtf8.getBytes(StandardCharsets.UTF_8);
    PointerByReference out = new PointerByReference();
    int rc = lib.todero_term_create_json(bytes, bytes.length, out);
    if (rc != OK) {
      throw new NativeTermException("create failed", rc, null);
    }
    return out.getValue();
  }

  public void free(Pointer handle) {
    if (handle == null) return;
    lib.todero_term_free(handle);
  }

  public void close(Pointer handle) {
    int rc = lib.todero_term_close(handle);
    if (rc != OK && rc != ToderoTermErrors.EXITED) {
      throw new NativeTermException("close failed", rc, lastError(handle));
    }
  }

  public void kill(Pointer handle) {
    int rc = lib.todero_term_kill(handle);
    if (rc != OK) {
      throw new NativeTermException("kill failed", rc, lastError(handle));
    }
  }

  public WaitExitResult waitExit(Pointer handle, int timeoutMs) {
    IntByReference outCode = new IntByReference(0);
    int rc = lib.todero_term_wait_exit(handle, timeoutMs, outCode);
    if (rc == OK) {
      return new WaitExitResult(true, outCode.getValue());
    }
    if (rc == ToderoTermErrors.TIMEOUT) {
      return new WaitExitResult(false, 0);
    }
    throw new NativeTermException("wait_exit failed", rc, lastError(handle));
  }

  public void write(Pointer handle, byte[] bytes) {
    int rc = lib.todero_term_write(handle, bytes, bytes.length);
    if (rc != OK) {
      throw new NativeTermException("write failed", rc, lastError(handle));
    }
  }

  public ReadResult read(Pointer handle, long sinceSeq, int maxBytes) {
    if (maxBytes <= 0) maxBytes = 1;
    byte[] out = new byte[maxBytes];
    LongByReference inout = new LongByReference(maxBytes);
    LongByReference outNewSeq = new LongByReference(0);
    int rc = lib.todero_term_read(handle, sinceSeq, out, inout, outNewSeq);
    if (rc != OK) {
      throw new NativeTermException("read failed", rc, lastError(handle));
    }
    int n = (int) inout.getValue();
    byte[] slice = new byte[n];
    System.arraycopy(out, 0, slice, 0, n);
    return new ReadResult(outNewSeq.getValue(), slice);
  }

  public BufferSnapshot buffer(Pointer handle, int maxBytes) {
    if (maxBytes <= 0) maxBytes = 1;
    byte[] out = new byte[maxBytes];
    LongByReference inout = new LongByReference(maxBytes);
    LongByReference s = new LongByReference(0);
    LongByReference e = new LongByReference(0);
    int rc = lib.todero_term_get_buffer(handle, out, inout, s, e);
    if (rc != OK) {
      throw new NativeTermException("get_buffer failed", rc, lastError(handle));
    }
    int n = (int) inout.getValue();
    byte[] slice = new byte[n];
    System.arraycopy(out, 0, slice, 0, n);
    return new BufferSnapshot(s.getValue(), e.getValue(), slice);
  }

  public void resize(Pointer handle, int cols, int rows) {
    int rc = lib.todero_term_resize(handle, (short) cols, (short) rows);
    if (rc != OK) {
      throw new NativeTermException("resize failed", rc, lastError(handle));
    }
  }

  public void setBufferLimit(Pointer handle, long maxBytes) {
    int rc = lib.todero_term_set_buffer_limit(handle, maxBytes);
    if (rc != OK) {
      throw new NativeTermException("set_buffer_limit failed", rc, lastError(handle));
    }
  }

  public boolean screenEnabled(Pointer handle) {
    byte[] enabled = new byte[1];
    int rc = lib.todero_term_screen_enabled(handle, enabled);
    if (rc != OK) {
      throw new NativeTermException("screen_enabled failed", rc, lastError(handle));
    }
    return enabled[0] != 0;
  }

  public ScreenInfo screenInfo(Pointer handle) {
    ToderoTermLibrary.ToderoTermScreenInfo info = new ToderoTermLibrary.ToderoTermScreenInfo();
    int rc = lib.todero_term_screen_info(handle, info);
    if (rc != OK) {
      throw new NativeTermException("screen_info failed", rc, lastError(handle));
    }
    info.read();
    return ScreenInfo.from(info);
  }

  public ScreenPayload screenText(Pointer handle, int maxBytes) {
    return screenPayload(handle, maxBytes, ScreenPayloadMode.TEXT, 0L);
  }

  public ScreenPayload screenFormatted(Pointer handle, int maxBytes) {
    return screenPayload(handle, maxBytes, ScreenPayloadMode.FORMATTED, 0L);
  }

  public ScreenDiffPayload screenDiff(Pointer handle, long sinceFrameId, int maxBytes) {
    if (maxBytes <= 0) maxBytes = 1;
    byte[] out = new byte[maxBytes];
    LongByReference inout = new LongByReference(maxBytes);
    ToderoTermLibrary.ToderoTermScreenDiffInfo diffInfo = new ToderoTermLibrary.ToderoTermScreenDiffInfo();
    ToderoTermLibrary.ToderoTermScreenInfo info = new ToderoTermLibrary.ToderoTermScreenInfo();
    int rc = lib.todero_term_screen_diff(handle, sinceFrameId, out, inout, diffInfo, info);
    if (rc != OK) {
      throw new NativeTermException("screen_diff failed", rc, lastError(handle));
    }
    diffInfo.read();
    info.read();
    int n = (int) inout.getValue();
    return new ScreenDiffPayload(
        ScreenDiffInfo.from(diffInfo),
        ScreenInfo.from(info),
        slice(out, n)
    );
  }

  public byte[] screenScrollbackText(Pointer handle, int maxBytes) {
    if (maxBytes <= 0) maxBytes = 1;
    byte[] out = new byte[maxBytes];
    LongByReference inout = new LongByReference(maxBytes);
    int rc = lib.todero_term_screen_scrollback_text(handle, out, inout);
    if (rc != OK) {
      throw new NativeTermException("screen_scrollback_text failed", rc, lastError(handle));
    }
    return slice(out, (int) inout.getValue());
  }

  public String lastError(Pointer handle) {
    if (handle == null) return null;
    Pointer p = lib.todero_term_last_error(handle);
    if (p == null) return null;
    return p.getString(0);
  }

  public static String b64(byte[] bytes) {
    return Base64.getEncoder().encodeToString(bytes);
  }

  public static byte[] unb64(String b64) {
    return Base64.getDecoder().decode(b64);
  }

  private ScreenPayload screenPayload(Pointer handle, int maxBytes, ScreenPayloadMode mode, long ignored) {
    if (maxBytes <= 0) maxBytes = 1;
    byte[] out = new byte[maxBytes];
    LongByReference inout = new LongByReference(maxBytes);
    ToderoTermLibrary.ToderoTermScreenInfo info = new ToderoTermLibrary.ToderoTermScreenInfo();
    int rc = switch (mode) {
      case TEXT -> lib.todero_term_screen_text(handle, out, inout, info);
      case FORMATTED -> lib.todero_term_screen_formatted(handle, out, inout, info);
    };
    if (rc != OK) {
      throw new NativeTermException(mode == ScreenPayloadMode.TEXT ? "screen_text failed" : "screen_formatted failed",
          rc, lastError(handle));
    }
    info.read();
    return new ScreenPayload(ScreenInfo.from(info), slice(out, (int) inout.getValue()));
  }

  private static byte[] slice(byte[] out, int n) {
    byte[] slice = new byte[n];
    System.arraycopy(out, 0, slice, 0, n);
    return slice;
  }

  public record WaitExitResult(boolean exited, int exitCode) {
  }

  public record ReadResult(long newSeq, byte[] bytes) {
  }

  public record BufferSnapshot(long seqStart, long seqEnd, byte[] bytes) {
  }

  public record ScreenInfo(
      int rows,
      int cols,
      int cursorRow,
      int cursorCol,
      boolean alternateScreen,
      boolean hideCursor,
      boolean applicationCursor,
      long frameId
  ) {
    static ScreenInfo from(ToderoTermLibrary.ToderoTermScreenInfo info) {
      return new ScreenInfo(
          Short.toUnsignedInt(info.rows),
          Short.toUnsignedInt(info.cols),
          Short.toUnsignedInt(info.cursor_row),
          Short.toUnsignedInt(info.cursor_col),
          info.alternate_screen != 0,
          info.hide_cursor != 0,
          info.application_cursor != 0,
          info.frame_id
      );
    }
  }

  public record ScreenDiffInfo(
      long frameIdStart,
      long frameIdEnd,
      boolean truncated
  ) {
    static ScreenDiffInfo from(ToderoTermLibrary.ToderoTermScreenDiffInfo info) {
      return new ScreenDiffInfo(info.frame_id_start, info.frame_id_end, info.truncated != 0);
    }
  }

  public record ScreenPayload(ScreenInfo info, byte[] bytes) {
  }

  public record ScreenDiffPayload(ScreenDiffInfo diffInfo, ScreenInfo info, byte[] bytes) {
  }

  private enum ScreenPayloadMode {
    TEXT,
    FORMATTED
  }

  public static final class NativeTermException extends RuntimeException {
    public final int errorCode;

    public NativeTermException(String message, int errorCode, String nativeMessage) {
      super(message + " rc=" + errorCode + (nativeMessage == null ? "" : (" native=" + nativeMessage)));
      this.errorCode = errorCode;
    }
  }

  public static final class ToderoTermErrors {
    public static final int NULL = 1;
    public static final int INVALID = 2;
    public static final int IO = 3;
    public static final int UNSUPPORTED = 4;
    public static final int TIMEOUT = 5;
    public static final int EXITED = 6;

    private ToderoTermErrors() {
    }
  }
}
