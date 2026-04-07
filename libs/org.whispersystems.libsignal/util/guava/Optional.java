package org.whispersystems.libsignal.util.guava;

import java.io.Serializable;

public final class Optional<T> implements Serializable {

    private static final long serialVersionUID = 0L;

    @SuppressWarnings("rawtypes")
    private static final Optional ABSENT = new Optional<>(null);

    private final T reference;

    private Optional(T reference) {
        this.reference = reference;
    }

    @SuppressWarnings("unchecked")
    public static <T> Optional<T> absent() {
        return (Optional<T>) ABSENT;
    }

    public static <T> Optional<T> of(T reference) {
        if (reference == null) {
            throw new NullPointerException("Optional.of() called with null.");
        }
        return new Optional<>(reference);
    }

    public static <T> Optional<T> fromNullable(T reference) {
        return (reference == null) ? Optional.<T>absent() : new Optional<>(reference);
    }

    public boolean isPresent() {
        return reference != null;
    }

    public T get() {
        if (reference == null) {
            throw new IllegalStateException("Optional.get() cannot be called on an absent value.");
        }
        return reference;
    }

    public T or(T defaultValue) {
        return (reference != null) ? reference : defaultValue;
    }

    public T orNull() {
        return reference;
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == this) return true;
        if (!(obj instanceof Optional)) return false;
        Optional<?> other = (Optional<?>) obj;
        if (reference == null && other.reference == null) return true;
        if (reference == null || other.reference == null) return false;
        return reference.equals(other.reference);
    }

    @Override
    public int hashCode() {
        return (reference == null) ? 0 : reference.hashCode() + 0x598df91c;
    }

    @Override
    public String toString() {
        return isPresent()
                ? "Optional.of(" + reference + ")"
                : "Optional.absent()";
    }
}
