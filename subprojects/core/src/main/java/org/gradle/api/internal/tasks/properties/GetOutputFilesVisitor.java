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

package org.gradle.api.internal.tasks.properties;

import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import org.gradle.api.NonNullApi;
import org.gradle.api.internal.file.FileCollectionInternal;
import org.gradle.api.internal.file.FileCollectionLeafVisitor;
import org.gradle.api.internal.file.FileTreeInternal;
import org.gradle.api.internal.tasks.PropertyFileCollection;
import org.gradle.api.internal.tasks.TaskPropertyUtils;
import org.gradle.api.tasks.util.PatternSet;
import org.gradle.internal.MutableBoolean;
import org.gradle.internal.file.PathToFileResolver;
import org.gradle.internal.file.TreeType;
import org.gradle.util.DeferredUtil;

import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

@NonNullApi
public class GetOutputFilesVisitor extends PropertyVisitor.Adapter {
    private final List<OutputFilePropertySpec> specs = Lists.newArrayList();
    private final String ownerDisplayName;
    private final PathToFileResolver fileResolver;
    private ImmutableSortedSet<OutputFilePropertySpec> fileProperties;
    private boolean hasDeclaredOutputs;

    public GetOutputFilesVisitor(String ownerDisplayName, PathToFileResolver fileResolver) {
        this.ownerDisplayName = ownerDisplayName;
        this.fileResolver = fileResolver;
    }

    @Override
    public void visitOutputFileProperty(String propertyName, boolean optional, PropertyValue value, OutputFilePropertyType filePropertyType) {
        hasDeclaredOutputs = true;
        resolveOutputFilePropertySpecs(ownerDisplayName, propertyName, value, filePropertyType, fileResolver, new Consumer<OutputFilePropertySpec>() {
            @Override
            public void accept(OutputFilePropertySpec outputFilePropertySpec) {
                specs.add(outputFilePropertySpec);
            }
        });
    }

    public static void resolveOutputFilePropertySpecs(String ownerDisplayName, String propertyName, PropertyValue value, OutputFilePropertyType filePropertyType, PathToFileResolver fileResolver, Consumer<OutputFilePropertySpec> consumer) {
        Object unpackedValue = DeferredUtil.unpack(value);
        if (unpackedValue == null) {
            return;
        }
        if (filePropertyType == OutputFilePropertyType.DIRECTORIES || filePropertyType == OutputFilePropertyType.FILES) {
            resolveCompositeOutputFilePropertySpecs(ownerDisplayName, propertyName, unpackedValue, filePropertyType.getOutputType(), fileResolver, consumer);
        } else {
            File outputFile = fileResolver.resolve(unpackedValue);
            DefaultCacheableOutputFilePropertySpec filePropertySpec = new DefaultCacheableOutputFilePropertySpec(propertyName, null, outputFile, filePropertyType.getOutputType());
            consumer.accept(filePropertySpec);
        }
    }

    public ImmutableSortedSet<OutputFilePropertySpec> getFileProperties() {
        if (fileProperties == null) {
            fileProperties = TaskPropertyUtils.collectFileProperties("output", specs.iterator());
        }
        return fileProperties;
    }

    public boolean hasDeclaredOutputs() {
        return hasDeclaredOutputs;
    }

    private static void resolveCompositeOutputFilePropertySpecs(final String ownerDisplayName, final String propertyName, Object unpackedValue, final TreeType outputType, final PathToFileResolver resolver, Consumer<OutputFilePropertySpec> consumer) {
        if (unpackedValue instanceof Map) {
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) unpackedValue).entrySet()) {
                Object key = entry.getKey();
                if (key == null) {
                    throw new IllegalArgumentException(String.format("Mapped output property '%s' has null key", propertyName));
                }
                String id = key.toString();
                File file = resolver.resolve(entry.getValue());
                consumer.accept(new DefaultCacheableOutputFilePropertySpec(propertyName, "." + id, file, outputType));
            }
        } else {
            final List<File> roots = Lists.newArrayList();
            final MutableBoolean nonFileRoot = new MutableBoolean();
            FileCollectionInternal outputFileCollection = FileCollectionHelper.asFileCollection(resolver, unpackedValue);
            outputFileCollection.visitLeafCollections(new FileCollectionLeafVisitor() {
                @Override
                public void visitCollection(FileCollectionInternal fileCollection) {
                    Iterables.addAll(roots, fileCollection);
                }

                @Override
                public void visitGenericFileTree(FileTreeInternal fileTree) {
                    nonFileRoot.set(true);
                }

                @Override
                public void visitFileTree(File root, PatternSet patterns) {
                    // We could support an unfiltered DirectoryFileTree here as a cacheable root,
                    // but because @OutputDirectory also doesn't support it we choose not to.
                    nonFileRoot.set(true);
                }
            });

            if (nonFileRoot.get()) {
                consumer.accept(new CompositeOutputFilePropertySpec(
                    propertyName,
                    new PropertyFileCollection(ownerDisplayName, propertyName, "output", resolver, unpackedValue),
                    outputType)
                );
            } else {
                int index = 0;
                for (File root : roots) {
                    consumer.accept(new DefaultCacheableOutputFilePropertySpec(propertyName, "$" + (++index), root, outputType));
                }
            }
        }
    }

}
