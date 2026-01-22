package com.djmusic.vlc.util;

import java.io.File;
import java.util.List;

public class PlaylistFormatter {

  public static String formatPlaylist(List<String> songs, int playListIndex) {
    StringBuilder json = new StringBuilder();
    json.append("{\n");
    json.append("  \"playListIndex\": ").append(playListIndex).append(",\n");
    json.append("  \"songs\": [\n");

    for (int i = 0; i < songs.size(); i++) {
      String fullPath = songs.get(i);
      File file = new File(fullPath);
      String path = file.getParent();
      String filename = file.getName();

      json.append("    {\n");
      json.append("      \"path\": \"").append(escapeJson(path)).append("\",\n");
      json.append("      \"filename\": \"").append(escapeJson(filename)).append("\"\n");
      json.append("    }");

      if (i < songs.size() - 1) {
        json.append(",");
      }
      json.append("\n");
    }

    json.append("  ]\n");
    json.append("}");
    return json.toString();
  }

  // Utility to escape JSON special characters
  private static String escapeJson(String text) {
    return text == null ? "" : text
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\b", "\\b")
        .replace("\f", "\\f")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
        .replace("\t", "\\t");
  }
}