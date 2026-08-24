/*
 * Copyright 2020 Avast Software, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.netflix.spinnaker.clouddriver.controllers;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.netflix.spinnaker.kork.common.Header.USER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.emptyString;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultHandlers.print;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

import ch.qos.logback.classic.Level;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.google.common.collect.ImmutableList;
import com.netflix.spinnaker.clouddriver.Main;
import com.netflix.spinnaker.clouddriver.artifacts.ArtifactCredentialsRepository;
import com.netflix.spinnaker.clouddriver.artifacts.helm.HelmArtifactCredentials;
import com.netflix.spinnaker.credentials.CredentialsRepository;
import com.netflix.spinnaker.filters.AuthenticatedRequestFilter;
import com.netflix.spinnaker.kork.artifacts.model.Artifact;
import com.netflix.spinnaker.kork.github.test.GitHubAppTestKeys;
import com.netflix.spinnaker.kork.test.log.MemoryAppender;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.context.WebApplicationContext;

@ExtendWith(SpringExtension.class)
@WebAppConfiguration
@SpringBootTest(classes = Main.class)
@TestPropertySource(
    properties = {
      "redis.enabled = false",
      "sql.enabled = false",
      "spring.application.name = clouddriver",
      "artifacts.helm.enabled = true"
    })
public class ArtifactControllerSpec {

  /**
   * Stands in for the GitHub API so the GitHub App accounts below can reproduce real failures
   * without network access.
   */
  private static final WireMockServer GITHUB = startStubbedGitHub();

  private static final Path GITHUB_APP_KEY = writeThrowawayGitHubAppKey();

  private static WireMockServer startStubbedGitHub() {
    WireMockServer server = new WireMockServer(WireMockConfiguration.options().dynamicPort());
    server.start();
    // authenticating as the app succeeds ...
    server.stubFor(
        get(urlPathEqualTo("/app"))
            .willReturn(
                aResponse()
                    .withHeader("Content-Type", "application/json")
                    .withBody("{\"id\": 12345, \"name\": \"test-app\"}")));
    // ... but the app has no installation with access to the repository, which is the failure
    // reported as a 500 before installation errors were classified as configuration errors
    server.stubFor(
        get(urlPathEqualTo("/repos/some-org/some-repo/installation"))
            .willReturn(aResponse().withStatus(404)));
    Runtime.getRuntime().addShutdownHook(new Thread(server::stop));
    return server;
  }

  private static Path writeThrowawayGitHubAppKey() {
    try {
      Path key = Files.createTempFile("gh-app-key", ".pem");
      key.toFile().deleteOnExit();
      return GitHubAppTestKeys.writePkcs8Pem(key);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  @DynamicPropertySource
  static void githubAppAccount(DynamicPropertyRegistry registry) {
    registry.add("artifacts.github.enabled", () -> "true");
    registry.add("artifacts.github.accounts[0].name", () -> "github-app-account");
    registry.add("artifacts.github.accounts[0].githubApp.appId", () -> "12345");
    registry.add(
        "artifacts.github.accounts[0].githubApp.appPrivateKeyPath", GITHUB_APP_KEY::toString);
    // Only the GitHub App API calls go to the stub. The artifact reference itself stays on
    // api.github.com so that it satisfies the account's default URL restrictions - it is never
    // fetched, because resolving the installation fails first.
    registry.add("artifacts.github.accounts[0].githubApp.apiBaseUrl", GITHUB::baseUrl);
  }

  private MockMvc mvc;

  @Autowired private WebApplicationContext webApplicationContext;

  @Autowired private ObjectMapper objectMapper;

  @Autowired private CredentialsRepository<HelmArtifactCredentials> helmCredentials;

  /**
   * This takes X-SPINNAKER-* headers from requests to clouddriver and puts them in the MDC. This is
   * enabled when clouddriver runs normally (by WebConfig), but needs explicit mention to function
   * in these tests.
   */
  @Autowired AuthenticatedRequestFilter authenticatedRequestFilter;

  @BeforeEach
  public void setup() throws Exception {
    this.mvc =
        webAppContextSetup(webApplicationContext).addFilters(authenticatedRequestFilter).build();
  }

  @Test
  public void testFetchWithMisconfiguredArtifact() throws Exception {
    Artifact misconfiguredArtifact = Artifact.builder().name("foo").build();

    // Capture the log messages that ArtifactCredentialsRepository generates,
    // since that's the class that logs a message when it detects a
    // misconfigured artifact.
    MemoryAppender memoryAppender = new MemoryAppender(ArtifactCredentialsRepository.class);

    // Use USER (i.e. X-SPINNAKER-HEADER) as a request header to match what
    // logback includes in log messages for assertions in this test to work.
    String userValue = "some user";

    MvcResult result =
        mvc.perform(
                put("/artifacts/fetch")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header(USER.getHeader(), userValue)
                    .content(objectMapper.writeValueAsString(misconfiguredArtifact)))
            .andReturn();

    mvc.perform(asyncDispatch(result))
        .andDo(print())
        .andExpect(status().isBadRequest())
        .andExpect(content().string(is(emptyString())));

    List<String> userMessages = memoryAppender.layoutSearch("[" + userValue + "]", Level.DEBUG);
    assertThat(userMessages).hasSize(1);
  }

  @Test
  public void testFetchWithGitHubAppConfigurationErrorReturnsBadRequest() throws Exception {
    // The app is not installed for this repository. That is a configuration problem, so it must
    // surface as a 400 rather than a 500: clouddriver has not failed, and retrying cannot help.
    Artifact artifact =
        Artifact.builder()
            .type("github/file")
            .artifactAccount("github-app-account")
            .reference("https://api.github.com/repos/some-org/some-repo/contents/manifest.yml")
            .version("main")
            .build();

    MvcResult result =
        mvc.perform(
                put("/artifacts/fetch")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(artifact)))
            .andReturn();

    mvc.perform(asyncDispatch(result)).andDo(print()).andExpect(status().isBadRequest());
  }

  @Test
  public void testArtifactNames() throws Exception {
    List<String> names = ImmutableList.of("artifact1", "artifact2");
    HelmArtifactCredentials credentials = Mockito.mock(HelmArtifactCredentials.class);
    Mockito.when(credentials.getName()).thenReturn("my-account");
    Mockito.when(credentials.getType()).thenReturn(HelmArtifactCredentials.CREDENTIALS_TYPE);
    Mockito.when(credentials.handlesType("helm/chart")).thenReturn(true);
    Mockito.when(credentials.getArtifactNames()).thenReturn(names);
    helmCredentials.save(credentials);

    mvc.perform(
            get("/artifacts/account/{accountName}/names", credentials.getName())
                .param("type", "helm/chart"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$", Matchers.hasSize(2)))
        .andExpect(jsonPath("$[0]", Matchers.is(names.get(0))))
        .andExpect(jsonPath("$[1]", Matchers.is(names.get(1))));

    // We also don't expect to find an account that can support type artifacts-helm
    mvc.perform(
            get("/artifacts/account/{accountName}/names", credentials.getName())
                .param("type", HelmArtifactCredentials.CREDENTIALS_TYPE))
        .andExpect(status().isNotFound());
  }

  @Test
  public void testArtifactVersions() throws Exception {
    final String artifactName = "my-artifact";
    List<String> versions = ImmutableList.of("version1", "version2");
    HelmArtifactCredentials credentials = Mockito.mock(HelmArtifactCredentials.class);
    Mockito.when(credentials.getName()).thenReturn("my-account");
    Mockito.when(credentials.getType()).thenReturn(HelmArtifactCredentials.CREDENTIALS_TYPE);
    Mockito.when(credentials.handlesType("helm/chart")).thenReturn(true);
    Mockito.when(credentials.getArtifactVersions(artifactName)).thenReturn(versions);
    helmCredentials.save(credentials);

    mvc.perform(
            get("/artifacts/account/{accountName}/versions", credentials.getName())
                .param("type", "helm/chart")
                .param("artifactName", artifactName))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$", Matchers.hasSize(2)))
        .andExpect(jsonPath("$[0]", Matchers.is(versions.get(0))))
        .andExpect(jsonPath("$[1]", Matchers.is(versions.get(1))));

    // We also don't expect to find an account that can support type artifacts-helm
    mvc.perform(
            get("/artifacts/account/{accountName}/versions", credentials.getName())
                .param("type", HelmArtifactCredentials.CREDENTIALS_TYPE)
                .param("artifactName", artifactName))
        .andExpect(status().isNotFound());
  }
}
