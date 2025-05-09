package com.netflix.spinnaker.clouddriver.docker.registry.provider.agent

import com.netflix.spinnaker.cats.agent.AgentDataType
import com.netflix.spinnaker.clouddriver.docker.registry.DockerRegistryCloudProvider
import com.netflix.spinnaker.clouddriver.docker.registry.security.DockerRegistryCredentials
import com.netflix.spinnaker.kork.docker.service.DockerRegistryClient
import groovy.util.logging.Slf4j
import spock.lang.Specification

class AbstractDockerRegistryCachingAgentSpec extends Specification {

  def "loadTags filters repositories by threadCount and index"() {
    given:
    def repos = ['repo1', 'repo2', 'repo3', 'repo4']
    def agent = new TestDockerRegistryCachingAgent(
      dockerRegistryCloudProvider: Mock(DockerRegistryCloudProvider),
      accountName: "test-account",
      credentials: Mock(DockerRegistryCredentials) {
        getRepositories() >> repos
        getClient() >> Mock(DockerRegistryClient) {
          address >> "test-registry"
        }
      },
      index: 0,
      threadCount: 2,
      intervalSecs: 60,
      registry: "test-registry"
    )

    when:
    def tags = agent.loadTags()

    then:
    tags.keySet().every { repo ->
      (repo.hashCode().abs() % 2) == 0
    }
  }

  def "getAgentType returns correct format"() {
    given:
    def agent = new TestDockerRegistryCachingAgent(
      dockerRegistryCloudProvider: Mock(DockerRegistryCloudProvider),
      accountName: "acct",
      credentials: Mock(DockerRegistryCredentials),
      index: 1,
      threadCount: 3,
      intervalSecs: 60,
      registry: "reg"
    )

    expect:
    agent.getAgentType() == "acct/TestDockerRegistryCachingAgent[2/3]"
  }

  def "getProviderName returns provider name"() {
    given:
    def agent = new TestDockerRegistryCachingAgent(
      dockerRegistryCloudProvider: Mock(DockerRegistryCloudProvider),
      accountName: "acct",
      credentials: Mock(DockerRegistryCredentials),
      index: 0,
      threadCount: 1,
      intervalSecs: 60,
      registry: "reg"
    )

    expect:
    agent.getProviderName() == "dockerRegistry"
  }

  def "buildCacheResult logs and returns CacheResult"() {
    given:
    def agent = new TestDockerRegistryCachingAgent(
      dockerRegistryCloudProvider: Mock(DockerRegistryCloudProvider),
      accountName: "acct",
      credentials: Mock(DockerRegistryCredentials),
      index: 0,
      threadCount: 1,
      intervalSecs: 60,
      registry: "reg"
    )
    def tagMap = [repo1: ["tag1", "tag2"] as Set, repo2: ["tag3"] as Set]

    when:
    def result = agent.buildCacheResult(tagMap)

    then:
    result instanceof com.netflix.spinnaker.cats.agent.CacheResult
  }

  // Minimal concrete subclass for testing
  @Slf4j
  static class TestDockerRegistryCachingAgent extends AbstractDockerRegistryCachingAgent {
    TestDockerRegistryCachingAgent(Map args) {
      super(args.dockerRegistryCloudProvider, args.accountName, args.credentials, args.index, args.threadCount, args.intervalSecs, args.registry)
    }
    @Override Set<AgentDataType> getDataTypes() { [] as Set }
    @Override Collection<String> getRepositories() { credentials.getRepositories() }
    @Override String getAgentTypeName() { "TestDockerRegistryCachingAgent" }
    @Override String getTaggedNamespace() { "namespace" }
    @Override String getTaggedKey(String a, String b, String c) { "taggedKey" }
    @Override String getImageIdKey(String id) { "imageIdKey" }

    @Override
    Long getAgentInterval() {
      return interval
    }
  }
}
