/*
 * Copyright 2026 Harness, Inc.
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

package com.netflix.spinnaker.kork.github;

import com.netflix.spinnaker.kork.exceptions.UserException;

/**
 * Thrown when GitHub App authentication cannot succeed for configuration reasons rather than
 * transient ones - for example the app is not installed in the organization owning the repository,
 * it has not been granted access to that repository, or the app id, private key or clock are wrong.
 *
 * <p>Retrying such a request cannot help, so this is deliberately <em>not</em> an {@link
 * java.io.IOException}: it is unchecked, so it passes through the {@code catch (IOException)}
 * blocks that artifact downloading uses to wrap transient failures, and it extends {@link
 * UserException} so that Spinnaker's shared exception handling reports it as a 400 with the message
 * intact instead of a 500. Transient failures (GitHub 5xx, network errors, timeouts) remain {@code
 * IOException}s and stay retryable.
 */
public class GitHubAppAuthenticationException extends UserException {

  public GitHubAppAuthenticationException(String message) {
    this(message, null);
  }

  public GitHubAppAuthenticationException(String message, Throwable cause) {
    super(message, cause, message);
    setRetryable(false);
  }
}
