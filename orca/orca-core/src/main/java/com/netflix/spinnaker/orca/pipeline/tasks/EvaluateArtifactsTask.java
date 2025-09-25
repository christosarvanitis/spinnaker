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

import com.google.common.base.Strings;
import com.netflix.spinnaker.kork.artifacts.ArtifactTypes;
import com.netflix.spinnaker.kork.artifacts.model.Artifact;
import com.netflix.spinnaker.orca.api.pipeline.Task;
import com.netflix.spinnaker.orca.api.pipeline.TaskResult;
import com.netflix.spinnaker.orca.api.pipeline.models.ExecutionStatus;
import com.netflix.spinnaker.orca.api.pipeline.models.StageExecution;
import com.netflix.spinnaker.orca.pipeline.EvaluateArtifactsStage;
import org.springframework.stereotype.Component;
import javax.annotation.Nonnull;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;

@Component
public class EvaluateArtifactsTask implements Task {

  @Override
  @Nonnull
  public TaskResult execute(@Nonnull StageExecution stage) {
    EvaluateArtifactsStage.EvaluateArtifactsStageContext context =
        stage.mapTo(EvaluateArtifactsStage.EvaluateArtifactsStageContext.class);

    Map<String, Object> outputs = new HashMap<>();

    List<Artifact> artifacts = context.getArtifactContents().stream()
        .filter(artifactContent -> !Strings.isNullOrEmpty(artifactContent.getContents().toString()))
        .map(artifactContent -> artifactFromContent(artifactContent))
        .collect(Collectors.toList());
    outputs = Collections.singletonMap("artifacts", artifacts);

    return TaskResult.builder(ExecutionStatus.SUCCEEDED)
        .outputs(outputs)
        .build();
  }

  private Artifact artifactFromContent(EvaluateArtifactsStage.ArtifactContent artifactContent) {
    return Artifact.builder()
        .type(ArtifactTypes.EMBEDDED_BASE64.getMimeType())
        .artifactAccount("embedded-artifact")
        .name(artifactContent.getName())
        .reference(Base64.getEncoder().encodeToString(artifactContent.getContents().toString().getBytes(StandardCharsets.UTF_8)))
        .build();
  }
}
