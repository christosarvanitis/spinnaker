/*
 * Copyright 2025 Harness, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.netflix.spinnaker.orca.pipeline.tasks;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import com.netflix.spinnaker.kork.artifacts.ArtifactTypes;
import com.netflix.spinnaker.kork.artifacts.model.Artifact;
import com.netflix.spinnaker.orca.api.pipeline.TaskResult;
import com.netflix.spinnaker.orca.api.pipeline.models.ExecutionStatus;
import com.netflix.spinnaker.orca.api.pipeline.models.ExecutionType;
import com.netflix.spinnaker.orca.api.pipeline.models.StageExecution;
import com.netflix.spinnaker.orca.pipeline.EvaluateArtifactsStage;
import com.netflix.spinnaker.orca.pipeline.EvaluateArtifactsStage.ArtifactContent;
import com.netflix.spinnaker.orca.pipeline.model.PipelineExecutionImpl;
import com.netflix.spinnaker.orca.pipeline.model.StageExecutionImpl;

import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
public class EvaluateArtifactsTaskTest {

  private EvaluateArtifactsTask evaluateArtifactsTask;

  @BeforeEach
  public void setup() {
    evaluateArtifactsTask = new EvaluateArtifactsTask();
  }

  @Test
  @DisplayName("should convert artifact contents to base64-encoded embedded artifacts")
  public void testArtifactContentToEmbeddedArtifact() {
    // Given
    List<ArtifactContent> artifactContents = new ArrayList<>();

    ArtifactContent content1 = new ArtifactContent();
    content1.setName("artifact1");
    content1.setContents("content-value-1");

    ArtifactContent content2 = new ArtifactContent();
    content2.setName("artifact2");
    content2.setContents("content-value-2");

    artifactContents.add(content1);
    artifactContents.add(content2);

    StageExecution stage = mockStageWithArtifactContents(artifactContents);

    // When
    TaskResult result = evaluateArtifactsTask.execute(stage);

    // Then
    assertThat(Optional.of(result.getStatus())).isEqualTo(ExecutionStatus.SUCCEEDED);
    assertThat(result.getOutputs()).containsKey("artifacts");

    List<Artifact> artifacts = (List<Artifact>) result.getOutputs().get("artifacts");
    assertThat(artifacts).hasSize(2);

    // Check first artifact
    Artifact artifact1 = artifacts.get(0);
    assertThat(artifact1.getName()).isEqualTo("artifact1");
    assertThat(artifact1.getType()).isEqualTo(ArtifactTypes.EMBEDDED_BASE64.getMimeType());
    assertThat(artifact1.getArtifactAccount()).isEqualTo("embedded-artifact");

    // Decode and verify the content
    String decodedContent1 = new String(Base64.getDecoder().decode(artifact1.getReference()), StandardCharsets.UTF_8);
    assertThat(decodedContent1).isEqualTo("content-value-1");

    // Check second artifact
    Artifact artifact2 = artifacts.get(1);
    assertThat(artifact2.getName()).isEqualTo("artifact2");
    String decodedContent2 = new String(Base64.getDecoder().decode(artifact2.getReference()), StandardCharsets.UTF_8);
    assertThat(decodedContent2).isEqualTo("content-value-2");
  }

  @Test
  @DisplayName("should filter out artifacts with null or empty contents")
  public void testFilterNullOrEmptyContents() {
    // Given
    List<ArtifactContent> artifactContents = new ArrayList<>();

    ArtifactContent content1 = new ArtifactContent();
    content1.setName("valid-artifact");
    content1.setContents("valid-content");

    ArtifactContent content2 = new ArtifactContent();
    content2.setName("null-content-artifact");
    content2.setContents(null);

    ArtifactContent content3 = new ArtifactContent();
    content3.setName("empty-content-artifact");
    content3.setContents("");

    artifactContents.add(content1);
    artifactContents.add(content2);
    artifactContents.add(content3);

    StageExecution stage = mockStageWithArtifactContents(artifactContents);

    // When
    TaskResult result = evaluateArtifactsTask.execute(stage);

    // Then
    assertThat(Optional.of(result.getStatus())).isEqualTo(ExecutionStatus.SUCCEEDED);
    List<Artifact> artifacts = (List<Artifact>) result.getOutputs().get("artifacts");

    // Only one artifact should pass the filter
    assertThat(artifacts).hasSize(1);
    assertThat(artifacts.get(0).getName()).isEqualTo("valid-artifact");
  }

  @Test
  @DisplayName("should handle empty artifact contents list")
  public void testEmptyArtifactContentsList() {
    // Given
    StageExecution stage = mockStageWithArtifactContents(Collections.emptyList());

    // When
    TaskResult result = evaluateArtifactsTask.execute(stage);

    // Then
    assertThat(Optional.of(result.getStatus())).isEqualTo(ExecutionStatus.SUCCEEDED);
    List<Artifact> artifacts = (List<Artifact>) result.getOutputs().get("artifacts");
    assertThat(artifacts).isEmpty();
  }

  @Test
  @DisplayName("should handle null artifact contents list")
  public void testNullArtifactContentsList() {
    // Given
    StageExecution stage = mockStageWithArtifactContents(null);

    // When
    TaskResult result = evaluateArtifactsTask.execute(stage);

    // Then
    assertThat(Optional.of(result.getStatus())).isEqualTo(ExecutionStatus.SUCCEEDED);
    List<Artifact> artifacts = (List<Artifact>) result.getOutputs().get("artifacts");
    assertThat(artifacts).isEmpty();
  }

  @Test
  @DisplayName("should handle non-string content objects")
  public void testNonStringContents() {
    // Given
    List<ArtifactContent> artifactContents = new ArrayList<>();

    ArtifactContent content = new ArtifactContent();
    content.setName("object-content");
    Map<String, String> contentMap = new HashMap<>();
    contentMap.put("key1", "value1");
    contentMap.put("key2", "value2");
    content.setContents(contentMap);

    artifactContents.add(content);

    StageExecution stage = mockStageWithArtifactContents(artifactContents);

    // When
    TaskResult result = evaluateArtifactsTask.execute(stage);

    // Then
    assertThat(Optional.of(result.getStatus())).isEqualTo(ExecutionStatus.SUCCEEDED);
    List<Artifact> artifacts = (List<Artifact>) result.getOutputs().get("artifacts");
    assertThat(artifacts).hasSize(1);

    // The toString() of the map should be encoded
    String expectedContent = contentMap.toString();
    String decodedContent = new String(Base64.getDecoder().decode(artifacts.get(0).getReference()), StandardCharsets.UTF_8);
    assertThat(decodedContent).isEqualTo(expectedContent);
  }

  private StageExecution mockStageWithArtifactContents(List<ArtifactContent> artifactContents) {
    StageExecution mockStage = spy(
        new StageExecutionImpl(
            new PipelineExecutionImpl(ExecutionType.PIPELINE, "test"),
            "evaluateArtifacts",
            new HashMap<>()));

    EvaluateArtifactsStage.EvaluateArtifactsStageContext context =
        new EvaluateArtifactsStage.EvaluateArtifactsStageContext(artifactContents);

    when(mockStage.mapTo(EvaluateArtifactsStage.EvaluateArtifactsStageContext.class))
        .thenReturn(context);

    return mockStage;
  }
}
