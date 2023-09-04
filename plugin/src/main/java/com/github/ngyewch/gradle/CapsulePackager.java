package com.github.ngyewch.gradle;

import org.apache.commons.io.FilenameUtils;
import org.apache.commons.io.IOUtils;

import java.io.*;
import java.nio.file.Files;
import java.util.HashSet;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarInputStream;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

public class CapsulePackager
    implements Closeable {

  private final JarOutputStream jarOutputStream;
  private final Set<String> filenameSet = new HashSet<>();

  public CapsulePackager(OutputStream outputStream, Manifest manifest)
      throws IOException {
    super();

    jarOutputStream = new JarOutputStream(outputStream, manifest);
  }

  public void addBootJar(File jarFile)
      throws IOException {
    try (final JarInputStream jarInputStream = new JarInputStream(Files.newInputStream(jarFile.toPath()))) {
      while (true) {
        final JarEntry jarEntry = jarInputStream.getNextJarEntry();
        if (jarEntry == null) {
          break;
        }
        if (jarEntry.getName().equalsIgnoreCase("META-INF/MANIFEST.MF")) {
          continue;
        }

        final JarEntry newJarEntry = new JarEntry(jarEntry.getName());
        newJarEntry.setTime(jarEntry.getTime());
        jarOutputStream.putNextEntry(newJarEntry);

        if (!jarEntry.isDirectory()) {
          IOUtils.copy(jarInputStream, jarOutputStream);
        }

        jarOutputStream.closeEntry();
      }
    }
  }

  public void addMainJar(File jarFile)
      throws IOException {
    addMainJar(jarFile, jarFile.getName());
  }

  private void addMainJar(File jarFile, String entryName)
      throws IOException {
    addJar(jarFile, entryName);
  }

  public void addLibJar(File jarFile)
      throws IOException {
    addLibJar(jarFile, jarFile.getName());
  }

  private void addLibJar(File jarFile, String entryName)
      throws IOException {
    addJar(jarFile, entryName);
  }

  private void addJar(File jarFile, String entryName)
      throws IOException {
    if (filenameSet.contains(entryName)) {
      final String baseName = FilenameUtils.getBaseName(entryName);
      final String extension = FilenameUtils.getExtension(entryName);
      int i = 1;
      while (true) {
        entryName = baseName + "-" + i + (extension.isEmpty() ? "" : "." + extension);
        if (!filenameSet.contains(entryName)) {
          break;
        }
        i++;
      }
    }
    filenameSet.add(entryName);

    final JarEntry jarEntry = new JarEntry(entryName);
    jarEntry.setTime(jarFile.lastModified());
    jarEntry.setMethod(JarEntry.STORED);
    jarEntry.setSize(jarFile.length());
    jarEntry.setCrc(ZipUtils.getCRC32(jarFile));
    jarOutputStream.putNextEntry(jarEntry);
    try (final InputStream inputStream = Files.newInputStream(jarFile.toPath())) {
      IOUtils.copy(inputStream, jarOutputStream);
    }
    jarOutputStream.closeEntry();
  }

  @Override
  public void close()
      throws IOException {
    jarOutputStream.close();
  }
}
