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

import java.io.File;
import java.io.FilenameFilter;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.jar.JarInputStream;
import java.util.jar.Manifest;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import org.w3c.dom.Document;
import org.w3c.dom.Element;

public class FeatureGen {

	private static class JarVisitor extends SimpleFileVisitor<Path> {
		final PathMatcher jarExt = FileSystems.getDefault().getPathMatcher("glob:*.jar");
		final Document doc;
		final Element mainRootElement;

		public JarVisitor(Document doc, Element mainRootElement) {
			this.doc = doc;
			this.mainRootElement = mainRootElement;
		}

		@Override
		public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {

			try {
				// Only work with JAR files
				if (!jarExt.matches(file.getFileName())) {
					return FileVisitResult.CONTINUE;
				}
				// Skip the OL Jar signing tool
				if (file.getFileName().toString().startsWith("com.objectiflune.protection.oljarencryptor")) {
					return FileVisitResult.CONTINUE;
				}

				JarInputStream jarStream = new JarInputStream(file.toUri().toURL().openStream());
				Manifest manifest = jarStream.getManifest();
				jarStream.close();

				// Get the name and version
				String name = "";
				String version = "";
				String unpack = null;
				String host = null;
				if (null == manifest) {
					String fileName = file.getFileName().toString();
					Pattern jarPattern = Pattern.compile("(.*)_(\\d+\\.\\d+\\.\\d+(\\..+)?)\\.jar");
					Matcher matcher = jarPattern.matcher(fileName);
					if (matcher.find()) {
						name = matcher.group(1);
						version = matcher.group(2);
					}
					else {
						return FileVisitResult.CONTINUE;
					}
					if (file.getParent().equals("features")) {
						unpack = "dir";
					}
				}
				else {
					// Get the name and version
					name = manifest.getMainAttributes().getValue("Bundle-SymbolicName");
					if (name.contains(";")) {
						name = name.split(";")[0];
					}
					version = manifest.getMainAttributes().getValue("Bundle-Version");
					unpack = manifest.getMainAttributes().getValue("Eclipse-BundleShape");
					host = manifest.getMainAttributes().getValue("Fragment-Host");
				}

				Element plugin = doc.createElement("plugin");
				plugin.setAttribute("id", name.trim());
				plugin.setAttribute("download-size", String.valueOf(Files.size(file)));
				plugin.setAttribute("install-size", String.valueOf(Files.size(file)));
				plugin.setAttribute("version", version.trim());

				if (unpack == null) {
					plugin.setAttribute("unpack", "false");
				}
				else {
					if (!unpack.equalsIgnoreCase("dir")) {
						plugin.setAttribute("unpack", "false");
					}
				}
				if (host != null) {
					plugin.setAttribute("fragment", "true");
				}

				mainRootElement.appendChild(plugin);
				System.out.println("JAR: " + file.getFileName() + "    Bundle: " + name + "    Version: " + version);
			}
			catch (Exception e) {
				System.out.println("FEATUREGEN ERROR: " + file.getFileName() + " caused an exception:");
				e.printStackTrace();
			}

			return FileVisitResult.CONTINUE;
		}

		@Override
		public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
			return FileVisitResult.CONTINUE;
		}

		@Override
		public FileVisitResult visitFileFailed(Path file, IOException exc) {
			System.err.println(exc);
			return FileVisitResult.CONTINUE;
		}
	}

	public static void execute(String sourceRepository, String destinationDirectory, String versionNumber) {
		DocumentBuilderFactory dbFactory = DocumentBuilderFactory.newInstance();
		DocumentBuilder dBuilder;
		try {
			dBuilder = dbFactory.newDocumentBuilder();
			final Document doc = dBuilder.newDocument();
			final Element mainRootElement = doc.createElement("feature");
			mainRootElement.setAttribute("id", "com.objectiflune.repository.def.feature");
			mainRootElement.setAttribute("label", "OL Repository Dependency Definition Feature");
			String buildNumber = System.getenv("BUILD_NUMBER") == null ? "0" : System.getenv("BUILD_NUMBER");
			String featureVersion = versionNumber + "." + buildNumber;
			mainRootElement.setAttribute("version", featureVersion);
			mainRootElement.setAttribute("provider-name", "OBJECTFLUNE");
			mainRootElement.setAttribute("os", "win32");
			mainRootElement.setAttribute("ws", "win32");
			mainRootElement.setAttribute("arch", "x86_64");
			doc.appendChild(mainRootElement);
			System.out.println("Feature: com.objectiflune.repository.def.feature    Version: " + featureVersion);

			// add dependencies
			JarVisitor jarVisitor = new JarVisitor(doc, mainRootElement);
			Path plugins = Paths.get(sourceRepository + "/plugins");
			Files.walkFileTree(plugins, jarVisitor);

			plugins = Paths.get(destinationDirectory + "/plugins");
			Files.walkFileTree(plugins, jarVisitor);

			// add executable feature
			// your directory
			File featureFile = new File(destinationDirectory + "/features");
			File[] matchingFiles = featureFile.listFiles(new FilenameFilter() {
				@Override
				public boolean accept(File dir, String name) {
					return name.startsWith("org.eclipse.equinox.executable");
				}
			});

			for (File feat : matchingFiles) {
				// Get the name and version
				String fileName = feat.getName();
				int uscore = fileName.lastIndexOf("_");
				String name = fileName.substring(0, uscore);
				String version = fileName.substring(uscore + 1);

				// Ensure the version is correct three digits
				String regex = "^\\d+\\.\\d+\\.\\d+";
				Pattern pattern = Pattern.compile(regex);
				while (!pattern.matcher(version).find()) {
					uscore = name.lastIndexOf("_");
					name = fileName.substring(0, uscore);
					version = fileName.substring(uscore + 1);
				}

				//Remove the ".jar" from the version
				int vLen = version.length() - 4;
				if (version.substring(vLen).equalsIgnoreCase(".jar")) {
					version = version.substring(0, vLen);
				}

				Element feature = doc.createElement("includes");
				feature.setAttribute("id", name.trim());
				feature.setAttribute("version", version.trim());

				mainRootElement.appendChild(feature);
				System.out.println("JAR: " + feat.getName() + "    Feature: " + name + "    Version: " + version);
			}

			// output DOM XML to console
			Transformer transformer = TransformerFactory.newInstance().newTransformer();
			transformer.setOutputProperty(OutputKeys.INDENT, "yes");
			DOMSource source = new DOMSource(doc);
			File f = new File(sourceRepository + "/features/com.objectiflune.repository.def.feature/feature.xml");
			f.delete();
			f.getParentFile().mkdirs();
			f.createNewFile();
			StreamResult console = new StreamResult(f);
			transformer.transform(source, console);

			System.out.println("\nXML DOM Created Successfully..");

		}
		catch (Exception e) {
			e.printStackTrace();
		}
	}
}
