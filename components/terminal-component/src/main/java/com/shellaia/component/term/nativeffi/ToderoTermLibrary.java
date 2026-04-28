package com.shellaia.component.term.nativeffi;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.LongByReference;
import com.sun.jna.ptr.PointerByReference;

import java.util.List;

public interface ToderoTermLibrary extends Library {

  static ToderoTermLibrary load(String absoluteLibPath) {
    return Native.load(absoluteLibPath, ToderoTermLibrary.class);
  }

  int todero_term_create_json(byte[] json_ptr, long json_len, PointerByReference out_handle);

  int todero_term_close(Pointer handle);

  int todero_term_kill(Pointer handle);

  int todero_term_wait_exit(Pointer handle, int timeout_ms, IntByReference out_exit_code);

  int todero_term_write(Pointer handle, byte[] bytes, long len);

  int todero_term_read(Pointer handle, long since_seq, byte[] out_buf, LongByReference inout_len,
      LongByReference out_new_seq);

  int todero_term_get_buffer(Pointer handle, byte[] out_buf, LongByReference inout_len,
      LongByReference out_seq_start, LongByReference out_seq_end);

  int todero_term_resize(Pointer handle, short cols, short rows);

  int todero_term_set_buffer_limit(Pointer handle, long max_bytes);

  int todero_term_screen_enabled(Pointer handle, byte[] out_enabled);

  int todero_term_screen_info(Pointer handle, ToderoTermScreenInfo out_info);

  int todero_term_screen_text(Pointer handle, byte[] out_buf, LongByReference inout_len,
      ToderoTermScreenInfo out_info);

  int todero_term_screen_formatted(Pointer handle, byte[] out_buf, LongByReference inout_len,
      ToderoTermScreenInfo out_info);

  int todero_term_screen_diff(Pointer handle, long since_frame_id, byte[] out_buf, LongByReference inout_len,
      ToderoTermScreenDiffInfo out_diff_info, ToderoTermScreenInfo out_info);

  int todero_term_screen_scrollback_text(Pointer handle, byte[] out_buf, LongByReference inout_len);

  Pointer todero_term_last_error(Pointer handle);

  void todero_term_free(Pointer handle);

  class ToderoTermScreenInfo extends Structure {
    public short rows;
    public short cols;
    public short cursor_row;
    public short cursor_col;
    public byte alternate_screen;
    public byte hide_cursor;
    public byte application_cursor;
    public byte reserved;
    public long frame_id;

    @Override
    protected List<String> getFieldOrder() {
      return List.of("rows", "cols", "cursor_row", "cursor_col", "alternate_screen", "hide_cursor",
          "application_cursor", "reserved", "frame_id");
    }
  }

  class ToderoTermScreenDiffInfo extends Structure {
    public long frame_id_start;
    public long frame_id_end;
    public byte truncated;
    public byte[] reserved = new byte[7];

    @Override
    protected List<String> getFieldOrder() {
      return List.of("frame_id_start", "frame_id_end", "truncated", "reserved");
    }
  }
}
