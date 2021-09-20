package com.github.ngyewch.gradle;

import org.gradle.api.artifacts.Configuration;
import org.gradle.api.provider.MapProperty;
import org.gradle.api.provider.Property;

public abstract class CapsuleExtension {

  public abstract Property<String> getVersion();

  public abstract Property<String> getArchiveBaseName();

  public abstract Property<String> getArchiveAppendix();

  public abstract Property<String> getArchiveVersion();

  public abstract Property<String> getArchiveClassifier();

  public abstract Property<String> getArchiveExtension();

  public abstract Property<Configuration> getEmbedConfiguration();

  public abstract MapProperty<String, String> getManifestAttributes();
}
