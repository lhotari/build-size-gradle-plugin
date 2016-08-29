/*
 * Copyright 2016 the original author or authors.
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

package io.github.lhotari.buildsize

import com.fasterxml.jackson.core.JsonEncoding
import com.fasterxml.jackson.core.JsonFactory
import com.fasterxml.jackson.core.JsonGenerator
import com.fasterxml.jackson.databind.ObjectMapper
import groovy.transform.Canonical
import groovy.transform.CompileStatic
import groovy.transform.stc.ClosureParams
import groovy.transform.stc.SimpleType
import org.gradle.api.Action
import org.gradle.api.DefaultTask
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.*
import org.gradle.api.artifacts.cache.ArtifactResolutionControl
import org.gradle.api.artifacts.cache.DependencyResolutionControl
import org.gradle.api.artifacts.cache.ModuleResolutionControl
import org.gradle.api.artifacts.component.ComponentIdentifier
import org.gradle.api.artifacts.component.LibraryBinaryIdentifier
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.component.ProjectComponentIdentifier
import org.gradle.api.artifacts.result.DependencyResult
import org.gradle.api.artifacts.result.ResolvedComponentResult
import org.gradle.api.artifacts.result.ResolvedDependencyResult
import org.gradle.api.artifacts.result.UnresolvedDependencyResult
import org.gradle.api.internal.artifacts.ComponentSelectionRulesInternal
import org.gradle.api.internal.artifacts.configurations.ConfigurationInternal
import org.gradle.api.internal.artifacts.configurations.ResolutionStrategyInternal
import org.gradle.api.internal.artifacts.ivyservice.resolutionstrategy.DefaultCachePolicy
import org.gradle.api.internal.artifacts.ivyservice.resolutionstrategy.DefaultResolutionStrategy
import org.gradle.api.internal.tasks.options.Option
import org.gradle.api.plugins.JavaPluginConvention
import org.gradle.api.specs.Specs
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.SourceSet
import org.gradle.api.tasks.TaskAction
import org.gradle.util.Path

import java.util.concurrent.TimeUnit
import java.util.regex.Matcher
import java.util.regex.Pattern

@CompileStatic
class BuildSizePlugin implements Plugin<Project> {
    @Override
    void apply(Project project) {
        project.tasks.create('buildSize', BuildSizeTask)
    }
}

@CompileStatic
class BuildSizeTask extends DefaultTask {
    @OutputFile
    File destination = new File(project.buildDir, "buildsizeinfo.json")
    @Input
    Collection<String> unmaskedSourceSetNames = ['main', 'test'] as Set
    @Input
    Collection<String> unmaskedConfigurationNames = ['compile', 'testCompile', 'compileOnly', 'testCompileOnly', 'runtime', 'testRuntime',
                                                     'default', 'archives',
                                                     'agent', 'testAgent', 'jacocoAgent',
                                                     'classpath', 'compileClasspath', 'testCompileClasspath', 'testRuntimeClasspath',
                                                     'protobuf', 'testProtobuf',
                                                     'checkstyle', 'codenarc'] as Set
    @Input
    Collection<String> locCountExtensions = ['java', 'groovy', 'scala', 'kt', 'properties', 'xml', 'xsd', 'xsl', 'html', 'js', 'css', 'scss', 'fxml', 'json'] as Set
    @Input
    boolean maskResults = true
    @Input
    boolean includeDependencyGraphs = true
    @Input
    int maskingSalt = project.rootDir.absolutePath.toString().hashCode()

    LocCounter defaultLocCounter = DefaultLocCounter.INSTANCE
    Map<String, LocCounter> overriddenLocCounters = Map.cast(['xml': XmlLocCounter.INSTANCE, 'html': XmlLocCounter.INSTANCE, 'fxml': XmlLocCounter.INSTANCE, 'xsd': XmlLocCounter.INSTANCE, 'xsl': XmlLocCounter.INSTANCE])

    BuildSizeTask() {
        // execute this task always
        getOutputs().upToDateWhen(Specs.SATISFIES_NONE)
    }

    @TaskAction
    void createReport() {
        ReportingSession session = new ReportingSession(this, createJsonGenerator(destination))
        session.run()
    }

    @Option(option = "no-mask-results", description = "Don't mask results")
    public void setNoMaskResults(boolean flagPassed) {
        if (flagPassed) {
            this.maskResults = false
        }
    }

    @Option(option = "masking-salt", description = "Apply salt (int value) to masking so that hashes aren't detectable")
    public void setMaskingSaltOption(String maskingSaltString) {
        if (maskingSaltString != null) {
            this.maskingSalt = Integer.parseInt(maskingSaltString)
        }
    }

    @Option(option = "no-dependency-graphs", description = "Don't include dependency graph metrics")
    public void setNoDependencyGraphOption(boolean flagPassed) {
        if (flagPassed) {
            this.includeDependencyGraphs = false
        }
    }

    private static JsonGenerator createJsonGenerator(File file) {
        JsonFactory jsonFactory = new JsonFactory()
        ObjectMapper objectMapper = new ObjectMapper(jsonFactory)
        JsonGenerator jsonGenerator = jsonFactory.createGenerator(file, JsonEncoding.UTF8)
        jsonGenerator.useDefaultPrettyPrinter()
        jsonGenerator.codec = objectMapper
        jsonGenerator
    }
}

@CompileStatic
class ReportingSession {
    private static final Pattern SOURCE_CODE_TOKEN_SEPARATORS = ~/[\s;]+/
    private static
    final Pattern FILE_SEPARATOR = Pattern.compile(Pattern.quote(File.separator))
    private final BuildSizeTask task
    private final JsonGenerator jsonGenerator
    private final Map<String, String> projectNames = [:]
    private final Map<String, String> sourceSetNames = [:]
    private final Map<String, String> configurationNames = [:]
    private final Map<String, String> configurationPaths = [:]
    long grandTotalSizeInBytes = 0
    long grandTotalSourceCodeSizeInBytes = 0
    int grandTotalLoc = 0
    int grandTotalFileCount = 0
    int grandTotalSourceFileCount = 0
    int sumOfFilePathLengths = 0
    int sumOfFilePathDepths = 0
    Set<String> grandPackageNames = [] as Set
    ResolvedComponentResult deepestRoot
    int deepestRootDepth
    Configuration deepestRootConfiguration
    ResolvedComponentResult largestRoot
    Set<ComponentIdentifier> deepestPath
    Map<ComponentIdentifier, GraphDepthAndSize> largestTreeResults
    int largestRootSize
    Configuration largestRootConfiguration
    int configurationCount = 0
    int sumOfRootSizes = 0
    int sumOfDepths = 0

    ReportingSession(BuildSizeTask task, JsonGenerator jsonGenerator) {
        this.task = task
        this.jsonGenerator = jsonGenerator
    }

    public void run() {
        task.logger.with {
            lifecycle "Writing build-size report JSON to ${task.destination}"
            if (task.maskResults) {
                lifecycle "The resulting names are masked with hashes and hashing salt ${task.maskingSalt}"
                lifecycle "Use --masking-salt=${task.maskingSalt} to produce similar masking of names. The default salt is based on the root directory's absolute path name."
            } else {
                lifecycle "The resulting names aren't masked."
            }
        }

        jsonGenerator.writeStartObject()
        try {
            jsonGenerator.writeFieldName('projects')
            jsonGenerator.writeStartArray()
            task.project.allprojects { Project subproject ->
                task.logger.lifecycle("Handling ${subproject.path}, masked as ${maskProjectName(subproject)}")
                jsonGenerator.writeStartObject()
                jsonGenerator.writeStringField('name', maskProjectName(subproject))

                jsonGenerator.writeArrayFieldStart('sourceSets')
                writeProjectSourceSets(subproject)
                jsonGenerator.writeEndArray()

                jsonGenerator.writeArrayFieldStart('configurations')
                writeProjectConfigurations(subproject)
                jsonGenerator.writeEndArray()

                jsonGenerator.writeEndObject()
            }
            jsonGenerator.writeEndArray()

            if (task.includeDependencyGraphs) {
                writeDeepestAndLargestDependencyGraphs()
            }

            jsonGenerator.writeNumberField("grandTotalSizeInBytes", grandTotalSizeInBytes)
            jsonGenerator.writeNumberField("grandTotalSourceCodeSizeInBytes", grandTotalSourceCodeSizeInBytes)
            jsonGenerator.writeNumberField("grandTotalLoc", grandTotalLoc)
            jsonGenerator.writeNumberField("grandTotalFileCount", grandTotalFileCount)
            jsonGenerator.writeNumberField("grandTotalSourceFileCount", grandTotalSourceFileCount)
            jsonGenerator.writeNumberField("averageFilePathLength", (sumOfFilePathLengths / grandTotalFileCount) as Integer)
            jsonGenerator.writeNumberField("averageFilePathDepth", (sumOfFilePathDepths / grandTotalFileCount) as Integer)
            jsonGenerator.writeNumberField("rootDirFilePathLength", task.project.rootDir.absolutePath.length())
            jsonGenerator.writeNumberField("rootDirFilePathDepth", pathDepth(task.project.rootDir))

            jsonGenerator.writeEndObject()
        } finally {
            jsonGenerator.close()
        }
    }

    private void writeDeepestAndLargestDependencyGraphs() {
        def processedIds = [] as Set
        jsonGenerator.writeFieldName('largest_dependency_graph')
        jsonGenerator.writeStartObject()
        jsonGenerator.writeStringField('project', resolvedMaskedProjectForConfiguration(largestRootConfiguration))
        jsonGenerator.writeStringField('configuration', maskConfigurationName(largestRootConfiguration))
        jsonGenerator.writeNumberField('largest_size', largestRootSize)
        jsonGenerator.writeNumberField('average_size', (sumOfRootSizes / configurationCount) as Integer)
        jsonGenerator.writeFieldName('graph')
        renderNode(largestRoot, processedIds, largestTreeResults)
        jsonGenerator.writeEndObject()

        jsonGenerator.writeFieldName('deepest_dependency_graph')
        jsonGenerator.writeStartObject()
        jsonGenerator.writeStringField('project', resolvedMaskedProjectForConfiguration(deepestRootConfiguration))
        jsonGenerator.writeStringField('configuration', maskConfigurationName(deepestRootConfiguration))
        jsonGenerator.writeNumberField('deepest_depth', deepestRootDepth)
        jsonGenerator.writeNumberField('average_depth', (sumOfDepths / configurationCount) as Integer)
        jsonGenerator.writeArrayFieldStart('deepest_path')
        for (ComponentIdentifier componentIdentifier : deepestPath) {
            writeComponentIdentifier(componentIdentifier)
        }
        jsonGenerator.writeEndArray()
        if (largestRoot.is(deepestRoot)) {
            jsonGenerator.writeBooleanField('largest_is_deepest', true)
        } else {
            jsonGenerator.writeFieldName('graph')
            renderNode(deepestRoot, processedIds)
        }
        jsonGenerator.writeEndObject()
    }

    void writeComponentIdentifier(ComponentIdentifier componentId) {
        jsonGenerator.writeStartObject()
        if (componentId instanceof ProjectComponentIdentifier) {
            jsonGenerator.writeStringField('type', 'project')
            jsonGenerator.writeStringField('project', maskProjectNameByPath(componentId.projectPath))
        } else if (componentId instanceof ModuleComponentIdentifier) {
            jsonGenerator.writeStringField('type', 'module')
            jsonGenerator.writeStringField('group', maskGroupName(componentId.group))
            jsonGenerator.writeStringField('name', maskDependencyName(componentId.module))
            jsonGenerator.writeStringField('version', maskDependencyVersion(componentId.version))
        } else if (componentId instanceof LibraryBinaryIdentifier) {
            jsonGenerator.writeStringField('type', 'library')
            jsonGenerator.writeStringField('project', maskProjectNameByPath(componentId.projectPath))
            jsonGenerator.writeStringField('library', maskGeneric('library', componentId.libraryName))
            jsonGenerator.writeStringField('variant', maskGeneric('variant', componentId.variant))
        } else {
            jsonGenerator.writeStringField('type', 'other')
        }
        jsonGenerator.writeEndObject()
    }

    void writeProjectConfigurations(Project project) {
        for (Configuration configuration : project.configurations) {
            //ResolutionResult result = configuration.getIncoming().getResolutionResult()
            //configuration.getResolvedConfiguration()

            def configurationInfo = [:]
            configurationInfo.name = maskConfigurationName(configuration)
            configurationInfo.project = resolvedMaskedProjectForConfiguration(configuration)
            configurationInfo.extendsFrom = configuration.getExtendsFrom().collect {
                def result = [configuration: maskConfigurationName(it)]
                // add name of project if it's in another project
                def projectForConfiguration = resolvedMaskedProjectForConfiguration(it)
                if (projectForConfiguration != configurationInfo.project) {
                    result.project = projectForConfiguration
                }
                result
            }
            configurationInfo.visible = configuration.visible
            configurationInfo.transitive = configuration.transitive
            configurationInfo.excludeRulesCount = configuration.getExcludeRules().size()
            configurationInfo.excludeRules = createExcludeRulesInfo(configuration.excludeRules)
            configurationInfo.artifactsCount = configuration.artifacts.size()

            configurationInfo.resolutionStrategy = createResolutionStrategyInfo(configuration.resolutionStrategy)

            configurationInfo.fileCount = configuration.getFiles().size()
            configurationInfo.directoryCount = configuration.getFiles().count { File file -> file.directory } ?: 0
            configurationInfo.filesTotalSize = configuration.getFiles().sum { File file -> file.file ? file.length() : 0 } ?: 0
            configurationInfo.lengthAsClasspath = configuration.getAsPath().length()

            configurationInfo.dependencies = createDependenciesInfo(configuration.dependencies)
            if (task.includeDependencyGraphs) {
                task.logger.lifecycle "Traversing dependency graph of configuration ${project.absoluteProjectPath(configuration.name)}"
                ResolvedComponentResult root = configuration.incoming.resolutionResult.root
                Map<ComponentIdentifier, GraphDepthAndSize> allResults = [:]
                GraphDepthAndSize result = calculateDependencyGraphDepthAndSize(root, 1, [] as Set, allResults)
                configurationCount++
                sumOfDepths += result.depth
                if (result.depth > deepestRootDepth) {
                    deepestRoot = root
                    deepestRootDepth = result.depth
                    deepestPath = result.deepestPath
                    deepestRootConfiguration = configuration
                }
                def rootSize = allResults.size()
                sumOfRootSizes += rootSize
                if (rootSize > largestRootSize) {
                    largestRoot = root
                    largestRootSize = rootSize
                    largestRootConfiguration = configuration
                    largestTreeResults = allResults
                }
            }
            jsonGenerator.writeObject(configurationInfo)
        }
    }

    GraphDepthAndSize calculateDependencyGraphDepthAndSize(ResolvedComponentResult resolvedComponentResult, int initialDepth, Set<ComponentIdentifier> parentIds, Map<ComponentIdentifier, GraphDepthAndSize> calculated) {
        GraphDepthAndSize result = calculated.get(resolvedComponentResult.id)
        if (result != null) {
            return result
        }
        Set<ComponentIdentifier> childParentIds = new LinkedHashSet<>(parentIds)
        childParentIds.add(resolvedComponentResult.id)
        int maxDepth = initialDepth
        Set<ComponentIdentifier> deepestPath = childParentIds
        for (Object dependencyResultObject : Set.cast(resolvedComponentResult.dependencies)) {
            DependencyResult dependencyResult = DependencyResult.cast(dependencyResultObject)
            if (dependencyResult instanceof ResolvedDependencyResult) {
                ResolvedDependencyResult resolvedDependencyResult = ResolvedDependencyResult.cast(dependencyResult)
                if (!childParentIds.contains(resolvedDependencyResult.selected.id)) {
                    GraphDepthAndSize subtreeResult = calculateDependencyGraphDepthAndSize(resolvedDependencyResult.selected, initialDepth + 1, childParentIds, calculated)
                    if (subtreeResult.depth > maxDepth) {
                        maxDepth = subtreeResult.depth
                        deepestPath = subtreeResult.deepestPath
                    }
                } else {
                    task.logger.debug "Circular result ${resolvedDependencyResult.selected.id} referenced from ${(childParentIds as List).reverse().join('->')}"
                }
            }
        }
        result = new GraphDepthAndSize(resolvedComponentResult, maxDepth, deepestPath)
        calculated.put(resolvedComponentResult.id, result)
        result
    }

    @Canonical
    static class GraphDepthAndSize {
        ResolvedComponentResult root
        int depth
        Set<ComponentIdentifier> deepestPath
    }

    void renderNode(ResolvedComponentResult resolvedComponentResult, Set<ComponentIdentifier> processedIds, Map<ComponentIdentifier, GraphDepthAndSize> treeResults = null) {
        processedIds.add(resolvedComponentResult.id)

        jsonGenerator.writeStartObject()

        jsonGenerator.writeStringField('type', 'resolved')
        writeModuleVersionInfo(resolvedComponentResult.moduleVersion)
        jsonGenerator.writeStringField('selectionReason', resolvedComponentResult.selectionReason.description)

        jsonGenerator.writeArrayFieldStart('dependencies')
        for (Object dependencyResultObject : Set.cast(resolvedComponentResult.dependencies)) {
            // workaround STC bug
            DependencyResult dependencyResult = DependencyResult.cast(dependencyResultObject)
            if (dependencyResult instanceof ResolvedDependencyResult) {
                ResolvedDependencyResult resolvedDependencyResult = ResolvedDependencyResult.cast(dependencyResult)
                if (!processedIds.contains(resolvedDependencyResult.selected.id)) {
                    renderNode(resolvedDependencyResult.selected, processedIds, treeResults)
                } else {
                    jsonGenerator.writeStartObject()
                    jsonGenerator.writeStringField('type', 'reference')
                    writeModuleVersionInfo(resolvedDependencyResult.selected.moduleVersion)
                    jsonGenerator.writeEndObject()
                }
            } else if (dependencyResult instanceof UnresolvedDependencyResult) {
                UnresolvedDependencyResult unresolvedDependencyResult = UnresolvedDependencyResult.cast(dependencyResult)
                jsonGenerator.writeStartObject()
                jsonGenerator.writeStringField('type', 'unresolved')
                jsonGenerator.writeStringField('attemptedReason', unresolvedDependencyResult.attemptedReason.description)
                jsonGenerator.writeEndObject()
            } else {
                jsonGenerator.writeStartObject()
                jsonGenerator.writeStringField('type', 'unknown')
                jsonGenerator.writeEndObject()
            }
        }
        jsonGenerator.writeEndArray()
        jsonGenerator.writeEndObject()
    }

    void writeModuleVersionInfo(ModuleVersionIdentifier moduleVersion) {
        jsonGenerator.writeStringField('group', maskGroupName(moduleVersion.group))
        jsonGenerator.writeStringField('name', maskDependencyName(moduleVersion.name))
        jsonGenerator.writeStringField('version', maskDependencyVersion(moduleVersion.version))
    }

    List<Map<String, Object>> createDependenciesInfo(Iterable<? extends Dependency> dependencies) {
        dependencies.collect { Dependency it -> createDependencyInfo(it) }
    }

    def createResolutionStrategyInfo(ResolutionStrategy resolutionStrategy) {
        def resolutionStrategyInfo = [:]
        resolutionStrategyInfo.type = resolutionStrategy instanceof DefaultResolutionStrategy ? 'default' : 'custom'
        resolutionStrategyInfo.forcedModulesCount = resolutionStrategy.forcedModules.size()
        def forcedModulesList = []
        resolutionStrategyInfo.forcedModules = forcedModulesList
        for (ModuleVersionSelector moduleVersionSelector : resolutionStrategy.forcedModules) {
            def moduleInfo = [:]
            forcedModulesList << moduleInfo
            moduleInfo.group = maskGroupName(moduleVersionSelector.group)
            moduleInfo.name = maskDependencyName(moduleVersionSelector.name)
            moduleInfo.version = maskDependencyVersion(moduleVersionSelector.version)
        }
        if (resolutionStrategy.componentSelection instanceof ComponentSelectionRulesInternal) {
            resolutionStrategyInfo.componentSelectionRulesCount = ComponentSelectionRulesInternal.cast(resolutionStrategy.componentSelection).rules.size()
        }
        try {
            def rules = (Collection) resolutionStrategy.dependencySubstitution.getMetaClass().getAttribute(resolutionStrategy.dependencySubstitution, "substitutionRules")
            resolutionStrategyInfo.dependencySubstitutionsCount = rules.size()
        } catch (e) {
        }

        ResolutionStrategyInternal resolutionStrategyInternal = ResolutionStrategyInternal.cast(resolutionStrategy)
        if (resolutionStrategyInternal.cachePolicy instanceof DefaultCachePolicy) {
            DefaultCachePolicy cachePolicy = DefaultCachePolicy.cast(resolutionStrategyInternal.cachePolicy)
            def cachePolicyInfo = [:]
            cachePolicyInfo.cacheDynamicVersionsFor = resolveCacheDynamicVersionsFor(cachePolicy)
            cachePolicyInfo.cacheChangingModulesFor = resolveCacheChangingModulesFor(cachePolicy)
            cachePolicyInfo.cacheMissingArtifactsFor = resolveCacheMissingArtifactsFor(cachePolicy)
            resolutionStrategyInfo.cachePolicy = cachePolicyInfo
        }

        resolutionStrategyInfo
    }

    int resolveCacheDynamicVersionsFor(DefaultCachePolicy cachePolicy) {
        List<Action<? super DependencyResolutionControl>> dependencyCacheRules = List.cast(cachePolicy.getMetaClass().getAttribute(cachePolicy, 'dependencyCacheRules'))
        Integer cacheSeconds = null
        for (Action<? super DependencyResolutionControl> rule : dependencyCacheRules) {
            rule.execute(new DependencyResolutionControl() {
                @Override
                ModuleIdentifier getRequest() {
                    return null
                }

                @Override
                Set<ModuleVersionIdentifier> getCachedResult() {
                    return null
                }

                @Override
                void cacheFor(int value, TimeUnit units) {
                    cacheSeconds = TimeUnit.SECONDS.convert((long) value, units).toInteger()
                }

                @Override
                void useCachedResult() {

                }

                @Override
                void refresh() {
                    true
                }
            })
            if (cacheSeconds != null) {
                break
            }
        }
        cacheSeconds ?: 0
    }

    int resolveCacheChangingModulesFor(DefaultCachePolicy cachePolicy) {
        List<Action<? super ModuleResolutionControl>> moduleCacheRules = List.cast(cachePolicy.getMetaClass().getAttribute(cachePolicy, 'moduleCacheRules'))
        Integer cacheSeconds = null
        for (Action<? super ModuleResolutionControl> rule : moduleCacheRules) {
            rule.execute(new ModuleResolutionControl() {
                @Override
                boolean isChanging() {
                    return true
                }

                @Override
                ModuleVersionIdentifier getRequest() {
                    return null
                }

                @Override
                ResolvedModuleVersion getCachedResult() {
                    return null
                }

                @Override
                void cacheFor(int value, TimeUnit units) {
                    cacheSeconds = TimeUnit.SECONDS.convert((long) value, units).toInteger()
                }

                @Override
                void useCachedResult() {

                }

                @Override
                void refresh() {

                }
            })
            if (cacheSeconds != null) {
                break
            }
        }
        cacheSeconds ?: 0
    }

    int resolveCacheMissingArtifactsFor(DefaultCachePolicy cachePolicy) {
        List<Action<? super ArtifactResolutionControl>> artifactCacheRules = List.cast(cachePolicy.getMetaClass().getAttribute(cachePolicy, 'artifactCacheRules'))
        Integer cacheSeconds = null
        for (Action<? super ArtifactResolutionControl> rule : artifactCacheRules) {
            rule.execute(new ArtifactResolutionControl() {
                @Override
                boolean belongsToChangingModule() {
                    return false
                }

                @Override
                ArtifactIdentifier getRequest() {
                    return null
                }

                @Override
                File getCachedResult() {
                    return null
                }

                @Override
                void cacheFor(int value, TimeUnit units) {
                    cacheSeconds = TimeUnit.SECONDS.convert((long) value, units).toInteger()
                }

                @Override
                void useCachedResult() {

                }

                @Override
                void refresh() {

                }
            })
            if (cacheSeconds != null) {
                break
            }
        }
        cacheSeconds ?: 0
    }

    Map<String, Object> createDependencyInfo(Dependency dependency) {
        def dependencyInfo = [:]
        if (dependency instanceof SelfResolvingDependency) {
            if (dependency instanceof ProjectDependency) {
                dependencyInfo.type = "project"
                def projectDependency = ProjectDependency.cast(dependency)
                dependencyInfo.project = maskProjectName(projectDependency.dependencyProject)
                if (projectDependency.projectConfiguration && projectDependency.projectConfiguration.name != 'default') {
                    dependencyInfo.configuration = maskConfigurationName(projectDependency.projectConfiguration)
                }
            } else if (dependency instanceof FileCollectionDependency) {
                Set<File> files = FileCollectionDependency.cast(dependency).resolve()
                dependencyInfo.type = "fileCollection"
                dependencyInfo.fileCount = files.count {
                    it.file
                }
                dependencyInfo.directoryCount = files.count {
                    it.directory
                }
            } else {
                dependencyInfo.type = "selfResolving"
            }
        } else {
            if (dependency instanceof ModuleDependency) {
                dependencyInfo.group = maskGroupName(dependency.group)
                dependencyInfo.name = maskDependencyName(dependency.name)
                dependencyInfo.version = maskDependencyVersion(dependency.version)
                dependencyInfo.type = 'module'
                dependencyInfo.transitive = dependency.transitive
                if (dependency.configuration && dependency.configuration != 'default') {
                    dependencyInfo.configuration = maskConfigurationName(dependency.configuration)
                }
                dependencyInfo.excludesRulesCount = dependency.excludeRules.size()
                dependencyInfo.excludeRules = createExcludeRulesInfo(dependency.excludeRules)
            }
            if (dependency instanceof ExternalDependency) {
                dependencyInfo.type = 'external'
                dependencyInfo.force = dependency.force
            }
            if (dependency instanceof ExternalModuleDependency) {
                dependencyInfo.type = 'external_module'
                dependencyInfo.changing = dependency.changing
            }
            if (dependency instanceof ClientModule) {
                dependencyInfo.type = 'client_module'
                dependencyInfo.module_id = maskGeneric("client_module", dependency.id)
                dependencyInfo.dependencies = createDependenciesInfo(dependency.dependencies)
            }
        }
        dependencyInfo
    }

    def createExcludeRulesInfo(Iterable<ExcludeRule> excludeRules) {
        def excludeRulesInfo = []
        for (ExcludeRule excludeRule : excludeRules) {
            excludeRulesInfo << createExcludeRuleInfo(excludeRule)
        }
        excludeRulesInfo
    }

    def createExcludeRuleInfo(ExcludeRule excludeRule) {
        def excludeRuleInfo = [:]
        excludeRuleInfo.group = maskGroupName(excludeRule.group)
        excludeRuleInfo.module = maskDependencyName(excludeRule.module)
        excludeRuleInfo
    }

    String maskGeneric(String prefix, String name) {
        if (task.maskResults) {
            name ? "${prefix}_${hashId(name)}".toString() : ''
        } else {
            name
        }
    }

    String maskGroupName(String group) {
        maskGeneric("group", group)
    }

    String maskDependencyName(String name) {
        maskGeneric("name", name)
    }

    String maskDependencyVersion(String version) {
        maskGeneric("version", version)
    }

    private int pathDepth(File file) {
        FILE_SEPARATOR.split(file.absolutePath).size()
    }

    String maskProjectName(Project project) {
        maskProjectNameByPath(project.path)
    }

    String maskProjectNameByPath(String path) {
        if (!task.maskResults) {
            return path
        }
        String masked = projectNames.get(path)
        if (!masked) {
            masked = path == ':' ? 'project_root' : "project_${hashId(path)}".toString()
            projectNames.put(path, masked)
        }
        masked
    }

    String maskConfigurationName(Configuration configuration) {
        maskConfigurationName(configuration.name)
    }

    String maskConfigurationName(String name) {
        if (!name || !task.maskResults) {
            return name
        }
        String masked = configurationNames.get(name)
        if (!masked) {
            if (name in task.unmaskedConfigurationNames) {
                masked = name
            } else {
                masked = "${name.toLowerCase().contains('test') ? 'testC' : 'c'}onfiguration_${hashId(name)}".toString()
            }
            configurationNames.put(name, masked)
        }
        masked
    }

    String resolvedMaskedProjectForConfiguration(Configuration configuration) {
        ConfigurationInternal configurationInternal = (ConfigurationInternal) configuration
        // the project path can be found from ConfigurationInternal.getPath() String by removing the last segment
        Path path = configurationInternal.path ? new Path(configurationInternal.path) : null
        def projectPath = path?.parent?.toString()
        projectPath ? maskProjectNameByPath(projectPath) : null
    }

    String maskSourceSetName(String name) {
        if (!task.maskResults) {
            return name
        }
        String masked = sourceSetNames.get(name)
        if (!masked) {
            if (name in task.unmaskedSourceSetNames) {
                masked = name
            } else if (name.toLowerCase().contains('test')) {
                masked = "otherTests_${hashId(name)}".toString()
            } else {
                masked = "otherSource_${hashId(name)}".toString()
            }
            sourceSetNames.put(name, masked)
        }
        masked
    }

    String hashId(String source) {
        int hash = source.hashCode()
        int salt = task.maskingSalt
        if (salt != 0) {
            hash = 31 * hash + salt
        }
        Integer.toHexString(hash)
    }

    String fileExtension(String filename) {
        int lastDot = filename.lastIndexOf('.')
        if (lastDot > -1) {
            filename.substring(lastDot + 1)
        } else {
            ''
        }
    }

    void writeProjectSourceSets(Project subproject) {
        getJavaPluginConvention(subproject).sourceSets.each { SourceSet sourceSet ->
            long totalSizeInBytes = 0
            long sourceCodeSizeInBytes = 0
            int totalLoc = 0
            int fileCount = 0
            int sourceFileCount = 0
            Map<String, Integer> locCounts = [:]
            Map<String, Integer> sourceFileCounts = [:]
            Map<String, Set<String>> packagesPerExtension = [:]
            def packageCallback = { String extension, String packageLine ->
                def parts = SOURCE_CODE_TOKEN_SEPARATORS.split(packageLine)
                if (parts.size() > 1) {
                    String packageName = parts[1]
                    def allPackages = packagesPerExtension.get(extension)
                    if (allPackages == null) {
                        allPackages = [] as Set
                        packagesPerExtension.put(extension, allPackages)
                    }
                    allPackages << packageName
                }
            }
            sourceSet.allSource.srcDirs.each { File dir ->
                if (!dir.exists()) {
                    return
                }
                dir.eachFileRecurse { File file ->
                    if (file.file) {
                        sumOfFilePathLengths += file.absolutePath.length()
                        sumOfFilePathDepths += pathDepth(file)
                        fileCount++
                        def fileSize = file.length()
                        totalSizeInBytes += fileSize
                        String extension = fileExtension(file.name)
                        if (extension in task.locCountExtensions) {
                            sourceFileCount++
                            sourceCodeSizeInBytes += fileSize

                            LocCounter counterToUse = task.overriddenLocCounters.get(extension) ?: task.defaultLocCounter
                            Integer currentLoc = locCounts.get(extension) ?: 0
                            def fileLoc = counterToUse.count(file, packageCallback.curry(extension))
                            locCounts.put(extension, currentLoc + fileLoc)
                            totalLoc += fileLoc

                            Integer currentSourceFileCount = sourceFileCounts.get(extension) ?: 0
                            sourceFileCounts.put(extension, currentSourceFileCount + 1)
                        }
                    }
                }
            }

            grandTotalLoc += totalLoc
            grandTotalSizeInBytes += totalSizeInBytes
            grandTotalSourceCodeSizeInBytes += sourceCodeSizeInBytes
            def allPackages = mergeAllPackages(packagesPerExtension)
            grandPackageNames.addAll(allPackages)
            grandTotalFileCount += fileCount
            grandTotalSourceFileCount += sourceFileCount

            def sourceSetInfo = [:]

            sourceSetInfo.name = maskSourceSetName(sourceSet.name)
            sourceSetInfo.fileCount = fileCount
            sourceSetInfo.totalSize = totalSizeInBytes
            sourceSetInfo.sourceCodeSize = sourceCodeSizeInBytes
            sourceSetInfo.totalLoc = totalLoc
            sourceSetInfo.loc = locCounts
            sourceSetInfo.sourceFileCounts = sourceFileCounts
            sourceSetInfo.packagesPerExtension = packagesPerExtension.collectEntries { k, v -> [k, v.size()] }
            sourceSetInfo.totalPackages = allPackages.size()

            sourceSetInfo.compileClasspathConfigurationName = maskConfigurationName(sourceSet.compileClasspathConfigurationName)
            sourceSetInfo.compileConfigurationName = maskConfigurationName(sourceSet.compileConfigurationName)
            sourceSetInfo.compileOnlyConfigurationName = maskConfigurationName(sourceSet.compileOnlyConfigurationName)
            sourceSetInfo.runtimeConfigurationName = maskConfigurationName(sourceSet.runtimeConfigurationName)


            jsonGenerator.writeObject(sourceSetInfo)
        }
    }

    Collection<String> mergeAllPackages(Map<String, Set<String>> packagesPerExtension) {
        packagesPerExtension.values().collectMany([] as Set) { Set<String> packageNames ->
            packageNames
        }
    }

    JavaPluginConvention getJavaPluginConvention(Project p) {
        p.convention.getPlugin(JavaPluginConvention)
    }
}

/*
 * LOC counting classes copied originally from https://github.com/aalmiray/stats-gradle-plugin
 * This is a stripped down version with just C-style and XML-style comment detection
 */

@CompileStatic
interface LocCounter {
    Pattern EMPTY = ~/^\s*$/
    Pattern SLASH_SLASH = ~/^\s*\/\/.*/
    Pattern SLASH_STAR_STAR_SLASH = ~/^(.*)\/\*(.*)\*\/(.*)$/

    int count(File file,
              @ClosureParams(value = SimpleType, options = ['String']) Closure<?> packageCallback)
}

@CompileStatic
class DefaultLocCounter implements LocCounter {
    static final DefaultLocCounter INSTANCE = new DefaultLocCounter()

    @Override
    int count(File file,
              @ClosureParams(value = SimpleType, options = ['String']) Closure<?> packageCallback) {
        def loc = 0
        def comment = 0
        file.eachLine { line ->
            String trimmed = line.trim()
            if (!trimmed.length() || line ==~ EMPTY) {
                return
            } else if (line ==~ SLASH_SLASH) {
                return
            }

            if (trimmed.startsWith('package ')) {
                packageCallback(line)
            }

            Matcher m = line =~ SLASH_STAR_STAR_SLASH
            if (m.find() && m.group(1) ==~ EMPTY && m.group(3) ==~ EMPTY) {
                return
            }
            int open = line.indexOf('/*')
            int close = line.indexOf('*/')
            if (open != -1 && (close - open) <= 1) {
                comment++
            } else {
                if (close != -1 && comment) {
                    comment--
                    if (!comment) {
                        return
                    }
                }
            }

            if (!comment) {
                loc++
            }
        }

        loc
    }
}

@CompileStatic
class XmlLocCounter implements LocCounter {
    static final XmlLocCounter INSTANCE = new XmlLocCounter()
    static final Pattern OPEN_CARET_CLOSE_CARET = ~/^(.*)<!--(.*)-->(.*)$/

    @Override
    int count(File file,
              @ClosureParams(value = SimpleType, options = ['String']) Closure<?> packageCallback) {
        def loc = 0
        def comment = 0
        file.eachLine { line ->
            if (!line.trim().length() || line ==~ EMPTY) {
                return
            }

            def m = line =~ OPEN_CARET_CLOSE_CARET
            if (m.find() && m.group(1) ==~ EMPTY && m.group(3) ==~ EMPTY) {
                return
            }
            int open = line.indexOf('<!--')
            int close = line.indexOf('-->')

            if (open != -1 && (close - open) <= 1) {
                comment++
            } else if (close != -1 && comment) {
                comment--
                if (!comment) {
                    return
                }
            }

            if (!comment) {
                loc++
            }
        }

        loc
    }
}

