package com.github.ngyewch.gradle;

import org.apache.commons.lang3.StringUtils;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.artifacts.*;
import org.gradle.api.file.FileCollection;
import org.gradle.api.plugins.BasePluginConvention;
import org.gradle.api.plugins.JavaApplication;
import org.gradle.api.provider.MapProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.TaskAction;
import org.gradle.api.tasks.bundling.Jar;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.*;
import java.util.jar.Manifest;
import java.util.stream.Collectors;

public abstract class PackageCapsuleTask
    extends DefaultTask {

  private static final String DEFAULT_CAPSULE_VERSION = "1.0.3";
  private static final String DEFAULT_CLASSIFIER = "capsule";
  private static final String DEFAULT_EXTENSION = "jar";

  @Input
  @Optional
  public abstract Property<String> getArchiveBaseName();

  @Input
  @Optional
  public abstract Property<String> getArchiveAppendix();

  @Input
  @Optional
  public abstract Property<String> getArchiveVersion();

  @Input
  @Optional
  public abstract Property<String> getArchiveClassifier();

  @Input
  @Optional
  public abstract Property<String> getArchiveExtension();

  @Input
  @Optional
  public abstract Property<Configuration> getEmbedConfiguration();

  @Input
  @Optional
  public abstract Property<String> getMainClass();

  @Input
  @Optional
  public abstract MapProperty<String, String> getManifestAttributes();

  @TaskAction
  public void action()
      throws IOException {
    final TaskHandler taskHandler = new TaskHandler();
    taskHandler.action();
  }

  private class TaskHandler {

    private final Project project;
    private final BasePluginConvention basePluginConvention;
    private final CapsuleExtension capsuleExtension;

    public TaskHandler() {
      super();

      project = getProject();
      basePluginConvention = project.getConvention().findPlugin(BasePluginConvention.class);
      if (basePluginConvention == null) {
        throw new GradleException("base plugin convention not found");
      }
      capsuleExtension = getProject().getExtensions().findByType(CapsuleExtension.class);
      if (capsuleExtension == null) {
        throw new GradleException("capsule extension not found");
      }
    }

    private void action()
        throws IOException {
      final String mainClass = doGetMainClass();
      final Configuration capsuleConfiguration = doGetCapsuleConfiguration();
      final Configuration embedConfiguration = doGetEmbedConfiguration();
      final Map<String, String> manifestAttributes = doGetManifestAttributes();

      final Set<ResolvedArtifact> capsuleResolvedArtifacts = capsuleConfiguration
          .getResolvedConfiguration().getResolvedArtifacts();
      final Set<ResolvedArtifact> runtimeResolvedArtifacts = embedConfiguration
          .getResolvedConfiguration().getResolvedArtifacts();

      final File libsDir = basePluginConvention.getLibsDirectory().getAsFile().get();
      libsDir.mkdirs();
      final File outputFile = new File(libsDir, getArchiveFileName());

      final Manifest manifest = new Manifest();
      for (final Map.Entry<String, String> entry : manifestAttributes.entrySet()) {
        manifest.getMainAttributes().putValue(entry.getKey(), entry.getValue());
      }

      try (final CapsulePackager capsulePackager = new CapsulePackager(new FileOutputStream(outputFile), mainClass,
          manifest)) {
        for (final ResolvedArtifact resolvedArtifact : capsuleResolvedArtifacts) {
          getLogger().info("adding boot classes: {}", resolvedArtifact.getModuleVersion());
          capsulePackager.addBootJar(resolvedArtifact.getFile());
        }

        for (final Object dependsOn : getDependsOn()) {
          Jar jar = null;
          if (dependsOn instanceof String) {
            final Task task = project.getTasks().findByName((String) dependsOn);
            if (task instanceof Jar) {
              jar = (Jar) task;
            }
          } else if (dependsOn instanceof Task) {
            if (dependsOn instanceof Jar) {
              jar = (Jar) dependsOn;
            }
          }
          if (jar != null) {
            final FileCollection jarOutputFiles = jar.getOutputs().getFiles();
            for (final File jarOutputFile : jarOutputFiles) {
              getLogger().info("adding main JAR: {}", jarOutputFile.getName());
              capsulePackager.addMainJar(jarOutputFile);
            }
          }
        }

        for (final ResolvedArtifact resolvedArtifact : runtimeResolvedArtifacts) {
          getLogger().info("adding lib JAR: {}", resolvedArtifact.getModuleVersion());
          capsulePackager.addLibJar(resolvedArtifact.getFile());
        }
      }
    }

    private String getArchiveFileName() {
      final Property<String> projectVersionProperty = project.getObjects().property(String.class);
      if (project.getVersion() != null) {
        final String projectVersion = project.getVersion().toString();
        if (!projectVersion.equals("unspecified")) {
          projectVersionProperty.set(projectVersion);
        }
      }

      final List<Provider<String>> archiveNamePartProviders = new ArrayList<>();
      archiveNamePartProviders.add(getArchiveBaseName()
          .orElse(capsuleExtension.getArchiveBaseName())
          .orElse(basePluginConvention.getArchivesBaseName()));
      archiveNamePartProviders.add(getArchiveAppendix()
          .orElse(capsuleExtension.getArchiveAppendix()));
      archiveNamePartProviders.add(getArchiveVersion()
          .orElse(capsuleExtension.getArchiveVersion())
          .orElse(projectVersionProperty));
      archiveNamePartProviders.add(getArchiveClassifier()
          .orElse(capsuleExtension.getArchiveClassifier())
          .orElse(DEFAULT_CLASSIFIER));

      final List<String> archiveNameParts = archiveNamePartProviders.stream()
          .filter(Provider::isPresent)
          .map(Provider::get)
          .filter(part -> !part.isBlank())
          .collect(Collectors.toList());

      final String extension = getArchiveExtension()
          .orElse(capsuleExtension.getArchiveExtension())
          .getOrElse(DEFAULT_EXTENSION);

      return StringUtils.join(archiveNameParts, "-") + "." + extension;
    }

    private Configuration doGetCapsuleConfiguration() {
      Configuration configuration = getProject().getConfigurations().findByName("capsule");
      if (configuration == null) {
        configuration = getProject().getConfigurations().create("capsule");
        getProject().getDependencies().add("capsule",
            String.format("co.paralleluniverse:capsule:%s", doGetCapsuleVersion()));
      }
      return configuration;
    }

    private String doGetCapsuleVersion() {
      return capsuleExtension.getVersion().getOrElse(DEFAULT_CAPSULE_VERSION);
    }

    private Configuration doGetEmbedConfiguration() {
      return getEmbedConfiguration()
          .orElse(capsuleExtension.getEmbedConfiguration())
          .getOrElse(getProject().getConfigurations().getByName("runtimeClasspath"));
    }

    private String doGetMainClass() {
      if (getMainClass().isPresent()) {
        return getMainClass().get();
      }
      final JavaApplication javaApplication = getProject().getExtensions().findByType(JavaApplication.class);
      if (javaApplication == null) {
        throw new GradleException("mainClass not specified");
      }
      if (javaApplication.getMainClass().isPresent()) {
        return javaApplication.getMainClass().get();
      }
      throw new GradleException("mainClass not specified");
    }

    private Map<String, String> doGetManifestAttributes() {
      return getManifestAttributes()
          .orElse(capsuleExtension.getManifestAttributes())
          .getOrElse(new HashMap<>());
    }
  }
}
