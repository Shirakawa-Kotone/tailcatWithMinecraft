package com.tailscale.mclink;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Small Java 8 equivalents of immutable collection and text operations. */
final class Compatibility {
    private Compatibility() {}
    static boolean isBlank(String value) {
        return value.codePoints().allMatch(Character::isWhitespace);
    }
    @SafeVarargs static <T> List<T> immutableList(T... values) {
        return immutableListCopy(Arrays.asList(values));
    }
    static <T> List<T> immutableListCopy(Collection<? extends T> values) {
        List<T> result = new ArrayList<>(values.size());
        for (T value : values) result.add(Objects.requireNonNull(value));
        return Collections.unmodifiableList(result);
    }
    @SafeVarargs static <T> Set<T> immutableSet(T... values) {
        return Collections.unmodifiableSet(new HashSet<>(immutableList(values)));
    }
}
