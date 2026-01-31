# Repository Layout
This repository is meant to be built as a [Gradle multi-project build](https://docs.gradle.org/current/userguide/intro_multi_project_builds.html) with approximately the following structure.
For simplicity, not every subproject will be detailed in this document.

```
amazon-q-jetbrains
├── buildSrc                                                :buildSrc
│   ├── ⋮
│   └── build.gradle.kts 
│
├── detekt-rules                                            :detekt-rules
│   ├── ⋮
│   └── build.gradle.kts 
│
├── ui-tests-starter                                        :ui-tests-starter
│   ├── ⋮
│   └── build.gradle.kts 
│
├── sandbox-all                                             :sandbox-all
│   ├── ⋮
│   └── build.gradle.kts 
│
├── plugins
│   ├── core-q                                              :plugin-core-q
│   │   ├── core-q                                          :plugin-core-q:core-q
│   │   │   ├── ⋮
│   │   │   └── build.gradle.kts
│   │   ├── sdk-codegen                                     :plugin-core-q:sdk-codegen
│   │   │   ├── ⋮
│   │   │   └── build.gradle.kts 
│   │   ├── resources                                       :plugin-core-q:resources
│   │   │   ├── ⋮
│   │   │   └── build.gradle.kts
│   │   ├── webview                                         :plugin-core-q:webview
│   │   │   ├── ⋮
│   │   │   └── build.gradle.kts
│   │   ├── jetbrains-community                             :plugin-core-q:jetbrains-community
│   │   │   ├── ⋮
│   │   │   └── build.gradle.kts
│   │   ├── jetbrains-ultimate                              :plugin-core-q:jetbrains-ultimate
│   │   │   ├── ⋮
│   │   │   └── build.gradle.kts
│   │   └── build.gradle.kts
│   │
│   └── amazonq                                             :plugin-amazonq
│       ├── shared                                          :plugin-amazonq:shared
│       │   ├── jetbrains-community                         :plugin-amazonq:shared:jetbrains-community
│       │   │   ├── ⋮
│       │   │   └── build.gradle.kts
│       │   ├── jetbrains-ultimate                          :plugin-amazonq:shared:jetbrains-ultimate
│       │   │   ├── ⋮
│       │   │   └── build.gradle.kts
│       │   └── build.gradle.kts
│       ├── codewhisperer                                   :plugin-amazonq:codewhisperer
│       │   ├── jetbrains-community                         :plugin-amazonq:codewhisperer:jetbrains-community
│       │   │   ├── ⋮
│       │   │   └── build.gradle.kts
│       │   ├── jetbrains-ultimate                          :plugin-amazonq:codewhisperer:jetbrains-ultimate
│       │   │   ├── ⋮
│       │   │   └── build.gradle.kts
│       │   └── build.gradle.kts
│       ├── codetransform                                   :plugin-amazonq:codetransform
│       │   ├── jetbrains-community                         :plugin-amazonq:codetransform:jetbrains-community
│       │   │   ├── ⋮
│       │   │   └── build.gradle.kts
│       │   └── build.gradle.kts
│       ├── chat                                            :plugin-amazonq:chat
│       │   ├── jetbrains-community                         :plugin-amazonq:chat:jetbrains-community
│       │   │   ├── ⋮
│       │   │   └── build.gradle.kts
│       │   └── build.gradle.kts
│       ├── mynah-ui                                        :plugin-amazonq:mynah-ui  
│       │   ├── ⋮
│       │   └── build.gradle.kts
│       └── build.gradle.kts 
│
├── build.gradle.kts  
└── settings.gradle.kts  
```

The intent of the above directory structure is to support emitting multiple discrete release artifacts from a single monorepo.
Where practical, [CODEOWNERS](https://docs.github.com/en/repositories/managing-your-repositorys-settings-and-features/customizing-your-repository/about-code-owners) should be accurately defined to facilitate proper assignment of pull requests.
This layout also conveniently allows us to define [GitHub rulesets](https://docs.github.com/en/repositories/configuring-branches-and-merges-in-your-repository/managing-rulesets/about-rulesets) for specific repository subprojects.

## Subprojects
Sub-projects should naturally fall along natural, logical boundaries. For example:
* `:buildSrc`: The standard Gradle convention for [encapsulated build logic](https://docs.gradle.org/current/userguide/organizing_gradle_projects.html#sec:build_sources)
* `:detekt-rules`: [detekt](https://github.com/detekt/detekt) custom rules and configuration for the entire repository 
* `:ui-tests-starter`: UI tests for all release artifacts
* `:sandbox-all`: Sandbox environment for running all plugins together

However, sub-projects in `plugins/` are primarily defined along feature and code ownership boundaries.
Instead of the standard `:plugins:<artifact>:<sub-sub-project>` structure, we reduce nesting by defining these projects as `:plugin-<artifact>:<sub-sub-project>`.

[Sourcesets](https://docs.gradle.org/current/userguide/building_java_projects.html#sec:java_source_sets) defined in each sub-project are utilized in the conventional manner for a Kotlin/JVM project.
Use of Java is discouraged in this project; all new code should be written in Kotlin with the standard [coding conventions](https://kotlinlang.org/docs/coding-conventions.html).

Since we have very little Java remaining and new code is exclusively Kotlin, we do not follow the standard Gradle directory configuration. Instead, we allow Kotlin and Java code to be rooted directly against the subproject root:
```
subproject
├── src
│   ├── file1.kt
│   ├── file2.kt
│   ├── ⋮
│   └── file.java
├── tst
│   ├── file3.kt
│   └── ⋮
├── resources
│   ├── META-INF
│   └── ⋮
├── ⋮
└── build.gradle.kts
```

Sourcesets referencing code in the IntelliJ Platform have additional layout conventions, partially due to restrictions on how [JetBrains' Gradle plugin](https://github.com/JetBrains/gradle-intellij-plugin) configures a given sub-project,
and partially to improve the developer experience by shifting runtime errors to compilation errors.

A (sub-)sub-project is further declared to segment code according to platform-level dependencies. For example, code residing in `jetbrains-community/` only references dependencies in the "community" IDEs (IntelliJ IDEA Community, PyCharm Community, etc.),
while code residing in `jetbrains-ultimate/` only references dependencies in the "ultimate" (paid) IDEs. The "ultimate" platform itself is a superset of "community", and so the "ultimate" sub-project takes a direct dependency on the "community" sub-project to support code-reuse.

An additional concern is supporting functionality across multiple IDE major versions where a dependent platform API changes signatures across major versions. We support this by conditionally including additional source files depending on the `ideProfileName` Gradle property (overridden in `gradle.properties` or at build time).
```
subproject
├── src             // always included
│   └── ⋮
├── src-242         // only included for the '2024.2' IDE major version
│   └── ⋮
├── src-243+        // included for all builds >= 2024.3
│   └── ⋮
├── src-242-252     // only included for 2024.2 to 2025.2 inclusive
│   └── ⋮
├── tst-253+        // included for all tests >= 2025.3
│   └── ⋮
└── ⋮
```
Full documentation on the supported folder patterns can be found on the `findFolders` utility in `:buildSrc`.

For artifacts where major functional logic is developed by multiple independent teams, we optionally support another layer of subprojects to increase code locality during development.

Below is a sample of the layout and sourceset interactions for the given artifacts:
* `plugin-core-q.zip`
* `plugin-amazonq.zip`

```mermaid
flowchart LR
    subgraph plugin-core-q
        resources-core[resources]
        sdk-codegen[sdk-codegen]
        webview-core[webview]
        core-q-core[core-q]
        jetbrains-community-core[jetbrains-community]
        jetbrains-ultimate-core[jetbrains-ultimate]
        
        jetbrains-community-core --depends--> resources-core
        jetbrains-community-core --depends--> sdk-codegen
        jetbrains-community-core --depends--> core-q-core
        jetbrains-community-core --depends--> webview-core
        jetbrains-ultimate-core --depends--> jetbrains-community-core

        subgraph intellij platform sourcesets
            jetbrains-community-core -. input for .-> instrument-core
            jetbrains-ultimate-core -. input for .-> instrument-core
            instrument-core[[InstrumentJarTask]]
        end

        resources-core -. input for .-> build-core
        sdk-codegen -. input for .-> build-core
        webview-core -. input for .-> build-core
        core-q-core -. input for .-> build-core
        instrument-core -. input for .-> build-core
        build-core[[PrepareSandbox + BuildPlugin]]
        
        build-core -- emits --> plugincoreqzip
        plugincoreqzip[plugin-core-q.zip\n* instrumented-jetbrains-community.jar\n* instrumented-jetbrains-ultimate.jar\n* resources.jar\n* sdk-codegen.jar\n* core-q.jar\n* webview.jar]
        style plugincoreqzip text-align:left
    end

    subgraph plugin-amazonq
        direction LR
        mynah-ui

        subgraph shared[shared: intellij platform sourcesets]
            jetbrains-community-shared[jetbrains-community]
            jetbrains-ultimate-shared[jetbrains-ultimate]
            
            jetbrains-ultimate-shared --depends--> jetbrains-community-shared

            jetbrains-community-shared -. input for .-> instrument-shared
            jetbrains-ultimate-shared -. input for .-> instrument-shared
            instrument-shared[[InstrumentJarTask]]
        end

        subgraph codewhisperer[codewhisperer: intellij platform sourcesets]
            jetbrains-community-codewhisperer[jetbrains-community]
            jetbrains-ultimate-codewhisperer[jetbrains-ultimate]

            jetbrains-ultimate-codewhisperer --depends--> jetbrains-community-codewhisperer

            jetbrains-community-codewhisperer -. input for .-> instrument-codewhisperer
            jetbrains-ultimate-codewhisperer -. input for .-> instrument-codewhisperer
            instrument-codewhisperer[[InstrumentJarTask]]
        end

        subgraph codetransform[codetransform: intellij platform sourcesets]
            jetbrains-community-codetransform[jetbrains-community]

            jetbrains-community-codetransform -. input for .-> instrument-codetransform
            instrument-codetransform[[InstrumentJarTask]]
        end

        subgraph chat[chat: intellij platform sourcesets]
            jetbrains-community-chat[jetbrains-community]

            jetbrains-community-chat -. input for .-> instrument-chat
            instrument-chat[[InstrumentJarTask]]
        end

        codewhisperer --depends---> shared
        codetransform --depends---> shared
        chat --depends---> shared

        mynah-ui -. input for .-> build-amazonq
        instrument-shared -. input for .-> build-amazonq
        instrument-codewhisperer -. input for .-> build-amazonq
        instrument-codetransform -. input for .-> build-amazonq
        instrument-chat -. input for .-> build-amazonq
        build-amazonq[[PrepareSandbox + BuildPlugin]]
        
        build-amazonq -- emits --> pluginamazonqzip
        pluginamazonqzip[plugin-amazonq.zip\n* mynah-ui.jar\n* instrumented-shared-jetbrains-community.jar\n* instrumented-shared-jetbrains-ultimate.jar\n* instrumented-codewhisperer-jetbrains-community.jar\n* instrumented-codewhisperer-jetbrains-ultimate.jar\n* instrumented-chat-jetbrains-community.jar\n* instrumented-codetransform-jetbrains-community.jar]
        style pluginamazonqzip text-align:left
    end

    plugin-amazonq -. runtime dependency on API .-> plugincoreqzip
