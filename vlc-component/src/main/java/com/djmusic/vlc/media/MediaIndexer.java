package com.djmusic.vlc.media;

import java.io.File;
import java.util.*;
import java.util.stream.Collectors;

public class MediaIndexer {
  private static final String[] MEDIA_EXTENSIONS = {
      // Images: "jpg", "jpeg", "png", "gif", "bmp",
      "mp4", "mkv", "avi", "mov", "webm", "flv", "mpg", "mpeg", // Videos
      "mp3", "wav", "ogg", "flac"                      // Audio
  };

  private final List<String> mediaFiles = new ArrayList<>();
  private final Random random = new Random();

  public MediaIndexer(String rootDir) {
    indexFiles(new File(rootDir));
    System.out.println("Indexed " + mediaFiles.size() + " media files.");
  }

  private void indexFiles(File root) {
    if (!root.exists() || !root.isDirectory()) return;

    File[] files = root.listFiles();
    if (files == null) return;

    for (File file : files) {
      if (file.isDirectory()) {
        indexFiles(file);
      } else if (isMediaFile(file)) {
        mediaFiles.add(file.getAbsolutePath());
      }
    }
  }

  private boolean isMediaFile(File file) {
    String name = file.getName().toLowerCase();
    for (String ext : MEDIA_EXTENSIONS) {
      if (name.endsWith("." + ext)) {
        return true;
      }
    }
    return false;
  }

  private int countWordMatches(String filename, String[] words) {
    int count = 0;
    for (String word : words) {
      if (filename.contains(word)) {
        count++;
      }
    }
    return count;
  }

  private boolean wordsAppearInOrder(String filename, String[] words) {
    int pos = 0;
    for (String word : words) {
      int idx = filename.indexOf(word, pos);
      if (idx == -1) return false;
      pos = idx + word.length();
    }
    return true;
  }

  public List<String> search(String partialName) {
    String[] words = partialName.toLowerCase().split("\\s+");
    List<ScoredPath> scored = new ArrayList<>();

    for (String path : mediaFiles) {
      String filename = new File(path).getName().toLowerCase();
      int wordScore = countWordMatches(filename, words);
      if (wordScore == 0) continue;

      double bonus = wordsAppearInOrder(filename, words) ? 0.1 : 0.0;
      scored.add(new ScoredPath(wordScore + bonus, path));
    }

    scored.sort(Comparator
        .comparingDouble((ScoredPath sp) -> -sp.score)
        .thenComparing(sp -> sp.path));

    if (!scored.isEmpty()) {
      return scored.stream()
          .limit(5)
          .map(sp -> sp.path)
          .collect(Collectors.toList());
    }

    return mediaFiles.isEmpty() ? new ArrayList<>() :
        Collections.singletonList(mediaFiles.get(random.nextInt(mediaFiles.size())));
  }

  private static class ScoredPath {
    double score;
    String path;

    ScoredPath(double score, String path) {
      this.score = score;
      this.path = path;
    }
  }
}

