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

package com.netflix.spinnaker.orca.pipeline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.netflix.spinnaker.kork.expressions.ExpressionEvaluationSummary;
import com.netflix.spinnaker.orca.api.pipeline.graph.TaskNode;
import com.netflix.spinnaker.orca.api.pipeline.models.ExecutionType;
import com.netflix.spinnaker.orca.api.pipeline.models.StageExecution;
import com.netflix.spinnaker.orca.pipeline.EvaluateArtifactsStage.ArtifactContent;
import com.netflix.spinnaker.orca.pipeline.EvaluateArtifactsStage.EvaluateArtifactsStageContext;
import com.netflix.spinnaker.orca.pipeline.model.PipelineExecutionImpl;
import com.netflix.spinnaker.orca.pipeline.model.StageContext;
import com.netflix.spinnaker.orca.pipeline.model.StageExecutionImpl;
import com.netflix.spinnaker.orca.pipeline.tasks.EvaluateArtifactsTask;
import com.netflix.spinnaker.orca.pipeline.tasks.artifacts.BindProducedArtifactsTask;
import com.netflix.spinnaker.orca.pipeline.util.ContextParameterProcessor;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
public class EvaluateArtifactsStageTest {

  @Mock private ObjectMapper mockObjectMapper;
  @Mock private ContextParameterProcessor mockContextParameterProcessor;
  @Mock private TaskNode.Builder taskNodeBuilder;
  @Mock private ExpressionEvaluationSummary mockSummary;

  private EvaluateArtifactsStage evaluateArtifactsStage;
  private StageExecution stage;

  @BeforeEach
  public void setup() {
    evaluateArtifactsStage = new EvaluateArtifactsStage(mockObjectMapper);
    stage = new StageExecutionImpl(
        new PipelineExecutionImpl(ExecutionType.PIPELINE, "test"),
        "evaluateArtifact",
        new HashMap<>());
  }

  @Test
  @DisplayName("should add correct tasks to task graph")
  public void testTaskGraph() {
    when(taskNodeBuilder.withTask(anyString(), any())).thenReturn(taskNodeBuilder);

    // When
    evaluateArtifactsStage.taskGraph(stage, taskNodeBuilder);

    // Then
    verify(taskNodeBuilder).withTask("evaluateArtifact", EvaluateArtifactsTask.class);
    verify(taskNodeBuilder).withTask("bindArtifacts", BindProducedArtifactsTask.class);
  }

  @Test
  @DisplayName("should process expressions in artifact contents")
  public void testProcessExpressions() {
    // Given
    List<ArtifactContent> artifactContents = new ArrayList<>();
    ArtifactContent artifactContent = new ArtifactContent();
    artifactContent.setName("myArtifact");
    artifactContent.setContents("${trigger.version}");
    artifactContents.add(artifactContent);

    Map<String, Object> stageContext = new HashMap<>();
    stageContext.put("artifactContents", artifactContents);
    stage = new StageExecutionImpl(
        new PipelineExecutionImpl(ExecutionType.PIPELINE, "test"),
        "evaluateArtifact",
        stageContext);

    StageContext augmentedContext = mock(StageContext.class);
    when(mockContextParameterProcessor.buildExecutionContext(stage))
        .thenReturn(augmentedContext);

    Map<String, Object> inputMap = new HashMap<>();
    inputMap.put("contents", "${trigger.version}");

    Map<String, Object> outputMap = new HashMap<>();
    outputMap.put("contents", "1.0.0");

    when(mockContextParameterProcessor.process(
        eq(inputMap),  // Use eq() for exact matching
        any(StageContext.class),
        eq(true),
        eq(mockSummary)
    )).thenReturn(outputMap);

    when(mockSummary.getFailureCount()).thenReturn(0);

    Map<String, Object> evaluatedContext = new HashMap<>();
    evaluatedContext.put("artifactContents", artifactContents);
    when(mockObjectMapper.convertValue(any(EvaluateArtifactsStageContext.class), (TypeReference<Object>) any()))
        .thenReturn(evaluatedContext);

    // When
    boolean result = evaluateArtifactsStage.processExpressions(stage, mockContextParameterProcessor, mockSummary);

    // Then
    assertThat(result).isFalse();
    assertThat(artifactContent.getContents()).isEqualTo("1.0.0");
    verify(augmentedContext).put("myArtifact", "1.0.0");

    // Verify the context was updated
    assertThat(stage.getContext().containsKey("artifactContents")).isTrue();
  }

  @Test
  @DisplayName("should handle expression evaluation failures")
  public void testProcessExpressionsWithFailures() {
    // Given
    List<ArtifactContent> artifactContents = new ArrayList<>();
    ArtifactContent artifactContent = new ArtifactContent();
    artifactContent.setName("myArtifact");
    artifactContent.setContents("${trigger.nonExistentProperty}");
    artifactContents.add(artifactContent);

    Map<String, Object> stageContext = new HashMap<>();
    stageContext.put("artifactContents", artifactContents);
    stage = new StageExecutionImpl(
        new PipelineExecutionImpl(ExecutionType.PIPELINE, "test"),
        "evaluateArtifact",
        stageContext);

    StageContext augmentedContext = mock(StageContext.class);
    when(mockContextParameterProcessor.buildExecutionContext(stage))
        .thenReturn(augmentedContext);

    // Simulate an expression evaluation failure
    when(mockContextParameterProcessor.process(any(), any(), anyBoolean(), any()))
        .thenReturn(Collections.singletonMap("contents", "${trigger.nonExistentProperty}"));

    // First check returns 0, second check returns 1 to simulate failure
    when(mockSummary.getFailureCount()).thenReturn(0).thenReturn(1);

    Map<String, Object> evaluatedContext = new HashMap<>();
    evaluatedContext.put("artifactContents", artifactContents);
    when(mockObjectMapper.convertValue(any(EvaluateArtifactsStageContext.class), (TypeReference<Object>) any()))
        .thenReturn(evaluatedContext);

    // Create a properly typed Map for the expression results
    Map<String, Set<ExpressionEvaluationSummary.Result>> expressionResults = new HashMap<>();
    Set<ExpressionEvaluationSummary.Result> errors = new HashSet<>();
    errors.add(new ExpressionEvaluationSummary.Result(
        null, System.currentTimeMillis(),
        "Failed to evaluate ${trigger.nonExistentProperty}",
        null));
    expressionResults.put("${trigger.nonExistentProperty}", errors);

    when(mockSummary.getExpressionResult()).thenReturn(expressionResults);

    // Mock the ObjectMapper to return our simplified error map when converting
    Map<String, Object> errorMap = new HashMap<>();
    errorMap.put("errors", Collections.singletonList("Failed to evaluate ${trigger.nonExistentProperty}"));
    when(mockObjectMapper.convertValue(any(), eq(Map.class)))
        .thenReturn(errorMap);

    // When
    boolean result = evaluateArtifactsStage.processExpressions(stage, mockContextParameterProcessor, mockSummary);

    // Then
    assertThat(result).isFalse();
    // The original content should not be modified when evaluation fails
    assertThat(artifactContent.getContents()).isEqualTo("${trigger.nonExistentProperty}");
    verify(augmentedContext, never()).put(eq("myArtifact"), any());

    // Verify that failure summary is added to context
    assertThat(stage.getContext().containsKey("expressionEvaluationSummary")).isTrue();
  }

  @Test
  @DisplayName("should handle null artifact contents")
  public void testProcessExpressionsWithNullContents() {
    // Given
    Map<String, Object> stageContext = new HashMap<>();
    stageContext.put("artifactContents", null);
    stage = new StageExecutionImpl(
        new PipelineExecutionImpl(ExecutionType.PIPELINE, "test"),
        "evaluateArtifact",
        stageContext);

    StageContext augmentedContext = mock(StageContext.class);
    when(mockContextParameterProcessor.buildExecutionContext(stage))
        .thenReturn(augmentedContext);

    Map<String, Object> evaluatedContext = new HashMap<>();
    evaluatedContext.put("artifactContents", null);
    when(mockObjectMapper.convertValue(any(EvaluateArtifactsStageContext.class), (TypeReference<Object>) any()))
        .thenReturn(evaluatedContext);

    // When
    boolean result = evaluateArtifactsStage.processExpressions(stage, mockContextParameterProcessor, mockSummary);

    // Then
    assertThat(result).isFalse();
    verify(mockContextParameterProcessor, never()).process(any(), any(), anyBoolean(), any());

    // Verify context was still updated
    verify(mockObjectMapper).convertValue(any(EvaluateArtifactsStageContext.class), (TypeReference<Object>) any());
    assertThat(stage.getContext().containsKey("artifactContents")).isTrue();
  }
}
