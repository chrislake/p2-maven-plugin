/**
 * Copyright (c) 2012 Reficio (TM) - Reestablish your software! All Rights Reserved.
 *
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.reficio.p2;

import com.google.common.base.Preconditions;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.Multimap;
import com.google.common.collect.Sets;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.AbstractMojoExecutionException;
import org.apache.maven.plugin.BuildPluginManager;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.plugins.annotations.*;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.PlexusConstants;
import org.codehaus.plexus.PlexusContainer;
import org.codehaus.plexus.component.annotations.Requirement;
import org.codehaus.plexus.component.repository.exception.ComponentLookupException;
import org.codehaus.plexus.context.Context;
import org.codehaus.plexus.context.ContextException;
import org.codehaus.plexus.personality.plexus.lifecycle.phase.Contextualizable;
import org.eclipse.sisu.equinox.launching.internal.P2ApplicationLauncher;
import org.reficio.p2.bundler.ArtifactBundler;
import org.reficio.p2.bundler.ArtifactBundlerInstructions;
import org.reficio.p2.bundler.ArtifactBundlerRequest;
import org.reficio.p2.bundler.impl.AquteBundler;
import org.reficio.p2.logger.Logger;
import org.reficio.p2.mirror.BundleMirror;
import org.reficio.p2.publisher.BundlePublisher;
import org.reficio.p2.publisher.CategoryPublisher;
import org.reficio.p2.resolver.eclipse.EclipseResolutionRequest;
import org.reficio.p2.resolver.eclipse.EclipseResolutionRequest.EclipseType;
import org.reficio.p2.resolver.eclipse.impl.DefaultEclipseResolver;
import org.reficio.p2.resolver.maven.Artifact;
import org.reficio.p2.resolver.maven.ArtifactResolutionRequest;
import org.reficio.p2.resolver.maven.ArtifactResolutionResult;
import org.reficio.p2.resolver.maven.ArtifactResolver;
import org.reficio.p2.resolver.maven.ResolvedArtifact;
import org.reficio.p2.resolver.maven.impl.AetherResolver;
import org.reficio.p2.utils.JarUtils;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Main plugin class
 *
 * @author Tom Bujok (tom.bujok@gmail.com)<br>
 *         Reficio (TM) - Reestablish your software!<br>
 *         http://www.reficio.org
 * @since 1.0.0
 */
@Mojo(
        name = "site",
        defaultPhase = LifecyclePhase.COMPILE,
        requiresDependencyResolution = ResolutionScope.RUNTIME,
        requiresDependencyCollection = ResolutionScope.RUNTIME
)
public class P2Mojo extends AbstractMojo implements Contextualizable {

    private static final String BUNDLES_TOP_FOLDER = "/source";
    private static final String FEATURES_DESTINATION_FOLDER = BUNDLES_TOP_FOLDER + "/features";
    private static final String BUNDLES_DESTINATION_FOLDER = BUNDLES_TOP_FOLDER + "/plugins";
    private static final String DEFAULT_CATEGORY_FILE = "category.xml";
    private static final String DEFAULT_CATEGORY_CLASSPATH_LOCATION = "/";

    @Parameter(defaultValue = "${project}", required = true, readonly = true)
    private MavenProject project;

    @Parameter(defaultValue = "${session}", required = true, readonly = true)
    private MavenSession session;

    @Component
    @Requirement
    private BuildPluginManager pluginManager;

    @Parameter(defaultValue = "${project.build.directory}", required = true)
    private String buildDirectory;

    @Parameter(defaultValue = "${project.build.directory}/repository", required = true)
    private String destinationDirectory;

    @Component
    @Requirement
    private P2ApplicationLauncher launcher;


    /**
     * Specifies a file containing category definitions.
     */
    @Parameter(defaultValue = "")
    private String categoryFileURL;

    /**
     * Optional line of additional arguments passed to the p2 application launcher.
     */
    @Parameter(defaultValue = "false")
    private boolean append;

    /**
     * Optional argument to globally set source requirement.
     */
    @Parameter(defaultValue = "false")
    private boolean includeDependencies;

    /**
     * Optional argument to globally set source requirement.
     */
    @Parameter(defaultValue = "false")
    private boolean dependenciesSource;

    /**
     * Optional argument to globally set transitive requirement
     */
    @Parameter(defaultValue = "true")
    private boolean dependenciesTransitive;

    /**
     * Optional line of additional arguments passed to the p2 application launcher.
     */
    @Parameter(defaultValue = "false")
    private boolean pedantic;

    /**
     * Specifies whether to compress generated update site.
     */
    @Parameter(defaultValue = "true")
    private boolean compressSite;

    /**
     * Specifies whether to create the categories xml file for the site.
     */
    @Parameter(defaultValue = "true")
    private boolean createCategories;

    /**
     * Specifies whether to re-process artifacts already in the destinationDirectory.
     */
    @Parameter(defaultValue = "false")
    private boolean skipExisting;

    /**
     * Kill the forked process after a certain number of seconds. If set to 0, wait forever for the
     * process, never timing out.
     */
    @Parameter(defaultValue = "0", alias = "p2.timeout")
    private int forkedProcessTimeoutInSeconds;

    /**
     * Specifies additional arguments to p2Launcher, for example -consoleLog -debug -verbose
     */
    @Parameter(defaultValue = "")
    private String additionalArgs;

    /**
     * Dependency injection container - used to get some components programatically
     */
    private PlexusContainer container;

    /**
     * Aether Repository System
     * Declared as raw Object type as different objects are injected in different Maven versions:
     * * 3.0.0 and above -> org.sonatype.aether...
     * * 3.1.0 and above -> org.eclipse.aether...
     */
    private Object repoSystem;

    /**
     * The current repository/network configuration of Maven.
     */
    @Parameter(defaultValue = "${repositorySystemSession}", readonly = true, required = true)
    private Object repoSession;

    /**
     * The project's remote repositories to use for the resolution of project dependencies.
     */
    @Parameter(defaultValue = "${project.remoteProjectRepositories}", readonly = true, required = true)
    private List<Object> projectRepos;

    @Parameter(readonly = true)
    private List<P2Artifact> artifacts;

    /**
     * A list of artifacts that define eclipse features
     */
    @Parameter(readonly = true)
    private List<P2Artifact> features;

    /**
     * A list of Eclipse artifacts that should be downloaded from P2 repositories
     */
    @Parameter(readonly = true)
    private List<EclipseArtifact> p2;

    /**
     * A list of Eclipse features that should be downloaded from P2 repositories
     */
    @Parameter(readonly = true)
    private List<EclipseFeature> p2Features;

    /**
     * Logger retrieved from the Maven internals.
     * It's the recommended way to do it...
     */
    private Log log;

    /**
     * Folder which the jar files bundled by the ArtifactBundler will be copied to
     */
    private File bundlesDestinationFolder;

    /**
     * Folder which the feature jar files bundled by the ArtifactBundler will be copied to
     */
    private File featuresDestinationFolder;

    /**
     * Processing entry point.
     * Method that orchestrates the execution of the plugin.
     */
    @Override
    public void execute() {
        try {
            initializeEnvironment();
            initializeRepositorySystem();
            processDependencies();
            processArtifacts();
            processFeatures();
            processEclipseArtifacts();
            processEclipseFeatures();
            executeP2PublisherPlugin();
            executeCategoryPublisher();
            cleanupEnvironment();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void initializeEnvironment() throws IOException {
        log = getLog();
        Logger.initialize(log);
        bundlesDestinationFolder = new File(buildDirectory, BUNDLES_DESTINATION_FOLDER);
        featuresDestinationFolder = new File(buildDirectory, FEATURES_DESTINATION_FOLDER);
        FileUtils.deleteDirectory(new File(buildDirectory, BUNDLES_TOP_FOLDER));
        FileUtils.forceMkdir(bundlesDestinationFolder);
        FileUtils.forceMkdir(featuresDestinationFolder);
        artifacts = artifacts != null ? artifacts : new ArrayList<P2Artifact>();
        features = features != null ? features : new ArrayList<P2Artifact>();
        p2 = p2 != null ? p2 : new ArrayList<EclipseArtifact>();
        p2Features = p2Features != null ? p2Features : new ArrayList<EclipseFeature>();
    }

    private void initializeRepositorySystem() {
        if (repoSystem == null) {
            repoSystem = lookup("org.eclipse.aether.RepositorySystem");
        }
        if (repoSystem == null) {
            repoSystem = lookup("org.sonatype.aether.RepositorySystem");
        }
        Preconditions.checkNotNull(repoSystem, "Could not initialize RepositorySystem");
    }

    private Object lookup(String role) {
        try {
            return container.lookup(role);
        } catch (ComponentLookupException ex) {
        }
        return null;
    }

    private void processDependencies() {
        if (includeDependencies) {
            for (org.apache.maven.artifact.Artifact defArtifact : project.getDependencyArtifacts()) {
                P2Artifact p2Artifact = new P2Artifact();
                p2Artifact.setId(defArtifact.getGroupId() + ":" + defArtifact.getArtifactId() + ":" + defArtifact.getVersion());
                p2Artifact.setIncludeSources(dependenciesSource);
                p2Artifact.setTransitive(dependenciesTransitive);
                artifacts.add(p2Artifact);
            }
        }
    }

    private void processArtifacts() {
        Multimap<P2Artifact, ResolvedArtifact> resolvedArtifacts = resolveArtifacts();
        log.info("Resolving " + resolvedArtifacts.size() + " artifacts");
        Set<Artifact> processedArtifacts = processRootArtifacts(resolvedArtifacts);
        processTransitiveArtifacts(resolvedArtifacts, processedArtifacts);
    }

    private Set<Artifact> processRootArtifacts(Multimap<P2Artifact, ResolvedArtifact> processedArtifacts) {
        Set<Artifact> bundledArtifacts = Sets.newHashSet();
        for (P2Artifact p2Artifact : artifacts) {
            for (ResolvedArtifact resolvedArtifact : processedArtifacts.get(p2Artifact)) {
                if (resolvedArtifact.isRoot()) {
                    if (bundledArtifacts.add(resolvedArtifact.getArtifact())) {
                        bundleArtifact(p2Artifact, resolvedArtifact);
                    } else {
                        String message = String.format("p2-maven-plugin misconfiguration" +
                                "\n\n\tJar [%s] is configured as an artifact multiple times. " +
                                "\n\tRemove the duplicate artifact definitions.\n", resolvedArtifact.getArtifact());
                        throw new RuntimeException(message);
                    }
                }
            }
        }
        return bundledArtifacts;
    }

    private void processTransitiveArtifacts(Multimap<P2Artifact, ResolvedArtifact> resolvedArtifacts, Set<Artifact> bundledArtifacts) {
        // then bundle transitive artifacts
        for (P2Artifact p2Artifact : artifacts) {
            for (ResolvedArtifact resolvedArtifact : resolvedArtifacts.get(p2Artifact)) {
                if (!resolvedArtifact.isRoot()) {
                    if (bundledArtifacts.add(resolvedArtifact.getArtifact())) {
                        bundleArtifact(p2Artifact, resolvedArtifact);
                    } else {
                        log.debug(String.format("Not bundling transitive dependency since it has already been bundled [%s]", resolvedArtifact.getArtifact()));
                    }
                }
            }
        }
    }

    private void processFeatures() {
        // artifacts should already have been resolved by processArtifacts()
        Multimap<P2Artifact, ResolvedArtifact> resolvedFeatures = resolveFeatures();
        // then bundle the artifacts including the transitive dependencies (if specified so)
        log.info("Resolving " + resolvedFeatures.size() + " features");
        for (P2Artifact p2Artifact : features) {
            for (ResolvedArtifact resolvedArtifact : resolvedFeatures.get(p2Artifact)) {
                handleFeature(p2Artifact, resolvedArtifact);
            }
        }
    }

    private Multimap<P2Artifact, ResolvedArtifact> resolveArtifacts() {
        Multimap<P2Artifact, ResolvedArtifact> resolvedArtifacts = ArrayListMultimap.create();
        for (P2Artifact p2Artifact : artifacts) {
            logResolving(p2Artifact);
            ArtifactResolutionResult resolutionResult = resolveArtifact(p2Artifact);
            resolvedArtifacts.putAll(p2Artifact, resolutionResult.getResolvedArtifacts());
        }
        return resolvedArtifacts;
    }

    private Multimap<P2Artifact, ResolvedArtifact> resolveFeatures() {
        Multimap<P2Artifact, ResolvedArtifact> resolvedArtifacts = ArrayListMultimap.create();
        for (P2Artifact p2Artifact : features) {
            logResolving(p2Artifact);
            ArtifactResolutionResult resolutionResult = resolveArtifact(p2Artifact);
            resolvedArtifacts.putAll(p2Artifact, resolutionResult.getResolvedArtifacts());
        }
        return resolvedArtifacts;
    }

    private void logResolving(EclipseArtifact p2) {
        log.debug(String.format("Resolving artifact=[%s] source=[%s]", p2.getId(),
                p2.shouldIncludeSources()));
    }

    private void logResolving(EclipseFeature p2Feature) {
        log.debug(String.format("Resolving feature=[%s]", p2Feature.getId()));
    }

    private void logResolving(P2Artifact p2) {
        log.debug(String.format("Resolving artifact=[%s] transitive=[%s] source=[%s]", p2.getId(), p2.shouldIncludeTransitive(),
                p2.shouldIncludeSources()));
    }

    private ArtifactResolutionResult resolveArtifact(P2Artifact p2Artifact) {
        ArtifactResolutionRequest resolutionRequest = ArtifactResolutionRequest.builder()
                .rootArtifactId(p2Artifact.getId())
                .resolveSource(p2Artifact.shouldIncludeSources())
                .resolveTransitive(p2Artifact.shouldIncludeTransitive())
                .excludes(p2Artifact.getExcludes())
                .build();
        ArtifactResolutionResult resolutionResult = getArtifactResolver().resolve(resolutionRequest);
        logResolved(resolutionRequest, resolutionResult);
        return resolutionResult;
    }

    private ArtifactResolver getArtifactResolver() {
        return new AetherResolver(repoSystem, repoSession, projectRepos);
    }

    private void logResolved(ArtifactResolutionRequest resolutionRequest, ArtifactResolutionResult resolutionResult) {
        for (ResolvedArtifact resolvedArtifact : resolutionResult.getResolvedArtifacts()) {
            log.debug("\t [JAR] " + resolvedArtifact.getArtifact());
            if (resolvedArtifact.getSourceArtifact() != null) {
                log.debug("\t [SRC] " + resolvedArtifact.getSourceArtifact().toString());
            } else if (resolutionRequest.isResolveSource()) {
                log.debug("\t [SRC] Failed to resolve source for artifact " + resolvedArtifact.getArtifact().toString());
            }
        }
    }

    private void bundleArtifact(P2Artifact p2Artifact, ResolvedArtifact resolvedArtifact) {
        P2Validator.validateBundleRequest(p2Artifact, resolvedArtifact);
        ArtifactBundler bundler = getArtifactBundler();
        ArtifactBundlerInstructions bundlerInstructions = P2Helper.createBundlerInstructions(p2Artifact, resolvedArtifact);
        ArtifactBundlerRequest bundlerRequest = P2Helper.createBundlerRequest(p2Artifact, resolvedArtifact, bundlesDestinationFolder);
        bundler.execute(bundlerRequest, bundlerInstructions);
    }

    private void handleFeature(P2Artifact p2Artifact, ResolvedArtifact resolvedArtifact) {
        log.debug("Handling feature " + p2Artifact.getId());
        ArtifactBundlerRequest bundlerRequest = P2Helper.createBundlerRequest(p2Artifact, resolvedArtifact, featuresDestinationFolder);
        try {
            File inputFile = bundlerRequest.getBinaryInputFile();
            File outputFile = bundlerRequest.getBinaryOutputFile();
            //This will also copy the input to the output
            JarUtils.adjustFeatureQualifierVersionWithTimestamp(inputFile, outputFile);
            log.info("Copied " + inputFile + " to " + outputFile);
        } catch (Exception ex) {
            throw new RuntimeException("Error while bundling jar or source: " + bundlerRequest.getBinaryInputFile().getName(), ex);
        }
    }

    private void processEclipseArtifacts() {
        DefaultEclipseResolver resolver = new DefaultEclipseResolver(projectRepos, bundlesDestinationFolder);
        log.info("Resolving " + p2.size() + " p2 artifacts");
        for (EclipseArtifact artifact : p2) {
            String[] tokens = artifact.getId().split(":");
            if (tokens.length != 2) {
                throw new RuntimeException("Wrong format " + artifact.getId());
            }
            boolean alreadyDownloaded = new File(destinationDirectory + "/plugins", artifact.getId().replace(":", "_") + ".jar").exists();
            if (artifact.shouldIncludeSources()) {
                alreadyDownloaded = alreadyDownloaded && new File(destinationDirectory + "/plugins", artifact.getId().replace(":", ".source_") + ".jar").exists();
            }
            if (!(alreadyDownloaded && skipExisting)) {
                logResolving(artifact);
                EclipseResolutionRequest request = new EclipseResolutionRequest(tokens[0], tokens[1], artifact.shouldIncludeSources(), EclipseType.PLUGIN);
                resolver.resolve(request);
            }
        }
    }

    private void processEclipseFeatures() throws IOException, MojoExecutionException {
        DefaultEclipseResolver resolver = new DefaultEclipseResolver(projectRepos, featuresDestinationFolder);
        log.info("Resolving " + p2Features.size() + " p2 features");
        for (EclipseFeature feature : p2Features) {
            String[] tokens = feature.getId().split(":");
            if (tokens.length != 2) {
                throw new RuntimeException("Wrong format " + feature.getId());
            }
            boolean alreadyDownloaded = new File(destinationDirectory + "/features", feature.getId().replace(":", "_") + ".jar").exists();
            if (!(alreadyDownloaded && skipExisting)) {
                logResolving(feature);
                EclipseResolutionRequest request = new EclipseResolutionRequest(tokens[0], tokens[1], false, EclipseType.FEATURE);
                resolver.resolve(request);

                if (feature.isTransitive()) {
                    BundleMirror mirror;
//                  if (skipExisting) {
//                      mirror = BundleMirror.builder()
//                          .mavenProject(project)
//                          .mavenSession(session)
//                          .buildPluginManager(pluginManager)
//
//                          .sourceURL(request.getSourceURL())
//                          .iuId(tokens[0] + ".feature.group")
//                          .iuVersion(tokens[1])
//                          .includePacked(false)
//                          .followStrictOnly(true)
//                          .append(append)
//                          .additionalArgs("-compare -compareAgainst " + request.getSourceURL())
//                          .destination(buildDirectory + BUNDLES_TOP_FOLDER)
//                          .build();
//                  }
//                  else {
                        mirror = BundleMirror.builder()
                                .mavenProject(project)
                                .mavenSession(session)
                                .buildPluginManager(pluginManager)

                                .sourceURL(request.getSourceURL())
                                .iuId(tokens[0] + ".feature.group")
                                .iuVersion(tokens[1])
                                .includePacked(false)
                                .followStrictOnly(true)
                                .append(append)
                                .destination(buildDirectory + BUNDLES_TOP_FOLDER)
                                .filter(feature.getFilter())
                                .build();
//                  }
                    mirror.execute();
                }
            }
        }
    }

    private ArtifactBundler getArtifactBundler() {
        return new AquteBundler(pedantic);
    }

    private void executeP2PublisherPlugin() throws IOException, MojoExecutionException {
        prepareDestinationDirectory();
        BundlePublisher publisher = BundlePublisher.builder()
                .mavenProject(project)
                .mavenSession(session)
                .buildPluginManager(pluginManager)
                .compressSite(compressSite)
                .append(append)
                .additionalArgs(additionalArgs)
                .artifactRepositoryLocation(destinationDirectory)
                .metadataRepositoryLocation(destinationDirectory)
                .build();
        publisher.execute();
    }

    private void prepareDestinationDirectory() throws IOException {
        if (!append) {
            FileUtils.deleteDirectory(new File(destinationDirectory));
        }
    }

    private void executeCategoryPublisher() throws AbstractMojoExecutionException, IOException {
        if (createCategories) {
            prepareCategoryLocationFile();
            CategoryPublisher publisher = CategoryPublisher.builder()
                    .p2ApplicationLauncher(launcher)
                    .additionalArgs(additionalArgs)
                    .forkedProcessTimeoutInSeconds(forkedProcessTimeoutInSeconds)
                    .categoryFileLocation(categoryFileURL)
                    .metadataRepositoryLocation(destinationDirectory)
                    .build();
            publisher.execute();
        }
    }

    private void prepareCategoryLocationFile() throws IOException {
        if (StringUtils.isBlank(categoryFileURL)) {
            InputStream is = getClass().getResourceAsStream(DEFAULT_CATEGORY_CLASSPATH_LOCATION + DEFAULT_CATEGORY_FILE);
            File destinationFolder = new File(destinationDirectory);
            destinationFolder.mkdirs();
            File categoryDefinitionFile = new File(destinationFolder, DEFAULT_CATEGORY_FILE);
            FileWriter writer = new FileWriter(categoryDefinitionFile);
            IOUtils.copy(is, writer, "UTF-8");
            IOUtils.closeQuietly(writer);
            categoryFileURL = categoryDefinitionFile.getAbsolutePath();
        }
    }

    private void cleanupEnvironment() throws IOException {
        File workFolder = new File(buildDirectory, BUNDLES_TOP_FOLDER);
        try {
            FileUtils.deleteDirectory(workFolder);
        } catch (IOException ex) {
            log.warn("Cannot cleanup the work folder " + workFolder.getAbsolutePath());
        }
    }

    @Override
    public void contextualize(Context context) throws ContextException {
        this.container = (PlexusContainer) context.get(PlexusConstants.PLEXUS_KEY);
    }

}
