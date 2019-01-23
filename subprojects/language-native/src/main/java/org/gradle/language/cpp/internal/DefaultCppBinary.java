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

package org.gradle.language.cpp.internal;

import com.google.common.io.Files;
import org.apache.commons.io.IOUtils;
import org.gradle.api.Buildable;
import org.gradle.api.artifacts.ArtifactCollection;
import org.gradle.api.artifacts.ArtifactView;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.ConfigurationContainer;
import org.gradle.api.artifacts.component.ModuleComponentIdentifier;
import org.gradle.api.artifacts.dsl.DependencyHandler;
import org.gradle.api.artifacts.result.ResolvedArtifactResult;
import org.gradle.api.artifacts.transform.ArtifactTransform;
import org.gradle.api.attributes.Usage;
import org.gradle.api.file.FileCollection;
import org.gradle.api.internal.file.FileOperations;
import org.gradle.api.internal.file.TemporaryFileProvider;
import org.gradle.api.internal.file.collections.MinimalFileSet;
import org.gradle.api.model.ObjectFactory;
import org.gradle.api.provider.Property;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.TaskDependency;
import org.gradle.internal.UncheckedException;
import org.gradle.language.cpp.CppBinary;
import org.gradle.language.cpp.CppPlatform;
import org.gradle.language.cpp.tasks.CppCompile;
import org.gradle.language.internal.DefaultNativeBinary;
import org.gradle.language.nativeplatform.internal.Names;
import org.gradle.nativeplatform.MachineArchitecture;
import org.gradle.nativeplatform.OperatingSystemFamily;
import org.gradle.nativeplatform.TargetMachine;
import org.gradle.nativeplatform.platform.NativePlatform;
import org.gradle.nativeplatform.toolchain.internal.NativeToolChainInternal;
import org.gradle.nativeplatform.toolchain.internal.PlatformToolProvider;

import javax.inject.Inject;
import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.apache.commons.io.FilenameUtils.removeExtension;

public class DefaultCppBinary extends DefaultNativeBinary implements CppBinary {
    private final static String C_PLUS_PLUS_API_DIRS = "cplusplus-api-dirs";

    private final Provider<String> baseName;
    private final FileCollection sourceFiles;
    private final FileCollection includePath;
    private final Configuration linkLibraries;
    private final FileCollection runtimeLibraries;
    private final CppPlatform targetPlatform;
    private final NativeToolChainInternal toolChain;
    private final PlatformToolProvider platformToolProvider;
    private final Configuration includePathConfiguration;
    private final Property<CppCompile> compileTaskProperty;
    private final NativeVariantIdentity identity;
    private final DependencyHandler dependencyHandler;

    public DefaultCppBinary(Names names, ObjectFactory objects, Provider<String> baseName, FileCollection sourceFiles, FileCollection componentHeaderDirs, ConfigurationContainer configurations, Configuration componentImplementation, CppPlatform targetPlatform, NativeToolChainInternal toolChain, PlatformToolProvider platformToolProvider, NativeVariantIdentity identity, DependencyHandler dependencyHandler) {
        super(names, objects, componentImplementation);
        this.baseName = baseName;
        this.sourceFiles = sourceFiles;
        this.targetPlatform = targetPlatform;
        this.toolChain = toolChain;
        this.platformToolProvider = platformToolProvider;
        this.compileTaskProperty = objects.property(CppCompile.class);
        this.identity = identity;
        this.dependencyHandler = dependencyHandler;

        // TODO - reduce duplication with Swift binary

        Configuration includePathConfig = configurations.create(names.withPrefix("cppCompile"));
        includePathConfig.setCanBeConsumed(false);
        includePathConfig.getAttributes().attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.class, Usage.C_PLUS_PLUS_API));
        includePathConfig.getAttributes().attribute(DEBUGGABLE_ATTRIBUTE, identity.isDebuggable());
        includePathConfig.getAttributes().attribute(OPTIMIZED_ATTRIBUTE, identity.isOptimized());
        includePathConfig.getAttributes().attribute(OperatingSystemFamily.OPERATING_SYSTEM_ATTRIBUTE, identity.getTargetMachine().getOperatingSystemFamily());
        includePathConfig.getAttributes().attribute(MachineArchitecture.ARCHITECTURE_ATTRIBUTE, identity.getTargetMachine().getArchitecture());
        includePathConfig.extendsFrom(getImplementationDependencies());

        Configuration nativeLink = configurations.create(names.withPrefix("nativeLink"));
        nativeLink.setCanBeConsumed(false);
        nativeLink.getAttributes().attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.class, Usage.NATIVE_LINK));
        nativeLink.getAttributes().attribute(DEBUGGABLE_ATTRIBUTE, identity.isDebuggable());
        nativeLink.getAttributes().attribute(OPTIMIZED_ATTRIBUTE, identity.isOptimized());
        nativeLink.getAttributes().attribute(OperatingSystemFamily.OPERATING_SYSTEM_ATTRIBUTE, identity.getTargetMachine().getOperatingSystemFamily());
        nativeLink.getAttributes().attribute(MachineArchitecture.ARCHITECTURE_ATTRIBUTE, identity.getTargetMachine().getArchitecture());
        nativeLink.extendsFrom(getImplementationDependencies());

        Configuration nativeRuntime = configurations.create(names.withPrefix("nativeRuntime"));
        nativeRuntime.setCanBeConsumed(false);
        nativeRuntime.getAttributes().attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.class, Usage.NATIVE_RUNTIME));
        nativeRuntime.getAttributes().attribute(DEBUGGABLE_ATTRIBUTE, identity.isDebuggable());
        nativeRuntime.getAttributes().attribute(OPTIMIZED_ATTRIBUTE, identity.isOptimized());
        nativeRuntime.getAttributes().attribute(OperatingSystemFamily.OPERATING_SYSTEM_ATTRIBUTE, identity.getTargetMachine().getOperatingSystemFamily());
        nativeRuntime.getAttributes().attribute(MachineArchitecture.ARCHITECTURE_ATTRIBUTE, identity.getTargetMachine().getArchitecture());
        nativeRuntime.extendsFrom(getImplementationDependencies());

        includePathConfiguration = includePathConfig;
        dependencyHandler.registerTransform(variantTransform -> {
            variantTransform.artifactTransform(UnzipTransform.class);
            variantTransform.getFrom().attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.class, Usage.C_PLUS_PLUS_API));
            variantTransform.getTo().attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.class, C_PLUS_PLUS_API_DIRS));
        });
        ArtifactView includeDirs = includePathConfig.getIncoming().artifactView(viewConfiguration -> {
           viewConfiguration.attributes(attributeContainer -> {
               attributeContainer.attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.class, C_PLUS_PLUS_API_DIRS));
           });
        });
        includePath = componentHeaderDirs.plus(includeDirs.getFiles());
        linkLibraries = nativeLink;
        runtimeLibraries = nativeRuntime;
    }

    @Inject
    protected FileOperations getFileOperations() {
        throw new UnsupportedOperationException();
    }

    @Inject
    protected TemporaryFileProvider getTemporaryFileProvider() {
        throw new UnsupportedOperationException();
    }

    @Inject
    protected NativeDependencyCache getNativeDependencyCache() {
        throw new UnsupportedOperationException();
    }

    @Override
    public Provider<String> getBaseName() {
        return baseName;
    }

    @Override
    public boolean isDebuggable() {
        return identity.isDebuggable();
    }

    @Override
    public boolean isOptimized() {
        return identity.isOptimized();
    }

    @Override
    public FileCollection getCppSource() {
        return sourceFiles;
    }

    @Override
    public FileCollection getCompileIncludePath() {
        return includePath;
    }

    @Override
    public FileCollection getLinkLibraries() {
        return linkLibraries;
    }

    public Configuration getLinkConfiguration() {
        return linkLibraries;
    }

    @Override
    public FileCollection getRuntimeLibraries() {
        return runtimeLibraries;
    }

    public Configuration getIncludePathConfiguration() {
        return includePathConfiguration;
    }

    @Override
    public TargetMachine getTargetMachine() {
        return targetPlatform.getTargetMachine();
    }

    @Override
    public CppPlatform getTargetPlatform() {
        return targetPlatform;
    }

    public NativePlatform getNativePlatform() {
        return ((DefaultCppPlatform) targetPlatform).getNativePlatform();
    }

    @Override
    public NativeToolChainInternal getToolChain() {
        return toolChain;
    }

    @Override
    public Property<CppCompile> getCompileTask() {
        return compileTaskProperty;
    }

    public PlatformToolProvider getPlatformToolProvider() {
        return platformToolProvider;
    }

    public NativeVariantIdentity getIdentity() {
        return identity;
    }

    private static class UnzipTransform extends ArtifactTransform {
        @Inject
        UnzipTransform() { }

        @Override
        public List<File> transform(File zippedFile) {
            if (zippedFile.isDirectory()) {
                return Collections.singletonList(zippedFile);
            } else {
                String unzippedDirName = removeExtension(zippedFile.getName());
                File unzipDir = new File(getOutputDirectory(), unzippedDirName);
                try {
                    unzipTo(zippedFile, unzipDir);
                } catch (IOException e) {
                    throw UncheckedException.throwAsUncheckedException(e);
                }
                return Collections.singletonList(unzipDir);
            }
        }

        private void unzipTo(File headersZip, File unzipDir) throws IOException {
            ZipInputStream inputStream = new ZipInputStream(new BufferedInputStream(new FileInputStream(headersZip)));
            try {
                ZipEntry entry = null;
                while ((entry = inputStream.getNextEntry()) != null) {
                    if (entry.isDirectory()) {
                        continue;
                    }
                    File outFile = new File(unzipDir, entry.getName());
                    Files.createParentDirs(outFile);
                    FileOutputStream outputStream = new FileOutputStream(outFile);
                    try {
                        IOUtils.copyLarge(inputStream, outputStream);
                    } finally {
                        outputStream.close();
                    }
                }
            } finally {
                inputStream.close();
            }
        }
    }
}
