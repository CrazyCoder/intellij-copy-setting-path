package com.intellij.plugin.copySettingPath

import java.lang.reflect.Field

/**
 * Utility functions for reflection-based operations.
 *
 * These functions provide safe access to private fields and methods
 * in IntelliJ Platform classes where direct API access is not available.
 */

/**
 * Finds a private or inherited field by name or type name in a class hierarchy.
 *
 * @param type The class to start searching from.
 * @param name The field name to search for.
 * @param orTypeName Optional type name to match if field name doesn't match.
 * @return The found field, or null if not found.
 */
fun findInheritedField(type: Class<*>, name: String, orTypeName: String? = null): Field? {
    return generateSequence(type) { it.superclass }
        .takeWhile { it != Any::class.java }
        .flatMap { it.declaredFields.asSequence() }
        .firstOrNull { field ->
            field.name == name || (orTypeName != null && field.type.name == orTypeName)
        }
}

/**
 * Finds a parent component in the hierarchy by its class name.
 *
 * This is useful when we need to find a specific IntelliJ component
 * without having a direct dependency on its class.
 *
 * Handles anonymous inner classes (e.g., ConfigurableEditor$1 matches ConfigurableEditor)
 * by checking both exact name match and superclass hierarchy.
 *
 * @param component The component to start searching from.
 * @param className The fully qualified class name to find.
 * @return The parent component with the matching class name, or null if not found.
 */
fun findParentByClassName(component: java.awt.Component, className: String): java.awt.Component? {
    var current: java.awt.Component? = component
    while (current != null) {
        if (matchesClassName(current.javaClass, className)) {
            return current
        }
        current = current.parent
    }
    return null
}

/**
 * Checks if a class matches the target class name.
 * Handles exact matches, anonymous inner classes, and inheritance.
 */
private fun matchesClassName(clazz: Class<*>, targetClassName: String): Boolean {
    // Check exact match
    if (clazz.name == targetClassName) {
        return true
    }

    // Check if it's an anonymous/inner class of the target (e.g., ConfigurableEditor$1)
    if (clazz.name.startsWith("$targetClassName$")) {
        return true
    }

    // Check superclass hierarchy (handles anonymous classes that extend the target)
    var superClass: Class<*>? = clazz.superclass
    while (superClass != null && superClass != Any::class.java) {
        if (superClass.name == targetClassName) {
            return true
        }
        superClass = superClass.superclass
    }

    return false
}

/**
 * Invokes the getPathNames() method on a target object via reflection.
 *
 * @param target The object to invoke the method on (typically a SettingsEditor).
 * @return Collection of path name strings, or null if invocation fails.
 */
@Suppress("UNCHECKED_CAST")
fun invokeGetPathNames(target: Any): Collection<String>? {
    return runCatching {
        val method = target.javaClass.getDeclaredMethod(PathConstants.METHOD_GET_PATH_NAMES)
        method.isAccessible = true
        method.invoke(target) as? Collection<String>
    }.onFailure { e ->
        LOG.debug("Failed to invoke getPathNames: ${e.message}")
    }.getOrNull()
}

/**
 * Extracts the myText field value from an object via reflection.
 *
 * This is commonly used for tree nodes that store their display text
 * in a private myText field.
 *
 * @param obj The object to extract the field from.
 * @return The field value as a string, or null if not found.
 */
fun extractMyTextField(obj: Any): String? {
    return runCatching {
        val field = obj.javaClass.getDeclaredField(PathConstants.FIELD_MY_TEXT)
        field.isAccessible = true
        field.get(obj)?.toString()
    }.getOrNull()
}

/**
 * Extracts a display name from an object using common getter methods.
 *
 * Tries methods in order: getDisplayName, getName, getText, getPresentableText, title.
 * Also handles special cases like InlayGroupSettingProvider where we need group.title().
 *
 * @param obj The object to extract display name from.
 * @param additionalMethods Additional method names to try after the standard ones.
 * @return The display name, or null if not extractable.
 */
fun extractDisplayNameViaReflection(obj: Any, vararg additionalMethods: String): String? {
    val methodsToTry = listOf("getDisplayName", "getName", "getText", "getPresentableText", "title") + additionalMethods

    for (methodName in methodsToTry) {
        runCatching {
            val method = obj.javaClass.getMethod(methodName)
            method.isAccessible = true
            val result = method.invoke(obj)?.toString()
            if (!result.isNullOrBlank() && result != obj.javaClass.name) {
                return result
            }
        }
    }

    // Special case: InlayGroupSettingProvider has group.title()
    // Try getGroup().title() chain for provider-like objects
    runCatching {
        val groupGetter = obj.javaClass.getMethod("getGroup")
        val group = groupGetter.invoke(obj) ?: return@runCatching null
        // Try title() on the group (for InlayGroup enum)
        val titleMethod = group.javaClass.getMethod("title")
        val result = titleMethod.invoke(group)?.toString()
        if (!result.isNullOrBlank()) {
            return result
        }
    }

    return null
}
