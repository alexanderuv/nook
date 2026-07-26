package io.nook.contract

/**
 * A partial-update field: [Keep] leaves the stored value alone, [Set] writes
 * the carried value — which may itself be null for nullable fields, where
 * "set to null" (clear) and "leave alone" mean different things.
 */
public sealed interface FieldChange<out T> {
    public data object Keep : FieldChange<Nothing>
    public data class Set<T>(public val value: T) : FieldChange<T>
}
