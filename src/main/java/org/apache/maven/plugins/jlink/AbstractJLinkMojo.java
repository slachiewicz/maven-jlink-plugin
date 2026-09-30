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
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.apache.maven.api.Project;
import org.apache.maven.api.Session;
import org.apache.maven.api.Toolchain;
import org.apache.maven.api.di.Inject;
import org.apache.maven.api.plugin.Log;
import org.apache.maven.api.plugin.Mojo;
import org.apache.maven.api.plugin.annotations.Parameter;
import org.apache.maven.api.services.ToolchainManager;
import org.apache.maven.api.services.ToolchainManagerException;

/**
 * @author Karl Heinz Marbaise <a href="mailto:khmarbaise@apache.org">khmarbaise@apache.org</a>
 */
public abstract class AbstractJLinkMojo implements Mojo {
    /**
     * <p>
     * Specify the requirements for this JDK toolchain. This overrules the toolchain selected by the
     * maven-toolchain-plugin.
     * </p>
     * <strong>note:</strong> requires at least Maven 3.3.1.
     */
    @Parameter
    private Map<String, String> jdkToolchain;

    @Inject
    private Project project;

    @Inject
    private Session session;

    @Inject
    private ToolchainManager toolchainManager;

    @Inject
    private Log log;

    protected Log getLog() {
        return log;
    }

    /**
     * Overload this to produce a zip with another classifier, for example a jlink-zip.
     *
     * @return get the classifier
     */
    protected abstract String getClassifier();

    protected JLinkExecutor getJlinkExecutor() {
        return new JLinkExecutor(getToolchain().orElse(null), getLog());
    }

    protected Optional<Toolchain> getToolchain() {
        Toolchain tc = null;

        if (jdkToolchain != null) {
            try {
                List<Toolchain> tcs = toolchainManager.getToolchains(getSession(), "jdk", jdkToolchain);

                if (tcs != null && !tcs.isEmpty()) {
                    tc = tcs.get(0);
                }
            } catch (ToolchainManagerException e) {
                // ignore
            }
        }

        if (tc == null) {
            // TODO: Check if we should make the type configurable?
            tc = toolchainManager
                    .getToolchainFromBuildContext(getSession(), "jdk")
                    .orElse(null);
        }

        return Optional.ofNullable(tc);
    }

    protected Project getProject() {
        return project;
    }

    protected Session getSession() {
        return session;
    }

    /**
     * This will convert a module path separated by either {@code :} or {@code ;} into a string which uses the platform
     * path separator uniformly.
     *
     * @param pluginModulePath the module path
     * @return the platform separated module path
     */
    protected StringBuilder convertSeparatedModulePathToPlatformSeparatedModulePath(String pluginModulePath) {
        StringBuilder sb = new StringBuilder();
        // Split the module path by either ":" or ";" Linux/Windows path separator and
        // convert uniformly to the platform separator.
        String[] splitModule = pluginModulePath.split("[;:]");
        for (String module : splitModule) {
            if (sb.length() > 0) {
                sb.append(File.pathSeparatorChar);
            }
            sb.append(module);
        }
        return sb;
    }
}
