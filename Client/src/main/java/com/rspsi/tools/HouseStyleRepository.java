package com.rspsi.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.rspsi.misc.JsonUtil;

import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class HouseStyleRepository {

    private static final ObjectMapper JSON_MAPPER = JsonUtil.getDefaultMapper();
    private static final Map<BuildingStyle, HouseStyleDefinition> CACHE = new ConcurrentHashMap<>();
    private static final Map<String, ObjectNode> MERGED_RESOURCE_CACHE = new ConcurrentHashMap<>();

    private HouseStyleRepository() {
    }

    public static HouseStyleDefinition load(BuildingStyle style) {
        if (style == null) {
            throw new IllegalArgumentException("style cannot be null");
        }
        if (style.getResourcePath() == null) {
            throw new IllegalArgumentException("style does not have a JSON resource: " + style);
        }
        return CACHE.computeIfAbsent(style, HouseStyleRepository::loadInternal);
    }

    private static HouseStyleDefinition loadInternal(BuildingStyle style) {
        ObjectNode mergedNode = loadMergedResource(style.getResourcePath(), new LinkedHashSet<>());
        try {
            return JSON_MAPPER.treeToValue(mergedNode, HouseStyleDefinition.class);
        } catch (IOException ex) {
            throw new IllegalStateException("Unable to read house style resource: " + style.getResourcePath(), ex);
        }
    }

    private static ObjectNode loadMergedResource(String resourcePath, Set<String> loadingStack) {
        ObjectNode cached = MERGED_RESOURCE_CACHE.get(resourcePath);
        if (cached != null) {
            return cached.deepCopy();
        }
        if (!loadingStack.add(resourcePath)) {
            throw new IllegalStateException("Circular house style inheritance detected: " + loadingStack + " -> " + resourcePath);
        }
        try (InputStream stream = HouseStyleRepository.class.getClassLoader().getResourceAsStream(resourcePath)) {
            if (stream == null) {
                throw new IllegalStateException("Missing house style resource: " + resourcePath);
            }
            JsonNode parsed = JSON_MAPPER.readTree(stream);
            if (!(parsed instanceof ObjectNode currentNode)) {
                throw new IllegalStateException("House style resource must be a JSON object: " + resourcePath);
            }

            JsonNode extendsNode = currentNode.remove("extendsStyle");
            ObjectNode mergedNode = currentNode.deepCopy();
            if (extendsNode != null && !extendsNode.isNull()) {
                String inheritedPath = extendsNode.asText("").trim();
                if (!inheritedPath.isEmpty()) {
                    String parentResourcePath = resolveInheritedResourcePath(resourcePath, inheritedPath);
                    ObjectNode parentNode = loadMergedResource(parentResourcePath, loadingStack);
                    mergedNode = mergeObjectNodes(parentNode, mergedNode);
                }
            }
            MERGED_RESOURCE_CACHE.putIfAbsent(resourcePath, mergedNode.deepCopy());
            return mergedNode;
        } catch (IOException ex) {
            throw new IllegalStateException("Unable to read house style resource: " + resourcePath, ex);
        } finally {
            loadingStack.remove(resourcePath);
        }
    }

    private static String resolveInheritedResourcePath(String currentResourcePath, String inheritedPath) {
        if (inheritedPath.contains("/") || inheritedPath.startsWith("\\")) {
            return inheritedPath.replace('\\', '/');
        }
        int lastSlash = currentResourcePath.lastIndexOf('/');
        if (lastSlash == -1) {
            return inheritedPath;
        }
        return currentResourcePath.substring(0, lastSlash + 1) + inheritedPath;
    }

    private static ObjectNode mergeObjectNodes(ObjectNode baseNode, ObjectNode overrideNode) {
        ObjectNode merged = baseNode.deepCopy();
        overrideNode.fields().forEachRemaining(entry -> {
            String fieldName = entry.getKey();
            JsonNode overrideValue = entry.getValue();
            JsonNode baseValue = merged.get(fieldName);
            if (baseValue instanceof ObjectNode baseObject && overrideValue instanceof ObjectNode overrideObject) {
                merged.set(fieldName, mergeObjectNodes(baseObject, overrideObject));
            } else {
                merged.set(fieldName, overrideValue.deepCopy());
            }
        });
        return merged;
    }
}
