/*
 * Copyright 2010-2022 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.gradle.kpm.idea

import org.jetbrains.kotlin.tooling.core.*
import java.io.Serializable
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.withLock

sealed interface IdeaKotlinExtras : Extras, Serializable

fun IdeaKotlinExtras(): IdeaKotlinExtras = EmptyIdeaKotlinExtras

fun IdeaKotlinExtras(extras: IterableExtras): IdeaKotlinExtras {
    if (extras.isEmpty()) return EmptyIdeaKotlinExtras
    return IdeaKotlinExtrasImpl(extras.toExtras())
}

@WriteReplacedModel(replacedBy = IdeaKotlinExtrasSurrogate::class)
@InternalKotlinGradlePluginApi
private class IdeaKotlinExtrasImpl(private val extras: IterableExtras) : IdeaKotlinExtras, AbstractIterableExtras() {
    override val ids: Set<Extras.Id<*>> get() = extras.ids
    override val entries: Set<Extras.Entry<*>> = extras.entries
    override fun <T : Any> get(key: Extras.Key<T>): T? = extras[key]
    override fun isEmpty(): Boolean = extras.isEmpty()

    private fun writeReplace(): Any {
        return IdeaKotlinExtrasSurrogate(serialize(extras.entries))
    }
}

@WriteReplacedModel(replacedBy = EmptyIdeaKotlinExtras.Surrogate::class)
private object EmptyIdeaKotlinExtras : IdeaKotlinExtras, AbstractIterableExtras() {
    override val ids: Set<Extras.Id<*>> = emptySet()
    override val entries: Set<Extras.Entry<*>> = emptySet()
    override fun <T : Any> get(key: Extras.Key<T>): T? = null

    object Surrogate : Serializable {
        @Suppress("unused")
        private const val serialVersionUID = 0L
        private fun readResolve(): Any = EmptyIdeaKotlinExtras
    }

    private fun writeReplace(): Any = Surrogate
}

private class IdeaKotlinExtrasSurrogate(
    private val extras: Map<Extras.Id<*>, ByteArray>
) : Serializable {
    private fun readResolve(): Any {
        return SerializedIdeaKotlinExtras(extras.toMutableMap())
    }

    companion object {
        private const val serialVersionUID = 0L
    }
}

@WriteReplacedModel(replacedBy = IdeaKotlinExtrasSurrogate::class)
@Suppress("unchecked_cast")
private class SerializedIdeaKotlinExtras(
    private val serializedExtras: MutableMap<Extras.Id<*>, ByteArray>
) : IdeaKotlinExtras {
    private val lock = ReentrantReadWriteLock()
    private val readLock = lock.readLock()
    private val writeLock = lock.writeLock()
    private val deserializedNulls = mutableSetOf<Extras.Id<*>>()
    private val deserializedExtras = mutableExtrasOf()
    override val ids: Set<Extras.Id<*>> = serializedExtras.keys.toSet()

    override fun <T : Any> get(key: Extras.Key<T>): T? {
        /*
        Fast path: If id is not present, we can already return null
        Locking is not required, since the `ids` are captured and immutable here
        */
        if (key.id !in ids) return null

        /*
        Guard: A key without serializer capability has no rights to access any data here
        This is done to prevent subtle heisenberg-like bugs where a value might, or might not be
        returned after or before accessing with another key that actually has a deserializer attached.
        */
        val serializer = key.capability<IdeaKotlinExtrasSerializer<T>>() ?: return null

        readLock.withLock {
            /* Check previous results */
            deserializedExtras[key]?.let { return it }
            if (key.id in deserializedNulls) return null
        }

        writeLock.withLock {
            /* try to deserialize */
            val data = serializedExtras[key.id] ?: return null
            return serializer.deserialize(key, data).also { value ->
                /* Release memory in favor of deserialized value cache */
                serializedExtras.remove(key.id)

                /* Cache serialization result */
                if (value == null) deserializedNulls.add(key.id)
                else deserializedExtras[key] = value
            }
        }
    }

    private fun writeReplace(): Any {
        readLock.withLock {
            return IdeaKotlinExtrasSurrogate(
                serializedExtras + serialize(deserializedExtras.entries)
            )
        }
    }
}

private fun serialize(entries: Set<Extras.Entry<*>>): Map<Extras.Id<*>, ByteArray> {
    fun <T : Any> serializeOrNull(entry: Extras.Entry<T>): ByteArray? {
        val serializer = entry.key.capability<IdeaKotlinExtrasSerializer<T>>() ?: return null
        return serializer.serialize(entry.key, entry.value)
    }

    return entries.mapNotNull { it.key.id to (serializeOrNull(it) ?: return@mapNotNull null) }.toMap()
}
