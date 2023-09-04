package com.github.ngyewch.gradle;

import org.apache.commons.lang3.StringUtils;
import org.gradle.api.*;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.ResolvedArtifact;
import org.gradle.api.file.FileCollection;
import org.gradle.api.model.ObjectFactory;
import org.gradle.api.plugins.BasePluginExtension;
import org.gradle.api.plugins.JavaApplication;
import org.gradle.api.provider.MapProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.TaskAction;
import org.gradle.api.tasks.bundling.Jar;

import javax.inject.Inject;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.*;
import java.util.jar.Attributes;
import java.util.jar.Manifest;
import java.util.stream.Collectors;

public abstract class PackageCapsuleTask
    extends DefaultTask {

  private static final String DEFAULT_CAPSULE_GROUP = "io.nextflow";
  private static final String DEFAULT_CAPSULE_VERSION = "1.1.1";
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
  public abstract MapProperty<String, String> getManifestAttributes();

  @Input
  private final CapsuleManifest capsuleManifest;

  @Inject
  public PackageCapsuleTask(ObjectFactory objectFactory) {
    super();

    capsuleManifest = objectFactory.newInstance(CapsuleManifest.class);
  }

  public CapsuleManifest getCapsuleManifest() {
    return capsuleManifest;
  }

  public void capsuleManifest(Action<CapsuleManifest> action) {
    action.execute(capsuleManifest);
  }

  @TaskAction
  public void action()
      throws IOException {
    final TaskHandler taskHandler = new TaskHandler();
    taskHandler.action();
  }

  private class TaskHandler {

    private final Project project;
    private final BasePluginExtension basePluginExtension;
    private final CapsuleExtension capsuleExtension;

    public TaskHandler() {
      super();

      project = getProject();
      basePluginExtension = project.getExtensions().findByType(BasePluginExtension.class);
      if (basePluginExtension == null) {
        throw new GradleException("base plugin extension not found");
      }
      capsuleExtension = getProject().getExtensions().findByType(CapsuleExtension.class);
      if (capsuleExtension == null) {
        throw new GradleException("capsule extension not found");
      }
    }

    private void action()
        throws IOException {
      final Configuration capsuleConfiguration = doGetCapsuleConfiguration();
      final Configuration embedConfiguration = doGetEmbedConfiguration();
      final Map<String, String> manifestAttributes = doGetManifestAttributes();

      final Set<ResolvedArtifact> capsuleResolvedArtifacts = capsuleConfiguration
          .getResolvedConfiguration().getResolvedArtifacts();
      final Set<ResolvedArtifact> runtimeResolvedArtifacts = embedConfiguration
          .getResolvedConfiguration().getResolvedArtifacts();

      final File libsDir = basePluginExtension.getLibsDirectory().getAsFile().get();
      libsDir.mkdirs();
      final File outputFile = new File(libsDir, getArchiveFileName());

      final Manifest manifest = createManifest();
      for (final Map.Entry<String, String> entry : manifestAttributes.entrySet()) {
        manifest.getMainAttributes().putValue(entry.getKey(), entry.getValue());
      }

      try (final CapsulePackager capsulePackager = new CapsulePackager(Files.newOutputStream(outputFile.toPath()), manifest)) {
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
      final String projectVersion = project.getVersion().toString();
      if (!projectVersion.equals("unspecified")) {
        projectVersionProperty.set(projectVersion);
      }

      final List<Provider<String>> archiveNamePartProviders = new ArrayList<>();
      archiveNamePartProviders.add(getArchiveBaseName()
          .orElse(capsuleExtension.getArchiveBaseName())
          .orElse(basePluginExtension.getArchivesName()));
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
          .filter(part -> !StringUtils.isBlank(part))
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
            String.format("%s:capsule:%s", doGetCapsuleGroup(), doGetCapsuleVersion()));
      }
      return configuration;
    }

    private String doGetCapsuleGroup() {
      return capsuleExtension.getGroup().getOrElse(DEFAULT_CAPSULE_GROUP);
    }

    private String doGetCapsuleVersion() {
      return capsuleExtension.getVersion().getOrElse(DEFAULT_CAPSULE_VERSION);
    }

    private Configuration doGetEmbedConfiguration() {
      return getEmbedConfiguration()
          .orElse(capsuleExtension.getEmbedConfiguration())
          .getOrElse(getProject().getConfigurations().getByName("runtimeClasspath"));
    }

    private Map<String, String> doGetManifestAttributes() {
      return getManifestAttributes()
          .orElse(capsuleExtension.getManifestAttributes())
          .getOrElse(new HashMap<>());
    }

    private Manifest createManifest() {
      final CompositeCapsuleManifest merged = new CompositeCapsuleManifest();

      final Manifest manifest = new Manifest();
      final Attributes mainAttributes = manifest.getMainAttributes();
      mainAttributes.put(Attributes.Name.MANIFEST_VERSION, "1.0");

      mainAttributes.put(Attributes.Name.MAIN_CLASS, "Capsule");
      mainAttributes.putValue("Premain-Class", "Capsule");

      populateManifest(mainAttributes, "Application-ID", merged.getApplicationId());
      populateManifest(mainAttributes, "Application-Version", merged.getApplicationVersion());
      populateManifest(mainAttributes, "Application-Name", merged.getApplicationName());
      populateManifest(mainAttributes, "Application-Class", merged.getApplicationClass());
      populateManifest(mainAttributes, "Min-Java-Version", merged.getMinJavaVersion());
      populateManifest(mainAttributes, "Java-Version", merged.getJavaVersion());
      populateManifestBoolean(mainAttributes, "JDK-Required", merged.getJdkRequired());
      populateManifestStringList(mainAttributes, "JVM-Args", merged.getJvmArgs());
      populateManifestStringList(mainAttributes, "Args", merged.getArgs());
      populateManifestStringMap(mainAttributes, "Environment-Variables", merged.getEnvironmentVariables());
      populateManifestStringMap(mainAttributes, "System-Properties", merged.getSystemProperties());

      return manifest;
    }

    private void populateManifest(Attributes attributes, String name, Provider<String> stringProvider) {
      if (stringProvider.isPresent()) {
        attributes.putValue(name, stringProvider.get());
      }
    }

    private void populateManifestBoolean(Attributes attributes, String name, Provider<Boolean> booleanProvider) {
      if (booleanProvider.isPresent()) {
        attributes.putValue(name, booleanProvider.get().toString());
      }
    }

    private void populateManifestStringMap(Attributes attributes, String name,
                                           Provider<Map<String, String>> stringMapProvider) {
      if (stringMapProvider.isPresent()) {
        if (stringMapProvider.get().isEmpty()) {
          return;
        }
        attributes.putValue(name, stringMapProvider.get().entrySet().stream()
            .map(entry -> String.format("%s=%s", entry.getKey(), entry.getValue()))
            .collect(Collectors.joining(" ")));
      }
    }

    private void populateManifestStringList(Attributes attributes, String name,
                                            Provider<List<String>> stringListProvider) {
      if (stringListProvider.isPresent()) {
        if (stringListProvider.get().isEmpty()) {
          return;
        }
        attributes.putValue(name, StringUtils.join(stringListProvider.get(), " "));
      }
    }

    private class CompositeCapsuleManifest {

      private final CapsuleManifest taskCapsuleManifest;
      private final CapsuleManifest extensionCapsuleManifest;

      private CompositeCapsuleManifest() {
        super();

        taskCapsuleManifest = capsuleManifest;
        extensionCapsuleManifest = capsuleExtension.getCapsuleManifest();
      }

      public Provider<String> getApplicationId() {
        return taskCapsuleManifest.getApplicationId()
            .orElse(extensionCapsuleManifest.getApplicationId());
      }

      public Provider<String> getApplicationVersion() {
        return taskCapsuleManifest.getApplicationVersion()
            .orElse(extensionCapsuleManifest.getApplicationVersion())
            .orElse(project.getProviders().provider(() -> {
              final String version = project.getVersion().toString();
              if (version.equals("unspecified")) {
                return null;
              }
              return version;
            }));
      }

      public Provider<String> getApplicationName() {
        return taskCapsuleManifest.getApplicationName()
            .orElse(extensionCapsuleManifest.getApplicationName());
      }

      public Provider<String> getApplicationClass() {
        return taskCapsuleManifest.getApplicationClass()
            .orElse(extensionCapsuleManifest.getApplicationClass())
            .orElse(project.getProviders().provider(() -> {
              final JavaApplication javaApplication = getProject().getExtensions().findByType(JavaApplication.class);
              if (javaApplication == null) {
                throw new GradleException("mainClass not specified");
              }
              if (javaApplication.getMainClass().isPresent()) {
                return javaApplication.getMainClass().get();
              }
              throw new GradleException("mainClass not specified");
            }));
      }

      public Provider<String> getMinJavaVersion() {
        return taskCapsuleManifest.getMinJavaVersion()
            .orElse(extensionCapsuleManifest.getMinJavaVersion());
      }

      public Provider<String> getJavaVersion() {
        return taskCapsuleManifest.getJavaVersion()
            .orElse(extensionCapsuleManifest.getJavaVersion());
      }

      public Provider<Boolean> getJdkRequired() {
        return taskCapsuleManifest.getJdkRequired()
            .orElse(extensionCapsuleManifest.getJdkRequired());
      }

      public Provider<List<String>> getJvmArgs() {
        return taskCapsuleManifest.getJvmArgs().map(ListTransformer.INSTANCE)
            .orElse(extensionCapsuleManifest.getJvmArgs().map(ListTransformer.INSTANCE));
      }

      public Provider<List<String>> getArgs() {
        return taskCapsuleManifest.getArgs().map(ListTransformer.INSTANCE)
            .orElse(extensionCapsuleManifest.getArgs().map(ListTransformer.INSTANCE));
      }

      public Provider<Map<String, String>> getEnvironmentVariables() {
        return taskCapsuleManifest.getEnvironmentVariables().map(MapTransformer.INSTANCE)
            .orElse(extensionCapsuleManifest.getEnvironmentVariables().map(MapTransformer.INSTANCE));
      }

      public Provider<Map<String, String>> getSystemProperties() {
        return taskCapsuleManifest.getSystemProperties().map(MapTransformer.INSTANCE)
            .orElse(extensionCapsuleManifest.getSystemProperties().map(MapTransformer.INSTANCE));
      }
    }
  }

  private static class ListTransformer
      implements Transformer<List<String>, List<String>> {

    private static final ListTransformer INSTANCE = new ListTransformer();

    @Override
    public List<String> transform(List<String> strings) {
      if ((strings == null) || strings.isEmpty()) {
        return null;
      }
      return strings;
    }
  }

  private static class MapTransformer
      implements Transformer<Map<String, String>, Map<String, String>> {

    private static final MapTransformer INSTANCE = new MapTransformer();

    @Override
    public Map<String, String> transform(Map<String, String> stringMap) {
      if ((stringMap == null) || stringMap.isEmpty()) {
        return null;
      }
      return stringMap;
    }
  }
}
