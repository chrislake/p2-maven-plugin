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
package org.reficio.p2.mirror;

import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.BuildPluginManager;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.project.MavenProject;

import java.io.IOException;

import static com.google.common.base.Preconditions.checkNotNull;
import static org.twdata.maven.mojoexecutor.MojoExecutor.*;

/**
 * @author Tom Bujok (tom.bujok@gmail.com)<br>
 *         Reficio (TM) - Reestablish your software!<br>
 *         http://www.reficio.org
 * @since 1.0.0
 */
public class BundleMirror {

    private static final String TYCHO_VERSION = "0.24.0";

    private final Boolean includePacked;
    private final Boolean followStrictOnly;
    private final Boolean append;
    private final String sourceURL;
    private final String destination;
    private final String iuId;
    private final String iuVersion;
    private final String additionalArgs;
    private final MavenProject mavenProject;
    private final MavenSession mavenSession;
    private final BuildPluginManager buildPluginManager;

    public BundleMirror(Boolean includePacked, Boolean followStrictOnly, Boolean append, String sourceURL, String destination, String additionalArgs,
            String iuId, String iuVersion, MavenProject mavenProject, MavenSession mavenSession, BuildPluginManager buildPluginManager) {
        this.includePacked = includePacked;
        this.followStrictOnly = followStrictOnly;
        this.append = append;
        this.sourceURL = sourceURL;
        this.destination = destination;
        this.iuId = iuId;
        this.iuVersion = iuVersion;
        this.additionalArgs = additionalArgs;
        this.mavenProject = mavenProject;
        this.mavenSession = mavenSession;
        this.buildPluginManager = buildPluginManager;
    }

    public void execute() throws MojoExecutionException, IOException {
        executeMojo(
                plugin(
                        groupId("org.eclipse.tycho.extras"),
                        artifactId("tycho-p2-extras-plugin"),
                        version(TYCHO_VERSION)
                ),
                goal("mirror"),
                configuration(
                        element(name("includePacked"), Boolean.toString(includePacked)),
                        element(name("followStrictOnly"), Boolean.toString(followStrictOnly)),
                        element(name("append"), Boolean.toString(append)),
                        element(name("source"),
                            element(name("repository"),
                                element(name("url"),sourceURL))),
                        element(name("ius"),
                            element(name("iu"),
                                element(name("id"), iuId),
                                element(name("version"),iuVersion))),
                        element(name("destination"), destination)//,
//                        element(name("additionalArgs"), additionalArgs)
                ),
                executionEnvironment(
                        mavenProject,
                        mavenSession,
                        buildPluginManager
                )
        );
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private Boolean includePacked;
        private Boolean followStrictOnly;
        private Boolean append;
        private String sourceURL;
        private String destination;
        private String iuId;
        private String iuVersion;
        private String additionalArgs;
        private MavenProject mavenProject;
        private MavenSession mavenSession;
        private BuildPluginManager buildPluginManager;

        public Builder includePacked(Boolean includePacked) {
            this.includePacked = includePacked;
            return this;
        }

        public Builder followStrictOnly(Boolean followStrictOnly) {
            this.followStrictOnly = followStrictOnly;
            return this;
        }

        public Builder append(Boolean append) {
            this.append = append;
            return this;
        }

        public Builder sourceURL(String sourceURL) {
            this.sourceURL = sourceURL;
            return this;
        }

        public Builder destination(String destination) {
            this.destination = destination;
            return this;
        }

        public Builder iuId(String iuId) {
            this.iuId = iuId;
            return this;
        }

        public Builder iuVersion(String iuVersion) {
            this.iuVersion = iuVersion;
            return this;
        }

        public Builder additionalArgs(String additionalArgs) {
            this.additionalArgs = additionalArgs;
            return this;
        }

        public Builder mavenProject(MavenProject mavenProject) {
            this.mavenProject = mavenProject;
            return this;
        }

        public Builder mavenSession(MavenSession mavenSession) {
            this.mavenSession = mavenSession;
            return this;
        }

        public Builder buildPluginManager(BuildPluginManager buildPluginManager) {
            this.buildPluginManager = buildPluginManager;
            return this;
        }

        public BundleMirror build() {
            return new BundleMirror(includePacked, followStrictOnly, append, sourceURL, destination, additionalArgs,
                    iuId, iuVersion, checkNotNull(mavenProject), checkNotNull(mavenSession), checkNotNull(buildPluginManager));
        }
    }
}
