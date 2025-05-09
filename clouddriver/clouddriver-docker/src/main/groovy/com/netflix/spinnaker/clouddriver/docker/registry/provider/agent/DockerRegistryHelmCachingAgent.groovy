package com.netflix.spinnaker.clouddriver.docker.registry.provider.agent

import com.netflix.spinnaker.cats.agent.AgentDataType
import com.netflix.spinnaker.clouddriver.docker.registry.cache.Keys
import groovy.util.logging.Slf4j

import static java.util.Collections.unmodifiableSet

@Slf4j
class DockerRegistryHelmCachingAgent extends AbstractDockerRegistryCachingAgent {
  static final Set<AgentDataType> types = unmodifiableSet([
    AgentDataType.Authority.AUTHORITATIVE.forType(Keys.Namespace.TAGGED_HELM_OCI_IMAGE.ns),
    AgentDataType.Authority.AUTHORITATIVE.forType(Keys.Namespace.IMAGE_ID.ns)
  ] as Set)

  DockerRegistryHelmCachingAgent(dockerRegistryCloudProvider, accountName, credentials, index, threadCount, intervalSecs, registry) {
    super(dockerRegistryCloudProvider, accountName, credentials, index, threadCount, intervalSecs, registry)
  }

  @Override
  Set<AgentDataType> getDataTypes() {
    return types
  }

  @Override
  Collection<String> getRepositories() {
    return credentials.helmOciRepositories
  }

  @Override
  String getAgentTypeName() {
    return DockerRegistryHelmCachingAgent.simpleName
  }

  @Override
  String getTaggedNamespace() {
    return Keys.Namespace.TAGGED_HELM_OCI_IMAGE.ns
  }

  @Override
  String getTaggedKey(String account, String repository, String tag) {
    return Keys.getHelmTaggedImageKey(account, repository, tag)
  }

  @Override
  String getImageIdKey(String imageId) {
    return Keys.getHelmImageIdKey(imageId)
  }

  @Override
  Long getAgentInterval() {
    return interval
  }
}
