package com.shellaia.component.term;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TermComponentPathAllowlistTest {

  @Test
  void canonicalAbsolutePath_resolvesExistingAbsolutePath() throws IOException {
    Path root = Files.createTempDirectory("term-component-root");
    try {
      Path resolved = TermComponent.canonicalAbsolutePath(root.toString());
      assertNotNull(resolved);
      assertEqualsRealPath(root, resolved);
    } finally {
      Files.deleteIfExists(root);
    }
  }

  @Test
  void isPathUnderRoot_allowsNestedDescendants() throws IOException {
    Path root = Files.createTempDirectory("term-component-allowed");
    Path nested = Files.createDirectories(root.resolve("a").resolve("b").resolve("c"));
    try {
      assertTrue(TermComponent.isPathUnderRoot(nested.toRealPath(), root.toRealPath()));
    } finally {
      Files.walk(root)
          .sorted((a, b) -> b.getNameCount() - a.getNameCount())
          .forEach(path -> {
            try {
              Files.deleteIfExists(path);
            } catch (IOException ignored) {
            }
          });
    }
  }

  @Test
  void isPathUnderRoot_rejectsSiblingPrefix() throws IOException {
    Path base = Files.createTempDirectory("term-component-prefix");
    Path allowed = base.resolveSibling(base.getFileName() + "-allowed");
    Path sibling = base.resolveSibling(base.getFileName() + "-allowed2");
    Files.createDirectories(allowed);
    Files.createDirectories(sibling);
    try {
      assertFalse(TermComponent.isPathUnderRoot(sibling.toRealPath(), allowed.toRealPath()));
    } finally {
      Files.deleteIfExists(allowed);
      Files.deleteIfExists(sibling);
      Files.deleteIfExists(base);
    }
  }

  @Test
  void prepareCwd_createsMissingNestedDirectoryUnderAllowedRoot() throws IOException {
    Path root = Files.createTempDirectory("term-component-prepare");
    Path target = root.toRealPath().resolve("nested").resolve("child");
    try {
      Path prepared = TermComponent.prepareCwd(target.toString(), root.toRealPath());
      assertTrue(Files.isDirectory(target));
      assertEquals(target.toRealPath(), prepared);
    } finally {
      Files.walk(root)
          .sorted((a, b) -> b.getNameCount() - a.getNameCount())
          .forEach(path -> {
            try {
              Files.deleteIfExists(path);
            } catch (IOException ignored) {
            }
          });
    }
  }

  private static void assertEqualsRealPath(Path expected, Path actual) throws IOException {
    assertTrue(Files.isSameFile(expected, actual));
  }
}
