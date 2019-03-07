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
package org.reficio.p2.bundler.impl;

import aQute.bnd.osgi.Analyzer;
import aQute.bnd.osgi.Jar;
import org.apache.commons.io.FileUtils;
import org.reficio.p2.bundler.ArtifactBundler;
import org.reficio.p2.bundler.ArtifactBundlerInstructions;
import org.reficio.p2.bundler.ArtifactBundlerRequest;
import org.reficio.p2.logger.Logger;
import org.reficio.p2.utils.BundleUtils;
import org.reficio.p2.utils.JarUtils;

import java.io.File;
import java.io.IOException;
import java.util.jar.Attributes;
import java.util.jar.Manifest;

/**
 * @author Tom Bujok (tom.bujok@gmail.com)<br>
 *         Reficio (TM) - Reestablish your software!<br>
 *         http://www.reficio.org
 * @since 1.0.0
 */
public class AquteBundler implements ArtifactBundler {

    public static final String ECLIPSE_SOURCE_BUNDLE = "Eclipse-SourceBundle";
    public static final String IMPLEMENTATION_TITLE = "Implementation-Title";
    public static final String SPECIFICATION_TITLE = "Specification-Title";
    public static final String MANIFEST_VERSION = "Manifest-Version";

    protected final BundleUtils bundleUtils;
    private final boolean pedantic;

    public AquteBundler(boolean pedantic) {
        this.bundleUtils = new BundleUtils();
        this.pedantic = pedantic;
    }

    @Override
    public void execute(ArtifactBundlerRequest request, ArtifactBundlerInstructions instructions, String finalDestinationDirectory) {


        String proposedBinaryJarName = request.getBinaryOutputFile().getName();
        proposedBinaryJarName = instructions.getSymbolicName() + "_" + instructions.getVersion() + ".jar";

        String proposedSourceJarName = "";
        if (request.isShouldBundleSourceFile()) {
            proposedSourceJarName = request.getSourceOutputFile().getName();
            proposedSourceJarName = instructions.getSourceSymbolicName() + "_" + instructions.getVersion() + ".jar";
        }

        File binaryFinalFile = new File(finalDestinationDirectory + "\\plugins", proposedBinaryJarName);
        boolean shouldCopy = binaryFinalFile.exists() ? !BundleUtils.INSTANCE.isBundle(binaryFinalFile) : true;
        File sourceFinalFile = new File(finalDestinationDirectory + "\\plugins", proposedSourceJarName);
        boolean shouldCopySource = sourceFinalFile.exists() ? !BundleUtils.INSTANCE.isBundle(sourceFinalFile) : true;

        try {
            log().debug("Executing Bundler:");
            doWrap(request, instructions, shouldCopy, osgiOverride);
            doSourceWrap(request, instructions, shouldCopySource);
        } catch (Exception ex) {
            throw new RuntimeException("Error while bundling jar or source: " + request.getBinaryInputFile().getName(), ex);
        }
    }

    private void doWrap(ArtifactBundlerRequest request, ArtifactBundlerInstructions instructions, boolean shouldCopy,
            Map<String, String> osgiOverride) throws Exception {
        if (request.isShouldBundleBinaryFile() && shouldCopy) {
            forceMkdirSilently(new File(request.getBinaryOutputFile().getParent()));
            prepareOutputFile(request.getBinaryOutputFile());
            log().info("\t [EXEC] " + request.getBinaryInputFile().getName());
            handleVanillaJarWrap(request, instructions);
        } else {
            log().debug("\t [SKIP] " + request.getBinaryInputFile().getName());
            if (shouldCopy) {
                handleBundleJarWrap(request, instructions, osgiOverride);
            }
        }
    }

    private static File forceMkdirSilently(File folder) {
        try {
            FileUtils.forceMkdir(folder);
            return folder;
        } catch (IOException e) {
            throw new RuntimeException(e.getMessage(), e);
        }
    }

    private void prepareOutputFile(File file) {
        if (file.exists()) {
            FileUtils.deleteQuietly(file);
        }
        try {
            if (!file.createNewFile()) {
                throw new RuntimeException("Cannot create output file " + file);
            }
        } catch (IOException e) {
            throw new RuntimeException("Cannot create output file " + file);
        }
    }

    private void handleVanillaJarWrap(ArtifactBundlerRequest request, ArtifactBundlerInstructions instructions) throws Exception {
        Analyzer analyzer = AquteHelper.buildAnalyzer(request, instructions, pedantic);
        try {
            populateJar(analyzer, request.getBinaryOutputFile());
            bundleUtils.reportErrors(analyzer);
            removeSignature(request.getBinaryOutputFile());
        } finally {
            analyzer.close();
        }
    }

    private void populateJar(Analyzer analyzer, File outputFile) throws Exception {
        Jar jar = analyzer.getJar();
        jar.setManifest(analyzer.calcManifest());
        try {
            jar.write(outputFile);
        } finally {
            jar.close();
        }
    }

    private void removeSignature(File jar) {
        if (JarUtils.containsSignature(jar)) {
            log().info("\t [UNSIGN] " + jar.getName());
            JarUtils.removeSignature(jar);
        }
    }

    private void handleBundleJarWrap(ArtifactBundlerRequest request, ArtifactBundlerInstructions instructions,
            Map<String, String> osgiOverride) throws IOException {
        // in general this method does not modify the jar since it's already a bundle
        // so the file is copied only
        FileUtils.copyFile(request.getBinaryInputFile(), request.getBinaryOutputFile());
        if (instructions.isSnapshot()) {
            // the only thing that is modified is the version of the bundle
            // in case it's a snapshot and the version does not contain a timestamp but a generic "SNAPSHOT" string only
            // the "SNAPSHOT" string is replaces with the manually generated timestamp
            JarUtils.adjustSnapshotOutputVersion(request.getBinaryInputFile(), request.getBinaryOutputFile(), instructions.getProposedVersion());
        }
        if (!osgiOverride.isEmpty()) {
            boolean success = JarUtils.attemptOSGiOverride(request.getBinaryInputFile(), request.getBinaryOutputFile(), osgiOverride);
            if (success) {
                removeSignature(request.getBinaryOutputFile());
            }
        }
    }

    private void doSourceWrap(ArtifactBundlerRequest request, ArtifactBundlerInstructions instructions, boolean shouldCopySource) throws Exception {
        if (request.isShouldBundleSourceFile() && shouldCopySource) {
            if (request.getSourceInputFile() == null) {
                return;
            }
            log().info("\t [EXEC] " + request.getSourceInputFile().getName());
            FileUtils.forceMkdir(new File(request.getSourceOutputFile().getParent()));
            String symbolicName = instructions.getSourceSymbolicName();
            String referencedBundleSymbolicName = instructions.getSymbolicName();
            String version;
            if (request.isShouldBundleBinaryFile()) {
                // take user-defined or proposed version
                version = instructions.getVersion();
            } else {
                // do not take user-defined and take proposed version
                // there is no bundling -> so cannot take version from instructions
                version = instructions.getProposedVersion();
            }
            String name = instructions.getSourceName();
            Jar jar = new Jar(request.getSourceInputFile());
            try {
                Manifest manifest = getManifest(jar);
                decorateSourceManifest(manifest, name, referencedBundleSymbolicName, symbolicName, version);
                jar.setManifest(manifest);
                jar.write(request.getSourceOutputFile());
                removeSignature(request.getSourceOutputFile());
            } finally {
                jar.close();
            }
        }
    }

    private Manifest getManifest(Jar jar) throws Exception {
        Manifest manifest = jar.getManifest();
        if (manifest == null) {
            manifest = new Manifest();
        }
        return manifest;
    }

    private void decorateSourceManifest(Manifest manifest, String name, String refrencedBundleSymbolicName, String symbolicName, String version) {
        Attributes attributes = manifest.getMainAttributes();
        attributes.putValue(Analyzer.BUNDLE_SYMBOLICNAME, symbolicName);
        attributes.putValue(ECLIPSE_SOURCE_BUNDLE, refrencedBundleSymbolicName + ";version=\"" + version + "\";roots:=\".\"");
        attributes.putValue(Analyzer.BUNDLE_VERSION, version);
        attributes.putValue(Analyzer.BUNDLE_LOCALIZATION, "plugin");
        attributes.putValue(MANIFEST_VERSION, "1.0");
        attributes.putValue(Analyzer.BUNDLE_MANIFESTVERSION, "2");
        attributes.putValue(Analyzer.BUNDLE_NAME, name);
        attributes.putValue(IMPLEMENTATION_TITLE, name);
        attributes.putValue(SPECIFICATION_TITLE, name);
        attributes.putValue(AquteHelper.TOOL_KEY, AquteHelper.TOOL);
    }

    private Logger log() {
        return Logger.getLog();
    }

}
