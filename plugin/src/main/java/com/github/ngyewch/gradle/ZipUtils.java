package com.github.ngyewch.gradle;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.zip.CRC32;

public class ZipUtils {

  public static long getCRC32(File file)
      throws IOException {
    try (final InputStream inputStream = Files.newInputStream(file.toPath())) {
      return getCRC32(inputStream);
    }
  }

  public static long getCRC32(InputStream inputStream)
      throws IOException {
    final byte[] buffer = new byte[4096];
    final CRC32 crc32 = new CRC32();
    while (true) {
      final int len = inputStream.read(buffer);
      if (len < 0) {
        return crc32.getValue();
      }
      crc32.update(buffer, 0, len);
    }
  }

}
