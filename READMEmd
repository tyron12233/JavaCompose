# Java Compose Compiler Plugin

**PROJECT STATUS: PROTOTYPE / WORK IN PROGRESS**

> **Note**: This project is currently an experimental proof-of-concept. It demonstrates the technical feasibility of bridging the Java language with the Jetpack Compose Runtime. It is **not** production-ready and relies on unstable internal APIs of the JDK (`com.sun.tools.javac`). The implementation details described below represent the current state of development and are subject to significant architectural changes.

## Overview

This project implements a low-level `javac` compiler plugin that targets the Android Jetpack Compose Runtime. It functions by intercepting the Java compilation pipeline immediately after parsing, directly manipulating the Abstract Syntax Tree (AST) to inject the runtime logic required for declarative UI management.

This implementation bridges the imperative structure of Java bytecode and the gap-buffer state management model of the Compose Runtime, allowing developers to write declarative UI code in Java using a Kotlin-like syntax.

## Sample Usage

The following example demonstrates how a developer writes UI code using this plugin. While it appears to be a standard Java class with a peculiar syntax for method calls, the plugin transforms it into a fully functional Compose component during compilation.

### Source Code
```java
package com.example.ui;

import androidx.compose.runtime.Composable;
import androidx.compose.runtime.Composer;

public class UserProfile {

    /**
     * A Composable function defined in Java.
     * The plugin automatically injects the necessary 'Composer' and 'changed' 
     * parameters into this method signature.
     */
    @Composable
    public void ProfileScreen(String userName, boolean showDetails) {
        
        // SYNTAX LOWERING:
        // Standard Java does not allow "Column { ... }".
        // The plugin intercepts this parser pattern and converts it 
        // into "Column(() -> { ... })".
        Column {
            
            // STATE & LOGIC:
            // Standard Java control flow works naturally within the UI definition.
            Text("User: " + userName);

            if (showDetails) {
                // NESTED LAYOUTS:
                // Groups are automatically generated for this block 
                // to maintain the slot table structure.
                Row {
                    Icon("user_badge.png");
                    Text("Verified Member");
                }
            }

            // EVENT HANDLING:
            // Standard Java lambdas are used for callbacks.
            Button(() -> System.out.println("Logout Clicked")) {
                Text("Logout");
            }
        }
    }
}
```

### Transformed Code (AST Representation)
*The compiler transforms the code into the following structure before generating bytecode:*

```java
@Composable
public void ProfileScreen(String userName, boolean showDetails, Composer composer, int changed) {
    // 1. Restart Group (Recomposition Scope)
    composer = composer.startRestartGroup(123456);

    // 2. Skipping Logic (Optimization)
    if (changed != 0 && (changed & 1) == 0 && composer.getSkipping()) {
        composer.skipToGroupEnd();
    } else {
        // 3. Lowered Syntax
        Column(() -> {
            // 4. Replaceable Group (Slot Table Navigation)
            composer.startReplaceableGroup(987654);
            
            // 5. Parameter Injection (Stability Bitmask: 0=Unstable, 1=Stable)
            Text("User: " + userName, composer, 0);

            if (showDetails) {
                Row(() -> {
                     composer.startReplaceableGroup(111222);
                     Icon("user_badge.png", composer, 1);
                     Text("Verified Member", composer, 1);
                     composer.endReplaceableGroup();
                }, composer, 0);
            }
            composer.endReplaceableGroup();
        }, composer, 0);
    }

    // 6. Recursive Recomposition Hook
    composer.endRestartGroup().updateScope((c, i) -> ProfileScreen(userName, showDetails, c, i | 1));
}
```

## Core Capabilities

*   **Syntax Transformation**: Converts trailing lambda syntax (e.g., `Column { }`) into standard Java lambda expressions.
*   **Context Injection**: Automatically injects the `Composer` context and `changed` bitmasks into method signatures and invocation sites.
*   **Slot Table Management**: Wraps composable functions and lambdas with `startReplaceableGroup` and `endReplaceableGroup` calls to maintain the UI tree structure.
*   **Smart Recomposition**: Implements stability inference to calculate bitmasks, allowing the runtime to skip execution of functions when parameters have not changed.
*   **Kotlin ABI Compatibility**: Supports interoperability with existing Kotlin Composable libraries by handling synthetic default arguments and the `$default` method suffix convention.

## How is this Possible?

To the standard Java compiler, the source code implies syntax errors or undefined symbols. The plugin bridges the gap between this declarative DSL and the imperative JVM bytecode through three specific mechanisms:

### 1. Exploiting Parser Recovery
In standard Java, an identifier followed immediately by a block (e.g., `Column { }`) is invalid. However, the OpenJDK parser generates a specific AST structure for this "error"—typically an `ExpressionStatement` (the identifier) followed disjointly by a `Block`.

This plugin runs immediately after the parse phase. It scans the AST for this specific error pattern. When found, it "lowers" the syntax by surgically removing the disjoint nodes and replacing them with a valid `JCMethodInvocation` accepting a `JCLambda`. Effectively, the compiler "heals" the syntax error before it attempts to analyze types.

### 2. Context Propagation
Jetpack Compose requires a `Composer` object to be passed down the entire UI tree to manage state. The plugin scans the AST for methods annotated with `@Composable`. It mutates the method definition (`JCMethodDecl`) to append the `Composer` parameter. Simultaneously, it visits every method call within that body. If a call targets another Composable function, the plugin injects the local `Composer` reference into the arguments list. This makes the context passing implicit to the developer but explicit in the bytecode.

### 3. Slot Table Bridging
The Compose Runtime does not execute a UI; it executes a series of gap-buffer operations to emit or update a tree. The plugin wraps every lambda body and method body with `composer.startReplaceableGroup(int key)` and `composer.endReplaceableGroup()`.
*   **The Key**: To ensure the runtime can identify which UI node corresponds to which line of code across recompilations, the plugin generates a deterministic hash based on the **Source File Path** and the **Character Offset** of the AST node.

## Architecture

The compiler plugin operates as a pipeline of sequential Tree Translators. These passes modify the AST after the parsing phase (`TaskListener.finished` event) but prior to type attribution or bytecode generation.

### 1. Syntax Lowering (`KotlinSyntaxFixer`)
Scans for the parser error pattern of `Ident { Block }`.
*   **Transformation**: `Column { stmt; }` $\rightarrow$ `Column(() -> { stmt; })`
*   **Note**: Source positions are propagated to the generated nodes to ensure deterministic group key generation in subsequent passes.

### 2. Definition Transformation (`ComposableDefinitionTransformer`)
Modifies method definitions annotated with `@Composable`.
*   **Transformation**: `void MyScreen(String text)` $\rightarrow$ `void MyScreen(String text, Composer composer, int changed)`

### 3. Group Injection (`ComposeGroupTransformer`)
Traverses lambdas within composable functions to inject Slot Table navigation markers.
*   **Mechanism**: Uses `treeMaker.at(pos)` to generate keys based on original source positions.
*   **Transformation**: Wraps lambda bodies with `startReplaceableGroup` / `endReplaceableGroup`.

### 4. Call Site Injection (`ComposeParameterInjector`)
Updates method invocations to pass the runtime parameters. It calculates a bitmask representing the stability of the arguments provided.
*   **Bit Logic**: `0` for unstable (variables), `1` for stable (literals).
*   **Transformation**: `Text("Value")` $\rightarrow$ `Text("Value", composer, 1)`

### 5. Method Body Transformation (`ComposableBodyTransformer`)
Wraps the execution of composable functions to handle skipping and recomposition.
*   **Skipping**: Checks the `changed` bitmask to see if execution can be skipped.
*   **Restarting**: Registers a scope update hook (`updateScope`) to allow the runtime to re-invoke the function when state changes.

### 6. Kotlin Interoperability (`KotlinInteropInjector`)
Handles calls to Composable functions defined in Kotlin.
*   **Mechanism**: Detects missing arguments in calls to Kotlin binaries, pads them with `null`, calculates the default bitmask, and redirects the call to the underlying `$default` synthetic method.

## Build Configuration

This project relies on the `com.sun.tools.javac` package, which is exported by the JDK compiler module.

### Prerequisites
*   JDK 11 or higher.
*   Compose Runtime dependencies (JARs): `compose-runtime`, `kotlin-stdlib`, `kotlinx-coroutines-core`.

### Execution
The compiler can be invoked programmatically via the `javax.tools.JavaCompiler` API or as a CLI plugin.

```bash
# Example Compilation
javac -cp "libs/*" -Xplugin:ComposePlugin Main.java
```

## Limitations & Constraints

### 1. IDE Support
Because this plugin leverages `javac` internals to "fix" syntax that is technically invalid Java, standard IDEs (IntelliJ, Eclipse) will flag the code as erroneous. The IDE parser is separate from the build system compiler and does not run this plugin during real-time code analysis.

### 2. Java Module System (Jigsaw)
This implementation accesses non-exported packages (`com.sun.tools.javac.*`). Running this requires explicit JVM arguments:
*   `--add-exports jdk.compiler/com.sun.tools.javac.tree=ALL-UNNAMED`
*   `--add-exports jdk.compiler/com.sun.tools.javac.util=ALL-UNNAMED`
*   `--add-exports jdk.compiler/com.sun.tools.javac.code=ALL-UNNAMED`

### 3. Type Attribution Timing
Currently, stability inference (calculating the `changed` mask) is done based on simple heuristics (is the argument a Literal?). A production-grade implementation would need to run stability inference *after* the `ATTRIBUTE` phase to determine if a specific object type is marked `@Stable` or `@Immutable` in the classpath, which complicates the AST modification process as trees become immutable after attribution in some contexts.
