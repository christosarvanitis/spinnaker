package com.netflix.spinnaker.clouddriver.docker.registry.provider.agent

import com.netflix.spinnaker.cats.agent.*
import com.netflix.spinnaker.cats.cache.CacheData
import com.netflix.spinnaker.cats.provider.ProviderCache
import com.netflix.spinnaker.clouddriver.docker.registry.DockerRegistryCloudProvider
import com.netflix.spinnaker.clouddriver.docker.registry.cache.DefaultCacheDataBuilder
import com.netflix.spinnaker.clouddriver.docker.registry.cache.Keys
import com.netflix.spinnaker.clouddriver.docker.registry.provider.DockerRegistryProvider
import com.netflix.spinnaker.clouddriver.docker.registry.provider.DockerRegistryProviderUtils
import com.netflix.spinnaker.clouddriver.docker.registry.security.DockerRegistryCredentials
import com.netflix.spinnaker.kork.docker.model.DockerRegistryTags
import com.netflix.spinnaker.kork.retrofit.exceptions.SpinnakerHttpException
import groovy.util.logging.Slf4j

import java.util.concurrent.ConcurrentMap
import java.util.concurrent.TimeUnit

@Slf4j
abstract class AbstractDockerRegistryCachingAgent implements CachingAgent, AccountAware, AgentIntervalAware {
  protected DockerRegistryCredentials credentials
  protected DockerRegistryCloudProvider dockerRegistryCloudProvider
  protected String accountName
  protected final int index
  protected final int threadCount
  protected final long interval
  protected String registry

  AbstractDockerRegistryCachingAgent(DockerRegistryCloudProvider dockerRegistryCloudProvider,
                                     String accountName,
                                     DockerRegistryCredentials credentials,
                                     int index,
                                     int threadCount,
                                     Long intervalSecs,
                                     String registry) {
    this.dockerRegistryCloudProvider = dockerRegistryCloudProvider
    this.accountName = accountName
    this.credentials = credentials
    this.index = index
    this.threadCount = threadCount
    this.interval = TimeUnit.SECONDS.toMillis(intervalSecs)
    this.registry = registry
  }

  abstract Set<AgentDataType> getDataTypes()
  abstract Collection<String> getRepositories()
  abstract String getAgentTypeName()
  abstract String getTaggedNamespace()
  abstract String getTaggedKey(String account, String repository, String tag)
  abstract String getImageIdKey(String imageId)

  @Override
  Collection<AgentDataType> getProvidedDataTypes() {
    return getDataTypes()
  }

  @Override
  CacheResult loadData(ProviderCache providerCache) {
    Map<String, Set<String>> tagMap = loadTags()
    buildCacheResult(tagMap)
  }

  @Override
  String getAgentType() {
    "${accountName}/${getAgentTypeName()}[${index + 1}/$threadCount]"
  }

  @Override
  String getProviderName() {
    DockerRegistryProvider.PROVIDER_NAME
  }

  @Override
  String getAccountName() {
    return accountName
  }

  protected Map<String, Set<String>> loadTags() {
    getRepositories().findAll { it ->
      threadCount == 1 || (it.hashCode() % threadCount).abs() == index
    }.collectEntries { repository ->
      if (credentials.skip?.contains(repository)) {
        return [:]
      }
      DockerRegistryTags tags = null
      try {
        tags = credentials.client.getTags(repository)
      } catch (Exception e) {
        if (e instanceof SpinnakerHttpException && ((SpinnakerHttpException)e).getResponseCode() == 404) {
          log.warn("Could not load tags for ${repository} in ${credentials.client.address}, reason: ${e.message}")
        } else {
          log.error("Could not load tags for ${repository} in ${credentials.client.address}", e)
        }
        return [:]
      }
      def name = tags?.name
      def imageTags = tags?.tags
      if (name && imageTags) {
        if (name != repository) {
          log.warn("Docker registry $accountName responded with an image name that does not match the repository name. Defaulting to repository='$repository' over name='$name'")
          name = repository
        }
        [(name): imageTags]
      } else {
        return [:]
      }
    }
  }

  protected CacheResult buildCacheResult(Map<String, Set<String>> tagMap) {
    log.info("Describing items in ${getAgentType()}")

    ConcurrentMap<String, DefaultCacheDataBuilder> cachedTags = DefaultCacheDataBuilder.defaultCacheDataBuilderMap()
    ConcurrentMap<String, DefaultCacheDataBuilder> cachedIds = DefaultCacheDataBuilder.defaultCacheDataBuilderMap()

    tagMap.forEach { repository, tags ->
      tags.parallelStream().forEach { tag ->
        if (!tag) {
          log.warn("Empty tag encountered for $accountName/$repository, not caching")
          return
        }
        def tagKey = getTaggedKey(accountName, repository, tag)
        def imageId = DockerRegistryProviderUtils.imageId(registry, repository, tag)
        def imageIdKey = getImageIdKey(imageId)
        def digest = null
        def digestContent = null
        def creationDate = null

        if (credentials.trackDigests) {
          try {
            digest = credentials.client.getDigest(repository, tag)
          } catch (Exception e) {
            if (e instanceof SpinnakerHttpException && ((SpinnakerHttpException)e).getResponseCode() == 404) {
              log.warn("Image manifest for $tagKey no longer available; tag will not be cached: $e.message")
              return
            } else {
              log.warn("Error retrieving manifest for $tagKey; digest and tag will not be cached: $e.message")
              return
            }
          }
        }

        if (credentials.inspectDigests) {
          try {
            digest = credentials.client.getConfigDigest(repository, tag)
            digestContent = credentials.client.getDigestContent(repository, digest)
          } catch (Exception e) {
            log.warn("Error retrieving config digest for $tagKey; digest and tag will not be cached: $e.message")
          }
        }

        if (credentials.sortTagsByDate) {
          try {
            creationDate = credentials.client.getCreationDate(repository, tag)
          } catch (Exception e) {
            log.warn("Unable to fetch tag creation date, reason: {} (tag: {}, repository: {})", e.message, tag, repository)
          }
        }

        def tagData = new DefaultCacheDataBuilder()
        tagData.setId(tagKey)
        tagData.attributes.put("name", "${repository}:${tag}".toString())
        tagData.attributes.put("account", accountName)
        tagData.attributes.put("digest", digest)
        tagData.attributes.put("date", creationDate)
        if (digestContent?.config != null) {
          tagData.attributes.put("labels", digestContent.config.Labels)
        }
        cachedTags.put(tagKey, tagData)

        def idData = new DefaultCacheDataBuilder()
        idData.setId(imageIdKey)
        idData.attributes.put("tagKey", tagKey)
        idData.attributes.put("account", accountName)
        cachedIds.put(imageIdKey, idData)
      }
      null
    }

    Map<String, Collection<CacheData>> cacheResults = [
      (getTaggedNamespace()): cachedTags.values().collect { it.build() },
      (Keys.Namespace.IMAGE_ID.ns): cachedIds.values().collect { it.build() }
    ]

    return new DefaultCacheResult(cacheResults)
  }
}
