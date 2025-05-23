/*
 * Copyright 2019 Pivotal Inc.
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

package com.netflix.spinnaker.gate.controllers;

import com.netflix.spinnaker.gate.services.internal.IgorService;
import com.netflix.spinnaker.gate.services.internal.OrcaServiceSelector;
import com.netflix.spinnaker.kork.retrofit.Retrofit2SyncCall;
import io.swagger.v3.oas.annotations.Operation;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/concourse")
@RequiredArgsConstructor
public class ConcourseController {

  private final Optional<IgorService> igorService;
  private final OrcaServiceSelector orcaService;

  @Operation(summary = "Retrieve the list of team names available to triggers")
  @GetMapping(value = "/{buildMaster}/teams")
  List<String> teams(@PathVariable("buildMaster") String buildMaster) {
    return Retrofit2SyncCall.execute(igorService.get().getConcourseTeams(buildMaster));
  }

  @Operation(summary = "Retrieve the list of pipeline names for a given team available to triggers")
  @GetMapping(value = "/{buildMaster}/teams/{team}/pipelines")
  List<String> pipelines(
      @PathVariable("buildMaster") String buildMaster, @PathVariable("team") String team) {
    return Retrofit2SyncCall.execute(igorService.get().getConcoursePipelines(buildMaster, team));
  }

  @Operation(summary = "Retrieve the list of job names for a given pipeline available to triggers")
  @GetMapping(value = "/{buildMaster}/teams/{team}/pipelines/{pipeline}/jobs")
  List<String> jobs(
      @PathVariable("buildMaster") String buildMaster,
      @PathVariable("team") String team,
      @PathVariable("pipeline") String pipeline) {
    return Retrofit2SyncCall.execute(
        igorService.get().getConcourseJobs(buildMaster, team, pipeline));
  }

  @Operation(
      summary =
          "Retrieve the list of resource names for a given pipeline available to the Concourse stage")
  @GetMapping(value = "/{buildMaster}/teams/{team}/pipelines/{pipeline}/resources")
  List<String> resources(
      @PathVariable("buildMaster") String buildMaster,
      @PathVariable("team") String team,
      @PathVariable("pipeline") String pipeline) {
    return Retrofit2SyncCall.execute(
        igorService.get().getConcourseResources(buildMaster, team, pipeline));
  }

  @Operation(
      summary =
          "Inform Spinnaker of the Concourse build running connected to a particular Concourse stage execution")
  @PostMapping("/stage/start")
  void stageExecution(
      @RequestParam("stageId") String stageId,
      @RequestParam("job") String job,
      @RequestParam("buildNumber") Integer buildNumber) {
    Retrofit2SyncCall.execute(
        orcaService.select().concourseStageExecution(stageId, job, buildNumber, ""));
  }
}
