/*
 * Copyright 2017 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.internal.snapshot.impl;

import com.google.common.collect.ImmutableList;
import org.gradle.internal.hash.Hasher;
import org.gradle.internal.isolation.Isolatable;
import org.gradle.internal.isolation.IsolationException;
import org.gradle.internal.snapshot.ValueSnapshot;
import org.gradle.internal.snapshot.ValueSnapshotter;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

public class ListValueSnapshot implements ValueSnapshot, Isolatable<List> {
    public static final ListValueSnapshot EMPTY = new ListValueSnapshot(ImmutableList.of());

    private final ImmutableList<ValueSnapshot> elements;

    public ListValueSnapshot(ImmutableList<ValueSnapshot> elements) {
        this.elements = elements;
    }

    public List<ValueSnapshot> getElements() {
        return elements;
    }

    @Override
    public void appendToHasher(Hasher hasher) {
        hasher.putString("List");
        hasher.putInt(elements.size());
        for (ValueSnapshot element : elements) {
            element.appendToHasher(hasher);
        }
    }

    @Override
    public ValueSnapshot snapshot(Object value, ValueSnapshotter snapshotter) {
        return processList(value, snapshotter);
    }

    private ValueSnapshot processList(Object value, ValueSnapshotter snapshotter) {
        if (!(value instanceof List)) {
            return snapshotter.snapshot(value);
        }

        // Find first position where values are different
        List<?> list = (List<?>) value;
        int pos = 0;
        int len = Math.min(elements.size(), list.size());
        ValueSnapshot newElement = null;
        for (; pos < len; pos++) {
            ValueSnapshot element = elements.get(pos);
            newElement = snapshotter.snapshot(list.get(pos), element);
            if (element != newElement) {
                break;
            }
            newElement = null;
        }
        if (pos == elements.size() && pos == list.size()) {
            // Same size and no differences
            return this;
        }

        // Copy the snapshots whose values are the same, then snapshot remaining values
        ImmutableList.Builder<ValueSnapshot> newElements = ImmutableList.builderWithExpectedSize(list.size());
        for (int i = 0; i < pos; i++) {
            newElements.add(elements.get(i));
        }
        if (pos < list.size()) {
            // If we broke out of the comparison because there was a difference, we can reuse the snapshot of the new element
            if (newElement != null) {
                newElements.add(newElement);
                pos++;
            }
            // Anything left over only exists in the new list
            for (int i = pos; i < list.size(); i++) {
                newElements.add(snapshotter.snapshot(list.get(i)));
            }
        }

        return new ListValueSnapshot(newElements.build());
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == this) {
            return true;
        }
        if (obj == null || obj.getClass() != getClass()) {
            return false;
        }
        ListValueSnapshot other = (ListValueSnapshot) obj;
        return elements.equals(other.elements);
    }

    @Override
    public int hashCode() {
        return elements.hashCode();
    }

    @Override
    @SuppressWarnings("unchecked")
    public List isolate() {
        List list = new ArrayList();
        for (ValueSnapshot snapshot : elements) {
            if (snapshot instanceof Isolatable) {
                list.add(((Isolatable) snapshot).isolate());
            } else {
                throw new IsolationException(snapshot);
            }
        }
        return list;
    }

    @Nullable
    @Override
    public <S> Isolatable<S> coerce(Class<S> type) {
        return null;
    }
}
