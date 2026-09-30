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

import javax.tools.ToolProvider;

import java.io.File;
import java.lang.module.ModuleDescriptor;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.apache.maven.api.plugin.Log;
import org.apache.maven.api.plugin.MojoException;
import org.apache.maven.api.services.DependencyResolverResult;
import org.apache.maven.shared.utils.cli.Commandline;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class JLinkMojoTest {

    private JLinkMojo mojo;

    @Mock
    private Log log;

    @Mock
    private DependencyResolverResult resolved;

    @BeforeEach
    void setUp() throws Exception {
        mojo = new JLinkMojo();
        setField(mojo.getClass().getSuperclass(), "log", log);
        setField(mojo.getClass(), "stripDebug", Boolean.TRUE);
    }

    private void setField(Class<?> type, String name, Object value) throws Exception {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        field.set(mojo, value);
    }

    @Test
    void doubleQuoteEveryArgument() throws Exception {
        // when
        List<String> jlinkArgs = mojo.createJlinkArgs(List.of(), List.of());

        // then
        assertThat(jlinkArgs).noneMatch(arg -> arg.trim().isBlank());
    }

    @DisabledOnOs(OS.WINDOWS)
    @Test
    void singleQuotesShellCommandUnix() throws Exception {
        // when
        List<String> jlinkArgs = mojo.createJlinkArgs(List.of("foo", "bar"), List.of("mvn", "jlink"));
        Commandline cmdLine = JLinkExecutor.createJLinkCommandLine(new File("/path/to/jlink"), jlinkArgs);

        // then
        assertThat(cmdLine.toString())
                .isEqualTo(
                        "/bin/sh -c '/path/to/jlink \"--strip-debug\" \"--module-path\" \"foo:bar\" \"--add-modules\" \"mvn,jlink\"'");
    }

    @EnabledOnOs(OS.WINDOWS)
    @Test
    void singleQuotesShellCommandWindows() throws Exception {
        // when
        List<String> jlinkArgs = mojo.createJlinkArgs(List.of("foo", "bar"), List.of("mvn", "jlink"));
        Commandline cmdLine = JLinkExecutor.createJLinkCommandLine(new File("/path/to/jlink"), jlinkArgs);

        // then
        assertThat(cmdLine.toString()).startsWith("cmd.exe ");
        assertThat(cmdLine.toString())
                .contains(
                        "\\path\\to\\jlink \"--strip-debug\" \"--module-path\" \"foo;bar\" \"--add-modules\" \"mvn,jlink");
    }

    @Test
    void getModulePathElements() throws Exception {
        Path dependency = Path.of("target/test-classes/dependency.jar");
        when(resolved.getPaths()).thenReturn(List.of(dependency));
        when(resolved.getModuleDescriptor(dependency))
                .thenReturn(
                        Optional.of(ModuleDescriptor.newModule("test.module").build()));

        Map<String, File> modulePathElements = mojo.getModulePathElements(resolved, new File("target/does-not-exist"));

        assertThat(modulePathElements).containsOnlyKeys("test.module");
        assertThat(modulePathElements.get("test.module")).isEqualTo(dependency.toFile());
    }

    @Test
    void ignoreAutomaticModules() throws Exception {
        Path valid = Path.of("target/test-classes/dependency1.jar");
        Path automatic = Path.of("target/test-classes/dependency2.jar");
        when(resolved.getPaths()).thenReturn(List.of(valid, automatic));
        when(resolved.getModuleDescriptor(valid))
                .thenReturn(
                        Optional.of(ModuleDescriptor.newModule("valid.module").build()));
        when(resolved.getModuleDescriptor(automatic))
                .thenReturn(Optional.of(
                        ModuleDescriptor.newAutomaticModule("automatic.module").build()));

        Map<String, File> modulePathElements = mojo.getModulePathElements(resolved, new File("target/does-not-exist"));

        assertThat(modulePathElements).containsOnlyKeys("valid.module");
    }

    @Test
    void projectClassesAreReadFromTheirModuleInfo(@TempDir Path dir) throws Exception {
        Path source = Files.writeString(dir.resolve("module-info.java"), "module the.project {}");
        Path classes = Files.createDirectory(dir.resolve("classes"));
        int exit =
                ToolProvider.getSystemJavaCompiler().run(null, null, null, "-d", classes.toString(), source.toString());
        assertThat(exit).isZero();
        when(resolved.getPaths()).thenReturn(List.of());

        Map<String, File> modulePathElements = mojo.getModulePathElements(resolved, classes.toFile());

        assertThat(modulePathElements).containsOnlyKeys("the.project");
    }

    @Test
    void failWhenProjectClassesHaveNoModuleInfo(@TempDir Path dir) throws Exception {
        when(resolved.getPaths()).thenReturn(List.of());

        assertThatThrownBy(() -> mojo.getModulePathElements(resolved, dir.toFile()))
                .isInstanceOf(MojoException.class)
                .hasMessageContaining("does not contain a module-info.java file");
    }

    @Test
    void ignoreJarWithoutModuleDescriptor(@TempDir Path dir) throws Exception {
        Path plain = Files.createFile(dir.resolve("plain.jar"));
        when(resolved.getPaths()).thenReturn(List.of(plain));
        when(resolved.getModuleDescriptor(plain)).thenReturn(Optional.empty());

        assertThat(mojo.getModulePathElements(resolved, new File("target/does-not-exist")))
                .isEmpty();
    }

    @Test
    void failWhenDirectoryHasNoModuleDescriptor(@TempDir Path dir) throws Exception {
        when(resolved.getPaths()).thenReturn(List.of(dir));
        when(resolved.getModuleDescriptor(dir)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> mojo.getModulePathElements(resolved, new File("target/does-not-exist")))
                .isInstanceOf(MojoException.class)
                .hasMessageContaining("does not have a module-info.java file");
    }
}
