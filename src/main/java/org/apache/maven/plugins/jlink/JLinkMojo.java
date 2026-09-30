/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.maven.plugins.jlink;

/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.module.ModuleDescriptor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.NoSuchElementException;
import java.util.Optional;

import org.apache.commons.io.FileUtils;
import org.apache.maven.api.PathScope;
import org.apache.maven.api.ProducedArtifact;
import org.apache.maven.api.Toolchain;
import org.apache.maven.api.di.Inject;
import org.apache.maven.api.plugin.MojoException;
import org.apache.maven.api.plugin.annotations.Mojo;
import org.apache.maven.api.plugin.annotations.Parameter;
import org.apache.maven.api.services.DependencyResolver;
import org.apache.maven.api.services.DependencyResolverException;
import org.apache.maven.api.services.DependencyResolverResult;
import org.apache.maven.api.services.ProjectManager;
import org.apache.maven.shared.archiver.MavenArchiver;
import org.apache.maven.shared.filtering.MavenFilteringException;
import org.apache.maven.shared.filtering.MavenResourcesExecution;
import org.apache.maven.shared.filtering.MavenResourcesFiltering;
import org.apache.maven.shared.filtering.Resource;
import org.codehaus.plexus.archiver.ArchiverException;
import org.codehaus.plexus.archiver.zip.ZipArchiver;

import static java.util.Collections.singletonMap;

/**
 * The JLink goal is intended to create a Java Run Time Image file based on
 * <a href="https://openjdk.java.net/jeps/282">https://openjdk.java.net/jeps/282</a>,
 * <a href="https://openjdk.java.net/jeps/220">https://openjdk.java.net/jeps/220</a>.
 *
 * @author Karl Heinz Marbaise <a href="mailto:khmarbaise@apache.org">khmarbaise@apache.org</a>
 */
@Mojo(name = "jlink", defaultPhase = "package")
public class JLinkMojo extends AbstractJLinkMojo {

    /**
     * <p>
     * Specify the requirements for this jdk toolchain. This overrules the toolchain selected by the
     * maven-toolchain-plugin.
     * </p>
     * <strong>note:</strong> requires at least Maven 3.3.1.
     */
    @Parameter
    private Map<String, String> jdkToolchain;

    /**
     * This is intended to strip debug information out. The command line equivalent of <code>jlink</code> is:
     * <code>-G, --strip-debug</code> strip debug information.
     */
    @Parameter(defaultValue = "false")
    private boolean stripDebug;

    /**
     * Here you can define the compression of the resources being used. The command line equivalent is:
     * <code>-c, --compress=&lt;level&gt;</code>.
     *
     * <p>The valid values for the level depend on the JDK:</p>
     *
     * <p>For JDK 9+:</p>
     * <ul>
     *     <li>0:  No compression. Equivalent to zip-0.</li>
     *     <li>1:  Constant String Sharing</li>
     *     <li>2:  Equivalent to zip-6.</li>
     * </ul>
     *
     * <p>For JDK 21+, those values are deprecated and to be removed in a future version.
     * The supported values are:<br>
     * {@code zip-[0-9]}, where {@code zip-0} provides no compression,
     * and {@code zip-9} provides the best compression.<br>
     * Default is {@code zip-6}.</p>
     */
    @Parameter
    private String compress;

    /**
     * Should the plugin generate a launcher script by means of jlink? The command line equivalent is:
     * <code>--launcher &lt;name&gt;=&lt;module&gt;[/&lt;mainclass&gt;]</code>. The valid values for the level are:
     * <code>&lt;name&gt;=&lt;module&gt;[/&lt;mainclass&gt;]</code>.
     */
    @Parameter
    private String launcher;

    /**
     * Specify one or more launchers for jlink.
     * The command line equivalent is:
     * <code>--launcher &lt;name&gt;=&lt;module&gt;[/&lt;mainclass&gt;]</code>.
     * The valid values are a list of
     * <code>&lt;name&gt;=&lt;module&gt;[/&lt;mainclass&gt;]</code> terms,
     * separated by commas.
     */
    @Parameter
    private List<String> launchers;

    /**
     * These JVM arguments will be appended to the {@code lib/modules} file.<br>
     * <strong>This parameter requires at least JDK 14.<br></strong>
     *
     * <p>The command line equivalent is: {@code jlink --add-options="..."}.</p>
     *
     * <p>Example:</p>
     *
     * <pre>
     *   &lt;addOptions&gt;
     *     &lt;addOption&gt;-Xmx256m&lt;/addOption&gt;
     *     &lt;addOption&gt;--enable-preview&lt;/addOption&gt;
     *     &lt;addOption&gt;-Dvar=value&lt;/addOption&gt;
     *   &lt;/addOptions&gt;
     * </pre>
     *
     * <p>Above example will result in {@code jlink --add-options="-Xmx256m" --enable-preview -Dvar=value"}.</p>
     */
    @Parameter
    private List<String> addOptions;

    /**
     * Limit the universe of observable modules. The following gives an example of the configuration which can be used
     * in the <code>pom.xml</code> file.
     *
     * <pre>
     *   &lt;limitModules&gt;
     *     &lt;limitModule&gt;mod1&lt;/limitModule&gt;
     *     &lt;limitModule&gt;xyz&lt;/limitModule&gt;
     *     .
     *     .
     *   &lt;/limitModules&gt;
     * </pre>
     *
     * This configuration is the equivalent of the command line option:
     * <code>--limit-modules &lt;mod&gt;[,&lt;mod&gt;...]</code>
     */
    @Parameter
    private List<String> limitModules;

    /**
     * <p>
     * Usually this is not necessary, cause this is handled automatically by the given dependencies.
     * </p>
     * <p>
     * By using the --add-modules you can define the root modules to be resolved. The configuration in
     * <code>pom.xml</code> file can look like this:
     * </p>
     *
     * <pre>
     * &lt;addModules&gt;
     *   &lt;addModule&gt;mod1&lt;/addModule&gt;
     *   &lt;addModule&gt;first&lt;/addModule&gt;
     *   .
     *   .
     * &lt;/addModules&gt;
     * </pre>
     *
     * The command line equivalent for jlink is: <code>--add-modules &lt;mod&gt;[,&lt;mod&gt;...]</code>.
     */
    @Parameter
    private List<String> addModules;

    /**
     * Define the plugin module path to be used. There can be defined multiple entries separated by either {@code ;} or
     * {@code :}. The jlink command line equivalent is: <code>--plugin-module-path &lt;modulepath&gt;</code>
     */
    @Parameter
    private String pluginModulePath;

    /**
     * The output directory for the resulting Run Time Image. The created Run Time Image is stored in non compressed
     * form. This will later being packaged into a <code>zip</code> file. <code>--output &lt;path&gt;</code>
     *
     * <p>The {@link #classifier} is appended as a subdirecty if it exists,
     * otherwise {@code default} will be used as subdirectory.
     * This ensures that multiple executions using classifiers will not overwrite the previous run’s image.</p>
     */
    @Parameter(defaultValue = "${project.build.directory}/maven-jlink", required = true, readonly = true)
    private File outputDirectoryImage;

    @Parameter(defaultValue = "${project.build.directory}", required = true, readonly = true)
    private File buildDirectory;

    @Parameter(defaultValue = "${project.build.outputDirectory}", required = true, readonly = true)
    private File outputDirectory;

    /**
     * The byte order of the generated Java Run Time image. <code>--endian &lt;little|big&gt;</code>. If the endian is
     * not given the default is: <code>native</code>.
     */
    // TODO: Should we define either little or big as default? or should we leave it as is?
    @Parameter
    private String endian;

    /**
     * Include additional paths on the <code>--module-path</code> option. Project dependencies and JDK modules are
     * automatically added.
     */
    @Parameter
    private List<String> modulePaths;

    /**
     * Add the option <code>--bind-services</code> or not.
     */
    @Parameter(defaultValue = "false")
    private boolean bindServices;

    /**
     * You can disable a plugin by using this option. <code>--disable-plugin pluginName</code>.
     */
    @Parameter
    private String disablePlugin;

    /**
     * <code>--ignore-signing-information</code>
     */
    @Parameter(defaultValue = "false")
    private boolean ignoreSigningInformation;

    /**
     * This will omit an <code>includes</code> directory from the resulting Java Run Time Image. The JLink
     * command line equivalent is: <code>--no-header-files</code>
     */
    @Parameter(defaultValue = "false")
    private boolean noHeaderFiles;

    /**
     * This will omit the <code>man</code> directory from the resulting Java Run Time Image. The JLink command
     * line equivalent is: <code>--no-man-pages</code>
     */
    @Parameter(defaultValue = "false")
    private boolean noManPages;

    /**
     * Suggest providers that implement the given service types from the module path.
     *
     * <pre>
     * &lt;suggestProviders&gt;
     *   &lt;suggestProvider&gt;name-a&lt;/suggestProvider&gt;
     *   &lt;suggestProvider&gt;name-b&lt;/suggestProvider&gt;
     *   .
     *   .
     * &lt;/suggestProviders&gt;
     * </pre>
     *
     * The jlink command linke equivalent: <code>--suggest-providers [&lt;name&gt;,...]</code>
     */
    @Parameter
    private List<String> suggestProviders;

    /**
     * Includes the list of locales where langtag is a BCP 47 language tag.
     *
     * <p>This option supports locale matching as defined in RFC 4647.
     * Ensure that you add the module jdk.localedata when using this option.</p>
     *
     * <p>The command line equivalent is: <code>--include-locales=en,ja,*-IN</code>.</p>
     *
     * <pre>
     * &lt;includeLocales&gt;
     *   &lt;includeLocale&gt;en&lt;/includeLocale&gt;
     *   &lt;includeLocale&gt;ja&lt;/includeLocale&gt;
     *   &lt;includeLocale&gt;*-IN&lt;/includeLocale&gt;
     *   .
     *   .
     * &lt;/includeLocales&gt;
     * </pre>
     */
    @Parameter
    private List<String> includeLocales;

    /**
     * This will turn on verbose mode. The jlink command line equivalent is: <code>--verbose</code>
     */
    @Parameter(defaultValue = "false")
    private boolean verbose;

    /**
     * Set the JDK location to create a Java custom runtime image.
     */
    @Parameter
    private File sourceJdkModules;

    /**
     * Controls whether the plugin tries to attach the resulting artifact to the project.
     *
     * @since 3.2.1
     */
    @Parameter(defaultValue = "true")
    private boolean attach;

    /**
     * Classifier to add to the artifact generated. If given, the artifact will be attached
     * as a supplemental artifact.
     * If not given, this will create the main artifact which is the default behavior.
     * If you try to do that a second time without using a classifier, the build will fail.
     */
    @Parameter
    private String classifier;

    /**
     * Name of the generated ZIP file in the <code>target</code> directory. This will not change the name of the
     * installed/deployed file.
     */
    @Parameter(defaultValue = "${project.build.finalName}", readonly = true)
    private String finalName;

    /**
     * Timestamp for reproducible output archive entries, either formatted as ISO 8601
     * <code>yyyy-MM-dd'T'HH:mm:ssXXX</code> or as an int representing seconds since the epoch (like
     * <a href="https://reproducible-builds.org/docs/source-date-epoch/">SOURCE_DATE_EPOCH</a>).
     *
     * @since 3.2.0
     */
    @Parameter(defaultValue = "${project.build.outputTimestamp}")
    private String outputTimestamp;

    /**
     * These files are added to the image after calling jlink, but before creating the zip file.
     *
     * @since 3.2.0
     */
    @Parameter
    private List<Resource> additionalResources;

    /**
     * Add directory prefix to all of zip entries in top level files/directories.
     *
     * <p>For example, if this value is set to <code>prefix</code>,
     * <code>bin/launcher</code> is transformed to <code>prefix/bin/launcher</code>.</p>
     *
     * <p>Empty String is set by default. It means no prefix.</p>
     *
     * @since 3.2.1
     */
    @Parameter(defaultValue = "")
    private String zipDirPrefix;

    /**
     * Attaches the artifacts produced by this plugin to the project.
     */
    @Inject
    private ProjectManager projectManager;

    @Inject
    private MavenResourcesFiltering mavenResourcesFiltering;

    @Inject
    private DependencyResolver dependencyResolver;

    /**
     * The JAR archiver needed for archiving the environments.
     */
    private final ZipArchiver zipArchiver = new ZipArchiver();

    @Override
    public void execute() throws MojoException {
        failIfParametersAreNotInTheirValidValueRanges();

        setOutputDirectoryImage();

        ifOutputDirectoryExistsDelteIt();

        JLinkExecutor jLinkExec = getJlinkExecutor();
        Collection<String> modulesToAdd = new ArrayList<>();
        if (addModules != null) {
            modulesToAdd.addAll(addModules);
        }
        jLinkExec.addAllModules(modulesToAdd);

        Collection<String> pathsOfModules = new ArrayList<>();
        if (modulePaths != null) {
            pathsOfModules.addAll(modulePaths);
        }

        for (Entry<String, File> item : getModulePathElements().entrySet()) {
            getLog().info(" -> module: " + item.getKey() + " ( "
                    + item.getValue().getPath() + " )");

            // We use the real module name and not the artifact Id...
            modulesToAdd.add(item.getKey());
            pathsOfModules.add(item.getValue().getPath());
        }

        // The jmods directory of the JDK
        jLinkExec
                .getJmodsFolder(this.sourceJdkModules)
                .ifPresent(jmodsFolder -> pathsOfModules.add(jmodsFolder.getAbsolutePath()));
        jLinkExec.addAllModulePaths(pathsOfModules);

        List<String> jlinkArgs = createJlinkArgs(pathsOfModules, modulesToAdd);

        try {
            jLinkExec.executeJlink(jlinkArgs);
        } catch (IllegalStateException e) {
            throw new MojoException("Unable to find jlink command: " + e.getMessage(), e);
        }

        // Add additional resources
        try {
            mavenResourcesFiltering.filterResources(new MavenResourcesExecution(
                    additionalResources,
                    outputDirectoryImage.toPath(),
                    getProject(),
                    "UTF-8",
                    Collections.emptyList(),
                    Collections.emptyList(),
                    getSession()));
        } catch (MavenFilteringException e) {
            throw new MojoException("Unable to copy the additional resources: " + e.getMessage(), e);
        }

        File createZipArchiveFromImage = createZipArchiveFromImage(buildDirectory, outputDirectoryImage);

        attachArtifactUnlessDisabled(createZipArchiveFromImage);
    }

    Map<String, File> getModulePathElements() throws MojoException {
        // For now only allow named modules. Once we can create a graph with ASM we can specify exactly the modules
        // and we can detect if auto modules are used. In that case the main artifact of the project should not be
        // set, so you cannot depend on this project and so it won't be distributed.
        DependencyResolverResult resolved;
        try {
            resolved = dependencyResolver.resolve(getSession(), getProject(), PathScope.MAIN_RUNTIME);
        } catch (DependencyResolverException e) {
            throw new MojoException(e.getMessage(), e);
        }
        return getModulePathElements(resolved, outputDirectory);
    }

    Map<String, File> getModulePathElements(DependencyResolverResult resolved, File outputDirectory)
            throws MojoException {
        Map<String, File> modulepathElements = new LinkedHashMap<>();

        try {
            for (Path path : resolved.getPaths()) {
                getLog().debug("Adding path: " + path);
                addModule(
                        modulepathElements,
                        path.toFile(),
                        resolved.getModuleDescriptor(path),
                        "The given dependency " + path
                                + " does not have a module-info.java file. So it can't be linked.");
            }

            // This part is for the module in target/classes ? (Hacky..)
            // FIXME: Is there a better way to identify that code exists?
            if (outputDirectory.exists()) {
                Path classes = outputDirectory.toPath();
                addModule(
                        modulepathElements,
                        outputDirectory,
                        readModuleDescriptor(classes),
                        "The given project " + classes
                                + " does not contain a module-info.java file. So it can't be linked.");
            }
        } catch (IOException e) {
            getLog().error(e.getMessage());
            throw new MojoException(e.getMessage(), e);
        }

        return modulepathElements;
    }

    /**
     * Reads the descriptor of the module built by this project. The resolver only knows the dependencies.
     */
    private static Optional<ModuleDescriptor> readModuleDescriptor(Path classesDirectory) throws IOException {
        Path moduleInfo = classesDirectory.resolve("module-info.class");
        if (!Files.isRegularFile(moduleInfo)) {
            return Optional.empty();
        }
        try (InputStream in = Files.newInputStream(moduleInfo)) {
            return Optional.of(ModuleDescriptor.read(in));
        }
    }

    private void addModule(
            Map<String, File> modulepathElements,
            File element,
            Optional<ModuleDescriptor> descriptor,
            String missingDescriptorMessage) {
        if (descriptor.isEmpty()) {
            if (element.isFile()) {
                // The resolver reports no descriptor for a JAR which only has a file name based automatic module name
                getLog().debug("Ignoring automatic module: " + element);
                return;
            }
            getLog().error(missingDescriptorMessage);
            throw new MojoException(missingDescriptorMessage);
        }

        ModuleDescriptor module = descriptor.get();
        // Filter out automatic modules
        if (module.isAutomatic()) {
            getLog().debug("Ignoring automatic module: " + module.name());
            return;
        }

        if (modulepathElements.containsKey(module.name())) {
            getLog().warn("The module name " + module.name() + " does already exists.");
        }
        modulepathElements.put(module.name(), element);
    }

    private boolean projectHasAlreadySetAnArtifact() {
        return projectManager.getPath(getProject()).filter(Files::isRegularFile).isPresent();
    }

    /**
     * @return true when the classifier is not {@code null} and not empty
     */
    private boolean hasClassifier() {
        return hasClassifier(getClassifier());
    }

    private File createZipArchiveFromImage(File outputDirectory, File outputDirectoryImage) throws MojoException {
        if (zipDirPrefix != null && !zipDirPrefix.isEmpty() && !zipDirPrefix.endsWith("/")) {
            zipDirPrefix += "/";
        }
        zipArchiver.addDirectory(outputDirectoryImage, zipDirPrefix);

        // configure for Reproducible Builds based on outputTimestamp value
        Optional<Instant> lastModified = MavenArchiver.parseBuildOutputTimestamp(outputTimestamp);
        if (lastModified.isPresent()) {
            zipArchiver.configureReproducibleBuild(FileTime.from(lastModified.get()));
        }

        File resultArchive = getZipFile(outputDirectory, finalName, getClassifier());

        zipArchiver.setDestFile(resultArchive);
        try {
            zipArchiver.createArchive();
        } catch (ArchiverException | IOException e) {
            getLog().error(e.getMessage(), e);
            throw new MojoException(e.getMessage(), e);
        }

        return resultArchive;
    }

    private void attachArtifactUnlessDisabled(File artifactFile) throws MojoException {
        if (!attach) {
            return;
        }

        ProducedArtifact artifact;
        if (hasClassifier()) {
            artifact = getSession()
                    .createProducedArtifact(
                            getProject().getGroupId(),
                            getProject().getArtifactId(),
                            getProject().getVersion(),
                            getClassifier(),
                            "zip",
                            "jlink");
        } else {
            if (projectHasAlreadySetAnArtifact()) {
                throw new MojoException("You have to use a classifier "
                        + "to attach supplemental artifacts to the project instead of replacing them.");
            }
            artifact = getProject().getMainArtifact().orElseThrow();
        }
        projectManager.attachArtifact(getProject(), artifact, artifactFile.toPath());
    }

    private void failIfParametersAreNotInTheirValidValueRanges() throws MojoException {
        if (endian != null && (!"big".equals(endian) && !"little".equals(endian))) {
            String message = "The given endian parameter " + endian
                    + " does not contain one of the following values: 'little' or 'big'.";
            getLog().error(message);
            throw new MojoException(message);
        }

        if (addOptions != null && !addOptions.isEmpty()) {
            requireJdk14();
        }
    }

    private void requireJdk14() throws MojoException {
        // needs JDK 14+
        Optional<Toolchain> optToolchain = getToolchain();
        String java14reqMsg = "parameter 'addOptions' needs at least a Java 14 runtime or a Java 14 toolchain.";

        if (optToolchain.isPresent()) {
            Toolchain toolchain = optToolchain.orElseThrow(NoSuchElementException::new);
            if (!toolchain.matchesRequirements(singletonMap("jdk", "14"))) {
                throw new MojoException(java14reqMsg);
            }
        } else if (Runtime.version().feature() < 14) {
            throw new MojoException(java14reqMsg);
        }
    }

    /**
     * Use a separate directory for each image.
     *
     * <p>Rationale: If a user creates multiple jlink artifacts using classifiers,
     * the directories should not overwrite themselves for each execution.</p>
     */
    private void setOutputDirectoryImage() {
        if (hasClassifier()) {
            final File classifiersDirectory = new File(outputDirectoryImage, "classifiers");
            outputDirectoryImage = new File(classifiersDirectory, classifier);
        } else {
            outputDirectoryImage = new File(outputDirectoryImage, "default");
        }
    }

    private void ifOutputDirectoryExistsDelteIt() throws MojoException {
        if (outputDirectoryImage.exists()) {
            // Delete the output folder of JLink before we start
            // otherwise JLink will fail with a message "Error: directory already exists: ..."
            try {
                getLog().debug("Deleting existing " + outputDirectoryImage.getAbsolutePath());
                FileUtils.forceDelete(outputDirectoryImage);
            } catch (IOException e) {
                getLog().error("IOException", e);
                throw new MojoException(
                        "Failure during deletion of " + outputDirectoryImage.getAbsolutePath() + " occured.");
            }
        }
    }

    protected List<String> createJlinkArgs(Collection<String> pathsOfModules, Collection<String> modulesToAdd)
            throws MojoException {
        List<String> jlinkArgs = new ArrayList<>();

        if (stripDebug) {
            jlinkArgs.add("--strip-debug");
        }

        if (bindServices) {
            jlinkArgs.add("--bind-services");
        }

        if (endian != null) {
            jlinkArgs.add("--endian");
            jlinkArgs.add(endian);
        }
        if (ignoreSigningInformation) {
            jlinkArgs.add("--ignore-signing-information");
        }
        if (compress != null) {
            jlinkArgs.add("--compress");
            jlinkArgs.add(compress);
        }
        if (launcher != null) {
            if (launchers != null) {
                throw new MojoException("Specify either single <launcher> or multiple <launchers>, not both.");
            } else {
                launchers = List.of(launcher);
            }
        }
        if (launchers != null) {
            for (String item : launchers) {
                jlinkArgs.add("--launcher");
                jlinkArgs.add(item);
            }
        }
        if (addOptions != null && !addOptions.isEmpty()) {
            jlinkArgs.add("--add-options=" + String.join(" ", addOptions));
        }

        if (disablePlugin != null) {
            jlinkArgs.add("--disable-plugin");
            jlinkArgs.add(disablePlugin);
        }
        if (pathsOfModules != null && !pathsOfModules.isEmpty()) {
            // @formatter:off
            jlinkArgs.add("--module-path");
            jlinkArgs.add(String.join(File.pathSeparator, pathsOfModules).replace("\\", "\\\\"));
            // @formatter:off
        }

        if (noHeaderFiles) {
            jlinkArgs.add("--no-header-files");
        }

        if (noManPages) {
            jlinkArgs.add("--no-man-pages");
        }

        if (hasLimitModules()) {
            jlinkArgs.add("--limit-modules");
            String sb = String.join(",", limitModules);
            jlinkArgs.add(sb);
        }

        if (!modulesToAdd.isEmpty()) {
            jlinkArgs.add("--add-modules");
            // This must be name of the module and *NOT* the name of the
            // file! Can we somehow pre check this information to fail early?
            String sb = String.join(",", modulesToAdd);
            jlinkArgs.add(sb.replace("\\", "\\\\"));
        }

        if (hasIncludeLocales()) {
            jlinkArgs.add("--add-modules");
            jlinkArgs.add("jdk.localedata");
            jlinkArgs.add("--include-locales");
            String sb = String.join(",", includeLocales);
            jlinkArgs.add(sb);
        }

        if (pluginModulePath != null) {
            jlinkArgs.add("--plugin-module-path");
            StringBuilder sb = convertSeparatedModulePathToPlatformSeparatedModulePath(pluginModulePath);
            jlinkArgs.add(sb.toString().replace("\\", "\\\\"));
        }

        if (buildDirectory != null) {
            jlinkArgs.add("--output");
            jlinkArgs.add(outputDirectoryImage.getAbsolutePath());
        }

        if (verbose) {
            jlinkArgs.add("--verbose");
        }

        // NOTE: suggestProviders is a terminal JlinkTask, so must be at the end!
        if (hasSuggestProviders()) {
            jlinkArgs.add("--suggest-providers");
            String sb = String.join(",", suggestProviders);
            jlinkArgs.add(sb);
        }

        return Collections.unmodifiableList(jlinkArgs);
    }

    private boolean hasIncludeLocales() {
        return includeLocales != null && !includeLocales.isEmpty();
    }

    private boolean hasSuggestProviders() {
        return suggestProviders != null && !suggestProviders.isEmpty();
    }

    private boolean hasLimitModules() {
        return limitModules != null && !limitModules.isEmpty();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected String getClassifier() {
        return classifier;
    }

    /**
     * Returns the archive file to generate, based on an optional classifier.
     *
     * @param basedir the output directory
     * @param finalName the name of the zip file
     * @param classifier an optional classifier
     * @return the file to generate
     */
    private static File getZipFile(File basedir, String finalName, String classifier) {
        if (finalName.isEmpty()) {
            throw new IllegalArgumentException("finalName is not allowed to be empty.");
        }

        StringBuilder fileName = new StringBuilder(finalName);

        if (hasClassifier(classifier)) {
            fileName.append("-").append(classifier);
        }

        fileName.append(".zip");

        return new File(basedir, fileName.toString());
    }

    private static boolean hasClassifier(String classifier) {
        boolean result = false;
        if (classifier != null && !classifier.isEmpty()) {
            result = true;
        }

        return result;
    }
}
