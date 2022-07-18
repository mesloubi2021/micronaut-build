/*
 * Copyright 2003-2021 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.micronaut.build.compat;

import groovy.json.JsonSlurper;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.provider.Provider;
import org.gradle.api.provider.ProviderFactory;
import org.gradle.api.tasks.CacheableTask;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.TaskAction;
import org.jetbrains.annotations.NotNull;

import javax.inject.Inject;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

@CacheableTask
public abstract class FindBaselineTask extends DefaultTask {

    public static final int CACHE_IN_SECONDS = 3600;

    @Input
    public abstract Property<String> getGithubSlug();

    @Input
    public abstract Property<String> getCurrentVersion();

    @Input
    protected Provider<Long> getTimestamp() {
        return getProviders().provider(() -> {
            long seconds = System.currentTimeMillis() / 1000;
            long base = seconds / CACHE_IN_SECONDS;
            return base * CACHE_IN_SECONDS;
        });
    }

    @Internal
    protected Provider<byte[]> getJson() {
        return getGithubSlug().map(this::fetchReleasesFromGitHub);
    }

    @Inject
    protected abstract ProviderFactory getProviders();

    private byte[] fetchReleasesFromGitHub(String slug) {
        String releasesUrl = "https://api.github.com/repos/" + normalizeSlug(slug) + "/releases";
        try {
            URL url = new URL(releasesUrl);
            HttpURLConnection con = (HttpURLConnection) url.openConnection();
            con.setRequestMethod("GET");
            con.setRequestProperty("Accept", "application/vnd.github.v3+json");
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (ReadableByteChannel rbc = Channels.newChannel(con.getInputStream()); WritableByteChannel wbc=Channels.newChannel(out)){
                ByteBuffer buffer = ByteBuffer.allocateDirect(16 * 1024);
                while (rbc.read(buffer) != -1) {
                    buffer.flip();
                    wbc.write(buffer);
                    buffer.compact();
                }
                buffer.flip();
                while (buffer.hasRemaining()) {
                    wbc.write(buffer);
                }
                return out.toByteArray();
            }
        } catch (IOException ex) {
            throw new GradleException("Failed to read releases from " + releasesUrl, ex);
        }
    }

    @OutputFile
    public abstract RegularFileProperty getPreviousVersion();

    @TaskAction
    public void execute() throws IOException {
        byte[] jsonBytes = getJson().get();

        JsonSlurper slurper = new JsonSlurper();
        List<Map<String, Object>> json = (List<Map<String, Object>>) slurper.parse(jsonBytes);
        List<VersionModel> releases = json.stream()
                .filter(release -> !Objects.equals(release.get("draft"), true) && !Objects.equals(release.get("prerelease"), true))
                .map(release -> (String) release.get("tag_name"))
                .map(tag -> {
                    if (tag.startsWith("v")) {
                        return tag.substring(1);
                    }
                    return tag;
                }).filter(v -> !v.contains("-"))
                .map(VersionModel::of)
                .sorted()
                .collect(Collectors.toList());
        VersionModel current = VersionModel.of(trimVersion());
        Optional<VersionModel> previous = releases.stream()
                .filter(v -> v.compareTo(current) < 0)
                .reduce((a, b) -> b);
        if (!previous.isPresent()) {
            throw new IllegalStateException("Could not find a previous version for " + current);
        }
        Files.write(getPreviousVersion().get().getAsFile().toPath(), previous.get().toString().getBytes("UTF-8"));
    }

    @NotNull
    private String trimVersion() {
        String version = getCurrentVersion().get();
        int idx = version.indexOf('-');
        if (idx >= 0) {
            return version.substring(0, idx);
        }
        return version;
    }

    private static String normalizeSlug(String slug) {
        if (slug.startsWith("/")) {
            slug = slug.substring(1);
        }
        if (slug.endsWith("/")) {
            slug = slug.substring(0, slug.length() - 1);
        }
        return slug;
    }
}
