/*
 * Copyright 2019 the original author or authors.
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

import org.gradle.api.Named;
import org.gradle.api.internal.model.NamedObjectInstantiator;
import org.gradle.internal.Cast;
import org.gradle.internal.hash.Hasher;
import org.gradle.internal.isolation.Isolatable;
import org.gradle.internal.snapshot.ValueSnapshot;

import javax.annotation.Nullable;

public class IsolatedNamedManagedType implements Isolatable<Object> {
    private final NamedObjectInstantiator.Managed value;
    private final NamedObjectInstantiator instantiator;

    public IsolatedNamedManagedType(NamedObjectInstantiator.Managed value, NamedObjectInstantiator instantiator) {
        this.value = value;
        this.instantiator = instantiator;
    }

    @Override
    public ValueSnapshot asSnapshot() {
        return new NamedManagedTypeSnapshot(value.publicType().getName(), value.getName());
    }

    @Override
    public void appendToHasher(Hasher hasher) {
        hasher.putString(value.publicType().getName());
        hasher.putString(value.getName());
    }

    @Override
    public Object isolate() {
        return value;
    }

    @Nullable
    @Override
    public <S> Isolatable<S> coerce(Class<S> type) {
        if (type.isInstance(value)) {
            return Cast.uncheckedCast(this);
        }
        if (value.publicType().getName().equals(type.getName())) {
            NamedObjectInstantiator.Managed newValue = (NamedObjectInstantiator.Managed) instantiator.named(type.asSubclass(Named.class), value.getName());
            return Cast.uncheckedCast(new IsolatedNamedManagedType(newValue, instantiator));
        }
        return null;
    }
}
