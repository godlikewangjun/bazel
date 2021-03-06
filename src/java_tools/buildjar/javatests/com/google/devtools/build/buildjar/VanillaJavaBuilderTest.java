// Copyright 2017 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.devtools.build.buildjar;

import static com.google.common.truth.Truth.assertThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.io.ByteStreams;
import com.google.devtools.build.buildjar.VanillaJavaBuilder.VanillaJavaBuilderResult;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Enumeration;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** {@link VanillaJavaBuilder}Test */
@RunWith(JUnit4.class)
public class VanillaJavaBuilderTest {
  @Rule public final TemporaryFolder temporaryFolder = new TemporaryFolder();

  VanillaJavaBuilderResult run(List<String> args) throws Exception {
    try (VanillaJavaBuilder builder = new VanillaJavaBuilder()) {
      return builder.run(args);
    }
  }

  ImmutableMap<String, byte[]> readJar(File file) throws IOException {
    ImmutableMap.Builder<String, byte[]> result = ImmutableMap.builder();
    try (JarFile jf = new JarFile(file)) {
      Enumeration<JarEntry> entries = jf.entries();
      while (entries.hasMoreElements()) {
        JarEntry je = entries.nextElement();
        result.put(je.getName(), ByteStreams.toByteArray(jf.getInputStream(je)));
      }
    }
    return result.build();
  }

  @Test
  public void hello() throws Exception {
    Path source = temporaryFolder.newFile("Test.java").toPath();
    Path output = temporaryFolder.newFile("out.jar").toPath();
    Files.write(
        source,
        ImmutableList.of(
            "class A {", //
            "}"),
        UTF_8);
    Path sourceJar = temporaryFolder.newFile("src.srcjar").toPath();
    try (OutputStream os = Files.newOutputStream(sourceJar);
        JarOutputStream jos = new JarOutputStream(os)) {
      jos.putNextEntry(new JarEntry("B.java"));
      jos.write("class B {}".getBytes(UTF_8));
    }
    Path resource = temporaryFolder.newFile("resource.properties").toPath();
    Files.write(resource, "hello".getBytes(UTF_8));

    VanillaJavaBuilderResult result =
        run(
            ImmutableList.of(
                "--sources",
                source.toString(),
                "--source_jars",
                sourceJar.toString(),
                "--output",
                output.toString(),
                "--classpath_resources",
                resource.toString(),
                "--bootclasspath",
                Paths.get(System.getProperty("java.home")).resolve("lib/rt.jar").toString(),
                "--classdir",
                temporaryFolder.newFolder().toString()));

    assertThat(result.output()).isEmpty();
    assertThat(result.ok()).isTrue();

    ImmutableMap<String, byte[]> outputEntries = readJar(output.toFile());
    assertThat(outputEntries.keySet())
        .containsExactly(
            "META-INF/", "META-INF/MANIFEST.MF", "A.class", "B.class", "resource.properties");
  }

  @Test
  public void error() throws Exception {
    Path source = temporaryFolder.newFile("Test.java").toPath();
    Path output = temporaryFolder.newFolder().toPath().resolve("out.jar");
    Files.write(
        source,
        ImmutableList.of(
            "class A {", //
            "}}"),
        UTF_8);

    VanillaJavaBuilderResult result =
        run(
            ImmutableList.of(
                "--sources",
                source.toString(),
                "--output",
                output.toString(),
                "--bootclasspath",
                Paths.get(System.getProperty("java.home")).resolve("lib/rt.jar").toString(),
                "--classdir",
                temporaryFolder.newFolder().toString()));

    assertThat(result.output()).contains("class, interface, or enum expected");
    assertThat(result.ok()).isFalse();
    assertThat(Files.exists(output)).isFalse();
  }
}
