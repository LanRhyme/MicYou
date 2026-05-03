# MicYou Plugin Development Skill

You are an expert in developing plugins for MicYou, a Kotlin Multiplatform application. Use this skill to help developers create, debug, and package MicYou plugins.

## Overview

MicYou plugins are Kotlin/JVM applications that extend the functionality of the MicYou app. Plugins run in a sandboxed environment and communicate with the host application through well-defined interfaces.

## Plugin Architecture

### Core Interfaces

#### Plugin Interface
Every plugin must implement the `Plugin` interface:

```kotlin
interface Plugin {
    val manifest: PluginManifest
    fun onLoad(context: PluginContext) {}
    fun onEnable() {}
    fun onDisable() {}
    fun onUnload() {}
}
```

#### PluginManifest
Contains plugin metadata:

```kotlin
@Serializable
data class PluginManifest(
    val id: String,              // Reverse domain name format
    val name: String,            // Display name
    val version: String,         // Semantic version
    val author: String,          // Author name
    val description: String = "",
    val tags: List<String> = emptyList(),
    val platform: PluginPlatform = PluginPlatform.BOTH,
    val minApiVersion: String,   // Minimum API version required
    val permissions: List<String> = emptyList(),
    val mainClass: String        // Fully qualified class name
)
```

#### PluginContext
Provides runtime environment access:

```kotlin
interface PluginContext {
    val pluginId: String
    val pluginDataDir: String
    val localization: PluginLocalization
    val appLocalization: PluginLocalization
    
    // Data storage
    fun getString(key: String, defaultValue: String): String
    fun putString(key: String, value: String)
    fun getBoolean(key: String, defaultValue: Boolean): Boolean
    fun putBoolean(key: String, value: Boolean)
    fun getInt(key: String, defaultValue: Int): Int
    fun putInt(key: String, value: Int)
    fun getFloat(key: String, defaultValue: Float): Float
    fun putFloat(key: String, value: Float)
    
    // Logging
    fun log(message: String)
    fun logError(message: String, throwable: Throwable? = null)
}
```

### Optional Interfaces

#### PluginUIProvider
For plugins that need UI components:

```kotlin
interface PluginUIProvider {
    val hasMainWindow: Boolean get() = false
    val hasDialog: Boolean get() = false
    val windowWidth: Dp get() = 600.dp
    val windowHeight: Dp get() = 500.dp
    val windowTitle: String get() = "Plugin Window"
    val windowResizable: Boolean get() = true
    
    @Composable
    fun MainWindow(onClose: () -> Unit) {}
    
    @Composable
    fun DialogContent(onDismiss: () -> Unit) {}
}
```

#### PluginSettingsProvider
For plugins that need settings UI:

```kotlin
interface PluginSettingsProvider {
    @Composable
    fun SettingsContent()
}
```

## Plugin Package Format

### Directory Structure
```
my-plugin.micyou-plugin.zip
├── plugin.json        # Required: Plugin manifest
├── plugin.jar         # Required: Compiled Kotlin/JVM code
├── assets/            # Optional: Resource files
│   ├── images/
│   └── sounds/
└── icon.png           # Optional: 128x128 PNG icon
```

### plugin.json Schema
```json
{
  "id": "com.example.myplugin",
  "name": "My Plugin",
  "version": "1.0.0",
  "author": "Developer Name",
  "description": "Plugin description",
  "tags": ["utility", "audio"],
  "platform": "both",
  "minApiVersion": "1.0.0",
  "permissions": ["network", "storage"],
  "mainClass": "com.example.myplugin.MyPlugin"
}
```

## Available Permissions

| Permission | ID | Risk Level | Description |
|------------|-----|------------|-------------|
| Storage | `storage` | Low | Access plugin-specific storage |
| Network | `network` | Medium | HTTP/HTTPS requests |
| Camera | `camera` | High | Camera device access |
| Microphone | `microphone` | High | Microphone device access |

## Platform Support

| Value | Description |
|-------|-------------|
| `mobile` | Android only |
| `desktop` | JVM Desktop only |
| `both` | Both platforms required |

## Development Workflow

### 1. Project Setup

Create a new Kotlin/JVM Gradle project:

```kotlin
// build.gradle.kts
plugins {
    kotlin("jvm") version "2.2.20"
    kotlin("plugin.serialization") version "2.2.20"
    kotlin("plugin.compose") version "2.2.20"
    id("org.jetbrains.compose") version "1.7.3"
}

group = "com.example"
version = "1.0.0"

repositories {
    mavenCentral()
    google()
}

dependencies {
    compileOnly(files("path/to/plugin-api-jvm.jar"))
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.0")
    
    implementation(compose.runtime)
    implementation(compose.foundation)
    implementation(compose.material3)
    implementation(compose.ui)
}
```

### 2. Implement Plugin Class

```kotlin
package com.example.myplugin

import com.lanrhyme.micyou.plugin.*

class MyPlugin : Plugin, PluginUIProvider {
    private var context: PluginContext? = null
    
    override val manifest = PluginManifest(
        id = "com.example.myplugin",
        name = "My Plugin",
        version = "1.0.0",
        author = "Developer",
        description = "A sample plugin",
        minApiVersion = "1.0.0",
        mainClass = "com.example.myplugin.MyPlugin"
    )
    
    override val hasMainWindow = true
    
    override fun onLoad(context: PluginContext) {
        this.context = context
        context.log("Plugin loaded")
    }
    
    override fun onEnable() {
        context?.log("Plugin enabled")
    }
    
    override fun onDisable() {
        context?.log("Plugin disabled")
    }
    
    @Composable
    override fun MainWindow(onClose: () -> Unit) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("My Plugin Window")
            Button(onClick = onClose) {
                Text("Close")
            }
        }
    }
}
```

### 3. Create plugin.json

Place in `src/main/resources/plugin.json`:

```json
{
  "id": "com.example.myplugin",
  "name": "My Plugin",
  "version": "1.0.0",
  "author": "Developer",
  "description": "A sample plugin",
  "minApiVersion": "1.0.0",
  "mainClass": "com.example.myplugin.MyPlugin"
}
```

### 4. Build and Package

```bash
# Build JAR
./gradlew jar

# Create plugin package
mkdir plugin-package
cp build/libs/my-plugin.jar plugin-package/plugin.jar
cp src/main/resources/plugin.json plugin-package/
cd plugin-package && zip -r ../my-plugin.micyou-plugin.zip .
```

## Best Practices

### Lifecycle Management
- Store `PluginContext` in `onLoad` for later use
- Release resources in `onDisable` and `onUnload`
- Use state flags to prevent duplicate initialization

### Data Storage
- Use `PluginContext` methods for simple key-value storage
- Use `pluginDataDir` for file-based storage
- Never access files outside plugin's data directory

### UI Development
- Use Material3 components
- Implement responsive layouts
- Handle loading and error states

### Error Handling
- Log errors with `context.logError()`
- Provide graceful degradation for missing permissions
- Handle exceptions in Composable functions

### Security
- Only request necessary permissions
- Validate all external inputs
- Don't expose sensitive data in logs

## Common Patterns

### Settings with Persistence

```kotlin
class MyPlugin : Plugin, PluginSettingsProvider {
    private var context: PluginContext? = null
    
    @Composable
    override fun SettingsContent() {
        var serverUrl by remember { 
            mutableStateOf(context?.getString("serverUrl", "") ?: "") 
        }
        
        Column {
            OutlinedTextField(
                value = serverUrl,
                onValueChange = { 
                    serverUrl = it
                    context?.putString("serverUrl", it)
                },
                label = { Text("Server URL") }
            )
        }
    }
}
```

### Async Data Loading

```kotlin
@Composable
override fun MainWindow(onClose: () -> Unit) {
    var data by remember { mutableStateOf<List<Item>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    
    LaunchedEffect(Unit) {
        data = loadDataFromNetwork()
        loading = false
    }
    
    if (loading) {
        CircularProgressIndicator()
    } else {
        LazyColumn {
            items(data) { item ->
                ItemRow(item)
            }
        }
    }
}
```

## Troubleshooting

### Plugin Not Loading
- Check `mainClass` matches the actual class name
- Verify `plugin.json` is in the JAR root
- Ensure all required fields are present

### Permission Denied
- Add required permissions to `plugin.json`
- Check permission ID spelling

### UI Not Showing
- Set `hasMainWindow = true` or `hasDialog = true`
- Implement the corresponding Composable method

### ClassNotFoundException
- Include all dependencies in the JAR (shaded JAR)
- Check for version conflicts

## Reference Documentation

For detailed API documentation, refer to:
- `docs/plugin-api/README.md` - Quick start guide
- `docs/plugin-api/api-reference.md` - Complete API reference
- `docs/plugin-api/plugin-format.md` - Package format specification
- `docs/plugin-api/permissions.md` - Permission system details
- `docs/plugin-api/best-practices.md` - Development best practices
